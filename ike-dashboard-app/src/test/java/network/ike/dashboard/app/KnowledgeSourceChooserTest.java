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
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
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
 * The knowledge sources of a new knowledge base are chosen with checkboxes, each protobuf with what it is under its
 * name (IKE-Network/ike-issues#1370): a source or a whole knowledge base, its coordinates, version, size
 * and deploy date, and what it was built from. Drawn headless with Monocle; the chooser is written to
 * {@code target/dashboard-knowledge source-chooser.png}.
 */
class KnowledgeSourceChooserTest {

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
    void eachProtobufSaysWhatItIsAndTheTwoKindsAreChosenApart() throws Exception {
        List<Object> shown = onFx(() -> {
            KnowledgeSourceChooser chooser = new KnowledgeSourceChooser(catalog(), List.of(KnowledgeBaseProject.KnowledgeSource.parse(
                    "network.ike.data:snomedct-kb:zip:unreasoned-pb:20260601.2-SNAPSHOT")));
            new Scene(chooser, 720, 760);
            layout(chooser);
            List<NexusCatalog.Offer> done = new ArrayList<>();
            chooser.setOnDone(done::addAll);
            List<String> first = words(chooser);
            boolean wholeSetAside = chooser.box("solor-kb").orElseThrow().isDisable();
            chooser.box("gudid-kb").orElseThrow().setSelected(true);
            layout(chooser);
            write(chooser.getScene().snapshot(null), Path.of("target", "dashboard-knowledge source-chooser.png"));
            String two = summary(chooser);
            chooser.box("gudid-kb").orElseThrow().setSelected(false);
            chooser.box("snomedct-kb").orElseThrow().setSelected(false);
            layout(chooser);
            boolean offeredAgain = !chooser.box("solor-kb").orElseThrow().isDisable();
            chooser.box("solor-kb").orElseThrow().setSelected(true);
            layout(chooser);
            List<String> whole = words(chooser);
            boolean sourcesSetAside = chooser.box("gudid-kb").orElseThrow().isDisable();
            all(chooser, TextField.class).getFirst().setText("GUDID-origin");
            layout(chooser);
            List<String> searched = words(chooser);
            button(chooser, "Use these knowledge sources").fire();
            return List.of(first, wholeSetAside, two, offeredAgain, whole, sourcesSetAside, searched,
                    done.stream().map(offer -> offer.coordinate().artifactId()).toList());
        });

        assertThat(texts(shown.getFirst())).as("sources first, each with what it is, its facts, and what it was built from")
                .containsSubsequence("Single sources",
                        "Each is one terminology or data set. Choose any number. The IKE Starter Set is always included first.",
                        "GUDID (Tinkar)",
                        "One source, read from its release. Combine it with other sources. The reasoner runs when your knowledge base is built.",
                        "network.ike.data:gudid-kb  ·  version 20261001.2-SNAPSHOT  ·  deployed 2026-11-10  ·  2.74 GiB  ·  collection SOLOR  ·  restricted",
                        "Read from gudid-origin 20261001 by the gudid importer.",
                        "SNOMED CT International (Tinkar)");
        assertThat(texts(shown.getFirst())).as("then the whole knowledge bases, by the names of what they contain")
                .containsSubsequence("Whole knowledge bases",
                        "Not offered while a source is chosen: the two cannot be combined yet.",
                        "SOLOR Knowledge Base",
                        "A whole knowledge base, already run through the reasoner. Use it as the base, and add your own edits.",
                        "Contains SNOMED CT International (Tinkar), GUDID (Tinkar).");
        assertThat(shown.get(1)).as("the knowledge source already chosen is checked, so a whole knowledge base is set aside").isEqualTo(true);
        assertThat(shown.get(2)).as("what is chosen, and how much it comes to").isEqualTo("2 chosen  ·  3.19 GiB");
        assertThat(shown.get(3)).as("with no source checked, a whole knowledge base is offered again").isEqualTo(true);
        assertThat(texts(shown.get(4))).contains("Not offered while a whole knowledge base is chosen: the two cannot be combined yet.",
                "Each already contains its sources and the IKE Starter Set.");
        assertThat(shown.get(5)).isEqualTo(true);
        assertThat(texts(shown.get(6))).as("a search matches the release a protobuf was read from, not only its name")
                .contains("GUDID (Tinkar)").doesNotContain("SNOMED CT International (Tinkar)", "Whole knowledge bases");
        assertThat(shown.get(7)).as("Use these knowledge sources gives what is checked").isEqualTo(List.of("solor-kb"));
    }

    private static NexusCatalog.Catalog catalog() {
        return new NexusCatalog.Catalog(List.of(
                offer("gudid-kb", "20261001.2-SNAPSHOT", "unreasoned-pb", (long) (2.74 * GIB)),
                offer("snomedct-kb", "20260601.2-SNAPSHOT", "unreasoned-pb", (long) (0.45 * GIB)),
                offer("solor-kb", "1-SNAPSHOT", "reasoned-pb", (long) (0.7 * GIB)),
                offer("solor-kb", "1-SNAPSHOT", "rkb", 2 * GIB)), Map.of(
                coordinate("gudid-kb", "20261001.2-SNAPSHOT"), described("gudid-kb", "20261001.2-SNAPSHOT", "GUDID (Tinkar)",
                        new KnowledgeBaseDescriptor.Input("SOURCE", "gov.fda:gudid-origin:zip:20261001", Optional.empty(), Optional.of("gudid"))),
                coordinate("snomedct-kb", "20260601.2-SNAPSHOT"), described("snomedct-kb", "20260601.2-SNAPSHOT",
                        "SNOMED CT International (Tinkar)"),
                coordinate("solor-kb", "1-SNAPSHOT"), described("solor-kb", "1-SNAPSHOT", "SOLOR Knowledge Base",
                        input("network.ike.data:snomedct-kb:zip:unreasoned-pb:20260601.2-SNAPSHOT"),
                        input("network.ike.data:gudid-kb:zip:unreasoned-pb:20261001.2-SNAPSHOT"))));
    }

    private static NexusCatalog.Offer offer(String artifactId, String version, String classifier, long size) {
        return new NexusCatalog.Offer(coordinate(artifactId, version), classifier, size, DEPLOYED, "ike-restricted-snapshots");
    }

    private static NexusCatalog.Described described(String artifactId, String version, String displayName,
                                                    KnowledgeBaseDescriptor.Input... inputs) {
        return new NexusCatalog.Described(new KnowledgeBaseDescriptor(1, coordinate(artifactId, version), Optional.empty(),
                displayName, "spined-64", 3, DEPLOYED, "restricted", "META-INF/ike/icon.png",
                KnowledgeBaseDescriptor.Memory.NONE, Optional.of("SOLOR"), List.of(inputs)), new byte[] {1});
    }

    private static KnowledgeBaseDescriptor.Input input(String reference) {
        return new KnowledgeBaseDescriptor.Input("PB", reference, Optional.empty(), Optional.empty());
    }

    private static Coordinate coordinate(String artifactId, String version) {
        return new Coordinate("network.ike.data", artifactId, version);
    }

    /** The words of every label shown, in order. */
    private static List<String> words(Node root) {
        return all(root, Label.class).stream().filter(label -> !(label.getParent() instanceof CheckBox)).map(Label::getText).toList();
    }

    private static String summary(Node root) {
        return words(root).stream().filter(text -> text.contains(" chosen  ·  ")).findFirst().orElse("");
    }

    @SuppressWarnings("unchecked")
    private static List<String> texts(Object shown) {
        return (List<String>) shown;
    }

    private static void layout(Parent root) {
        root.applyCss();
        root.layout();
    }

    private static Button button(Node root, String text) {
        return all(root, Button.class).stream().filter(button -> text.equals(button.getText())).findFirst()
                .orElseThrow(() -> new AssertionError("no button " + text));
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
        if (node instanceof ScrollPane scroll && scroll.getContent() != null) {
            collect(scroll.getContent(), type, found);
        } else if (node instanceof Parent parent) {
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
