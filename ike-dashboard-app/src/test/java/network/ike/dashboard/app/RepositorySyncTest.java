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
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The sync state of the git repositories inside a store, against real repositories: a bare remote and
 * two clones of it, one standing for this machine's store and one for another machine's.
 */
class RepositorySyncTest {

    private static final Duration WAIT = Duration.ofSeconds(30);
    private static final Instant NOW = Instant.parse("2026-10-24T20:00:00Z");
    private static RepositorySync.Git git;

    @BeforeAll
    static void findGit() {
        Optional<RepositorySync.Git> found = RepositorySync.Git.find();
        assumeTrue(found.isPresent(), "no git installed where the dashboard looks for it");
        git = found.get();
    }

    @Test
    void everyRepositoryInsideAStoreIsFoundAndNamedByItsFolder(@TempDir Path temp) throws IOException {
        Path store = temp.resolve("ike-kb");
        init(store.resolve("changeSets"));
        init(store.resolve("preferences"));
        Files.createDirectories(store.resolve("lucene"));

        assertThat(RepositorySync.find(store)).containsExactly(
                new RepositorySync.Repository(store.resolve("changeSets"), "change sets"),
                new RepositorySync.Repository(store.resolve("preferences"), "preferences"));
        assertThat(RepositorySync.find(temp.resolve("absent"))).isEmpty();
    }

    @Test
    void aCloneAtTheRemotesHeadIsCurrent(@TempDir Path temp) throws IOException {
        Path mine = cloneOf(remote(temp), temp.resolve("mine"));

        RepositorySync.Status status = RepositorySync.remote(git, local(mine), Optional.empty(), NOW);

        assertThat(status.uncommitted()).isZero();
        assertThat(status.ahead()).isZero();
        assertThat(status.remote()).isEqualTo(RepositorySync.Remote.CURRENT);
        assertThat(status.freshness()).contains(new Notice.Freshness(NOW, true));
    }

    @Test
    void filesNotCommittedAreCountedWithoutTheNetwork(@TempDir Path temp) throws IOException {
        Path mine = cloneOf(remote(temp), temp.resolve("mine"));
        Files.writeString(mine.resolve("Author 20261024 ike-cs.zip"), "change set");
        Files.writeString(mine.resolve("README.md"), "changed");

        RepositorySync.Status status = local(mine);

        assertThat(status.uncommitted()).isEqualTo(2);
        assertThat(status.remote()).as("not asked yet").isEqualTo(RepositorySync.Remote.UNCHECKED);
    }

    @Test
    void aCommitNotPushedIsAheadOfTheRemoteTrackingBranch(@TempDir Path temp) throws IOException {
        Path mine = cloneOf(remote(temp), temp.resolve("mine"));
        commit(mine, "Author 20261024 ike-cs.zip");

        assertThat(local(mine).ahead()).isEqualTo(1);
    }

    @Test
    void whatAnotherMachinePushedIsBehindAndFetchingItLeavesTheBranchWhereItWas(@TempDir Path temp) throws IOException {
        Path remote = remote(temp);
        Path mine = cloneOf(remote, temp.resolve("mine"));
        Path theirs = cloneOf(remote, temp.resolve("theirs"));
        commit(theirs, "Theirs 1 ike-cs.zip");
        commit(theirs, "Theirs 2 ike-cs.zip");
        run(theirs, "push", "-q", "origin", "main");
        String before = run(mine, "rev-parse", "HEAD").strip();

        RepositorySync.Status status = RepositorySync.remote(git, local(mine), Optional.empty(), NOW);

        assertThat(status.remote()).isEqualTo(RepositorySync.Remote.BEHIND);
        assertThat(status.behind()).isEqualTo(2);
        assertThat(run(mine, "rev-parse", "HEAD").strip()).as("the tree and branch are untouched").isEqualTo(before);
        assertThat(Files.exists(mine.resolve("Theirs 1 ike-cs.zip"))).isFalse();
    }

    @Test
    void aRemoteThatCannotBeAskedIsUnknownAsOfTheLastCheckThatWorked(@TempDir Path temp) throws IOException {
        Path remote = remote(temp);
        Path mine = cloneOf(remote, temp.resolve("mine"));
        deleteTree(remote);
        Notice.Freshness last = new Notice.Freshness(NOW.minus(Duration.ofHours(2)), true);

        RepositorySync.Status status = RepositorySync.remote(git, local(mine), Optional.of(last), NOW);

        assertThat(status.remote()).isEqualTo(RepositorySync.Remote.UNKNOWN);
        assertThat(status.freshness()).contains(new Notice.Freshness(last.checkedAt(), false));
        assertThat(status.problem()).isNotBlank();
    }

    @Test
    void aRepositoryWithNoRemoteHasNothingToPushOrPull(@TempDir Path temp) throws IOException {
        Path alone = temp.resolve("preferences");
        init(alone);
        commit(alone, "preferences.xml");

        RepositorySync.Status status = RepositorySync.remote(git, local(alone), Optional.empty(), NOW);

        assertThat(status.remote()).isEqualTo(RepositorySync.Remote.NONE);
        assertThat(status.ahead()).isZero();
    }

    @Test
    void macOsStandInForGitIsNeverRun() {
        Optional<RepositorySync.Git> found = RepositorySync.Git.find(Map.of(), "Mac OS X");
        found.ifPresent(chosen -> assertThat(chosen.executable()).isNotEqualTo(Path.of("/usr/bin/git")));
        assertThat(RepositorySync.Git.find(Map.of(RepositorySync.Git.OVERRIDE, "/no/such/git"), "Linux")).isEmpty();
    }

    private static RepositorySync.Status local(Path folder) {
        return RepositorySync.local(git, new RepositorySync.Repository(folder, folder.getFileName().toString()));
    }

    /** A bare remote whose main holds one commit. */
    private static Path remote(Path temp) throws IOException {
        Path seed = temp.resolve("seed");
        init(seed);
        commit(seed, "README.md");
        Path remote = temp.resolve("remote.git");
        run(temp, "clone", "-q", "--bare", seed.toString(), remote.toString());
        return remote;
    }

    private static Path cloneOf(Path remote, Path folder) {
        run(folder.getParent(), "clone", "-q", remote.toString(), folder.toString());
        return folder;
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

    private static void deleteTree(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}
