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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which change sets need an upgrade, told from their files (IKE-Network/ike-issues#1313). Two are
 * real: a session change set as Komet's writer left it in a 64-bit store on 2026-10-23, its
 * references naming no pattern, and the same change set after Komet upgraded it against that store.
 */
class ChangeSetCheckTest {

    @TempDir
    Path dir;

    @Test
    void aChangeSetAsTheWriterLeftItNeedsAnUpgradeAndItsUpgradeDoesNot() throws IOException {
        assertThat(ChangeSetCheck.need(resource("format-3-without-patterns.zip")))
                .isEqualTo(ChangeSetCheck.Need.FORMAT_3_WITHOUT_PATTERNS);
        assertThat(ChangeSetCheck.need(resource("format-3-upgraded.zip"))).isEqualTo(ChangeSetCheck.Need.CURRENT);
    }

    @Test
    void aManifestWithoutAFormatVersionIsFormat1() throws IOException {
        assertThat(ChangeSetCheck.need(changeSet("a.zip", null))).isEqualTo(ChangeSetCheck.Need.FORMAT_1);
    }

    @Test
    void formatVersion2IsFormat2() throws IOException {
        assertThat(ChangeSetCheck.need(changeSet("b.zip", "2"))).isEqualTo(ChangeSetCheck.Need.FORMAT_2);
    }

    @Test
    void aTableEntryWithNoPatternSequenceNamesNoPattern() throws IOException {
        // Entry 1 names itself as the pattern pattern; entry 2 names it; entry 3 names none.
        byte[] named = table(entry(1), entry(1));
        byte[] unnamed = table(entry(0));
        assertThat(ChangeSetCheck.need(format3("c.zip", named, table(entry(1)))))
                .isEqualTo(ChangeSetCheck.Need.CURRENT);
        assertThat(ChangeSetCheck.need(format3("d.zip", named, unnamed)))
                .isEqualTo(ChangeSetCheck.Need.FORMAT_3_WITHOUT_PATTERNS);
    }

    @Test
    void emptyFilesAndOtherZipsAreNoChangeSets() throws IOException {
        assertThat(ChangeSetCheck.need(Files.createFile(dir.resolve("empty ike-cs.zip")))).isEqualTo(ChangeSetCheck.Need.NONE);
        assertThat(ChangeSetCheck.need(Files.writeString(dir.resolve("notes.zip"), "not a zip")))
                .isEqualTo(ChangeSetCheck.Need.NONE);
        Path noManifest = dir.resolve("other.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(noManifest))) {
            out.putNextEntry(new ZipEntry("readme.txt"));
            out.write("hello".getBytes(StandardCharsets.UTF_8));
        }
        assertThat(ChangeSetCheck.need(noManifest)).isEqualTo(ChangeSetCheck.Need.NONE);
    }

    @Test
    void aStoreIsCheckedByItsWritersFolderAlone() throws IOException {
        Path store = dir.resolve("store");
        Path folder = Files.createDirectories(ChangeSetCheck.folder(store));
        Files.copy(resource("format-3-without-patterns.zip"), folder.resolve("x ike-cs.zip"));
        Files.copy(resource("format-3-upgraded.zip"), folder.resolve("y ike-cs.zip"));
        Files.copy(changeSet("a.zip", null), folder.resolve("z ike-cs.zip"));
        Files.createFile(folder.resolve("empty ike-cs.zip"));
        Files.writeString(folder.resolve("x ike-cs.zip.spool"), "a session still writing");
        Files.copy(changeSet("b.zip", "2"), Files.createDirectories(folder.resolve("nested")).resolve("n ike-cs.zip"));

        ChangeSetCheck.Finding finding = ChangeSetCheck.check(store);

        assertThat(finding).isEqualTo(new ChangeSetCheck.Finding(1, 0, 1, 1));
        assertThat(finding.needed()).isEqualTo(2);
        assertThat(finding.text()).isEqualTo(
                "2 change sets need an upgrade to load in a 64-bit store: 1 in format 1, 1 in format 3 naming no pattern");
    }

    @Test
    void aStoreWithNoChangeSetFolderNeedsNothing() throws IOException {
        assertThat(ChangeSetCheck.check(dir.resolve("no-store"))).isEqualTo(ChangeSetCheck.Finding.NONE);
    }

    private Path resource(String name) throws IOException {
        Path file = dir.resolve(name);
        try (InputStream in = ChangeSetCheckTest.class.getResourceAsStream("/change-sets/" + name)) {
            assertThat(in).as(name).isNotNull();
            Files.copy(in, file);
        }
        return file;
    }

    /** A change set with only a manifest, naming {@code version} or no version. */
    private Path changeSet(String name, String version) throws IOException {
        Path file = dir.resolve(name);
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(file))) {
            out.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
            out.write(("Manifest-Version: 1.0\n" + (version == null ? "" : ChangeSetCheck.VERSION_ATTRIBUTE + ": "
                    + version + "\n") + "\n").getBytes(StandardCharsets.UTF_8));
        }
        return file;
    }

    private Path format3(String name, byte[] components, byte[] references) throws IOException {
        Path file = dir.resolve(name);
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(file))) {
            out.putNextEntry(new ZipEntry(ChangeSetCheck.TABLES.get(0)));
            out.write(components);
            out.putNextEntry(new ZipEntry(ChangeSetCheck.TABLES.get(1)));
            out.write(references);
            out.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
            out.write(("Manifest-Version: 1.0\n" + ChangeSetCheck.VERSION_ATTRIBUTE + ": 3\n\n")
                    .getBytes(StandardCharsets.UTF_8));
        }
        return file;
    }

    /** One {@code ComponentEntry}: its pattern sequence (left out at 0, as protobuf does) and one UUID. */
    private static byte[] entry(int patternSequence) {
        ByteArrayOutputStream entry = new ByteArrayOutputStream();
        if (patternSequence != 0) {
            entry.write(0x08);
            varint(entry, patternSequence);
        }
        entry.write(0x12); // uuid_bits, packed fixed64
        entry.write(16);
        entry.writeBytes(new byte[16]);
        return entry.toByteArray();
    }

    /** One length-delimited {@code ComponentTable} message of the entries. */
    private static byte[] table(byte[]... entries) {
        ByteArrayOutputStream message = new ByteArrayOutputStream();
        for (byte[] entry : entries) {
            message.write(0x0a);
            varint(message, entry.length);
            message.writeBytes(entry);
        }
        ByteArrayOutputStream delimited = new ByteArrayOutputStream();
        varint(delimited, message.size());
        delimited.writeBytes(message.toByteArray());
        return delimited.toByteArray();
    }

    private static void varint(ByteArrayOutputStream out, long value) {
        while ((value & ~0x7fL) != 0) {
            out.write((int) ((value & 0x7f) | 0x80));
            value >>>= 7;
        }
        out.write((int) value);
    }
}
