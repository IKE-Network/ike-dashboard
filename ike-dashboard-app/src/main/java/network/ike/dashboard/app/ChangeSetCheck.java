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

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.Manifest;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Which of a store's change sets need an upgrade before a store that mints nids by pattern, such as
 * the 64-bit spined array, loads them (IKE-Network/ike-issues#1313), told from the files alone as
 * Komet's {@code ChangeSetUpgrade} tells it: a manifest with no {@value #VERSION_ATTRIBUTE} is
 * format 1; version 2 is format 2; a format-3 change set whose component or reference table gives
 * a component pattern sequence 0 names no pattern for it. Empty files, sessions that wrote nothing,
 * are no change sets.
 */
public final class ChangeSetCheck {

    /** The manifest attribute naming a change set's format version. */
    static final String VERSION_ATTRIBUTE = "Ike-Format-Version";

    /** The tables a format-3 change set numbers its components in, carried and then referenced. */
    static final List<String> TABLES = List.of("META-INF/components.pb", "META-INF/references.pb");

    private ChangeSetCheck() {
    }

    /** What one file needs. */
    public enum Need {
        /** Not a change set: empty, or no zip with a manifest. */
        NONE,
        /** Format 1. */
        FORMAT_1,
        /** Format 2. */
        FORMAT_2,
        /** Format 3 naming no pattern for a component it refers to. */
        FORMAT_3_WITHOUT_PATTERNS,
        /** Format 3 with every pattern named. */
        CURRENT
    }

    /**
     * What a store's change-set folder holds.
     *
     * @param format1                change sets in format 1
     * @param format2                change sets in format 2
     * @param format3WithoutPatterns format-3 change sets naming no pattern for a component
     * @param current                change sets with nothing to upgrade
     */
    public record Finding(int format1, int format2, int format3WithoutPatterns, int current) {

        /** Nothing found. */
        public static final Finding NONE = new Finding(0, 0, 0, 0);

        /**
         * How many change sets an upgrade rewrites.
         *
         * @return the count
         */
        public int needed() {
            return format1 + format2 + format3WithoutPatterns;
        }

        /**
         * The finding, for the person: how many need an upgrade, and in what formats.
         *
         * @return such as {@code 3 change sets need an upgrade: 1 in format 1, 2 in format 2}
         */
        public String text() {
            List<String> parts = new ArrayList<>();
            if (format1 > 0) {
                parts.add(format1 + " in format 1");
            }
            if (format2 > 0) {
                parts.add(format2 + " in format 2");
            }
            if (format3WithoutPatterns > 0) {
                parts.add(format3WithoutPatterns + " in format 3 naming no pattern");
            }
            return needed() + (needed() == 1 ? " change set needs" : " change sets need") + " an upgrade to load in a"
                    + " 64-bit store: " + String.join(", ", parts);
        }
    }

    /**
     * The folder a store's change-set writer writes to.
     *
     * @param store the store's folder
     * @return {@code <store>/changeSets/src/main/resources}
     */
    public static Path folder(Path store) {
        return store.resolve("changeSets").resolve("src").resolve("main").resolve("resources");
    }

    /**
     * Checks the change sets in a store's change-set folder, not its subfolders.
     *
     * @param store the store's folder
     * @return what they need; {@link Finding#NONE} when it has no change-set folder
     * @throws IOException if the folder cannot be listed
     */
    public static Finding check(Path store) throws IOException {
        Path folder = folder(store);
        if (!Files.isDirectory(folder)) {
            return Finding.NONE;
        }
        int[] counts = new int[Need.values().length];
        try (Stream<Path> files = Files.list(folder)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".zip")).toList()) {
                counts[need(file).ordinal()]++;
            }
        }
        return new Finding(counts[Need.FORMAT_1.ordinal()], counts[Need.FORMAT_2.ordinal()],
                counts[Need.FORMAT_3_WITHOUT_PATTERNS.ordinal()], counts[Need.CURRENT.ordinal()]);
    }

    /**
     * What one file needs.
     *
     * @param file the file
     * @return its need; {@link Need#NONE} for an empty or unreadable file
     */
    public static Need need(Path file) {
        try {
            if (!Files.isRegularFile(file) || Files.size(file) == 0) {
                return Need.NONE;
            }
            try (ZipFile zip = new ZipFile(file.toFile())) {
                ZipEntry entry = zip.getEntry("META-INF/MANIFEST.MF");
                if (entry == null) {
                    return Need.NONE;
                }
                String version;
                try (InputStream in = zip.getInputStream(entry)) {
                    version = new Manifest(in).getMainAttributes().getValue(VERSION_ATTRIBUTE);
                }
                if (version == null) {
                    return Need.FORMAT_1;
                }
                return switch (version.strip()) {
                    case "2" -> Need.FORMAT_2;
                    case "3" -> namesEveryPattern(zip) ? Need.CURRENT : Need.FORMAT_3_WITHOUT_PATTERNS;
                    default -> Need.NONE;
                };
            }
        } catch (IOException | RuntimeException e) {
            return Need.NONE;
        }
    }

    /**
     * Whether every component of a format-3 change set's tables names its pattern. The tables are
     * length-delimited {@code ComponentTable} messages ({@code Tinkar.proto}): field 1 repeats
     * {@code ComponentEntry}, whose field 1 is {@code pattern_sequence}, left out when it is 0.
     */
    static boolean namesEveryPattern(ZipFile zip) throws IOException {
        for (String table : TABLES) {
            ZipEntry entry = zip.getEntry(table);
            if (entry == null) {
                continue;
            }
            try (InputStream in = new BufferedInputStream(zip.getInputStream(entry), 1 << 16)) {
                while (true) {
                    long length = varint(in, true);
                    if (length < 0) {
                        break;
                    }
                    byte[] message = in.readNBytes((int) length);
                    if (message.length != length) {
                        throw new EOFException(table + " ends inside a message");
                    }
                    if (!everyEntryNamesAPattern(message)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /** Whether each {@code ComponentEntry} of one {@code ComponentTable} message has a pattern sequence. */
    private static boolean everyEntryNamesAPattern(byte[] table) throws IOException {
        Reader reader = new Reader(table, 0, table.length);
        while (reader.more()) {
            long key = reader.varint();
            int field = (int) (key >>> 3);
            int wireType = (int) (key & 7);
            if (field == 1 && wireType == 2) {
                int length = (int) reader.varint();
                if (patternSequence(new Reader(table, reader.position, reader.position + length)) == 0) {
                    return false;
                }
                reader.position += length;
            } else {
                reader.skip(wireType);
            }
        }
        return true;
    }

    private static long patternSequence(Reader entry) throws IOException {
        long sequence = 0;
        while (entry.more()) {
            long key = entry.varint();
            if ((key >>> 3) == 1 && (key & 7) == 0) {
                sequence = entry.varint();
            } else {
                entry.skip((int) (key & 7));
            }
        }
        return sequence;
    }

    /** A varint from a stream, or -1 at a clean end when {@code endAllowed}. */
    private static long varint(InputStream in, boolean endAllowed) throws IOException {
        long value = 0;
        for (int shift = 0; shift < 64; shift += 7) {
            int b = in.read();
            if (b < 0) {
                if (shift == 0 && endAllowed) {
                    return -1;
                }
                throw new EOFException("a change-set table ends inside a varint");
            }
            value |= (long) (b & 0x7f) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
        }
        throw new IOException("a varint longer than 64 bits");
    }

    /** Reads protobuf wire format from a slice of bytes. */
    private static final class Reader {
        private final byte[] bytes;
        private final int end;
        private int position;

        Reader(byte[] bytes, int start, int end) {
            this.bytes = bytes;
            this.position = start;
            this.end = end;
        }

        boolean more() {
            return position < end;
        }

        long varint() throws IOException {
            long value = 0;
            for (int shift = 0; shift < 64; shift += 7) {
                if (position >= end) {
                    throw new EOFException("a change-set table ends inside a varint");
                }
                int b = bytes[position++] & 0xff;
                value |= (long) (b & 0x7f) << shift;
                if ((b & 0x80) == 0) {
                    return value;
                }
            }
            throw new IOException("a varint longer than 64 bits");
        }

        void skip(int wireType) throws IOException {
            switch (wireType) {
                case 0 -> varint();
                case 1 -> position += 8;
                case 2 -> {
                    // Read the length first: "position += varint()" keeps the position from before it.
                    int length = (int) varint();
                    position += length;
                }
                case 5 -> position += 4;
                default -> throw new IOException("wire type " + wireType + " in a change-set table");
            }
            if (position > end) {
                throw new EOFException("a change-set table ends inside a field");
            }
        }
    }
}
