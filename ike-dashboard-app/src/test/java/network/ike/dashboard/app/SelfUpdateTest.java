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

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserDefinedFileAttributeView;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The dashboard updates itself: it finds its newest build on Nexus, refuses a download that does not
 * match its checksum or that Gatekeeper would not open, and swaps the new application in only once the
 * running one has quit, keeping the previous one. A {@link HttpServer} stands in for Nexus, serving
 * what Nexus serves; the applications are real bundles, checked by the real {@code codesign} and
 * {@code spctl}.
 */
class SelfUpdateTest {

    private static final String CLASSIFIER = "osx-aarch64";
    private static final String DIRECTORY = "/repository/ike-public/network/ike/dashboard/ike-dashboard-app/";

    private final Map<String, byte[]> served = new HashMap<>();
    private HttpServer nexus;

    @AfterEach
    void stopNexus() {
        if (nexus != null) {
            nexus.stop(0);
        }
    }

    @Test
    void theNewestVersionWithAZipForThePlatformIsTheUpdate() throws Exception {
        serve("maven-metadata.xml", metadata("26.1025.2011", "26.1025.2202", "26.1026.900"));
        serve("26.1025.2011/ike-dashboard-app-26.1025.2011-osx-aarch64.zip", new byte[] {1});
        serve("26.1025.2202/ike-dashboard-app-26.1025.2202-osx-aarch64.zip", new byte[] {1, 2, 3});
        serve("26.1025.2202/ike-dashboard-app-26.1025.2202-osx-aarch64.zip.sha1", "7767ffada1eafe8d9a9c4def4baed17d665a5e1c\n");
        serve("26.1026.900/ike-dashboard-app-26.1026.900-osx-x86_64.zip", new byte[] {1});

        KometRelease release = SelfUpdate.newest(HttpClient.newHttpClient(), repository(), CLASSIFIER).orElseThrow();

        assertThat(release.version()).as("the newest has no zip for this platform").isEqualTo("26.1025.2202");
        assertThat(release.coordinate()).contains(new Coordinate(SelfUpdate.GROUP, SelfUpdate.ARTIFACT, "26.1025.2202"));
        assertThat(release.sizeBytes()).isEqualTo(3);
        assertThat(release.checksum()).contains("sha1:7767ffada1eafe8d9a9c4def4baed17d665a5e1c");
    }

    @Test
    void aDashboardWhoseFolderCanBeWrittenIsUpdatedInPlace(@TempDir Path folder) throws IOException {
        SelfUpdate.Running running = new SelfUpdate.Running(bundle(folder, "26.1025.2011"), "26.1025.2011");

        SelfUpdate.Placement placement = SelfUpdate.place(running, folder.resolve("home"));

        assertThat(placement.location()).isEqualTo(SelfUpdate.Location.IN_PLACE);
        assertThat(placement.target()).isEqualTo(running.bundle());
        assertThat(placement.previous()).isEqualTo(folder.resolve(".ike-dashboard-update/previous/IKE Dashboard.app"));
    }

    @Test
    void aDashboardInAFolderThatCannotBeWrittenIsUpdatedIntoTheUsersApplications(@TempDir Path folder) throws IOException {
        Path locked = Files.createDirectories(folder.resolve("Applications"));
        SelfUpdate.Running running = new SelfUpdate.Running(bundle(locked, "26.1025.2011"), "26.1025.2011");
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            SelfUpdate.Placement placement = SelfUpdate.place(running, folder.resolve("home"));

            assertThat(placement.location()).isEqualTo(SelfUpdate.Location.APPLICATIONS);
            assertThat(placement.target()).isEqualTo(folder.resolve("home/Applications/IKE Dashboard.app"));
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    @Test
    void aTranslocatedDashboardIsMovedBeforeItIsOfferedAnUpdate() {
        SelfUpdate.Running running = new SelfUpdate.Running(Path.of(
                "/private/var/folders/x1/abc/T/AppTranslocation/3F1C2A/d/IKE Dashboard.app"), "26.1025.2011");

        SelfUpdate.Placement placement = SelfUpdate.place(running, Path.of("/Users/someone"));

        assertThat(placement.location()).isEqualTo(SelfUpdate.Location.TRANSLOCATED);
        assertThat(placement.target()).isEqualTo(Path.of("/Users/someone/Applications/IKE Dashboard.app"));
        assertThat(new SelfUpdate.State(placement, Optional.of(release("26.1025.2202"))).offered()).isEmpty();
    }

    @Test
    void onlyANewerBuildIsOffered() {
        SelfUpdate.Placement placement = new SelfUpdate.Placement(new SelfUpdate.Running(Path.of("/A/IKE Dashboard.app"),
                "26.1025.2202"), SelfUpdate.Location.IN_PLACE, Path.of("/A/IKE Dashboard.app"));

        assertThat(new SelfUpdate.State(placement, Optional.of(release("26.1026.15"))).offered()).isPresent();
        assertThat(new SelfUpdate.State(placement, Optional.of(release("26.1025.2202"))).offered()).isEmpty();
        assertThat(new SelfUpdate.State(placement, Optional.of(release("26.1025.2011"))).offered()).isEmpty();
        assertThat(new SelfUpdate.State(placement, Optional.empty()).offered()).isEmpty();
    }

    @Test
    void anApplicationIsTheDashboardByItsBundleIdentifier(@TempDir Path folder) throws IOException {
        Path dashboard = bundle(folder, "26.1025.2202");
        Path komet = Files.createDirectories(folder.resolve("Komet.app/Contents"));
        Files.writeString(komet.resolve("Info.plist"), plist("dev.ikm.komet.desktop", "26.1024.212"));

        assertThat(SelfUpdate.read(dashboard)).contains(new SelfUpdate.Running(dashboard, "26.1025.2202"));
        assertThat(SelfUpdate.read(komet.getParent())).isEmpty();
    }

    @Test
    void aDownloadThatDoesNotMatchItsChecksumIsRefusedAndNothingIsLeftStaged(@TempDir Path folder) throws Exception {
        assumeTrue(Files.isExecutable(Path.of("/usr/bin/ditto")), "macOS");
        byte[] zip = zip(folder, bundle(Files.createDirectories(folder.resolve("built")), "26.1025.2202"));
        KometRelease release = served("26.1025.2202", zip, "0000000000000000000000000000000000000000");
        SelfUpdate.Placement placement = inPlace(folder);

        assertThatThrownBy(() -> SelfUpdate.stage(HttpClient.newHttpClient(), release, placement,
                folder.resolve("repository"), message -> { }))
                .hasMessageContaining("The download of the IKE Dashboard 26.1025.2202 does not match");
        assertThat(placement.work().resolve("new")).doesNotExist();
    }

    @Test
    void anApplicationGatekeeperWouldNotOpenIsRefused(@TempDir Path folder) throws Exception {
        assumeTrue(Files.isExecutable(Path.of("/usr/bin/codesign")), "macOS");
        Path built = bundle(Files.createDirectories(folder.resolve("built")), "26.1025.2202");
        // Signed, but ad hoc rather than with a Developer ID and notarized: codesign accepts it, spctl does not.
        Path executable = Files.createDirectories(built.resolve("Contents/MacOS")).resolve("IKE Dashboard");
        Files.copy(Path.of("/usr/bin/true"), executable);
        KometInstaller.run(List.of("codesign", "--force", "--sign", "-", built.toString()));
        KometInstaller.run(List.of("codesign", "--verify", "--deep", "--strict", built.toString()));
        byte[] zip = zip(folder, built);
        KometRelease release = served("26.1025.2202", zip, KometInstaller.digest(folder.resolve("built.zip"), "SHA-1"));
        SelfUpdate.Placement placement = inPlace(folder);
        List<String> said = new java.util.ArrayList<>();

        assertThatThrownBy(() -> SelfUpdate.stage(HttpClient.newHttpClient(), release, placement,
                folder.resolve("repository"), said::add))
                .hasMessageStartingWith("Gatekeeper refuses the IKE Dashboard 26.1025.2202: spctl failed");
        assertThat(said).contains("Unpacking the IKE Dashboard 26.1025.2202 …");
        assertThat(placement.work().resolve("new")).doesNotExist();
        assertThat(folder.resolve("repository/network/ike/dashboard/ike-dashboard-app/26.1025.2202/"
                + "ike-dashboard-app-26.1025.2202-osx-aarch64.zip")).as("the download stays in the local repository")
                .exists();
    }

    @Test
    void anApplicationOfAnotherVersionIsRefused(@TempDir Path folder) throws IOException {
        Path built = bundle(folder, "26.1025.2011");

        assertThatThrownBy(() -> SelfUpdate.check(built, "26.1025.2202"))
                .hasMessage("IKE Dashboard.app is version 26.1025.2011, not 26.1025.2202");
    }

    @Test
    void theHelperSwapsOnlyOnceTheDashboardHasQuitAndKeepsThePreviousOne(@TempDir Path folder) throws Exception {
        SelfUpdate.Placement placement = inPlace(folder);
        Files.writeString(placement.target().resolve("which"), "old");
        Path staged = Files.createDirectories(placement.work().resolve("new/IKE Dashboard.app"));
        Files.writeString(staged.resolve("which"), "new");
        Path opened = folder.resolve("opened");
        Process dashboard = new ProcessBuilder("sleep", "2").start();

        Process helper = SelfUpdate.swap(placement, staged, dashboard.pid(), List.of("/usr/bin/touch", opened.toString()),
                folder.resolve("logs/self-update.log"));

        assertThat(helper.waitFor(1, TimeUnit.SECONDS)).as("it waits while the dashboard runs").isFalse();
        assertThat(placement.target().resolve("which")).hasContent("old");
        assertThat(helper.waitFor(30, TimeUnit.SECONDS)).isTrue();
        assertThat(helper.exitValue()).isZero();
        assertThat(placement.target().resolve("which")).hasContent("new");
        assertThat(placement.previous().resolve("which")).hasContent("old");
        assertThat(opened).as("then it opens the new one").exists();
        assertThat(folder.resolve("logs/self-update.log")).content().contains("Replaced " + placement.target());
    }

    @Test
    void theHelperPutsThePreviousOneBackWhenTheNewOneCannotBeMovedIn(@TempDir Path folder) throws Exception {
        SelfUpdate.Placement placement = inPlace(folder);
        Files.writeString(placement.target().resolve("which"), "old");
        Path opened = folder.resolve("opened");

        Process helper = SelfUpdate.swap(placement, placement.work().resolve("new/missing.app"), ProcessHandle.current()
                .pid() + 1_000_000, List.of("/usr/bin/touch", opened.toString()), folder.resolve("self-update.log"));

        assertThat(helper.waitFor(30, TimeUnit.SECONDS)).isTrue();
        assertThat(helper.exitValue()).isEqualTo(1);
        assertThat(placement.target().resolve("which")).hasContent("old");
        assertThat(opened).as("nothing new to open").doesNotExist();
    }

    @Test
    void theNewDashboardRemovesThePreviousOneOnceItHasStarted(@TempDir Path folder) throws IOException {
        SelfUpdate.Placement inPlace = inPlace(folder);
        Files.createDirectories(inPlace.previous());
        SelfUpdate.Placement elsewhere = new SelfUpdate.Placement(inPlace.running(), SelfUpdate.Location.APPLICATIONS,
                inPlace.target());

        SelfUpdate.cleanUp(elsewhere);
        assertThat(inPlace.previous()).as("another dashboard's previous one is not this one's to remove").exists();
        SelfUpdate.cleanUp(inPlace);
        assertThat(inPlace.work()).doesNotExist();
    }

    @Test
    void aMovedDashboardLosesItsQuarantine(@TempDir Path folder) throws IOException {
        Path app = bundle(folder, "26.1025.2202");
        UserDefinedFileAttributeView attributes = Files.getFileAttributeView(app, UserDefinedFileAttributeView.class);
        assumeTrue(attributes != null, "extended attributes");
        attributes.write("com.apple.quarantine", ByteBuffer.wrap("0081;00000000;Safari;".getBytes(StandardCharsets.UTF_8)));

        SelfUpdate.unquarantine(app);
        SelfUpdate.unquarantine(app);

        assertThat(attributes.list()).doesNotContain("com.apple.quarantine");
    }

    private SelfUpdate.Placement inPlace(Path folder) throws IOException {
        Path running = bundle(Files.createDirectories(folder.resolve("Applications")), "26.1025.2011");
        return new SelfUpdate.Placement(new SelfUpdate.Running(running, "26.1025.2011"), SelfUpdate.Location.IN_PLACE, running);
    }

    /** Serves a zip and its {@code .sha1} as Nexus does, and the release that names them. */
    private KometRelease served(String version, byte[] zip, String sha1) throws IOException {
        String path = version + "/ike-dashboard-app-" + version + "-" + CLASSIFIER + ".zip";
        serve(path, zip);
        return new KometRelease(version, KometRelease.Source.NEXUS, URI.create(repository() + DIRECTORY.substring(
                "/repository/ike-public/".length()) + path), zip.length, Optional.of("sha1:" + sha1), Optional.empty(),
                Optional.of(new Coordinate(SelfUpdate.GROUP, SelfUpdate.ARTIFACT, version)), CLASSIFIER);
    }

    private void serve(String path, String body) throws IOException {
        serve(path, body.getBytes(StandardCharsets.UTF_8));
    }

    private void serve(String path, byte[] body) throws IOException {
        served.put(DIRECTORY + path, body);
        if (nexus != null) {
            return;
        }
        nexus = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        nexus.createContext("/", exchange -> {
            byte[] found = served.get(exchange.getRequestURI().getPath());
            boolean head = exchange.getRequestMethod().equals("HEAD");
            if (found == null) {
                exchange.sendResponseHeaders(404, -1);
            } else if (head) {
                exchange.getResponseHeaders().add("Content-Length", Integer.toString(found.length));
                exchange.sendResponseHeaders(200, -1);
            } else {
                exchange.sendResponseHeaders(200, found.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(found);
                }
            }
            exchange.close();
        });
        nexus.start();
    }

    private String repository() {
        return "http://127.0.0.1:" + nexus.getAddress().getPort() + "/repository/ike-public/";
    }

    /** Zips an application as the installer build does, keeping its folder. */
    private static byte[] zip(Path folder, Path app) throws Exception {
        Path zip = folder.resolve(app.getParent().getFileName() + ".zip");
        KometInstaller.run(List.of("ditto", "-c", "-k", "--keepParent", app.toString(), zip.toString()));
        return Files.readAllBytes(zip);
    }

    private static Path bundle(Path folder, String version) throws IOException {
        Path contents = Files.createDirectories(folder.resolve(SelfUpdate.BUNDLE_NAME).resolve("Contents"));
        Files.writeString(contents.resolve("Info.plist"), plist(SelfUpdate.BUNDLE_IDENTIFIER, version));
        return contents.getParent();
    }

    private static String plist(String identifier, String version) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "https://www.apple.com/DTDs/PropertyList-1.0.dtd">
                <plist version="1.0"><dict>
                  <key>CFBundleExecutable</key><string>IKE Dashboard</string>
                  <key>CFBundleIdentifier</key><string>%s</string>
                  <key>CFBundleShortVersionString</key><string>%s</string>
                  <key>CFBundlePackageType</key><string>APPL</string>
                </dict></plist>
                """.formatted(identifier, version);
    }

    private static byte[] metadata(String... versions) {
        StringBuilder listed = new StringBuilder();
        for (String version : versions) {
            listed.append("<version>").append(version).append("</version>");
        }
        return ("<metadata><groupId>network.ike.dashboard</groupId><artifactId>ike-dashboard-app</artifactId>"
                + "<versioning><versions>" + listed + "</versions></versioning></metadata>").getBytes(StandardCharsets.UTF_8);
    }

    private static KometRelease release(String version) {
        return new KometRelease(version, KometRelease.Source.NEXUS, URI.create("https://nexus.example/" + version),
                1, Optional.empty(), Optional.empty(), Optional.of(new Coordinate(SelfUpdate.GROUP, SelfUpdate.ARTIFACT,
                version)), CLASSIFIER);
    }
}
