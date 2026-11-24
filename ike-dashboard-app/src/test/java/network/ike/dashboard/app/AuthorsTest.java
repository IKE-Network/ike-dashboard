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

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The two authors the dashboard starts Komet as, from {@code ~/.ike/authors.properties}
 * (IKE-Network/ike-issues#1399, steps 1 and 2), read from real files.
 */
class AuthorsTest {

    private static final String ADA = """
            person.name=Ada Lovelace
            person.email=ada@komet.ike.network
            assistant.name=Ada's Claude
            assistant.email=ada-claude@komet.ike.network
            """;

    @Nested
    class TheFile {

        @Test
        void aFileWithBothNamesThePersonAndTheirAssistant(@TempDir Path home) throws IOException {
            Authors.Reading reading = Authors.read(write(home, ADA));

            assertThat(reading).isInstanceOf(Authors.Found.class);
            Authors authors = reading.found().orElseThrow();
            assertThat(authors.person()).isEqualTo(new Authors.Author("Ada Lovelace", "ada@komet.ike.network"));
            assertThat(authors.assistant()).contains(new Authors.Author("Ada's Claude", "ada-claude@komet.ike.network"));
            assertThat(authors.person().ident()).isEqualTo("Ada Lovelace <ada@komet.ike.network>");
        }

        @Test
        void theAssistantMayBeLeftOut(@TempDir Path home) throws IOException {
            Authors.Reading reading = Authors.read(write(home, "person.name=Ada Lovelace\nperson.email=ada@komet.ike.network\n"));

            assertThat(reading.found()).hasValueSatisfying(authors -> assertThat(authors.assistant()).isEmpty());
        }

        @Test
        void theFileIsAuthorsPropertiesInDotIke(@TempDir Path home) {
            assertThat(Authors.file(home)).isEqualTo(home.resolve(".ike").resolve("authors.properties"));
        }

        @Test
        void noFileGivesNoAuthorsAndNoGitVariables(@TempDir Path home) {
            Authors.Reading reading = Authors.read(Authors.file(home));

            assertThat(reading).isEqualTo(new Authors.Absent(Authors.file(home)));
            assertThat(reading.found()).isEmpty();
            assertThat(reading.gitEnvironment()).isEmpty();
        }

        @Test
        void aPersonNamedInPartIsRefusedWithTheKeyThatIsMissing(@TempDir Path home) throws IOException {
            Authors.Reading reading = Authors.read(write(home, "person.name=Ada Lovelace\n"));

            assertThat(reading).isInstanceOfSatisfying(Authors.Unreadable.class,
                    unreadable -> assertThat(unreadable.problem()).contains("person.email"));
            assertThat(reading.gitEnvironment()).as("nothing is taken from a file that cannot be used").isEmpty();
        }

        @Test
        void anAssistantNamedInPartIsRefused(@TempDir Path home) throws IOException {
            Authors.Reading reading = Authors.read(write(home,
                    "person.name=Ada Lovelace\nperson.email=ada@komet.ike.network\nassistant.name=Ada's Claude\n"));

            assertThat(reading).isInstanceOfSatisfying(Authors.Unreadable.class,
                    unreadable -> assertThat(unreadable.problem()).isEqualTo(
                            "assistant.name and assistant.email go together, and it has only assistant.name"));
        }

        @Test
        void anEmailThatIsNoAddressIsRefused(@TempDir Path home) throws IOException {
            Authors.Reading reading = Authors.read(write(home, "person.name=Ada Lovelace\nperson.email=ada at komet\n"));

            assertThat(reading).isInstanceOfSatisfying(Authors.Unreadable.class,
                    unreadable -> assertThat(unreadable.problem()).contains("ada at komet", "name@domain"));
        }

        @Test
        void aNameGitWouldRefuseIsRefused(@TempDir Path home) throws IOException {
            Authors.Reading reading = Authors.read(write(home, "person.name=Ada <Lovelace>\nperson.email=ada@komet.ike.network\n"));

            assertThat(reading).isInstanceOfSatisfying(Authors.Unreadable.class,
                    unreadable -> assertThat(unreadable.problem()).contains("git refuses"));
        }

        @Test
        void aFileThatIsNoPropertiesFileIsRefused(@TempDir Path home) throws IOException {
            Authors.Reading reading = Authors.read(write(home, "person.name=\\u12\n"));

            assertThat(reading).isInstanceOfSatisfying(Authors.Unreadable.class,
                    unreadable -> assertThat(unreadable.problem()).startsWith("it cannot be read"));
        }

        @Test
        void keysItDoesNotKnowArePassedOver(@TempDir Path home) throws IOException {
            Authors.Reading reading = Authors.read(write(home, ADA + "person.signingKey=ssh-ed25519 AAAA\n"));

            assertThat(reading).isInstanceOf(Authors.Found.class);
        }

        @Test
        void theFileMayListTheAddressesOfThePersonsEarlierCommits(@TempDir Path home) throws IOException {
            Authors authors = Authors.read(write(home, ADA
                    + "person.earlier=ada@example.test, ada@engine.test,ADA@example.test\n")).found().orElseThrow();

            assertThat(authors.earlier()).containsExactly("ada@example.test", "ada@engine.test");
            assertThat(authors.withoutAssistant().earlier()).isEqualTo(authors.earlier());
            assertThat(authors.gitEnvironment().values()).doesNotContain("ada@example.test", "ada@engine.test");
        }

        @Test
        void theFileNeedListNoEarlierAddress(@TempDir Path home) throws IOException {
            assertThat(Authors.read(write(home, ADA)).found().orElseThrow().earlier()).isEmpty();
            assertThat(Authors.read(write(home, ADA + "person.earlier=\n")).found().orElseThrow().earlier()).isEmpty();
        }

        @Test
        void anEarlierAddressGitWouldNotTakeIsRefused(@TempDir Path home) throws IOException {
            assertThat(Authors.read(write(home, ADA + "person.earlier=ada@example.test, Ada Lovelace\n")))
                    .isInstanceOfSatisfying(Authors.Unreadable.class, unreadable -> assertThat(unreadable.problem())
                            .contains("person.earlier lists Ada Lovelace"));
        }

        @Test
        void anIdentityAddressIsNotAnEarlierOne(@TempDir Path home) throws IOException {
            assertThat(Authors.read(write(home, ADA + "person.earlier=ADA@komet.ike.network\n")))
                    .isInstanceOfSatisfying(Authors.Unreadable.class, unreadable -> assertThat(unreadable.problem())
                            .endsWith("the identity address of Ada Lovelace"));
            assertThat(Authors.read(write(home, ADA + "person.earlier=ada-claude@komet.ike.network\n")))
                    .isInstanceOfSatisfying(Authors.Unreadable.class, unreadable -> assertThat(unreadable.problem())
                            .endsWith("the identity address of Ada's Claude"));
        }

        @Test
        void namesAreReadAsUtf8(@TempDir Path home) throws IOException {
            Authors.Reading reading = Authors.read(write(home, "person.name=Jürgen Müller\nperson.email=jm@komet.ike.network\n"));

            assertThat(reading.found().map(authors -> authors.person().name())).contains("Jürgen Müller");
        }
    }

    @Nested
    class Commits {

        @Test
        void thePersonIsBothAuthorAndCommitter(@TempDir Path home) throws IOException {
            assertThat(Authors.read(write(home, ADA)).gitEnvironment()).containsExactly(
                    Map.entry("GIT_AUTHOR_NAME", "Ada Lovelace"),
                    Map.entry("GIT_AUTHOR_EMAIL", "ada@komet.ike.network"),
                    Map.entry("GIT_COMMITTER_NAME", "Ada Lovelace"),
                    Map.entry("GIT_COMMITTER_EMAIL", "ada@komet.ike.network"));
        }

        @Test
        void aKometLaunchSetsTheHeapAndThePerson(@TempDir Path home) throws IOException {
            Path folder = home.resolve("komet-26.1101.900");
            KometInstallation komet = new KometInstallation("Komet 26.1101.900", "26.1101.900", folder,
                    folder.resolve("bin").resolve("launchKomet"));

            Map<String, String> variables = KometLauncher.launcherEnvironment(komet,
                    Map.of(HeapSizing.MAX_HEAP_VARIABLE, "8g"), Authors.read(write(home, ADA)));

            assertThat(variables).containsEntry(HeapSizing.MAX_HEAP_VARIABLE, "8g")
                    .containsEntry("GIT_AUTHOR_EMAIL", "ada@komet.ike.network")
                    .containsEntry("GIT_COMMITTER_EMAIL", "ada@komet.ike.network");
            assertThat(KometLauncher.launcherEnvironment(komet, Map.of(HeapSizing.MAX_HEAP_VARIABLE, "8g"),
                    Authors.read(Authors.file(home.resolve("nobody")))))
                    .as("with no file, a launch is as it was").containsExactly(Map.entry(HeapSizing.MAX_HEAP_VARIABLE, "8g"));
        }

        @Test
        void gitAsThePersonCarriesTheirIdentityAndGitAsNobodyNone(@TempDir Path home) throws IOException {
            RepositorySync.Git git = new RepositorySync.Git(home.resolve("git"));

            assertThat(git.identity()).isEmpty();
            assertThat(git.as(Authors.read(write(home, ADA))).identity()).containsEntry("GIT_COMMITTER_NAME", "Ada Lovelace");
            assertThat(git.as(Authors.read(Authors.file(home.resolve("nobody")))))
                    .as("no file, no identity").isEqualTo(git);
        }

        @Test
        void aBuildIsGivenGitsIdentity(@TempDir Path home) throws IOException {
            KnowledgeBaseCreation.Prerequisites prerequisites = new KnowledgeBaseCreation.Prerequisites(Optional.empty(),
                    Optional.of(new RepositorySync.Git(home.resolve("git")).as(Authors.read(write(home, ADA)))));

            assertThat(prerequisites.environment(Map.of("PATH", home.toString())))
                    .containsEntry("GIT_AUTHOR_NAME", "Ada Lovelace")
                    .containsEntry("GIT_COMMITTER_EMAIL", "ada@komet.ike.network");
        }
    }

    @Nested
    class WithNoAssistant {

        @Test
        void theVariableLeavesTheAssistantOutForAProcessAndTheToolsItStarts(@TempDir Path home) throws IOException {
            write(home, ADA);

            assertThat(Authors.current(home, Map.of()).found().orElseThrow().assistant())
                    .contains(new Authors.Author("Ada's Claude", "ada-claude@komet.ike.network"));
            Authors.Reading alone = Authors.current(home, Map.of("IKE_ASSISTANT", "none"));
            assertThat(alone.found().orElseThrow().person()).isEqualTo(new Authors.Author("Ada Lovelace", "ada@komet.ike.network"));
            assertThat(alone.found().orElseThrow().assistant()).isEmpty();
            assertThat(alone.file()).isEqualTo(Authors.file(home));
        }

        @Test
        void onlyNoneLeavesTheAssistantOut() {
            assertThat(Authors.leavesAssistantOut(Map.of("IKE_ASSISTANT", " None "))).isTrue();
            assertThat(Authors.leavesAssistantOut(Map.of("IKE_ASSISTANT", ""))).isFalse();
            assertThat(Authors.leavesAssistantOut(Map.of("IKE_ASSISTANT", "Ada's Claude"))).isFalse();
            assertThat(Authors.leavesAssistantOut(Map.of())).isFalse();
        }

        @Test
        void noFileAndAFileThatCannotBeUsedStayAsTheyAre(@TempDir Path home) throws IOException {
            assertThat(Authors.current(home, Map.of("IKE_ASSISTANT", "none"))).isInstanceOf(Authors.Absent.class);
            write(home, "person.name=Ada Lovelace\n");
            assertThat(Authors.current(home, Map.of("IKE_ASSISTANT", "none"))).isInstanceOf(Authors.Unreadable.class);
        }

        @Test
        void theCommandThenPrintsThePersonAlone(@TempDir Path home) throws IOException {
            Path file = write(home, ADA);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();

            DashboardCommand.authors(new PrintStream(bytes, true, StandardCharsets.UTF_8),
                    Authors.current(home, Map.of("IKE_ASSISTANT", "none")));

            assertThat(bytes.toString(StandardCharsets.UTF_8)).isEqualTo(String.join(System.lineSeparator(),
                    "person     Ada Lovelace <ada@komet.ike.network>", "from       " + file, ""));
        }
    }

    @Nested
    class TheCommand {

        @Test
        void printsThePersonTheAssistantAndTheFile(@TempDir Path home) throws IOException {
            Path file = write(home, ADA);

            assertThat(print(Authors.read(file))).isEqualTo(String.join(System.lineSeparator(),
                    "person     Ada Lovelace <ada@komet.ike.network>",
                    "assistant  Ada's Claude <ada-claude@komet.ike.network>",
                    "from       " + file, ""));
        }

        @Test
        void printsEachEarlierAddress(@TempDir Path home) throws IOException {
            Path file = write(home, ADA + "person.earlier=ada@example.test,ada@engine.test\n");

            assertThat(print(Authors.read(file))).isEqualTo(String.join(System.lineSeparator(),
                    "person     Ada Lovelace <ada@komet.ike.network>",
                    "assistant  Ada's Claude <ada-claude@komet.ike.network>",
                    "earlier    ada@example.test",
                    "earlier    ada@engine.test",
                    "from       " + file, ""));
        }

        @Test
        void stopsWhenThereIsNoFile(@TempDir Path home) {
            assertThatThrownBy(() -> print(Authors.read(Authors.file(home))))
                    .isInstanceOf(IllegalStateException.class).hasMessageStartingWith("No authors: ");
        }

        @Test
        void stopsAndSaysWhyWhenTheFileCannotBeUsed(@TempDir Path home) throws IOException {
            Authors.Reading reading = Authors.read(write(home, "person.name=Ada Lovelace\n"));

            assertThatThrownBy(() -> print(reading)).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("cannot be used").hasMessageContaining("person.email");
        }

        private static String print(Authors.Reading reading) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DashboardCommand.authors(new PrintStream(bytes, true, StandardCharsets.UTF_8), reading);
            return bytes.toString(StandardCharsets.UTF_8);
        }
    }

    private static Path write(Path home, String text) throws IOException {
        Path file = Authors.file(home);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, text, StandardCharsets.UTF_8);
    }
}
