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
                .contains("No projects yet", "No projects in " + Path.of(System.getProperty("user.home"), "knowledge-bases") + " yet."
                        + " A project builds a knowledge base from protobufs on Nexus. Choose New knowledge base to make one.");
        assertThat(shown.get(1)).as("the list: each project by its name, marked for what its repository lacks")
                .containsSubsequence("gene-kb", "no remote", "notes-kb", "not committed");
        assertThat(shown.get(1)).as("the first project's detail")
                .contains("Gene Knowledge Base", "network.ike.data:gene-kb:1-SNAPSHOT", "Folder  " + PROJECTS.resolve("gene-kb"),
                        "Built from", "1.  ike-starter-set  ·  protobuf, unreasoned  ·  1-20261107.045844-2",
                        "2.  ncbi-gene-kb  ·  protobuf, unreasoned  ·  20261020.1-20261111.021538-1",
                        "Each source stays at this version until the project is changed.",
                        "Deploys to", "ike-restricted  ·  https://nexus.example/snapshots/", "Collection  TRY",
                        "Repository", "Branch main  ·  everything committed  ·  no remote yet",
                        "Its history is on this machine only.", "Last build")
                .anyMatch(text -> text.startsWith("Built 2026-11-12"));
        assertThat(shown.get(2)).as("another project: uncommitted work, a remote, and no build yet")
                .contains("notes-kb", "Branch main  ·  2 files not committed  ·  has a remote", "Not built yet.",
                        "Its POM names no repository of its own.",
                        "This project builds no knowledge base: it is built from no knowledge sources.");
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

    @Test
    void aProjectWithNoRemoteIsGivenOneAfterACheckOfWhatThatWillDo() throws Exception {
        List<Object> shown = onFx(() -> {
            ProjectsPane pane = new ProjectsPane();
            new Scene(pane, 1080, 640);
            setProjects(pane);
            layout(pane);
            Path gene = PROJECTS.resolve("gene-kb");
            List<String> asked = new ArrayList<>();
            pane.setOnRemote((project, url, publish) -> asked.add((publish ? "add " : "check ") + project.artifactId() + " " + url));
            button(pane, "Add remote…").fire();
            layout(pane);
            List<String> asking = Words.of(pane);
            boolean nothingToCheck = button(pane, "Check").isDisable();
            TextField url = all(pane, TextField.class).getFirst();
            url.setText(" https://github.com/my-org/gene-kb.git ");
            button(pane, "Check").fire();
            pane.working(gene, "Asking the remote what it has …");
            pane.remoteDrafted(gene, "https://github.com/my-org/gene-kb.git", new KnowledgeBaseCreation.Outcome(0, List.of(
                    "[INFO] Add remote: gene-kb",
                    "[INFO]   Remote:       https://github.com/my-org/gene-kb.git  (could not be read)",
                    "[WARNING]   The remote could not be read: fatal: repository not found. Publish will refuse.",
                    "[INFO] Draft only — run ike:kb-remote-publish with the same inputs to do it.")));
            layout(pane);
            List<String> refused = Words.of(pane);
            boolean notOffered = all(pane, Button.class).stream().noneMatch(button -> "Add remote and push".equals(button.getText()));
            pane.remoteDrafted(gene, "https://github.com/my-org/gene-kb.git", new KnowledgeBaseCreation.Outcome(0, List.of(
                    "[INFO] Add remote: gene-kb",
                    "[INFO]   Remote:       https://github.com/my-org/gene-kb.git  (empty)",
                    "[INFO]   origin:       added",
                    "[INFO] Draft only — run ike:kb-remote-publish with the same inputs to do it.")));
            layout(pane);
            List<String> checked = Words.of(pane);
            url.setText("https://github.com/my-org/another.git");
            layout(pane);
            boolean hiddenAfterEdit = !shown(button(pane, "Add remote and push"));
            url.setText("https://github.com/my-org/gene-kb.git");
            layout(pane);
            button(pane, "Add remote and push").fire();
            pane.remoteAdded(gene, new KnowledgeBaseCreation.Outcome(0, List.of("[INFO] BUILD SUCCESS")));
            layout(pane);
            pane.select(PROJECTS.resolve("notes-kb"));
            layout(pane);
            boolean noneForOneWithARemote = all(pane, Button.class).stream().noneMatch(button -> "Add remote…".equals(button.getText()));
            pane.select(gene);
            layout(pane);
            return List.of(asking, nothingToCheck, refused, notOffered, checked, hiddenAfterEdit, asked, Words.of(pane),
                    noneForOneWithARemote);
        });

        assertThat(words(shown.getFirst())).as("what the remote is, said before a URL is typed")
                .contains("Create the repository on your git host first, and leave it empty. This is the remote of the project"
                        + " that builds the knowledge base, not of the knowledge base.");
        assertThat(shown.get(1)).as("nothing to check until a URL is typed").isEqualTo(true);
        assertThat(words(shown.get(2))).as("a remote that cannot be read: the reason, and no offer to add it")
                .contains("Add remote: gene-kb\n  Remote:       https://github.com/my-org/gene-kb.git  (could not be read)",
                        "The remote could not be read: fatal: repository not found.");
        assertThat(shown.get(3)).isEqualTo(true);
        assertThat(words(shown.get(4))).as("a remote that can be added: what adding it will do")
                .contains("Add remote: gene-kb\n  Remote:       https://github.com/my-org/gene-kb.git  (empty)\n  origin:       added");
        assertThat(shown.get(5)).as("adding is offered only for the URL as it was checked").isEqualTo(true);
        assertThat(shown.get(6)).as("the check, then the adding, each with the URL trimmed")
                .isEqualTo(List.of("check gene-kb https://github.com/my-org/gene-kb.git", "add gene-kb https://github.com/my-org/gene-kb.git"));
        assertThat(words(shown.get(7))).contains("The remote is set and the project is pushed.");
        assertThat(shown.get(8)).as("a project that has a remote is not offered another").isEqualTo(true);
    }

    @Test
    void aWorkingSetIsListedWithItsMembersAndAProjectJoinsOrLeavesFromItsOwnPage() throws Exception {
        List<Object> shown = onFx(() -> {
            ProjectsPane pane = new ProjectsPane();
            new Scene(pane, 1080, 640);
            KnowledgeBaseProjectFolder member = project(PROJECTS.resolve("gene-ws").resolve("source-kb"), "source-kb");
            KnowledgeBaseProjectFolder workingSet = new KnowledgeBaseProjectFolder(PROJECTS.resolve("gene-ws"), "gene-ws",
                    Optional.of("network.ike.data"), Optional.of("1-SNAPSHOT"), Optional.empty(), Optional.empty(), List.of(),
                    Optional.empty(), Optional.empty(), true, List.of(member));
            KnowledgeBaseProjectFolder alone = project(PROJECTS.resolve("gene-kb"), "gene-kb");
            pane.setProjects(PROJECTS, List.of(alone, workingSet), Map.of(), true);
            layout(pane);
            List<String> asked = new ArrayList<>();
            pane.setOnMember((project, set, add) -> asked.add((add ? "add " : "remove ") + project.artifactId() + " " + set.artifactId()));
            List<String> listed = pane.projects().stream().map(KnowledgeBaseProjectFolder::artifactId).toList();
            List<String> onItsOwn = Words.of(pane);
            button(pane, "Add to working set").fire();
            pane.select(workingSet.folder());
            layout(pane);
            List<String> set = Words.of(pane);
            boolean noDeploy = all(pane, Button.class).stream().noneMatch(button -> "Deploy…".equals(button.getText()));
            pane.select(member.folder());
            layout(pane);
            List<String> inside = Words.of(pane);
            button(pane, "Remove from working set").fire();
            pane.finished(member.folder(), "Added to gene-ws.", "source-kb was not added to gene-ws",
                    new KnowledgeBaseCreation.Outcome(0, List.of("[INFO] BUILD SUCCESS")));
            layout(pane);
            return List.of(listed, onItsOwn, set, noDeploy, inside, asked, Words.of(pane));
        });

        assertThat(shown.getFirst()).as("a working set, then its members beneath it").isEqualTo(List.of("gene-kb", "gene-ws", "source-kb"));
        assertThat(words(shown.get(1))).as("a project on its own can join a working set")
                .contains("Working set", "On its own.", "A working set builds several projects together, in order. This project's"
                        + " folder moves into the working set's folder. It still builds on its own there.");
        assertThat(words(shown.get(2))).as("the working set: what it is, and its members in build order")
                .contains("A working set. It builds its members together, in the order listed.", "Members", "1.  source-kb",
                        "Deploy each member from its own page.")
                .doesNotContain("Deploys to", "Last build");
        assertThat(shown.get(3)).as("a working set is built, not deployed").isEqualTo(true);
        assertThat(words(shown.get(4))).as("a member says which working set it is in, and how it leaves")
                .contains("A member of gene-ws.", "Its folder moves back to " + PROJECTS + ", where it is a project on its own again.");
        assertThat(shown.get(5)).isEqualTo(List.of("add gene-kb gene-ws", "remove source-kb gene-ws"));
        assertThat(words(shown.get(6))).contains("Added to gene-ws.");
    }

    @Test
    void aNewWorkingSetIsNamedBeforeItIsMadeAndANameInUseIsRefused() throws Exception {
        List<Object> shown = onFx(() -> {
            ProjectsPane pane = new ProjectsPane();
            new Scene(pane, 1080, 640);
            setProjects(pane);
            pane.setNames(new KnowledgeBaseProject.Names(java.util.Set.of("gene-kb", "notes-kb", "gene-ws"), java.util.Set.of(),
                    java.util.Set.of(), java.util.Set.of()));
            layout(pane);
            List<String> asked = new ArrayList<>();
            pane.setOnNewWorkingSet((name, groupId) -> asked.add(name + " in " + groupId));
            button(pane, "New working set…").fire();
            layout(pane);
            List<String> empty = Words.of(pane);
            boolean nothingYet = button(pane, "Create working set").isDisable();
            TextField name = all(pane, TextField.class).stream().filter(field -> "my-ws".equals(field.getPromptText())).findFirst().orElseThrow();
            name.setText("Gene WS");
            layout(pane);
            List<String> malformed = Words.of(pane);
            name.setText("gene-ws");
            layout(pane);
            List<String> taken = Words.of(pane);
            boolean refused = button(pane, "Create working set").isDisable();
            name.setText(" solor-ws ");
            layout(pane);
            boolean offered = !button(pane, "Create working set").isDisable();
            button(pane, "Create working set").fire();
            button(pane, "Close").fire();
            layout(pane);
            return List.of(empty, nothingYet, malformed, taken, refused, offered, asked,
                    pane.selected().map(KnowledgeBaseProjectFolder::artifactId).orElse("none"));
        });

        assertThat(words(shown.getFirst())).as("what a working set is, before a name is typed")
                .contains("New working set", "Names end in -ws by custom, such as solor-ws.")
                .anyMatch(text -> text.startsWith("A working set builds several projects together, in order:"));
        assertThat(shown.get(1)).isEqualTo(true);
        assertThat(words(shown.get(2))).contains("The name is lower-case letters and digits joined by hyphens, such as my-kb.");
        assertThat(words(shown.get(3))).contains("A project named gene-ws is already in your projects folder. gene-2-ws is free.");
        assertThat(shown.get(4)).isEqualTo(true);
        assertThat(shown.get(5)).as("a new name can be made").isEqualTo(true);
        assertThat(shown.get(6)).as("with its name trimmed, in the group shown").isEqualTo(List.of("solor-ws in network.ike.data"));
        assertThat(shown.get(7)).as("Close returns to the projects").isEqualTo("gene-kb");
    }

    private static KnowledgeBaseProjectFolder project(Path folder, String artifactId) {
        return new KnowledgeBaseProjectFolder(folder, artifactId, Optional.of("network.ike.data"), Optional.of("1-SNAPSHOT"),
                Optional.empty(), Optional.empty(),
                List.of(KnowledgeBaseProject.KnowledgeSource.parse("network.ike.data:ncbi-gene-kb:zip:unreasoned-pb:20261020.1-20261111.021538-1")),
                Optional.empty(), Optional.empty());
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

    @SuppressWarnings("unchecked")
    private static List<String> words(Object shown) {
        return (List<String>) shown;
    }

    private static void setProjects(ProjectsPane pane) {
        KnowledgeBaseProjectFolder gene = new KnowledgeBaseProjectFolder(PROJECTS.resolve("gene-kb"), "gene-kb",
                Optional.of("network.ike.data"), Optional.of("1-SNAPSHOT"), Optional.of("Gene Knowledge Base"), Optional.of("TRY"),
                List.of(KnowledgeBaseProject.KnowledgeSource.parse("network.ike.foundation:ike-starter-set:zip:unreasoned-pb:1-20261107.045844-2"),
                        KnowledgeBaseProject.KnowledgeSource.parse("network.ike.data:ncbi-gene-kb:zip:unreasoned-pb:20261020.1-20261111.021538-1")),
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
