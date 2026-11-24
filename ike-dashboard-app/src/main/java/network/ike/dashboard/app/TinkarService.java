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
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.stream.Stream;

/**
 * Runs a tinkar-service build on a store, for local development: the jar a {@code tinkar-service} checkout
 * builds into {@code target/}, or any folder holding one. The service opens the store it is pointed at
 * with {@code --data.path.parent}, {@code --data.path.child} and {@code --data.controller.name}, the
 * provider that reads the store's layout, so any store Komet opens, managed or not, can be served.
 * <p>The service is started in the folder chosen, so a checkout's own {@code .env} and
 * {@code data/credentials.properties} apply, as they do for {@code ./mvnw spring-boot:run}. Its store is
 * flushed and closed by its shutdown hooks, so it is stopped gently ({@link Process#destroy()}, SIGTERM);
 * a forced stop can leave the store needing recovery.
 */
public final class TinkarService {

    /** The class a tinkar-service jar starts, named in its manifest. */
    static final String START_CLASS = "dev.ikm.tinkar.service.TinkarServiceApplication";

    /** What Spring Boot prints once the service answers. */
    static final String READY_LINE = "Started TinkarServiceApplication";

    /** The REST port tinkar-service listens on unless told otherwise. */
    public static final int DEFAULT_REST_PORT = 8085;

    /** The gRPC port tinkar-service listens on unless told otherwise. */
    public static final int DEFAULT_GRPC_PORT = 9095;

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private TinkarService() {
    }

    /**
     * A tinkar-service jar.
     *
     * @param jar      the executable jar
     * @param version  its {@code Implementation-Version}, or empty
     * @param jdk      the Java feature release it was built for ({@code Build-Jdk-Spec}), or 0 when not said
     * @param built    when the jar was last written: its change time, as the file's date is the reproducible
     *                 build's fixed {@code project.build.outputTimestamp}
     */
    public record Build(Path jar, String version, int jdk, Instant built) {

        /**
         * The folder the service runs in: the checkout for a jar in its {@code target/}, else the jar's folder.
         *
         * @return the working folder
         */
        public Path workingFolder() {
            Path folder = jar.toAbsolutePath().getParent();
            return folder.getFileName() != null && folder.getFileName().toString().equals("target") && folder.getParent() != null
                    ? folder.getParent() : folder;
        }
    }

    /**
     * A JDK the service can run on.
     *
     * @param home    the JDK's home folder
     * @param feature its feature release, such as 27
     */
    public record Jdk(Path home, int feature) {

        /**
         * Its {@code java} launcher.
         *
         * @return the launcher
         */
        public Path java() {
            return home.resolve("bin").resolve(OperatingSystem.current() == OperatingSystem.WINDOWS ? "java.exe" : "java");
        }
    }

    /**
     * What a start runs.
     *
     * @param build    the build
     * @param jdk      the JDK
     * @param store    the store, which must be one Komet opens
     * @param need     the memory: its heap, and for Rocks its block cache
     * @param restPort the REST port
     * @param grpcPort the gRPC port
     */
    public record Launch(Build build, Jdk jdk, KnowledgeBase store, HeapSizing.Need need, int restPort, int grpcPort) {
    }

    /**
     * The tinkar-service builds in a folder, newest first: the jars in its {@code target/}, and any in the
     * folder itself. A jar counts when its manifest starts {@value #START_CLASS}, so sources, javadoc and
     * Spring Boot's {@code .jar.original} are left out.
     *
     * @param folder a tinkar-service checkout, or a folder holding its jar
     * @return the builds
     */
    public static List<Build> builds(Path folder) {
        List<Build> builds = new ArrayList<>();
        for (Path where : List.of(folder.resolve("target"), folder)) {
            if (!Files.isDirectory(where)) {
                continue;
            }
            try (Stream<Path> entries = Files.list(where)) {
                entries.filter(path -> path.getFileName().toString().endsWith(".jar") && Files.isRegularFile(path))
                        .map(TinkarService::build).flatMap(Optional::stream).forEach(builds::add);
            } catch (IOException e) {
                // a folder that cannot be listed holds no build we can run
            }
        }
        builds.sort(Comparator.comparing(Build::built).reversed());
        return List.copyOf(builds);
    }

    /**
     * A jar, when it is a tinkar-service build.
     *
     * @param jar the jar
     * @return the build, or empty when the jar is not one or cannot be read
     */
    static Optional<Build> build(Path jar) {
        try (JarFile file = new JarFile(jar.toFile())) {
            Manifest manifest = file.getManifest();
            if (manifest == null) {
                return Optional.empty();
            }
            Attributes main = manifest.getMainAttributes();
            if (!START_CLASS.equals(main.getValue("Start-Class"))) {
                return Optional.empty();
            }
            return Optional.of(new Build(jar, Optional.ofNullable(main.getValue("Implementation-Version")).orElse(""),
                    feature(main.getValue("Build-Jdk-Spec")), written(jar)));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /**
     * When a file was last written. A reproducible build sets a jar's date to its fixed
     * {@code project.build.outputTimestamp} (the IKE parent's release), so the date says nothing about when it was
     * built; setting it changes the file's change time, which is when the build wrote it. Where the file system
     * keeps no change time, as on Windows, the date is all there is.
     *
     * @param file the file
     * @return when it was written
     * @throws IOException if the file cannot be read
     */
    static Instant written(Path file) throws IOException {
        Instant modified = Files.getLastModifiedTime(file).toInstant();
        try {
            Instant changed = ((java.nio.file.attribute.FileTime) Files.getAttribute(file, "unix:ctime")).toInstant();
            return changed.isAfter(modified) ? changed : modified;
        } catch (UnsupportedOperationException | IllegalArgumentException e) {
            return modified;
        }
    }

    /**
     * What a checkout holds, to tell whether a build is of it as it is now.
     *
     * @param branch       the git branch checked out, when the folder is a git checkout on one
     * @param lastChange   when a file of its source ({@code src/main}, {@code pom.xml}) was last changed
     */
    public record Source(Optional<String> branch, Optional<Instant> lastChange) {

        /** A folder that is not a checkout. */
        public static final Source NONE = new Source(Optional.empty(), Optional.empty());

        /**
         * Whether the source changed after a build was made.
         *
         * @param build the build
         * @return {@code true} when it did, so the build is not of the source as it is
         */
        public boolean changedSince(Build build) {
            return lastChange.filter(change -> change.isAfter(build.built())).isPresent();
        }
    }

    /**
     * What a checkout holds: its branch, from {@code .git/HEAD}, and when its source last changed.
     *
     * @param folder the folder chosen
     * @return what it holds; {@link Source#NONE} for a folder with neither
     */
    public static Source source(Path folder) {
        return new Source(branch(folder), lastChange(folder));
    }

    private static Optional<String> branch(Path folder) {
        try {
            Path git = folder.resolve(".git");
            if (Files.isRegularFile(git)) {                 // a worktree or submodule points to its git folder
                String pointer = Files.readString(git).strip();
                if (!pointer.startsWith("gitdir:")) {
                    return Optional.empty();
                }
                git = folder.resolve(pointer.substring("gitdir:".length()).strip());
            }
            Path head = git.resolve("HEAD");
            if (!Files.isRegularFile(head)) {
                return Optional.empty();
            }
            String ref = Files.readString(head).strip();
            return ref.startsWith("ref: refs/heads/") ? Optional.of(ref.substring("ref: refs/heads/".length()))
                    : Optional.of("detached at " + ref.substring(0, Math.min(7, ref.length())));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static Optional<Instant> lastChange(Path folder) {
        Optional<Instant> newest = Optional.empty();
        for (Path root : List.of(folder.resolve("pom.xml"), folder.resolve("src").resolve("main"))) {
            if (!Files.exists(root)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(root)) {
                Optional<Instant> here = files.filter(Files::isRegularFile).map(file -> {
                    try {
                        return Files.getLastModifiedTime(file).toInstant();
                    } catch (IOException e) {
                        return Instant.EPOCH;
                    }
                }).max(Comparator.naturalOrder());
                if (here.isPresent() && (newest.isEmpty() || here.get().isAfter(newest.get()))) {
                    newest = here;
                }
            } catch (IOException | java.io.UncheckedIOException e) {
                // what cannot be read cannot be compared
            }
        }
        return newest;
    }

    /**
     * A JDK home, when it is one: a folder with {@code bin/java} and a {@code release} file naming its version.
     *
     * @param home the folder
     * @return the JDK, or empty
     */
    public static Optional<Jdk> jdk(Path home) {
        Path release = home.resolve("release");
        if (!Files.isRegularFile(release)) {
            return Optional.empty();
        }
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(release)) {
            properties.load(in);
        } catch (IOException e) {
            return Optional.empty();
        }
        int feature = feature(Optional.ofNullable(properties.getProperty("JAVA_VERSION")).orElse("").replace("\"", ""));
        Jdk jdk = new Jdk(home, feature);
        return feature > 0 && Files.isRegularFile(jdk.java()) ? Optional.of(jdk) : Optional.empty();
    }

    /**
     * Finds a JDK of a feature release: {@code JAVA_HOME} when it is that release, else, on macOS,
     * {@code /usr/libexec/java_home -v <feature>}. A build using preview features runs only on the release
     * it was built for.
     *
     * @param feature  the feature release, such as 27
     * @param javaHome the {@code JAVA_HOME} variable, or {@code null}
     * @return the JDK, or empty when none is found
     */
    public static Optional<Jdk> findJdk(int feature, String javaHome) {
        if (javaHome != null && !javaHome.isBlank()) {
            Optional<Jdk> home = jdk(Path.of(javaHome)).filter(jdk -> jdk.feature() == feature);
            if (home.isPresent()) {
                return home;
            }
        }
        Path javaHomeTool = Path.of("/usr/libexec/java_home");
        if (OperatingSystem.current() == OperatingSystem.MAC && Files.isExecutable(javaHomeTool)) {
            try {
                Process process = new ProcessBuilder(javaHomeTool.toString(), "-v", String.valueOf(feature))
                        .redirectErrorStream(false).start();
                String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
                if (process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0 && !out.isEmpty()) {
                    return jdk(Path.of(out)).filter(jdk -> jdk.feature() == feature);
                }
            } catch (IOException e) {
                return Optional.empty();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return Optional.empty();
    }

    /**
     * The provider that opens a store: the one its manifest names, else the one for its layout. These are the
     * {@code CONTROLLER_NAME}s of rocks-kb, tinkar-core's 64-bit spined array, and komet-sequential-store-plugin.
     *
     * @param store the store
     * @return the controller name, or empty for an entry no provider opens
     */
    public static Optional<String> controller(KnowledgeBase store) {
        String named = store.attributes().get(KnowledgeBase.OPEN_CONTROLLER);
        if (named != null && !named.isBlank()) {
            return Optional.of(named.strip());
        }
        return switch (store.kind()) {
            case ROCKS -> Optional.of("Open Rocks KB");
            case SPINED_64 -> Optional.of("Open 64-bit SpinedArrayStore");
            case SPINED_32 -> Optional.of("Open SpinedArrayStore");
            default -> Optional.empty();
        };
    }

    /**
     * Why a launch cannot start, if it cannot.
     *
     * @param launch   the launch
     * @param portFree whether a port is free
     * @return the reasons, empty when it can start
     */
    public static List<String> refusals(Launch launch, java.util.function.IntPredicate portFree) {
        List<String> refusals = new ArrayList<>();
        if (controller(launch.store()).isEmpty()) {
            refusals.add(Names.of(launch.store().name()) + " is not a store tinkar-service opens.");
        }
        if (launch.build().jdk() > 0 && launch.jdk().feature() != launch.build().jdk()) {
            refusals.add("This build is for Java " + launch.build().jdk() + " and uses preview features, so it runs only on Java "
                    + launch.build().jdk() + ". The JDK chosen is Java " + launch.jdk().feature() + ".");
        }
        if (launch.restPort() == launch.grpcPort()) {
            refusals.add("The REST and gRPC ports must differ.");
        }
        for (int port : new int[] {launch.restPort(), launch.grpcPort()}) {
            if (!portFree.test(port)) {
                refusals.add("Port " + port + " is in use, perhaps by a tinkar-service started elsewhere. Stop it, or choose another port.");
            }
        }
        if (StoreLock.openInKomet(launch.store().path())) {
            refusals.add(Names.of(launch.store().name()) + " is open in Komet or another service. Close it there, then try again.");
        }
        return refusals;
    }

    /**
     * Whether nothing listens on a port on this machine.
     *
     * @param port the port
     * @return {@code true} when it can be bound
     */
    public static boolean portFree(int port) {
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress(port));
            return true;
        } catch (BindException e) {
            return false;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * The command a launch runs.
     *
     * @param launch the launch
     * @return the command and its arguments
     */
    public static List<String> command(Launch launch) {
        List<String> command = new ArrayList<>();
        command.add(launch.jdk().java().toString());
        command.add("--enable-preview");
        command.add("-Xmx" + Math.max(1, launch.need().heap() / HeapSizing.GIB) + "g");
        if (launch.need().cache() > 0) {
            command.add("-D" + HeapSizing.CACHE_PROPERTY + "=" + launch.need().cache());
        }
        command.add("-jar");
        command.add(launch.build().jar().toAbsolutePath().toString());
        Path store = launch.store().path().toAbsolutePath();
        command.add("--data.path.parent=" + store.getParent());
        command.add("--data.path.child=" + store.getFileName());
        command.add("--data.controller.name=" + controller(launch.store()).orElseThrow());
        // A dataset name set in the checkout's .env would replace the store chosen here.
        command.add("--dataset.name=");
        command.add("--server.port=" + launch.restPort());
        command.add("--grpc.server.port=" + launch.grpcPort());
        return List.copyOf(command);
    }

    /**
     * Where a launch logs: a file of its own in the dashboard's log folder.
     *
     * @param logFolder the log folder
     * @param store     the store
     * @return the log file
     */
    static Path logFile(Path logFolder, KnowledgeBase store) {
        return logFolder.resolve("tinkar-service-" + store.name() + "-" + LocalDateTime.now().format(STAMP) + ".log");
    }

    /**
     * Starts a launch.
     *
     * @param launch    the launch
     * @param logFolder where its log goes
     * @return the run
     * @throws IOException if the process cannot be started
     */
    public static ServiceRun start(Launch launch, Path logFolder) throws IOException {
        Files.createDirectories(logFolder);
        Path log = logFile(logFolder, launch.store());
        Process process = new ProcessBuilder(command(launch))
                .directory(launch.build().workingFolder().toFile())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        return new ServiceRun(launch, process, log, Instant.now());
    }

    /** The leading number of a version, such as 27 for {@code 27.0.1}; 0 when there is none. */
    static int feature(String version) {
        if (version == null) {
            return 0;
        }
        String digits = version.strip().replaceFirst("^1\\.", "").split("[^0-9]", 2)[0];
        try {
            return digits.isEmpty() ? 0 : Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * The last lines of a log, for saying why a run stopped.
     *
     * @param log   the log
     * @param lines how many
     * @return the lines, or a sentence saying the log cannot be read
     */
    static String tail(Path log, int lines) {
        try {
            List<String> all = Files.readAllLines(log, StandardCharsets.UTF_8);
            return String.join("\n", all.subList(Math.max(0, all.size() - lines), all.size()));
        } catch (IOException e) {
            return "(its log " + log + " cannot be read: " + e.getMessage().toLowerCase(Locale.ROOT) + ")";
        }
    }
}
