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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The file work of an upgrade (decision 20 of {@code dev-ike-dashboard}): carry, swap, restore, and the refusals. */
class StoreUpgradeTest {

    @TempDir
    Path solor;

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

        StoreUpgrade.Carried carried = StoreUpgrade.carry(store, work);

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
        assertThat(carried.summary()).contains("carried");
    }

    @Test
    void aFileTheNewBuildHasWithOtherBytesIsAConflictAndIsNeverWrittenOver() throws IOException {
        Path store = store("pizza-kb");
        write(store.resolve("changeSets/src/main/resources/same.zip"), "same");
        write(store.resolve("changeSets/src/main/resources/other.zip"), "mine, longer");
        Path work = StoreUpgrade.workFolder(store);
        write(work.resolve("changeSets/src/main/resources/same.zip"), "same");
        write(work.resolve("changeSets/src/main/resources/other.zip"), "theirs");

        StoreUpgrade.Carried carried = StoreUpgrade.carry(store, work);

        assertThat(carried.conflicts()).containsExactly(store.resolve("changeSets/src/main/resources/other.zip"));
        assertThat(work.resolve("changeSets/src/main/resources/other.zip")).hasContent("theirs");
        assertThat(store.resolve("changeSets/src/main/resources/same.zip")).as("the same change set, left where it was").exists();
    }

    @Test
    void carryingBackUndoesACarry() throws IOException {
        Path store = store("pizza-kb");
        write(store.resolve("changeSets/src/main/resources/mine.zip"), "mine");
        write(store.resolve("preferences/preferences.xml"), "my windows");
        Path work = StoreUpgrade.workFolder(store);
        write(work.resolve("preferences/preferences.xml"), "defaults");

        StoreUpgrade.carry(store, work);
        StoreUpgrade.carry(work, store);

        assertThat(store.resolve("changeSets/src/main/resources/mine.zip")).hasContent("mine");
        assertThat(store.resolve("preferences/preferences.xml")).hasContent("my windows");
        assertThat(work.resolve("changeSets")).doesNotExist();
    }

    @Test
    void theSwapRenamesBothAndRestoreSwapsBackCarryingTheWorkSince() throws IOException {
        Path store = store("pizza-kb");
        write(store.resolve("preferences/preferences.xml"), "old session");
        Path work = StoreUpgrade.workFolder(store);
        write(work.resolve("rocks/CURRENT"), "new data");
        StoreUpgrade.carry(store, work);

        StoreUpgrade.swap(store, work);

        Path previous = StoreUpgrade.previousFolder(store);
        assertThat(store.resolve("rocks/CURRENT")).hasContent("new data");
        assertThat(previous.resolve("rocks/CURRENT")).hasContent("old data");
        assertThat(work).doesNotExist();
        assertThat(previous.resolve("preferences")).as("carried into the new build, not left behind").doesNotExist();

        write(store.resolve("changeSets/src/main/resources/since.zip"), "written in the new build");
        write(store.resolve("preferences/preferences.xml"), "new session");
        StoreUpgrade.Carried back = StoreUpgrade.restore(store);

        assertThat(store.resolve("rocks/CURRENT")).as("the previous build is back").hasContent("old data");
        assertThat(store.resolve("changeSets/src/main/resources/since.zip")).hasContent("written in the new build");
        assertThat(store.resolve("preferences/preferences.xml")).as("the latest session wins").hasContent("new session");
        assertThat(previous.resolve("rocks/CURRENT")).as("the new build is now the previous one").hasContent("new data");
        assertThat(work).doesNotExist();
        assertThat(back.moved()).as("the change-set folder and the preferences, moved whole").hasSize(2);
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
        assertThat(refusals.get(0)).contains("open in a Komet (process 4242)");
        assertThat(refusals.get(1)).contains("still writing").contains("ike-cs.spool");
        assertThat(refusals.get(2)).contains("2 files not committed in change sets");
        assertThat(refusals.get(3)).contains("1 commit not pushed from change sets");
        assertThat(refusals.get(4)).contains("pizza-kb.upgrade").contains("kept for inspection");
    }

    @Test
    void aQuietStoreHasNoRefusals() throws IOException {
        Path store = store("pizza-kb");
        HeapSizing.Running other = new HeapSizing.Running(1, Optional.of(solor.resolve("other-kb")), 1, 0);

        assertThat(StoreUpgrade.refusals(knowledgeBase(store), List.of(), List.of(other))).isEmpty();
    }

    @Test
    void thePreviousBuildIsTheMemberNamedAfterTheStoreAndNeverTheLatest() {
        KnowledgeBase current = new KnowledgeBase("pizza-kb", solor.resolve("pizza-kb"), StoreKind.ROCKS, 10,
                Optional.of(new Coordinate("network.ike.data", "pizza-kb", "1-SNAPSHOT")),
                Map.of(KnowledgeBase.RESOLVED_VERSION, "1-20261101.000000-5"), Instant.parse("2026-11-01T00:00:00Z"));
        KnowledgeBase previous = new KnowledgeBase("pizza-kb.previous", solor.resolve("pizza-kb.previous"), StoreKind.ROCKS, 10,
                Optional.of(new Coordinate("network.ike.data", "pizza-kb", "1-SNAPSHOT")),
                Map.of(KnowledgeBase.RESOLVED_VERSION, "1-20261106.000000-6"), Instant.parse("2026-11-06T00:00:00Z"));

        KnowledgeBaseFamily family = KnowledgeBaseFamily.group(List.of(current, previous)).getFirst();

        assertThat(family.members()).as("a restore can leave the newer build as the previous one").startsWith(previous);
        assertThat(family.latest()).contains(current);
        assertThat(family.latestIdentified()).contains(current);
        assertThat(StoreUpgrade.previous(family)).contains(previous);
        assertThat(StoreUpgrade.isWork("pizza-kb.upgrade")).isTrue();
        assertThat(StoreUpgrade.isPrevious("pizza-kb")).isFalse();
    }

    @Test
    void theScannerDoesNotListAWorkingFolder() throws IOException {
        store("pizza-kb");
        Files.createDirectories(StoreUpgrade.workFolder(solor.resolve("pizza-kb")).resolve("rocks"));
        Files.createDirectories(StoreUpgrade.previousFolder(solor.resolve("pizza-kb")).resolve("rocks"));

        assertThat(KnowledgeBaseScanner.scan(solor)).extracting(KnowledgeBase::name)
                .containsExactly("pizza-kb", "pizza-kb.previous");
    }

    private Path store(String name) throws IOException {
        Path store = solor.resolve(name);
        write(store.resolve("rocks/CURRENT"), "old data");
        return store;
    }

    private static KnowledgeBase knowledgeBase(Path store) {
        return new KnowledgeBase(store.getFileName().toString(), store, StoreKind.ROCKS, 10, Optional.empty(), Map.of(),
                Instant.parse("2026-11-01T00:00:00Z"));
    }

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }
}
