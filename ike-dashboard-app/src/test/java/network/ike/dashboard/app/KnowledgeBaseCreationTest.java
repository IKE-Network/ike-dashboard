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
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Creating a knowledge base runs the goals through a Maven wrapper written first
 * (IKE-Network/ike-issues#1370): a draft leaves nothing where the project will be, a creation that fails
 * takes the wrapper away again, and each run is given the JDK and git it needs. A shell script stands in
 * for Maven, as an HTTP server stands in for Nexus elsewhere; what the goals write is as they wrote it on
 * 2026-11-12.
 */
class KnowledgeBaseCreationTest {

    private static final String GENE = "network.ike.data:ncbi-gene-kb:zip:unreasoned-pb:20261020.1-SNAPSHOT";

    /** What kb-create-draft wrote for a small assembly, Maven's framing included. */
    private static final List<String> DRAFT = List.of(
            "[INFO] Scanning for projects...",
            "[INFO] ",
            "[INFO] --- ike:280:kb-create-draft (default-cli) @ standalone-pom ---",
            "[INFO] Pinning network.ike.foundation:ike-starter-set:zip:unreasoned-pb:1-SNAPSHOT …",
            "[INFO] Pinning network.ike.data:ncbi-gene-kb:zip:unreasoned-pb:20261020.1-SNAPSHOT …",
            "[INFO] Knowledge base: Gene Try Knowledge Base (assembly)",
            "[INFO]   Coordinates:  network.ike.data:gene-try-kb:1-SNAPSHOT",
            "[INFO]   Layers, in load order, pinned:",
            "[INFO]     network.ike.foundation:ike-starter-set:zip:unreasoned-pb:1-20261107.045844-2, 0.9 MiB",
            "[INFO]     network.ike.data:ncbi-gene-kb:zip:unreasoned-pb:20261020.1-20261111.021538-1, 0.1 MiB",
            "[INFO]   Layers total: 1.0 MiB; estimated loaded heap 4.0 MiB",
            "[INFO]   Rocks store:  no — the layers total 1.0 MiB, below 2 GiB",
            "[INFO]   Files:",
            "[INFO]     pom.xml",
            "[INFO]     .gitignore (standards scaffold)",
            "[INFO] Draft only — run ike:kb-create-publish with the same inputs to create it.",
            "[INFO] ------------------------------------------------------------------------",
            "[INFO] BUILD SUCCESS");

    /** What Maven wrote when the goal refused a layer Nexus does not hold. */
    private static final List<String> REFUSED = List.of(
            "[INFO] Pinning network.ike.data:no-such-kb:zip:unreasoned-pb:1-SNAPSHOT …",
            "[INFO] ------------------------------------------------------------------------",
            "[ERROR] BUILD FAILURE",
            "[INFO] ------------------------------------------------------------------------",
            "[ERROR] Failed to execute goal network.ike.tooling:ike-maven-plugin:280:kb-create-draft (default-cli) on project"
                    + " standalone-pom: Cannot pin the layers: network.ike.data:no-such-kb:zip:unreasoned-pb:1-SNAPSHOT has never"
                    + " been deployed to https://nexus.tinkar.org/repository/ike-restricted/",
            "[ERROR] ",
            "[ERROR] To see the full stack trace of the errors, re-run Maven with the '-e' switch");

    @Test
    void theJdkIsTheOneJavaHomeNamesWhenRecentEnoughElseTheNewestInstalled(@TempDir Path root) throws IOException {
        Path old = jdk(root.resolve("named/jdk-25"), "25.0.2");
        Path machine = root.resolve("JavaVirtualMachines");
        jdk(machine.resolve("temurin-21.jdk/Contents/Home"), "21.0.9");
        Path bundled = jdk(machine.resolve("openjdk-27.0.1.jdk/Contents/Home"), "27.0.1");
        Path newest = jdk(root.resolve("jdks/jdk-28"), "28");
        Files.createDirectories(root.resolve("jdks/not-a-jdk"));
        Path runtime = root.resolve("jdks/runtime-29");                        // a runtime image has no compiler
        Files.createDirectories(runtime);
        Files.writeString(runtime.resolve("release"), "JAVA_VERSION=\"29\"\n");

        assertThat(KnowledgeBaseCreation.jdk(Map.of("JAVA_HOME", bundled.toString()), List.of(root.resolve("jdks"))))
                .as("JAVA_HOME, when it is 27 or later").contains(bundled);
        assertThat(KnowledgeBaseCreation.jdk(Map.of("JAVA_HOME", old.toString()), List.of(machine, root.resolve("jdks"))))
                .as("an older JAVA_HOME is passed over for the newest installed").contains(newest);
        assertThat(KnowledgeBaseCreation.jdk(Map.of(), List.of(machine))).as("a macOS bundle's home").contains(bundled);
        assertThat(KnowledgeBaseCreation.jdk(Map.of(), List.of(root.resolve("named"), root.resolve("absent"))))
                .as("nothing recent enough").isEmpty();
    }

    @Test
    void whatIsMissingIsSaidAndARunIsGivenTheJdkAndGit(@TempDir Path root) throws IOException {
        Path jdk = jdk(root.resolve("jdk-27"), "27.0.1");
        RepositorySync.Git git = new RepositorySync.Git(root.resolve("tools/git"));
        KnowledgeBaseCreation.Prerequisites none = new KnowledgeBaseCreation.Prerequisites(Optional.empty(), Optional.empty());
        KnowledgeBaseCreation.Prerequisites both = new KnowledgeBaseCreation.Prerequisites(Optional.of(jdk), Optional.of(git));

        assertThat(none.missing()).containsExactly(
                "JDK 27 or later is not installed. Install one, or set JAVA_HOME to one.",
                "git is not installed. Install it, or set IKE_DASHBOARD_GIT to it.");
        assertThat(both.missing()).isEmpty();
        assertThat(both.environment(Map.of("PATH", "/usr/bin"))).as("a dashboard opened from the Finder has neither")
                .containsEntry("JAVA_HOME", jdk.toString())
                .containsEntry("PATH", String.join(java.io.File.pathSeparator, jdk.resolve("bin").toString(),
                        root.resolve("tools").toString(), "/usr/bin"));
        assertThatThrownBy(() -> new KnowledgeBaseCreation(none, "280", folder -> List.of("true"))
                .draft(project(root.resolve("kb")), line -> { }))
                .as("nothing is run without them").isInstanceOf(IOException.class).hasMessageContaining("JDK 27");
    }

    @Test
    void thePluginVersionIsTheDashboardsOwnUnlessTheEnvironmentNamesAnother() {
        assertThat(KnowledgeBaseCreation.toolingVersion(Map.of())).isEqualTo(KnowledgeBaseCreation.TOOLING_VERSION);
        assertThat(KnowledgeBaseCreation.toolingVersion(Map.of(KnowledgeBaseCreation.TOOLING_OVERRIDE, " 281-SNAPSHOT ")))
                .isEqualTo("281-SNAPSHOT");
    }

    @Test
    void theWrapperIsWrittenOnceAndItsScriptCanBeRun(@TempDir Path root) throws IOException {
        Path folder = root.resolve("my-kb");

        List<String> first = KnowledgeBaseCreation.writeWrapper(folder);
        Files.writeString(folder.resolve("mvnw.cmd"), "the person's own");
        Files.delete(folder.resolve("mvnw"));
        List<String> second = KnowledgeBaseCreation.writeWrapper(folder);

        assertThat(first).containsExactly("mvnw", "mvnw.cmd", ".mvn/wrapper/maven-wrapper.properties");
        assertThat(Files.isExecutable(folder.resolve("mvnw"))).isTrue();
        assertThat(Files.readString(folder.resolve(".mvn/wrapper/maven-wrapper.properties")))
                .as("Maven 4, fetched by the script alone").contains("distributionType=only-script", "apache-maven-4.");
        assertThat(second).as("only what is not there is written").containsExactly("mvnw");
        assertThat(Files.readString(folder.resolve("mvnw.cmd"))).isEqualTo("the person's own");
    }

    @Test
    void aDraftsReportIsReadWithEachLayerAsItWasPinned() {
        KnowledgeBaseCreation.Outcome outcome = new KnowledgeBaseCreation.Outcome(0, DRAFT);

        assertThat(outcome.succeeded()).isTrue();
        assertThat(outcome.report()).as("from the knowledge base's name to its last file, without Maven's framing")
                .startsWith("Knowledge base: Gene Try Knowledge Base (assembly)", "  Coordinates:  network.ike.data:gene-try-kb:1-SNAPSHOT")
                .endsWith("    .gitignore (standards scaffold)").hasSize(10);
        assertThat(outcome.pinned()).containsExactly(
                Map.entry(KnowledgeBaseProject.Layer.parse(
                        "network.ike.foundation:ike-starter-set:zip:unreasoned-pb:1-20261107.045844-2"), "0.9 MiB"),
                Map.entry(KnowledgeBaseProject.Layer.parse(
                        "network.ike.data:ncbi-gene-kb:zip:unreasoned-pb:20261020.1-20261111.021538-1"), "0.1 MiB"));
        assertThat(outcome.failure()).isEmpty();
    }

    @Test
    void aRefusalIsReadInTheGoalsWords() {
        KnowledgeBaseCreation.Outcome outcome = new KnowledgeBaseCreation.Outcome(1, REFUSED);

        assertThat(outcome.succeeded()).isFalse();
        assertThat(outcome.report()).isEmpty();
        assertThat(outcome.failure()).contains("Cannot pin the layers: network.ike.data:no-such-kb:zip:unreasoned-pb:1-SNAPSHOT"
                + " has never been deployed to https://nexus.tinkar.org/repository/ike-restricted/");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aDraftRunsTheGoalInAFolderOfItsOwnAndWritesNothingWhereTheProjectWillBe(@TempDir Path root) throws Exception {
        Path jdk = jdk(root.resolve("jdk-27"), "27.0.1");
        Path record = root.resolve("ran.txt");
        Path maven = standIn(root, record, false);
        KnowledgeBaseProject project = project(root.resolve("projects/my-kb"));
        List<String> heard = new ArrayList<>();

        KnowledgeBaseCreation.Outcome outcome = creation(jdk, maven).draft(project, heard::add);

        List<String> ran = Files.readAllLines(record);
        assertThat(outcome.succeeded()).isTrue();
        assertThat(heard).as("each line as it is written").isEqualTo(outcome.lines()).contains("[INFO] Knowledge base: my-kb");
        assertThat(ran).as("the draft goal, by coordinates, for the project's folder")
                .contains("-B", "-ntp", "network.ike.tooling:ike-maven-plugin:280:kb-create-draft", "-Dname=my-kb",
                        "-DtargetDirectory=" + project.folder(), "-Dlayers=" + GENE, "JAVA_HOME=" + jdk);
        assertThat(ran).as("run where a wrapper was written, apart from the project").anyMatch(line -> line.startsWith("wrapper=yes"));
        assertThat(Path.of(ran.getFirst().substring("cwd=".length()))).as("and that folder is gone again").doesNotExist();
        assertThat(root.resolve("projects")).as("nothing where the project will be").doesNotExist();
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aCreationWritesTheWrapperIntoTheProjectsFolderThenRunsThePublishGoalThere(@TempDir Path root) throws Exception {
        Path jdk = jdk(root.resolve("jdk-27"), "27.0.1");
        Path record = root.resolve("ran.txt");
        KnowledgeBaseProject project = project(root.resolve("projects/my-kb"));

        KnowledgeBaseCreation.Outcome outcome = creation(jdk, standIn(root, record, true)).create(project, line -> { });

        List<String> ran = Files.readAllLines(record);
        assertThat(outcome.succeeded()).isTrue();
        assertThat(ran).contains("cwd=" + project.folder().toRealPath(), "wrapper=yes",
                "network.ike.tooling:ike-maven-plugin:280:kb-create-publish", "-DtargetDirectory=" + project.folder());
        assertThat(project.folder().resolve("pom.xml")).exists();
        assertThat(project.folder().resolve("mvnw")).as("the wrapper stays with the project").isExecutable();
        assertThatThrownBy(() -> creation(jdk, standIn(root, record, true)).create(project, line -> { }))
                .as("creation runs once").isInstanceOf(IOException.class).hasMessageContaining("already contains a project");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aCreationThatFailsBeforeTheProjectIsWrittenLeavesNothingBehind(@TempDir Path root) throws Exception {
        Path jdk = jdk(root.resolve("jdk-27"), "27.0.1");
        Path made = root.resolve("projects/my-kb");
        Path there = Files.createDirectories(root.resolve("projects/already-there"));
        Files.writeString(there.resolve("notes.txt"), "the person's own");

        KnowledgeBaseCreation.Outcome first = creation(jdk, standIn(root, root.resolve("ran.txt"), false))
                .create(project(made), line -> { });
        KnowledgeBaseCreation.Outcome second = creation(jdk, standIn(root, root.resolve("ran.txt"), false))
                .create(project(there), line -> { });

        assertThat(first.succeeded()).as("the stand-in writes no project and fails").isFalse();
        assertThat(first.failure()).contains("Cannot pin the layers");
        assertThat(made).as("a folder the creation made is removed").doesNotExist();
        assertThat(second.succeeded()).isFalse();
        try (var entries = Files.list(there)) {
            assertThat(entries.map(path -> path.getFileName().toString())).as("a folder that was there keeps only what was in it")
                    .containsExactly("notes.txt");
        }
    }

    private static KnowledgeBaseCreation creation(Path jdk, Path maven) {
        KnowledgeBaseCreation.Prerequisites prerequisites = new KnowledgeBaseCreation.Prerequisites(Optional.of(jdk),
                Optional.of(new RepositorySync.Git(Path.of("/usr/bin/git"))));
        return new KnowledgeBaseCreation(prerequisites, "280", folder -> List.of(maven.toString()));
    }

    private static KnowledgeBaseProject project(Path folder) {
        return new KnowledgeBaseProject("my-kb", folder, Map.of("layers", GENE));
    }

    /** A folder that looks like a JDK: its release file and a compiler. */
    private static Path jdk(Path home, String version) throws IOException {
        Files.createDirectories(home.resolve("bin"));
        Files.writeString(home.resolve("release"), "IMPLEMENTOR=\"Test\"\nJAVA_VERSION=\"" + version + "\"\n");
        Path javac = Files.writeString(home.resolve("bin/javac"), "#!/bin/sh\n");
        assertThat(javac.toFile().setExecutable(true)).isTrue();
        return home;
    }

    /**
     * A script in Maven's place: it records where it ran, whether a wrapper was there, its arguments and
     * its JAVA_HOME, then either writes a POM where the goal would and succeeds, or fails as the goal does.
     */
    private static Path standIn(Path root, Path record, boolean succeeds) throws IOException {
        Path script = root.resolve(succeeds ? "maven-succeeds.sh" : "maven-refuses.sh");
        String body = """
                #!/bin/sh
                {
                  echo "cwd=$(pwd -P)"
                  if [ -x ./mvnw ] && [ -f .mvn/wrapper/maven-wrapper.properties ]; then echo "wrapper=yes"; else echo "wrapper=no"; fi
                  for argument in "$@"; do echo "$argument"; done
                  echo "JAVA_HOME=$JAVA_HOME"
                } > "%s"
                echo "[INFO] Knowledge base: my-kb"
                """.formatted(record);
        if (succeeds) {
            body += """
                    for argument in "$@"; do
                      case "$argument" in -DtargetDirectory=*) echo "<project/>" > "${argument#-DtargetDirectory=}/pom.xml";; esac
                    done
                    case "$*" in *kb-create-draft*) ;; *) exit 0;; esac
                    """;
        }
        // A draft succeeds either way: only the publish of the refusing stand-in fails.
        body += """
                case "$*" in *kb-create-draft*) exit 0;; esac
                echo "[ERROR] Failed to execute goal a:b:1:kb-create-publish (default-cli) on project standalone-pom: Cannot pin the layers"
                echo "[ERROR] "
                exit 1
                """;
        Files.writeString(script, body);
        assertThat(script.toFile().setExecutable(true)).isTrue();
        return script;
    }
}
