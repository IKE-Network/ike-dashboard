package network.ike.dashboard.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The dashboard tells each entry of {@code ~/Solor} apart from its files alone, and reads what a
 * store says about itself the standard way.
 */
class KnowledgeBaseScannerTest {

    @TempDir
    Path solor;

    @Test
    void eachLayoutIsToldApartFromItsFiles() throws IOException {
        Files.createDirectories(solor.resolve("rocks-store/rocks"));
        Files.createDirectories(solor.resolve("spined-32/nidToByteArrayMap"));
        Files.createDirectories(solor.resolve("spined-32/nidToCitingComponentNidMap"));
        Files.createDirectories(solor.resolve("spined-64"));
        Files.writeString(solor.resolve("spined-64/store-format.properties"), "engine=spined\nlayout=64-bit\nentityFormat=3\n");
        Files.createDirectories(solor.resolve("notes"));
        Files.writeString(solor.resolve("starter-pb.zip"), "zip");
        Files.writeString(solor.resolve("relaunch.log"), "log");
        Files.createDirectories(solor.resolve(".hidden/rocks"));

        List<KnowledgeBase> found = KnowledgeBaseScanner.scan(solor);

        assertThat(found).extracting(KnowledgeBase::name, KnowledgeBase::kind).containsExactly(
                org.assertj.core.groups.Tuple.tuple("notes", StoreKind.UNKNOWN),
                org.assertj.core.groups.Tuple.tuple("rocks-store", StoreKind.ROCKS),
                org.assertj.core.groups.Tuple.tuple("spined-32", StoreKind.SPINED_32),
                org.assertj.core.groups.Tuple.tuple("spined-64", StoreKind.SPINED_64),
                org.assertj.core.groups.Tuple.tuple("starter-pb.zip", StoreKind.CHANGE_SETS));
    }

    @Test
    void aStoreSaysWhichBuildItIs() throws IOException {
        Path store = Files.createDirectories(solor.resolve("pizza-kb"));
        Files.createDirectories(store.resolve("rocks"));
        Path maven = Files.createDirectories(store.resolve("META-INF/maven/network.ike.data/pizza-kb"));
        Files.writeString(maven.resolve("pom.properties"),
                "#Created by ike-maven-plugin 267\nartifactId=pizza-kb\ngroupId=network.ike.data\nversion=20250627.1-SNAPSHOT\n");
        Files.writeString(store.resolve("META-INF/MANIFEST.MF"), """
                Manifest-Version: 1.0
                Created-By: ike-maven-plugin 267
                Ike-Assembled-At: 2026-10-24T00:20:08.015364Z
                Ike-Open-Controller: Open Rocks KB
                Ike-Resolved-Version: 20250627.1-20261024.001942-7

                """);

        KnowledgeBase pizza = KnowledgeBaseScanner.inspect(store);

        assertThat(pizza.identified()).isTrue();
        assertThat(pizza.coordinate()).contains(new Coordinate("network.ike.data", "pizza-kb", "20250627.1-SNAPSHOT"));
        assertThat(pizza.attributes()).as("only the Ike- attributes").containsOnly(
                Map.entry("Ike-Assembled-At", "2026-10-24T00:20:08.015364Z"),
                Map.entry("Ike-Open-Controller", "Open Rocks KB"),
                Map.entry("Ike-Resolved-Version", "20250627.1-20261024.001942-7"));
        assertThat(pizza.assembledAt()).contains(Instant.parse("2026-10-24T00:20:08.015364Z"));
        assertThat(pizza.resolvedVersion()).contains("20250627.1-20261024.001942-7");
        assertThat(pizza.sizeBytes()).isPositive();
    }

    @Test
    void aStoreWithoutADescriptorIsUnidentified() throws IOException {
        Path store = Files.createDirectories(solor.resolve("solor-kb/nidToByteArrayMap"));
        Files.createDirectories(solor.resolve("solor-kb/nidToCitingComponentNidMap"));

        KnowledgeBase solorKb = KnowledgeBaseScanner.inspect(store.getParent());

        assertThat(solorKb.identified()).isFalse();
        assertThat(solorKb.attributes()).isEmpty();
        assertThat(solorKb.assembledAt()).isEqualTo(Optional.empty());
    }

    @Test
    void aStoreNamesItsIcon() throws IOException {
        Path store = Files.createDirectories(solor.resolve("pizza-kb"));
        Files.createDirectories(store.resolve("rocks"));
        Files.createDirectories(store.resolve("META-INF/ike"));
        Files.write(store.resolve("META-INF/ike/icon.png"), new byte[] {(byte) 0x89, 'P', 'N', 'G'});
        Files.writeString(store.resolve("META-INF/MANIFEST.MF"), """
                Manifest-Version: 1.0
                Ike-Icon: META-INF/ike/icon.png

                """);
        Path escaping = Files.createDirectories(solor.resolve("other-kb"));
        Files.createDirectories(escaping.resolve("META-INF"));
        Files.writeString(escaping.resolve("META-INF/MANIFEST.MF"), "Manifest-Version: 1.0\nIke-Icon: ../pizza-kb/META-INF/ike/icon.png\n\n");

        KnowledgeBase pizza = KnowledgeBaseScanner.inspect(store);

        assertThat(pizza.attributes()).containsEntry("Ike-Icon", "META-INF/ike/icon.png");
        assertThat(pizza.icon()).contains(store.resolve("META-INF/ike/icon.png"));
        assertThat(KnowledgeBaseFamily.group(List.of(pizza)).getFirst().icon()).contains(store.resolve("META-INF/ike/icon.png"));
        assertThat(KnowledgeBaseScanner.inspect(escaping).icon()).as("a name leading out of the store is not followed").isEmpty();
        Files.delete(store.resolve("META-INF/ike/icon.png"));
        assertThat(KnowledgeBaseScanner.inspect(store).icon()).as("named but missing").isEmpty();
    }

    @Test
    void aMissingFolderHasNoKnowledgeBases() throws IOException {
        assertThat(KnowledgeBaseScanner.scan(solor.resolve("absent"))).isEmpty();
    }
}
