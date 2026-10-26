package network.ike.dashboard.app;

import network.ike.dashboard.download.ArtifactCoordinates;
import network.ike.dashboard.download.MavenDataStoreDownloadTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void aDownloadCancelledWhileUnpackingLeavesNoStoreAndKeepsWhatWasDownloaded(@TempDir Path temp) throws IOException {
        Coordinate coordinate = new Coordinate("network.ike.data", "pizza-kb", "20250627.1");
        Path localRepository = temp.resolve("m2");
        Path cached = MavenDataStoreDownloadTask.localRepositoryPath(localRepository,
                new ArtifactCoordinates(coordinate.groupId(), coordinate.artifactId()), coordinate.version(), "rkb");
        Files.createDirectories(cached.getParent());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(cached))) {
            for (int part = 0; part < 50; part++) {
                zip.putNextEntry(new ZipEntry("pizza-kb/part-" + part));
                zip.write(new byte[1024]);
                zip.closeEntry();
            }
        }
        Path solor = Files.createDirectories(temp.resolve("Solor"));
        Transfer transfer = new Transfer("Pizza Knowledge Base (Rocks)");
        transfer.onChange(snapshot -> {
            if (snapshot.phase().equals("Unpacking")) {
                transfer.cancel();
            }
        });
        transfer.started();

        assertThatThrownBy(() -> StoreDownload.download(HttpClient.newHttpClient(),
                new RepositoryAccess("https://nexus.invalid/repository/ike-public", "ike-public", Optional.empty()),
                coordinate, "rkb", solor, localRepository, transfer))
                .isInstanceOf(CancellationException.class);

        assertThat(solor.resolve("pizza-kb")).as("no half-unpacked store for the dashboard to list").doesNotExist();
        assertThat(cached).as("the zip stays, so the next download reuses it").exists();
    }

    @Test
    void aProtobufIsManagedByWhatTheDashboardKeepsBesideIt(@TempDir Path temp) throws IOException {
        Path solor = Files.createDirectories(temp.resolve("Solor"));
        Coordinate snomed = new Coordinate("network.ike.data", "snomedct-kb", "20260601.2-SNAPSHOT");
        Path zip = StoreDownload.protobufFile(solor, "snomedct-kb", "20260601.2-20261026.160451-9", "unreasoned-pb");
        Files.writeString(zip, "change sets");
        KnowledgeBaseDescriptor descriptor = new KnowledgeBaseDescriptor(1, snomed, Optional.empty(), "SNOMED CT",
                "spined-64", 3, java.time.Instant.parse("2026-10-26T16:07:05Z"), "restricted", "META-INF/ike/icon.png",
                KnowledgeBaseDescriptor.Memory.NONE, Optional.of("SOLOR"), java.util.List.of(new KnowledgeBaseDescriptor.Input(
                        "SOURCE", "org.snomed:rf2:zip:20260601", Optional.empty(), Optional.of("snomed-rf2"))));

        StoreDownload.describeProtobuf(zip, snomed, new NexusCatalog.Described(descriptor, new byte[] {(byte) 0x89, 'P'}),
                Map.of(KnowledgeBase.RESOLVED_VERSION, "20260601.2-20261026.160451-9"));

        assertThat(KnowledgeBaseScanner.scan(solor)).as("the hidden folder is no entry of its own").singleElement()
                .satisfies(found -> {
                    assertThat(found.kind()).isEqualTo(StoreKind.CHANGE_SETS);
                    assertThat(found.managed()).isTrue();
                    assertThat(found.coordinate()).contains(snomed);
                    assertThat(found.descriptor().descriptor()).contains(descriptor);
                    assertThat(found.resolvedVersion()).contains("20260601.2-20261026.160451-9");
                    assertThat(found.icon().orElseThrow()).hasBinaryContent(new byte[] {(byte) 0x89, 'P'});
                    assertThat(KnowledgeBaseFamily.managed(java.util.List.of(found)))
                            .extracting(KnowledgeBaseFamily::name).containsExactly("snomedct-kb");
                });
        assertThatThrownBy(() -> StoreDownload.protobufFile(solor, "snomedct-kb", "20260601.2-20261026.160451-9",
                "unreasoned-pb")).isInstanceOf(IOException.class).hasMessageContaining("already there");
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
