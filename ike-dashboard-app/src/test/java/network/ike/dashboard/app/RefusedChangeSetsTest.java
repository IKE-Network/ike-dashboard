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

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link RefusedChangeSets}: what an upgrade's check refused, in words, and setting it aside (ike-issues#1356). */
class RefusedChangeSetsTest {

    private static final String KNOWLEDGE = "changeSets/src/main/resources/";
    private static final String NARRATION = "Author 20261107T190649PST rAB ike-cs.zip";
    private static final String MISSING = "No entity key found for UUIDs: [872518d6-f78f-5ea8-8c5c-64e9e6a83a76],"
            + " and no pattern in scope to allocate one under";

    /** The rows of a report as Komet writes it on 2026-11-08, one loaded, two refused. */
    private static final String REPORT = """
            # Change-set load: /Users/kec/Solor/solor-gudid-card-leak-demo.upgrade

            | File | Outcome | Records | Detail |
            |---|---|---:|---|
            | `changeSets/src/main/resources/Author 20261107T181320PST 8zv ike-cs.zip` | loaded | 548 |  |
            | `changeSets/src/main/resources/Author 20261107T190649PST rAB ike-cs.zip` | refused |  | %s |
            | `instanceChangeSets/Notes [x] Author 20261107T192322PST 1q9 ike-cs.zip` | refused |  | Something else went wrong |

            20 change-set file(s): 18 loaded, 0 registered before, 2 refused
            """.formatted(MISSING);

    @TempDir
    Path store;

    @Test
    void theReportNamesEachRefusedFileAndWhy() {
        List<RefusedChangeSets.Refusal> refused = RefusedChangeSets.parse(REPORT);

        assertThat(refused).containsExactly(
                new RefusedChangeSets.Refusal(Path.of(KNOWLEDGE + NARRATION), MISSING),
                new RefusedChangeSets.Refusal(Path.of("instanceChangeSets/Notes [x] Author 20261107T192322PST 1q9 ike-cs.zip"),
                        "Something else went wrong"));
    }

    @Test
    void aReportWithNoRefusalNamesNone() {
        assertThat(RefusedChangeSets.parse("| `a.zip` | loaded | 3 |  |\n")).isEmpty();
    }

    @Test
    void theManifestSaysWhoPackagedItWhenAndWhatItContains() throws IOException {
        changeSet(KNOWLEDGE + NARRATION);
        RefusedChangeSets.Summary summary = RefusedChangeSets.analyze(store,
                new RefusedChangeSets.Refusal(Path.of(KNOWLEDGE + NARRATION), MISSING));

        assertThat(summary.fileName()).isEqualTo(NARRATION);
        assertThat(summary.packager()).isEqualTo("KOMET user (SOLOR)");
        assertThat(summary.packaged()).isEqualTo("2026-11-08T03:11:44.097814");
        assertThat(summary.contents()).isEqualTo("11 semantics, 11 STAMPs");
        assertThat(summary.names()).containsExactly("Komet Narrator (Claude)", "Narration");
        assertThat(summary.reason()).isEqualTo("It refers to a component that is not in this version of the"
                + " knowledge base or in any other change set.");
    }

    @Test
    void aFileWithNoManifestStillSaysWhy() {
        RefusedChangeSets.Summary summary = RefusedChangeSets.analyze(store,
                new RefusedChangeSets.Refusal(Path.of(KNOWLEDGE + "gone.zip"), "Something else went wrong"));

        assertThat(summary.contents()).isEmpty();
        assertThat(summary.names()).isEmpty();
        assertThat(summary.reason()).isEqualTo("Something else went wrong");
    }

    @Test
    void severalMissingComponentsAreCounted() {
        assertThat(RefusedChangeSets.reason("No entity key found for UUIDs: [872518d6-f78f-5ea8-8c5c-64e9e6a83a76,"
                + " b7034311-c4b9-5b77-8895-9f567bce766e], and no pattern in scope"))
                .startsWith("It refers to 2 components");
    }

    @Test
    void setAsideKeepsEachFileAtItsPathUnderTheUnprocessedFolderAndPutBackUndoesIt() throws IOException {
        Path file = changeSet(KNOWLEDGE + NARRATION);
        List<RefusedChangeSets.Refusal> refused = List.of(new RefusedChangeSets.Refusal(Path.of(KNOWLEDGE + NARRATION), MISSING));

        List<Path[]> moved = RefusedChangeSets.setAside(store, refused);

        Path aside = store.resolve(RefusedChangeSets.UNPROCESSED_FOLDER).resolve(KNOWLEDGE + NARRATION);
        assertThat(file).doesNotExist();
        assertThat(aside).exists();

        RefusedChangeSets.putBack(moved);

        assertThat(file).exists();
        assertThat(aside).doesNotExist();
    }

    @Test
    void anUpgradeCarriesTheUnprocessedFolder() {
        assertThat(StoreUpgrade.CHANGE_SET_FOLDERS).contains(RefusedChangeSets.UNPROCESSED_FOLDER);
    }

    @Test
    void theChoiceSaysWhatEachIsAndWhatSetAsideDoes() throws IOException {
        changeSet(KNOWLEDGE + NARRATION);
        RefusedChangeSets.Summary summary = RefusedChangeSets.analyze(store,
                new RefusedChangeSets.Refusal(Path.of(KNOWLEDGE + NARRATION), MISSING));

        String text = Names.plain(DashboardView.refusedText(List.of(summary), Optional.empty()));

        assertThat(DashboardView.refusedHeader(1)).isEqualTo("A change set could not be loaded into the new version");
        assertThat(DashboardView.refusedHeader(2)).isEqualTo("2 change sets could not be loaded into the new version");
        assertThat(text).contains(NARRATION,
                "11 semantics, 11 STAMPs · Komet Narrator (Claude) · Narration",
                "Packaged by KOMET user (SOLOR) on 2026-11-08 03:11",
                "is not in this version of the knowledge base or in any other change set",
                "Set aside moves it into the folder unprocessedChangeSets");
        assertThat(text).doesNotContain(" hold");
    }

    @Test
    void aFileInARepositoryWithARemoteCannotBeSetAside() {
        Path changeSets = store.resolve("changeSets");
        RepositorySync.Repository repository = new RepositorySync.Repository(changeSets, "change sets");
        List<RefusedChangeSets.Refusal> refused = List.of(new RefusedChangeSets.Refusal(Path.of(KNOWLEDGE + NARRATION), MISSING));

        Optional<String> shared = DashboardApp.sharedRepository(store, refused, List.of(new RepositorySync.Status(
                repository, "main", 0, 0, RepositorySync.Remote.CURRENT, 0, Optional.empty(), "")));
        Optional<String> local = DashboardApp.sharedRepository(store, refused, List.of(new RepositorySync.Status(
                repository, "main", 0, 0, RepositorySync.Remote.NONE, 0, Optional.empty(), "")));

        assertThat(shared).hasValueSatisfying(reason -> assertThat(reason).contains("change sets", "shared through a remote"));
        assertThat(local).isEmpty();
    }

    /** A change set with the manifest Komet wrote for {@code rAB} on 2026-11-08. */
    private Path changeSet(String relative) throws IOException {
        Path file = store.resolve(relative);
        Files.createDirectories(file.getParent());
        String manifest = """
                Manifest-Version: 1.0
                Ike-Format-Version: 3
                Packager-Name: KOMET user (SOLOR)
                Package-Date: 2026-11-08T03:11:44.097814
                Total-Count: 22
                Concept-Count: 0
                Semantic-Count: 11
                Pattern-Count: 0
                Stamp-Count: 11

                Name: e2017267-1339-5834-b029-fd3d964d226b
                Description: Narration

                Name: 06f3a6e6-3627-5115-94fb-238c789b8743
                Description: Komet Narrator (Claude)

                """;
        try (OutputStream out = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
            zip.write(manifest.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return file;
    }
}
