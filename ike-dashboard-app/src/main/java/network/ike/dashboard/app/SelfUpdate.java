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

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.UserDefinedFileAttributeView;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The dashboard updates itself (decision 2 of the {@code dev-ike-dashboard} topic: plain Java, settled
 * by KEC 2026-10-23). It finds the newest {@code network.ike.dashboard:ike-dashboard-app} zip on Nexus
 * as it finds Komet's, downloads it through the local repository, checks the {@code .sha1} Nexus keeps
 * beside it, and unpacks it with {@code ditto}. A file Java downloads carries no quarantine, so
 * Gatekeeper never assesses it unless asked: the dashboard asks, with {@code codesign} and
 * {@code spctl}, and installs nothing either refuses.
 *
 * <p>A running application should not replace itself. A helper waits for the dashboard to quit, moves
 * the old application aside and the new one in, and opens it. The old one is kept, in
 * {@value #WORK_FOLDER}{@code /previous} beside the new one, until the new one has started once.
 *
 * <p>Where the dashboard runs decides where the new one goes: in place when its folder can be written;
 * in {@code ~/Applications} when it cannot, such as an administrator's {@code /Applications} or a disk
 * image. One opened where it was downloaded runs translocated, from a randomized read-only copy, and is
 * first moved into {@code ~/Applications}.
 */
public final class SelfUpdate {

    /** The dashboard's group. */
    public static final String GROUP = "network.ike.dashboard";

    /** The dashboard's application: the signed, notarized {@code .app}, zipped. */
    public static final String ARTIFACT = "ike-dashboard-app";

    /** The bundle identifier jpackage gives the application. */
    public static final String BUNDLE_IDENTIFIER = "network.ike.dashboard";

    /** The application's folder name. */
    public static final String BUNDLE_NAME = "IKE Dashboard.app";

    /** The folder beside the application that holds a staged update and the previous application. */
    public static final String WORK_FOLDER = ".ike-dashboard-update";

    /** The property jpackage's launcher sets to its own path, inside the application. */
    static final String APP_PATH_PROPERTY = "jpackage.app-path";

    private static final String QUARANTINE = "com.apple.quarantine";

    /** Waits for the dashboard to quit, swaps the applications, and runs the rest of its arguments. */
    private static final String SWAP_SCRIPT = """
            #!/bin/sh
            # Written by the IKE Dashboard's self-update: replaces the dashboard once it has quit.
            pid="$1"; staged="$2"; target="$3"; previous="$4"
            shift 4
            waited=0
            while kill -0 "$pid" 2>/dev/null; do
                sleep 0.2
                waited=$((waited + 1))
                if [ "$waited" -gt 3000 ]; then
                    echo "The dashboard (process $pid) did not quit in ten minutes; nothing was replaced."
                    exit 1
                fi
            done
            rm -rf "$previous"
            mkdir -p "$(dirname "$previous")"
            if [ -e "$target" ] && ! mv "$target" "$previous"; then
                echo "Could not move $target aside; nothing was replaced."
                exit 1
            fi
            if ! mv "$staged" "$target"; then
                echo "Could not move $staged to $target; putting the previous one back."
                if [ -e "$previous" ]; then
                    mv "$previous" "$target"
                fi
                exit 1
            fi
            echo "Replaced $target"
            exec "$@"
            """;

    private SelfUpdate() {
    }

    /**
     * The running dashboard: its application and version.
     *
     * @param bundle  the {@code .app} folder
     * @param version its {@code CFBundleShortVersionString}
     */
    public record Running(Path bundle, String version) {
    }

    /** Where the new dashboard goes, by where the running one is. */
    public enum Location {
        /** Over the running one, whose folder can be written. */
        IN_PLACE,
        /** Into {@code ~/Applications}, since the running one's folder cannot be written. */
        APPLICATIONS,
        /** Into {@code ~/Applications} once moved there, since macOS runs this one from a read-only copy. */
        TRANSLOCATED
    }

    /**
     * Where the dashboard runs and where an update of it goes.
     *
     * @param running  the running dashboard
     * @param location how the two relate
     * @param target   the application the update replaces or becomes
     */
    public record Placement(Running running, Location location, Path target) {

        /**
         * The folder beside the target that holds a staged update and the previous application.
         *
         * @return the folder
         */
        public Path work() {
            return target.resolveSibling(WORK_FOLDER);
        }

        /**
         * The previous application, kept until the new one has started once.
         *
         * @return where it is kept
         */
        public Path previous() {
            return work().resolve("previous").resolve(target.getFileName());
        }
    }

    /**
     * What the self-update check found: where the dashboard runs and the newest build on Nexus.
     *
     * @param placement where the dashboard runs and where an update goes
     * @param newest    the newest build on Nexus, once asked
     */
    public record State(Placement placement, Optional<KometRelease> newest) {

        /**
         * The build to offer: the newest, when it is newer than the running dashboard and the dashboard
         * can be updated where it runs.
         *
         * @return the build, if one is offered
         */
        public Optional<KometRelease> offered() {
            return placement.location() == Location.TRANSLOCATED ? Optional.empty()
                    : newest.filter(release -> release.newerThan(placement.running().version()));
        }
    }

    /**
     * The running dashboard, when it runs as an application; empty when it runs from a build, which
     * does not update itself.
     *
     * @return the dashboard
     */
    public static Optional<Running> running() {
        String launcher = System.getProperty(APP_PATH_PROPERTY);
        if (launcher == null || launcher.isBlank()) {
            return Optional.empty();
        }
        // <bundle>/Contents/MacOS/<launcher>
        Path bundle = Path.of(launcher).toAbsolutePath().getParent();
        for (int i = 0; i < 2 && bundle != null; i++) {
            bundle = bundle.getParent();
        }
        return bundle == null ? Optional.empty() : read(bundle);
    }

    /**
     * Reads an application as a dashboard.
     *
     * @param bundle an {@code .app} folder
     * @return the dashboard, or empty when the folder is not one or cannot be read
     */
    public static Optional<Running> read(Path bundle) {
        Path plist = bundle.resolve("Contents").resolve("Info.plist");
        if (!Files.isRegularFile(plist)) {
            return Optional.empty();
        }
        try (InputStream in = new BufferedInputStream(Files.newInputStream(plist))) {
            if (KometInstallations.binary(in)) {
                return Optional.empty();
            }
            Map<String, String> keys = KometInstallations.plistStrings(in);
            String version = keys.get("CFBundleShortVersionString");
            return BUNDLE_IDENTIFIER.equals(keys.get("CFBundleIdentifier")) && version != null
                    ? Optional.of(new Running(bundle, version)) : Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * Where an update of a running dashboard goes.
     *
     * @param running the running dashboard
     * @param home    the user's home folder
     * @return the placement
     */
    public static Placement place(Running running, Path home) {
        Path applications = home.resolve("Applications").resolve(BUNDLE_NAME);
        Path bundle = running.bundle();
        if (translocated(bundle)) {
            return new Placement(running, Location.TRANSLOCATED, applications);
        }
        Path folder = bundle.toAbsolutePath().getParent();
        if (folder != null && Files.isWritable(folder) && Files.isWritable(bundle)) {
            return new Placement(running, Location.IN_PLACE, bundle);
        }
        return new Placement(running, Location.APPLICATIONS, applications);
    }

    /**
     * Whether macOS runs an application translocated: from the randomized, read-only copy Gatekeeper
     * makes of a quarantined application opened where it was downloaded.
     *
     * @param bundle the application
     * @return {@code true} when translocated
     */
    static boolean translocated(Path bundle) {
        return bundle.toAbsolutePath().toString().contains("/AppTranslocation/");
    }

    /**
     * The newest dashboard on Nexus for this machine, from the public repository without credentials.
     *
     * @param http the client
     * @return the build, if one is deployed
     * @throws IOException          if Nexus cannot be asked
     * @throws InterruptedException if interrupted
     */
    public static Optional<KometRelease> newest(HttpClient http) throws IOException, InterruptedException {
        return newest(http, KometReleases.PUBLIC_URL, KometReleases.platformClassifier());
    }

    /**
     * The newest dashboard in a repository for a platform.
     *
     * @param http          the client
     * @param repositoryUrl the repository
     * @param classifier    the platform
     * @return the build, if one is deployed
     * @throws IOException          if the repository cannot be asked
     * @throws InterruptedException if interrupted
     */
    public static Optional<KometRelease> newest(HttpClient http, String repositoryUrl, String classifier)
            throws IOException, InterruptedException {
        return KometReleases.newestOnNexus(http, repositoryUrl, GROUP, ARTIFACT, classifier);
    }

    /**
     * Downloads, checks and unpacks a dashboard build beside the target, showing its progress on a
     * transfer the person can cancel while it downloads.
     *
     * @param http            the client
     * @param release         the build
     * @param placement       where it goes
     * @param localRepository the local Maven repository, which keeps the download
     * @param transfer        shows the progress, and cancels the download when asked
     * @return the staged application, checked, ready to swap in
     * @throws Exception if it cannot be downloaded, does not match its checksum, or is refused
     */
    public static Path stage(HttpClient http, KometRelease release, Placement placement, Path localRepository,
                             Transfer transfer) throws Exception {
        return stage(http, release, placement, localRepository, transfer::phase, transfer, transfer::cancelWith);
    }

    /**
     * Downloads, checks and unpacks a dashboard build beside the target.
     *
     * @param http            the client
     * @param release         the build
     * @param placement       where it goes
     * @param localRepository the local Maven repository, which keeps the download
     * @param progress        told what is happening, in words
     * @return the staged application, checked, ready to swap in
     * @throws Exception if it cannot be downloaded, does not match its checksum, or is refused
     */
    public static Path stage(HttpClient http, KometRelease release, Placement placement, Path localRepository,
                             Consumer<String> progress) throws Exception {
        return stage(http, release, placement, localRepository, progress,
                new KometInstaller.Progress(progress, "Downloading the IKE Dashboard " + release.version()), cancel -> { });
    }

    private static Path stage(HttpClient http, KometRelease release, Placement placement, Path localRepository,
                              Consumer<String> progress, DownloadListener<Path> listener, Consumer<Runnable> cancelWith)
            throws Exception {
        Path staging = freshStaging(placement);
        try {
            Coordinate coordinate = release.coordinate().orElseThrow(
                    () -> new IOException("The IKE Dashboard " + release.version() + " is not a Nexus artifact"));
            Path cached = MavenDataStoreDownloadTask.localRepositoryPath(localRepository,
                    new ArtifactCoordinates(coordinate.groupId(), coordinate.artifactId()), coordinate.version(),
                    coordinate.version(), release.classifier());
            Path zip = staging.resolve(ARTIFACT + ".zip");
            MavenDataStoreDownloadTask task = MavenDataStoreDownloadTask.placeAsFileTask(http, release.uri(), cached, zip,
                    MavenDataStoreDownloadTask.AssetVerification.NONE);
            task.addListener(listener);
            cancelWith.accept(task::cancel);
            task.call();
            KometInstaller.verify(cached, release, "the IKE Dashboard " + release.version());
            UsageJournal.record(localRepository, UsageJournal.DASHBOARD, LocalDate.now(), List.of(cached));
            progress.accept("Unpacking the IKE Dashboard " + release.version() + " …");
            Path unpacked = Files.createDirectories(staging.resolve("unpacked"));
            KometInstaller.run(List.of("ditto", "-x", "-k", zip.toString(), unpacked.toString()));
            Path app = KometInstaller.only(unpacked, path -> Files.isDirectory(path)
                    && path.getFileName().toString().endsWith(".app"), "The download holds no application");
            progress.accept("Checking the IKE Dashboard " + release.version() + " with Gatekeeper …");
            check(app, release.version());
            Files.delete(zip);
            return app;
        } catch (Exception e) {
            KometInstaller.deleteTree(staging);
            throw e;
        }
    }

    /**
     * Copies the running dashboard into the work folder of its placement, so a translocated one can be
     * moved where it can update itself. The copy is checked as a download is, then loses its
     * quarantine, so macOS opens it where it is rather than translocating it again.
     *
     * @param placement where it goes
     * @return the copy, ready to swap in
     * @throws Exception if it cannot be copied or is refused
     */
    public static Path stageRunning(Placement placement) throws Exception {
        Path staging = freshStaging(placement);
        try {
            Path copy = staging.resolve(BUNDLE_NAME);
            KometInstaller.run(List.of("ditto", placement.running().bundle().toString(), copy.toString()));
            check(copy, placement.running().version());
            unquarantine(copy);
            return copy;
        } catch (Exception e) {
            KometInstaller.deleteTree(staging);
            throw e;
        }
    }

    /**
     * Refuses an application that is not the dashboard of the version expected, that {@code codesign}
     * finds altered, or that Gatekeeper would not open.
     *
     * @param app     the application
     * @param version the version it should be
     * @throws IOException          if it is refused
     * @throws InterruptedException if interrupted
     */
    static void check(Path app, String version) throws IOException, InterruptedException {
        Running found = read(app).orElseThrow(() -> new IOException(app.getFileName() + " is not the IKE Dashboard"));
        if (!found.version().equals(version)) {
            throw new IOException(app.getFileName() + " is version " + found.version() + ", not " + version);
        }
        try {
            KometInstaller.run(List.of("codesign", "--verify", "--deep", "--strict", app.toString()));
            KometInstaller.run(List.of("spctl", "--assess", "--type", "execute", app.toString()));
        } catch (IOException e) {
            throw new IOException("Gatekeeper refuses the IKE Dashboard " + version + ": " + e.getMessage(), e);
        }
    }

    /**
     * Starts the helper that swaps the staged application in once a process has quit, then runs a
     * command, such as {@code open} on the new application. The helper outlives the dashboard; what it
     * says goes to a log.
     *
     * @param placement where the staged application goes
     * @param staged    the staged application
     * @param waitFor   the process to wait for, the dashboard's own
     * @param then      the command to run once swapped
     * @param log       where the helper writes what it did
     * @return the helper
     * @throws IOException if it cannot be started
     */
    public static Process swap(Placement placement, Path staged, long waitFor, List<String> then, Path log)
            throws IOException {
        Path script = Files.writeString(Files.createDirectories(placement.work()).resolve("swap.sh"), SWAP_SCRIPT);
        Files.createDirectories(log.toAbsolutePath().getParent());
        List<String> command = new ArrayList<>(List.of("/bin/sh", script.toString(), Long.toString(waitFor),
                staged.toString(), placement.target().toString(), placement.previous().toString()));
        command.addAll(then);
        return new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile())).start();
    }

    /**
     * The command that opens an application once swapped in.
     *
     * @param app the application
     * @return the command
     */
    public static List<String> relaunch(Path app) {
        return List.of("/usr/bin/open", app.toString());
    }

    /**
     * Removes what an update left beside a dashboard running in place: the previous application, now
     * that the new one has started, and anything staged and never swapped in. A dashboard running
     * elsewhere leaves the work folder alone, since it belongs to the one in {@code ~/Applications}.
     *
     * @param placement where the dashboard runs
     * @throws IOException if it cannot be removed
     */
    public static void cleanUp(Placement placement) throws IOException {
        if (placement.location() == Location.IN_PLACE) {
            KometInstaller.deleteTree(placement.work());
        }
    }

    private static Path freshStaging(Placement placement) throws IOException {
        Path staging = placement.work().resolve("new");
        KometInstaller.deleteTree(staging);
        return Files.createDirectories(staging);
    }

    /**
     * Removes the quarantine from an application's folder, which is what macOS translocates by.
     *
     * @param app the application
     * @throws IOException if it cannot be removed
     */
    static void unquarantine(Path app) throws IOException {
        UserDefinedFileAttributeView attributes = Files.getFileAttributeView(app, UserDefinedFileAttributeView.class);
        if (attributes != null && attributes.list().contains(QUARANTINE)) {
            attributes.delete(QUARANTINE);
        }
    }
}
