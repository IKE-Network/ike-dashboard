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

import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * The Projects tab (decision 21 of the IKE Dashboard design; KEC, 2026-11-12): the projects that build
 * knowledge bases, kept apart from the knowledge bases Komet opens. It is laid out as the Knowledge Bases
 * tab is: the projects in the projects folder on the left, each by its knowledge base's name, and the
 * chosen one's detail on the right, with what it is built from, where it deploys, the state of its git
 * repository, and Build and Deploy. A project with no remote yet is given one here: the remote of the
 * project that builds the knowledge base, not of the knowledge base. Listing every project also shows
 * which names are in use. It does no IO of its own; {@link DashboardApp} reads the projects and runs
 * their builds.
 */
public final class ProjectsPane extends SplitPane {

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());
    private static final String MUTED = "-fx-text-fill: #6b6b6b;";
    private static final String ATTENTION = "-fx-text-fill: #b35900;";
    private static final String PROBLEM = "-fx-text-fill: #c62828;";

    private final ListView<KnowledgeBaseProjectFolder> list = new ListView<>();
    private final Button newKnowledgeBase = new Button("New knowledge base…");
    private final VBox detail = new VBox(10);
    private final TextArea output = new TextArea();
    private final Map<Path, RepositorySync.Status> git = new HashMap<>();
    private Path folder = Path.of(System.getProperty("user.home"), "knowledge-bases");
    private boolean gitFound = true;

    /** The project whose build Maven is running, and what it is doing. */
    private Optional<Path> working = Optional.empty();
    private String workingOn = "";
    /** How the last build of each project ended, said until the next. */
    private final Map<Path, Ended> ended = new HashMap<>();
    /** The project the person is asked to confirm a deploy of. */
    private Optional<Path> confirming = Optional.empty();
    /** The project the output shown belongs to. */
    private Optional<Path> outputOf = Optional.empty();

    /** The project a remote is being added to, and the URL typed for it. */
    private Optional<Path> addingRemote = Optional.empty();
    private final TextField remoteUrl = new TextField();
    /** What the last check of a remote showed, offered to be done while the URL stays as it was checked. */
    private Optional<RemoteDraft> remoteDraft = Optional.empty();
    private RemoteAction onRemote = (project, url, publish) -> { };

    /**
     * What a check of a remote showed.
     *
     * @param project the project's folder
     * @param url     the remote's URL, as checked
     * @param report  what adding it would do
     * @param refusal why it cannot be added as it stands, or empty
     */
    private record RemoteDraft(Path project, String url, List<String> report, Optional<String> refusal) {
    }

    /** What checking a remote, or adding it, does. */
    @FunctionalInterface
    public interface RemoteAction {

        /**
         * Checks or adds a remote.
         *
         * @param project the project
         * @param url     the remote's git URL
         * @param publish {@code false} to check what adding it would do, {@code true} to add it and push
         */
        void run(KnowledgeBaseProjectFolder project, String url, boolean publish);
    }

    private Runnable onNew = () -> { };
    private BiConsumer<KnowledgeBaseProjectFolder, String> onBuild = (project, phase) -> { };
    private Consumer<Path> onReveal = path -> { };
    private Runnable onCancel = () -> { };

    /**
     * How a build ended.
     *
     * @param text    what to say of it
     * @param problem whether it failed
     */
    private record Ended(String text, boolean problem) {
    }

    /** Builds the pane, empty until the projects are set. */
    public ProjectsPane() {
        list.setId("project-list");
        list.setCellFactory(view -> new ProjectCell());
        list.setPlaceholder(new Label("No projects yet"));
        list.getSelectionModel().selectedItemProperty().addListener((observable, was, now) -> {
            confirming = Optional.empty();
            showDetail();
        });
        remoteUrl.setPromptText("https://github.com/my-org/my-kb.git");
        HBox.setHgrow(remoteUrl, Priority.ALWAYS);
        newKnowledgeBase.setMinWidth(Region.USE_PREF_SIZE);
        newKnowledgeBase.setOnAction(event -> onNew.run());
        HBox masterBottom = new HBox(newKnowledgeBase);
        masterBottom.setPadding(new Insets(8));
        BorderPane master = new BorderPane(list);
        master.setBottom(masterBottom);
        master.setMinWidth(260);

        output.setEditable(false);
        output.setPrefRowCount(16);
        output.setStyle("-fx-font-family: Menlo, Consolas, monospace; -fx-font-size: 11px;");
        detail.setPadding(new Insets(16, 20, 20, 20));
        ScrollPane detailScroll = new ScrollPane(detail);
        detailScroll.setFitToWidth(true);
        detailScroll.setStyle("-fx-background-color: transparent; -fx-background-insets: 0;");

        getItems().setAll(master, detailScroll);
        setOrientation(Orientation.HORIZONTAL);
        setDividerPositions(0.32);
        SplitPane.setResizableWithParent(master, false);
        showDetail();
    }

    /**
     * Shows the projects in the projects folder, keeping the one chosen; the first is chosen when none was.
     *
     * @param folder   the projects folder
     * @param projects the projects in it
     * @param states   the state of each project's git repository, by its folder; absent for one with none
     * @param gitFound whether git is installed, so the states could be read
     */
    public void setProjects(Path folder, List<KnowledgeBaseProjectFolder> projects, Map<Path, RepositorySync.Status> states,
                            boolean gitFound) {
        this.folder = folder;
        this.gitFound = gitFound;
        Optional<Path> chosen = selected().map(KnowledgeBaseProjectFolder::folder);
        git.clear();
        git.putAll(states);
        list.getItems().setAll(projects);
        chosen.ifPresent(this::select);
        if (list.getSelectionModel().isEmpty() && !projects.isEmpty()) {
            list.getSelectionModel().selectFirst();
        }
        showDetail();
    }

    /**
     * Chooses a project.
     *
     * @param project its folder
     * @return whether it is listed
     */
    public boolean select(Path project) {
        Optional<KnowledgeBaseProjectFolder> found = list.getItems().stream()
                .filter(candidate -> candidate.folder().equals(project)).findFirst();
        found.ifPresent(candidate -> list.getSelectionModel().select(candidate));
        return found.isPresent();
    }

    /**
     * The project chosen.
     *
     * @return it, or empty when none is
     */
    public Optional<KnowledgeBaseProjectFolder> selected() {
        return Optional.ofNullable(list.getSelectionModel().getSelectedItem());
    }

    /**
     * The projects listed, in order.
     *
     * @return the projects
     */
    public List<KnowledgeBaseProjectFolder> projects() {
        return List.copyOf(list.getItems());
    }

    /**
     * What New knowledge base does.
     *
     * @param action the action
     */
    public void setOnNew(Runnable action) {
        onNew = action;
    }

    /**
     * What Build and Deploy do, given the project and the Maven phase, {@code verify} or {@code deploy}.
     *
     * @param action the action
     */
    public void setOnBuild(BiConsumer<KnowledgeBaseProjectFolder, String> action) {
        onBuild = action;
    }

    /**
     * What showing a project's folder does.
     *
     * @param action the action
     */
    public void setOnReveal(Consumer<Path> action) {
        onReveal = action;
    }

    /**
     * What checking a remote and adding it do.
     *
     * @param action the action
     */
    public void setOnRemote(RemoteAction action) {
        onRemote = action;
    }

    /**
     * What Cancel does while a build runs.
     *
     * @param action the action
     */
    public void setOnCancel(Runnable action) {
        onCancel = action;
    }

    /**
     * Says that Maven is running a project's build; one build runs at a time.
     *
     * @param project the project's folder
     * @param what    such as "Building gene-kb …"
     */
    public void working(Path project, String what) {
        working = Optional.of(project);
        workingOn = what;
        outputOf = Optional.of(project);
        ended.remove(project);
        confirming = Optional.empty();
        output.clear();
        list.refresh();
        showDetail();
    }

    /**
     * Shows the newest line Maven wrote.
     *
     * @param line the line
     */
    public void progress(String line) {
        if (working.isPresent()) {
            output.appendText(line + "\n");
        }
    }

    /**
     * Shows how a project's build ended; Maven's output stays to be read.
     *
     * @param project the project's folder
     * @param phase   the Maven phase that ran
     * @param outcome how it ended
     */
    public void built(Path project, String phase, KnowledgeBaseCreation.Outcome outcome) {
        working = Optional.empty();
        boolean deploy = "deploy".equals(phase);
        ended.put(project, outcome.succeeded()
                ? new Ended(deploy ? "Deployed." : "Built.", false)
                : new Ended((deploy ? "The deploy failed" : "The build failed")
                        + outcome.failure().map(reason -> ": " + reason.lines().findFirst().orElse("")).orElse(". Maven's output is below."), true));
        list.refresh();
        showDetail();
    }

    /**
     * Shows what a check of a remote found: what adding it would do, or why it cannot be added.
     *
     * @param project the project's folder
     * @param url     the remote's URL, as checked
     * @param outcome how the check ended
     */
    public void remoteDrafted(Path project, String url, KnowledgeBaseCreation.Outcome outcome) {
        working = Optional.empty();
        if (outcome.succeeded()) {
            remoteDraft = Optional.of(new RemoteDraft(project, url, outcome.report(KnowledgeBaseCreation.REMOTE_HEADING),
                    outcome.refusal()));
            ended.remove(project);
        } else {
            remoteDraft = Optional.empty();
            ended.put(project, new Ended("The remote cannot be added" + outcome.failure()
                    .map(reason -> ": " + reason.lines().findFirst().orElse("")).orElse(". Maven's output is below."), true));
        }
        list.refresh();
        showDetail();
    }

    /**
     * Shows how adding a remote ended.
     *
     * @param project the project's folder
     * @param outcome how it ended
     */
    public void remoteAdded(Path project, KnowledgeBaseCreation.Outcome outcome) {
        working = Optional.empty();
        if (outcome.succeeded()) {
            ended.put(project, new Ended("The remote is set and the project is pushed.", false));
            addingRemote = Optional.empty();
            remoteDraft = Optional.empty();
            remoteUrl.clear();
        } else {
            ended.put(project, new Ended("The remote was not added" + outcome.failure()
                    .map(reason -> ": " + reason.lines().findFirst().orElse("")).orElse(". Maven's output is below."), true));
        }
        list.refresh();
        showDetail();
    }

    /**
     * Says that Maven could not be run at all, or was stopped.
     *
     * @param project the project's folder
     * @param reason  why
     */
    public void failed(Path project, String reason) {
        working = Optional.empty();
        ended.put(project, new Ended(reason, true));
        list.refresh();
        showDetail();
    }

    private void showDetail() {
        detail.getChildren().clear();
        Optional<KnowledgeBaseProjectFolder> chosen = selected();
        if (chosen.isEmpty()) {
            SelectableText none = muted(list.getItems().isEmpty()
                    ? "No projects in " + folder + " yet. A project builds a knowledge base from protobufs on Nexus."
                    + " Choose New knowledge base to make one."
                    : "Choose a project");
            detail.getChildren().add(none);
            return;
        }
        KnowledgeBaseProjectFolder project = chosen.get();
        SelectableText title = SelectableText.of(project.title());
        title.setStyle("-fx-font-size: 20px; -fx-font-weight: bold;");
        HBox heading = new HBox(12, tile(48), title);
        heading.setAlignment(Pos.CENTER_LEFT);
        detail.getChildren().add(heading);
        detail.getChildren().add(muted(project.coordinates()));
        detail.getChildren().add(muted("Folder  " + project.folder()));
        if (!project.buildsKnowledgeBase()) {
            detail.getChildren().add(muted("This project builds no knowledge base: it is built from no protobuf layers."));
        }

        boolean busy = working.isPresent();
        boolean mine = working.filter(project.folder()::equals).isPresent();
        Button build = new Button("Build");
        build.setDefaultButton(true);
        build.setStyle("-fx-font-size: 14px; -fx-padding: 6 28 6 28;");
        build.setDisable(busy);
        build.setOnAction(event -> onBuild.accept(project, "verify"));
        Button deploy = new Button("Deploy…");
        deploy.setDisable(busy);
        deploy.setOnAction(event -> {
            confirming = Optional.of(project.folder());
            showDetail();
        });
        Button reveal = new Button("Show in " + Finder.name());
        reveal.setOnAction(event -> onReveal.accept(project.folder()));
        HBox actions = new HBox(8, build, deploy, reveal);
        actions.setAlignment(Pos.CENTER_LEFT);
        actions.setPadding(new Insets(6, 0, 0, 0));
        detail.getChildren().add(actions);
        if (confirming.filter(project.folder()::equals).isPresent() && !busy) {
            detail.getChildren().add(confirmDeploy(project));
        }
        if (mine) {
            Label running = new Label(workingOn);
            running.setStyle(ATTENTION);
            Button cancel = new Button("Cancel");
            cancel.setOnAction(event -> onCancel.run());
            HBox line = new HBox(10, running, cancel);
            line.setAlignment(Pos.CENTER_LEFT);
            detail.getChildren().add(line);
        } else if (busy) {
            detail.getChildren().add(muted("Another project is being built. One builds at a time."));
        }
        Optional.ofNullable(ended.get(project.folder())).ifPresent(end -> {
            SelectableText said = SelectableText.of(end.text());
            said.setStyle(end.problem() ? PROBLEM : "-fx-text-fill: #2e7d32;");
            detail.getChildren().add(said);
        });

        if (!project.layers().isEmpty()) {
            detail.getChildren().add(heading("Built from"));
            int position = 1;
            for (KnowledgeBaseProject.Layer layer : project.layers()) {
                detail.getChildren().add(SelectableText.of(position++ + ".  " + layer.artifactId() + "  ·  "
                        + NexusCatalog.label(layer.classifier()) + "  ·  " + layer.version()));
            }
            detail.getChildren().add(muted("Each layer stays at this version until the project is changed."));
        }
        detail.getChildren().add(heading("Deploys to"));
        detail.getChildren().add(project.deploysTo().map(repository -> SelectableText.of(repository.id() + "  ·  " + repository.url()))
                .orElseGet(() -> muted("Its POM names no repository of its own.")));
        project.collection().ifPresent(collection -> detail.getChildren().add(muted("Collection  " + collection)));
        detail.getChildren().add(heading("Repository"));
        detail.getChildren().add(repository(project));
        RepositorySync.Status state = git.get(project.folder());
        if (state != null && state.remote() == RepositorySync.Remote.NONE) {
            if (addingRemote.filter(project.folder()::equals).isPresent()) {
                detail.getChildren().add(remoteBox(project, busy));
            } else {
                Button add = new Button("Add remote…");
                add.setDisable(busy);
                add.setOnAction(event -> {
                    addingRemote = Optional.of(project.folder());
                    remoteDraft = Optional.empty();
                    showDetail();
                });
                detail.getChildren().add(add);
            }
        }
        detail.getChildren().add(heading("Last build"));
        detail.getChildren().add(project.built().map(when -> SelectableText.of("Built " + WHEN.format(when)))
                .orElseGet(() -> muted("Not built yet.")));
        if (outputOf.filter(project.folder()::equals).isPresent()) {
            detail.getChildren().add(heading(mine ? "Maven's output" : "Maven's output, from the last run"));
            detail.getChildren().add(output);
        }
    }

    /** Asks before a deploy, which publishes the knowledge base where others take it from. */
    private Node confirmDeploy(KnowledgeBaseProjectFolder project) {
        SelectableText question = SelectableText.of("Deploy " + project.coordinates()
                + project.deploysTo().map(repository -> " to " + repository.id()).orElse("") + "? It is built first."
                + " Everyone who can read that repository can then download it.");
        Button deploy = new Button("Deploy");
        deploy.setOnAction(event -> {
            confirming = Optional.empty();
            onBuild.accept(project, "deploy");
        });
        Button keep = new Button("Do not deploy");
        keep.setOnAction(event -> {
            confirming = Optional.empty();
            showDetail();
        });
        HBox buttons = new HBox(8, deploy, keep);
        VBox box = new VBox(8, question, buttons);
        box.setPadding(new Insets(10));
        box.setStyle("-fx-background-color: #fff3e0; -fx-background-radius: 6;");
        return box;
    }

    /**
     * Where a remote is typed, checked, and then added: the check shows what adding it will do, and
     * adding is offered only for the URL as it was checked.
     */
    private Node remoteBox(KnowledgeBaseProjectFolder project, boolean busy) {
        SelectableText what = muted("Create the repository on your git host first, and leave it empty. This is the remote"
                + " of the project that builds the knowledge base, not of the knowledge base.");
        Button check = new Button("Check");
        check.disableProperty().bind(remoteUrl.textProperty().map(text -> busy || text.isBlank()).orElse(true));
        check.setOnAction(event -> onRemote.run(project, remoteUrl.getText().strip(), false));
        Button close = new Button("Close");
        close.setOnAction(event -> {
            addingRemote = Optional.empty();
            remoteDraft = Optional.empty();
            showDetail();
        });
        HBox line = new HBox(8, remoteUrl, check, close);
        line.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(8, what, line);
        remoteDraft.filter(draft -> draft.project().equals(project.folder())).ifPresent(draft -> {
            SelectableText report = SelectableText.of(String.join("\n", draft.report()));
            report.setStyle("-fx-font-family: Menlo, Consolas, monospace; -fx-font-size: 11px;");
            VBox checked = new VBox(8, report);
            if (draft.refusal().isPresent()) {
                SelectableText refusal = SelectableText.of(draft.refusal().get());
                refusal.setStyle(PROBLEM);
                checked.getChildren().add(refusal);
            } else {
                Button add = new Button("Add remote and push");
                add.setDisable(busy);
                add.setOnAction(event -> onRemote.run(project, draft.url(), true));
                checked.getChildren().add(add);
            }
            // What was checked is offered only while the URL stays as it was checked.
            checked.visibleProperty().bind(remoteUrl.textProperty().map(text -> text.strip().equals(draft.url())).orElse(false));
            checked.managedProperty().bind(checked.visibleProperty());
            box.getChildren().add(checked);
        });
        box.setPadding(new Insets(10));
        box.setStyle("-fx-background-color: white; -fx-background-radius: 6; -fx-border-color: #d9d9d9; -fx-border-radius: 6;");
        return box;
    }

    /** What is known of a project's git repository, from its files alone. */
    private Node repository(KnowledgeBaseProjectFolder project) {
        RepositorySync.Status status = git.get(project.folder());
        if (status == null) {
            return muted(gitFound ? "Its folder is not a git repository." : "git is not installed, so its repository was not read.");
        }
        if (!status.problem().isEmpty() && status.remote() == RepositorySync.Remote.UNKNOWN && status.uncommitted() == 0) {
            return muted(status.problem());
        }
        List<String> facts = new ArrayList<>();
        facts.add("Branch " + status.branch());
        facts.add(status.uncommitted() == 0 ? "everything committed"
                : status.uncommitted() + (status.uncommitted() == 1 ? " file" : " files") + " not committed");
        if (status.remote() == RepositorySync.Remote.NONE) {
            facts.add("no remote yet");
        } else if (status.ahead() > 0) {
            facts.add(status.ahead() + (status.ahead() == 1 ? " commit" : " commits") + " not pushed");
        } else {
            facts.add("has a remote");
        }
        VBox lines = new VBox(4, SelectableText.of(String.join("  ·  ", facts)));
        if (status.remote() == RepositorySync.Remote.NONE) {
            lines.getChildren().add(muted("Its history is on this machine only."));
        }
        return lines;
    }

    private static SelectableText heading(String text) {
        SelectableText heading = SelectableText.of(text);
        heading.setStyle("-fx-font-weight: bold; -fx-padding: 8 0 0 0;");
        return heading;
    }

    private static SelectableText muted(String text) {
        SelectableText area = SelectableText.of(text);
        area.setStyle(MUTED);
        return area;
    }

    /** A project's tile: the same shape as a knowledge base's icon, so the lists read alike. */
    private static Node tile(double size) {
        Label letter = new Label("P");
        letter.setStyle("-fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: " + Math.round(size * 0.5) + "px;");
        StackPane tile = new StackPane(letter);
        tile.setMinSize(size, size);
        tile.setPrefSize(size, size);
        tile.setMaxSize(size, size);
        tile.setStyle("-fx-background-color: #5a6b3c; -fx-background-radius: " + Math.round(size * 0.22) + ";");
        return tile;
    }

    /** A line of the list: the tile, the knowledge base's name, and a mark for what needs attention. */
    private final class ProjectCell extends ListCell<KnowledgeBaseProjectFolder> {
        @Override
        protected void updateItem(KnowledgeBaseProjectFolder project, boolean empty) {
            super.updateItem(project, empty);
            setText(null);
            if (empty || project == null) {
                setGraphic(null);
                return;
            }
            Label name = new Label(project.artifactId());
            Label mark = new Label(mark(project));
            mark.setStyle("-fx-font-size: 11px;" + (ended.containsKey(project.folder()) && ended.get(project.folder()).problem()
                    ? PROBLEM : MUTED));
            Region gap = new Region();
            HBox.setHgrow(gap, Priority.ALWAYS);
            HBox line = new HBox(6, tile(20), name, gap, mark);
            line.setAlignment(Pos.CENTER_LEFT);
            line.setMinWidth(0);
            line.prefWidthProperty().bind(list.widthProperty().subtract(36));
            setGraphic(line);
        }
    }

    /** What sets a project apart in the list: its build under way or failed, else the state of its repository. */
    private String mark(KnowledgeBaseProjectFolder project) {
        if (working.filter(project.folder()::equals).isPresent()) {
            return "building";
        }
        Ended end = ended.get(project.folder());
        if (end != null && end.problem()) {
            return "failed";
        }
        RepositorySync.Status status = git.get(project.folder());
        if (status == null) {
            return "";
        }
        if (status.uncommitted() > 0) {
            return "not committed";
        }
        return status.remote() == RepositorySync.Remote.NONE ? "no remote" : "";
    }

    /**
     * What Maven wrote in the run shown.
     *
     * @return the output
     */
    String outputText() {
        return output.getText();
    }
}
