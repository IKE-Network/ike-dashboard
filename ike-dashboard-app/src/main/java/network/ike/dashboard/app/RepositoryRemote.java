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
 * decisions 16 and 21; KEC, 2026-11-12). There is no Maven project here, so no goal does it. A folder
 * that is no repository yet is made one first. Two kinds of repository are given a remote, and they
 * differ in who else writes them:
 *
 * <ul>
 *   <li>The <em>change sets</em> are shared with the other authors. Komet's sync commits, merges and
 *       loads them, so the dashboard commits only a README when neither side has anything, on the
 *       branch {@value #BRANCH}, as Komet's own Connect does. Komet keeps its own record of the address
 *       and the login it syncs with, so its sync is still connected in Komet.</li>
 *   <li>The <em>preferences</em> are one person's: window layout, journals, and assistant
 *       conversations. Nothing else commits them, so the dashboard commits what is in the folder. Its
 *       branch is named for the knowledge base, so one private repository can keep the preferences of
 *       all of a person's knowledge bases (decision 16). Komet writes preferences while it runs, so
 *       nothing is done while the knowledge base is open.</li>
 * </ul>
 *
 * <p>Either way it pushes only when the push adds to what the remote has: a remote without the branch,
 * or one whose branch this repository already contains. When the remote has other commits there, the
 * remote is set and nothing is pushed or merged.
 *
 * <p>{@link #check} changes nothing and says what {@link #add} will do, so the person reads it first.
 */
public final class RepositoryRemote {

    /** What a repository inside a knowledge base is for, which decides how it is given a remote. */
    public enum Kind {
        /** The change sets, shared with the other authors and synced by Komet. */
        CHANGE_SETS,
        /** One person's preferences for the knowledge base. */
        PREFERENCES;

        /**
         * What a folder inside a knowledge base is.
         *
         * @param folder the folder
         * @return preferences for the preferences folder, else change sets
         */
        public static Kind of(Path folder) {
            return folder.getFileName() != null && folder.getFileName().toString().equals(RepositorySync.PREFERENCES)
                    ? PREFERENCES : CHANGE_SETS;
        }
    }

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
        return check(git, folder, url, BRANCH);
    }

    /**
     * Asks a remote what it has and works out what adding it to a repository would do. Nothing is changed.
     *
     * @param git      the git command
     * @param folder   the repository's folder, or a folder that is to become one
     * @param url      the remote's address
     * @param personal the branch a new preferences repository is made on, the knowledge base's name
     * @return what was found
     */
    public static Check check(RepositorySync.Git git, Path folder, String url, String personal) {
        Kind kind = Kind.of(folder);
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
        if (kind == Kind.PREFERENCES && StoreLock.openInKomet(folder.getParent())) {
            return Check.refused(folder, remote, "This knowledge base is open in Komet, which writes its preferences while it"
                    + " runs. Quit Komet, then try again.");
        }
        boolean commits = repository && git.run(folder, LOCAL, "rev-parse", "-q", "--verify", "HEAD").ok();
        String branch = commits ? branch(git, folder) : kind == Kind.PREFERENCES ? personal : BRANCH;
        int uncommitted = kind == Kind.PREFERENCES ? uncommitted(git, folder, repository) : 0;
        if (kind == Kind.PREFERENCES && !commits && uncommitted == 0) {
            return Check.refused(folder, remote, "There are no preferences in it yet. Open the knowledge base in Komet once first.");
        }
        Optional<String> theirs = heads.out().lines().filter(line -> line.endsWith("refs/heads/" + branch))
                .map(line -> line.split("\\s+")[0]).findFirst();
        boolean contained = theirs.isPresent() && commits
                && git.run(folder, LOCAL, "merge-base", "--is-ancestor", theirs.get(), "HEAD").ok();
        List<String> steps = new ArrayList<>();
        if (!repository) {
            steps.add("Make " + folder.getFileName() + " a git repository, on the branch " + branch + ".");
        }
        if (uncommitted > 0) {
            steps.add("Commit what is in it now: " + uncommitted + (uncommitted == 1 ? " file." : " files."));
        }
        steps.add("Set its remote, origin, to " + remote + ".");
        // A local history that will exist once the commit above is made contains nothing of the remote's.
        boolean pushes = theirs.isEmpty() || contained;
        if (kind == Kind.PREFERENCES) {
            steps.add(pushes ? "Push the branch " + branch + ", which then follows the remote."
                    : "Push nothing: the remote already has preferences on the branch " + branch
                    + " that this folder does not have. Nothing is merged.");
            steps.add("Later changes are not sent by themselves yet. Commit and push them with git.");
        } else {
            if (theirs.isEmpty() && !commits) {
                steps.add("Commit a " + README + ", since neither side has anything yet, and push it. Komet does the same when it connects.");
            } else if (pushes) {
                steps.add("Push the branch " + branch + ", which then follows the remote.");
            } else {
                steps.add("Push nothing: the remote already has change sets this folder does not have."
                        + " Komet's sync pulls them and loads them.");
            }
            steps.add("Komet's own sync is still connected in Komet, with the same address.");
        }
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
        return add(git, folder, url, BRANCH);
    }

    /**
     * Adds a remote as its check said: makes the repository when there is none, commits a person's
     * preferences, sets {@code origin}, and pushes when the push only adds to what the remote has.
     *
     * @param git      the git command
     * @param folder   the repository's folder, or a folder that is to become one
     * @param url      the remote's address
     * @param personal the branch a new preferences repository is made on, the knowledge base's name
     * @return what was done, as the check worded it
     * @throws IOException if the remote cannot be added as it stands, or git fails; when the push fails the
     *                     remote is set, and the message says so
     */
    public static Check add(RepositorySync.Git git, Path folder, String url, String personal) throws IOException {
        Kind kind = Kind.of(folder);
        Check check = check(git, folder, url, personal);
        if (check.refusal().isPresent()) {
            throw new IOException(check.refusal().get());
        }
        if (!Files.exists(folder.resolve(".git"))) {
            must(git.run(folder, LOCAL, "init", "-q", "-b", kind == Kind.PREFERENCES ? personal : BRANCH),
                    "git could not make the repository");
        }
        if (kind == Kind.PREFERENCES && uncommitted(git, folder, true) > 0) {
            must(git.run(folder, LOCAL, "add", "-A"), "git could not add the preferences");
            must(git.run(folder, LOCAL, commit("Preferences of " + folder.getParent().getFileName(), git, folder)),
                    "git could not commit the preferences");
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
            must(git.run(folder, LOCAL, commit("Initial commit for changeset exchange", git, folder, "--only", README)),
                    "git could not commit the " + README);
        }
        RepositorySync.Git.Result push = git.run(folder, PUSH, "push", "-u", "origin", branch(git, folder));
        if (!push.ok()) {
            throw new IOException("The remote is set, but the push failed: " + reason(push)
                    + ". Push again with git, or with Komet's sync, once the cause is dealt with.");
        }
        return check;
    }

    /** The words of a commit that never asks for a signature, and is the dashboard's own when git has been told no name. */
    private static String[] commit(String message, RepositorySync.Git git, Path folder, String... only) {
        List<String> commit = new ArrayList<>(List.of("-c", "commit.gpgsign=false"));
        if (!git.run(folder, LOCAL, "config", "user.email").ok()) {
            commit.addAll(List.of("-c", "user.name=IKE Dashboard", "-c", "user.email=ike-dashboard@localhost"));
        }
        commit.addAll(List.of("commit", "-q"));
        commit.addAll(List.of(only));
        commit.addAll(List.of("-m", message));
        return commit.toArray(String[]::new);
    }

    /** How many files a folder has that its repository has not committed: every file when it is no repository yet. */
    private static int uncommitted(RepositorySync.Git git, Path folder, boolean repository) {
        if (repository) {
            return (int) git.run(folder, LOCAL, "status", "--porcelain", "--untracked-files=all").out().lines()
                    .filter(line -> !line.isBlank()).count();
        }
        try (java.util.stream.Stream<Path> files = Files.walk(folder)) {
            return (int) files.filter(Files::isRegularFile).count();
        } catch (IOException unreadable) {
            return 0;
        }
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
