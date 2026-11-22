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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.prefs.Preferences;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who a Komet is opened as (KEC, 2026-11-22; IKE-Network/ike-issues#1399): the choices a file of authors
 * gives, what each sets on a launch, and the choice kept for each folder. Read from real files, kept in a
 * real preferences node, and started as a real process.
 */
class OpenAsTest {

    private static final String ADA = """
            person.name=Ada Lovelace
            person.email=ada@komet.ike.network
            assistant.name=Ada's Claude
            assistant.email=ada-claude@komet.ike.network
            """;
    private static final String ADA_ALONE = """
            person.name=Ada Lovelace
            person.email=ada@komet.ike.network
            """;

    @Nested
    class TheChoices {

        @Test
        void aFileWithAnAssistantGivesThePersonWithItAndWithNone(@TempDir Path home) throws IOException {
            List<OpenAs> choices = OpenAs.choices(Authors.read(write(home, ADA)));

            assertThat(choices).extracting(OpenAs::assistant).containsExactly(OpenAs.Assistant.NAMED, OpenAs.Assistant.NONE);
            assertThat(choices).extracting(choice -> choice.label().orElseThrow()).containsExactly(
                    "Ada Lovelace, assisted by Ada's Claude", "Ada Lovelace, with no assistant");
        }

        @Test
        void aFileWithNoAssistantGivesThePersonAlone(@TempDir Path home) throws IOException {
            Authors.Reading file = Authors.read(write(home, ADA_ALONE));

            assertThat(OpenAs.choices(file)).extracting(choice -> choice.label().orElseThrow()).containsExactly("Ada Lovelace");
            OpenAs kept = OpenAs.of(file, OpenAs.Assistant.NONE);
            assertThat(kept.assistant()).as("nothing to leave out, so a kept choice is passed over")
                    .isEqualTo(OpenAs.Assistant.NAMED);
            assertThat(kept.leavesAssistantOut()).isFalse();
            assertThat(kept.environment()).doesNotContainKey(Authors.ASSISTANT_VARIABLE);
        }

        @Test
        void noFileAndAFileThatCannotBeUsedGiveNoChoice(@TempDir Path home) throws IOException {
            Authors.Reading absent = Authors.read(Authors.file(home.resolve("nobody")));
            Authors.Reading unreadable = Authors.read(write(home, "person.name=Ada Lovelace\n"));

            for (Authors.Reading file : List.of(absent, unreadable)) {
                assertThat(OpenAs.choices(file)).isEmpty();
                OpenAs openAs = OpenAs.of(file, OpenAs.Assistant.NONE);
                assertThat(openAs.label()).isEmpty();
                assertThat(openAs.environment()).as("Komet starts as it always has").isEmpty();
                assertThat(openAs.arguments(komet(home, "26.1201.900"))).isEmpty();
            }
        }

        @Test
        void eachChoiceSaysWhatItDoesWithEachAuthorAsGitShowsOne(@TempDir Path home) throws IOException {
            Authors.Reading file = Authors.read(write(home, ADA));

            assertThat(OpenAs.of(file, OpenAs.Assistant.NAMED).detail()).contains(
                    "Komet opens as Ada Lovelace <ada@komet.ike.network>. Their own work is committed as them."
                            + " Work the assistant does is committed as Ada's Claude <ada-claude@komet.ike.network>,"
                            + " with Ada Lovelace as committer.");
            assertThat(OpenAs.of(file, OpenAs.Assistant.NONE).detail()).contains(
                    "Komet opens as Ada Lovelace <ada@komet.ike.network>. Every commit names them, the work of"
                            + " Ada's Claude included.");
        }
    }

    @Nested
    class ALaunch {

        @Test
        void thePersonReachesEveryKometThroughGitsVariables(@TempDir Path home) throws IOException {
            Authors.Reading file = Authors.read(write(home, ADA));

            assertThat(OpenAs.of(file, OpenAs.Assistant.NAMED).environment()).containsExactly(
                    Map.entry("GIT_AUTHOR_NAME", "Ada Lovelace"),
                    Map.entry("GIT_AUTHOR_EMAIL", "ada@komet.ike.network"),
                    Map.entry("GIT_COMMITTER_NAME", "Ada Lovelace"),
                    Map.entry("GIT_COMMITTER_EMAIL", "ada@komet.ike.network"));
        }

        @Test
        void noAssistantReachesKometAsAVariableBesideGits(@TempDir Path home) throws IOException {
            OpenAs alone = OpenAs.of(Authors.read(write(home, ADA)), OpenAs.Assistant.NONE);

            assertThat(alone.leavesAssistantOut()).isTrue();
            assertThat(alone.authors().found().orElseThrow().assistant()).isEmpty();
            assertThat(alone.environment()).containsEntry("IKE_ASSISTANT", "none")
                    .containsEntry("GIT_AUTHOR_EMAIL", "ada@komet.ike.network").hasSize(5);
            assertThat(KometLauncher.launcherEnvironment(komet(home, "26.1201.900"),
                    Map.of(HeapSizing.MAX_HEAP_VARIABLE, "8g"), alone))
                    .containsEntry(HeapSizing.MAX_HEAP_VARIABLE, "8g").containsEntry("IKE_ASSISTANT", "none");
        }

        @Test
        void aKometThatTakesTheChoiceIsToldThePersonWithUser(@TempDir Path home) throws IOException {
            OpenAs openAs = OpenAs.of(Authors.read(write(home, ADA)), OpenAs.Assistant.NAMED);
            KometInstallation takes = komet(home, OpenAs.SINCE + ".900");
            KnowledgeBase store = store(home);

            assertThat(OpenAs.taken(takes)).isTrue();
            assertThat(KometLauncher.openedAs(takes, KometLauncher.command(takes, store), openAs)).containsExactly(
                    takes.executable().toString(), "--kb=" + store.path(), "--user=Ada Lovelace");
        }

        @Test
        void anOlderKometIsStartedAsBefore(@TempDir Path home) throws IOException {
            // It would write a warning in its log at each launch: --user names no user of the knowledge base.
            OpenAs openAs = OpenAs.of(Authors.read(write(home, ADA)), OpenAs.Assistant.NONE);
            KometInstallation older = komet(home, "26.1113.1558");
            KnowledgeBase store = store(home);

            assertThat(OpenAs.taken(older)).isFalse();
            assertThat(KometLauncher.openedAs(older, KometLauncher.command(older, store), openAs)).containsExactly(
                    older.executable().toString(), "--kb=" + store.path());
        }

        @Test
        void aNameWindowsWouldReadAsItsOwnIsLeftOutThere(@TempDir Path home) throws IOException {
            Path folder = home.resolve("komet-" + OpenAs.SINCE + ".900");
            KometInstallation batch = new KometInstallation("Komet", OpenAs.SINCE + ".900", folder,
                    folder.resolve("bin").resolve("launchKomet.bat"));
            KometInstallation shell = komet(home, OpenAs.SINCE + ".900");

            OpenAs plain = OpenAs.of(Authors.read(write(home, ADA)), OpenAs.Assistant.NAMED);
            assertThat(plain.arguments(batch)).containsExactly("--user=Ada Lovelace");

            OpenAs quoted = OpenAs.of(Authors.read(write(home, ADA.replace("Ada Lovelace", "Ada \"Countess\" Lovelace"))),
                    OpenAs.Assistant.NAMED);
            assertThat(quoted.arguments(batch)).as("Java refuses a batch file an argument with a quotation mark").isEmpty();
            assertThat(quoted.arguments(shell)).containsExactly("--user=Ada \"Countess\" Lovelace");
            assertThat(quoted.environment()).as("git's variables carry the name as it is")
                    .containsEntry("GIT_AUTHOR_NAME", "Ada \"Countess\" Lovelace");

            assertThat(OpenAs.travelsThroughBatch("Seán O'Brien-Núñez, Jr.")).isTrue();
            assertThat(OpenAs.travelsThroughBatch("100% Ada")).isFalse();
        }

        @Test
        @DisabledOnOs(OS.WINDOWS)
        void theProcessStartedHasTheArgumentAndTheVariables(@TempDir Path home) throws Exception {
            // A stand-in for a Komet's launcher: a real process, which prints what it was started with.
            Path folder = home.resolve("komet-" + OpenAs.SINCE + ".900");
            Path launcher = Files.createDirectories(folder.resolve("bin")).resolve("launchKomet");
            Files.writeString(launcher, """
                    #!/bin/sh
                    for argument in "$@"; do echo "argument=$argument"; done
                    echo "assistant=${IKE_ASSISTANT:-unset}"
                    echo "author=$GIT_AUTHOR_NAME <$GIT_AUTHOR_EMAIL>"
                    echo "heap=$KOMET_MAX_HEAP"
                    echo "done"
                    """, StandardCharsets.UTF_8);
            Files.setPosixFilePermissions(launcher, PosixFilePermissions.fromString("rwxr-xr-x"));
            KometInstallation komet = new KometInstallation("Komet", OpenAs.SINCE + ".900", folder, launcher);
            KnowledgeBase store = store(home);
            Authors.Reading file = Authors.read(write(home, ADA));

            Path alone = KometLauncher.launch(komet, store, Map.of(HeapSizing.MAX_HEAP_VARIABLE, "8g"),
                    OpenAs.of(file, OpenAs.Assistant.NONE), home.resolve("logs-alone"));
            assertThat(finished(alone)).containsExactly("argument=--kb=" + store.path(), "argument=--user=Ada Lovelace",
                    "assistant=none", "author=Ada Lovelace <ada@komet.ike.network>", "heap=8g", "done");

            Path assisted = KometLauncher.launch(komet, store, Map.of(HeapSizing.MAX_HEAP_VARIABLE, "8g"),
                    OpenAs.of(file, OpenAs.Assistant.NAMED), home.resolve("logs-assisted"));
            assertThat(finished(assisted)).contains("argument=--user=Ada Lovelace", "assistant=unset");
        }

        /** The lines of a stand-in launcher's log, once it has printed its last. */
        private static List<String> finished(Path log) throws Exception {
            Instant limit = Instant.now().plus(Duration.ofSeconds(30));
            while (Instant.now().isBefore(limit)) {
                List<String> lines = Files.exists(log) ? Files.readAllLines(log) : List.of();
                if (lines.contains("done")) {
                    return lines;
                }
                Thread.sleep(50);
            }
            throw new AssertionError("The launcher did not finish: " + (Files.exists(log) ? Files.readString(log) : "no log"));
        }
    }

    @Nested
    class KeptForEachFolder {

        /** A node of its own, removed after each test, so a test never touches the person's choices. */
        private final Preferences node = Preferences.userRoot().node("network/ike/dashboard-test/open-as-" + UUID.randomUUID());

        @AfterEach
        void removeTestNode() throws Exception {
            node.removeNode();
        }

        @Test
        void aFolderStartsWithTheAssistantAndKeepsWhatIsChosen(@TempDir Path home) throws Exception {
            OpenAsChoices choices = new OpenAsChoices(node);
            Path demo = home.resolve("Solor").resolve("pizza-demo");
            Path check = home.resolve("Solor").resolve("pizza-check");
            Authors.Reading file = Authors.read(write(home, ADA));

            assertThat(choices.of(demo)).isEqualTo(OpenAs.Assistant.NAMED);
            choices.choose(demo, OpenAs.Assistant.NONE);

            assertThat(new OpenAsChoices(node).of(demo)).as("kept across a restart").isEqualTo(OpenAs.Assistant.NONE);
            assertThat(choices.of(check)).as("another folder of the same knowledge base keeps its own")
                    .isEqualTo(OpenAs.Assistant.NAMED);
            assertThat(choices.openAs(file, demo).label()).contains("Ada Lovelace, with no assistant");
            assertThat(choices.openAs(file, check).label()).contains("Ada Lovelace, assisted by Ada's Claude");

            choices.choose(demo, OpenAs.Assistant.NAMED);
            assertThat(choices.of(demo)).isEqualTo(OpenAs.Assistant.NAMED);
            assertThat(node.keys()).as("only a folder opened with no assistant is written down").isEmpty();
        }

        @Test
        void withNowhereToKeepThemEveryFolderOpensWithTheAssistant(@TempDir Path home) throws Exception {
            Path demo = home.resolve("Solor").resolve("pizza-demo");
            OpenAsChoices.NONE.choose(demo, OpenAs.Assistant.NONE);
            assertThat(OpenAsChoices.NONE.of(demo)).isEqualTo(OpenAs.Assistant.NAMED);
        }
    }

    private static Path write(Path home, String content) throws IOException {
        Path file = Authors.file(home);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static KometInstallation komet(Path home, String version) {
        Path folder = home.resolve("komet-" + version);
        return new KometInstallation("Komet " + version, version, folder, folder.resolve("bin").resolve("launchKomet"));
    }

    private static KnowledgeBase store(Path home) {
        return new KnowledgeBase("pizza-kb", home.resolve("Solor").resolve("pizza-kb"), StoreKind.ROCKS, 10,
                Optional.empty(), Map.of(), Instant.parse("2026-11-01T00:00:00Z"));
    }
}
