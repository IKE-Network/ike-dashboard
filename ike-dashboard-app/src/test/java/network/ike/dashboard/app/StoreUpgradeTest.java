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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The file work of an upgrade (decision 20 of {@code dev-ike-dashboard}): carry, swap, and the refusals. */
class StoreUpgradeTest {

    @TempDir
    Path solor;

    private final StoreUpgrade.Moves moves = new StoreUpgrade.Moves();

    @Test
    void changeSetsMergeAndPreferencesReplaceAndTheRestStays() throws IOException {
        Path store = store("pizza-kb");
        write(store.resolve("changeSets/src/main/resources/Author 20261101T100000PST AAA ike-cs.zip"), "mine");
        write(store.resolve("changeSets/.git/HEAD"), "ref: refs/heads/main");
        write(store.resolve("instanceChangeSets/Author 20261101T110000PST BBB ike-cs.zip"), "instances");
        write(store.resolve("preferences/preferences.xml"), "my windows");
        write(store.resolve("preferences/journals/journal_1/node.xml"), "journal");
        write(store.resolve("ephemeralChangeSets/Author ike-cs.zip"), "regenerated");
        write(store.resolve("notes/.git/HEAD"), "ref: refs/heads/main");
        write(store.resolve("notes/readme.md"), "a third repository");
        Path work = StoreUpgrade.workFolder(store);
        write(work.resolve("changeSets/src/main/resources/Author 20261106T000000PST ZZZ ike-cs.zip"), "the build's own");
        write(work.resolve("preferences/preferences.xml"), "the build's blank defaults");
        write(work.resolve("rocks/CURRENT"), "new data");

        StoreUpgrade.Carried carried = StoreUpgrade.carry(store, work, moves);

        assertThat(work.resolve("changeSets/src/main/resources")).isDirectoryContaining("glob:**AAA ike-cs.zip")
                .isDirectoryContaining("glob:**ZZZ ike-cs.zip");
        assertThat(work.resolve("changeSets/.git/HEAD")).as("the repository travels").hasContent("ref: refs/heads/main");
        assertThat(work.resolve("instanceChangeSets/Author 20261101T110000PST BBB ike-cs.zip")).hasContent("instances");
        assertThat(work.resolve("preferences/preferences.xml")).as("the person's session replaces the defaults").hasContent("my windows");
        assertThat(work.resolve("preferences/journals/journal_1/node.xml")).exists();
        assertThat(work.resolve("notes/readme.md")).as("any other repository moves whole").hasContent("a third repository");
        assertThat(work.resolve("ephemeralChangeSets")).doesNotExist();
        assertThat(store.resolve("ephemeralChangeSets/Author ike-cs.zip")).as("regenerated, so left behind").exists();
        assertThat(store.resolve("rocks/CURRENT")).as("the old data stays").hasContent("old data");
        assertThat(carried.conflicts()).isEmpty();
        assertThat(carried.moved()).isNotEmpty();
        assertThat(carried.summary()).contains("moved into the new version");
    }

    @Test
    void sharedAndLocalChangeSetsTravelAndThoseOfFilesStay() throws IOException {
        RepositorySync.Git git = git();
        Path store = store("pizza-kb");
        Path shared = sharedRepository(git, store);
        write(store.resolve("localChangeSets/Conversation journal module [abc] Author AAA ike-cs.zip"), "a conversation");
        write(store.resolve("instanceChangeSets/Conversation journal module [abc] Author BBB ike-cs.zip"), "an older one");
        write(store.resolve("fileChangeSets/adoc [def] Author CCC ike-cs.zip"), "read from its file again");
        write(store.resolve("loadedChangeSets.tsv"), "sha256:0\tAAA ike-cs.zip\n");
        String head = run(git, shared, "rev-parse", "HEAD");
        Path work = StoreUpgrade.workFolder(store);
        write(work.resolve("changeSets/src/main/resources/Author ZZZ ike-cs.zip"), "the build's own");
        write(work.resolve("rocks/CURRENT"), "new data");

        StoreUpgrade.Carried carried = StoreUpgrade.carry(store, work, moves);

        assertThat(carried.conflicts()).isEmpty();
        assertThat(carried.moved()).as("each folder moved whole").contains(work.resolve("sharedChangeSets"),
                work.resolve("localChangeSets"), work.resolve("instanceChangeSets"));
        assertThat(store.resolve("fileChangeSets/adoc [def] Author CCC ike-cs.zip")).as("left behind").exists();
        assertThat(work.resolve("fileChangeSets")).doesNotExist();
        assertThat(work.resolve("loadedChangeSets.tsv")).as("what Komet loaded into the old build, which the new one"
                + " has not loaded").doesNotExist();

        StoreUpgrade.swap(store, work);

        assertThat(store.resolve("rocks/CURRENT")).hasContent("new data");
        assertThat(store.resolve("localChangeSets/Conversation journal module [abc] Author AAA ike-cs.zip"))
                .hasContent("a conversation");
        assertThat(store.resolve("instanceChangeSets/Conversation journal module [abc] Author BBB ike-cs.zip"))
                .hasContent("an older one");
        assertThat(store.resolve("fileChangeSets")).doesNotExist();
        assertThat(run(git, shared, "rev-parse", "HEAD")).as("the same history").isEqualTo(head);
        assertThat(run(git, shared, "symbolic-ref", "--short", "HEAD").strip()).isEqualTo("shared");
        assertThat(run(git, shared, "status", "--porcelain")).as("nothing lost, nothing added").isBlank();
        RepositorySync.Status status = RepositorySync.local(git, new RepositorySync.Repository(shared, "shared"));
        assertThat(status.remote()).as("its remote travelled").isEqualTo(RepositorySync.Remote.UNCHECKED);
        assertThat(status.ahead()).isZero();
    }

    @Test
    void carryingBackReturnsSharedAndLocalChangeSets() throws IOException {
        RepositorySync.Git git = git();
        Path store = store("pizza-kb");
        Path shared = sharedRepository(git, store);
        write(store.resolve("localChangeSets/Conversation journal module [abc] Author AAA ike-cs.zip"), "a conversation");
        Path work = StoreUpgrade.workFolder(store);
        write(work.resolve("rocks/CURRENT"), "new data");

        StoreUpgrade.carry(store, work, moves);
        moves.undo();

        assertThat(store.resolve("localChangeSets/Conversation journal module [abc] Author AAA ike-cs.zip"))
                .hasContent("a conversation");
        assertThat(run(git, shared, "status", "--porcelain", "--branch").lines().toList())
                .containsExactly("## shared...origin/shared");
        assertThat(work.resolve("sharedChangeSets")).doesNotExist();
        assertThat(work.resolve("localChangeSets")).doesNotExist();
    }

    @Test
    void aBuildThatHasSharedChangeSetsOfItsOwnGetsTheRepositoryAndKeepsThem() throws IOException {
        RepositorySync.Git git = git();
        Path store = store("pizza-kb");
        Path shared = sharedRepository(git, store);
        String head = run(git, shared, "rev-parse", "HEAD");
        Path work = StoreUpgrade.workFolder(store);
        write(work.resolve("sharedChangeSets/Build [xyz] Author ZZZ ike-cs.zip"), "the build's own");
        write(work.resolve("sharedChangeSets/Lab documents [abc] Author AAA ike-cs.zip"),
                "Lab documents [abc] Author AAA ike-cs.zip"); // the same change set

        StoreUpgrade.Carried carried = StoreUpgrade.carry(store, work, moves);

        Path carriedTo = work.resolve("sharedChangeSets");
        assertThat(carried.conflicts()).isEmpty();
        assertThat(carried.merged()).contains(carriedTo.resolve(".git"));
        assertThat(run(git, carriedTo, "rev-parse", "HEAD")).isEqualTo(head);
        assertThat(run(git, carriedTo, "status", "--porcelain").lines().toList())
                .as("the build's own change set is there, for the next sync to add")
                .containsExactly("?? \"Build [xyz] Author ZZZ ike-cs.zip\"");
    }

    @Test
    void twoRepositoriesAreNeverMixed() throws IOException {
        RepositorySync.Git git = git();
        Path store = store("pizza-kb");
        Path shared = sharedRepository(git, store);
        commit(git, shared, "Not on the build's side ike-cs.zip");
        String mine = run(git, shared, "rev-parse", "HEAD");
        Path work = StoreUpgrade.workFolder(store);
        Path theirs = work.resolve("sharedChangeSets");
        Files.createDirectories(work);
        run(git, work, "clone", "-q", "--branch", "shared", solor.resolve("remote.git").toString(), theirs.toString());
        String build = run(git, theirs, "rev-parse", "HEAD");

        StoreUpgrade.Carried carried = StoreUpgrade.carry(store, work, moves);

        assertThat(carried.conflicts()).as("reported, so the upgrade stops").containsExactly(shared.resolve(".git"));
        assertThat(run(git, theirs, "rev-parse", "HEAD")).isEqualTo(build);
        assertThat(run(git, theirs, "status", "--porcelain")).isBlank();
        moves.undo();
        assertThat(run(git, shared, "rev-parse", "HEAD")).as("as it was").isEqualTo(mine);
        assertThat(run(git, shared, "status", "--porcelain")).isBlank();
        assertThat(run(git, shared, "fsck").lines().filter(line -> line.startsWith("dangling"))).isEmpty();
    }

    @Test
    void aFileTheNewBuildHasWithOtherBytesIsAConflictAndIsNeverWrittenOver() throws IOException {
        Path store = store("pizza-kb");
        write(store.resolve("changeSets/src/main/resources/same.zip"), "same");
        write(store.resolve("changeSets/src/main/resources/other.zip"), "mine, longer");
        write(store.resolve("changeSets/src/main/resources/size.zip"), "mine");
        Path work = StoreUpgrade.workFolder(store);
        write(work.resolve("changeSets/src/main/resources/same.zip"), "same");
        write(work.resolve("changeSets/src/main/resources/other.zip"), "theirs");
        write(work.resolve("changeSets/src/main/resources/size.zip"), "ours"); // as many bytes, and other ones

        StoreUpgrade.Carried carried = StoreUpgrade.carry(store, work, moves);

        assertThat(carried.conflicts()).containsExactly(store.resolve("changeSets/src/main/resources/other.zip"),
                store.resolve("changeSets/src/main/resources/size.zip"));
        assertThat(work.resolve("changeSets/src/main/resources/other.zip")).hasContent("theirs");
        assertThat(store.resolve("changeSets/src/main/resources/same.zip")).as("the same change set, left where it was").exists();
    }

    @Test
    void undoingACarryReturnsWhatWasMovedAndNothingOfTheNewBuilds() throws IOException {
        Path store = store("pizza-kb");
        write(store.resolve("changeSets/src/main/resources/mine.zip"), "mine");
        write(store.resolve("preferences/preferences.xml"), "my windows");
        write(store.resolve("notes/.git/HEAD"), "ref: refs/heads/main");
        Map<String, String> before = files(store);
        Path work = StoreUpgrade.workFolder(store);
        write(work.resolve("changeSets/src/main/resources/ZZZ.zip"), "the build's own");
        write(work.resolve("localChangeSets/during the check.zip"), "written by the check in Komet");
        write(work.resolve("preferences/preferences.xml"), "defaults");

        StoreUpgrade.carry(store, work, moves);
        assertThat(files(store)).isNotEqualTo(before);
        moves.undo();

        assertThat(files(store)).as("as it was").isEqualTo(before);
        assertThat(files(work)).containsOnlyKeys("changeSets/src/main/resources/ZZZ.zip",
                "localChangeSets/during the check.zip");
        moves.undo();
        assertThat(files(store)).as("a second time there is nothing to undo").isEqualTo(before);
    }

    @Test
    void aCarryThatFailsPartWayIsUndone() throws IOException {
        Path store = store("pizza-kb");
        write(store.resolve("changeSets/src/main/resources/mine.zip"), "mine");
        write(store.resolve("localChangeSets/conversation.zip"), "a conversation");
        write(store.resolve("preferences/preferences.xml"), "my windows");
        write(store.resolve("notes/lab/.git/HEAD"), "ref: refs/heads/main");
        Map<String, String> before = files(store);
        Path work = StoreUpgrade.workFolder(store);
        write(work.resolve("changeSets/src/main/resources/ZZZ.zip"), "the build's own");
        write(work.resolve("notes"), "a file where the repository's folder would go");

        assertThatThrownBy(() -> StoreUpgrade.carry(store, work, moves)).isInstanceOf(IOException.class);
        assertThat(work.resolve("localChangeSets/conversation.zip")).as("it got this far").exists();
        assertThat(work.resolve("preferences/preferences.xml")).hasContent("my windows");
        moves.undo();

        assertThat(files(store)).as("as it was").isEqualTo(before);
        assertThat(files(work)).containsOnlyKeys("changeSets/src/main/resources/ZZZ.zip", "notes");
    }

    @Test
    void aCarryThatFailsBeforeThePreferencesLeavesThePersonsInPlace() throws IOException {
        assumeTrue(OperatingSystem.current() != OperatingSystem.WINDOWS, "a symbolic link there needs a privilege");
        Path store = store("pizza-kb");
        write(store.resolve("changeSets/src/main/resources/mine.zip"), "mine");
        write(store.resolve("localChangeSets/conversation.zip"), "a conversation");
        write(store.resolve("preferences/preferences.xml"), "my windows");
        Map<String, String> before = files(store);
        Path work = StoreUpgrade.workFolder(store);
        write(work.resolve("preferences/preferences.xml"), "the build's blank defaults");
        // A link to nothing has the folder's name and is not a folder to merge into, so the move fails.
        Files.createSymbolicLink(work.resolve("localChangeSets"), solor.resolve("nothing"));

        assertThatThrownBy(() -> StoreUpgrade.carry(store, work, moves)).isInstanceOf(IOException.class);
        assertThat(work.resolve("changeSets/src/main/resources/mine.zip")).as("it got this far").exists();
        moves.undo();

        assertThat(files(store)).as("as it was, and not the build's defaults").isEqualTo(before);
    }

    @Test
    void whatCannotBeReturnedIsReportedAndTheRestReturns() throws IOException {
        Path store = store("pizza-kb");
        write(store.resolve("localChangeSets/conversation.zip"), "a conversation");
        write(store.resolve("changeSets/src/main/resources/mine.zip"), "mine");
        write(store.resolve("preferences/preferences.xml"), "my windows");
        Map<String, String> before = files(store);
        Path work = StoreUpgrade.workFolder(store);
        write(work.resolve("rocks/CURRENT"), "new data");
        StoreUpgrade.carry(store, work, moves);
        // Komet, opened on the old build meanwhile, wrote another file where one was.
        Path obstacle = store.resolve("changeSets/src/main/resources/mine.zip");
        write(obstacle, "ours"); // as many bytes, and other ones

        assertThatThrownBy(moves::undo).isInstanceOf(IOException.class).hasMessageContaining("mine.zip");
        assertThat(store.resolve("localChangeSets/conversation.zip")).hasContent("a conversation");
        assertThat(store.resolve("preferences/preferences.xml")).hasContent("my windows");
        assertThat(obstacle).as("never written over").hasContent("ours");
        assertThat(work.resolve("changeSets/src/main/resources/mine.zip")).as("kept").hasContent("mine");

        Files.delete(obstacle);
        moves.undo();
        assertThat(files(store)).as("what was left is tried again").isEqualTo(before);
    }

    @Test
    void aStoreThatIsItselfAGitRepositoryIsNotCarried() throws IOException {
        Path store = store("pizza-kb");
        write(store.resolve(".git/HEAD"), "ref: refs/heads/main");
        write(store.resolve("rocks/000001.sst"), "old table");
        write(store.resolve("changeSets/src/main/resources/mine.zip"), "mine");
        write(store.resolve("preferences/preferences.xml"), "my windows");
        Path work = StoreUpgrade.workFolder(store);
        // Nothing here is in conflict with the old build, so nothing else would stop its files from moving in.
        write(work.resolve("rocks/CURRENT"), "old data");
        write(work.resolve("rocks/000002.sst"), "new table");
        write(work.resolve("preferences/preferences.xml"), "the build's blank defaults");
        Map<String, String> before = files(store);
        Map<String, String> build = files(work);

        assertThatThrownBy(() -> StoreUpgrade.carry(store, work, moves)).isInstanceOf(IOException.class)
                .hasMessageContaining("is itself a git repository");
        moves.undo();

        assertThat(files(store)).as("the old build").isEqualTo(before);
        assertThat(files(work)).as("the new build").isEqualTo(build);
        assertThat(StoreUpgrade.cannotCarry(store).map(Names::plain)).as("so an upgrade says it before the download")
                .contains("pizza-kb is itself a git repository, and an upgrade cannot keep that repository. Use Install a new"
                        + " copy, which leaves this folder as it is.");
        assertThat(StoreUpgrade.refusals(knowledgeBase(store), List.of(), List.of()).stream().map(Names::plain))
                .as("a reinstall, which keeps nothing, is not stopped by it").noneMatch(refusal -> refusal.contains("itself"));
    }

    @Test
    void aStoreThatContainsRepositoriesCanBeCarried() throws IOException {
        Path store = store("pizza-kb");
        write(store.resolve("changeSets/.git/HEAD"), "ref: refs/heads/main");
        write(store.resolve("notes/.git/HEAD"), "ref: refs/heads/main");

        assertThat(StoreUpgrade.cannotCarry(store)).isEmpty();
    }

    @Test
    void anUpgradeThatFinishedIsNotUndone() throws IOException {
        Path store = store("pizza-kb");
        write(store.resolve("changeSets/src/main/resources/mine.zip"), "mine");
        Path work = StoreUpgrade.workFolder(store);
        write(work.resolve("rocks/CURRENT"), "new data");
        StoreUpgrade.carry(store, work, moves);
        StoreUpgrade.swap(store, work);
        Map<String, String> upgraded = files(store);

        moves.keep();
        moves.undo();

        assertThat(files(store)).isEqualTo(upgraded);
    }

    @Test
    void theSwapPutsTheNewBuildInPlaceAndTheOldOneAside() throws IOException {
        Path store = store("pizza-kb");
        write(store.resolve("preferences/preferences.xml"), "old session");
        Path work = StoreUpgrade.workFolder(store);
        write(work.resolve("rocks/CURRENT"), "new data");
        StoreUpgrade.carry(store, work, moves);

        StoreUpgrade.swap(store, work);

        Path previous = StoreUpgrade.previousFolder(store);
        assertThat(store.resolve("rocks/CURRENT")).hasContent("new data");
        assertThat(store.resolve("preferences/preferences.xml")).hasContent("old session");
        assertThat(previous.resolve("rocks/CURRENT")).as("aside, for the Trash").hasContent("old data");
        assertThat(work).doesNotExist();
        assertThat(previous.resolve("preferences")).as("carried into the new build, not left behind").doesNotExist();
    }

    @Test
    void theSwapRefusesWhenAPreviousBuildIsStillThere() throws IOException {
        Path store = store("pizza-kb");
        Files.createDirectories(StoreUpgrade.previousFolder(store));
        Files.createDirectories(StoreUpgrade.workFolder(store));

        assertThatThrownBy(() -> StoreUpgrade.swap(store, StoreUpgrade.workFolder(store)))
                .isInstanceOf(IOException.class).hasMessageContaining("already exists");
        assertThat(store.resolve("rocks/CURRENT")).hasContent("old data");
    }

    @Test
    void refusalsNameAnOpenStoreASpoolUnsyncedWorkAndALeftoverWorkingFolder() throws IOException {
        Path store = store("pizza-kb");
        write(store.resolve("changeSets/src/main/resources/Author ike-cs.spool"), "being written");
        Files.createDirectories(StoreUpgrade.workFolder(store));
        KnowledgeBase copy = knowledgeBase(store);
        RepositorySync.Repository repository = new RepositorySync.Repository(store.resolve("changeSets"), "change sets");
        RepositorySync.Status status = new RepositorySync.Status(repository, "main", 2, 1, RepositorySync.Remote.CURRENT, 0,
                Optional.empty(), "");
        HeapSizing.Running open = new HeapSizing.Running(4242, Optional.of(store), 1, 0);

        List<String> refusals = StoreUpgrade.refusals(copy, List.of(status), List.of(open));

        assertThat(refusals).hasSize(5);
        assertThat(refusals.get(0)).contains("is open in Komet. Quit Komet");
        assertThat(refusals.get(1)).contains("still writing").contains("ike-cs.spool");
        assertThat(refusals.get(2)).contains("2 files not committed in change sets");
        assertThat(refusals.get(3)).contains("1 commit not pushed from change sets");
        assertThat(refusals.get(4)).contains("pizza-kb.upgrade").contains("Move it to the");
    }

    @Test
    void aStoreKometIsWritingSharedOrLocalChangeSetsInIsRefused() throws IOException {
        Path store = store("pizza-kb");
        Path shared = Path.of("sharedChangeSets", "Lab documents [abc] Author ike-cs.spool");
        Path local = Path.of("localChangeSets", "Conversation journal module [abc] Author ike-cs.spool");
        write(store.resolve(shared), "being written");
        write(store.resolve(local), "being written");
        write(store.resolve("fileChangeSets/adoc [def] Author ike-cs.spool"), "read from its file again");

        List<String> refusals = StoreUpgrade.refusals(knowledgeBase(store), List.of(), List.of());

        assertThat(refusals.stream().map(Names::plain)).containsExactly(
                "Komet is still writing " + shared + "; quit it first.",
                "Komet is still writing " + local + "; quit it first.");
    }

    @Test
    void aLeftoverFolderThatContainsWorkIsNotToBeMovedToTheTrash() throws IOException {
        Path store = store("pizza-kb");
        write(store.resolve("changeSets/.git/HEAD"), "ref: refs/heads/main");
        write(store.resolve("changeSets/src/main/resources/Author AAA ike-cs.zip"), "mine");
        write(store.resolve("changeSets/src/main/resources/Author BBB ike-cs.zip"), "mine too");
        write(store.resolve("localChangeSets/Conversation journal module [abc] Author CCC ike-cs.zip"), "a conversation");
        write(store.resolve("preferences/preferences.xml"), "my windows");
        Path notes = Path.of("documents", "notes");
        write(store.resolve(notes).resolve(".git/HEAD"), "ref: refs/heads/main");
        Path work = StoreUpgrade.workFolder(store);
        write(work.resolve("rocks/CURRENT"), "new data");
        StoreUpgrade.carry(store, work, moves);

        assertThat(refusals(store)).as("the dashboard stopped here").containsExactly("An earlier upgrade left the folder"
                + " pizza-kb.upgrade next to this one, and it contains work that is not in pizza-kb: the git repository"
                + " changeSets with 2 change sets, 1 change set in localChangeSets, the folder preferences and the git"
                + " repository " + notes + ". Do not move the folder to the " + Finder.trashName() + " yet, because it may"
                + " contain the only copy. First move that work to the same place in pizza-kb, without replacing anything"
                + " there.");

        moves.undo();

        assertThat(refusals(store)).as("with everything back, it is only the new build").containsExactly("An earlier"
                + " upgrade left the folder pizza-kb.upgrade next to this one. Move it to the " + Finder.trashName()
                + " to upgrade again.");
    }

    @Test
    void whatTheStoreHasTooIsNotWorkInALeftoverFolder() throws IOException {
        Path store = store("pizza-kb");
        write(store.resolve("changeSets/.git/HEAD"), "ref: refs/heads/main");
        write(store.resolve("changeSets/src/main/resources/same.zip"), "mine");
        write(store.resolve("changeSets/src/main/resources/differs.zip"), "mine");
        write(store.resolve("preferences/preferences.xml"), "my windows");
        Path work = StoreUpgrade.workFolder(store);
        write(work.resolve("changeSets/.git/HEAD"), "ref: refs/heads/main");
        write(work.resolve("changeSets/src/main/resources/same.zip"), "mine");
        write(work.resolve("preferences/preferences.xml"), "the build's blank defaults");
        write(work.resolve("fileChangeSets/adoc [def] Author DDD ike-cs.zip"), "read from its file again");
        write(work.resolve("loadedChangeSets.tsv"), "sha256:0\tAAA ike-cs.zip\n");
        assertThat(refusals(store)).as("nothing that is not in the store").containsExactly("An earlier upgrade left the"
                + " folder pizza-kb.upgrade next to this one. Move it to the " + Finder.trashName() + " to upgrade again.");

        write(work.resolve("changeSets/src/main/resources/differs.zip"), "ours");

        assertThat(refusals(store)).as("the same name with other bytes is not the same change set").containsExactly(
                "An earlier upgrade left the folder pizza-kb.upgrade next to this one, and it contains work that is not in"
                + " pizza-kb: 1 change set in changeSets. Do not move the folder to the " + Finder.trashName() + " yet, because"
                + " it may contain the only copy. First move that work to the same place in pizza-kb, without replacing"
                + " anything there.");
    }

    private List<String> refusals(Path store) throws IOException {
        return StoreUpgrade.refusals(knowledgeBase(store), List.of(), List.of()).stream().map(Names::plain).toList();
    }

    @Test
    void aQuietStoreHasNoRefusals() throws IOException {
        Path store = store("pizza-kb");
        HeapSizing.Running other = new HeapSizing.Running(1, Optional.of(solor.resolve("other-kb")), 1, 0);

        assertThat(StoreUpgrade.refusals(knowledgeBase(store), List.of(), List.of(other))).isEmpty();
    }

    @Test
    void theScannerDoesNotListAWorkingFolder() throws IOException {
        store("pizza-kb");
        Files.createDirectories(StoreUpgrade.workFolder(solor.resolve("pizza-kb")).resolve("rocks"));
        Files.createDirectories(solor.resolve("pizza-copy" + StoreCopy.PARTIAL_SUFFIX).resolve("rocks"));
        Files.createDirectories(StoreUpgrade.previousFolder(solor.resolve("pizza-kb")).resolve("rocks"));

        assertThat(KnowledgeBaseScanner.scan(solor)).as("a build left aside is a folder like any other")
                .extracting(KnowledgeBase::name).containsExactly("pizza-kb", "pizza-kb.previous");
        assertThat(StoreUpgrade.isWork("pizza-kb.upgrade")).isTrue();
    }

    private Path store(String name) throws IOException {
        Path store = solor.resolve(name);
        write(store.resolve("rocks/CURRENT"), "old data");
        return store;
    }

    /** The shared change sets of a store, cloned from a remote on the branch {@code shared}, with everything pushed. */
    private Path sharedRepository(RepositorySync.Git git, Path store) throws IOException {
        Path seed = Files.createDirectories(solor.resolve("seed"));
        run(git, seed, "init", "-q");
        run(git, seed, "symbolic-ref", "HEAD", "refs/heads/shared");
        commit(git, seed, "Lab documents [abc] Author AAA ike-cs.zip");
        run(git, solor, "clone", "-q", "--bare", seed.toString(), solor.resolve("remote.git").toString());
        Path shared = store.resolve("sharedChangeSets");
        run(git, store, "clone", "-q", "--branch", "shared", solor.resolve("remote.git").toString(), shared.toString());
        return shared;
    }

    private static RepositorySync.Git git() {
        Optional<RepositorySync.Git> found = RepositorySync.Git.find();
        assumeTrue(found.isPresent(), "no git installed where the dashboard looks for it");
        return found.get();
    }

    private static void commit(RepositorySync.Git git, Path folder, String file) throws IOException {
        Files.writeString(folder.resolve(file), file);
        run(git, folder, "add", file);
        run(git, folder, "-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "-q", "-m", "Add " + file);
    }

    private static String run(RepositorySync.Git git, Path folder, String... args) {
        RepositorySync.Git.Result result = git.run(folder, Duration.ofSeconds(30), args);
        assertThat(result.ok()).as("git %s: %s", String.join(" ", args), result.err()).isTrue();
        return result.out();
    }

    private static KnowledgeBase knowledgeBase(Path store) {
        return new KnowledgeBase(store.getFileName().toString(), store, StoreKind.ROCKS, 10, Optional.empty(), Map.of(),
                Instant.parse("2026-11-01T00:00:00Z"));
    }

    /** Each file under a folder and what is in it, by its path with slashes. */
    private static Map<String, String> files(Path folder) throws IOException {
        Map<String, String> files = new TreeMap<>();
        try (Stream<Path> paths = Files.walk(folder)) {
            for (Path file : paths.filter(Files::isRegularFile).toList()) {
                files.put(folder.relativize(file).toString().replace('\\', '/'), Files.readString(file));
            }
        }
        return files;
    }

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }
}
