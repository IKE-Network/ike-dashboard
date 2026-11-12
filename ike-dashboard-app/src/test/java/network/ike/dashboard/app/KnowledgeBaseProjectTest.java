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

import org.junit.jupiter.api.Test;

import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A knowledge base to create is said in the words of the goals that create it (IKE-Network/ike-issues#1370):
 * its name, its folder, and the goals' own properties. What the goals would refuse is caught before they
 * run, and the sizes are estimated by their rule.
 */
class KnowledgeBaseProjectTest {

    private static final String GENE = "network.ike.data:ncbi-gene-kb:zip:unreasoned-pb:20261020.1-SNAPSHOT";
    private static final String SNOMED = "network.ike.data:snomedct-kb:zip:unreasoned-pb:20260601.2-SNAPSHOT";
    private static final String SOLOR = "network.ike.data:solor-kb:zip:reasoned-pb:1-SNAPSHOT";
    private static final long GIB = 1L << 30;

    @Test
    void theGoalIsNamedByItsFullCoordinatesAndGivenTheNameTheFolderAndEachProperty() {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("layers", SNOMED + "," + GENE);
        properties.put("collection", "TRY");
        properties.put("displayName", "  ");
        KnowledgeBaseProject project = new KnowledgeBaseProject("my-kb", Path.of("/work/my-kb"), properties);

        assertThat(project.arguments(KnowledgeBaseCreation.DRAFT, "280")).as("batch mode, no transfer progress, no colour, then the goal")
                .containsExactly("-B", "-ntp", "-Dstyle.color=never", "network.ike.tooling:ike-maven-plugin:280:kb-create-draft",
                        "-Dname=my-kb", "-DtargetDirectory=" + Path.of("/work/my-kb"), "-Dlayers=" + SNOMED + "," + GENE, "-Dcollection=TRY");
        assertThat(project.properties()).as("a blank property is not said").doesNotContainKey("displayName");
        assertThat(project.kind()).as("an assembly unless another kind is named").isEqualTo(KnowledgeBaseProject.Kind.ASSEMBLY);
        assertThat(project.layers()).extracting(KnowledgeBaseProject.Layer::artifactId).containsExactly("snomedct-kb", "ncbi-gene-kb");
        assertThat(project.problems()).isEmpty();
    }

    @Test
    void aCommandLineNamesPropertiesAndTheFolder() {
        KnowledgeBaseProject named = KnowledgeBaseProject.parse("my-kb",
                List.of("layers=" + GENE, "kind=source-layer", "importer=nuva", "release=/releases/nuva.zip", "folder=/elsewhere/kb"),
                Path.of("/home/knowledge-bases"));
        KnowledgeBaseProject unnamed = KnowledgeBaseProject.parse("my-kb", List.of("layers=" + GENE), Path.of("/home/knowledge-bases"));

        assertThat(named.folder()).as("as given, made absolute").isEqualTo(Path.of("/elsewhere/kb").toAbsolutePath());
        assertThat(named.kind()).isEqualTo(KnowledgeBaseProject.Kind.SOURCE_LAYER);
        assertThat(named.problems()).isEmpty();
        assertThat(unnamed.folder()).as("in the projects folder, named for the knowledge base")
                .isEqualTo(Path.of("/home/knowledge-bases/my-kb"));
        assertThatThrownBy(() -> KnowledgeBaseProject.parse("my-kb", List.of("layer=" + GENE), Path.of("/home")))
                .as("a property the goals do not take").isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown property layer").hasMessageContaining("layers");
        assertThatThrownBy(() -> KnowledgeBaseProject.parse("my-kb", List.of("layers"), Path.of("/home")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("property=value");
    }

    @Test
    void whatTheGoalsWouldRefuseIsCaughtBeforeTheyRun() {
        assertThat(project("My_KB", Map.of("layers", GENE)).problems()).as("a name that is no artifactId")
                .containsExactly("The name is lower-case letters and digits joined by hyphens, such as my-kb.");
        assertThat(project("", Map.of()).problems()).as("nothing said yet")
                .containsExactly("Give the knowledge base a name.", "Add at least one protobuf layer.");
        assertThat(project("my-kb", Map.of("layers", GENE + "," + GENE.replace("20261020.1", "20251020.1"))).problems())
                .as("one artifact at two versions").containsExactly("ncbi-gene-kb is listed twice.");
        assertThat(project("my-kb", Map.of("layers", SOLOR + "," + GENE)).problems()).as("decision 10")
                .containsExactly("Reasoned and unreasoned protobufs cannot be combined yet.");
        assertThat(project("my-kb", Map.of("layers", GENE + ",network.ike.foundation:ike-starter-set:zip:unreasoned-pb:1-SNAPSHOT"))
                .problems()).containsExactly("The starter set must be first. It is at position 2.");
        assertThat(project("my-kb", Map.of("layers", SNOMED, "kind", "source-layer")).problems())
                .containsExactly("A source layer needs its importer and the release it reads.");
        assertThat(project("my-kb", Map.of("layers", SNOMED, "importer", "nuva", "release", "/r.zip")).problems())
                .containsExactly("An assembly imports no release. An importer and a release belong to a source layer.");
        assertThat(project("my-kb", Map.of("layers", "snomedct-kb")).problems()).as("a layer that is no coordinate")
                .containsExactly("Expected groupId:artifactId:zip:classifier:version, got snomedct-kb.");
    }

    @Test
    void theStarterSetIsPutFirstUnlessItIsListedOrAReasonedProtobufIsTheBase() {
        assertThat(KnowledgeBaseProject.starterSetFirst(project("a", Map.of("layers", SNOMED + "," + GENE)).layers())).isTrue();
        assertThat(KnowledgeBaseProject.starterSetFirst(project("a", Map.of("layers", SOLOR)).layers()))
                .as("a reasoned protobuf already carries it").isFalse();
        assertThat(KnowledgeBaseProject.starterSetFirst(project("a",
                Map.of("layers", "network.ike.foundation:ike-starter-set:zip:unreasoned-pb:1-SNAPSHOT," + GENE)).layers())).isFalse();
    }

    @Test
    void theSizesAreEstimatedByTheGoalsRule() {
        KnowledgeBaseProject.Estimate small = KnowledgeBaseProject.estimate(KnowledgeBaseProject.Kind.ASSEMBLY,
                List.of(900_000L, 100_000L), Optional.empty());
        // solor-gudid-kb's inputs, about 3.28 GiB: loaded 4 x, the reasoner 3.2 x that, the collector's room 1.35 x.
        long gudid = (long) (3.28 * GIB);
        KnowledgeBaseProject.Estimate large = KnowledgeBaseProject.estimate(KnowledgeBaseProject.Kind.ASSEMBLY,
                List.of(gudid, -1L), Optional.empty());
        KnowledgeBaseProject.Estimate source = KnowledgeBaseProject.estimate(KnowledgeBaseProject.Kind.SOURCE_LAYER,
                List.of(gudid), Optional.empty());
        KnowledgeBaseProject.Estimate never = KnowledgeBaseProject.estimate(KnowledgeBaseProject.Kind.ASSEMBLY,
                List.of(gudid), Optional.of(false));

        assertThat(small.heapGib()).as("never under 8 GiB").isEqualTo(8);
        assertThat(small.rocks()).isFalse();
        assertThat(small.rocksReason()).isEqualTo("the layers total 1.0 MiB, below 2 GiB");
        assertThat(large.loadedBytes()).isEqualTo((long) Math.ceil(4.0 * gudid));
        assertThat(large.heapGib()).as("1.35 x (1 + 3.2) x 4 x 3.28 GiB, rounded up").isEqualTo(75);
        assertThat(large.rocks()).as("2 GiB of layers or more builds a Rocks store (decision 6)").isTrue();
        assertThat(large.rocksReason()).isEqualTo("the layers total 3.28 GiB (1 not known), at or above 2 GiB");
        assertThat(source.heapGib()).as("an import adds 1 x where the reasoner adds 3.2 x").isEqualTo(36);
        assertThat(source.rocks()).as("a source layer builds no store").isFalse();
        assertThat(never.rocks()).isFalse();
        assertThat(never.rocksReason()).isEqualTo("never, as chosen");
    }

    @Test
    void aNameAlreadyInUseIsRefusedAndAFreeOneLikeItIsOffered() {
        KnowledgeBaseProject.Names names = new KnowledgeBaseProject.Names(
                Set.of("gene-kb", "gene-2-kb"),
                Set.of("solor-kb"),
                Set.of("solor-kb", "pizza-kb-authoring", "notes-2-kb"),
                Set.of("network.ike.data:snomedct-kb", "network.ike.data:snomedct-2-kb"));

        assertThat(names.taken("my-kb", "network.ike.data")).as("a new name").isEmpty();
        assertThat(names.taken("gene-kb", "network.ike.data")).as("a project already made")
                .contains("A project named gene-kb is already in your projects folder.");
        assertThat(names.taken("solor-kb", "network.ike.data")).as("a downloaded version of the new one would go into this one's folder")
                .contains("A knowledge base named solor-kb is already in your knowledge-base folder.");
        assertThat(names.taken("pizza-kb", "network.ike.data")).as("the folder Komet would edit it in")
                .contains("Your knowledge-base folder already has a folder named pizza-kb-authoring.");
        assertThat(names.taken("snomedct-kb", "network.ike.data")).as("it would deploy over the one on Nexus")
                .contains("A knowledge base named snomedct-kb is already on Nexus.");
        assertThat(names.taken("snomedct-kb", "org.example")).as("another group's is another knowledge base").isEmpty();
        assertThat(names.free("gene-kb", "network.ike.data")).as("the first number that is free, before -kb").isEqualTo("gene-3-kb");
        assertThat(names.free("snomedct-kb", "network.ike.data")).isEqualTo("snomedct-3-kb");
        assertThat(names.free("notes-kb", "network.ike.data")).as("a folder of that name counts too").isEqualTo("notes-3-kb");
        assertThat(names.free("solor", "network.ike.data")).isEqualTo("solor-2");
    }

    @Test
    void theNamesInUseAreReadFromTheProjectsFolderAndTheKnowledgeBaseFolder(@TempDir Path home) throws Exception {
        Path projects = home.resolve("knowledge-bases");
        Path project = Files.createDirectories(projects.resolve("gene-work"));
        Files.writeString(project.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.1.0">
                    <parent><groupId>network.ike.platform</groupId><artifactId>ike-parent</artifactId><version>225</version></parent>
                    <groupId>network.ike.data</groupId>
                    <artifactId>gene-kb</artifactId>
                </project>
                """);
        Path unreadable = Files.createDirectories(projects.resolve("broken-kb"));
        Files.writeString(unreadable.resolve("pom.xml"), "<project>");
        Files.createDirectories(projects.resolve("notes"));
        Path solor = home.resolve("Solor");
        Files.createDirectories(solor.resolve("solor-kb"));

        KnowledgeBaseProject.Names names = KnowledgeBaseProject.Names.read(projects, solor, Set.of());

        assertThat(names.projects()).as("each project's name and every folder's, so neither is used twice")
                .containsExactlyInAnyOrder("gene-work", "gene-kb", "broken-kb", "notes");
        assertThat(names.here()).containsExactly("solor-kb");
        assertThat(names.folders()).containsExactly("solor-kb");
        assertThat(KnowledgeBaseProject.Names.read(home.resolve("absent"), home.resolve("none"), Set.of()))
                .as("folders not made yet have no names in use").isEqualTo(KnowledgeBaseProject.Names.NONE);
    }

    private static KnowledgeBaseProject project(String name, Map<String, String> properties) {
        return new KnowledgeBaseProject(name, Path.of("/work").resolve(name), properties);
    }
}
