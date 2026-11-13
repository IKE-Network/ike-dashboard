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
import javafx.scene.control.ChoiceBox;
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
 * which names are in use. A working set is listed with its members beneath it: it builds several projects
 * together, in order, and a project joins one only once it builds on its own. It does no IO of its own; {@link DashboardApp} reads the projects and runs
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

    /** The projects and working sets in the projects folder, without the members listed beneath them. */
    private List<KnowledgeBaseProjectFolder> top = List.of();
    private final Button newWorkingSet = new Button("New working set…");
    /** Whether the detail shows where a new working set is named, in place of a project. */
    private boolean namingWorkingSet;
    private final TextField workingSetName = new TextField();
    private final TextField workingSetGroup = new TextField(KnowledgeBaseProject.DEFAULT_GROUP);
    private KnowledgeBaseProject.Names names = KnowledgeBaseProject.Names.NONE;
    private BiConsumer<String, String> onNewWorkingSet = (name, groupId) -> { };
    private MemberAction onMember = (project, workingSet, add) -> { };

    /** What adding a project to a working set, or removing a member from one, does. */
    @FunctionalInterface
    public interface MemberAction {

        /**
         * Adds or removes a member.
         *
         * @param project    the project
         * @param workingSet the working set
         * @param add        {@code true} to add the project, {@code false} to remove the member
         */
        void run(KnowledgeBaseProjectFolder project, KnowledgeBaseProjectFolder workingSet, boolean add);
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
            if (now != null) {
                namingWorkingSet = false;
            }
            showDetail();
        });
        workingSetName.setPromptText("my-ws");
        workingSetName.textProperty().addListener((observable, was, now) -> {
            if (namingWorkingSet) {
                showWorkingSetProblem();
            }
        });
        newWorkingSet.setMinWidth(Region.USE_PREF_SIZE);
        newWorkingSet.setOnAction(event -> {
            namingWorkingSet = true;
            list.getSelectionModel().clearSelection();
            showDetail();
        });
        remoteUrl.setPromptText("https://github.com/my-org/my-kb.git");
        HBox.setHgrow(remoteUrl, Priority.ALWAYS);
        newKnowledgeBase.setMinWidth(Region.USE_PREF_SIZE);
        newKnowledgeBase.setOnAction(event -> onNew.run());
        HBox masterBottom = new HBox(8, newKnowledgeBase, newWorkingSet);
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
        top = List.copyOf(projects);
        boolean naming = namingWorkingSet;
        // Each working set, then its members beneath it.
        List<KnowledgeBaseProjectFolder> listed = new ArrayList<>();
        for (KnowledgeBaseProjectFolder project : projects) {
            listed.add(project);
            listed.addAll(project.members());
        }
        list.getItems().setAll(listed);
        chosen.ifPresent(this::select);
        if (list.getSelectionModel().isEmpty() && !listed.isEmpty() && !naming) {
            list.getSelectionModel().selectFirst();
        }
        namingWorkingSet = naming && list.getSelectionModel().isEmpty();
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
     * The names already in use, which a new working set must not repeat.
     *
     * @param names the names in use
     */
    public void setNames(KnowledgeBaseProject.Names names) {
        this.names = names;
    }

    /**
     * What making a working set does, given its name and its group.
     *
     * @param action the action
     */
    public void setOnNewWorkingSet(BiConsumer<String, String> action) {
        onNewWorkingSet = action;
    }

    /**
     * What adding a project to a working set, and removing a member from one, do.
     *
     * @param action the action
     */
    public void setOnMember(MemberAction action) {
        onMember = action;
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
     * Shows how a run of Maven that changed a working set ended: one made, a project added, or a member
     * removed. What is said stays with the folder the work ended in.
     *
     * @param subject the folder said of: the working set made, or the project where it now is
     * @param done    what to say when it succeeded
     * @param failed  what to say when it did not, before Maven's reason
     * @param outcome how it ended
     */
    public void finished(Path subject, String done, String failed, KnowledgeBaseCreation.Outcome outcome) {
        working = Optional.empty();
        outputOf = Optional.of(subject);
        ended.put(subject, outcome.succeeded() ? new Ended(done, false)
                : new Ended(failed + outcome.failure().map(reason -> ": " + reason.lines().findFirst().orElse(""))
                        .orElse(". Maven's output is below."), true));
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
        if (namingWorkingSet) {
            showWorkingSetForm();
            return;
        }
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
        HBox heading = new HBox(12, tile(project, 48), title);
        heading.setAlignment(Pos.CENTER_LEFT);
        detail.getChildren().add(heading);
        detail.getChildren().add(muted(project.coordinates()));
        detail.getChildren().add(muted("Folder  " + project.folder()));
        Optional<KnowledgeBaseProjectFolder> memberOf = project.workingSetAmong(top);
        if (project.workingSet()) {
            detail.getChildren().add(muted("A working set. It builds its members together, in the order listed."));
        } else if (!project.buildsKnowledgeBase()) {
            detail.getChildren().add(muted("This project builds no knowledge base: it is built from no knowledge sources."));
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
        if (project.workingSet()) {
            // A working set's own POM names no repository: each member is deployed from its own page.
            actions.getChildren().remove(deploy);
        }
        actions.setAlignment(Pos.CENTER_LEFT);
        actions.setPadding(new Insets(6, 0, 0, 0));
        detail.getChildren().add(actions);
        if (confirming.filter(project.folder()::equals).isPresent() && !busy) {
            detail.getChildren().add(confirmDeploy(project));
        }
        showRun(project.folder());

        if (project.workingSet()) {
            detail.getChildren().add(heading("Members"));
            int position = 1;
            for (KnowledgeBaseProjectFolder member : project.members()) {
                detail.getChildren().add(SelectableText.of(position++ + ".  " + member.artifactId()
                        + (member.title().equals(member.artifactId()) ? "" : "  ·  " + member.title())));
            }
            detail.getChildren().add(muted(project.members().isEmpty()
                    ? "No members yet. Choose a project that is on its own, and add it to this working set."
                    : "Deploy each member from its own page."));
        }

        if (!project.knowledgeSources().isEmpty()) {
            detail.getChildren().add(heading("Built from"));
            int position = 1;
            for (KnowledgeBaseProject.KnowledgeSource knowledgeSource : project.knowledgeSources()) {
                detail.getChildren().add(SelectableText.of(position++ + ".  " + knowledgeSource.artifactId() + "  ·  "
                        + NexusCatalog.label(knowledgeSource.classifier()) + "  ·  " + knowledgeSource.version()));
            }
            detail.getChildren().add(muted("Each source stays at this version until the project is changed."));
        }
        if (!project.workingSet()) {
            detail.getChildren().add(heading("Deploys to"));
            detail.getChildren().add(project.deploysTo().map(repository -> SelectableText.of(repository.id() + "  ·  " + repository.url()))
                    .orElseGet(() -> muted("Its POM names no repository of its own.")));
            project.collection().ifPresent(collection -> detail.getChildren().add(muted("Collection  " + collection)));
            detail.getChildren().add(heading("Working set"));
            detail.getChildren().add(membership(project, memberOf, busy));
        }
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
        if (!project.workingSet()) {
            detail.getChildren().add(heading("Last build"));
            detail.getChildren().add(project.built().map(when -> SelectableText.of("Built " + WHEN.format(when)))
                    .orElseGet(() -> muted("Not built yet.")));
        }
        if (outputOf.filter(project.folder()::equals).isPresent()) {
            detail.getChildren().add(heading(mine ? "Maven's output" : "Maven's output, from the last run"));
            detail.getChildren().add(output);
        }
    }

    /** Says what Maven is doing for a folder, or how its last run there ended. */
    private void showRun(Path subject) {
        if (working.filter(subject::equals).isPresent()) {
            Label running = new Label(workingOn);
            running.setStyle(ATTENTION);
            Button cancel = new Button("Cancel");
            cancel.setOnAction(event -> onCancel.run());
            HBox line = new HBox(10, running, cancel);
            line.setAlignment(Pos.CENTER_LEFT);
            detail.getChildren().add(line);
        } else if (working.isPresent()) {
            detail.getChildren().add(muted("Another project is being built. One builds at a time."));
        }
        Optional.ofNullable(ended.get(subject)).ifPresent(end -> {
            SelectableText said = SelectableText.of(end.text());
            said.setStyle(end.problem() ? PROBLEM : "-fx-text-fill: #2e7d32;");
            detail.getChildren().add(said);
        });
    }

    /**
     * A project's place among the working sets: the one it is a member of, with the way out of it, or
     * the working sets it could join.
     */
    private Node membership(KnowledgeBaseProjectFolder project, Optional<KnowledgeBaseProjectFolder> memberOf, boolean busy) {
        if (memberOf.isPresent()) {
            Button remove = new Button("Remove from working set");
            remove.setDisable(busy);
            remove.setOnAction(event -> onMember.run(project, memberOf.get(), false));
            return new VBox(6, SelectableText.of("A member of " + memberOf.get().artifactId() + "."), remove,
                    muted("Its folder moves back to " + folder + ", where it is a project on its own again."));
        }
        List<KnowledgeBaseProjectFolder> workingSets = top.stream().filter(KnowledgeBaseProjectFolder::workingSet).toList();
        if (workingSets.isEmpty()) {
            return muted("On its own. A working set builds several projects together, in order. New working set makes one.");
        }
        ChoiceBox<KnowledgeBaseProjectFolder> which = new ChoiceBox<>();
        which.getItems().setAll(workingSets);
        which.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(KnowledgeBaseProjectFolder workingSet) {
                return workingSet == null ? "" : workingSet.artifactId();
            }

            @Override
            public KnowledgeBaseProjectFolder fromString(String text) {
                return null;
            }
        });
        which.getSelectionModel().selectFirst();
        Button add = new Button("Add to working set");
        add.setDisable(busy);
        add.setOnAction(event -> Optional.ofNullable(which.getValue()).ifPresent(workingSet -> onMember.run(project, workingSet, true)));
        HBox line = new HBox(8, add, which);
        line.setAlignment(Pos.CENTER_LEFT);
        return new VBox(6, SelectableText.of("On its own."), line,
                muted("A working set builds several projects together, in order. This project's folder moves into the"
                        + " working set's folder. It still builds on its own there."));
    }

    /** Where a new working set is named; it is made empty, and projects are added to it afterwards. */
    private void showWorkingSetForm() {
        SelectableText title = SelectableText.of("New working set");
        title.setStyle("-fx-font-size: 20px; -fx-font-weight: bold;");
        detail.getChildren().add(title);
        detail.getChildren().add(muted("A working set builds several projects together, in order: a knowledge base and the"
                + " sources it is built from, for example. It is made empty, in a folder of its own in " + folder
                + ". A project is added once it builds on its own."));
        HBox.setHgrow(workingSetName, Priority.ALWAYS);
        HBox.setHgrow(workingSetGroup, Priority.ALWAYS);
        Label nameLabel = new Label("Name");
        nameLabel.setMinWidth(60);
        Label groupLabel = new Label("Group");
        groupLabel.setMinWidth(60);
        HBox nameLine = new HBox(8, nameLabel, workingSetName);
        nameLine.setAlignment(Pos.CENTER_LEFT);
        HBox groupLine = new HBox(8, groupLabel, workingSetGroup);
        groupLine.setAlignment(Pos.CENTER_LEFT);
        Button create = new Button("Create working set");
        create.setId("create-working-set");
        create.setDefaultButton(true);
        create.setOnAction(event -> onNewWorkingSet.accept(workingSetName.getText().strip(), workingSetGroup.getText().strip()));
        Button close = new Button("Close");
        close.setOnAction(event -> {
            namingWorkingSet = false;
            if (!list.getItems().isEmpty()) {
                list.getSelectionModel().selectFirst();
            }
            showDetail();
        });
        HBox buttons = new HBox(8, create, close);
        SelectableText problem = SelectableText.of("");
        problem.setId("working-set-problem");
        detail.getChildren().addAll(nameLine, muted("Names end in -ws by custom, such as solor-ws."), groupLine, problem, buttons);
        showWorkingSetProblem();
        showRun(folder.resolve(workingSetName.getText().strip().isEmpty() ? "-" : workingSetName.getText().strip()));
        if (outputOf.filter(folder.resolve(workingSetName.getText().strip())::equals).isPresent()) {
            detail.getChildren().add(heading("Maven's output"));
            detail.getChildren().add(output);
        }
    }

    /** Says what is wrong with the name typed for a working set, and offers Create only when nothing is. */
    private void showWorkingSetProblem() {
        Optional<String> problem = workingSetProblem();
        detail.getChildren().stream().filter(node -> "working-set-problem".equals(node.getId())).findFirst()
                .ifPresent(node -> {
                    SelectableText said = (SelectableText) node;
                    said.setText(problem.orElse(""));
                    said.setStyle(problem.isPresent() && !workingSetName.getText().isBlank() ? PROBLEM : MUTED);
                });
        detail.lookupAll("#create-working-set").forEach(node -> node.setDisable(problem.isPresent() || working.isPresent()));
    }

    /** Why the name typed cannot name a working set, or empty when it can. */
    private Optional<String> workingSetProblem() {
        String name = workingSetName.getText() == null ? "" : workingSetName.getText().strip();
        String groupId = workingSetGroup.getText() == null ? "" : workingSetGroup.getText().strip();
        if (name.isEmpty()) {
            return Optional.of("Give the working set a name.");
        }
        List<String> problems = KnowledgeBaseProject.nameProblems(name, groupId);
        if (!problems.isEmpty()) {
            return Optional.of(problems.getFirst());
        }
        return names.taken(name, groupId).map(taken -> taken + " " + names.free(name, groupId) + " is free.");
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

    /** A project's tile, a working set's with a W: the same shape as a knowledge base's icon, so the lists read alike. */
    private static Node tile(KnowledgeBaseProjectFolder project, double size) {
        Label letter = new Label(project.workingSet() ? "W" : "P");
        letter.setStyle("-fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: " + Math.round(size * 0.5) + "px;");
        StackPane tile = new StackPane(letter);
        tile.setMinSize(size, size);
        tile.setPrefSize(size, size);
        tile.setMaxSize(size, size);
        tile.setStyle("-fx-background-color: " + (project.workingSet() ? "#6b4f3c" : "#5a6b3c") + "; -fx-background-radius: "
                + Math.round(size * 0.22) + ";");
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
            HBox line = new HBox(6, tile(project, 20), name, gap, mark);
            if (project.workingSetAmong(top).isPresent()) {
                line.setPadding(new Insets(0, 0, 0, 18));        // a member, beneath its working set
            }
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
