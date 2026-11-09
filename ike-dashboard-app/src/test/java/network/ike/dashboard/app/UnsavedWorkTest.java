package network.ike.dashboard.app;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Move to Trash says what in a folder is saved nowhere else (KEC, 2026-11-08), read from its git repositories'
 * own files: files not committed, commits not pushed, history with no remote, and change sets in no repository.
 */
class UnsavedWorkTest {

    private static final Duration WAIT = Duration.ofSeconds(30);
    private static RepositorySync.Git git;

    @BeforeAll
    static void findGit() {
        Optional<RepositorySync.Git> found = RepositorySync.Git.find();
        assumeTrue(found.isPresent(), "no git installed where the dashboard looks for it");
        git = found.get();
    }

    @Test
    void eachRepositorysUnsavedWorkIsNamedAndAPushedOneIsNot(@TempDir Path temp) throws IOException {
        Path remote = temp.resolve("remote.git");
        Path seed = temp.resolve("seed");
        init(seed);
        commit(seed, "first.zip");
        run(temp, "clone", "-q", "--bare", seed.toString(), remote.toString());
        Path store = temp.resolve("ike-kb");
        Files.createDirectories(store);
        run(store, "clone", "-q", remote.toString(), store.resolve("changeSets").toString());
        commit(store.resolve("changeSets"), "second.zip");                       // committed, not pushed
        Files.writeString(store.resolve("changeSets/third.zip"), "third");      // not committed
        init(store.resolve("preferences"));
        commit(store.resolve("preferences"), "preferences.xml");                // no remote
        Files.createDirectories(store.resolve("instanceChangeSets"));
        Files.writeString(store.resolve("instanceChangeSets/loose.zip"), "loose"); // in no repository

        UnsavedWork.Report report = UnsavedWork.of(Optional.of(git), store);

        assertThat(report.repositories()).isEqualTo(2);
        assertThat(report.saved()).isFalse();
        assertThat(report.findings().stream().map(Names::plain)).containsExactly(
                "1 change set is in no git repository, so it is only in this folder.",
                "changeSets has 1 file not committed.",
                "changeSets has 1 commit not pushed.",
                "preferences has no remote, so its history is only in this folder.");

        run(store.resolve("changeSets"), "add", "third.zip");
        run(store.resolve("changeSets"), "-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "-q", "-m", "third");
        run(store.resolve("changeSets"), "push", "-q", "origin", "HEAD");
        Path pushed = temp.resolve("pushed");
        Files.createDirectories(pushed);
        run(pushed, "clone", "-q", remote.toString(), pushed.resolve("changeSets").toString());
        assertThat(UnsavedWork.of(Optional.of(git), pushed).saved()).as("committed and pushed: nothing at stake").isTrue();
    }

    @Test
    void theDialogSaysWhatIsAtStakeOrThatNothingIs() {
        KnowledgeBase demo = store("solor-gudid-card-leak-demo");
        KnowledgeBase fresh = store("ike-kb");
        Map<KnowledgeBase, UnsavedWork.Report> one = new LinkedHashMap<>();
        one.put(demo, new UnsavedWork.Report(List.of(Names.of("changeSets") + " has 3 files not committed."), 2, true));
        Map<KnowledgeBase, UnsavedWork.Report> clean = new LinkedHashMap<>();
        clean.put(fresh, new UnsavedWork.Report(List.of(), 2, true));

        assertThat(Names.plain(DashboardView.trashText(one))).startsWith(
                        "This folder has work that is saved nowhere else:\n• changeSets has 3 files not committed.")
                .contains("That work goes to the " + Finder.trashName() + " with the folder.");
        assertThat(Names.plain(DashboardView.trashText(clean)))
                .startsWith("Every change in its git repositories is committed and pushed, so nothing is lost.")
                .doesNotContain("may contain");
    }

    private static KnowledgeBase store(String name) {
        return new KnowledgeBase(name, Path.of("/Solor", name), StoreKind.ROCKS, 10, Optional.empty(), Map.of(),
                Instant.parse("2026-11-01T00:00:00Z"));
    }

    private static void init(Path folder) throws IOException {
        Files.createDirectories(folder);
        run(folder, "init", "-q");
        run(folder, "symbolic-ref", "HEAD", "refs/heads/main");
    }

    private static void commit(Path folder, String file) throws IOException {
        Files.writeString(folder.resolve(file), file);
        run(folder, "add", file);
        run(folder, "-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "-q", "-m", "Add " + file);
    }

    private static String run(Path folder, String... args) {
        RepositorySync.Git.Result result = git.run(folder, WAIT, args);
        assertThat(result.ok()).as("git %s: %s", String.join(" ", args), result.err()).isTrue();
        return result.out();
    }
}
