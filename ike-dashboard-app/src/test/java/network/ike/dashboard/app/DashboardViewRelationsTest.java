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
import javafx.scene.Scene;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A knowledge base's detail names what it was built from and what is built from it (decision 18), each
 * a link: to its line when it is in {@code ~/Solor}, else to the Nexus window. A protobuf the dashboard
 * downloaded is a knowledge base of its own. Drawn headless with Monocle; the assembly's detail is
 * written to {@code target/dashboard-relations.png}.
 */
class DashboardViewRelationsTest {

    private static final Instant ASSEMBLED = Instant.parse("2026-10-26T17:11:30Z");

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
    void anAssemblyLinksWhatItWasBuiltFromAndASourceWhatIncludesIt() throws Exception {
        List<List<String>> shown = onFx(() -> {
            DashboardView view = view();
            view.select("solor-gudid-kb");
            view.applyCss();
            view.layout();
            List<String> assembly = texts(view);
            write(view.getScene().snapshot(null), Path.of("target", "dashboard-relations.png"));
            link(view, "SNOMED CT").fire();
            view.applyCss();
            view.layout();
            List<String> source = texts(view);
            source.add("selected " + view.selected().map(KnowledgeBaseFamily::name).orElse("none"));
            return List.of(assembly, source);
        });

        assertThat(shown.getFirst()).as("the assembly: its inputs, here or on Nexus or neither")
                .contains("Built from", "link SNOMED CT", "link GUDID", "ike-starter-set", "20260601.2-20261026.160451-9")
                .doesNotContain("Included in");
        assertThat(shown.getFirst()).as("the knowledge base itself by groupId and artifactId")
                .anyMatch(text -> text.startsWith("network.ike.data:solor-gudid-kb  ·  1 folder"));
        assertThat(shown.getFirst()).as("beneath each name, its group and how the assembly took it")
                .contains("network.ike.foundation  ·  protobuf, unreasoned", "network.ike.data  ·  protobuf, unreasoned");
        assertThat(shown.getFirst()).as("and beneath each, the source release its own descriptor records, here or on Nexus")
                .contains("snomedct-origin", "SnomedCT_InternationalRF2_20260601+1.0.0",
                        "org.snomed  ·  release, read by snomed-rf2", "gudid-origin", "20261001",
                        "gov.fda  ·  release, read by gudid");
        assertThat(shown.getLast()).as("the source, a protobuf, which the link selected")
                .contains("selected snomedct-kb", "Only protobuf files. Komet cannot open these directly. It loads them into a knowledge base.",
                        "Included in", "link SOLOR", "link SOLOR GUDID");
    }

    @Test
    void aFolderThatRecordsOnlyItsOwnProtobufShowsWhatNexusRecordsOnceItIsTheNewestVersion() throws Exception {
        List<List<String>> shown = onFx(() -> {
            KnowledgeBase rocks = knowledgeBase("solor-gudid-kb", "1-SNAPSHOT", "SOLOR GUDID", StoreKind.ROCKS,
                    input("network.ike.data:solor-gudid-kb:zip:reasoned-pb:1-SNAPSHOT"));
            DashboardView view = new DashboardView();
            view.setKomets(List.of(new KometInstallation("Komet Desktop 26.1024.1820", "26.1024.1820",
                    Path.of("/Applications/Komet.app"), Path.of("/Applications/Komet.app/Contents/MacOS/Komet"))));
            view.setFamilies(List.of(new KnowledgeBaseFamily("solor-gudid-kb", List.of(rocks))));
            view.setCatalog(new NexusCatalog.Catalog(List.of(), Map.of(
                    coordinate("solor-gudid-kb", "1-SNAPSHOT"), described("solor-gudid-kb", "1-SNAPSHOT", "SOLOR GUDID",
                            input("network.ike.data:gudid-kb:zip:unreasoned-pb:20261001.2-SNAPSHOT")),
                    coordinate("gudid-kb", "20261001.2-SNAPSHOT"), described("gudid-kb", "20261001.2-SNAPSHOT", "GUDID",
                            GUDID_RELEASE))));
            new Scene(view, 1080, 640);
            view.select("solor-gudid-kb");
            view.applyCss();
            view.layout();
            List<String> unchecked = texts(view);
            view.setStatus("solor-gudid-kb", new BuildStatus(BuildStatus.State.CURRENT, "the newest deploy", Optional.of(
                    new SnapshotMetadata.Deploy("1-20261107.023118-4", ASSEMBLED))));
            view.applyCss();
            view.layout();
            return List.of(unchecked, texts(view));
        });

        assertThat(shown.getFirst()).as("not known to be the version Nexus describes: nothing is claimed")
                .doesNotContain("Built from");
        assertThat(shown.getLast()).as("the newest version: what Nexus records it was built from")
                .contains("Built from", "link GUDID", "20261001.2-SNAPSHOT", "network.ike.data  ·  protobuf, unreasoned",
                        "gudid-origin", "20261001", "gov.fda  ·  release, read by gudid");
    }

    @Test
    void anArtifactOfTheSameNameFromAnotherGroupIsNotLinkedToTheOneHere() throws Exception {
        List<String> shown = onFx(() -> {
            KnowledgeBase assembly = knowledgeBase("solor-gudid-kb", "1-SNAPSHOT", "SOLOR GUDID", StoreKind.SPINED_64,
                    input("gov.fda:gudid-kb:zip:unreasoned-pb:20261001.2"));
            KnowledgeBase ours = knowledgeBase("gudid-kb", "20261001.2-SNAPSHOT", "GUDID", StoreKind.CHANGE_SETS);
            DashboardView view = new DashboardView();
            view.setFamilies(List.of(new KnowledgeBaseFamily("gudid-kb", List.of(ours)),
                    new KnowledgeBaseFamily("solor-gudid-kb", List.of(assembly))));
            view.setCatalog(new NexusCatalog.Catalog(List.of(), Map.of(
                    coordinate("gudid-kb", "20261001.2-SNAPSHOT"), described("gudid-kb", "20261001.2-SNAPSHOT", "GUDID"))));
            new Scene(view, 1080, 640);
            view.select("solor-gudid-kb");
            view.applyCss();
            view.layout();
            return texts(view);
        });

        assertThat(shown).as("gov.fda's gudid-kb is not network.ike.data's, here or on Nexus")
                .contains("Built from", "gudid-kb", "20261001.2", "gov.fda  ·  protobuf, unreasoned")
                .doesNotContain("link GUDID", "link gudid-kb");
    }

    private static final KnowledgeBaseDescriptor.Input GUDID_RELEASE = new KnowledgeBaseDescriptor.Input("SOURCE",
            "gov.fda:gudid-origin:zip:20261001", Optional.empty(), Optional.of("gudid"));

    private static DashboardView view() {
        KnowledgeBase gudid = knowledgeBase("solor-gudid-kb", "1-SNAPSHOT", "SOLOR GUDID", StoreKind.SPINED_64,
                input("network.ike.foundation:ike-starter-set:zip:unreasoned-pb:1-SNAPSHOT"),
                new KnowledgeBaseDescriptor.Input("PB", "network.ike.data:snomedct-kb:zip:unreasoned-pb:20260601.2-SNAPSHOT",
                        Optional.of("20260601.2-20261026.160451-9"), Optional.empty()),
                input("network.ike.data:gudid-kb:zip:unreasoned-pb:20261001.2-SNAPSHOT"));
        KnowledgeBase snomed = knowledgeBase("snomedct-kb", "20260601.2-SNAPSHOT", "SNOMED CT", StoreKind.CHANGE_SETS,
                new KnowledgeBaseDescriptor.Input("SOURCE",
                        "org.snomed:snomedct-origin:zip:SnomedCT_InternationalRF2_20260601+1.0.0", Optional.empty(),
                        Optional.of("snomed-rf2")));
        DashboardView view = new DashboardView();
        view.setKomets(List.of(new KometInstallation("Komet Desktop 26.1024.1820", "26.1024.1820",
                Path.of("/Applications/Komet.app"), Path.of("/Applications/Komet.app/Contents/MacOS/Komet"))));
        view.setFamilies(List.of(new KnowledgeBaseFamily("snomedct-kb", List.of(snomed)),
                new KnowledgeBaseFamily("solor-gudid-kb", List.of(gudid))));
        view.setCatalog(new NexusCatalog.Catalog(List.of(), Map.of(
                coordinate("gudid-kb", "20261001.2-SNAPSHOT"), described("gudid-kb", "20261001.2-SNAPSHOT", "GUDID",
                        GUDID_RELEASE),
                coordinate("solor-kb", "1-SNAPSHOT"), described("solor-kb", "1-SNAPSHOT", "SOLOR",
                        input("network.ike.data:snomedct-kb:zip:unreasoned-pb:20260601.2-SNAPSHOT")))));
        new Scene(view, 1080, 640);
        view.applyCss();
        view.layout();
        return view;
    }

    private static KnowledgeBase knowledgeBase(String artifactId, String version, String displayName, StoreKind kind,
                                               KnowledgeBaseDescriptor.Input... inputs) {
        Coordinate coordinate = coordinate(artifactId, version);
        KnowledgeBaseDescriptor descriptor = descriptor(coordinate, displayName, inputs);
        String name = kind == StoreKind.CHANGE_SETS ? artifactId + "-unreasoned-pb.zip" : artifactId;
        return new KnowledgeBase(name, Path.of("/Solor", name), kind, 1_000_000, Optional.of(coordinate), Map.of(),
                ASSEMBLED, DescriptorCheck.met(descriptor));
    }

    private static KnowledgeBaseDescriptor descriptor(Coordinate coordinate, String displayName,
                                                      KnowledgeBaseDescriptor.Input... inputs) {
        return new KnowledgeBaseDescriptor(1, coordinate, Optional.empty(), displayName, "spined-64", 3, ASSEMBLED,
                "restricted", "META-INF/ike/icon.png", KnowledgeBaseDescriptor.Memory.NONE, Optional.of("SOLOR"),
                List.of(inputs));
    }

    private static NexusCatalog.Described described(String artifactId, String version, String displayName,
                                                    KnowledgeBaseDescriptor.Input... inputs) {
        return new NexusCatalog.Described(descriptor(coordinate(artifactId, version), displayName, inputs), new byte[] {1});
    }

    private static KnowledgeBaseDescriptor.Input input(String reference) {
        return new KnowledgeBaseDescriptor.Input("PB", reference, Optional.empty(), Optional.empty());
    }

    private static Coordinate coordinate(String artifactId, String version) {
        return new Coordinate("network.ike.data", artifactId, version);
    }

    /** Each label's text, and each link's as {@code link <text>}. */
    private static List<String> texts(DashboardView view) {
        List<String> texts = new java.util.ArrayList<>();
        texts.addAll(Words.of(view));
        view.lookupAll(".hyperlink").stream().map(Hyperlink.class::cast).map(link -> "link " + link.getText())
                .forEach(texts::add);
        return texts;
    }

    private static Hyperlink link(DashboardView view, String text) {
        return view.lookupAll(".hyperlink").stream().map(Hyperlink.class::cast).filter(link -> link.getText().equals(text))
                .findFirst().orElseThrow(() -> new AssertionError("no link " + text));
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
