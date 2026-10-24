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
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.TreeMap;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.stream.Stream;

/**
 * Lists the knowledge bases in a folder and reads what each says about itself: its Maven descriptor
 * at {@code META-INF/maven/<g>/<a>/pom.properties} and the {@code Ike-*} attributes of its
 * {@code META-INF/MANIFEST.MF}, with the JDK alone. No store is opened.
 */
public final class KnowledgeBaseScanner {

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
        return new KnowledgeBase(entry.getFileName().toString(), entry, kind(entry), size(entry), descriptor(entry),
                ikeAttributes(entry), Files.getLastModifiedTime(entry).toInstant());
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
