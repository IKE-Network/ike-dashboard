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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * The sync state of the git repositories inside a store (the IKE Dashboard design, decision 14). A
 * store holds several, each synced on its own: Komet's sync keeps its change sets in one, and its
 * preferences may be another. The dashboard assumes no fixed list: it finds every {@code .git} within
 * a few levels of the store's folder.
 *
 * <p>Two of the three answers need no network: <em>uncommitted</em> (files the repository has not
 * committed) and <em>needs push</em> (commits its remote-tracking branch does not have). The third,
 * <em>behind</em>, can only be learned from the remote: {@link #remote} asks it for its head with
 * {@code git ls-remote}, which transfers no objects, and only when that head is one local history does
 * not contain does it fetch, to count what is incoming. A check that could not be made is
 * {@link Remote#UNKNOWN}, never current.
 *
 * <p>Reading a repository's index from its files alone is not practical, so this runs the git command,
 * with every prompt turned off. It looks for git where installers put it rather than on the
 * {@code PATH}, which an application started from the Dock does not inherit, and never runs macOS's
 * {@code /usr/bin/git} stand-in, which offers to install the developer tools when they are missing.
 */
public final class RepositorySync {

    /** How deep below the store a repository is looked for. */
    static final int DEPTH = 3;

    private static final Duration LOCAL_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration REMOTE_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration FETCH_TIMEOUT = Duration.ofMinutes(2);

    private RepositorySync() {
    }

    /**
     * A git repository inside a store.
     *
     * @param folder its working tree
     * @param label  what the person reads: {@code change sets}, {@code preferences}, or its path in the store
     */
    public record Repository(Path folder, String label) {
    }

    /** What the remote said, the one answer that needs the network. */
    public enum Remote {
        /** The repository has no {@code origin}: nothing to push to or pull from. */
        NONE,
        /** Not asked yet. */
        UNCHECKED,
        /** The remote has nothing local history lacks. */
        CURRENT,
        /** The remote has commits local history lacks. */
        BEHIND,
        /** The remote could not be asked: offline, refused, or not found. */
        UNKNOWN
    }

    /**
     * A repository's sync state.
     *
     * @param repository  the repository
     * @param branch      its branch, read from its own {@code HEAD}
     * @param uncommitted files not committed, untracked ones included
     * @param ahead       commits the remote-tracking branch does not have
     * @param remote      what the remote said
     * @param behind      commits the remote has that local history lacks, or {@code -1} when it is behind by an
     *                    unknown count
     * @param freshness   when the remote was last asked, and whether that worked
     * @param problem     why the remote could not be asked, or empty
     */
    public record Status(Repository repository, String branch, int uncommitted, int ahead, Remote remote, int behind,
                         Optional<Notice.Freshness> freshness, String problem) {

        /**
         * The same state with the remote's answer.
         *
         * @param remote    what the remote said
         * @param behind    commits it has that local history lacks
         * @param freshness when it was asked
         * @param problem   why it could not be asked, or empty
         * @return the state
         */
        Status withRemote(Remote remote, int behind, Optional<Notice.Freshness> freshness, String problem) {
            return new Status(repository, branch, uncommitted, ahead, remote, behind, freshness, problem);
        }
    }

    /**
     * The repositories inside a store, outermost first.
     *
     * @param store the store's folder
     * @return its repositories, none when it holds none
     * @throws IOException if the folder cannot be read
     */
    public static List<Repository> find(Path store) throws IOException {
        if (!Files.isDirectory(store)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.walk(store, DEPTH)) {
            return paths.filter(path -> path.getFileName() != null && path.getFileName().toString().equals(".git"))
                    .map(Path::getParent)
                    .sorted()
                    .map(folder -> new Repository(folder, label(store, folder)))
                    .toList();
        }
    }

    /** What the person reads for a repository: the folders Komet keeps by name, any other by its path. */
    static String label(Path store, Path folder) {
        String relative = store.relativize(folder).toString();
        return switch (relative) {
            case "" -> "the store";
            case "changeSets" -> "change sets";
            case "preferences" -> "preferences";
            default -> relative;
        };
    }

    /**
     * A repository's state from its files alone: its branch, what it has not committed, and what it has
     * not pushed as far as the last fetch knows. Needs no network.
     *
     * @param git        the git command
     * @param repository the repository
     * @return its state, the remote {@link Remote#UNCHECKED}, or {@link Remote#NONE} without an {@code origin}
     */
    public static Status local(Git git, Repository repository) {
        Path folder = repository.folder();
        Git.Result head = git.run(folder, LOCAL_TIMEOUT, "symbolic-ref", "--short", "-q", "HEAD");
        String branch = head.ok() ? head.out().strip() : "HEAD";
        Git.Result status = git.run(folder, LOCAL_TIMEOUT, "status", "--porcelain");
        if (!status.ok()) {
            return new Status(repository, branch, 0, 0, Remote.UNKNOWN, 0, Optional.empty(),
                    "git could not read it: " + status.firstErrorLine());
        }
        int uncommitted = (int) status.out().lines().filter(line -> !line.isBlank()).count();
        boolean hasOrigin = git.run(folder, LOCAL_TIMEOUT, "remote").out().lines().anyMatch("origin"::equals);
        if (!hasOrigin) {
            return new Status(repository, branch, uncommitted, 0, Remote.NONE, 0, Optional.empty(), "");
        }
        String tracking = "refs/remotes/origin/" + branch;
        boolean tracked = git.run(folder, LOCAL_TIMEOUT, "rev-parse", "-q", "--verify", tracking).ok();
        Git.Result count = git.run(folder, LOCAL_TIMEOUT, "rev-list", "--count", tracked ? tracking + "..HEAD" : "HEAD");
        int ahead = count.ok() ? parse(count.out()) : 0;
        return new Status(repository, branch, uncommitted, ahead, Remote.UNCHECKED, 0, Optional.empty(), "");
    }

    /**
     * Asks the remote whether it has commits local history lacks, fetching only when it has. A check that
     * fails keeps the time of the last one that worked, marked not current.
     *
     * @param git      the git command
     * @param local    the repository's state from {@link #local}
     * @param previous when the remote was last asked successfully, if it was
     * @param now      the time of this check
     * @return the state with the remote's answer
     */
    public static Status remote(Git git, Status local, Optional<Notice.Freshness> previous, Instant now) {
        if (local.remote() == Remote.NONE) {
            return local;
        }
        Path folder = local.repository().folder();
        Git.Result heads = git.run(folder, REMOTE_TIMEOUT, "ls-remote", "origin", "refs/heads/" + local.branch());
        if (!heads.ok()) {
            return local.withRemote(Remote.UNKNOWN, 0,
                    previous.map(last -> new Notice.Freshness(last.checkedAt(), false)), heads.firstErrorLine());
        }
        Optional<Notice.Freshness> checked = Optional.of(new Notice.Freshness(now, true));
        Optional<String> remoteHead = heads.out().lines().map(line -> line.split("\\s+")[0]).filter(sha -> !sha.isBlank())
                .findFirst();
        if (remoteHead.isEmpty()) {
            // The remote does not have the branch yet: nothing to pull; everything local is to push.
            return local.withRemote(Remote.CURRENT, 0, checked, "");
        }
        String sha = remoteHead.get();
        if (git.run(folder, LOCAL_TIMEOUT, "merge-base", "--is-ancestor", sha, "HEAD").ok()) {
            return local.withRemote(Remote.CURRENT, 0, checked, "");
        }
        if (!git.run(folder, FETCH_TIMEOUT, "fetch", "-q", "origin", local.branch()).ok()) {
            return local.withRemote(Remote.BEHIND, -1, checked, "");
        }
        Git.Result count = git.run(folder, LOCAL_TIMEOUT, "rev-list", "--count", "HEAD.." + sha);
        return local.withRemote(Remote.BEHIND, count.ok() ? parse(count.out()) : -1, checked, "");
    }

    private static int parse(String count) {
        try {
            return Integer.parseInt(count.strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * The git command, run with no prompt: no terminal prompt for a password, and SSH in batch mode.
     *
     * @param executable the git executable
     */
    public record Git(Path executable) {

        /** Where installers put git on macOS and Linux, in the order preferred. */
        static final List<Path> CANDIDATES = List.of(
                Path.of("/opt/homebrew/bin/git"),
                Path.of("/usr/local/bin/git"),
                Path.of("/Library/Developer/CommandLineTools/usr/bin/git"),
                Path.of("/Applications/Xcode.app/Contents/Developer/usr/bin/git"));

        /** The variable that names a git executable to use instead. */
        static final String OVERRIDE = "IKE_DASHBOARD_GIT";

        /**
         * The git command on this machine, if there is one.
         *
         * @return it, or empty when none is installed
         */
        public static Optional<Git> find() {
            return find(System.getenv(), System.getProperty("os.name", ""));
        }

        static Optional<Git> find(Map<String, String> environment, String osName) {
            String override = environment.get(OVERRIDE);
            if (override != null && !override.isBlank()) {
                return Optional.of(Path.of(override)).filter(Files::isExecutable).map(Git::new);
            }
            if (OperatingSystem.of(osName) == OperatingSystem.WINDOWS) {
                return windowsCandidates(environment).stream().filter(Files::isRegularFile).findFirst().map(Git::new);
            }
            List<Path> candidates = new ArrayList<>(CANDIDATES);
            if (!osName.toLowerCase(Locale.ROOT).contains("mac")) {
                // Elsewhere /usr/bin/git is git itself, not a stand-in that offers to install it.
                candidates.add(Path.of("/usr/bin/git"));
            }
            return candidates.stream().filter(Files::isExecutable).findFirst().map(Git::new);
        }

        /**
         * Where Git for Windows puts git, in the order preferred: its installer for every user
         * ({@code Program Files}), its installer for one user ({@code %LOCALAPPDATA%\Programs}), then
         * any {@code git.exe} on the {@code PATH}, as from another installer.
         *
         * @param environment the environment
         * @return the places to look
         */
        static List<Path> windowsCandidates(Map<String, String> environment) {
            List<Path> candidates = new ArrayList<>();
            for (String programFiles : List.of("ProgramFiles", "ProgramW6432")) {
                String folder = environment.get(programFiles);
                if (folder != null && !folder.isBlank()) {
                    candidates.add(Path.of(folder, "Git", "cmd", "git.exe"));
                }
            }
            String local = environment.get("LOCALAPPDATA");
            if (local != null && !local.isBlank()) {
                candidates.add(Path.of(local, "Programs", "Git", "cmd", "git.exe"));
            }
            // Windows does not distinguish the variable's case; a Map handed in does.
            String path = environment.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase("PATH"))
                    .map(Map.Entry::getValue).findFirst().orElse("");
            for (String folder : path.split(";")) {
                if (!folder.isBlank()) {
                    try {
                        candidates.add(Path.of(folder.strip(), "git.exe"));
                    } catch (java.nio.file.InvalidPathException e) {
                        // A PATH entry that is not a path names no git.
                    }
                }
            }
            return candidates;
        }

        /**
         * What one git command printed.
         *
         * @param exitStatus its exit status, or {@code -1} when it did not finish in time
         * @param out        what it wrote to standard output
         * @param err        what it wrote to standard error
         */
        public record Result(int exitStatus, String out, String err) {

            /**
             * Whether it succeeded.
             *
             * @return {@code true} for exit status 0
             */
            public boolean ok() {
                return exitStatus == 0;
            }

            /**
             * The first line of its error output, for the person.
             *
             * @return it, or a note of the exit status when git wrote none
             */
            public String firstErrorLine() {
                return err.lines().filter(line -> !line.isBlank()).findFirst().map(String::strip)
                        .orElse(exitStatus == -1 ? "git did not answer in time" : "git exited with status " + exitStatus);
            }
        }

        /**
         * Runs a git command in a folder.
         *
         * @param folder  the working tree
         * @param timeout how long to wait
         * @param args    the git subcommand and its arguments
         * @return what it printed
         */
        public Result run(Path folder, Duration timeout, String... args) {
            List<String> command = new ArrayList<>();
            command.add(executable.toString());
            command.add("-C");
            command.add(folder.toString());
            command.addAll(List.of(args));
            ProcessBuilder builder = new ProcessBuilder(command);
            Map<String, String> environment = builder.environment();
            environment.put("GIT_TERMINAL_PROMPT", "0");
            environment.put("GIT_SSH_COMMAND", "ssh -o BatchMode=yes -o ConnectTimeout=10");
            environment.put("LC_ALL", "C");
            try {
                Process process = builder.start();
                process.getOutputStream().close();
                CompletableFuture<String> out = read(process.getInputStream());
                CompletableFuture<String> err = read(process.getErrorStream());
                if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                    return new Result(-1, "", "");
                }
                return new Result(process.exitValue(), out.join(), err.join());
            } catch (IOException e) {
                return new Result(-2, "", "could not run git: " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new Result(-1, "", "interrupted");
            }
        }

        private static CompletableFuture<String> read(InputStream stream) {
            return CompletableFuture.supplyAsync(() -> {
                try (stream) {
                    return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                } catch (IOException e) {
                    return "";
                }
            });
        }
    }
}
