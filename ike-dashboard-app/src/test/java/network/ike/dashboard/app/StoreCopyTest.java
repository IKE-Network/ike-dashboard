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

/**
 * Copy to a new folder (KEC, 2026-11-08): the whole store, its change sets, preferences and repositories included,
 * into a folder beside it, made under a working name the scanner skips and renamed once whole.
 */
class StoreCopyTest {

    @TempDir
    Path solor;

    @Test
    void theWholeStoreIsCopiedAndTheOriginalIsUntouched() throws IOException {
        Path store = solor.resolve("pizza-kb");
        write(store.resolve("rocks/CURRENT"), "data");
        write(store.resolve("changeSets/src/main/resources/mine.zip"), "mine");
        write(store.resolve("changeSets/.git/HEAD"), "ref: refs/heads/main");
        write(store.resolve("preferences/preferences.xml"), "my windows");

        Path copy = StoreCopy.copy(store, "  pizza-teaching ");

        assertThat(copy).isEqualTo(solor.resolve("pizza-teaching"));
        assertThat(copy.resolve("rocks/CURRENT")).hasContent("data");
        assertThat(copy.resolve("changeSets/src/main/resources/mine.zip")).hasContent("mine");
        assertThat(copy.resolve("changeSets/.git/HEAD")).as("repositories travel with their history")
                .hasContent("ref: refs/heads/main");
        assertThat(copy.resolve("preferences/preferences.xml")).hasContent("my windows");
        assertThat(store.resolve("changeSets/src/main/resources/mine.zip")).hasContent("mine");
        assertThat(solor.resolve("pizza-teaching" + StoreCopy.PARTIAL_SUFFIX)).doesNotExist();

        write(copy.resolve("changeSets/src/main/resources/only-here.zip"), "the copy's own");
        assertThat(store.resolve("changeSets/src/main/resources/only-here.zip")).as("separate copies").doesNotExist();
    }

    @Test
    void theCopyByBytesIsWhatAVolumeThatCannotCloneGets() throws IOException {
        Path store = solor.resolve("pizza-kb");
        write(store.resolve("rocks/CURRENT"), "data");

        StoreCopy.copyTree(store, solor.resolve("by-bytes"));

        assertThat(solor.resolve("by-bytes/rocks/CURRENT")).hasContent("data");
    }

    @Test
    void namesAreSuggestedFreeAndRefusedWhenTakenOrTheDashboardsOwn() throws IOException {
        Path store = solor.resolve("pizza-kb");
        Files.createDirectories(store);
        Files.createDirectories(solor.resolve("pizza-kb-copy"));

        assertThat(StoreCopy.suggestedName(store)).isEqualTo("pizza-kb-copy-2");
        assertThat(StoreCopy.nameProblem(store, "pizza-kb-copy")).hasValueSatisfying(why -> assertThat(why).contains("already"));
        assertThat(StoreCopy.nameProblem(store, "")).isPresent();
        assertThat(StoreCopy.nameProblem(store, "a/b")).isPresent();
        assertThat(StoreCopy.nameProblem(store, ".hidden")).isPresent();
        assertThat(StoreCopy.nameProblem(store, "x.upgrade")).isPresent();
        assertThat(StoreCopy.nameProblem(store, "x.previous")).isPresent();
        assertThat(StoreCopy.nameProblem(store, "pizza-teaching")).isEmpty();
        assertThatThrownBy(() -> StoreCopy.copy(store, "pizza-kb-copy")).isInstanceOf(IOException.class);
    }

    @Test
    void aStoreOpenInKometOrBeingWrittenIsNotCopied() throws IOException {
        Path store = solor.resolve("pizza-kb");
        write(store.resolve("changeSets/src/main/resources/Author ike-cs.spool"), "being written");
        KnowledgeBase copy = new KnowledgeBase("pizza-kb", store, StoreKind.ROCKS, 10, Optional.empty(), Map.of(),
                Instant.parse("2026-11-01T00:00:00Z"));

        List<String> refusals = StoreCopy.refusals(copy, List.of(new HeapSizing.Running(4242, Optional.of(store), 1, 0)));

        assertThat(refusals).hasSize(2);
        assertThat(refusals.get(0)).contains("is open in Komet. Quit Komet");
        assertThat(refusals.get(1)).contains("still writing");
    }

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }
}
