package network.ike.dashboard.app;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Nexus window's tree: each knowledge base with its databases and protobufs, what it was built from,
 * and what is built from it, as the SOLOR pipeline deploys them.
 */
class KnowledgeBaseTreeTest {

    private static final Instant DEPLOYED = Instant.parse("2026-10-26T17:11:30Z");

    @Test
    void eachKnowledgeBaseHoldsItsDatabasesProtobufsAndWhatItIsBuiltFromAndIncludedIn() {
        NexusCatalog.Catalog catalog = NexusCatalog.Catalog.of(List.of(
                offer("snomedct-kb", "20260601.2-SNAPSHOT", "unreasoned-pb"),
                offer("solor-gudid-kb", "1-SNAPSHOT", "rkb"),
                offer("solor-gudid-kb", "1-SNAPSHOT", "reasoned-sa"),
                offer("solor-gudid-kb", "1-SNAPSHOT", "reasoned-pb"),
                offer("solor-kb", "1-SNAPSHOT", "rkb")), Map.of(
                coordinate("snomedct-kb", "20260601.2-SNAPSHOT"), described("snomedct-kb", "20260601.2-SNAPSHOT", "SNOMED CT"),
                coordinate("solor-gudid-kb", "1-SNAPSHOT"), described("solor-gudid-kb", "1-SNAPSHOT", "SOLOR GUDID",
                        pb("network.ike.foundation:ike-starter-set:zip:unreasoned-pb:1-SNAPSHOT", "1-20261020.101010-4"),
                        pb("network.ike.data:snomedct-kb:zip:unreasoned-pb:20260601.2-SNAPSHOT", "20260601.2-20261026.160451-9"),
                        new KnowledgeBaseDescriptor.Input("CHANGESET",
                                "network.ike.data:solor-kb-extension:zip:changeset:1-SNAPSHOT", Optional.empty(), Optional.empty()),
                        // A store loaded from the knowledge base's own protobuf is not built from another.
                        pb("network.ike.data:solor-gudid-kb:zip:reasoned-pb:1-SNAPSHOT", null)),
                coordinate("solor-kb", "1-SNAPSHOT"), described("solor-kb", "1-SNAPSHOT", "SOLOR",
                        pb("network.ike.data:snomedct-kb:zip:unreasoned-pb:20260601.2-SNAPSHOT", null))));

        List<KnowledgeBaseTree.Node> tree = KnowledgeBaseTree.of(catalog);

        assertThat(tree).extracting(KnowledgeBaseTree.Node::label).containsExactly("SNOMED CT", "SOLOR", "SOLOR GUDID");
        KnowledgeBaseTree.Node gudid = tree.getLast();
        assertThat(gudid.children()).extracting(KnowledgeBaseTree.Node::label)
                .containsExactly(KnowledgeBaseTree.DATABASES, KnowledgeBaseTree.PROTOBUFS, KnowledgeBaseTree.BUILT_FROM);
        assertThat(gudid.under(KnowledgeBaseTree.DATABASES)).extracting(KnowledgeBaseTree.Node::label)
                .containsExactly("Rocks", "spined array");
        assertThat(gudid.under(KnowledgeBaseTree.PROTOBUFS)).extracting(KnowledgeBaseTree.Node::label)
                .containsExactly("protobuf, reasoned");
        List<KnowledgeBaseTree.Node> builtFrom = gudid.under(KnowledgeBaseTree.BUILT_FROM);
        assertThat(builtFrom).extracting(KnowledgeBaseTree.Node::label)
                .as("a described knowledge base by its name, any other artifact by its artifactId")
                .containsExactly("ike-starter-set", "SNOMED CT", "solor-kb-extension");
        assertThat(builtFrom).extracting(node -> KnowledgeBaseTree.describe(node.input().orElseThrow()),
                        node -> node.input().flatMap(KnowledgeBaseDescriptor.Input::version).orElse(""))
                .containsExactly(org.assertj.core.groups.Tuple.tuple("protobuf, unreasoned", "1-20261020.101010-4"),
                        org.assertj.core.groups.Tuple.tuple("protobuf, unreasoned", "20260601.2-20261026.160451-9"),
                        org.assertj.core.groups.Tuple.tuple("change sets", "1-SNAPSHOT"));
        assertThat(builtFrom.get(1).knowledgeBase()).as("names the described knowledge base, to go to it")
                .contains(coordinate("snomedct-kb", "20260601.2-SNAPSHOT"));

        KnowledgeBaseTree.Node snomed = tree.getFirst();
        assertThat(snomed.children()).extracting(KnowledgeBaseTree.Node::label)
                .as("a source's protobuf, and the assemblies that include it")
                .containsExactly(KnowledgeBaseTree.PROTOBUFS, KnowledgeBaseTree.INCLUDED_IN);
        assertThat(snomed.under(KnowledgeBaseTree.INCLUDED_IN)).extracting(KnowledgeBaseTree.Node::label)
                .containsExactly("SOLOR", "SOLOR GUDID");
    }

    @Test
    void anInputReadsItsCoordinateClassifierAndFile() {
        KnowledgeBaseDescriptor.Input snomed = pb("network.ike.data:snomedct-kb:zip:unreasoned-pb:20260601.2-SNAPSHOT", null);
        assertThat(snomed.coordinate()).contains(coordinate("snomedct-kb", "20260601.2-SNAPSHOT"));
        assertThat(snomed.classifier()).contains("unreasoned-pb");
        assertThat(snomed.of(coordinate("snomedct-kb", "20250101.1"))).as("at any version").isTrue();
        assertThat(snomed.file()).isEmpty();

        KnowledgeBaseDescriptor.Input edits = new KnowledgeBaseDescriptor.Input("PB", "file:pizza-edits.pb.zip",
                Optional.empty(), Optional.empty());
        assertThat(edits.coordinate()).isEmpty();
        assertThat(edits.file()).contains("pizza-edits.pb.zip");
        assertThat(KnowledgeBaseTree.input(edits, Map.of()).label()).isEqualTo("pizza-edits.pb.zip");

        KnowledgeBaseDescriptor.Input rf2 = new KnowledgeBaseDescriptor.Input("SOURCE", "org.snomed:rf2:zip:20260601",
                Optional.empty(), Optional.of("snomed-rf2"));
        assertThat(KnowledgeBaseTree.describe(rf2)).isEqualTo("source, read by snomed-rf2");
        assertThat(rf2.classifier()).as("a four-part coordinate names no classifier").isEmpty();
    }

    private static KnowledgeBaseDescriptor.Input pb(String reference, String resolved) {
        return new KnowledgeBaseDescriptor.Input("PB", reference, Optional.ofNullable(resolved), Optional.empty());
    }

    private static Coordinate coordinate(String artifactId, String version) {
        return new Coordinate("network.ike.data", artifactId, version);
    }

    private static NexusCatalog.Offer offer(String artifactId, String version, String classifier) {
        return new NexusCatalog.Offer(coordinate(artifactId, version), classifier, 1_000_000, DEPLOYED, "ike-restricted");
    }

    private static NexusCatalog.Described described(String artifactId, String version, String displayName,
                                                    KnowledgeBaseDescriptor.Input... inputs) {
        return new NexusCatalog.Described(new KnowledgeBaseDescriptor(1, coordinate(artifactId, version), Optional.empty(),
                displayName, "spined-64", 3, DEPLOYED, "restricted", "META-INF/ike/icon.png",
                KnowledgeBaseDescriptor.Memory.NONE, Optional.of("SOLOR"), List.of(inputs)), new byte[] {1});
    }
}
