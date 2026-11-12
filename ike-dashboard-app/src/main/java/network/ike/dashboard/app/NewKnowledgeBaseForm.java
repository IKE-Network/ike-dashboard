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

import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.VPos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The form in front of {@code ike:kb-create-draft} and {@code ike:kb-create-publish}
 * (IKE-Network/ike-issues#1370): a person names a new knowledge base, picks the protobuf layers on
 * Nexus it is built from, in load order, reads the draft, and creates it. The form says what the goals
 * will be given, a {@link KnowledgeBaseProject}, and shows what they answer; it works nothing out that
 * the goals decide, beyond an estimate of the sizes while the layers are picked. Once the project is
 * created it is the Projects tab's, where it is built and deployed. It does no IO of its
 * own; {@link DashboardApp} supplies the catalog and runs the goals.
 *
 * <p>Create is offered only for the project the last draft showed: any change to the form sets the
 * draft aside, so what is created is what was read.
 */
public final class NewKnowledgeBaseForm extends BorderPane {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault());
    private static final String MUTED = "-fx-text-fill: #6b6b6b;";
    private static final String PROBLEM = "-fx-text-fill: #c62828;";
    private static final String SECTION = "-fx-font-weight: bold;";
    /** The width of the labels beside the fields, the same in every group so the fields line up. */
    private static final double LABELS = 120;

    /**
     * One layer of the list.
     *
     * @param layer     the layer, at the version Nexus lists it under
     * @param sizeBytes its size, negative when not known
     * @param deployed  when it was deployed, when known
     * @param name      the name a person reads
     * @param builtFrom what its own descriptor says it was built from, blank when it says nothing
     */
    record Row(KnowledgeBaseProject.Layer layer, long sizeBytes, Optional<Instant> deployed, String name, String builtFrom) {
    }

    private final TextField name = new TextField();
    private final TextField displayName = new TextField();
    private final TextField group = new TextField(KnowledgeBaseProject.DEFAULT_GROUP);
    private final TextField version = new TextField(KnowledgeBaseProject.DEFAULT_VERSION);
    private final ToggleButton assembly = new ToggleButton(KnowledgeBaseProject.Kind.ASSEMBLY.label());
    private final ToggleButton sourceLayer = new ToggleButton(KnowledgeBaseProject.Kind.SOURCE_LAYER.label());
    private final TextField collection = new TextField();
    private final TextField folder = new TextField();
    private final TextField importer = new TextField();
    private final TextField release = new TextField();
    private final TextField importerOptions = new TextField();
    private final TextField editModule = new TextField();
    private final VBox layerBox = new VBox(6);
    private final Button addProtobuf = new Button("Add protobuf…");
    private final Button copyInputs = new Button("Copy inputs from KB…");
    private final Label sizes = new Label();
    private final ToggleButton rocksBySize = new ToggleButton("By size");
    private final ToggleButton rocksAlways = new ToggleButton("Always");
    private final ToggleButton rocksNever = new ToggleButton("Never");
    private final Label rocksLine = new Label();
    private final Label deploysTo = new Label();
    private final Hyperlink changeDeploy = new Hyperlink("Change…");
    private final TextField distributionId = new TextField(KnowledgeBaseProject.DEFAULT_DISTRIBUTION_ID);
    private final TextField releasesUrl = new TextField(KnowledgeBaseProject.DEFAULT_RELEASES_URL);
    private final TextField snapshotsUrl = new TextField(KnowledgeBaseProject.DEFAULT_SNAPSHOTS_URL);
    private final GridPane deployFields = new GridPane();
    private final TextArea draft = new TextArea();
    private final Label message = new Label();
    private final Button draftButton = new Button("Draft");
    private final Button createButton = new Button("Create knowledge base");
    private final Button cancel = new Button("Cancel");
    private final Button another = new Button("New knowledge base");
    private final List<Node> assemblyOnly = new ArrayList<>();
    private final List<Node> sourceLayerOnly = new ArrayList<>();
    /** Everything a person fills in; the draft beneath it stays readable while this waits. */
    private final VBox fields = new VBox(14);

    private final List<Row> rows = new ArrayList<>();
    private NexusCatalog.Catalog catalog = new NexusCatalog.Catalog(List.of(), Map.of());
    /** The projects folder, where a new project is made in a folder named for its knowledge base. */
    private Path projects = Path.of(System.getProperty("user.home"), "knowledge-bases");
    private KnowledgeBaseProject.Names namesHere = KnowledgeBaseProject.Names.NONE;
    /** A free name like the one typed, offered when that one is taken. */
    private Optional<String> suggestion = Optional.empty();
    private final Hyperlink useSuggestion = new Hyperlink();
    private boolean folderFollowsName = true;
    private List<String> missing = List.of();
    private boolean working;
    private Optional<KnowledgeBaseProject> drafted = Optional.empty();
    private Map<KnowledgeBaseProject.Layer, String> pinned = Map.of();
    /** The last draft, whose sizes are the goals' own and replace the estimate while it stands. */
    private Optional<KnowledgeBaseCreation.Outcome> draftOutcome = Optional.empty();
    private final ScrollPane scroll = new ScrollPane();
    private Optional<KnowledgeBaseProject> created = Optional.empty();
    private Stage chooser;

    private Consumer<KnowledgeBaseProject> onDraft = project -> { };
    private Consumer<KnowledgeBaseProject> onCreate = project -> { };
    private Runnable onCancel = () -> { };

    /** Builds the form, with no layers and nothing on Nexus to pick from until a catalog is set. */
    public NewKnowledgeBaseForm() {
        name.setPromptText("my-kb");
        displayName.setPromptText("From the name");
        collection.setPromptText("None");
        importer.setPromptText("Such as snomed-rf2");
        release.setPromptText("The release's coordinate, or its path");
        importerOptions.setPromptText("No options");
        editModule.setPromptText("None: every module's versions are kept");
        segmented(assembly, sourceLayer);
        assembly.setSelected(true);
        segmented(rocksBySize, rocksAlways, rocksNever);
        rocksBySize.setSelected(true);

        fields.getChildren().addAll(identity(), layers(), size(), deployment(), repository());
        VBox form = new VBox(14, fields, draftSection());
        form.setPadding(new Insets(16, 20, 16, 20));
        scroll.setContent(form);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color: transparent; -fx-background-insets: 0;");
        setCenter(scroll);

        message.setWrapText(true);
        message.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(message, Priority.ALWAYS);
        createButton.setDefaultButton(true);
        for (Button button : List.of(draftButton, createButton, cancel, another)) {
            button.setMinWidth(Region.USE_PREF_SIZE);
            button.managedProperty().bind(button.visibleProperty());
        }
        useSuggestion.setMinWidth(Region.USE_PREF_SIZE);
        useSuggestion.managedProperty().bind(useSuggestion.visibleProperty());
        useSuggestion.setVisible(false);
        useSuggestion.setOnAction(event -> suggestion.ifPresent(name::setText));
        HBox bottom = new HBox(8, message, useSuggestion, cancel, another, draftButton, createButton);
        bottom.setAlignment(Pos.CENTER_LEFT);
        bottom.setPadding(new Insets(8, 12, 10, 20));
        setBottom(bottom);

        draftButton.setOnAction(event -> onDraft.accept(project()));
        createButton.setOnAction(event -> drafted.ifPresent(onCreate));
        cancel.setOnAction(event -> onCancel.run());
        another.setOnAction(event -> startOver());
        addProtobuf.setOnAction(event -> chooseProtobuf());
        copyInputs.setOnAction(event -> chooseKnowledgeBase());

        name.textProperty().addListener((observable, was, now) -> {
            if (folderFollowsName) {
                folder.setText(defaultFolder().toString());
                folderFollowsName = true;                // setting the text is not the person's edit
            }
            changed();
        });
        folder.textProperty().addListener((observable, was, now) -> {
            folderFollowsName = false;
            changed();
        });
        for (TextField field : List.of(displayName, group, version, collection, importer, release, importerOptions,
                editModule, distributionId, releasesUrl, snapshotsUrl)) {
            field.textProperty().addListener((observable, was, now) -> changed());
        }
        assembly.selectedProperty().addListener((observable, was, now) -> changed());
        rocksBySize.getToggleGroup().selectedToggleProperty().addListener((observable, was, now) -> changed());
        folder.setText(defaultFolder().toString());
        folderFollowsName = true;
        changed();
    }

    private Node identity() {
        HBox kind = new HBox(assembly, sourceLayer);
        Label kindLine = muted("");
        kindLine.textProperty().bind(assembly.selectedProperty().map(selected -> selected
                ? "Combines the layers, runs the reasoner, and publishes a reasoned protobuf."
                : "Reads a release into an unreasoned protobuf, which an assembly then loads."));
        GridPane first = grid();
        int row = field(first, 0, "Name", name, "Lower-case letters, digits and hyphens. It also names the folder.");
        row = field(first, row, "Display name", displayName, null);
        row = field(first, row, "Group", group, null);
        row = field(first, row, "Version", version, null);
        row = field(first, row, "Kind", kind, null);
        first.add(kindLine, 1, row);
        GridPane source = grid();
        row = field(source, 0, "Importer", importer, null);
        row = field(source, row, "Release", release, null);
        field(source, row, "Importer options", importerOptions, null);
        sourceLayerOnly.add(source);
        GridPane edits = grid();
        field(edits, 0, "Komet edits", editModule,
                "Kept as change sets in the project's changesets folder. Name a module's UUID to keep only its versions.");
        assemblyOnly.add(edits);
        GridPane last = grid();
        row = field(last, 0, "Collection", collection, "The collection dashboards list it under.");
        field(last, row, "Folder", folder, "The project that builds it, apart from the knowledge base itself. Made when it is created.");
        return section("Identity", new VBox(6, first, source, edits, last));
    }

    private Node layers() {
        HBox buttons = new HBox(8, addProtobuf, copyInputs);
        Label order = muted("Layers load in the order listed.");
        return section("Layers", new VBox(8, layerBox, buttons, order));
    }

    private Node size() {
        HBox rocks = new HBox(rocksBySize, rocksAlways, rocksNever);
        Label rocksLabel = new Label("Rocks database");
        HBox choice = new HBox(10, rocksLabel, rocks, rocksLine);
        choice.setAlignment(Pos.CENTER_LEFT);
        rocksLine.setStyle(MUTED);
        assemblyOnly.add(choice);
        return section("Size", new VBox(8, sizes, choice));
    }

    private Node deployment() {
        HBox line = new HBox(8, deploysTo, changeDeploy);
        line.setAlignment(Pos.CENTER_LEFT);
        deployFields.setHgap(10);
        deployFields.setVgap(6);
        ColumnConstraints labels = new ColumnConstraints(LABELS);
        ColumnConstraints values = new ColumnConstraints();
        values.setHgrow(Priority.ALWAYS);
        deployFields.getColumnConstraints().setAll(labels, values);
        deployFields.addRow(0, new Label("Server id"), distributionId);
        deployFields.addRow(1, new Label("Releases"), releasesUrl);
        deployFields.addRow(2, new Label("Snapshots"), snapshotsUrl);
        deployFields.managedProperty().bind(deployFields.visibleProperty());
        deployFields.setVisible(false);
        changeDeploy.managedProperty().bind(changeDeploy.visibleProperty());
        changeDeploy.setOnAction(event -> {
            deployFields.setVisible(true);
            changeDeploy.setVisible(false);
        });
        return section("Deploys to", new VBox(8, line, deployFields));
    }

    private Node repository() {
        Label line = new Label("A git repository in its folder, with one commit and no remote.");
        Label later = muted("You can add a remote on any git host later.");
        return section("Repository", new VBox(4, line, later));
    }

    private Node draftSection() {
        draft.setEditable(false);
        draft.setPrefRowCount(14);
        draft.setStyle("-fx-font-family: Menlo, Consolas, monospace; -fx-font-size: 11px;");
        draft.setPromptText("Draft shows what will be created, with each layer at its exact version on Nexus."
                + " Nothing is written until you choose Create knowledge base.");
        return section("Draft", draft);
    }

    private static Node section(String title, Node content) {
        Label heading = new Label(title);
        heading.setStyle(SECTION);
        return new VBox(6, heading, content);
    }

    private static GridPane grid() {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(6);
        ColumnConstraints labels = new ColumnConstraints(LABELS);
        labels.setHalignment(HPos.LEFT);
        ColumnConstraints values = new ColumnConstraints();
        values.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().setAll(labels, values);
        return grid;
    }

    /** Adds a labelled field, and beneath it a line that says what it is; returns the next free row. */
    private static int field(GridPane grid, int row, String label, Node field, String explanation) {
        Label heading = new Label(label);
        GridPane.setValignment(heading, VPos.CENTER);
        grid.add(heading, 0, row);
        grid.add(field, 1, row);
        if (explanation == null) {
            return row + 1;
        }
        grid.add(muted(explanation), 1, row + 1);
        return row + 2;
    }

    private static Label muted(String text) {
        Label label = new Label(text);
        label.setStyle(MUTED);
        label.setWrapText(true);
        return label;
    }

    private static void segmented(ToggleButton... buttons) {
        ToggleGroup group = new ToggleGroup();
        for (int index = 0; index < buttons.length; index++) {
            ToggleButton button = buttons[index];
            button.setToggleGroup(group);
            button.setMinWidth(Region.USE_PREF_SIZE);
            button.getStyleClass().add(index == 0 ? "left-pill" : index == buttons.length - 1 ? "right-pill" : "center-pill");
        }
        group.selectedToggleProperty().addListener((observable, was, now) -> {
            if (now == null) {
                was.setSelected(true);                   // one is always chosen
            }
        });
    }

    /**
     * What Nexus offers, to pick layers from and to copy another knowledge base's inputs from.
     *
     * @param catalog the described knowledge bases and their protobufs
     */
    public void setCatalog(NexusCatalog.Catalog catalog) {
        this.catalog = catalog;
        refresh();
    }

    /**
     * Where new projects are made: the projects folder, apart from the knowledge bases themselves, in
     * which a project has a folder named for its knowledge base.
     *
     * @param projects the projects folder
     */
    public void setProjectsFolder(Path projects) {
        this.projects = projects;
        if (folderFollowsName) {
            folder.setText(defaultFolder().toString());
            folderFollowsName = true;
        }
    }

    /**
     * The names already in use on this machine, which a new knowledge base must not repeat; the names on
     * Nexus come from the catalog.
     *
     * @param names the names of the projects, and of the knowledge bases and folders in the knowledge-base folder
     */
    public void setNamesHere(KnowledgeBaseProject.Names names) {
        namesHere = names;
        refresh();
    }

    /** The names in use, here and on Nexus. */
    private KnowledgeBaseProject.Names names() {
        java.util.Set<String> nexus = new java.util.HashSet<>();
        catalog.described().keySet().forEach(coordinate -> nexus.add(coordinate.groupId() + ":" + coordinate.artifactId()));
        catalog.offers().forEach(offer -> nexus.add(offer.coordinate().groupId() + ":" + offer.coordinate().artifactId()));
        return namesHere.withNexus(nexus);
    }

    /** The folder a project is made in while the person has not named another. */
    private Path defaultFolder() {
        String typed = text(name);
        return projects.resolve(typed);
    }

    /**
     * What a build needs that this machine lacks; while anything is missing, nothing can be drafted.
     *
     * @param missing a sentence for each thing missing
     */
    public void setPrerequisites(List<String> missing) {
        this.missing = List.copyOf(missing);
        refresh();
    }

    /**
     * What Draft does, given the project the form describes.
     *
     * @param action the action
     */
    public void setOnDraft(Consumer<KnowledgeBaseProject> action) {
        onDraft = action;
    }

    /**
     * What Create knowledge base does, given the project the last draft showed.
     *
     * @param action the action
     */
    public void setOnCreate(Consumer<KnowledgeBaseProject> action) {
        onCreate = action;
    }

    /**
     * What Cancel does while Maven runs.
     *
     * @param action the action
     */
    public void setOnCancel(Runnable action) {
        onCancel = action;
    }

    /**
     * The project the form describes, in the goals' words: only what differs from their defaults is said.
     *
     * @return the project
     */
    public KnowledgeBaseProject project() {
        Map<String, String> properties = new LinkedHashMap<>();
        KnowledgeBaseProject.Kind kind = kind();
        if (kind != KnowledgeBaseProject.Kind.ASSEMBLY) {
            properties.put(KnowledgeBaseProject.KIND, kind.value());
        }
        put(properties, "groupId", group, KnowledgeBaseProject.DEFAULT_GROUP);
        put(properties, "version", version, KnowledgeBaseProject.DEFAULT_VERSION);
        put(properties, "displayName", displayName, "");
        properties.put(KnowledgeBaseProject.LAYERS, String.join(",", rows.stream().map(row -> row.layer().toString()).toList()));
        if (kind == KnowledgeBaseProject.Kind.SOURCE_LAYER) {
            put(properties, "importer", importer, "");
            put(properties, "release", release, "");
            put(properties, "importerOptions", importerOptions, "");
        } else {
            put(properties, "editModule", editModule, "");
            rocks().ifPresent(always -> properties.put(KnowledgeBaseProject.ROCKS, String.valueOf(always)));
        }
        put(properties, "collection", collection, "");
        put(properties, "distributionId", distributionId, KnowledgeBaseProject.DEFAULT_DISTRIBUTION_ID);
        put(properties, "releasesUrl", releasesUrl, KnowledgeBaseProject.DEFAULT_RELEASES_URL);
        put(properties, "snapshotsUrl", snapshotsUrl, KnowledgeBaseProject.DEFAULT_SNAPSHOTS_URL);
        String where = folder.getText() == null ? "" : folder.getText().strip();
        return new KnowledgeBaseProject(name.getText(), where.isEmpty() ? projects : Path.of(where).toAbsolutePath().normalize(),
                properties);
    }

    private static void put(Map<String, String> properties, String property, TextField field, String unless) {
        String value = field.getText() == null ? "" : field.getText().strip();
        if (!value.isEmpty() && !value.equals(unless)) {
            properties.put(property, value);
        }
    }

    private KnowledgeBaseProject.Kind kind() {
        return assembly.isSelected() ? KnowledgeBaseProject.Kind.ASSEMBLY : KnowledgeBaseProject.Kind.SOURCE_LAYER;
    }

    private Optional<Boolean> rocks() {
        return rocksAlways.isSelected() ? Optional.of(true) : rocksNever.isSelected() ? Optional.of(false) : Optional.empty();
    }

    /**
     * The layers listed, in load order, without the starter set the goals put first.
     *
     * @return the rows
     */
    List<Row> rows() {
        return List.copyOf(rows);
    }

    /**
     * Adds a protobuf Nexus offers as the last layer; one of an artifact already listed replaces it in place.
     *
     * @param offer the protobuf
     */
    void addLayer(NexusCatalog.Offer offer) {
        Row row = new Row(KnowledgeBaseProject.Layer.of(offer), offer.sizeBytes(), Optional.of(offer.updated()),
                displayName(offer.coordinate()), builtFrom(offer.coordinate()));
        place(row);
        changed();
    }

    /**
     * Lists the protobufs another knowledge base was built from, as its descriptor on Nexus records them,
     * in place of the layers listed. The starter set is left out: the goals put it first.
     *
     * @param knowledgeBase the knowledge base, as the catalog describes it
     */
    void copyInputs(Coordinate knowledgeBase) {
        NexusCatalog.Described described = catalog.described().get(knowledgeBase);
        if (described == null) {
            return;
        }
        rows.clear();
        for (KnowledgeBaseDescriptor.Input input : described.descriptor().inputs()) {
            Optional<Coordinate> coordinate = input.coordinate();
            Optional<String> classifier = input.classifier().filter(NexusCatalog.PROTOBUF_CLASSIFIERS::contains);
            if (coordinate.isEmpty() || classifier.isEmpty()) {
                continue;
            }
            KnowledgeBaseProject.Layer layer = new KnowledgeBaseProject.Layer(coordinate.get().groupId(),
                    coordinate.get().artifactId(), classifier.get(), coordinate.get().version());
            if (layer.starterSet()) {
                continue;
            }
            Optional<NexusCatalog.Offer> offer = catalog.offers().stream().filter(candidate ->
                    candidate.coordinate().equals(coordinate.get()) && candidate.classifier().equals(classifier.get())).findFirst();
            place(new Row(layer, offer.map(NexusCatalog.Offer::sizeBytes).orElse(-1L), offer.map(NexusCatalog.Offer::updated),
                    displayName(coordinate.get()), builtFrom(coordinate.get())));
        }
        changed();
    }

    private void place(Row row) {
        for (int index = 0; index < rows.size(); index++) {
            if (rows.get(index).layer().sameArtifact(row.layer())) {
                rows.set(index, row);
                return;
            }
        }
        rows.add(row);
    }

    private String displayName(Coordinate knowledgeBase) {
        return described(knowledgeBase).map(entry -> entry.descriptor().displayName()).orElse(knowledgeBase.artifactId());
    }

    /** A knowledge base's entry in the catalog, at the version named, else at any version. */
    private Optional<NexusCatalog.Described> described(Coordinate knowledgeBase) {
        NexusCatalog.Described exact = catalog.described().get(knowledgeBase);
        if (exact != null) {
            return Optional.of(exact);
        }
        return catalog.described().entrySet().stream().filter(entry -> entry.getKey().sameArtifact(knowledgeBase))
                .map(Map.Entry::getValue).findFirst();
    }

    /** What a layer's own descriptor says it was built from: each input's artifact, version, and role. */
    private String builtFrom(Coordinate knowledgeBase) {
        NexusCatalog.Described entry = catalog.described().get(knowledgeBase);
        if (entry == null) {
            return "";
        }
        List<String> inputs = new ArrayList<>();
        for (KnowledgeBaseDescriptor.Input input : entry.descriptor().inputs()) {
            if (input.of(knowledgeBase)) {
                continue;                                // a store records the protobuf it was loaded from
            }
            String what = input.coordinate().map(coordinate -> coordinate.artifactId()
                            + input.version().map(text -> " " + text).orElse(""))
                    .or(input::file).orElse(input.reference());
            inputs.add(what + " (" + KnowledgeBaseTree.describe(input) + ")");
        }
        return String.join("; ", inputs);
    }

    /** The form changed: the draft no longer shows it. */
    private void changed() {
        if (created.isPresent()) {
            return;
        }
        if (drafted.isPresent() && !drafted.get().equals(project())) {
            drafted = Optional.empty();
            pinned = Map.of();
            draftOutcome = Optional.empty();
            draft.clear();
        }
        refresh();
    }

    private void refresh() {
        KnowledgeBaseProject.Kind kind = kind();
        assemblyOnly.forEach(node -> show(node, kind == KnowledgeBaseProject.Kind.ASSEMBLY));
        sourceLayerOnly.forEach(node -> show(node, kind == KnowledgeBaseProject.Kind.SOURCE_LAYER));
        showLayers();
        KnowledgeBaseProject.Estimate estimate = KnowledgeBaseProject.estimate(kind,
                rows.stream().map(Row::sizeBytes).toList(), rocks());
        // Until a draft pins the layers the sizes are an estimate, without the starter set; then they are the goals' own.
        Optional<String> total = draftOutcome.flatMap(outcome -> outcome.reported("Layers total"));
        Optional<String> heap = draftOutcome.flatMap(outcome -> outcome.reported("Build heap"));
        Optional<String> store = draftOutcome.flatMap(outcome -> outcome.reported("Rocks store"));
        sizes.setText(rows.isEmpty() ? "No layers yet."
                : total.isPresent() && heap.isPresent() ? "Layers total " + total.get() + "  ·  the build is given " + heap.get()
                : "Layers total " + KnowledgeBaseProject.Estimate.size(estimate.layerBytes())
                + (estimate.unknownSizes() == 0 ? "" : " (" + estimate.unknownSizes() + " not known)")
                + "  ·  about " + KnowledgeBaseProject.Estimate.size(estimate.loadedBytes()) + " of memory once loaded"
                + "  ·  the build is given " + estimate.heapGib() + " GiB");
        rocksLine.setText(store.map(line -> "From the draft: " + line + ".")
                .orElse((estimate.rocks() ? "Built: " : "Not built: ") + estimate.rocksReason() + "."));
        deploysTo.setText(text(distributionId) + "  ·  " + text(version.getText().strip().endsWith("-SNAPSHOT")
                ? snapshotsUrl : releasesUrl));

        boolean done = created.isPresent();
        KnowledgeBaseProject project = project();
        List<String> problems = new ArrayList<>(project.problems());
        suggestion = Optional.empty();
        if (!done && problems.isEmpty()) {
            // A name in use is said only once the name is one the goals would take.
            String groupId = project.properties().getOrDefault("groupId", KnowledgeBaseProject.DEFAULT_GROUP);
            KnowledgeBaseProject.Names names = names();
            Optional<String> taken = names.taken(project.name(), groupId);
            if (taken.isPresent()) {
                suggestion = Optional.of(names.free(project.name(), groupId));
                problems.add(taken.get() + " " + suggestion.get() + " is free.");
            }
        }
        useSuggestion.setText(suggestion.map(free -> "Use " + free).orElse(""));
        useSuggestion.setVisible(suggestion.isPresent() && !working && missing.isEmpty());
        boolean ready = missing.isEmpty() && problems.isEmpty();
        fields.setDisable(working || done);
        draftButton.setVisible(!done);
        createButton.setVisible(!done);
        cancel.setVisible(working);
        another.setVisible(done);
        another.setDisable(working);
        draftButton.setDisable(working || !ready);
        createButton.setDisable(working || !ready || drafted.isEmpty());
        if (!working && !done) {
            if (!missing.isEmpty()) {
                say(String.join(" ", missing), true);
            } else if (!problems.isEmpty()) {
                // An untouched form has nothing wrong with it yet; it says what to do first.
                say(problems.getFirst(), !(text(name).isEmpty() && rows.isEmpty()));
            } else if (drafted.isPresent()) {
                say("The draft shows what will be created in " + drafted.get().folder() + ".", false);
            } else {
                say("Draft it to see what will be created.", false);
            }
        }
    }

    private static String text(TextField field) {
        return field.getText() == null ? "" : field.getText().strip();
    }

    private static void show(Node node, boolean shown) {
        node.setVisible(shown);
        node.setManaged(shown);
    }

    private void say(String text, boolean problem) {
        message.setText(text);
        message.setStyle(problem ? PROBLEM : MUTED);
    }

    private void showLayers() {
        List<Node> shown = new ArrayList<>();
        List<KnowledgeBaseProject.Layer> layers = rows.stream().map(Row::layer).toList();
        int position = 1;
        if (KnowledgeBaseProject.starterSetFirst(layers)) {
            Optional<Map.Entry<KnowledgeBaseProject.Layer, String>> starter = pinned.entrySet().stream()
                    .filter(entry -> entry.getKey().starterSet()).findFirst();
            shown.add(layerRow(position++, "IKE Starter Set", NexusCatalog.label("unreasoned-pb") + "  ·  "
                            + starter.map(entry -> entry.getKey().version() + "  ·  " + entry.getValue())
                            .orElse("the draft finds its exact version"),
                    "Always loaded first. Every other layer depends on it.", List.of()));
        }
        for (int index = 0; index < rows.size(); index++) {
            Row row = rows.get(index);
            int at = index;
            Optional<Map.Entry<KnowledgeBaseProject.Layer, String>> pin = pinned.entrySet().stream()
                    .filter(entry -> entry.getKey().sameArtifact(row.layer())).findFirst();
            String facts = NexusCatalog.label(row.layer().classifier()) + "  ·  "
                    + pin.map(entry -> entry.getKey().version() + "  ·  " + entry.getValue())
                    .orElse(row.layer().version()
                            + row.deployed().map(when -> ", deployed " + DAY.format(when)).orElse("")
                            + "  ·  " + (row.sizeBytes() < 0 ? "size not known" : KnowledgeBaseProject.Estimate.size(row.sizeBytes())));
            Button up = small("↑", "Load " + row.name() + " earlier", () -> move(at, at - 1));
            up.setDisable(index == 0);
            Button down = small("↓", "Load " + row.name() + " later", () -> move(at, at + 1));
            down.setDisable(index == rows.size() - 1);
            Button remove = small("Remove", "Remove " + row.name(), () -> {
                rows.remove(at);
                changed();
            });
            shown.add(layerRow(position++, row.name(), facts,
                    row.builtFrom().isEmpty() ? "" : "Built from " + row.builtFrom(), List.of(up, down, remove)));
        }
        layerBox.getChildren().setAll(shown);
    }

    private void move(int from, int to) {
        if (to < 0 || to >= rows.size()) {
            return;
        }
        rows.add(to, rows.remove(from));
        changed();
    }

    private static Button small(String text, String accessible, Runnable action) {
        Button button = new Button(text);
        button.setAccessibleText(accessible);
        button.setMinWidth(Region.USE_PREF_SIZE);
        button.setStyle("-fx-font-size: 11px; -fx-padding: 2 7 2 7;");
        button.setOnAction(event -> action.run());
        return button;
    }

    private static Node layerRow(int position, String name, String facts, String builtFrom, List<Button> actions) {
        Label number = new Label(position + ".");
        number.setMinWidth(22);
        number.setStyle(MUTED);
        Label title = new Label(name);
        title.setStyle(SECTION);
        VBox lines = new VBox(1, title, muted(facts));
        if (!builtFrom.isEmpty()) {
            lines.getChildren().add(muted(builtFrom));
        }
        HBox.setHgrow(lines, Priority.ALWAYS);
        HBox buttons = new HBox(4);
        buttons.getChildren().addAll(actions);
        buttons.setAlignment(Pos.TOP_RIGHT);
        buttons.setMinWidth(Region.USE_PREF_SIZE);
        HBox row = new HBox(8, number, lines, buttons);
        row.setPadding(new Insets(6, 8, 6, 8));
        row.setStyle("-fx-background-color: white; -fx-background-radius: 6; -fx-border-color: #d9d9d9; -fx-border-radius: 6;");
        return row;
    }

    /** Offers the protobufs on Nexus that are not layers yet. */
    private void chooseProtobuf() {
        List<NexusCatalog.Offer> offers = catalog.offers().stream()
                .filter(offer -> NexusCatalog.PROTOBUF_CLASSIFIERS.contains(offer.classifier()))
                .filter(offer -> rows.stream().noneMatch(row -> row.layer().equals(KnowledgeBaseProject.Layer.of(offer))))
                .toList();
        choose("Add a protobuf layer", "Add", offers, offer -> displayName(offer.coordinate()) + "  ·  " + offer.label()
                + "  ·  " + offer.coordinate().version() + "  ·  " + KnowledgeBaseProject.Estimate.size(offer.sizeBytes()),
                this::addLayer);
    }

    /** Offers the knowledge bases on Nexus whose descriptor records protobuf inputs. */
    private void chooseKnowledgeBase() {
        List<Coordinate> knowledgeBases = catalog.described().entrySet().stream()
                .filter(entry -> entry.getValue().descriptor().inputs().stream().anyMatch(input -> !input.of(entry.getKey())
                        && input.classifier().filter(NexusCatalog.PROTOBUF_CLASSIFIERS::contains).isPresent()))
                .map(Map.Entry::getKey)
                .sorted(java.util.Comparator.comparing(this::displayName, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(Coordinate::version))
                .toList();
        choose("Copy the inputs of a knowledge base", "Copy inputs", knowledgeBases,
                knowledgeBase -> displayName(knowledgeBase) + "  ·  " + knowledgeBase.version(), this::copyInputs);
    }

    /** A window that lists choices, searchable; a double click or the button takes one. */
    private <T> void choose(String title, String action, List<T> choices, Function<T, String> text, Consumer<T> onChosen) {
        if (chooser != null) {
            chooser.close();
        }
        TextField search = new TextField();
        search.setPromptText("Search");
        ListView<T> list = new ListView<>();
        list.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(T item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : text.apply(item));
            }
        });
        list.setPlaceholder(new Label(choices.isEmpty() ? "Nexus offers nothing to choose yet" : "Nothing matches"));
        Runnable filter = () -> {
            String needle = search.getText() == null ? "" : search.getText().strip().toLowerCase(Locale.ROOT);
            list.getItems().setAll(choices.stream().filter(choice -> needle.isEmpty()
                    || text.apply(choice).toLowerCase(Locale.ROOT).contains(needle)).toList());
        };
        search.textProperty().addListener((observable, was, now) -> filter.run());
        filter.run();
        Stage window = new Stage();
        Button take = new Button(action);
        take.setDefaultButton(true);
        take.disableProperty().bind(list.getSelectionModel().selectedItemProperty().isNull());
        Runnable chosen = () -> Optional.ofNullable(list.getSelectionModel().getSelectedItem()).ifPresent(choice -> {
            window.close();
            onChosen.accept(choice);
        });
        take.setOnAction(event -> chosen.run());
        list.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2) {
                chosen.run();
            }
        });
        HBox bottom = new HBox(take);
        bottom.setAlignment(Pos.CENTER_RIGHT);
        bottom.setPadding(new Insets(8));
        BorderPane content = new BorderPane(list);
        BorderPane.setMargin(search, new Insets(8));
        content.setTop(search);
        content.setBottom(bottom);
        window.setTitle(title);
        window.setScene(new Scene(content, 560, 380));
        if (getScene() != null && getScene().getWindow() != null) {
            window.initOwner(getScene().getWindow());
        }
        chooser = window;
        window.show();
    }

    /**
     * Says that Maven is running, and what for; the form waits until {@link #drafted} or {@link #created}
     * ends it.
     *
     * @param what such as "Drafting my-kb …"
     */
    public void working(String what) {
        working = true;
        draft.clear();
        say(what, false);
        refresh();
    }

    /**
     * Shows the newest line Maven wrote, so a long step is seen to move.
     *
     * @param line the line
     */
    public void progress(String line) {
        if (working && !line.isBlank()) {
            draft.appendText(line + "\n");
        }
    }

    /**
     * Shows how a draft ended: its plan, with each layer at the deploy it was pinned to, or why the goals
     * refused it.
     *
     * @param project the project drafted
     * @param outcome how the draft ended
     */
    public void drafted(KnowledgeBaseProject project, KnowledgeBaseCreation.Outcome outcome) {
        working = false;
        if (outcome.succeeded() && project.equals(project())) {
            drafted = Optional.of(project);
            pinned = outcome.pinned();
            draftOutcome = Optional.of(outcome);
            draft.setText(String.join("\n", outcome.report()));
            refresh();
            showDraft();
        } else {
            drafted = Optional.empty();
            pinned = Map.of();
            draftOutcome = Optional.empty();
            fail("The draft was refused", outcome);
        }
    }

    /**
     * Shows how a creation ended: the project and what can be done with it, or why it failed.
     *
     * @param project the project created
     * @param outcome how the creation ended
     */
    public void created(KnowledgeBaseProject project, KnowledgeBaseCreation.Outcome outcome) {
        working = false;
        if (outcome.succeeded()) {
            created = Optional.of(project);
            draft.setText(String.join("\n", outcome.report()));
            refresh();
            showDraft();
            say("Created " + project.name() + " in " + project.folder() + ". Build it and deploy it on the Projects tab.", false);
        } else {
            fail(project.name() + " was not created", outcome);
        }
    }

    /**
     * Says that Maven could not be run at all, or was stopped.
     *
     * @param reason why
     */
    public void failed(String reason) {
        working = false;
        refresh();
        say(reason, true);
    }

    /** Brings the draft area, at the foot of the form, into view. */
    private void showDraft() {
        applyCss();
        layout();
        scroll.setVvalue(scroll.getVmax());
    }

    private void fail(String what, KnowledgeBaseCreation.Outcome outcome) {
        refresh();
        showDraft();
        Optional<String> reason = outcome.failure();
        draft.setText(reason.orElseGet(() -> String.join("\n", outcome.lines())));
        say(what + reason.map(text -> ": " + text.lines().findFirst().orElse("")).orElse(". Maven's output is below."), true);
    }

    /** Clears the form for another knowledge base, keeping the group, where it deploys, and the folder new ones go in. */
    private void startOver() {
        created = Optional.empty();
        drafted = Optional.empty();
        pinned = Map.of();
        draftOutcome = Optional.empty();
        rows.clear();
        draft.clear();
        folderFollowsName = true;
        name.clear();
        displayName.clear();
        folder.setText(defaultFolder().toString());
        folderFollowsName = true;
        changed();
    }

    /**
     * The words the form shows beneath its fields: what is wrong, what to do next, or what happened.
     *
     * @return the message
     */
    String message() {
        return message.getText();
    }

    /**
     * What the draft area shows.
     *
     * @return its text
     */
    String draftText() {
        return draft.getText();
    }

    /**
     * Whether Create knowledge base can be pressed: a draft of the form as it stands succeeded.
     *
     * @return whether it is offered
     */
    boolean canCreate() {
        return createButton.isVisible() && !createButton.isDisable();
    }
}
