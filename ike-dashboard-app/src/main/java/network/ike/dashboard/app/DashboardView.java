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

import javafx.beans.binding.Bindings;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.value.ObservableValue;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeTableCell;
import javafx.scene.control.TreeTableColumn;
import javafx.scene.control.TreeTableRow;
import javafx.scene.control.TreeTableView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The dashboard's window content. Knowledge bases show as one row each: the row is the newest copy
 * Komet can open, in bold, with the Komet that opens it; older copies, and a newer build Nexus has,
 * fold beneath it. It holds no IO of its own; {@link DashboardApp} supplies the data and the actions.
 */
public final class DashboardView extends BorderPane {

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    /** A row of the tree: a knowledge base, an older copy of it, or a newer build Nexus has. */
    public static final class Entry {

        /** The kinds of row. */
        public enum Kind {
            /** A knowledge base: its newest copy, or its only entry. */
            FAMILY,
            /** An older copy of a knowledge base, in {@code ~/Solor}. */
            COPY,
            /** A newer build on Nexus, not downloaded. */
            AVAILABLE
        }

        private final Kind kind;
        private final KnowledgeBaseFamily family;
        private final KnowledgeBase copy;
        private final SnapshotMetadata.Deploy deploy;
        private final ObjectProperty<BuildStatus> status = new SimpleObjectProperty<>(BuildStatus.UNCHECKED);

        private Entry(Kind kind, KnowledgeBaseFamily family, KnowledgeBase copy, SnapshotMetadata.Deploy deploy) {
            this.kind = kind;
            this.family = family;
            this.copy = copy;
            this.deploy = deploy;
        }

        /**
         * The kind of row.
         *
         * @return the kind
         */
        public Kind kind() {
            return kind;
        }

        /**
         * The knowledge base the row belongs to.
         *
         * @return the family
         */
        public KnowledgeBaseFamily family() {
            return family;
        }

        /**
         * The copy in {@code ~/Solor} the row stands for.
         *
         * @return the copy; empty for a build on Nexus
         */
        public Optional<KnowledgeBase> copy() {
            return Optional.ofNullable(copy);
        }

        /**
         * The newer build on Nexus the row stands for.
         *
         * @return the deploy; empty for a copy in {@code ~/Solor}
         */
        public Optional<SnapshotMetadata.Deploy> deploy() {
            return Optional.ofNullable(deploy);
        }

        /**
         * What the newer-build check last found for the knowledge base; set on its family row.
         *
         * @return the status property
         */
        public ObjectProperty<BuildStatus> statusProperty() {
            return status;
        }

        boolean latest() {
            return copy != null && family.latest().map(latest -> latest == copy).orElse(false);
        }
    }

    private final TreeItem<Entry> root = new TreeItem<>();
    private final TreeTableView<Entry> knowledgeBaseTree = new TreeTableView<>(root);
    private final ObservableList<KometInstallation> komets = FXCollections.observableArrayList();
    private final TableView<KometInstallation> kometTable = new TableView<>(komets);
    private final Button refresh = new Button("Refresh");
    private final Button checkNexus = new Button("Check Nexus");
    private final Button download = new Button("Download");
    private final Button open = new Button("Open in Komet");
    private final Label message = new Label();
    private BiConsumer<KometInstallation, KnowledgeBase> onOpen = (komet, knowledgeBase) -> { };
    private Consumer<Entry> onDownload = entry -> { };

    /** Builds the view, empty until data is set. */
    public DashboardView() {
        knowledgeBaseTree.setShowRoot(false);
        knowledgeBaseTree.getColumns().addAll(List.of(
                nameColumn(),
                column("Kind", 140, entry -> entry.copy().map(copy -> copy.kind().label()).orElse("")),
                column("Size", 80, entry -> entry.copy().map(copy -> DashboardCommand.size(copy.sizeBytes())).orElse("")),
                column("Build", 330, DashboardView::build),
                column("Date", 120, entry -> entry.copy().map(copy -> WHEN.format(copy.recency()))
                        .or(() -> entry.deploy().map(deploy -> WHEN.format(deploy.updated()))).orElse("")),
                statusColumn(),
                column("Opens with", 170, this::opensWith)));
        knowledgeBaseTree.setPlaceholder(new Label("No knowledge bases found"));
        knowledgeBaseTree.setColumnResizePolicy(TreeTableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        knowledgeBaseTree.setRowFactory(tree -> new EntryRow());

        TableColumn<KometInstallation, String> version = new TableColumn<>("Version");
        version.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue().version()));
        version.setPrefWidth(140);
        TableColumn<KometInstallation, String> name = new TableColumn<>("Komet");
        name.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue().name()));
        name.setPrefWidth(300);
        TableColumn<KometInstallation, String> location = new TableColumn<>("Location");
        location.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue().bundle().toString()));
        kometTable.getColumns().addAll(List.of(version, name, location));
        kometTable.setPlaceholder(new Label("No Komet installed"));
        kometTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox toolbar = new HBox(8, refresh, checkNexus, spacer, download, open);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(8));

        BorderPane knowledgeBases = new BorderPane(knowledgeBaseTree);
        knowledgeBases.setTop(toolbar);
        TabPane tabs = new TabPane(new Tab("Knowledge bases", knowledgeBases), new Tab("Komet versions", kometTable));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        setCenter(tabs);

        message.setPadding(new Insets(4, 8, 6, 8));
        message.setMaxWidth(Double.MAX_VALUE);
        setBottom(message);

        ObservableValue<Entry> selected = knowledgeBaseTree.getSelectionModel().selectedItemProperty().map(TreeItem::getValue);
        ObservableValue<Boolean> cannotOpen = selected.map(entry -> launcher(entry).isEmpty()).orElse(true);
        open.disableProperty().bind(Bindings.createBooleanBinding(cannotOpen::getValue, cannotOpen, komets));
        download.disableProperty().bind(selected.map(entry -> entry.kind() != Entry.Kind.AVAILABLE).orElse(true));
        open.setOnAction(event -> selectedEntry().ifPresent(entry -> launcher(entry)
                .ifPresent(komet -> onOpen.accept(komet, entry.copy().orElseThrow()))));
        download.setOnAction(event -> selectedEntry().ifPresent(onDownload));
    }

    /**
     * Shows the knowledge bases, one row each.
     *
     * @param families the knowledge bases
     */
    public void setFamilies(List<KnowledgeBaseFamily> families) {
        root.getChildren().setAll(families.stream().map(DashboardView::familyItem).toList());
    }

    /**
     * Shows the Komet versions.
     *
     * @param installations the Komet versions, newest first
     */
    public void setKomets(List<KometInstallation> installations) {
        komets.setAll(installations);
        knowledgeBaseTree.refresh();
    }

    /**
     * The knowledge-base rows.
     *
     * @return one entry per knowledge base
     */
    public List<Entry> families() {
        return root.getChildren().stream().map(TreeItem::getValue).toList();
    }

    /**
     * Records what the newer-build check found for a knowledge base; a newer build appears beneath it.
     *
     * @param family the knowledge base's row
     * @param status what the check found
     */
    public void setStatus(Entry family, BuildStatus status) {
        family.statusProperty().set(status);
        root.getChildren().stream().filter(item -> item.getValue() == family).findFirst().ifPresent(item -> {
            item.getChildren().removeIf(child -> child.getValue().kind() == Entry.Kind.AVAILABLE);
            if (status.state() == BuildStatus.State.NEWER && status.newest().isPresent()) {
                item.getChildren().addFirst(new TreeItem<>(new Entry(Entry.Kind.AVAILABLE, family.family(), null,
                        status.newest().get())));
                item.setExpanded(true);
            }
        });
    }

    /**
     * Selects a knowledge base's row by name.
     *
     * @param name the knowledge base's name
     */
    public void select(String name) {
        root.getChildren().stream().filter(item -> item.getValue().family().name().equals(name)).findFirst()
                .ifPresent(item -> knowledgeBaseTree.getSelectionModel().select(item));
    }

    /**
     * Expands or folds every knowledge base.
     *
     * @param expanded whether to show the older copies
     */
    public void setAllExpanded(boolean expanded) {
        root.getChildren().forEach(item -> item.setExpanded(expanded));
    }

    /**
     * Shows a line at the foot of the window.
     *
     * @param text the line
     */
    public void setMessage(String text) {
        message.setText(text);
    }

    /**
     * What Refresh does.
     *
     * @param action the action
     */
    public void setOnRefresh(Runnable action) {
        refresh.setOnAction(event -> action.run());
    }

    /**
     * What Check Nexus does.
     *
     * @param action the action
     */
    public void setOnCheckNexus(Runnable action) {
        checkNexus.setOnAction(event -> action.run());
    }

    /**
     * What opening does, given the Komet and the copy.
     *
     * @param action the action
     */
    public void setOnOpen(BiConsumer<KometInstallation, KnowledgeBase> action) {
        onOpen = action;
    }

    /**
     * What Download does, given the row of a newer build.
     *
     * @param action the action
     */
    public void setOnDownload(Consumer<Entry> action) {
        onDownload = action;
    }

    private Optional<Entry> selectedEntry() {
        return Optional.ofNullable(knowledgeBaseTree.getSelectionModel().getSelectedItem()).map(TreeItem::getValue);
    }

    private Optional<KometInstallation> launcher(Entry entry) {
        return entry.copy().flatMap(copy -> LauncherChoice.choose(komets, copy));
    }

    private String opensWith(Entry entry) {
        if (entry.copy().isEmpty() || !entry.copy().get().kind().opensInKomet()) {
            return "";
        }
        return launcher(entry).map(komet -> "Komet " + komet.version())
                .orElseGet(() -> LauncherChoice.requirement(entry.copy().get()).map(need -> "needs " + need)
                        .orElse("no Komet installed"));
    }

    private static String build(Entry entry) {
        if (entry.kind() == Entry.Kind.AVAILABLE) {
            return entry.deploy().map(SnapshotMetadata.Deploy::value).orElse("");
        }
        KnowledgeBase copy = entry.copy().orElseThrow();
        String coordinate = copy.coordinate().map(Coordinate::toString).orElse("unidentified");
        return copy.resolvedVersion().map(resolved -> coordinate + " (" + resolved + ")").orElse(coordinate);
    }

    private static TreeItem<Entry> familyItem(KnowledgeBaseFamily family) {
        KnowledgeBase shown = family.latest().orElse(family.members().getFirst());
        TreeItem<Entry> item = new TreeItem<>(new Entry(Entry.Kind.FAMILY, family, shown, null));
        family.members().stream().filter(member -> member != shown)
                .forEach(member -> item.getChildren().add(new TreeItem<>(new Entry(Entry.Kind.COPY, family, member, null))));
        return item;
    }

    private static TreeTableColumn<Entry, String> column(String title, double width, Function<Entry, String> value) {
        TreeTableColumn<Entry, String> column = new TreeTableColumn<>(title);
        column.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(value.apply(cell.getValue().getValue())));
        column.setPrefWidth(width);
        return column;
    }

    private static TreeTableColumn<Entry, String> nameColumn() {
        TreeTableColumn<Entry, String> column = column("Knowledge base", 280, entry -> switch (entry.kind()) {
            case FAMILY -> entry.family().name();
            case COPY -> entry.copy().map(KnowledgeBase::name).orElse("");
            case AVAILABLE -> "newer build on Nexus";
        });
        column.setCellFactory(table -> new TreeTableCell<>() {
            @Override
            protected void updateItem(String text, boolean empty) {
                super.updateItem(text, empty);
                Entry entry = empty || getTableRow() == null ? null : getTableRow().getItem();
                if (entry == null) {
                    setText(null);
                    setTooltip(null);
                    setStyle("");
                    return;
                }
                long more = entry.family().members().size() - 1;
                setText(entry.kind() == Entry.Kind.FAMILY && more > 0 ? text + "   (" + more + " more)" : text);
                setTooltip(entry.copy().map(copy -> new Tooltip(copy.path().toString())).orElse(null));
                setStyle(entry.kind() == Entry.Kind.AVAILABLE ? "-fx-font-style: italic;"
                        : entry.latest() ? "-fx-font-weight: bold;" : "");
            }
        });
        return column;
    }

    private static TreeTableColumn<Entry, BuildStatus> statusColumn() {
        TreeTableColumn<Entry, BuildStatus> column = new TreeTableColumn<>("Nexus");
        column.setCellValueFactory(cell -> cell.getValue().getValue().kind() == Entry.Kind.FAMILY
                ? cell.getValue().getValue().statusProperty() : new ReadOnlyObjectWrapper<>(null));
        column.setPrefWidth(120);
        column.setCellFactory(table -> new TreeTableCell<>() {
            @Override
            protected void updateItem(BuildStatus status, boolean empty) {
                super.updateItem(status, empty);
                if (empty || status == null) {
                    setText(null);
                    setTooltip(null);
                    setStyle("");
                    return;
                }
                setText(status.state().label());
                setTooltip(status.detail().isEmpty() ? null : new Tooltip(status.detail()));
                setStyle(switch (status.state()) {
                    case NEWER -> "-fx-text-fill: #b35900; -fx-font-weight: bold;";
                    case CURRENT -> "-fx-text-fill: #2e7d32;";
                    case ERROR -> "-fx-text-fill: #c62828;";
                    default -> "-fx-text-fill: #616161;";
                });
            }
        });
        return column;
    }

    /** A row with an "Open with" menu of the Komets that read its copy. */
    private final class EntryRow extends TreeTableRow<Entry> {
        @Override
        protected void updateItem(Entry entry, boolean empty) {
            super.updateItem(entry, empty);
            if (empty || entry == null || entry.copy().isEmpty() || !entry.copy().get().kind().opensInKomet()) {
                setContextMenu(null);
                return;
            }
            KnowledgeBase copy = entry.copy().get();
            Menu openWith = new Menu("Open with");
            komets.stream().filter(komet -> LauncherChoice.reads(komet, copy)).forEach(komet -> {
                MenuItem item = new MenuItem("Komet " + komet.version() + "  " + komet.name());
                item.setOnAction(event -> onOpen.accept(komet, copy));
                openWith.getItems().add(item);
            });
            openWith.setDisable(openWith.getItems().isEmpty());
            setContextMenu(new ContextMenu(openWith));
        }
    }
}
