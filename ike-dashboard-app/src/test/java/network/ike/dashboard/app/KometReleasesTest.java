package network.ike.dashboard.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.nio.file.attribute.PosixFilePermission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The dashboard finds Komet builds where the installer build puts them, and recognizes the images it
 * installed. The release record is GitHub's for the checkpoint of 2026-10-24, shortened.
 */
class KometReleasesTest {

    private static final String RELEASES = """
            [ { "tag_name": "checkpoint/main-20261024-020831", "draft": false, "prerelease": true,
                "published_at": "2026-10-24T02:23:57Z",
                "assets": [
                  { "name": "Komet.Desktop.2026-10-24.220s-26.10.220-windows-x86_64.msi", "size": 218999013,
                    "browser_download_url": "https://github.com/x/y.msi" },
                  { "name": "Komet.Desktop.2026-10-24.212s-26.1024.212-osx-aarch64.pkg", "size": 219493378,
                    "digest": "sha256:15f92d044472507ab732b3bb49f9dacd4b79378ec6f96035d246b3bb01cdb5d7",
                    "browser_download_url": "https://github.com/IKE-Network/ike-komet-wsr/releases/download/checkpoint/main-20261024-020831/Komet.Desktop.2026-10-24.212s-26.1024.212-osx-aarch64.pkg" } ] } ]
            """;

    @Test
    void theNewestReleasesPackageForThePlatformIsTheBuild() {
        KometRelease release = KometReleases.fromGitHub(RELEASES, "osx-aarch64").orElseThrow();

        assertThat(release.version()).isEqualTo("26.1024.212");
        assertThat(release.source()).isEqualTo(KometRelease.Source.GITHUB);
        assertThat(release.sizeBytes()).isEqualTo(219_493_378);
        assertThat(release.checksum()).contains("sha256:15f92d044472507ab732b3bb49f9dacd4b79378ec6f96035d246b3bb01cdb5d7");
        assertThat(release.newerThan("26.1016.346")).isTrue();
        assertThat(KometReleases.fromGitHub(RELEASES, "osx-x86_64")).as("no Intel package in that release").isEmpty();
    }

    @Test
    void metadataListsTheDeployedVersions() throws IOException {
        byte[] metadata = """
                <metadata><groupId>network.ike.platform</groupId><artifactId>komet-app</artifactId>
                <versioning><release>26.1024.999</release>
                <versions><version>26.1023.2241</version><version>26.1024.999</version></versions>
                </versioning></metadata>""".getBytes(StandardCharsets.UTF_8);

        assertThat(KometReleases.versions(metadata)).containsExactly("26.1023.2241", "26.1024.999");
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "an execute bit; KometInstallationsTest has the Windows image")
    void anInstalledImageIsAKomet(@TempDir Path install) throws IOException {
        Path image = Files.createDirectories(install.resolve(KometInstaller.imageFolder("26.1024.999") + "/bin"));
        Path launcher = Files.writeString(image.resolve("launchKomet"), "#!/bin/sh\n");
        Files.setPosixFilePermissions(launcher, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE));
        Files.writeString(image.getParent().resolve(KometInstaller.PROPERTIES), "version=26.1024.999\n");
        Files.createDirectories(install.resolve("not-komet/bin"));

        assertThat(KometInstallations.find(java.util.List.of(install))).singleElement().satisfies(komet -> {
            assertThat(komet.version()).isEqualTo("26.1024.999");
            assertThat(komet.name()).isEqualTo("Komet 26.1024.999");
            assertThat(komet.bundle().toString()).as("the launcher cannot run from a path with a space").doesNotContain(" ");
            assertThat(komet.executable()).isEqualTo(launcher);
            assertThat(KometLauncher.command(komet, new KnowledgeBase("pizza-kb", Path.of("/s/pizza-kb"), StoreKind.ROCKS, 0,
                    Optional.empty(), java.util.Map.of(), java.time.Instant.EPOCH)))
                    .containsExactly(launcher.toString(), "--kb=/s/pizza-kb");
        });
    }

    @Test
    void aDownloadThatDoesNotMatchItsChecksumIsRefused(@TempDir Path folder) throws IOException {
        Path file = Files.writeString(folder.resolve("komet.pkg"), "abc");
        KometRelease release = new KometRelease("26.1024.212", KometRelease.Source.GITHUB, URI.create("https://x"), 3,
                Optional.of("sha256:0000"), Optional.empty(), Optional.empty(), "osx-aarch64");
        KometRelease sha1 = new KometRelease("26.1024.212", KometRelease.Source.NEXUS, URI.create("https://x"), 3,
                Optional.of("sha1:a9993e364706816aba3e25717850c26c9cd0d89d"), Optional.empty(), Optional.empty(), "osx-aarch64");

        assertThatThrownBy(() -> KometInstaller.verify(file, release)).hasMessageContaining("does not match");
        KometInstaller.verify(file, sha1);
    }
}
