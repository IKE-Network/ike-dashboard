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

    private static final String LEVEL_ONE = """
            #Knowledge-base descriptor, level 1
            descriptorLevel=1
            groupId=network.ike.data
            artifactId=pizza-kb
            version=20250627.1-SNAPSHOT
            displayName=Pizza Knowledge Base
            layout=rocks
            entityFormat=3
            assembledAt=2026-10-24T14\\:05\\:07.641539Z
            access=restricted
            icon=META-INF/ike/icon.png
            """;

    @Test
    void aCompleteLevelOneDescriptorWithItsIconIsManaged() throws IOException {
        Path store = describedStore("pizza-kb", LEVEL_ONE, true);

        KnowledgeBase pizza = KnowledgeBaseScanner.inspect(store);

        assertThat(pizza.managed()).isTrue();
        assertThat(pizza.descriptor().descriptor()).contains(new KnowledgeBaseDescriptor(1,
                new Coordinate("network.ike.data", "pizza-kb", "20250627.1-SNAPSHOT"), Optional.empty(),
                "Pizza Knowledge Base", "rocks", 3, Instant.parse("2026-10-24T14:05:07.641539Z"), "restricted",
                "META-INF/ike/icon.png"));
        assertThat(pizza.icon()).contains(store.resolve("META-INF/ike/icon.png"));
        assertThat(pizza.displayName()).isEqualTo("Pizza Knowledge Base");
        assertThat(KnowledgeBaseFamily.managed(List.of(pizza))).singleElement()
                .satisfies(family -> assertThat(family.icon()).contains(store.resolve("META-INF/ike/icon.png")));
        assertThat(KnowledgeBaseFamily.unmanaged(List.of(pizza))).isEmpty();
    }

    @Test
    void theMemoryTheAssemblyMeasuredIsReadAndAMalformedSizeIsLeftOut() throws IOException {
        KnowledgeBase measured = KnowledgeBaseScanner.inspect(describedStore("measured", LEVEL_ONE
                + "statedAxiomBytes=168032664\nloadedHeapBytes=1469861888\nreasonerHeapBytes=lots\n", true));

        assertThat(measured.managed()).as("optional at level 1, so a malformed one costs nothing").isTrue();
        assertThat(measured.descriptor().descriptor().orElseThrow().memory()).isEqualTo(new KnowledgeBaseDescriptor.Memory(
                Optional.of(168_032_664L), Optional.of(1_469_861_888L), Optional.empty()));
        assertThat(KnowledgeBaseScanner.inspect(describedStore("unmeasured", LEVEL_ONE, true))
                .descriptor().descriptor().orElseThrow().memory()).isEqualTo(KnowledgeBaseDescriptor.Memory.NONE);
    }

    @Test
    void aStoreFallingShortOfTheMinimumIsNotManaged() throws IOException {
        assertThat(problems(describedStore("no-icon", LEVEL_ONE, false))).containsExactly("icon META-INF/ike/icon.png is missing");
        assertThat(problems(describedStore("no-name", LEVEL_ONE.replace("displayName=Pizza Knowledge Base\n", ""), true)))
                .containsExactly("missing displayName");
        assertThat(problems(describedStore("bad-fields", LEVEL_ONE.replace("layout=rocks", "layout=tape")
                .replace("access=restricted", "access=friends").replace("entityFormat=3", "entityFormat=three"), true)))
                .containsExactly("layout tape is not rocks, spined-32 or spined-64", "access friends is not public or restricted",
                        "entityFormat three is not a number");
        assertThat(problems(describedStore("level-2", LEVEL_ONE.replace("descriptorLevel=1", "descriptorLevel=2"), true)))
                .containsExactly("descriptor level 2 is not one the dashboard reads");
        assertThat(problems(describedStore("escaping", LEVEL_ONE.replace("icon=META-INF/ike/icon.png",
                "icon=../no-icon/META-INF/ike/icon.png"), true)))
                .containsExactly("icon ../no-icon/META-INF/ike/icon.png is outside the store");
        Path bare = Files.createDirectories(solor.resolve("bare/rocks")).getParent();
        assertThat(problems(bare)).containsExactly("no knowledge-base descriptor");
        Files.writeString(solor.resolve("starter-pb.zip"), "zip");
        KnowledgeBase zip = KnowledgeBaseScanner.inspect(solor.resolve("starter-pb.zip"));
        assertThat(zip.managed()).isFalse();
        assertThat(KnowledgeBaseFamily.unmanaged(KnowledgeBaseScanner.scan(solor))).extracting(KnowledgeBase::name)
                .containsExactly("bad-fields", "bare", "escaping", "level-2", "no-icon", "no-name", "starter-pb.zip");
    }

    private Path describedStore(String name, String descriptor, boolean withIcon) throws IOException {
        Path store = Files.createDirectories(solor.resolve(name).resolve("rocks")).getParent();
        Files.createDirectories(store.resolve("META-INF/ike"));
        Files.writeString(store.resolve("META-INF/ike/knowledge-base.properties"), descriptor);
        if (withIcon) {
            Files.write(store.resolve("META-INF/ike/icon.png"), new byte[] {(byte) 0x89, 'P', 'N', 'G'});
        }
        return store;
    }

    private static List<String> problems(Path store) throws IOException {
        KnowledgeBase entry = KnowledgeBaseScanner.inspect(store);
        assertThat(entry.managed()).isFalse();
        return entry.descriptor().problems();
    }

    @Test
    void aMissingFolderHasNoKnowledgeBases() throws IOException {
        assertThat(KnowledgeBaseScanner.scan(solor.resolve("absent"))).isEmpty();
    }

    @Test
    void theInputsADescriptorRecordsAreReadInOrder() {
        java.util.Properties descriptor = new java.util.Properties();
        descriptor.setProperty("input.1", "PB network.ike.data:snomedct-kb:zip:unreasoned-pb:20260601.2-SNAPSHOT");
        descriptor.setProperty("input.1.resolvedVersion", "20260601.2-20261026.160451-9");
        descriptor.setProperty("input.2", "SOURCE org.snomed:rf2:zip:20260601");
        descriptor.setProperty("input.2.importer", "snomed-rf2");
        descriptor.setProperty("input.3", "malformed");
        descriptor.setProperty("input.4", "CHANGESET file:edits.pb.zip");
        descriptor.setProperty("input.6", "PB after a gap, not read");

        assertThat(KnowledgeBaseScanner.inputs(descriptor)).containsExactly(
                new KnowledgeBaseDescriptor.Input("PB", "network.ike.data:snomedct-kb:zip:unreasoned-pb:20260601.2-SNAPSHOT",
                        Optional.of("20260601.2-20261026.160451-9"), Optional.empty()),
                new KnowledgeBaseDescriptor.Input("SOURCE", "org.snomed:rf2:zip:20260601", Optional.empty(),
                        Optional.of("snomed-rf2")),
                new KnowledgeBaseDescriptor.Input("CHANGESET", "file:edits.pb.zip", Optional.empty(), Optional.empty()));
    }
}
