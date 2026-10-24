package network.ike.dashboard.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.jar.Manifest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A download adds to the store's manifest what only a download knows, keeping what the build wrote.
 */
class StoreDownloadTest {

    @TempDir
    Path store;

    @Test
    void theDownloadsAttributesAreAddedToTheBuilds() throws IOException {
        Files.createDirectories(store.resolve("META-INF"));
        Files.writeString(store.resolve("META-INF/MANIFEST.MF"),
                "Manifest-Version: 1.0\nCreated-By: ike-maven-plugin 267\nIke-Open-Controller: Open Rocks KB\n\n");

        StoreDownload.addToManifest(store, Map.of(KnowledgeBase.RESOLVED_VERSION, "20250627.1-20261024.001942-7",
                StoreDownload.RESOLVED_REPOSITORY, "ike-restricted"));

        Manifest manifest = new Manifest();
        try (InputStream in = Files.newInputStream(store.resolve("META-INF/MANIFEST.MF"))) {
            manifest.read(in);
        }
        assertThat(manifest.getMainAttributes().getValue("Created-By")).isEqualTo("ike-maven-plugin 267");
        assertThat(manifest.getMainAttributes().getValue("Ike-Open-Controller")).isEqualTo("Open Rocks KB");
        assertThat(manifest.getMainAttributes().getValue("Ike-Resolved-Version")).isEqualTo("20250627.1-20261024.001942-7");
        assertThat(manifest.getMainAttributes().getValue("Ike-Resolved-Repository")).isEqualTo("ike-restricted");
    }

    @Test
    void aStoreWithoutAManifestGetsOne() throws IOException {
        StoreDownload.addToManifest(store, Map.of(StoreDownload.DOWNLOADED_AT, "2026-10-24T02:40:17Z"));

        assertThat(KnowledgeBaseScanner.ikeAttributes(store)).containsOnly(
                Map.entry("Ike-Downloaded-At", "2026-10-24T02:40:17Z"));
    }

    @Test
    void theChecksumIsTheOneNexusServes() throws IOException {
        Path file = Files.writeString(store.resolve("a.txt"), "abc");

        assertThat(StoreDownload.sha1(file)).isEqualTo("a9993e364706816aba3e25717850c26c9cd0d89d");
    }

    @Test
    void sizesReadAsPeopleWriteThem() {
        assertThat(DashboardCommand.size(97)).isEqualTo("97 B");
        assertThat(DashboardCommand.size(3_920_000)).isEqualTo("3.92 MB");
        assertThat(DashboardCommand.size(13_700_000_000L)).isEqualTo("13.7 GB");
        assertThat(DashboardCommand.size(258_000_000)).isEqualTo("258 MB");
    }
}
