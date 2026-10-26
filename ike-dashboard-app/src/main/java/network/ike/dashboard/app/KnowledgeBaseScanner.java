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
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.stream.Stream;

/**
 * Lists the knowledge bases in a folder and reads what each says about itself, with the JDK alone: its
 * knowledge-base descriptor {@code META-INF/ike/knowledge-base.properties}, which decides whether the
 * dashboard manages it, and, for the details of what it does not manage, its Maven descriptor at
 * {@code META-INF/maven/<g>/<a>/pom.properties} and the {@code Ike-*} attributes of its
 * {@code META-INF/MANIFEST.MF}. No store is opened. This is the one place a knowledge-base descriptor
 * is read, the ones on Nexus included.
 */
public final class KnowledgeBaseScanner {

    /** The knowledge-base descriptor, relative to the store. */
    public static final String DESCRIPTOR = "META-INF/ike/knowledge-base.properties";

    /**
     * Where the dashboard keeps what a downloaded protobuf says about itself, beside {@code ~/Solor}'s
     * zips: a folder per zip, {@code .ike/protobufs/<zip name>/}, laid out as a store's {@code META-INF}
     * is. A zip cannot carry it, since Komet reads every entry of a change-set zip; a hidden folder is
     * one Komet does not list.
     */
    public static final String PROTOBUF_DESCRIPTIONS = ".ike/protobufs";

    /** The descriptor levels the dashboard reads. */
    public static final Set<Integer> SUPPORTED_LEVELS = Set.of(1);

    private static final Set<String> LAYOUTS = Set.of("rocks", "spined-32", "spined-64");
    private static final Set<String> ACCESS = Set.of("public", "restricted");

    private KnowledgeBaseScanner() {
    }

    /**
     * The entries of a folder that may be knowledge bases: folders and zips, without hidden entries.
     *
     * @param solor the folder, usually {@code ~/Solor}
     * @return one entry each, sorted by name; empty when the folder does not exist
     * @throws IOException if the folder cannot be listed
     */
    public static List<KnowledgeBase> scan(Path solor) throws IOException {
        if (!Files.isDirectory(solor)) {
            return List.of();
        }
        List<KnowledgeBase> found = new ArrayList<>();
        try (Stream<Path> entries = Files.list(solor)) {
            for (Path entry : entries.sorted(Comparator.comparing(path -> path.getFileName().toString())).toList()) {
                String name = entry.getFileName().toString();
                if (name.startsWith(".") || !(Files.isDirectory(entry) || name.endsWith(".zip"))) {
                    continue;
                }
                found.add(inspect(entry));
            }
        }
        return List.copyOf(found);
    }

    /**
     * Reads one entry.
     *
     * @param entry a folder or zip
     * @return what it says about itself
     * @throws IOException if it cannot be read
     */
    public static KnowledgeBase inspect(Path entry) throws IOException {
        Path describing = describing(entry);
        return new KnowledgeBase(entry.getFileName().toString(), entry, kind(entry), size(entry), descriptor(describing),
                ikeAttributes(describing), Files.getLastModifiedTime(entry).toInstant(), knowledgeBaseDescriptor(describing));
    }

    /**
     * The folder that says what an entry is: a store's own, a zip's under {@value #PROTOBUF_DESCRIPTIONS}.
     *
     * @param entry a folder or zip
     * @return the folder its {@code META-INF} is in
     */
    public static Path describing(Path entry) {
        String name = entry.getFileName().toString();
        if (!name.endsWith(".zip") || Files.isDirectory(entry)) {
            return entry;
        }
        return entry.resolveSibling(PROTOBUF_DESCRIPTIONS).resolve(name);
    }

    /**
     * Whether a store's knowledge-base descriptor meets the minimum for the dashboard to manage it: at a
     * supported level, every required field, and the icon it names inside the store.
     *
     * @param entry a folder or zip
     * @return the descriptor, or why the store is not managed
     * @throws IOException if the descriptor exists but cannot be read
     */
    public static DescriptorCheck knowledgeBaseDescriptor(Path entry) throws IOException {
        Path file = entry.resolve(DESCRIPTOR);
        if (!Files.isDirectory(entry) || !Files.isRegularFile(file)) {
            return DescriptorCheck.unmet(List.of("no knowledge-base descriptor"));
        }
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            properties.load(in);
        }
        DescriptorCheck fields = knowledgeBaseDescriptor(properties);
        if (!fields.met()) {
            return fields;
        }
        String icon = fields.descriptor().get().icon();
        try {
            Path resolved = entry.resolve(icon).normalize();
            if (!resolved.startsWith(entry.normalize())) {
                return DescriptorCheck.unmet(List.of("icon " + icon + " is outside the store"));
            }
            if (!Files.isRegularFile(resolved)) {
                return DescriptorCheck.unmet(List.of("icon " + icon + " is missing"));
            }
        } catch (InvalidPathException e) {
            return DescriptorCheck.unmet(List.of("icon " + icon + " is not a path"));
        }
        return fields;
    }

    /**
     * Whether a knowledge-base descriptor's fields meet the minimum: a supported level and every field
     * the level requires, each well formed. The icon file is not looked for; a store's is checked by
     * {@link #knowledgeBaseDescriptor(Path)}, one on Nexus by its {@code png:icon} beside it.
     *
     * @param properties the descriptor's entries
     * @return the descriptor, or what is missing or malformed
     */
    public static DescriptorCheck knowledgeBaseDescriptor(Properties properties) {
        String level = field(properties, "descriptorLevel");
        if (level == null) {
            return DescriptorCheck.unmet(List.of("descriptor names no level"));
        }
        int number;
        try {
            number = Integer.parseInt(level);
        } catch (NumberFormatException e) {
            return DescriptorCheck.unmet(List.of("descriptor level " + level + " is not a number"));
        }
        if (!SUPPORTED_LEVELS.contains(number)) {
            return DescriptorCheck.unmet(List.of("descriptor level " + number + " is not one the dashboard reads"));
        }
        List<String> problems = new ArrayList<>();
        for (String required : List.of("groupId", "artifactId", "version", "displayName", "layout", "entityFormat",
                "assembledAt", "access", "icon")) {
            if (field(properties, required) == null) {
                problems.add("missing " + required);
            }
        }
        String layout = field(properties, "layout");
        if (layout != null && !LAYOUTS.contains(layout)) {
            problems.add("layout " + layout + " is not rocks, spined-32 or spined-64");
        }
        String access = field(properties, "access");
        if (access != null && !ACCESS.contains(access)) {
            problems.add("access " + access + " is not public or restricted");
        }
        Integer entityFormat = null;
        String format = field(properties, "entityFormat");
        try {
            entityFormat = format == null ? null : Integer.valueOf(format);
        } catch (NumberFormatException e) {
            problems.add("entityFormat " + format + " is not a number");
        }
        Instant assembledAt = null;
        String assembled = field(properties, "assembledAt");
        try {
            assembledAt = assembled == null ? null : Instant.parse(assembled);
        } catch (DateTimeParseException e) {
            problems.add("assembledAt " + assembled + " is not an instant");
        }
        if (!problems.isEmpty()) {
            return DescriptorCheck.unmet(problems);
        }
        return DescriptorCheck.met(new KnowledgeBaseDescriptor(number,
                new Coordinate(field(properties, "groupId"), field(properties, "artifactId"), field(properties, "version")),
                Optional.ofNullable(field(properties, "resolvedVersion")), field(properties, "displayName"), layout,
                entityFormat, assembledAt, access, field(properties, "icon"),
                new KnowledgeBaseDescriptor.Memory(bytes(properties, "statedAxiomBytes"),
                        bytes(properties, "loadedHeapBytes"), bytes(properties, "reasonerHeapBytes")),
                Optional.ofNullable(field(properties, "collection")), inputs(properties)));
    }

    /**
     * The inputs a descriptor records, {@code input.1} on until one is missing; an entry that is not a
     * role and a reference is left out, since no level requires them.
     *
     * @param properties the descriptor
     * @return the inputs, in order
     */
    static List<KnowledgeBaseDescriptor.Input> inputs(Properties properties) {
        List<KnowledgeBaseDescriptor.Input> inputs = new ArrayList<>();
        for (int n = 1; field(properties, "input." + n) != null; n++) {
            String[] entry = field(properties, "input." + n).split("\\s+", 2);
            if (entry.length == 2) {
                inputs.add(new KnowledgeBaseDescriptor.Input(entry[0], entry[1],
                        Optional.ofNullable(field(properties, "input." + n + ".resolvedVersion")),
                        Optional.ofNullable(field(properties, "input." + n + ".importer"))));
            }
        }
        return inputs;
    }

    /** An optional size in bytes; absent when missing or not a whole number, which no level requires. */
    private static Optional<Long> bytes(Properties properties, String name) {
        try {
            return Optional.ofNullable(field(properties, name)).map(Long::valueOf).filter(value -> value >= 0);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** A field's value, stripped; {@code null} when absent or blank. */
    private static String field(Properties properties, String name) {
        String value = properties.getProperty(name);
        return value == null || value.isBlank() ? null : value.strip();
    }

    /**
     * What an entry is, from its files.
     *
     * @param entry a folder or zip
     * @return its kind
     * @throws IOException if {@code store-format.properties} cannot be read
     */
    static StoreKind kind(Path entry) throws IOException {
        if (Files.isRegularFile(entry)) {
            return entry.getFileName().toString().endsWith(".zip") ? StoreKind.CHANGE_SETS : StoreKind.UNKNOWN;
        }
        if (Files.isDirectory(entry.resolve("rocks"))) {
            return StoreKind.ROCKS;
        }
        if (Files.isDirectory(entry.resolve("nidToByteArrayMap"))
                && Files.isDirectory(entry.resolve("nidToCitingComponentNidMap"))) {
            return StoreKind.SPINED_32;
        }
        Path format = entry.resolve("store-format.properties");
        if (Files.isRegularFile(format)) {
            Properties properties = new Properties();
            try (InputStream in = Files.newInputStream(format)) {
                properties.load(in);
            }
            if ("64-bit".equals(properties.getProperty("layout"))) {
                return StoreKind.SPINED_64;
            }
        }
        return StoreKind.UNKNOWN;
    }

    /**
     * The coordinate of the store's Maven descriptor, the first under {@code META-INF/maven}.
     *
     * @param entry a folder or zip
     * @return the coordinate, or empty when the store carries none
     * @throws IOException if a descriptor exists but cannot be read
     */
    static Optional<Coordinate> descriptor(Path entry) throws IOException {
        Path maven = entry.resolve("META-INF").resolve("maven");
        if (!Files.isDirectory(maven)) {
            return Optional.empty();
        }
        try (Stream<Path> files = Files.walk(maven, 3)) {
            Optional<Path> found = files.filter(path -> path.getFileName().toString().equals("pom.properties"))
                    .sorted().findFirst();
            if (found.isEmpty()) {
                return Optional.empty();
            }
            Properties properties = new Properties();
            try (InputStream in = Files.newInputStream(found.get())) {
                properties.load(in);
            }
            String groupId = properties.getProperty("groupId");
            String artifactId = properties.getProperty("artifactId");
            String version = properties.getProperty("version");
            if (groupId == null || artifactId == null || version == null) {
                return Optional.empty();
            }
            return Optional.of(new Coordinate(groupId, artifactId, version));
        }
    }

    /**
     * The {@code Ike-*} main attributes of the store's manifest.
     *
     * @param entry a folder or zip
     * @return the attributes by name, sorted; empty without a manifest
     * @throws IOException if the manifest exists but cannot be read
     */
    static Map<String, String> ikeAttributes(Path entry) throws IOException {
        Path file = entry.resolve("META-INF").resolve("MANIFEST.MF");
        Map<String, String> found = new TreeMap<>();
        if (!Files.isRegularFile(file)) {
            return found;
        }
        Manifest manifest = new Manifest();
        try (InputStream in = Files.newInputStream(file)) {
            manifest.read(in);
        }
        for (Map.Entry<Object, Object> attribute : manifest.getMainAttributes().entrySet()) {
            String name = ((Attributes.Name) attribute.getKey()).toString();
            if (name.startsWith("Ike-")) {
                found.put(name, String.valueOf(attribute.getValue()));
            }
        }
        return found;
    }

    /**
     * The bytes an entry takes, summed over its files.
     *
     * @param entry a folder or file
     * @return the size
     * @throws IOException if it cannot be walked
     */
    static long size(Path entry) throws IOException {
        if (Files.isRegularFile(entry)) {
            return Files.size(entry);
        }
        long[] total = {0};
        Files.walkFileTree(entry, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                total[0] += attributes.size();
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException e) {
                // A file removed or unreadable mid-walk, such as a lock file, does not stop the count.
                return FileVisitResult.CONTINUE;
            }
        });
        return total[0];
    }

}
