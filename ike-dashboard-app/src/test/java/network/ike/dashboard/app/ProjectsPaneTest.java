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
import javafx.scene.control.ScrollPane;
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
 * The Projects tab (decision 21): the projects that build knowledge bases, apart from the knowledge bases
 * Komet opens, each with what it is built from, where it deploys, its repository's state, and Build and
 * Deploy. Drawn headless with Monocle; the tab is written to {@code target/dashboard-projects.png}.
 */
class ProjectsPaneTest {

    private static final Path PROJECTS = Path.of("/home/knowledge-bases");
    private static final Instant BUILT = Instant.parse("2026-11-12T14:36:44Z");

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
    void eachProjectIsListedAndTheChosenOneSaysWhatItIsBuiltFromAndWhereItStands() throws Exception {
        List<List<String>> shown = onFx(() -> {
            DashboardView view = new DashboardView();
            new Scene(view, 1080, 640);
            view.showProjects();
            layout(view);
            List<String> empty = Words.of(view.projects());
            setProjects(view.projects());
            layout(view);
            List<String> first = Words.of(view.projects());
            write(view.getScene().snapshot(null), Path.of("target", "dashboard-projects.png"));
            view.projects().select(PROJECTS.resolve("notes-kb"));
            layout(view);
            List<String> second = Words.of(view.projects());
            return List.of(empty, first, second, List.of(String.valueOf(view.showingProjects()),
                    String.valueOf(all(view, ToggleButton.class).stream().map(ToggleButton::getText).toList())));
        });

        assertThat(shown.getFirst()).as("before any project is made, what a project is and how to make one")
                .contains("No projects yet", "No projects in " + System.getProperty("user.home") + "/knowledge-bases yet."
                        + " A project builds a knowledge base from protobufs on Nexus. Choose New knowledge base to make one.");
        assertThat(shown.get(1)).as("the list: each project by its name, marked for what its repository lacks")
                .containsSubsequence("gene-kb", "no remote", "notes-kb", "not committed");
        assertThat(shown.get(1)).as("the first project's detail")
                .contains("Gene Knowledge Base", "network.ike.data:gene-kb:1-SNAPSHOT", "Folder  /home/knowledge-bases/gene-kb",
                        "Built from", "1.  ike-starter-set  ·  protobuf, unreasoned  ·  1-20261107.045844-2",
                        "2.  ncbi-gene-kb  ·  protobuf, unreasoned  ·  20261020.1-20261111.021538-1",
                        "Each layer stays at this version until the project is changed.",
                        "Deploys to", "ike-restricted  ·  https://nexus.example/snapshots/", "Collection  TRY",
                        "Repository", "Branch main  ·  everything committed  ·  no remote yet",
                        "Its history is on this machine only.", "Last build")
                .anyMatch(text -> text.startsWith("Built 2026-11-12"));
        assertThat(shown.get(2)).as("another project: uncommitted work, a remote, and no build yet")
                .contains("notes-kb", "Branch main  ·  2 files not committed  ·  has a remote", "Not built yet.",
                        "Its POM names no repository of its own.",
                        "This project builds no knowledge base: it is built from no protobuf layers.");
        assertThat(shown.get(3)).as("Projects is a tab of its own, beside Knowledge Bases")
                .containsExactly("true", "[Knowledge Bases, Projects, Komet, Notices]");
    }

    @Test
    void buildRunsAtOnceAndDeployAsksFirst() throws Exception {
        List<Object> shown = onFx(() -> {
            ProjectsPane pane = new ProjectsPane();
            new Scene(pane, 1080, 640);
            setProjects(pane);
            layout(pane);                                // the split pane has its content once it is laid out
            List<String> asked = new ArrayList<>();
            pane.setOnBuild((project, phase) -> asked.add(phase + " " + project.artifactId()));
            pane.setOnNew(() -> asked.add("new"));
            button(pane, "New knowledge base…").fire();
            button(pane, "Build").fire();
            button(pane, "Deploy…").fire();
            layout(pane);
            List<String> asking = Words.of(pane);
            List<String> beforeConfirming = List.copyOf(asked);
            button(pane, "Do not deploy").fire();
            layout(pane);
            boolean gone = all(pane, Button.class).stream().noneMatch(button -> "Deploy".equals(button.getText()));
            button(pane, "Deploy…").fire();
            layout(pane);
            button(pane, "Deploy").fire();
            return List.of(beforeConfirming, asking, gone, asked);
        });

        assertThat(shown.getFirst()).as("Build runs the project's build; Deploy… has run nothing yet")
                .isEqualTo(List.of("new", "verify gene-kb"));
        assertThat(words(shown.get(1))).as("what a deploy does, said before it is done")
                .contains("Deploy network.ike.data:gene-kb:1-SNAPSHOT to ike-restricted? It is built first."
                        + " Everyone who can read that repository can then download it.");
        assertThat(shown.get(2)).as("Do not deploy takes the question away").isEqualTo(true);
        assertThat(shown.get(3)).as("only the confirmation deploys").isEqualTo(List.of("new", "verify gene-kb", "deploy gene-kb"));
    }

    @Test
    void aBuildIsShownWhileItRunsAndHowItEndedStays() throws Exception {
        List<Object> shown = onFx(() -> {
            ProjectsPane pane = new ProjectsPane();
            new Scene(pane, 1080, 640);
            setProjects(pane);
            layout(pane);                                // the split pane has its content once it is laid out
            Path gene = PROJECTS.resolve("gene-kb");
            pane.working(gene, "Building gene-kb …");
            pane.progress("[INFO] Loaded 22985 entities");
            layout(pane);
            List<String> running = Words.of(pane);
            boolean waiting = button(pane, "Build").isDisable() && button(pane, "Deploy…").isDisable();
            String output = pane.outputText();
            pane.select(PROJECTS.resolve("notes-kb"));
            layout(pane);
            List<String> other = Words.of(pane);
            pane.built(gene, "verify", new KnowledgeBaseCreation.Outcome(1, List.of(
                    "[ERROR] Failed to execute goal a:b:1:kb-assemble (assemble-knowledge-base) on project gene-kb: The reasoner ran out of memory",
                    "[ERROR] ")));
            pane.select(gene);
            layout(pane);
            List<String> failed = Words.of(pane);
            pane.built(gene, "deploy", new KnowledgeBaseCreation.Outcome(0, List.of("[INFO] BUILD SUCCESS")));
            layout(pane);
            return List.of(running, waiting, output, other, failed, Words.of(pane));
        });

        assertThat(words(shown.getFirst())).as("the build under way, in the list and in the detail")
                .contains("building", "Building gene-kb …", "Maven's output");
        assertThat(shown.get(1)).as("no second build is started meanwhile").isEqualTo(true);
        assertThat(shown.get(2)).isEqualTo("[INFO] Loaded 22985 entities\n");
        assertThat(words(shown.get(3))).as("another project waits its turn")
                .contains("Another project is being built. One builds at a time.");
        assertThat(words(shown.get(4))).as("a failure, in Maven's words, with its output kept")
                .contains("failed", "The build failed: The reasoner ran out of memory", "Maven's output, from the last run");
        assertThat(words(shown.get(5))).contains("Deployed.").doesNotContain("failed");
    }

    @SuppressWarnings("unchecked")
    private static List<String> words(Object shown) {
        return (List<String>) shown;
    }

    private static void setProjects(ProjectsPane pane) {
        KnowledgeBaseProjectFolder gene = new KnowledgeBaseProjectFolder(PROJECTS.resolve("gene-kb"), "gene-kb",
                Optional.of("network.ike.data"), Optional.of("1-SNAPSHOT"), Optional.of("Gene Knowledge Base"), Optional.of("TRY"),
                List.of(KnowledgeBaseProject.Layer.parse("network.ike.foundation:ike-starter-set:zip:unreasoned-pb:1-20261107.045844-2"),
                        KnowledgeBaseProject.Layer.parse("network.ike.data:ncbi-gene-kb:zip:unreasoned-pb:20261020.1-20261111.021538-1")),
                Optional.of(new KnowledgeBaseProjectFolder.Repository("ike-restricted", "https://nexus.example/snapshots/")),
                Optional.of(BUILT));
        KnowledgeBaseProjectFolder notes = new KnowledgeBaseProjectFolder(PROJECTS.resolve("notes-kb"), "notes-kb",
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), List.of(), Optional.empty(), Optional.empty());
        pane.setProjects(PROJECTS, List.of(gene, notes), Map.of(
                gene.folder(), status(gene.folder(), 0, RepositorySync.Remote.NONE),
                notes.folder(), status(notes.folder(), 2, RepositorySync.Remote.UNCHECKED)), true);
    }

    private static RepositorySync.Status status(Path folder, int uncommitted, RepositorySync.Remote remote) {
        return new RepositorySync.Status(new RepositorySync.Repository(folder, "project"), "main", uncommitted, 0, remote, 0,
                Optional.empty(), "");
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
