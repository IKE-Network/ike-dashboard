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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Gives a git repository inside a knowledge base its remote, with git alone (the IKE Dashboard design,
 * decision 21; KEC, 2026-11-12): the change-set repository, which is shared with other authors. There
 * is no Maven project here, so no goal does it. A change-set folder that is no repository yet is made
 * one first, on the branch {@value #BRANCH}, as Komet's own Connect makes it.
 *
 * <p>The dashboard never merges or loads change sets: that is Komet's sync. So it pushes only when the
 * push adds to what the remote has: an empty remote, or one whose branch this repository already
 * contains. When the remote has other change sets, the remote is set and nothing is pushed; Komet's
 * sync pulls them and loads them. Komet keeps its own record of the address and the login it syncs
 * with, so its sync is still connected in Komet, with the same address.
 *
 * <p>{@link #check} changes nothing and says what {@link #add} will do, so the person reads it first.
 */
public final class RepositoryRemote {

    /** The branch a new repository is made on, the one Komet's sync uses. */
    public static final String BRANCH = "main";

    /** The file committed when neither side has anything yet, as Komet's Connect commits one. */
    static final String README = "README.md";

    private static final Duration LOCAL = Duration.ofSeconds(20);
    private static final Duration REMOTE = Duration.ofSeconds(30);
    private static final Duration PUSH = Duration.ofMinutes(10);
    private static final Pattern URL = Pattern.compile("^(https?|ssh|git|file)://\\S+$");
    private static final Pattern SCP = Pattern.compile("^[\\w.-]+@[\\w.-]+:\\S+$");

    private RepositoryRemote() {
    }

    /**
     * What a check of a remote found, and what adding it will do.
     *
     * @param folder  the repository's folder
     * @param url     the remote's address, as checked
     * @param steps   what adding it will do, a sentence each, in order
     * @param refusal why it cannot be added as it stands, or empty
     * @param pushes  whether adding it pushes
     */
    public record Check(Path folder, String url, List<String> steps, Optional<String> refusal, boolean pushes) {

        /**
         * Copies the steps.
         *
         * @param folder  the repository's folder
         * @param url     the remote's address
         * @param steps   what adding it will do
         * @param refusal why it cannot be added
         * @param pushes  whether adding it pushes
         */
        public Check {
            steps = List.copyOf(steps);
        }

        private static Check refused(Path folder, String url, String why) {
            return new Check(folder, url, List.of(), Optional.of(why), false);
        }
    }

    /**
     * Asks a remote what it has and works out what adding it to a repository would do. Nothing is changed.
     *
     * @param git    the git command
     * @param folder the repository's folder, or a folder that is to become one
     * @param url    the remote's address
     * @return what was found
     */
    public static Check check(RepositorySync.Git git, Path folder, String url) {
        String remote = url == null ? "" : url.strip();
        if (!URL.matcher(remote).matches() && !SCP.matcher(remote).matches()) {
            return Check.refused(folder, remote, "That is not a git address. Use https://…, ssh://…, or user@host:path.");
        }
        boolean repository = Files.exists(folder.resolve(".git"));
        if (repository && git.run(folder, LOCAL, "remote", "get-url", "origin").ok()) {
            return Check.refused(folder, remote, "It already has a remote.");
        }
        // Asked from the folder's parent, which needs no repository.
        RepositorySync.Git.Result heads = git.run(repository ? folder : folder.getParent(), REMOTE, "ls-remote", "--heads", remote);
        if (!heads.ok()) {
            return Check.refused(folder, remote, "The remote could not be read: " + reason(heads)
                    + ". Create the repository on your git host first, and check that you are signed in.");
        }
        boolean commits = repository && git.run(folder, LOCAL, "rev-parse", "-q", "--verify", "HEAD").ok();
        String branch = commits ? branch(git, folder) : BRANCH;
        Optional<String> theirs = heads.out().lines().filter(line -> line.endsWith("refs/heads/" + branch))
                .map(line -> line.split("\\s+")[0]).findFirst();
        boolean contained = theirs.isPresent() && commits
                && git.run(folder, LOCAL, "merge-base", "--is-ancestor", theirs.get(), "HEAD").ok();
        List<String> steps = new ArrayList<>();
        if (!repository) {
            steps.add("Make " + folder.getFileName() + " a git repository, on the branch " + BRANCH + ".");
        }
        steps.add("Set its remote, origin, to " + remote + ".");
        boolean pushes = theirs.isEmpty() || contained;
        if (theirs.isEmpty() && !commits) {
            steps.add("Commit a " + README + ", since neither side has anything yet, and push it. Komet does the same when it connects.");
        } else if (pushes) {
            steps.add("Push the branch " + branch + ", which then follows the remote.");
        } else {
            steps.add("Push nothing: the remote already has change sets this folder does not have."
                    + " Komet's sync pulls them and loads them.");
        }
        steps.add("Komet's own sync is still connected in Komet, with the same address.");
        return new Check(folder, remote, steps, Optional.empty(), pushes);
    }

    /**
     * Adds a remote as its check said: makes the repository when there is none, sets {@code origin}, and
     * pushes when the push only adds to what the remote has.
     *
     * @param git    the git command
     * @param folder the repository's folder, or a folder that is to become one
     * @param url    the remote's address
     * @return what was done, as the check worded it
     * @throws IOException if the remote cannot be added as it stands, or git fails; when the push fails the
     *                     remote is set, and the message says so
     */
    public static Check add(RepositorySync.Git git, Path folder, String url) throws IOException {
        Check check = check(git, folder, url);
        if (check.refusal().isPresent()) {
            throw new IOException(check.refusal().get());
        }
        if (!Files.exists(folder.resolve(".git"))) {
            must(git.run(folder, LOCAL, "init", "-q", "-b", BRANCH), "git could not make the repository");
        }
        must(git.run(folder, LOCAL, "remote", "add", "origin", check.url()), "git could not set the remote");
        if (!check.pushes()) {
            return check;
        }
        if (!git.run(folder, LOCAL, "rev-parse", "-q", "--verify", "HEAD").ok()) {
            Path readme = folder.resolve(README);
            if (Files.notExists(readme)) {
                Files.writeString(readme, "# Change sets\n\nThe change sets of a knowledge base, exchanged through this repository.\n",
                        StandardCharsets.UTF_8);
            }
            must(git.run(folder, LOCAL, "add", "--", README), "git could not add the " + README);
            // Only the README: the change sets themselves are committed by Komet's sync.
            List<String> commit = new ArrayList<>(List.of("-c", "commit.gpgsign=false"));
            if (!git.run(folder, LOCAL, "config", "user.email").ok()) {
                // A person who has never used git has told it no name; the commit is the dashboard's own.
                commit.addAll(List.of("-c", "user.name=IKE Dashboard", "-c", "user.email=ike-dashboard@localhost"));
            }
            commit.addAll(List.of("commit", "-q", "--only", README, "-m", "Initial commit for changeset exchange"));
            must(git.run(folder, LOCAL, commit.toArray(String[]::new)), "git could not commit the " + README);
        }
        RepositorySync.Git.Result push = git.run(folder, PUSH, "push", "-u", "origin", branch(git, folder));
        if (!push.ok()) {
            throw new IOException("The remote is set, but the push failed: " + reason(push)
                    + ". Push again with git, or with Komet's sync, once the cause is dealt with.");
        }
        return check;
    }

    private static String branch(RepositorySync.Git git, Path folder) {
        RepositorySync.Git.Result head = git.run(folder, LOCAL, "symbolic-ref", "--short", "-q", "HEAD");
        return head.ok() && !head.out().isBlank() ? head.out().strip() : BRANCH;
    }

    private static void must(RepositorySync.Git.Result result, String what) throws IOException {
        if (!result.ok()) {
            throw new IOException(what + ": " + reason(result));
        }
    }

    /**
     * Why git failed, in its own words: the first line it marks {@code fatal:} or {@code error:}, without
     * the mark, since what follows is advice; else the first line it wrote. No closing full stop.
     */
    static String reason(RepositorySync.Git.Result result) {
        String line = result.err().lines().map(String::strip)
                .filter(text -> text.startsWith("fatal: ") || text.startsWith("error: ")).findFirst()
                .map(text -> text.substring(text.indexOf(' ') + 1)).orElseGet(result::firstErrorLine);
        return line.endsWith(".") ? line.substring(0, line.length() - 1) : line;
    }
}
