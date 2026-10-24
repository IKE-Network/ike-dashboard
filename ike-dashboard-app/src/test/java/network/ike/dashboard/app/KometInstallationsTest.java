package network.ike.dashboard.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
                .containsExactly("/Applications/Komet.app/Contents/MacOS/Komet", "--kb=/Users/someone/Solor/pizza-kb");
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
