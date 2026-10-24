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
package network.ike.dashboard.journal;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The usage journal of a Maven local repository: one line per file used, naming the day, the tool
 * that used it, and the file's path relative to the repository root.
 *
 * <pre>
 * # IKE usage journal: &lt;day&gt; TAB &lt;tool&gt; TAB &lt;path relative to the local repository&gt;
 * 2026-10-23	maven	network/ike/data/solor-kb/1-SNAPSHOT/solor-kb-1-SNAPSHOT-rkb.zip
 * </pre>
 *
 * <p>It exists because the file system cannot say when a file was last read: on macOS, reading a
 * file in the local repository leaves its access time unchanged. Writers append, under a file lock
 * and, within one JVM, a monitor, so builds running at once interleave whole lines and nothing else. The journal is compacted to one
 * line per file once it passes {@link #COMPACT_AT_BYTES}, so it stays near the size of the
 * repository's file list however many builds run.
 *
 * <p>Nothing here deletes from the repository; that is the dashboard's collector, which reads
 * {@link #lastUsed(Path)}.
 */
public final class UsageJournal {

    /** The journal's file name, in the local repository's root. */
    public static final String FILE_NAME = ".ike-usage.journal";

    /** The tool name Maven builds record under. */
    public static final String MAVEN = "maven";

    /** The tool name IntelliJ's Maven import records under: Maven embedded in IntelliJ's Maven server. */
    public static final String INTELLIJ = "intellij";

    /** The tool name the dashboard records its own downloads under. */
    public static final String DASHBOARD = "dashboard";

    /** The size past which a write compacts the journal first. */
    static final long COMPACT_AT_BYTES = 16L * 1024 * 1024;

    /**
     * Serializes writers in this JVM: a file lock is held per JVM, so a second thread asking for one
     * on the same file is refused rather than made to wait.
     */
    private static final Object IN_PROCESS = new Object();

    private static final String HEADER =
            "# IKE usage journal: <day> TAB <tool> TAB <path relative to the local repository>\n";

    private UsageJournal() {
    }

    /**
     * The journal file of a local repository.
     *
     * @param localRepository the local repository's root
     * @return the journal file, which may not exist yet
     */
    public static Path file(Path localRepository) {
        return localRepository.resolve(FILE_NAME);
    }

    /**
     * Records that files were used on a day. Files outside the repository are left out, as are
     * duplicates; the rest are appended in one write, under the journal's lock.
     *
     * @param localRepository the local repository's root
     * @param tool            the tool that used them, such as {@link #MAVEN}
     * @param day             the day they were used
     * @param files           the files, absolute or relative to the repository
     * @return how many lines were written
     * @throws IOException if the journal cannot be written
     */
    public static int record(Path localRepository, String tool, LocalDate day, Collection<Path> files)
            throws IOException {
        if (tool.isBlank() || tool.contains("\t") || tool.contains("\n")) {
            throw new IllegalArgumentException("Tool name must be one word: " + tool);
        }
        Path root = localRepository.toAbsolutePath().normalize();
        TreeSet<String> lines = new TreeSet<>();
        for (Path file : files) {
            relative(root, file).ifPresent(path -> lines.add(day + "\t" + tool + "\t" + path + "\n"));
        }
        if (lines.isEmpty()) {
            return 0;
        }
        StringBuilder text = new StringBuilder();
        lines.forEach(text::append);
        Path journal = file(root);
        synchronized (IN_PROCESS) {
            try (FileChannel channel = FileChannel.open(journal, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, StandardOpenOption.READ);
                 FileLock ignored = channel.lock()) {
                if (channel.size() > COMPACT_AT_BYTES) {
                    compactLocked(channel);
                }
                if (channel.size() == 0) {
                    write(channel, HEADER);
                }
                channel.position(channel.size());
                write(channel, text.toString());
            }
        }
        return lines.size();
    }

    /**
     * The last day each file was used, by the journal. A file the journal has never seen is absent.
     * Lines that cannot be read are skipped.
     *
     * @param localRepository the local repository's root
     * @return the last day of use, by path relative to the repository
     * @throws IOException if the journal exists but cannot be read
     */
    public static Map<Path, LocalDate> lastUsed(Path localRepository) throws IOException {
        Path journal = file(localRepository);
        Map<Path, LocalDate> last = new HashMap<>();
        if (!Files.isRegularFile(journal)) {
            return last;
        }
        try (BufferedReader reader = Files.newBufferedReader(journal, StandardCharsets.UTF_8)) {
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                parse(line).ifPresent(entry -> last.merge(entry.path(), entry.day(),
                        (a, b) -> a.isAfter(b) ? a : b));
            }
        }
        return last;
    }

    /**
     * Rewrites the journal as one line per file, its last use, under the journal's lock.
     *
     * @param localRepository the local repository's root
     * @throws IOException if the journal cannot be rewritten
     */
    public static void compact(Path localRepository) throws IOException {
        Path journal = file(localRepository);
        if (!Files.isRegularFile(journal)) {
            return;
        }
        synchronized (IN_PROCESS) {
            try (FileChannel channel = FileChannel.open(journal, StandardOpenOption.WRITE, StandardOpenOption.READ);
                 FileLock ignored = channel.lock()) {
                compactLocked(channel);
            }
        }
    }

    /** One journal line: the day, the tool and the path. */
    record Entry(LocalDate day, String tool, Path path) {
    }

    /**
     * Reads one journal line.
     *
     * @param line the line
     * @return the entry, or empty for a comment, a blank line or a line that cannot be read
     */
    static Optional<Entry> parse(String line) {
        if (line.isBlank() || line.startsWith("#")) {
            return Optional.empty();
        }
        String[] fields = line.split("\t", 3);
        if (fields.length != 3 || fields[2].isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new Entry(LocalDate.parse(fields[0]), fields[1], Path.of(fields[2])));
        } catch (DateTimeParseException | InvalidPathException e) {
            return Optional.empty();
        }
    }

    /**
     * A file's path relative to the repository, with forward slashes, when it lies inside it.
     *
     * @param root the repository's root, absolute and normalized
     * @param file the file, absolute or relative to the root
     * @return the relative path, or empty when the file lies outside the repository
     */
    static Optional<String> relative(Path root, Path file) {
        Path absolute = (file.isAbsolute() ? file : root.resolve(file)).normalize();
        if (!absolute.startsWith(root) || absolute.equals(root)) {
            return Optional.empty();
        }
        String path = root.relativize(absolute).toString().replace('\\', '/');
        if (path.contains("\t") || path.contains("\n") || path.equals(FILE_NAME)) {
            return Optional.empty();
        }
        return Optional.of(path);
    }

    private static void compactLocked(FileChannel channel) throws IOException {
        Map<Path, Entry> last = new TreeMap<>();
        channel.position(0);
        String text = StandardCharsets.UTF_8.decode(read(channel)).toString();
        text.lines().forEach(line -> parse(line).ifPresent(entry -> last.merge(entry.path(), entry,
                (a, b) -> a.day().isAfter(b.day()) ? a : b)));
        StringBuilder compacted = new StringBuilder(HEADER);
        last.values().forEach(entry -> compacted.append(entry.day()).append('\t').append(entry.tool())
                .append('\t').append(entry.path().toString().replace('\\', '/')).append('\n'));
        // Rewritten in place, under the lock every writer takes, so no writer can append between
        // the read and the rewrite.
        channel.truncate(0);
        channel.position(0);
        write(channel, compacted.toString());
        channel.force(true);
    }

    private static ByteBuffer read(FileChannel channel) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(Math.toIntExact(channel.size()));
        while (buffer.hasRemaining() && channel.read(buffer) >= 0) {
            // Read to the end.
        }
        buffer.flip();
        return buffer;
    }

    private static void write(FileChannel channel, String text) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }
}
