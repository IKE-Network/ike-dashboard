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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Creates a knowledge-base project (IKE-Network/ike-issues#1370) by running the goals that do the work,
 * {@code ike:kb-create-draft} and {@code ike:kb-create-publish}, named by their full coordinates so a
 * machine with no {@code ike:} prefix in its Maven settings runs them too. Maven cannot run before a
 * project exists and need not be installed (decision 1), so the Maven wrapper is written first, into
 * the project's own folder for a publish and into a folder of its own for a draft, which writes nothing
 * where the project will be. The goals do everything else: pin each layer, write the POM, apply the
 * standards scaffold, make the git repository and its first commit, and validate the result.
 *
 * <p>The build needs JDK {@value #JDK} and git (decision 11); {@link #prerequisites} finds both or says
 * which is missing. A dashboard opened from the Finder has neither a {@code JAVA_HOME} nor a shell's
 * {@code PATH}, so each run is given both.
 */
public final class KnowledgeBaseCreation {

    /** The plugin that contains the goals, as {@code groupId:artifactId}. */
    public static final String PLUGIN = "network.ike.tooling:ike-maven-plugin";

    /** The plugin version the dashboard runs, the first that contains the goals. */
    public static final String TOOLING_VERSION = "280";

    /** The variable that names another plugin version to run, such as a snapshot under test. */
    public static final String TOOLING_OVERRIDE = "IKE_DASHBOARD_TOOLING_VERSION";

    /** The goal that shows the plan and writes nothing. */
    public static final String DRAFT = "kb-create-draft";

    /** The goal that creates what the draft showed. */
    public static final String PUBLISH = "kb-create-publish";

    /**
     * How Maven is run for the dashboard: in batch mode, without transfer progress, and without colour,
     * so what it writes can be shown and read as plain lines.
     */
    static final List<String> PLAIN = List.of("-B", "-ntp", "-Dstyle.color=never");

    /** The plugin version that first contains the goals that give a project its remote. */
    public static final String REMOTE_TOOLING_VERSION = "281";

    /** The goal that shows what adding a remote would do, and changes nothing. */
    public static final String REMOTE_DRAFT = "kb-remote-draft";

    /** The goal that sets a project's remote, commits its POM and pushes. */
    public static final String REMOTE_PUBLISH = "kb-remote-publish";

    /** The plugin that makes working sets and changes their members, as {@code groupId:artifactId}. */
    public static final String WORKING_SET_PLUGIN = "network.ike.platform:ike-workspace-maven-plugin";

    /** The version of that plugin the dashboard runs. */
    public static final String WORKING_SET_VERSION = "227";

    /** The variable that names another version of the working-set plugin to run. */
    public static final String WORKING_SET_OVERRIDE = "IKE_DASHBOARD_PLATFORM_VERSION";

    /** The JDK a knowledge base builds on, at least. */
    public static final int JDK = 27;

    /** The wrapper's files, as they are named in a project; the first two are scripts. */
    static final List<String> WRAPPER = List.of("mvnw", "mvnw.cmd", ".mvn/wrapper/maven-wrapper.properties");

    private static final Pattern JAVA_VERSION = Pattern.compile("JAVA_VERSION=\"(\\d+)[^\"]*\"");
    /** The first line of the report the remote goals write. */
    public static final String REMOTE_HEADING = "Add remote:";

    /** The first line of the report the creation goals write. */
    public static final String CREATE_HEADING = "Knowledge base:";

    private static final String INFO = "[INFO] ";
    private static final String WARNING = "[WARNING] ";
    private static final String ERROR = "[ERROR] ";
    private static final Pattern FAILED_GOAL = Pattern.compile("^Failed to execute goal \\S+ \\([^)]*\\) on project \\S+: ");

    private final Prerequisites prerequisites;
    private final String toolingVersion;
    private final String remoteToolingVersion;
    private final String workingSetVersion;
    private final Function<Path, List<String>> maven;

    /**
     * What a build needs that the dashboard does not bring.
     *
     * @param jdk the home of a JDK {@value KnowledgeBaseCreation#JDK} or later, when one is installed
     * @param git git, when it is installed
     */
    public record Prerequisites(Optional<Path> jdk, Optional<RepositorySync.Git> git) {

        /**
         * What is missing, each with what to do about it.
         *
         * @return a sentence each, none when a knowledge base can be created
         */
        public List<String> missing() {
            List<String> missing = new ArrayList<>();
            if (jdk.isEmpty()) {
                missing.add("JDK " + JDK + " or later is not installed. Install one, or set JAVA_HOME to one.");
            }
            if (git.isEmpty()) {
                missing.add("git is not installed. Install it, or set " + RepositorySync.Git.OVERRIDE + " to it.");
            }
            return missing;
        }

        /**
         * The environment a run is given: the JDK as {@code JAVA_HOME}, and it and git first on the path.
         *
         * @param environment the dashboard's own environment
         * @return what to add to it
         */
        Map<String, String> environment(Map<String, String> environment) {
            Map<String, String> added = new LinkedHashMap<>();
            List<String> path = new ArrayList<>();
            jdk.ifPresent(home -> {
                added.put("JAVA_HOME", home.toString());
                path.add(home.resolve("bin").toString());
            });
            git.ifPresent(found -> Optional.ofNullable(found.executable().getParent()).ifPresent(folder -> path.add(folder.toString())));
            String pathName = environment.keySet().stream().filter("PATH"::equalsIgnoreCase).findFirst().orElse("PATH");
            Optional.ofNullable(environment.get(pathName)).filter(value -> !value.isBlank()).ifPresent(path::add);
            added.put(pathName, String.join(java.io.File.pathSeparator, path));
            return added;
        }
    }

    /**
     * How a run of Maven ended.
     *
     * @param exit  its exit code, zero when it succeeded
     * @param lines everything it wrote, in order
     */
    public record Outcome(int exit, List<String> lines) {

        /**
         * Copies the lines.
         *
         * @param exit  the exit code
         * @param lines everything the run wrote
         */
        public Outcome {
            lines = List.copyOf(lines);
        }

        /**
         * Whether the run succeeded.
         *
         * @return whether Maven exited with zero
         */
        public boolean succeeded() {
            return exit == 0;
        }

        /**
         * The plan the goals report: from the knowledge base's name to its last file.
         *
         * @return the report's lines, as the goals indent them; empty when there is none
         */
        public List<String> report() {
            return report(CREATE_HEADING);
        }

        /**
         * The report that starts at a heading and runs through the lines indented beneath it.
         *
         * @param heading how its first line starts
         * @return the report's lines, as the goals indent them; empty when there is none
         */
        public List<String> report(String heading) {
            List<String> report = new ArrayList<>();
            boolean within = false;
            for (String line : lines) {
                if (!line.startsWith(INFO)) {
                    within = false;
                    continue;
                }
                String text = line.substring(INFO.length());
                if (text.startsWith(heading)) {
                    within = true;
                } else if (within && !text.startsWith("  ")) {
                    within = false;
                }
                if (within) {
                    report.add(text);
                }
            }
            return report;
        }

        /**
         * Each layer as the goals pinned it, in load order: its coordinate at the exact deploy, and its size.
         *
         * @return the pinned coordinate of each layer, to its size as the report words it
         */
        public Map<KnowledgeBaseProject.Layer, String> pinned() {
            Map<KnowledgeBaseProject.Layer, String> pinned = new LinkedHashMap<>();
            for (String line : report()) {
                String text = line.strip();
                int comma = text.indexOf(", ");
                if (!line.startsWith("    ") || comma < 0 || text.chars().filter(character -> character == ':').count() != 4) {
                    continue;
                }
                try {
                    pinned.put(KnowledgeBaseProject.Layer.parse(text.substring(0, comma)), text.substring(comma + 2));
                } catch (IllegalArgumentException notALayer) {
                    // Another indented line of the report, such as a file's name.
                }
            }
            return pinned;
        }

        /**
         * Why a goal that ran cleanly says its publish half will refuse, such as a remote that cannot be read.
         *
         * @return the reason, or empty when it gave none
         */
        public Optional<String> refusal() {
            String marker = " Publish will refuse.";
            return lines.stream().filter(line -> line.startsWith(WARNING) && line.endsWith(marker))
                    .map(line -> line.substring(WARNING.length(), line.length() - marker.length()).strip()).findFirst();
        }

        /**
         * What the report says under one of its headings, such as {@code Build heap}.
         *
         * @param heading the heading, without its colon
         * @return what follows it, or empty when the report has no such line
         */
        public Optional<String> reported(String heading) {
            String start = "  " + heading + ":";
            return report().stream().filter(line -> line.startsWith(start)).findFirst()
                    .map(line -> line.substring(start.length()).strip());
        }

        /**
         * Why the run failed, in the goals' words, without Maven's own framing.
         *
         * @return the reason, or empty when Maven gave none
         */
        public Optional<String> failure() {
            List<String> reason = new ArrayList<>();
            boolean within = false;
            for (String line : lines) {
                if (!line.startsWith(ERROR.strip())) {
                    continue;
                }
                String text = line.length() > ERROR.length() ? line.substring(ERROR.length()) : "";
                if (!within && text.startsWith("Failed to execute goal")) {
                    within = true;
                    text = FAILED_GOAL.matcher(text).replaceFirst("");
                } else if (within && text.isBlank()) {
                    break;
                }
                if (within) {
                    reason.add(text.replaceFirst(" -> \\[Help \\d+]$", ""));
                }
            }
            return reason.isEmpty() ? Optional.empty() : Optional.of(String.join("\n", reason));
        }
    }

    /**
     * Creation through the Maven wrapper, with the plugin version the environment names or the
     * dashboard's own.
     *
     * @param prerequisites the JDK and git to run with
     */
    public KnowledgeBaseCreation(Prerequisites prerequisites) {
        this(prerequisites, toolingVersion(System.getenv(), TOOLING_VERSION),
                toolingVersion(System.getenv(), REMOTE_TOOLING_VERSION), KnowledgeBaseCreation::wrapper);
    }

    /**
     * Creation through a Maven of the caller's choosing.
     *
     * @param prerequisites  the JDK and git to run with
     * @param toolingVersion the version of the plugin that contains the goals
     * @param maven          the command that runs Maven in a folder, to which the arguments are added
     */
    KnowledgeBaseCreation(Prerequisites prerequisites, String toolingVersion, Function<Path, List<String>> maven) {
        this(prerequisites, toolingVersion, toolingVersion, maven);
    }

    private KnowledgeBaseCreation(Prerequisites prerequisites, String toolingVersion, String remoteToolingVersion,
                                  Function<Path, List<String>> maven) {
        this.prerequisites = prerequisites;
        this.toolingVersion = toolingVersion;
        this.remoteToolingVersion = remoteToolingVersion;
        this.workingSetVersion = Optional.ofNullable(System.getenv(WORKING_SET_OVERRIDE)).map(String::strip)
                .filter(version -> !version.isEmpty()).orElse(WORKING_SET_VERSION);
        this.maven = maven;
    }

    /**
     * The plugin version to run for goals that a version first contains.
     *
     * @param environment the dashboard's environment
     * @param since       the version that first contains the goals
     * @return what {@value #TOOLING_OVERRIDE} names, else that version
     */
    static String toolingVersion(Map<String, String> environment, String since) {
        return Optional.ofNullable(environment.get(TOOLING_OVERRIDE)).map(String::strip).filter(version -> !version.isEmpty())
                .orElse(since);
    }

    /**
     * Finds what a build needs on this machine.
     *
     * @return the JDK and git found
     */
    public static Prerequisites prerequisites() {
        return prerequisites(System.getenv(), System.getProperty("os.name", ""), Path.of(System.getProperty("user.home")));
    }

    static Prerequisites prerequisites(Map<String, String> environment, String osName, Path home) {
        return new Prerequisites(jdk(environment, jdkFolders(osName, home)), RepositorySync.Git.find(environment, osName));
    }

    /**
     * Where JDKs are installed: the system's place, the person's own, and where IntelliJ downloads them.
     *
     * @param osName the operating system's name
     * @param home   the person's home
     * @return the folders JDKs are installed in
     */
    static List<Path> jdkFolders(String osName, Path home) {
        return switch (OperatingSystem.of(osName)) {
            case WINDOWS -> List.of(Path.of("C:\\Program Files\\Java"), Path.of("C:\\Program Files\\Eclipse Adoptium"),
                    home.resolve(".jdks"));
            case MAC -> List.of(home.resolve("Library/Java/JavaVirtualMachines"), Path.of("/Library/Java/JavaVirtualMachines"));
            default -> List.of(Path.of("/usr/lib/jvm"), home.resolve(".jdks"), home.resolve(".sdkman/candidates/java"));
        };
    }

    /**
     * The JDK a build runs on: the one {@code JAVA_HOME} names when it is recent enough, else the newest
     * installed that is.
     *
     * @param environment the dashboard's environment
     * @param folders     the folders JDKs are installed in
     * @return the JDK's home, or empty when none is {@value #JDK} or later
     */
    static Optional<Path> jdk(Map<String, String> environment, List<Path> folders) {
        Optional<Path> named = Optional.ofNullable(environment.get("JAVA_HOME")).filter(value -> !value.isBlank()).map(Path::of)
                .filter(home -> javaVersion(home).filter(version -> version >= JDK).isPresent());
        if (named.isPresent()) {
            return named;
        }
        Map<Path, Integer> found = new LinkedHashMap<>();
        for (Path folder : folders) {
            if (!Files.isDirectory(folder)) {
                continue;
            }
            try (Stream<Path> installed = Files.list(folder)) {
                for (Path jdk : installed.sorted().toList()) {
                    // macOS keeps a JDK's home inside its bundle.
                    for (Path home : List.of(jdk.resolve("Contents/Home"), jdk)) {
                        javaVersion(home).filter(version -> version >= JDK).ifPresent(version -> found.putIfAbsent(home, version));
                    }
                }
            } catch (IOException unreadable) {
                // A folder that cannot be listed has no JDK the build could use.
            }
        }
        return found.entrySet().stream().max(Map.Entry.comparingByValue(Comparator.naturalOrder())).map(Map.Entry::getKey);
    }

    /** The feature version a JDK's {@code release} file states, when the folder is a JDK with a compiler. */
    private static Optional<Integer> javaVersion(Path home) {
        Path release = home.resolve("release");
        if (!Files.isRegularFile(release) || (!Files.isExecutable(home.resolve("bin/javac"))
                && !Files.isRegularFile(home.resolve("bin/javac.exe")))) {
            return Optional.empty();
        }
        try {
            Matcher matcher = JAVA_VERSION.matcher(Files.readString(release));
            return matcher.find() ? Optional.of(Integer.parseInt(matcher.group(1))) : Optional.empty();
        } catch (IOException | NumberFormatException unreadable) {
            return Optional.empty();
        }
    }

    /**
     * The command that runs the Maven wrapper of a folder.
     *
     * @param folder the folder the wrapper is in
     * @return the command, to which Maven's arguments are added
     */
    static List<String> wrapper(Path folder) {
        return OperatingSystem.current() == OperatingSystem.WINDOWS
                ? List.of("cmd.exe", "/c", folder.resolve("mvnw.cmd").toString())
                : List.of(folder.resolve("mvnw").toString());
    }

    /**
     * Writes the Maven wrapper into a folder, which is made when it is not there; a wrapper file already
     * there is left as it is.
     *
     * @param folder the folder
     * @return the files written, as they are named in the folder
     * @throws IOException if a file cannot be written
     */
    static List<String> writeWrapper(Path folder) throws IOException {
        List<String> written = new ArrayList<>();
        for (String name : WRAPPER) {
            Path file = folder.resolve(name);
            if (Files.exists(file)) {
                continue;
            }
            Files.createDirectories(file.getParent());
            String resource = "wrapper/" + name.substring(name.lastIndexOf('/') + 1);
            try (InputStream in = KnowledgeBaseCreation.class.getResourceAsStream(resource)) {
                if (in == null) {
                    throw new IOException("The dashboard is missing its copy of the Maven wrapper's " + resource);
                }
                Files.copy(in, file);
            }
            if (name.equals("mvnw") && !file.toFile().setExecutable(true)) {
                throw new IOException("Could not make " + file + " executable");
            }
            written.add(name);
        }
        return written;
    }

    /**
     * Shows what creating a project would do, with every layer pinned. Nexus is read; nothing is written
     * where the project will be.
     *
     * @param project the project
     * @param line    given each line Maven writes, as it is written
     * @return how the draft ended; its {@link Outcome#report() report} is the plan
     * @throws IOException          if Maven cannot be run
     * @throws InterruptedException if interrupted, after Maven has been stopped
     */
    public Outcome draft(KnowledgeBaseProject project, Consumer<String> line) throws IOException, InterruptedException {
        Path work = Files.createTempDirectory("ike-kb-draft-");
        try {
            writeWrapper(work);
            return run(work, project.arguments(DRAFT, toolingVersion), line);
        } finally {
            deleteTree(work);
        }
    }

    /**
     * Creates a project: the wrapper, then everything the publish goal does. When it fails before the
     * project is written, the wrapper is taken away again and a folder this made is removed.
     *
     * @param project the project
     * @param line    given each line Maven writes, as it is written
     * @return how the creation ended
     * @throws IOException          if the folder already contains a project, or Maven cannot be run
     * @throws InterruptedException if interrupted, after Maven has been stopped
     */
    public Outcome create(KnowledgeBaseProject project, Consumer<String> line) throws IOException, InterruptedException {
        Path folder = project.folder();
        if (Files.isRegularFile(folder.resolve("pom.xml")) || Files.exists(folder.resolve(".git"))) {
            throw new IOException(folder + " already contains a project. Choose another name or folder.");
        }
        boolean made = !Files.exists(folder);
        List<String> wrapper = writeWrapper(folder);
        boolean created = false;
        try {
            Outcome outcome = run(folder, project.arguments(PUBLISH, toolingVersion), line);
            created = Files.isRegularFile(folder.resolve("pom.xml"));
            return outcome;
        } finally {
            if (!created) {
                removeWrapper(folder, wrapper, made);
            }
        }
    }

    /**
     * Runs a phase of a project's own build, such as {@code verify} or {@code deploy}, through its wrapper.
     *
     * @param folder the project
     * @param phase  the phase
     * @param line   given each line Maven writes, as it is written
     * @return how the build ended
     * @throws IOException          if Maven cannot be run
     * @throws InterruptedException if interrupted, after Maven has been stopped
     */
    public Outcome build(Path folder, String phase, Consumer<String> line) throws IOException, InterruptedException {
        List<String> arguments = new ArrayList<>(PLAIN);
        arguments.add(phase);
        return run(folder, arguments, line);
    }

    /**
     * Shows what giving a project its remote would do: the remote is asked for its branches, and nothing
     * is changed. The remote is the project's own, not that of the knowledge base it builds.
     *
     * @param folder the project
     * @param remote the remote's git URL
     * @param line   given each line Maven writes, as it is written
     * @return how the draft ended; its {@link Outcome#report(String) report} under {@value #REMOTE_HEADING}
     *         is the plan, and {@link Outcome#refusal()} says why the push would fail
     * @throws IOException          if Maven cannot be run
     * @throws InterruptedException if interrupted, after Maven has been stopped
     */
    public Outcome remoteDraft(Path folder, String remote, Consumer<String> line) throws IOException, InterruptedException {
        return run(folder, remoteArguments(REMOTE_DRAFT, remote), line);
    }

    /**
     * Gives a project its remote: sets {@code origin}, has the POM name it, commits that, and pushes.
     *
     * @param folder the project
     * @param remote the remote's git URL
     * @param line   given each line Maven writes, as it is written
     * @return how it ended
     * @throws IOException          if Maven cannot be run
     * @throws InterruptedException if interrupted, after Maven has been stopped
     */
    public Outcome remotePublish(Path folder, String remote, Consumer<String> line) throws IOException, InterruptedException {
        return run(folder, remoteArguments(REMOTE_PUBLISH, remote), line);
    }

    /**
     * Makes an empty working set: a folder in the projects folder with a POM that will list its members,
     * in a git repository of its own with one commit and no remote. Maven is run from a wrapper written
     * elsewhere, so nothing but the working set is left in the projects folder.
     *
     * @param projects the projects folder, made when it is not there
     * @param name     the working set's name, which also names its folder
     * @param groupId  its group
     * @param line     given each line Maven writes, as it is written
     * @return how it ended
     * @throws IOException          if a folder of that name is already there, or Maven cannot be run
     * @throws InterruptedException if interrupted, after Maven has been stopped
     */
    public Outcome newWorkingSet(Path projects, String name, String groupId, Consumer<String> line)
            throws IOException, InterruptedException {
        if (Files.exists(projects.resolve(name))) {
            throw new IOException(projects.resolve(name) + " is already there. Choose another name.");
        }
        Files.createDirectories(projects);
        Path work = Files.createTempDirectory("ike-ws-new-");
        try {
            writeWrapper(work);
            return run(work, projects, workingSetArguments("scaffold-init", "-Dname=" + name, "-Dgroup=" + groupId), line);
        } finally {
            deleteTree(work);
        }
    }

    /**
     * Adds a project to a working set: its folder moves into the working set's folder, and the working
     * set lists it as a member and commits that. A project must build on its own before it is added; it
     * still does afterwards. When the working set refuses it, the folder is moved back.
     *
     * @param workingSet the working set's folder
     * @param project    the project's folder, outside the working set
     * @param line       given each line Maven writes, as it is written
     * @return how it ended
     * @throws IOException          if the working set already has a folder of the project's name, the folder
     *                              cannot be moved, or Maven cannot be run
     * @throws InterruptedException if interrupted, after Maven has been stopped
     */
    public Outcome addToWorkingSet(Path workingSet, Path project, Consumer<String> line)
            throws IOException, InterruptedException {
        Path member = workingSet.resolve(project.getFileName());
        if (Files.exists(member)) {
            throw new IOException(workingSet.getFileName() + " already has a folder named " + project.getFileName() + ".");
        }
        Files.move(project, member);
        boolean added = false;
        try {
            Outcome outcome = run(workingSet, workingSetArguments("add", "-Drepo=" + memberAddress(member)), line);
            added = outcome.succeeded();
            return outcome;
        } finally {
            if (!added) {
                Files.move(member, project);
            }
        }
    }

    /**
     * Removes a member from a working set: the working set stops listing it and commits that, and the
     * member's folder moves out into the projects folder, where it is a project on its own again.
     *
     * @param workingSet the working set's folder
     * @param member     the member's name, which is its folder's
     * @param projects   the projects folder
     * @param line       given each line Maven writes, as it is written
     * @return how it ended
     * @throws IOException          if the projects folder already has a folder of that name, the folder
     *                              cannot be moved, or Maven cannot be run
     * @throws InterruptedException if interrupted, after Maven has been stopped
     */
    public Outcome removeFromWorkingSet(Path workingSet, String member, Path projects, Consumer<String> line)
            throws IOException, InterruptedException {
        Path target = projects.resolve(member);
        if (Files.exists(target)) {
            throw new IOException(projects + " already has a folder named " + member + ".");
        }
        Outcome outcome = run(workingSet, workingSetArguments("remove", "-Dsubproject=" + member), line);
        if (outcome.succeeded()) {
            Files.move(workingSet.resolve(member), target);
        }
        return outcome;
    }

    /**
     * Where a working set says a member comes from: the member's remote when it has one, so the working
     * set can be cloned elsewhere with its members, else the member's own folder. The working set names a
     * member by the last part of the address, so a remote whose last part is not the folder's name is
     * passed over for the folder.
     */
    private String memberAddress(Path member) {
        String name = member.getFileName().toString();
        // No closing slash: the last part of the address is the member's name.
        String local = member.toUri().toString().replaceFirst("/$", "");
        return prerequisites.git()
                .map(git -> git.run(member, java.time.Duration.ofSeconds(20), "remote", "get-url", "origin"))
                .filter(RepositorySync.Git.Result::ok).map(result -> result.out().strip())
                .filter(origin -> origin.replaceFirst("\\.git$", "").replaceFirst("/$", "").endsWith("/" + name)
                        || origin.replaceFirst("\\.git$", "").endsWith(":" + name))
                .orElse(local);
    }

    private List<String> workingSetArguments(String goal, String... properties) {
        List<String> arguments = new ArrayList<>(PLAIN);
        arguments.add(WORKING_SET_PLUGIN + ":" + workingSetVersion + ":" + goal);
        arguments.addAll(List.of(properties));
        return arguments;
    }

    private List<String> remoteArguments(String goal, String remote) {
        List<String> arguments = new ArrayList<>(PLAIN);
        arguments.add(PLUGIN + ":" + remoteToolingVersion + ":" + goal);
        arguments.add("-Dremote=" + remote.strip());
        return arguments;
    }

    private Outcome run(Path folder, List<String> arguments, Consumer<String> line) throws IOException, InterruptedException {
        return run(folder, folder, arguments, line);
    }

    /** Runs the Maven of one folder, where its wrapper is, in another, where the work is done. */
    private Outcome run(Path wrapper, Path folder, List<String> arguments, Consumer<String> line)
            throws IOException, InterruptedException {
        List<String> missing = prerequisites.missing();
        if (!missing.isEmpty()) {
            throw new IOException(String.join(" ", missing));
        }
        List<String> command = new ArrayList<>(maven.apply(wrapper));
        command.addAll(arguments);
        ProcessBuilder builder = new ProcessBuilder(command).directory(folder.toFile()).redirectErrorStream(true);
        builder.environment().putAll(prerequisites.environment(builder.environment()));
        Process process = builder.start();
        List<String> lines = java.util.Collections.synchronizedList(new ArrayList<>());
        // Read on a thread of its own, so the wait below can be interrupted while Maven is silent.
        Thread reader = Thread.ofVirtual().name("ike-dashboard-maven-output").start(() -> {
            try (BufferedReader output = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                for (String read = output.readLine(); read != null; read = output.readLine()) {
                    lines.add(read);
                    line.accept(read);
                }
            } catch (IOException closed) {
                // The process was stopped; what it wrote until then is kept.
            }
        });
        try {
            int exit = process.waitFor();
            reader.join();
            return new Outcome(exit, lines);
        } catch (InterruptedException e) {
            process.descendants().forEach(ProcessHandle::destroy);
            process.destroy();
            throw e;
        }
    }

    /** Takes away the wrapper files this wrote, and the folders left empty by it. */
    private static void removeWrapper(Path folder, List<String> wrapper, boolean made) throws IOException {
        for (String name : wrapper) {
            Files.deleteIfExists(folder.resolve(name));
        }
        List<Path> emptied = new ArrayList<>(List.of(folder.resolve(".mvn/wrapper"), folder.resolve(".mvn")));
        if (made) {
            emptied.add(folder);
        }
        for (Path directory : emptied) {
            if (Files.isDirectory(directory)) {
                try (Stream<Path> entries = Files.list(directory)) {
                    if (entries.findAny().isPresent()) {
                        return;
                    }
                }
                Files.delete(directory);
            }
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(file);
            }
        }
    }
}
