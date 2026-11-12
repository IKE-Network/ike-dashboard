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
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A project that builds a knowledge base is read from its POM alone (IKE-Network/ike-issues#1370): what
 * it is, the layers it is built from at the versions it fixes them to, where it deploys, and when it
 * last built. The POM here has the shape {@code ike:kb-create-publish} writes.
 */
class KnowledgeBaseProjectFolderTest {

    private static final String POM = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.1.0" root="true">
                <modelVersion>4.1.0</modelVersion>
                <parent>
                    <groupId>network.ike.platform</groupId>
                    <artifactId>ike-parent</artifactId>
                    <version>225</version>
                    <relativePath/>
                </parent>
                <groupId>network.ike.data</groupId>
                <artifactId>gene-kb</artifactId>
                <version>%s</version>
                <name>Gene Knowledge Base</name>
                <distributionManagement>
                    <repository><id>ike-restricted</id><url>https://nexus.example/releases/</url></repository>
                    <snapshotRepository><id>ike-restricted</id><url>https://nexus.example/snapshots/</url></snapshotRepository>
                    <site><id>ike-site</id><url>https://ike.network/gene-kb/</url></site>
                </distributionManagement>
                <properties>
                    <ike.kbAssemble.collection>TRY</ike.kbAssemble.collection>
                    <network.ike.foundation__GA__ike-starter-set__VERSION>1-20261107.045844-2</network.ike.foundation__GA__ike-starter-set__VERSION>
                    <network.ike.data__GA__ncbi-gene-kb__VERSION>20261020.1-20261111.021538-1</network.ike.data__GA__ncbi-gene-kb__VERSION>
                </properties>
                <dependencies>
                    <dependency>
                        <groupId>network.ike.knowledge</groupId>
                        <artifactId>ike-knowledge-provider</artifactId>
                        <version>7-SNAPSHOT</version>
                        <scope>runtime</scope>
                    </dependency>
                    <dependency>
                        <groupId>network.ike.foundation</groupId>
                        <artifactId>ike-starter-set</artifactId>
                        <version>${network.ike.foundation__GA__ike-starter-set__VERSION}</version>
                        <type>zip</type>
                        <classifier>unreasoned-pb</classifier>
                        <scope>provided</scope>
                    </dependency>
                    <dependency>
                        <groupId>network.ike.data</groupId>
                        <artifactId>ncbi-gene-kb</artifactId>
                        <version>${network.ike.data__GA__ncbi-gene-kb__VERSION}</version>
                        <type>zip</type>
                        <classifier>unreasoned-pb</classifier>
                        <scope>provided</scope>
                    </dependency>
                </dependencies>
            </project>
            """;

    @Test
    void aProjectIsReadFromItsPom(@TempDir Path projects) throws Exception {
        Path folder = Files.createDirectories(projects.resolve("gene-kb"));
        Files.writeString(folder.resolve("pom.xml"), POM.formatted("1-SNAPSHOT"));
        Path protobuf = Files.createDirectories(folder.resolve("target")).resolve("gene-kb-1-SNAPSHOT-reasoned-pb.zip");
        Files.writeString(protobuf, "built");
        Instant built = Instant.parse("2026-11-12T14:36:44Z");
        Files.setLastModifiedTime(protobuf, FileTime.from(built));

        KnowledgeBaseProjectFolder project = KnowledgeBaseProjectFolder.read(folder);

        assertThat(project.title()).isEqualTo("Gene Knowledge Base");
        assertThat(project.coordinates()).as("its own coordinates, not its parent's").isEqualTo("network.ike.data:gene-kb:1-SNAPSHOT");
        assertThat(project.layers()).as("each protobuf layer, in load order, at the version the POM fixes it to")
                .extracting(KnowledgeBaseProject.Layer::toString)
                .containsExactly("network.ike.foundation:ike-starter-set:zip:unreasoned-pb:1-20261107.045844-2",
                        "network.ike.data:ncbi-gene-kb:zip:unreasoned-pb:20261020.1-20261111.021538-1");
        assertThat(project.buildsKnowledgeBase()).isTrue();
        assertThat(project.deploysTo()).as("a snapshot goes to the snapshots repository")
                .contains(new KnowledgeBaseProjectFolder.Repository("ike-restricted", "https://nexus.example/snapshots/"));
        assertThat(project.collection()).contains("TRY");
        assertThat(project.built()).as("when the protobuf it publishes was last written").contains(built);
    }

    @Test
    void aReleaseDeploysToTheReleasesRepositoryAndAProjectNotBuiltSaysSo(@TempDir Path projects) throws Exception {
        Path folder = Files.createDirectories(projects.resolve("gene-kb"));
        Files.writeString(folder.resolve("pom.xml"), POM.formatted("2"));

        KnowledgeBaseProjectFolder project = KnowledgeBaseProjectFolder.read(folder);

        assertThat(project.deploysTo()).contains(new KnowledgeBaseProjectFolder.Repository("ike-restricted", "https://nexus.example/releases/"));
        assertThat(project.built()).isEmpty();
    }

    @Test
    void aWorkingSetIsReadWithItsMembersInTheOrderItListsThem(@TempDir Path projects) throws Exception {
        Path workingSet = Files.createDirectories(projects.resolve("gene-ws"));
        Files.writeString(workingSet.resolve("workspace.yaml"), "subprojects:\n");
        Files.writeString(workingSet.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.1.0" root="true">
                    <groupId>network.ike.data</groupId>
                    <artifactId>gene-ws</artifactId>
                    <version>1-SNAPSHOT</version>
                    <subprojects>
                        <subproject>source-kb</subproject>
                        <subproject>gene-kb</subproject>
                        <subproject>not-here-yet</subproject>
                    </subprojects>
                </project>
                """);
        Files.writeString(Files.createDirectories(workingSet.resolve("gene-kb")).resolve("pom.xml"), POM.formatted("1-SNAPSHOT"));
        Files.writeString(Files.createDirectories(workingSet.resolve("source-kb")).resolve("pom.xml"),
                POM.formatted("1-SNAPSHOT").replace("<artifactId>gene-kb</artifactId>", "<artifactId>source-kb</artifactId>"));
        Files.writeString(Files.createDirectories(workingSet.resolve("stray")).resolve("pom.xml"), "<project/>");
        Files.writeString(Files.createDirectories(projects.resolve("alone-kb")).resolve("pom.xml"),
                POM.formatted("1-SNAPSHOT").replace("<artifactId>gene-kb</artifactId>", "<artifactId>alone-kb</artifactId>"));

        List<KnowledgeBaseProjectFolder> found = KnowledgeBaseProjectFolder.scan(projects);
        KnowledgeBaseProjectFolder read = found.getLast();

        assertThat(found).extracting(KnowledgeBaseProjectFolder::artifactId).as("a working set is a project of the projects folder")
                .containsExactly("alone-kb", "gene-ws");
        assertThat(found.getFirst().workingSet()).isFalse();
        assertThat(read.workingSet()).isTrue();
        assertThat(read.members()).as("the members its POM lists that are there, in that order; a folder it does not list is none")
                .extracting(KnowledgeBaseProjectFolder::artifactId).containsExactly("source-kb", "gene-kb");
        assertThat(read.members().getLast().folder()).isEqualTo(workingSet.resolve("gene-kb"));
        assertThat(read.members().getLast().workingSetAmong(found)).as("a member knows its working set").contains(read);
        assertThat(found.getFirst().workingSetAmong(found)).as("a project on its own has none").isEmpty();
        KnowledgeBaseProject.Names names = KnowledgeBaseProject.Names.read(projects, projects.resolve("no-solor"), java.util.Set.of());
        assertThat(names.projects()).as("a member's name is in use, like any project's").contains("gene-ws", "source-kb", "gene-kb", "alone-kb");
    }

    @Test
    void everyFolderWithAPomIsAProjectAndOneThatCannotBeReadIsNamedByItsFolder(@TempDir Path projects) throws Exception {
        Path gene = Files.createDirectories(projects.resolve("gene-kb"));
        Files.writeString(gene.resolve("pom.xml"), POM.formatted("1-SNAPSHOT"));
        Path broken = Files.createDirectories(projects.resolve("broken-kb"));
        Files.writeString(broken.resolve("pom.xml"), "<project>");
        Path inherits = Files.createDirectories(projects.resolve("child"));
        Files.writeString(inherits.resolve("pom.xml"), """
                <project>
                    <parent><groupId>org.example</groupId><artifactId>parent</artifactId><version>3</version></parent>
                    <artifactId>child</artifactId>
                </project>
                """);
        Files.createDirectories(projects.resolve("notes"));
        Files.writeString(projects.resolve("pom.xml"), "<project/>");

        List<KnowledgeBaseProjectFolder> found = KnowledgeBaseProjectFolder.scan(projects);

        assertThat(found).as("by folder name; a folder with no POM is not a project").extracting(KnowledgeBaseProjectFolder::artifactId)
                .containsExactly("broken-kb", "child", "gene-kb");
        assertThat(found.getFirst().coordinates()).as("a POM that cannot be read still names a project").isEqualTo("broken-kb");
        assertThat(found.getFirst().buildsKnowledgeBase()).isFalse();
        assertThat(found.get(1).coordinates()).as("a project with no group or version of its own has its parent's")
                .isEqualTo("org.example:child:3");
        assertThat(KnowledgeBaseProjectFolder.scan(projects.resolve("absent"))).as("a projects folder not made yet").isEmpty();
    }
}
