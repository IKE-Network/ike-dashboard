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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The change sets an upgrade's check in Komet refused, analyzed for the person deciding whether to cancel
 * the upgrade or set them aside ({@code IKE-Network/ike-issues#1356}). Komet's report names each refused
 * file and why; each file's manifest says who packaged it, when, and what it contains.
 */
public final class RefusedChangeSets {

    /** The folder under a store's root refused change sets are set aside in; Komet never reads it. */
    public static final String UNPROCESSED_FOLDER = "unprocessedChangeSets";

    /** A report row of a refused file: {@code | `<file>` | refused | <records> | <detail> |}. */
    private static final Pattern REFUSED_ROW = Pattern.compile("^\\| `(.+?)` \\| refused \\| [^|]* \\| (.*?) ?\\|$");

    /** The UUIDs a "No entity key found for UUIDs: [...]" refusal names. */
    private static final Pattern MISSING = Pattern.compile("No entity key found for UUIDs: \\[([^]]*)]");

    private static final Pattern UUID = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private RefusedChangeSets() {
    }

    /**
     * One file Komet refused.
     *
     * @param file   the file, relative to the store the check ran on
     * @param detail Komet's reason
     */
    public record Refusal(Path file, String detail) {
    }

    /**
     * What a refused file is, in words.
     *
     * @param refusal     the file and Komet's reason
     * @param packager    who packaged it, as its manifest says, or empty
     * @param packaged    when, as its manifest says, or empty
     * @param contents    what it contains, such as {@code 11 semantics, 11 STAMPs}
     * @param names       the concepts its manifest describes, such as its modules and authors
     * @param reason      why it was refused, in words
     */
    public record Summary(Refusal refusal, String packager, String packaged, String contents, List<String> names,
                          String reason) {

        /**
         * @return the file's name, without its folders
         */
        public String fileName() {
            return refusal.file().getFileName().toString();
        }
    }

    /**
     * The refused files a load report names, in its order.
     *
     * @param report Komet's report
     * @return the refusals; none when it names none
     */
    public static List<Refusal> parse(String report) {
        List<Refusal> refusals = new ArrayList<>();
        for (String line : report.split("\\R")) {
            Matcher row = REFUSED_ROW.matcher(line.strip());
            if (row.matches()) {
                refusals.add(new Refusal(Path.of(row.group(1)), row.group(2).strip()));
            }
        }
        return List.copyOf(refusals);
    }

    /**
     * Reads what a refused file is from its manifest.
     *
     * @param store   the store the check ran on
     * @param refusal the file and Komet's reason
     * @return the summary; with only the reason when the file has no readable manifest
     */
    public static Summary analyze(Path store, Refusal refusal) {
        Optional<Manifest> manifest = manifest(store.resolve(refusal.file()));
        String reason = reason(refusal.detail());
        if (manifest.isEmpty()) {
            return new Summary(refusal, "", "", "", List.of(), reason);
        }
        Attributes main = manifest.get().getMainAttributes();
        List<String> parts = new ArrayList<>();
        count(parts, main, "Concept-Count", "concept", "concepts");
        count(parts, main, "Pattern-Count", "pattern", "patterns");
        count(parts, main, "Semantic-Count", "semantic", "semantics");
        count(parts, main, "Stamp-Count", "STAMP", "STAMPs");
        // A manifest keeps its sections in no order; sorted, the summary reads the same every time.
        Set<String> names = new TreeSet<>();
        manifest.get().getEntries().values().forEach(section -> {
            String description = section.getValue("Description");
            if (description != null && !description.isBlank()) {
                names.add(description.strip());
            }
        });
        return new Summary(refusal, value(main, "Packager-Name"), value(main, "Package-Date"), String.join(", ", parts),
                List.copyOf(names), reason);
    }

    /**
     * Komet's reason in words: for components the build does not contain, how many; otherwise Komet's own
     * words.
     *
     * @param detail Komet's reason
     * @return the reason
     */
    static String reason(String detail) {
        Matcher missing = MISSING.matcher(detail);
        if (missing.find()) {
            long count = UUID.matcher(missing.group(1)).results().count();
            return "It refers to " + (count == 1 ? "a component" : count + " components")
                    + " that neither this build nor any other change set contains.";
        }
        return detail.isBlank() ? "Komet gave no reason." : detail;
    }

    private static Optional<Manifest> manifest(Path zip) {
        if (!Files.isRegularFile(zip)) {
            return Optional.empty();
        }
        try (ZipFile file = new ZipFile(zip.toFile())) {
            ZipEntry entry = file.getEntry("META-INF/MANIFEST.MF");
            if (entry == null) {
                return Optional.empty();
            }
            try (InputStream in = file.getInputStream(entry)) {
                return Optional.of(new Manifest(in));
            }
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static void count(List<String> parts, Attributes main, String key, String one, String many) {
        try {
            int count = Integer.parseInt(value(main, key));
            if (count > 0) {
                parts.add(count + " " + (count == 1 ? one : many));
            }
        } catch (NumberFormatException e) {
            // absent or unreadable: say nothing of it
        }
    }

    private static String value(Attributes main, String key) {
        String value = main.getValue(key);
        return value == null ? "" : value.strip();
    }

    /**
     * Where a refused file is set aside: under {@value #UNPROCESSED_FOLDER}, at the path it had in the
     * store, so where it came from stays plain.
     *
     * @param store the store
     * @param file  the file, relative to the store
     * @return its place under the unprocessed folder
     */
    public static Path asideOf(Path store, Path file) {
        return store.resolve(UNPROCESSED_FOLDER).resolve(file);
    }

    /**
     * Sets the refused files aside, each under {@value #UNPROCESSED_FOLDER} at the path it had.
     *
     * @param store    the store
     * @param refusals the files
     * @return the moves made, from and to, in order, so they can be undone
     * @throws IOException if a file cannot be moved; those already moved are put back first
     */
    public static List<Path[]> setAside(Path store, List<Refusal> refusals) throws IOException {
        List<Path[]> moved = new ArrayList<>();
        try {
            for (Refusal refusal : refusals) {
                Path from = store.resolve(refusal.file());
                Path to = asideOf(store, refusal.file());
                Files.createDirectories(to.getParent());
                Files.move(from, to);
                moved.add(new Path[] {from, to});
            }
        } catch (IOException e) {
            putBack(moved);
            throw e;
        }
        return List.copyOf(moved);
    }

    /**
     * Undoes {@link #setAside}.
     *
     * @param moved the moves it made
     * @throws IOException if a file cannot be moved back
     */
    public static void putBack(List<Path[]> moved) throws IOException {
        for (int i = moved.size() - 1; i >= 0; i--) {
            Files.move(moved.get(i)[1], moved.get(i)[0]);
        }
    }
}
