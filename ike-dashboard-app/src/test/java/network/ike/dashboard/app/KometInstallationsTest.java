package network.ike.dashboard.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The dashboard finds every installed Komet by its bundle identifier, wherever it is installed, and
 * nothing else.
 */
class KometInstallationsTest {

    @TempDir
    Path root;

    @Test
    void kometBundlesAreFoundNewestFirst() throws IOException {
        Path applications = Files.createDirectories(root.resolve("Applications"));
        Path install = Files.createDirectories(root.resolve("IKE/Komet"));
        bundle(applications.resolve("Komet Desktop 2026-10-16 346s.app"), KometInstallation.BUNDLE_IDENTIFIER,
                "26.1016.346", "Komet Desktop 2026-10-16 346s");
        bundle(install.resolve("26.1023.12/Komet Desktop 2026-10-23 12s.app"), KometInstallation.BUNDLE_IDENTIFIER,
                "26.1023.12", "Komet Desktop 2026-10-23 12s");
        bundle(applications.resolve("Safari.app"), "com.apple.Safari", "19.0", "Safari");
        Path binary = Files.createDirectories(applications.resolve("Binary.app/Contents"));
        Files.write(binary.resolve("Info.plist"), "bplist00\u0001\u0002".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));

        List<KometInstallation> found = KometInstallations.find(List.of(applications, install, root.resolve("absent")));

        assertThat(found).extracting(KometInstallation::version).containsExactly("26.1023.12", "26.1016.346");
        assertThat(found.getFirst().name()).isEqualTo("Komet Desktop 2026-10-23 12s");
        assertThat(found.getFirst().executable()).isEqualTo(install.resolve(
                "26.1023.12/Komet Desktop 2026-10-23 12s.app/Contents/MacOS/Komet Desktop 2026-10-23 12s"));
    }

    @Test
    void versionsOrderByTheirNumbers() {
        assertThat(KometInstallations.compareVersions("26.1016.346", "26.1023.12")).isNegative();
        assertThat(KometInstallations.compareVersions("26.1023.12", "26.1023.12")).isZero();
        assertThat(KometInstallations.compareVersions("3.0.10", "3.0.9")).isPositive();
    }

    @Test
    void theLaunchCommandNamesTheKnowledgeBase() {
        KometInstallation komet = new KometInstallation("Komet", "26.1016.346", Path.of("/Applications/Komet.app"),
                Path.of("/Applications/Komet.app/Contents/MacOS/Komet"));
        KnowledgeBase pizza = new KnowledgeBase("pizza-kb", Path.of("/Users/someone/Solor/pizza-kb"), StoreKind.ROCKS,
                0, java.util.Optional.empty(), java.util.Map.of(), java.time.Instant.EPOCH);

        assertThat(KometLauncher.command(komet, pizza))
                .containsExactly(komet.executable().toString(), "--kb=" + pizza.path());
    }

    @Test
    void onWindowsKometIsAJlinkImageOrAnImageWhoseLauncherConfigurationRunsIt() throws IOException {
        Path local = root.resolve("AppData/Local");
        Path installed = appImage(local.resolve("Komet"), "Komet", "dev.ikm.komet.desktop/dev.ikm.komet.desktop.KometLauncher",
                "26.1026.57");
        appImage(local.resolve("Programs/Other"), "Other", "com.example/com.example.Main", "1.0");
        Path install = root.resolve("IKE/Komet");
        Path bin = Files.createDirectories(install.resolve(KometInstaller.imageFolder("26.1101.900")).resolve("bin"));
        Files.writeString(bin.resolve("launchKomet.bat"), "@echo off\r\n");
        Files.writeString(bin.getParent().resolve(KometInstaller.PROPERTIES), "version=26.1101.900\n");

        List<KometInstallation> found = KometInstallations.find(KometInstallations.defaultRoots(root, install,
                OperatingSystem.WINDOWS, Map.of("LOCALAPPDATA", local.toString())));

        assertThat(found).extracting(KometInstallation::version).containsExactly("26.1101.900", "26.1026.57");
        assertThat(found.getFirst().executable()).isEqualTo(bin.resolve("launchKomet.bat"));
        assertThat(found.get(1).name()).isEqualTo("Komet");
        assertThat(found.get(1).executable()).isEqualTo(installed.resolve("Komet.exe"));
    }

    @Test
    void onWindowsKometIsLookedForWherePerUserAndEveryUserInstallersPutApplications() {
        Path home = Path.of("home");
        assertThat(KometInstallations.defaultRoots(home, Path.of("komets"), OperatingSystem.WINDOWS,
                Map.of("ProgramFiles", Path.of("programs").toString())))
                .containsExactly(Path.of("komets"), home.resolve("AppData/Local"), home.resolve("AppData/Local/Programs"),
                        Path.of("programs"));
    }

    /**
     * Writes a jpackage application image as Windows lays one out: its launcher, and beside it in
     * {@code app} the launcher configuration naming the main module and the version.
     */
    static Path appImage(Path folder, String name, String main, String version) throws IOException {
        Path app = Files.createDirectories(folder.resolve("app"));
        Files.writeString(app.resolve(name + ".cfg"), """
                [Application]
                app.mainmodule=%s

                [JavaOptions]
                java-options=-Djpackage.app-version=%s
                java-options=--enable-preview
                """.formatted(main, version));
        Files.write(folder.resolve(name + ".exe"), new byte[] {'M', 'Z'});
        return folder;
    }

    private static void bundle(Path app, String identifier, String version, String executable) throws IOException {
        Path contents = Files.createDirectories(app.resolve("Contents"));
        Files.writeString(contents.resolve("Info.plist"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "https://www.apple.com/DTDs/PropertyList-1.0.dtd">
                <plist version="1.0">
                <dict>
                  <key>CFBundleExecutable</key>
                  <string>%s</string>
                  <key>CFBundleIdentifier</key>
                  <string>%s</string>
                  <key>LSApplicationCategoryType</key>
                  <array><string>public.app-category.utilities</string></array>
                  <key>CFBundleShortVersionString</key>
                  <string>%s</string>
                </dict>
                </plist>
                """.formatted(executable, identifier, version));
    }
}
