/*
 * Copyright © 2026 IKE Network (support@ike.network)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package network.ike.dashboard.app;

import network.ike.dashboard.download.ArtifactCoordinates;
import network.ike.dashboard.download.DownloadListener;
import network.ike.dashboard.download.MavenDataStoreDownloadTask;
import network.ike.dashboard.journal.UsageJournal;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Installs a Komet build beside the ones already installed, never over one, so any version stays
 * available to open what it reads. A jlink image from Nexus is unpacked into
 * {@code <install location>/komet-<version>} and run by its own launcher; an installer package from
 * GitHub has its application taken out, signature checked, and placed in the install location, with
 * no installer and no administrator password. Unpacking uses the macOS tools, which keep the
 * launcher's execute bit and the image's symbolic links.
 */
public final class KometInstaller {

    /** The file an installed image keeps its provenance in; its presence marks a Komet image. */
    public static final String PROPERTIES = "komet-app.properties";

    private static final Duration TIMEOUT = Duration.ofMinutes(30);

    private KometInstaller() {
    }

    /**
     * Installs a Komet build.
     *
     * @param http            the client
     * @param release         the build
     * @param installLocation where Komets are installed
     * @param localRepository the local Maven repository, which keeps a Nexus download
     * @param progress        told what is happening, in words
     * @return the installed Komet
     * @throws Exception if it cannot be downloaded, checked or unpacked
     */
    public static KometInstallation install(HttpClient http, KometRelease release, Path installLocation,
                                            Path localRepository, Consumer<String> progress) throws Exception {
        return install(http, release, installLocation, localRepository, progress,
                new Progress(progress, "Downloading Komet " + release.version()), cancel -> { });
    }

    /**
     * Installs a Komet build, showing its progress on a transfer the person can cancel while it downloads.
     *
     * @param http            the client
     * @param release         the build
     * @param installLocation where Komets are installed
     * @param localRepository the local Maven repository, which keeps a Nexus download
     * @param transfer        shows the progress, and cancels the download when asked
     * @return the installed Komet
     * @throws Exception if it cannot be downloaded, checked or unpacked, or was cancelled
     */
    public static KometInstallation install(HttpClient http, KometRelease release, Path installLocation,
                                            Path localRepository, Transfer transfer) throws Exception {
        return install(http, release, installLocation, localRepository, transfer::phase, transfer, transfer::cancelWith);
    }

    private static KometInstallation install(HttpClient http, KometRelease release, Path installLocation,
                                             Path localRepository, Consumer<String> progress,
                                             DownloadListener<Path> listener, Consumer<Runnable> cancelWith)
            throws Exception {
        Files.createDirectories(installLocation);
        return switch (release.source()) {
            case NEXUS -> installImage(http, release, installLocation, localRepository, progress, listener, cancelWith);
            case GITHUB -> installPackage(release, installLocation, progress);
        };
    }

    private static KometInstallation installImage(HttpClient http, KometRelease release, Path installLocation,
                                                  Path localRepository, Consumer<String> progress,
                                                  DownloadListener<Path> listener, Consumer<Runnable> cancelWith)
            throws Exception {
        Path target = installLocation.resolve(imageFolder(release.version()));
        refuseExisting(target);
        if (target.toAbsolutePath().toString().chars().anyMatch(Character::isWhitespace)) {
            // JReleaser's launchKomet puts $APP_HOME/conf/log4j2.xml in its Java options unquoted, so a
            // space anywhere in the path splits it and Java takes the rest for the main class.
            throw new IOException("Komet's launcher cannot run from a path with a space in it: " + target
                    + ". Choose an install location without one.");
        }
        Coordinate coordinate = release.coordinate().orElseThrow();
        ArtifactCoordinates artifact = new ArtifactCoordinates(coordinate.groupId(), coordinate.artifactId());
        Path cached = MavenDataStoreDownloadTask.localRepositoryPath(localRepository, artifact, coordinate.version(),
                coordinate.version(), release.classifier());
        Path staging = Files.createTempDirectory(installLocation, ".komet-" + release.version() + "-");
        try {
            Path zip = staging.resolve("komet-app.zip");
            MavenDataStoreDownloadTask task = MavenDataStoreDownloadTask.placeAsFileTask(http, release.uri(), cached, zip,
                    MavenDataStoreDownloadTask.AssetVerification.NONE);
            task.addListener(listener);
            cancelWith.accept(task::cancel);
            task.call();
            verify(cached, release);
            progress.accept("Unpacking Komet " + release.version() + " …");
            Path unpacked = Files.createDirectories(staging.resolve("unpacked"));
            run(List.of("ditto", "-x", "-k", zip.toString(), unpacked.toString()));
            Path image = only(unpacked, path -> Files.isExecutable(path.resolve("bin/launchKomet")),
                    "no Komet image with bin/launchKomet");
            Properties properties = new Properties();
            properties.setProperty("version", release.version());
            properties.setProperty("coordinate", coordinate + ":zip:" + release.classifier());
            properties.setProperty("source", release.uri().toString());
            release.checksum().ifPresent(checksum -> properties.setProperty("checksum", checksum));
            properties.setProperty("installedAt", Instant.now().toString());
            try (OutputStream out = Files.newOutputStream(image.resolve(PROPERTIES))) {
                properties.store(out, "Installed by the IKE Dashboard");
            }
            Files.move(image, target);
            UsageJournal.record(localRepository, UsageJournal.DASHBOARD, LocalDate.now(), List.of(cached));
        } finally {
            deleteTree(staging);
        }
        return KometInstallations.read(target)
                .orElseThrow(() -> new IOException("Installed " + target + " but cannot read it back"));
    }

    private static KometInstallation installPackage(KometRelease release, Path installLocation,
                                                    Consumer<String> progress) throws Exception {
        Path staging = Files.createTempDirectory(installLocation, ".komet-" + release.version() + "-");
        try {
            Path pkg = staging.resolve("komet.pkg");
            progress.accept("Downloading Komet " + release.version() + " from GitHub"
                    + (release.sizeBytes() > 0 ? " (" + DashboardCommand.size(release.sizeBytes()) + ")" : "") + " …");
            // GitHub serves release assets through a redirect to its storage host.
            HttpClient redirecting = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
            HttpResponse<Path> response = redirecting.send(HttpRequest.newBuilder(release.uri()).timeout(TIMEOUT).GET().build(),
                    HttpResponse.BodyHandlers.ofFile(pkg));
            if (response.statusCode() != 200) {
                throw new IOException("GitHub answered " + response.statusCode() + " for " + release.uri());
            }
            verify(pkg, release);
            progress.accept("Unpacking Komet " + release.version() + " …");
            Path expanded = staging.resolve("expanded");
            run(List.of("pkgutil", "--expand-full", pkg.toString(), expanded.toString()));
            Path app;
            try (Stream<Path> found = Files.find(expanded, 3, (path, attributes) -> attributes.isDirectory()
                    && path.getFileName().toString().endsWith(".app"))) {
                app = found.findFirst().orElseThrow(() -> new IOException("The package holds no application"));
            }
            run(List.of("codesign", "--verify", "--deep", "--strict", app.toString()));
            Path target = installLocation.resolve(app.getFileName().toString());
            refuseExisting(target);
            Files.move(app, target);
            return KometInstallations.read(target)
                    .orElseThrow(() -> new IOException("Installed " + target + " but it is not a Komet"));
        } finally {
            deleteTree(staging);
        }
    }

    /**
     * The folder an image of a version is installed in: no spaces, which the image's launcher cannot run
     * from.
     *
     * @param version the app version
     * @return such as {@code komet-26.1024.1356}
     */
    static String imageFolder(String version) {
        return "komet-" + version;
    }

    /**
     * Checks a download against the checksum its source published.
     *
     * @param file    the download
     * @param release what it should be
     * @throws IOException if it does not match
     */
    static void verify(Path file, KometRelease release) throws IOException {
        verify(file, release, "Komet " + release.version());
    }

    /**
     * Checks a download against the checksum its source published.
     *
     * @param file    the download
     * @param release what it should be
     * @param what    what it is, for the refusal
     * @throws IOException if it does not match
     */
    static void verify(Path file, KometRelease release, String what) throws IOException {
        Optional<String> expected = release.checksum();
        if (expected.isEmpty()) {
            return;
        }
        String[] parts = expected.get().split(":", 2);
        String algorithm = parts[0].equalsIgnoreCase("sha1") ? "SHA-1" : "SHA-256";
        String actual = digest(file, algorithm);
        if (!actual.equalsIgnoreCase(parts[1])) {
            throw new IOException("The download of " + what + " does not match its published "
                    + parts[0] + " (" + parts[1] + "); got " + actual);
        }
    }

    static String digest(Path file, String algorithm) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buffer = new byte[1 << 16];
                for (int read; (read = in.read(buffer)) > 0; ) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    private static void refuseExisting(Path target) throws IOException {
        if (Files.exists(target)) {
            throw new IOException(target.getFileName() + " is already installed in " + target.getParent());
        }
    }

    static Path only(Path directory, java.util.function.Predicate<Path> test, String missing) throws IOException {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.filter(test).findFirst().orElseThrow(() -> new IOException(missing));
        }
    }

    static void run(List<String> command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes()).strip();
        if (process.waitFor() != 0) {
            throw new IOException(command.getFirst() + " failed" + (output.isEmpty() ? "" : ": " + output));
        }
    }

    static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        List<Path> paths;
        try (Stream<Path> walk = Files.walk(root)) {
            paths = new ArrayList<>(walk.toList());
        }
        for (int i = paths.size() - 1; i >= 0; i--) {
            Files.deleteIfExists(paths.get(i));
        }
    }

    /** Turns a download's progress into words. */
    record Progress(Consumer<String> progress, String title) implements DownloadListener<Path> {
        @Override
        public void updateValue(Path result) {
        }

        @Override
        public void updateMessage(String message) {
        }

        @Override
        public void updateTitle(String title) {
        }

        @Override
        public void updateProgress(double workDone, double max) {
            if (max > 0) {
                progress.accept(title + " … " + Math.round(100 * workDone / max) + "%");
            }
        }
    }
}
