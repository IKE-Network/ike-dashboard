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

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Giving a knowledge base's change-set repository its remote with git alone (decision 21), against real
 * repositories: a bare repository stands for the git host. A check changes nothing and says what adding
 * will do; adding pushes only what adds to the remote, and never merges or loads change sets.
 */
class RepositoryRemoteTest {

    private static final Duration WAIT = Duration.ofSeconds(30);
    private static RepositorySync.Git git;

    @BeforeAll
    static void findGit() {
        Optional<RepositorySync.Git> found = RepositorySync.Git.find();
        assumeTrue(found.isPresent(), "no git installed where the dashboard looks for it");
        git = found.get();
    }

    @Test
    void aChangeSetFolderWithNoRepositoryIsMadeOneAndPushedAsKometWouldLeaveIt(@TempDir Path temp) throws IOException {
        Path store = temp.resolve("solor-kb");
        Path changeSets = Files.createDirectories(store.resolve("changeSets"));
        Files.writeString(changeSets.resolve("edits.zip"), "a change set Komet wrote");
        String remote = bare(temp.resolve("host/solor-changes.git"));

        Optional<RepositorySync.Status> before = RepositorySync.changeSetsNotYet(store);
        RepositoryRemote.Check check = RepositoryRemote.check(git, changeSets, " " + remote + " ");
        boolean untouched = Files.notExists(changeSets.resolve(".git")) && Files.notExists(changeSets.resolve("README.md"));
        RepositoryRemote.add(git, changeSets, remote);

        assertThat(before).as("the dashboard says the folder is no repository yet")
                .hasValueSatisfying(status -> {
                    assertThat(status.isRepository()).isFalse();
                    assertThat(status.repository().label()).isEqualTo("change sets");
                    assertThat(status.remote()).isEqualTo(RepositorySync.Remote.NONE);
                });
        assertThat(check.refusal()).isEmpty();
        assertThat(check.url()).as("the address, trimmed").isEqualTo(remote);
        assertThat(check.steps()).containsExactly(
                "Make changeSets a git repository, on the branch main.",
                "Set its remote, origin, to " + remote + ".",
                "Commit a README.md, since neither side has anything yet, and push it. Komet does the same when it connects.",
                "Komet's own sync is still connected in Komet, with the same address.");
        assertThat(untouched).as("a check changes nothing").isTrue();
        assertThat(out(changeSets, "remote", "get-url", "origin")).isEqualTo(remote);
        assertThat(out(changeSets, "log", "--format=%s")).isEqualTo("Initial commit for changeset exchange");
        assertThat(out(changeSets, "status", "--porcelain")).as("the change sets are left for Komet's sync to commit")
                .isEqualTo("?? edits.zip");
        assertThat(out(temp.resolve("host/solor-changes.git"), "rev-parse", "refs/heads/main"))
                .as("the remote has the branch Komet's sync looks for").isEqualTo(out(changeSets, "rev-parse", "HEAD"));
        assertThat(out(changeSets, "rev-parse", "--abbrev-ref", "main@{upstream}")).isEqualTo("origin/main");
        assertThat(RepositorySync.changeSetsNotYet(store)).as("it is a repository now").isEmpty();
        assertThat(RepositoryRemote.check(git, changeSets, remote).refusal()).contains("It already has a remote.");
    }

    @Test
    void aRepositoryWithItsOwnCommitsIsPushedToAnEmptyRemote(@TempDir Path temp) throws IOException {
        Path changeSets = repository(temp.resolve("solor-kb/changeSets"), "edits.zip");
        String remote = bare(temp.resolve("host/solor-changes.git"));

        RepositoryRemote.Check check = RepositoryRemote.add(git, changeSets, remote);

        assertThat(check.steps()).containsExactly("Set its remote, origin, to " + remote + ".",
                "Push the branch main, which then follows the remote.",
                "Komet's own sync is still connected in Komet, with the same address.");
        assertThat(out(temp.resolve("host/solor-changes.git"), "rev-parse", "refs/heads/main")).isEqualTo(out(changeSets, "rev-parse", "HEAD"));
        assertThat(out(changeSets, "log", "--format=%s")).as("no README is committed where there is history").isEqualTo("Change sets");
    }

    @Test
    void aRemoteWithOtherChangeSetsIsSetAndNothingIsPushedOrMerged(@TempDir Path temp) throws IOException {
        Path theirs = repository(temp.resolve("other-machine/changeSets"), "theirs.zip");
        String remote = bare(temp.resolve("host/solor-changes.git"));
        RepositoryRemote.add(git, theirs, remote);
        String remoteHead = out(temp.resolve("host/solor-changes.git"), "rev-parse", "refs/heads/main");
        Path mine = repository(temp.resolve("solor-kb/changeSets"), "mine.zip");
        String myHead = out(mine, "rev-parse", "HEAD");
        Path fresh = Files.createDirectories(temp.resolve("new-kb/changeSets"));

        RepositoryRemote.Check diverged = RepositoryRemote.add(git, mine, remote);
        RepositoryRemote.Check empty = RepositoryRemote.add(git, fresh, remote);

        assertThat(diverged.pushes()).isFalse();
        assertThat(diverged.steps()).contains("Push nothing: the remote already has change sets this folder does not have."
                + " Komet's sync pulls them and loads them.");
        assertThat(out(mine, "remote", "get-url", "origin")).as("the remote is set all the same").isEqualTo(remote);
        assertThat(out(mine, "rev-parse", "HEAD")).as("nothing is merged into this folder").isEqualTo(myHead);
        assertThat(out(temp.resolve("host/solor-changes.git"), "rev-parse", "refs/heads/main")).as("and nothing is pushed over theirs")
                .isEqualTo(remoteHead);
        assertThat(empty.pushes()).as("a folder with nothing of its own joins a remote that has change sets, and pushes nothing").isFalse();
        assertThat(out(fresh, "remote", "get-url", "origin")).isEqualTo(remote);
        assertThat(fresh.resolve("README.md")).doesNotExist();
    }

    @Test
    void whatCannotBeAddedIsRefusedWithNothingChanged(@TempDir Path temp) throws IOException {
        Path changeSets = Files.createDirectories(temp.resolve("solor-kb/changeSets"));
        String absent = temp.resolve("host/absent.git").toUri().toString();

        assertThat(RepositoryRemote.check(git, changeSets, "my repository").refusal())
                .contains("That is not a git address. Use https://…, ssh://…, or user@host:path.");
        assertThat(RepositoryRemote.check(git, changeSets, absent).refusal()).hasValueSatisfying(why ->
                assertThat(why).startsWith("The remote could not be read: ").contains("does not appear to be a git repository")
                        .endsWith(". Create the repository on your git host first, and check that you are signed in."));
        assertThatThrownBy(() -> RepositoryRemote.add(git, changeSets, absent)).isInstanceOf(IOException.class)
                .hasMessageContaining("The remote could not be read");
        assertThat(changeSets.resolve(".git")).as("a refusal makes no repository").doesNotExist();
    }

    @Test
    void preferencesAreCommittedOnABranchNamedForTheirKnowledgeBaseSoOneRemoteKeepsThemAll(@TempDir Path temp) throws IOException {
        Path solor = Files.createDirectories(temp.resolve("solor-kb/preferences"));
        Files.writeString(solor.resolve("preferences.xml"), "<map/>");
        Files.writeString(Files.createDirectories(solor.resolve("journals")).resolve("journal_1.xml"), "<map/>");
        Path pizza = Files.createDirectories(temp.resolve("pizza-kb-authoring/preferences"));
        Files.writeString(pizza.resolve("preferences.xml"), "<map/>");
        Path bare = temp.resolve("host/my-komet-preferences.git");
        String remote = bare(bare);

        RepositoryRemote.Check check = RepositoryRemote.check(git, solor, remote, "solor-kb", true);
        boolean untouched = Files.notExists(solor.resolve(".git"));
        RepositoryRemote.add(git, solor, remote, "solor-kb", true);
        RepositoryRemote.add(git, pizza, remote, "pizza-kb", true);

        assertThat(check.steps()).containsExactly(
                "Make preferences a git repository, on the branch solor-kb.",
                "Commit what is in it now: 2 files.",
                "Set its remote, origin, to " + remote + ".",
                "Push the branch solor-kb, which then follows the remote.",
                "Later changes are sent only when you choose Commit and push.");
        assertThat(untouched).as("a check changes nothing").isTrue();
        assertThat(out(solor, "log", "--format=%s")).as("named for the folder they are the preferences of").isEqualTo("Preferences of solor-kb");
        assertThat(out(solor, "status", "--porcelain")).as("everything in the folder is committed").isEmpty();
        assertThat(out(solor, "rev-parse", "--abbrev-ref", "solor-kb@{upstream}")).isEqualTo("origin/solor-kb");
        assertThat(out(bare, "for-each-ref", "--format=%(refname:short)", "refs/heads")).as("one remote, a branch for each knowledge base")
                .isEqualTo("pizza-kb\nsolor-kb");
        assertThat(out(bare, "rev-parse", "refs/heads/solor-kb")).isEqualTo(out(solor, "rev-parse", "HEAD"));
    }

    @Test
    void preferencesOnAnotherMachineAreNeverMergedAndAnEmptyFolderIsRefused(@TempDir Path temp) throws IOException {
        Path other = Files.createDirectories(temp.resolve("other-machine/solor-kb/preferences"));
        Files.writeString(other.resolve("preferences.xml"), "<map>theirs</map>");
        String remote = bare(temp.resolve("host/my-komet-preferences.git"));
        RepositoryRemote.add(git, other, remote, "solor-kb", true);
        String theirs = out(temp.resolve("host/my-komet-preferences.git"), "rev-parse", "refs/heads/solor-kb");
        Path mine = Files.createDirectories(temp.resolve("solor-kb/preferences"));
        Files.writeString(mine.resolve("preferences.xml"), "<map>mine</map>");
        Path empty = Files.createDirectories(temp.resolve("new-kb/preferences"));

        RepositoryRemote.Check diverged = RepositoryRemote.add(git, mine, remote, "solor-kb", true);

        assertThat(diverged.pushes()).isFalse();
        assertThat(diverged.steps()).contains("Push nothing: the remote already has preferences on the branch solor-kb"
                + " that this folder does not have. Nothing is merged.");
        assertThat(out(mine, "log", "--format=%s")).as("this machine's are committed here all the same").isEqualTo("Preferences of solor-kb");
        assertThat(out(mine, "remote", "get-url", "origin")).isEqualTo(remote);
        assertThat(out(temp.resolve("host/my-komet-preferences.git"), "rev-parse", "refs/heads/solor-kb"))
                .as("the other machine's are untouched on the remote").isEqualTo(theirs);
        assertThat(RepositoryRemote.check(git, empty, remote, "new-kb", true).refusal())
                .contains("There are no preferences in it yet. Open the knowledge base in Komet once first.");
        assertThat(empty.resolve(".git")).doesNotExist();
    }

    @Test
    void sendingPreferencesIsThePersonsChoiceWhenTheRemoteIsAddedAndAfterwards(@TempDir Path temp) throws IOException {
        Path preferences = Files.createDirectories(temp.resolve("solor-kb/preferences"));
        Files.writeString(preferences.resolve("preferences.xml"), "<map/>");
        Path bare = temp.resolve("host/my-komet-preferences.git");
        String remote = bare(bare);

        RepositoryRemote.Check notNow = RepositoryRemote.add(git, preferences, remote, "solor-kb", false);
        String afterAdd = out(preferences, "status", "--porcelain");
        String branchesAfterAdd = out(bare, "for-each-ref", "--format=%(refname:short)", "refs/heads");
        String first = RepositoryRemote.send(git, preferences);
        String again = RepositoryRemote.send(git, preferences);
        Files.writeString(preferences.resolve("preferences.xml"), "<map>a window moved</map>");
        Files.writeString(preferences.resolve("journal.xml"), "<map/>");
        String later = RepositoryRemote.send(git, preferences);

        assertThat(notNow.steps()).as("the remote is set, and nothing is sent, when the person clears the choice").containsExactly(
                "Make preferences a git repository, on the branch solor-kb.",
                "Set its remote, origin, to " + remote + ".",
                "Commit and push nothing now, as you chose. Commit and push sends them when you want.");
        assertThat(afterAdd).as("nothing was committed").isEqualTo("?? preferences.xml");
        assertThat(branchesAfterAdd).as("and nothing pushed").isEmpty();
        assertThat(first).isEqualTo("Committed 1 file and pushed.");
        assertThat(again).isEqualTo("Nothing has changed since the last push.");
        assertThat(later).isEqualTo("Committed 2 files and pushed.");
        assertThat(out(bare, "rev-parse", "refs/heads/solor-kb")).isEqualTo(out(preferences, "rev-parse", "HEAD"));
        assertThat(out(preferences, "log", "--format=%s").lines()).hasSize(2);
    }

    @Test
    void sendingNeverMergesWithWhatAnotherMachinePushed(@TempDir Path temp) throws IOException {
        Path other = Files.createDirectories(temp.resolve("other-machine/solor-kb/preferences"));
        Files.writeString(other.resolve("preferences.xml"), "<map>theirs</map>");
        Path bare = temp.resolve("host/my-komet-preferences.git");
        String remote = bare(bare);
        RepositoryRemote.add(git, other, remote, "solor-kb", true);
        String theirs = out(bare, "rev-parse", "refs/heads/solor-kb");
        Path mine = Files.createDirectories(temp.resolve("solor-kb/preferences"));
        Files.writeString(mine.resolve("preferences.xml"), "<map>mine</map>");
        RepositoryRemote.add(git, mine, remote, "solor-kb", false);
        Path none = Files.createDirectories(temp.resolve("new-kb/preferences"));

        assertThatThrownBy(() -> RepositoryRemote.send(git, mine)).isInstanceOf(IOException.class)
                .hasMessage("Committed 1 file here. Nothing was pushed: the remote has preferences from another machine that"
                        + " this folder does not have, and the dashboard does not merge them.");
        assertThat(out(bare, "rev-parse", "refs/heads/solor-kb")).as("theirs are untouched").isEqualTo(theirs);
        assertThat(out(mine, "log", "--format=%s")).as("mine are committed here, so nothing is lost").isEqualTo("Preferences of solor-kb");
        assertThatThrownBy(() -> RepositoryRemote.send(git, none)).isInstanceOf(IOException.class)
                .hasMessage("It has no remote yet. Add one first.");
    }

    /** A repository with one commit and no remote, as a store's change-set folder is before it is connected. */
    private static Path repository(Path folder, String file) throws IOException {
        Files.createDirectories(folder);
        Files.writeString(folder.resolve(file), "a change set");
        run(folder, "init", "-q", "-b", "main");
        run(folder, "config", "user.name", "Test Author");
        run(folder, "config", "user.email", "test@example.invalid");
        run(folder, "config", "commit.gpgsign", "false");
        run(folder, "add", "-A");
        run(folder, "commit", "-q", "-m", "Change sets");
        return folder;
    }

    /** An empty bare repository, standing for one just made on a git host. */
    private static String bare(Path folder) throws IOException {
        Files.createDirectories(folder);
        run(folder, "init", "-q", "--bare", "-b", "main");
        return folder.toUri().toString().replaceFirst("/$", "");
    }

    private static String out(Path folder, String... args) {
        return run(folder, args).out().strip();
    }

    private static RepositorySync.Git.Result run(Path folder, String... args) {
        RepositorySync.Git.Result result = git.run(folder, WAIT, args);
        assertThat(result.ok()).as("git %s: %s", String.join(" ", args), result.err()).isTrue();
        return result;
    }
}
