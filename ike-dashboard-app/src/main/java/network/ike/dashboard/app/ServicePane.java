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

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.util.StringConverter;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The Service tab: runs a tinkar-service build on a store on this machine, for local development. The build is
 * chosen from a folder, usually a tinkar-service checkout, whose {@code target/} holds the jar its build made;
 * the store is any Komet opens, managed or not. One service runs at a time; Stop asks it to close its store
 * first. It does no IO of its own beyond reading a running service's log; {@link DashboardApp} finds the builds
 * and the JDK and starts the process.
 */
public final class ServicePane extends javafx.scene.layout.BorderPane {

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final String MUTED = "-fx-text-fill: #6b6b6b;";
    private static final String GOOD = "-fx-text-fill: #2e7d32;";
    private static final String ATTENTION = "-fx-text-fill: #b35900;";
    private static final String PROBLEM = "-fx-text-fill: #c62828;";
    /** How long a stop may take before Force Stop is offered. */
    static final Duration STOP_PATIENCE = Duration.ofSeconds(60);

    /** What starting a service asks for. */
    public record StartRequest(TinkarService.Build build, KnowledgeBase store, int restPort, int grpcPort) {
    }

    private final VBox box = new VBox(10);
    private final SelectableText folderText = SelectableText.of("");
    private final Button chooseFolder = new Button("Choose Folder…");
    private final Button rescan = new Button("↻");
    private final ChoiceBox<TinkarService.Build> jar = new ChoiceBox<>();
    private final SelectableText jarFacts = SelectableText.of("");
    private final SelectableText jdkText = SelectableText.of("");
    private final Button chooseJdk = new Button("Choose JDK…");
    private final ObservableList<KnowledgeBase> stores = FXCollections.observableArrayList();
    private final FilteredList<KnowledgeBase> shownStores = new FilteredList<>(stores);
    private final ListView<KnowledgeBase> storeList = new ListView<>(shownStores);
    private final TextField storeSearch = new TextField();
    private final SelectableText memory = SelectableText.of("");
    private final TextField restPort = new TextField(String.valueOf(TinkarService.DEFAULT_REST_PORT));
    private final TextField grpcPort = new TextField(String.valueOf(TinkarService.DEFAULT_GRPC_PORT));
    private final Button start = new Button("Start");
    private final VBox refusals = new VBox(4);
    private final VBox runBox = new VBox(6);
    /** Start and the ports, kept in view below the scrolling choices. */
    private final VBox actions = new VBox(6);
    private final Timeline ticker = new Timeline(new KeyFrame(javafx.util.Duration.seconds(1), event -> showRun()));

    private Optional<Path> folder = Optional.empty();
    private TinkarService.Source source = TinkarService.Source.NONE;
    private final SelectableText freshness = SelectableText.of("");
    private Optional<TinkarService.Jdk> jdk = Optional.empty();
    private Optional<ServiceRun> run = Optional.empty();
    /** The store chosen last, by path, kept across refreshes of the list. */
    private Optional<Path> chosenStore = Optional.empty();
    private boolean filling;

    private Consumer<Path> onFolder = path -> { };
    private Consumer<Path> onJdk = path -> { };
    private Runnable onRescan = () -> { };
    private Consumer<Path> onJar = path -> { };
    private Consumer<Path> onStore = path -> { };
    private Consumer<StartRequest> onStart = request -> { };
    private Runnable onStop = () -> { };
    private Runnable onForceStop = () -> { };
    private Consumer<Path> onOpenFile = path -> { };
    private Consumer<URI> onOpenLink = uri -> { };
    private Function<KnowledgeBase, HeapSizing.Need> memoryOf = store -> new HeapSizing.Need(HeapSizing.FIXED_HEAP, 0);

    /** Builds the tab, empty until a folder, a JDK and the stores are set. */
    public ServicePane() {
        ScrollPane scroll = new ScrollPane(box);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color: transparent; -fx-background-insets: 0;");
        box.setPadding(new Insets(16, 24, 12, 24));
        setCenter(scroll);
        actions.setPadding(new Insets(10, 24, 12, 24));
        actions.setStyle("-fx-border-color: #d9d9d9; -fx-border-width: 1 0 0 0;");
        setBottom(actions);

        chooseFolder.setOnAction(event -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("Choose a tinkar-service checkout, or a folder holding its jar");
            folder.filter(path -> path.toFile().isDirectory()).ifPresent(path -> chooser.setInitialDirectory(path.toFile()));
            Optional.ofNullable(chooser.showDialog(getScene() == null ? null : getScene().getWindow()))
                    .ifPresent(chosen -> onFolder.accept(chosen.toPath()));
        });
        rescan.setTooltip(new javafx.scene.control.Tooltip("Look in the folder again, after a build"));
        rescan.setAccessibleText("Look again");
        rescan.setOnAction(event -> onRescan.run());
        chooseJdk.setOnAction(event -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("Choose a JDK's home folder");
            jdk.map(TinkarService.Jdk::home).filter(path -> path.toFile().isDirectory())
                    .ifPresent(path -> chooser.setInitialDirectory(path.toFile()));
            Optional.ofNullable(chooser.showDialog(getScene() == null ? null : getScene().getWindow()))
                    .ifPresent(chosen -> onJdk.accept(chosen.toPath()));
        });
        jar.setConverter(new StringConverter<>() {
            @Override
            public String toString(TinkarService.Build build) {
                return build == null ? "" : build.jar().getFileName() + "  ·  built " + WHEN.format(build.built());
            }

            @Override
            public TinkarService.Build fromString(String text) {
                return null;
            }
        });
        jar.getSelectionModel().selectedItemProperty().addListener((observable, was, now) -> {
            showJarFacts();
            if (now != null && !filling) {
                onJar.accept(now.jar());
            }
            updateStart();
        });

        storeSearch.setPromptText("Search");
        storeSearch.textProperty().addListener((observable, was, now) -> {
            String needle = now == null ? "" : now.strip().toLowerCase(Locale.ROOT);
            shownStores.setPredicate(store -> needle.isEmpty() || store.name().toLowerCase(Locale.ROOT).contains(needle)
                    || store.displayName().toLowerCase(Locale.ROOT).contains(needle));
        });
        storeList.setCellFactory(view -> new StoreCell());
        storeList.setPlaceholder(new Label("No store that tinkar-service opens"));
        storeList.setPrefHeight(240);
        storeList.getSelectionModel().selectedItemProperty().addListener((observable, was, now) -> {
            if (now != null && !filling) {
                chosenStore = Optional.of(now.path());
                onStore.accept(now.path());
            }
            showMemory();
            updateStart();
        });

        for (TextField port : List.of(restPort, grpcPort)) {
            port.setPrefColumnCount(6);
            port.textProperty().addListener((observable, was, now) -> updateStart());
        }
        start.setDefaultButton(true);
        start.setStyle("-fx-font-size: 14px; -fx-padding: 6 28 6 28;");
        start.setOnAction(event -> startChosen());
        ticker.setCycleCount(Animation.INDEFINITE);

        build();
    }

    // ----- what the app sets -----

    /**
     * The folder builds are found in, and the builds in it.
     *
     * @param folder the folder, or empty when none is chosen
     * @param builds the builds, newest first
     * @param chosen the jar chosen last, chosen again when it is among them; else the newest
     * @param source what the folder holds: its branch, and when its source last changed
     */
    public void setBuilds(Optional<Path> folder, List<TinkarService.Build> builds, Optional<Path> chosen,
                          TinkarService.Source source) {
        this.folder = folder;
        this.source = source;
        filling = true;
        try {
            jar.getItems().setAll(builds);
            Optional<TinkarService.Build> again = chosen.flatMap(path -> builds.stream()
                    .filter(build -> build.jar().equals(path)).findFirst());
            again.or(() -> builds.stream().findFirst()).ifPresentOrElse(jar.getSelectionModel()::select,
                    () -> jar.getSelectionModel().clearSelection());
        } finally {
            filling = false;
        }
        build();
    }

    /**
     * Looks in the folder again, as the tab does each time it is shown, so a build made meanwhile is seen.
     */
    public void rescan() {
        if (folder.isPresent()) {
            onRescan.run();
        }
    }

    /**
     * The JDK the service runs on.
     *
     * @param jdk the JDK, or empty when none of the right release was found
     */
    public void setJdk(Optional<TinkarService.Jdk> jdk) {
        this.jdk = jdk;
        build();
    }

    /**
     * The stores tinkar-service can open: every copy of every knowledge base, and the stores not managed.
     *
     * @param stores the stores
     */
    public void setStores(List<KnowledgeBase> stores) {
        filling = true;
        try {
            this.stores.setAll(stores);
            chosenStore.flatMap(path -> shownStores.stream().filter(store -> store.path().equals(path)).findFirst())
                    .ifPresentOrElse(storeList.getSelectionModel()::select, () -> storeList.getSelectionModel().clearSelection());
        } finally {
            filling = false;
        }
        showMemory();
        updateStart();
    }

    /**
     * The store chosen last, chosen again when it is listed.
     *
     * @param store the store's path
     */
    public void setChosenStore(Optional<Path> store) {
        chosenStore = store;
        setStores(List.copyOf(stores));
    }

    /**
     * The ports last used.
     *
     * @param rest the REST port
     * @param grpc the gRPC port
     */
    public void setPorts(int rest, int grpc) {
        restPort.setText(String.valueOf(rest));
        grpcPort.setText(String.valueOf(grpc));
    }

    /**
     * The service running, if the dashboard started one.
     *
     * @param run the run
     */
    public void setRun(Optional<ServiceRun> run) {
        this.run = run;
        refusals.getChildren().clear();
        if (run.isPresent()) {
            ticker.play();
        }
        showRun();
    }

    /**
     * Says why the service did not start.
     *
     * @param reasons the reasons
     */
    public void setRefusals(List<String> reasons) {
        refusals.getChildren().setAll(reasons.stream().map(reason -> styled(reason, PROBLEM)).toList());
    }

    /**
     * Says the service's state again, as when its process ends.
     */
    public void refresh() {
        showRun();
    }

    /**
     * The memory a store opens with, as Komet would open it.
     *
     * @param memoryOf the memory, by store
     */
    public void setMemoryOf(Function<KnowledgeBase, HeapSizing.Need> memoryOf) {
        this.memoryOf = memoryOf;
        showMemory();
    }

    /**
     * What choosing a folder does.
     *
     * @param action the action, given the folder
     */
    public void setOnFolder(Consumer<Path> action) {
        onFolder = action;
    }

    /**
     * What choosing a JDK does.
     *
     * @param action the action, given its home folder
     */
    public void setOnJdk(Consumer<Path> action) {
        onJdk = action;
    }

    /**
     * What looking in the folder again does.
     *
     * @param action the action
     */
    public void setOnRescan(Runnable action) {
        onRescan = action;
    }

    /**
     * What choosing a jar does.
     *
     * @param action the action, given the jar
     */
    public void setOnJar(Consumer<Path> action) {
        onJar = action;
    }

    /**
     * What choosing a store does.
     *
     * @param action the action, given the store's path
     */
    public void setOnStore(Consumer<Path> action) {
        onStore = action;
    }

    /**
     * What Start does.
     *
     * @param action the action
     */
    public void setOnStart(Consumer<StartRequest> action) {
        onStart = action;
    }

    /**
     * What Stop does.
     *
     * @param action the action
     */
    public void setOnStop(Runnable action) {
        onStop = action;
    }

    /**
     * What Force Stop does.
     *
     * @param action the action
     */
    public void setOnForceStop(Runnable action) {
        onForceStop = action;
    }

    /**
     * What Show Log does.
     *
     * @param action the action, given the log
     */
    public void setOnOpenFile(Consumer<Path> action) {
        onOpenFile = action;
    }

    /**
     * What a link to the running service does.
     *
     * @param action the action, given the link
     */
    public void setOnOpenLink(Consumer<URI> action) {
        onOpenLink = action;
    }

    // ----- layout -----

    private void build() {
        box.getChildren().clear();
        SelectableText title = SelectableText.of("Tinkar Service");
        title.setStyle("-fx-font-size: 20px; -fx-font-weight: bold;");
        box.getChildren().add(title);
        box.getChildren().add(muted("Runs a tinkar-service build on a knowledge base on this machine, for local development."
                + " Any store Komet opens can be served, managed or not."));
        box.getChildren().add(runBox);

        box.getChildren().add(heading("Build"));
        GridPane grid = new GridPane();
        // A path is as tall as its text, so it centres beside its button, level with its label.
        for (SelectableText text : List.of(folderText, jdkText, jarFacts, freshness)) {
            text.setMaxHeight(Region.USE_PREF_SIZE);
        }
        grid.setHgap(10);
        grid.setVgap(6);
        folderText.setText(folder.map(Path::toString).orElse("No folder chosen"));
        folderText.setStyle(folder.isPresent() ? "" : MUTED);
        HBox folderRow = new HBox(8, folderText, chooseFolder, rescan);
        folderRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(folderText, Priority.ALWAYS);
        chooseFolder.setMinWidth(Region.USE_PREF_SIZE);
        rescan.setMinWidth(Region.USE_PREF_SIZE);
        rescan.setDisable(folder.isEmpty());
        grid.addRow(0, label("Folder"), folderRow);
        if (folder.isPresent() && jar.getItems().isEmpty()) {
            grid.addRow(1, label("Jar"), styled("No tinkar-service jar in " + Names.of(folder.get()) + " or its target folder."
                    + " Build it with ./mvnw package, then look again.", ATTENTION));
        } else if (folder.isPresent()) {
            jar.setMaxWidth(Double.MAX_VALUE);
            grid.addRow(1, label("Jar"), jar);
            grid.add(new VBox(2, jarFacts, freshness), 1, 2);
        }
        jdkText.setText(jdk.map(found -> found.home() + "  ·  Java " + found.feature())
                .orElse("No JDK found for this build. Choose its home folder."));
        jdkText.setStyle(jdk.isPresent() ? "" : ATTENTION);
        HBox jdkRow = new HBox(8, jdkText, chooseJdk);
        jdkRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(jdkText, Priority.ALWAYS);
        chooseJdk.setMinWidth(Region.USE_PREF_SIZE);
        grid.addRow(3, label("Java"), jdkRow);
        javafx.scene.layout.ColumnConstraints labels = new javafx.scene.layout.ColumnConstraints();
        labels.setMinWidth(Region.USE_PREF_SIZE);
        javafx.scene.layout.ColumnConstraints values = new javafx.scene.layout.ColumnConstraints();
        values.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().setAll(labels, values);
        box.getChildren().add(grid);
        showJarFacts();

        box.getChildren().add(heading("Knowledge base"));
        box.getChildren().addAll(storeSearch, storeList, memory);

        Region gap = new Region();
        gap.setMinWidth(12);
        HBox startRow = new HBox(8, start, gap, label("REST port"), restPort, label("gRPC port"), grpcPort);
        startRow.setAlignment(Pos.CENTER_LEFT);
        actions.getChildren().setAll(startRow, refusals);
        showRun();
        updateStart();
    }

    private void showJarFacts() {
        TinkarService.Build build = jar.getValue();
        if (build == null) {
            jarFacts.setText("");
            freshness.setText("");
            return;
        }
        jarFacts.setText((build.version().isEmpty() ? "" : build.version() + "  ·  ")
                + source.branch().map(branch -> "branch " + branch + "  ·  ").orElse("")
                + (build.jdk() > 0 ? "for Java " + build.jdk() + "  ·  " : "") + "runs in " + build.workingFolder());
        jarFacts.setStyle(MUTED);
        if (source.lastChange().isEmpty()) {
            freshness.setText("");
        } else if (source.changedSince(build)) {
            freshness.setText("Source changed at " + WHEN.format(source.lastChange().get())
                    + ", after this jar was built. Rebuild with ./mvnw package -DskipTests to run your changes.");
            freshness.setStyle(ATTENTION);
        } else {
            freshness.setText("Up to date with your source: built after its last change, at "
                    + WHEN.format(source.lastChange().get()) + ".");
            freshness.setStyle(GOOD);
        }
    }

    private void showMemory() {
        KnowledgeBase store = storeList.getSelectionModel().getSelectedItem();
        if (store == null) {
            memory.setText("Choose the knowledge base to serve.");
            memory.setStyle(MUTED);
            return;
        }
        memory.setText("Opens with " + memoryOf.apply(store).text() + ", as Komet would open it, through "
                + TinkarService.controller(store).orElse("no provider") + ".");
        memory.setStyle(MUTED);
    }

    /** The running service's state, with Stop; or how the last one ended. */
    private void showRun() {
        runBox.getChildren().clear();
        if (run.isEmpty()) {
            ticker.stop();
            updateStart();
            return;
        }
        ServiceRun current = run.get();
        TinkarService.Launch launch = current.launch();
        ServiceRun.State state = current.state();
        String store = launch.store().displayName();
        VBox banner = new VBox(6);
        banner.setPadding(new Insets(10, 12, 10, 12));
        Hyperlink log = new Hyperlink("Show log");
        log.setOnAction(event -> onOpenFile.accept(current.log()));
        Button stop = new Button("Stop");
        stop.setOnAction(event -> onStop.run());
        switch (state) {
            case STARTING -> {
                banner.setStyle("-fx-background-color: #fff3e0;");
                banner.getChildren().add(styled("Starting on " + store + " … "
                        + seconds(Duration.between(current.started(), Instant.now())) + " so far", ATTENTION));
                banner.getChildren().add(row(stop, log));
            }
            case READY -> {
                banner.setStyle("-fx-background-color: #e8f5e9;");
                banner.getChildren().add(styled("Running on " + store + ", since "
                        + TIME.format(current.readyAt().orElse(current.started())) + "  ·  pid " + current.pid(), GOOD));
                String rest = "http://localhost:" + launch.restPort();
                Hyperlink swagger = new Hyperlink(rest + "/swagger-ui.html");
                swagger.setOnAction(event -> onOpenLink.accept(URI.create(rest + "/swagger-ui.html")));
                banner.getChildren().add(row(label("REST"), swagger, label("gRPC  localhost:" + launch.grpcPort())));
                banner.getChildren().add(row(stop, log));
            }
            case STOPPING -> {
                banner.setStyle("-fx-background-color: #fff3e0;");
                Duration waited = Duration.between(current.stopAskedAt().orElse(Instant.now()), Instant.now());
                banner.getChildren().add(styled("Stopping: flushing and closing " + store + " … "
                        + seconds(waited), ATTENTION));
                if (waited.compareTo(STOP_PATIENCE) >= 0) {
                    Button force = new Button("Force Stop");
                    force.setOnAction(event -> onForceStop.run());
                    banner.getChildren().add(row(force, log));
                    banner.getChildren().add(styled("It has not ended after " + STOP_PATIENCE.toSeconds() + " s. Force Stop ends it at once:"
                            + " its store is not closed, and may need recovery when next opened.", PROBLEM));
                } else {
                    banner.getChildren().add(row(log));
                }
            }
            case ENDED -> {
                ticker.stop();
                Optional<Integer> status = current.exitStatus();
                if (current.endedAsExpected()) {
                    banner.setStyle("-fx-background-color: #f2f2f2;");
                    banner.getChildren().add(muted("Stopped. It served " + store
                            + status.map(code -> code == 0 || code == 143 ? "" : ", and ended with status " + code).orElse("") + "."));
                } else {
                    banner.setStyle("-fx-background-color: #ffebee;");
                    banner.getChildren().add(styled("It stopped before it was ready" + status.map(code -> ", with status " + code).orElse("")
                            + ". The end of its log:", PROBLEM));
                    SelectableText tail = SelectableText.of(TinkarService.tail(current.log(), 15));
                    tail.setStyle("-fx-font-family: monospace; -fx-font-size: 11px;");
                    banner.getChildren().add(tail);
                }
                banner.getChildren().add(row(log));
            }
            default -> { }
        }
        runBox.getChildren().add(banner);
        updateStart();
    }

    private void updateStart() {
        boolean running = run.map(current -> current.state() != ServiceRun.State.ENDED).orElse(false);
        start.setDisable(running || jar.getValue() == null || jdk.isEmpty()
                || storeList.getSelectionModel().getSelectedItem() == null || port(restPort).isEmpty() || port(grpcPort).isEmpty());
        start.setText(running ? "Running" : "Start");
    }

    private void startChosen() {
        TinkarService.Build build = jar.getValue();
        KnowledgeBase store = storeList.getSelectionModel().getSelectedItem();
        Optional<Integer> rest = port(restPort);
        Optional<Integer> grpc = port(grpcPort);
        if (build == null || store == null || rest.isEmpty() || grpc.isEmpty()) {
            return;
        }
        refusals.getChildren().clear();
        onStart.accept(new StartRequest(build, store, rest.get(), grpc.get()));
    }

    private static Optional<Integer> port(TextField field) {
        try {
            int port = Integer.parseInt(field.getText().strip());
            return port > 0 && port < 65536 ? Optional.of(port) : Optional.empty();
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static String seconds(Duration duration) {
        long seconds = Math.max(0, duration.toSeconds());
        return seconds < 60 ? seconds + " s" : seconds / 60 + " min " + seconds % 60 + " s";
    }

    private static HBox row(javafx.scene.Node... nodes) {
        HBox row = new HBox(10, nodes);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private static Label label(String text) {
        Label label = new Label(text);
        label.setMinWidth(Region.USE_PREF_SIZE);
        return label;
    }

    private static SelectableText heading(String text) {
        SelectableText heading = SelectableText.of(text);
        heading.setStyle("-fx-font-weight: bold; -fx-padding: 8 0 0 0;");
        return heading;
    }

    private static SelectableText muted(String text) {
        return styled(text, MUTED);
    }

    private static SelectableText styled(String text, String style) {
        SelectableText area = SelectableText.of(text);
        area.setStyle(style);
        return area;
    }

    /** A store: its name, then what it is and where. */
    private static final class StoreCell extends ListCell<KnowledgeBase> {
        @Override
        protected void updateItem(KnowledgeBase store, boolean empty) {
            super.updateItem(store, empty);
            if (empty || store == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            Label name = new Label(store.displayName());
            name.setStyle("-fx-font-weight: bold;");
            Label facts = new Label(store.kind().label() + (store.managed() ? "" : "  ·  Not managed") + "  ·  "
                    + DashboardCommand.size(store.sizeBytes()) + "  ·  " + Names.plain(store.whereShown()));
            facts.setStyle(MUTED);
            setText(null);
            setGraphic(new VBox(2, name, facts));
        }
    }
}
