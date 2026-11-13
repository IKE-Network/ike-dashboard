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

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The New knowledge base form (IKE-Network/ike-issues#1370): layers picked from what Nexus offers, in
 * load order, behind the starter set; the project said in the goals' words; Create offered only for what
 * the last draft showed. Drawn headless with Monocle; the drafted form is written to
 * {@code target/dashboard-new-knowledge-base.png}.
 */
class NewKnowledgeBaseFormTest {

    private static final Instant DEPLOYED = Instant.parse("2026-11-11T02:15:38Z");
    private static final long GIB = 1L << 30;

    @BeforeAll
    static void startHeadless() throws Exception {
        System.setProperty("glass.platform", "Monocle");
        System.setProperty("monocle.platform", "Headless");
        System.setProperty("prism.order", "sw");
        CompletableFuture<Void> started = new CompletableFuture<>();
        try {
            Platform.startup(() -> started.complete(null));
        } catch (IllegalStateException alreadyStarted) {
            started.complete(null);
        }
        started.get(30, TimeUnit.SECONDS);
    }

    @Test
    void layersArePickedInLoadOrderBehindTheStarterSetAndTheProjectIsSaidInTheGoalsWords() throws Exception {
        List<Object> shown = onFx(() -> {
            NewKnowledgeBaseForm form = form();
            List<String> untouched = List.of(form.message(), String.valueOf(button(form, "Draft").isDisable()));
            field(form, "my-kb").setText("solor-gene-kb");
            form.addLayer(offer("snomedct-kb", "20260601.2-SNAPSHOT", "unreasoned-pb", (long) (0.45 * GIB)));
            form.addLayer(offer("gudid-kb", "20261001.2-SNAPSHOT", "unreasoned-pb", (long) (2.74 * GIB)));
            form.addLayer(offer("ncbi-gene-kb", "20261020.1-SNAPSHOT", "unreasoned-pb", 100_000));
            accessible(form, "Load NCBI Gene earlier").fire();
            accessible(form, "Remove GUDID").fire();
            form.addLayer(offer("gudid-kb", "20261001.2-SNAPSHOT", "unreasoned-pb", (long) (2.74 * GIB)));
            layout(form);
            return List.of(untouched, Words.of(form), form.project(), button(form, "Draft").isDisable(), form.canCreate());
        });

        @SuppressWarnings("unchecked")
        List<String> words = (List<String>) shown.get(1);
        KnowledgeBaseProject project = (KnowledgeBaseProject) shown.get(2);
        assertThat(shown.getFirst()).as("an untouched form says what to do first, and cannot be drafted")
                .isEqualTo(List.of("Give the knowledge base a name.", "true"));
        assertThat(words).as("the starter set first, locked, then each layer with what Nexus says of it")
                .containsSubsequence("1.", "IKE Starter Set", "Always loaded first. Every other layer depends on it.",
                        "2.", "SNOMED CT", "protobuf, unreasoned  ·  20260601.2-SNAPSHOT, deployed 2026-11-10  ·  460.8 MiB",
                        "Built from snomedct-origin SnomedCT_InternationalRF2_20260601+1.0.0 (source, read by snomed-rf2)",
                        "3.", "NCBI Gene", "4.", "GUDID");
        assertThat(words).as("the sizes, by the goals' rule: 2 GiB of layers or more builds a Rocks store")
                .contains("Layers total 3.19 GiB  ·  about 12.76 GiB of memory once loaded  ·  the build is given 73 GiB",
                        "Built: the layers total 3.19 GiB, at or above 2 GiB.",
                        "ike-restricted  ·  https://nexus.tinkar.org/repository/ike-restricted-snapshots/",
                        "A git repository in its folder, with one commit and no remote.",
                        "Draft it to see what will be created.");
        assertThat(project.name()).isEqualTo("solor-gene-kb");
        assertThat(project.folder()).as("in the projects folder, following the name").isEqualTo(Path.of("/home/kb/solor-gene-kb").toAbsolutePath());
        assertThat(project.properties()).as("only the layers: everything else is the goals' default").containsExactly(Map.entry("layers",
                "network.ike.data:snomedct-kb:zip:unreasoned-pb:20260601.2-SNAPSHOT,"
                        + "network.ike.data:ncbi-gene-kb:zip:unreasoned-pb:20261020.1-SNAPSHOT,"
                        + "network.ike.data:gudid-kb:zip:unreasoned-pb:20261001.2-SNAPSHOT"));
        assertThat(shown.get(3)).as("it can be drafted").isEqualTo(false);
        assertThat(shown.get(4)).as("and not created before a draft").isEqualTo(false);
    }

    @Test
    void createIsOfferedOnlyForWhatTheLastDraftShowed() throws Exception {
        List<Object> shown = onFx(() -> {
            NewKnowledgeBaseForm form = form();
            List<KnowledgeBaseProject> asked = new ArrayList<>();
            form.setOnDraft(asked::add);
            form.setOnCreate(asked::add);
            field(form, "my-kb").setText("gene-try-kb");
            form.addLayer(offer("ncbi-gene-kb", "20261020.1-SNAPSHOT", "unreasoned-pb", 100_000));
            button(form, "Draft").fire();
            form.working("Drafting gene-try-kb …");
            boolean waiting = button(form, "Draft").isDisable() && button(form, "Cancel").isVisible();
            form.drafted(asked.getFirst(), new KnowledgeBaseCreation.Outcome(0, List.of(
                    "[INFO] Knowledge base: Gene Try Knowledge Base (assembly)",
                    "[INFO]   Layers, in load order, pinned:",
                    "[INFO]     network.ike.foundation:ike-starter-set:zip:unreasoned-pb:1-20261107.045844-2, 0.9 MiB",
                    "[INFO]     network.ike.data:ncbi-gene-kb:zip:unreasoned-pb:20261020.1-20261111.021538-1, 0.1 MiB",
                    "[INFO]   Build heap:   -Xmx8g (estimated)",
                    "[INFO] Draft only — run ike:kb-create-publish with the same inputs to create it.")));
            layout(form);
            write(form.getScene().snapshot(null), Path.of("target", "dashboard-new-knowledge-base.png"));
            List<String> drafted = Words.of(form);
            String draft = form.draftText();
            boolean offered = form.canCreate();
            button(form, "Create knowledge base").fire();
            field(form, "None").setText("TRY");                        // the collection
            boolean afterChange = form.canCreate();
            form.drafted(form.project(), new KnowledgeBaseCreation.Outcome(1, List.of(
                    "[ERROR] Failed to execute goal a:b:280:kb-create-draft (default-cli) on project standalone-pom:"
                            + " Cannot pin the layers: it has never been deployed",
                    "[ERROR] ")));
            return List.of(waiting, drafted, draft, offered, asked, afterChange, form.draftText(), form.message(), form.canCreate());
        });

        @SuppressWarnings("unchecked")
        List<String> drafted = (List<String>) shown.get(1);
        @SuppressWarnings("unchecked")
        List<KnowledgeBaseProject> asked = (List<KnowledgeBaseProject>) shown.get(4);
        assertThat(shown.getFirst()).as("while Maven runs the form waits, and offers Cancel").isEqualTo(true);
        assertThat(drafted).as("each layer at the deploy the draft pinned it to, the starter set among them")
                .contains("protobuf, unreasoned  ·  1-20261107.045844-2  ·  0.9 MiB",
                        "protobuf, unreasoned  ·  20261020.1-20261111.021538-1  ·  0.1 MiB",
                        "The draft shows what will be created in " + Path.of("/home/kb/gene-try-kb").toAbsolutePath() + ".");
        assertThat((String) shown.get(2)).as("the goals' report, without Maven's framing")
                .startsWith("Knowledge base: Gene Try Knowledge Base (assembly)\n  Layers, in load order, pinned:")
                .endsWith("  Build heap:   -Xmx8g (estimated)");
        assertThat(shown.get(3)).as("the draft succeeded: Create is offered").isEqualTo(true);
        assertThat(asked).as("Draft and Create are given the same project").hasSize(2);
        assertThat(asked.getLast()).isEqualTo(asked.getFirst());
        assertThat(shown.get(5)).as("a change sets the draft aside").isEqualTo(false);
        assertThat(shown.get(6)).as("a refusal, in the goals' words").isEqualTo("Cannot pin the layers: it has never been deployed");
        assertThat(shown.get(7)).isEqualTo("The draft was refused: Cannot pin the layers: it has never been deployed");
        assertThat(shown.get(8)).isEqualTo(false);
    }

    @Test
    void aSourceLayerAsksForItsImporterAndReleaseAndBuildsNoRocksStore() throws Exception {
        List<Object> shown = onFx(() -> {
            NewKnowledgeBaseForm form = form();
            field(form, "my-kb").setText("nuva-try-kb");
            form.addLayer(offer("snomedct-kb", "20260601.2-SNAPSHOT", "unreasoned-pb", (long) (0.45 * GIB)));
            boolean importerHidden = !shown(field(form, "Such as snomed-rf2"));
            toggle(form, "Source layer").setSelected(true);
            String missing = form.message();
            field(form, "Such as snomed-rf2").setText("nuva");
            field(form, "The release's coordinate, or its path").setText("/releases/nuva.zip");
            toggle(form, "Always").setSelected(true);                     // hidden with the kind, and not said
            layout(form);
            return List.of(importerHidden, missing, form.project(), shown(toggle(form, "Always")),
                    shown(field(form, "Such as snomed-rf2")), form.message());
        });

        KnowledgeBaseProject project = (KnowledgeBaseProject) shown.get(2);
        assertThat(shown.getFirst()).as("an assembly has no importer").isEqualTo(true);
        assertThat(shown.get(1)).isEqualTo("A source layer needs its importer and the release it reads.");
        assertThat(project.properties()).containsExactly(Map.entry("kind", "source-layer"),
                Map.entry("layers", "network.ike.data:snomedct-kb:zip:unreasoned-pb:20260601.2-SNAPSHOT"),
                Map.entry("importer", "nuva"), Map.entry("release", "/releases/nuva.zip"));
        assertThat(shown.get(3)).as("the Rocks choice is gone").isEqualTo(false);
        assertThat(shown.get(4)).as("and the importer is asked for").isEqualTo(true);
        assertThat(shown.get(5)).isEqualTo("Draft it to see what will be created.");
    }

    @Test
    void anotherKnowledgeBasesInputsFillTheListAndWhatIsMissingStopsADraft() throws Exception {
        List<Object> shown = onFx(() -> {
            NewKnowledgeBaseForm form = form();
            field(form, "my-kb").setText("solor-copy-kb");
            form.addLayer(offer("ncbi-gene-kb", "20261020.1-SNAPSHOT", "unreasoned-pb", 100_000));
            form.copyInputs(coordinate("solor-gudid-kb", "1-SNAPSHOT"));
            List<NewKnowledgeBaseForm.Row> rows = form.rows();
            form.setPrerequisites(List.of("JDK 27 or later is not installed. Install one, or set JAVA_HOME to one."));
            return List.of(rows, form.message(), button(form, "Draft").isDisable());
        });

        @SuppressWarnings("unchecked")
        List<NewKnowledgeBaseForm.Row> rows = (List<NewKnowledgeBaseForm.Row>) shown.getFirst();
        assertThat(rows).as("its protobufs, in its load order, in place of what was listed; the starter set is put first anyway")
                .extracting(row -> row.layer().toString())
                .containsExactly("network.ike.data:snomedct-kb:zip:unreasoned-pb:20260601.2-SNAPSHOT",
                        "network.ike.data:gudid-kb:zip:unreasoned-pb:20261001.2-SNAPSHOT");
        assertThat(rows).extracting(NewKnowledgeBaseForm.Row::name).containsExactly("SNOMED CT", "GUDID");
        assertThat(rows.getFirst().sizeBytes()).as("the size Nexus lists for that protobuf").isEqualTo((long) (0.45 * GIB));
        assertThat(rows.getLast().sizeBytes()).as("not known when Nexus lists none at that version").isEqualTo(-1);
        assertThat(shown.get(1)).isEqualTo("JDK 27 or later is not installed. Install one, or set JAVA_HOME to one.");
        assertThat(shown.get(2)).isEqualTo(true);
    }

    @Test
    void aNameAlreadyInUseStopsADraftAndAFreeOneIsOneClickAway() throws Exception {
        List<Object> shown = onFx(() -> {
            NewKnowledgeBaseForm form = form();
            form.setNamesHere(new KnowledgeBaseProject.Names(java.util.Set.of("gene-kb"), java.util.Set.of(), java.util.Set.of(),
                    java.util.Set.of()));
            form.addLayer(offer("ncbi-gene-kb", "20261020.1-SNAPSHOT", "unreasoned-pb", 100_000));
            field(form, "my-kb").setText("gene-kb");
            String here = form.message();
            boolean stopped = button(form, "Draft").isDisable();
            field(form, "my-kb").setText("snomedct-kb");
            String nexus = form.message();
            javafx.scene.control.Hyperlink use = all(form, javafx.scene.control.Hyperlink.class).stream()
                    .filter(link -> link.getText().startsWith("Use ")).findFirst().orElseThrow();
            String offered = use.getText();
            use.fire();
            return List.of(here, stopped, nexus, offered, form.project().name(), form.project().folder(), form.message(),
                    button(form, "Draft").isDisable(), shown(use));
        });

        assertThat(shown.getFirst()).as("a project of that name is already made")
                .isEqualTo("A project named gene-kb is already in your projects folder. gene-2-kb is free.");
        assertThat(shown.get(1)).isEqualTo(true);
        assertThat(shown.get(2)).as("or on Nexus, where the new one would deploy over it")
                .isEqualTo("A knowledge base named snomedct-kb is already on Nexus. snomedct-2-kb is free.");
        assertThat(shown.get(3)).isEqualTo("Use snomedct-2-kb");
        assertThat(shown.get(4)).as("the link takes the free name").isEqualTo("snomedct-2-kb");
        assertThat(shown.get(5)).as("and the folder follows it").isEqualTo(Path.of("/home/kb/snomedct-2-kb").toAbsolutePath());
        assertThat(shown.get(6)).isEqualTo("Draft it to see what will be created.");
        assertThat(shown.get(7)).isEqualTo(false);
        assertThat(shown.get(8)).as("the offer is gone once the name is free").isEqualTo(false);
    }

    @Test
    void whatIsCheckedBecomesTheLayersKeepingTheLoadOrderOfThoseThatStay() throws Exception {
        List<List<String>> shown = onFx(() -> {
            NewKnowledgeBaseForm form = form();
            NexusCatalog.Offer snomed = offer("snomedct-kb", "20260601.2-SNAPSHOT", "unreasoned-pb", (long) (0.45 * GIB));
            NexusCatalog.Offer gene = offer("ncbi-gene-kb", "20261020.1-SNAPSHOT", "unreasoned-pb", 100_000);
            form.addLayer(gene);
            form.addLayer(snomed);
            form.copyInputs(coordinate("solor-gudid-kb", "1-SNAPSHOT"));       // SNOMED CT, then GUDID, which Nexus offers no protobuf of here
            List<String> copied = form.rows().stream().map(NewKnowledgeBaseForm.Row::name).toList();
            form.setLayers(List.of(gene, snomed));
            List<String> added = form.rows().stream().map(NewKnowledgeBaseForm.Row::name).toList();
            form.setLayers(List.of(gene));
            return List.of(copied, added, form.rows().stream().map(NewKnowledgeBaseForm.Row::name).toList(),
                    List.of(all(form, Button.class).stream().anyMatch(button -> "Choose layers…".equals(button.getText())) ? "offered" : "missing"));
        });

        assertThat(shown.getFirst()).containsExactly("SNOMED CT", "GUDID");
        assertThat(shown.get(1)).as("one that stays keeps its place, a new one loads last, and one that could not be checked stays")
                .containsExactly("SNOMED CT", "GUDID", "NCBI Gene");
        assertThat(shown.get(2)).as("one no longer checked is taken out").containsExactly("GUDID", "NCBI Gene");
        assertThat(shown.get(3)).containsExactly("offered");
    }

    private static NewKnowledgeBaseForm form() {
        NewKnowledgeBaseForm form = new NewKnowledgeBaseForm();
        form.setProjectsFolder(Path.of("/home/kb"));
        form.setCatalog(new NexusCatalog.Catalog(List.of(
                offer("snomedct-kb", "20260601.2-SNAPSHOT", "unreasoned-pb", (long) (0.45 * GIB))), Map.of(
                coordinate("snomedct-kb", "20260601.2-SNAPSHOT"), described("snomedct-kb", "20260601.2-SNAPSHOT", "SNOMED CT",
                        new KnowledgeBaseDescriptor.Input("SOURCE",
                                "org.snomed:snomedct-origin:zip:SnomedCT_InternationalRF2_20260601+1.0.0", Optional.empty(),
                                Optional.of("snomed-rf2"))),
                coordinate("gudid-kb", "20261001.2-SNAPSHOT"), described("gudid-kb", "20261001.2-SNAPSHOT", "GUDID"),
                coordinate("ncbi-gene-kb", "20261020.1-SNAPSHOT"), described("ncbi-gene-kb", "20261020.1-SNAPSHOT", "NCBI Gene"),
                coordinate("solor-gudid-kb", "1-SNAPSHOT"), described("solor-gudid-kb", "1-SNAPSHOT", "SOLOR GUDID",
                        input("network.ike.foundation:ike-starter-set:zip:unreasoned-pb:1-SNAPSHOT"),
                        input("network.ike.data:snomedct-kb:zip:unreasoned-pb:20260601.2-SNAPSHOT"),
                        input("network.ike.data:gudid-kb:zip:unreasoned-pb:20261001.2-SNAPSHOT"),
                        new KnowledgeBaseDescriptor.Input("CHANGESET", "file:edits.zip", Optional.empty(), Optional.empty())))));
        new Scene(form, 760, 900);
        layout(form);
        return form;
    }

    private static void layout(NewKnowledgeBaseForm form) {
        form.applyCss();
        form.layout();
    }

    private static NexusCatalog.Offer offer(String artifactId, String version, String classifier, long size) {
        return new NexusCatalog.Offer(coordinate(artifactId, version), classifier, size, DEPLOYED, "ike-restricted-snapshots");
    }

    private static NexusCatalog.Described described(String artifactId, String version, String displayName,
                                                    KnowledgeBaseDescriptor.Input... inputs) {
        return new NexusCatalog.Described(new KnowledgeBaseDescriptor(1, coordinate(artifactId, version), Optional.empty(),
                displayName, "spined-64", 3, DEPLOYED, "restricted", "META-INF/ike/icon.png",
                KnowledgeBaseDescriptor.Memory.NONE, Optional.empty(), List.of(inputs)), new byte[] {1});
    }

    private static KnowledgeBaseDescriptor.Input input(String reference) {
        return new KnowledgeBaseDescriptor.Input("PB", reference, Optional.empty(), Optional.empty());
    }

    private static Coordinate coordinate(String artifactId, String version) {
        return new Coordinate("network.ike.data", artifactId, version);
    }

    /** The field whose prompt, the words it shows while empty, is the one given. */
    private static TextField field(NewKnowledgeBaseForm form, String prompt) {
        return all(form, TextField.class).stream().filter(field -> prompt.equals(field.getPromptText())).findFirst()
                .orElseThrow(() -> new AssertionError("no field prompting " + prompt));
    }

    private static Button button(NewKnowledgeBaseForm form, String text) {
        return all(form, Button.class).stream().filter(button -> text.equals(button.getText())).findFirst()
                .orElseThrow(() -> new AssertionError("no button " + text));
    }

    private static Button accessible(NewKnowledgeBaseForm form, String text) {
        return all(form, Button.class).stream().filter(button -> text.equals(button.getAccessibleText())).findFirst()
                .orElseThrow(() -> new AssertionError("no button for " + text));
    }

    private static ToggleButton toggle(NewKnowledgeBaseForm form, String text) {
        return all(form, ToggleButton.class).stream().filter(toggle -> text.equals(toggle.getText())).findFirst()
                .orElseThrow(() -> new AssertionError("no choice " + text));
    }

    /** Whether a node is shown: it and everything it is inside are visible. */
    private static boolean shown(Node node) {
        for (Node at = node; at != null; at = at.getParent()) {
            if (!at.isVisible()) {
                return false;
            }
        }
        return true;
    }

    private static <T extends Node> List<T> all(Node root, Class<T> type) {
        List<T> found = new ArrayList<>();
        collect(root, type, found);
        return found;
    }

    private static <T extends Node> void collect(Node node, Class<T> type, List<T> found) {
        if (type.isInstance(node)) {
            found.add(type.cast(node));
        }
        if (node instanceof javafx.scene.control.ScrollPane scroll && scroll.getContent() != null) {
            collect(scroll.getContent(), type, found);
        } else if (node instanceof javafx.scene.Parent parent) {
            parent.getChildrenUnmodifiable().forEach(child -> collect(child, type, found));
        }
    }

    private static void write(javafx.scene.image.WritableImage image, Path png) throws java.io.IOException {
        java.awt.image.BufferedImage buffered = new java.awt.image.BufferedImage((int) image.getWidth(),
                (int) image.getHeight(), java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < buffered.getHeight(); y++) {
            for (int x = 0; x < buffered.getWidth(); x++) {
                buffered.setRGB(x, y, image.getPixelReader().getArgb(x, y));
            }
        }
        java.nio.file.Files.createDirectories(png.getParent());
        javax.imageio.ImageIO.write(buffered, "png", png.toFile());
    }

    private static <T> T onFx(Callable<T> work) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                result.complete(work.call());
            } catch (Throwable e) {
                result.completeExceptionally(e);
            }
        });
        return result.get(30, TimeUnit.SECONDS);
    }
}
