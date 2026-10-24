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
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.util.Subscription;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * The dashboard's window content: the knowledge bases in {@code ~/Solor}, and the Komet versions
 * installed. It holds no IO of its own; {@link DashboardApp} supplies the data and the actions.
 */
public final class DashboardView extends BorderPane {

    private static final DateTimeFormatter ASSEMBLED = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    /** One knowledge base and what the newer-build check last found for it. */
    public static final class Row {
        private final KnowledgeBase knowledgeBase;
        private final ObjectProperty<BuildStatus> status = new SimpleObjectProperty<>(BuildStatus.UNCHECKED);

        Row(KnowledgeBase knowledgeBase) {
            this.knowledgeBase = knowledgeBase;
        }

        /**
         * The knowledge base.
         *
         * @return the knowledge base
         */
        public KnowledgeBase knowledgeBase() {
            return knowledgeBase;
        }

        /**
         * What the newer-build check last found.
         *
         * @return the status property
         */
        public ObjectProperty<BuildStatus> statusProperty() {
            return status;
        }
    }

    private final ObservableList<Row> rows = FXCollections.observableArrayList();
    private final ObservableList<KometInstallation> komets = FXCollections.observableArrayList();
    private final TableView<Row> knowledgeBaseTable = new TableView<>(rows);
    private final TableView<KometInstallation> kometTable = new TableView<>(komets);
    private final ComboBox<KometInstallation> kometChoice = new ComboBox<>(komets);
    private final Button refresh = new Button("Refresh");
    private final Button checkNexus = new Button("Check Nexus");
    private final Button open = new Button("Open in Komet");
    private final Label message = new Label();
    private final Subscription subscriptions;

    /** Builds the view, empty until data is set. */
    public DashboardView() {
        knowledgeBaseTable.getColumns().addAll(List.of(
                column("Knowledge base", 260, row -> row.knowledgeBase().name()),
                column("Kind", 150, row -> row.knowledgeBase().kind().label()),
                column("Size", 90, row -> DashboardCommand.size(row.knowledgeBase().sizeBytes())),
                column("Build", 300, row -> row.knowledgeBase().coordinate().map(Coordinate::toString)
                        .orElse("unidentified")),
                column("Assembled", 130, row -> row.knowledgeBase().assembledAt().map(ASSEMBLED::format).orElse("")),
                statusColumn()));
        knowledgeBaseTable.setPlaceholder(new Label("No knowledge bases found"));
        knowledgeBaseTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

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

        kometChoice.setPromptText("Komet version");
        kometChoice.setCellFactory(list -> new KometCell());
        kometChoice.setButtonCell(new KometCell());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox toolbar = new HBox(8, refresh, checkNexus, spacer, kometChoice, open);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(8));

        BorderPane knowledgeBases = new BorderPane(knowledgeBaseTable);
        knowledgeBases.setTop(toolbar);

        Tab knowledgeBaseTab = new Tab("Knowledge bases", knowledgeBases);
        Tab kometTab = new Tab("Komet versions", kometTable);
        TabPane tabs = new TabPane(knowledgeBaseTab, kometTab);
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        setCenter(tabs);

        message.setPadding(new Insets(4, 8, 6, 8));
        message.setMaxWidth(Double.MAX_VALUE);
        setBottom(message);

        // Opening needs a store Komet opens and a Komet to open it with.
        ObservableValue<Boolean> noStore = knowledgeBaseTable.getSelectionModel().selectedItemProperty()
                .map(row -> !row.knowledgeBase().kind().opensInKomet())
                .orElse(true);
        ObservableValue<Boolean> noKomet = kometChoice.valueProperty().map(komet -> false).orElse(true);
        open.disableProperty().bind(Bindings.createBooleanBinding(() -> noStore.getValue() || noKomet.getValue(),
                noStore, noKomet));
        subscriptions = komets.subscribe(() -> {
            if (kometChoice.getValue() == null && !komets.isEmpty()) {
                kometChoice.setValue(komets.getFirst());
            }
        });
    }

    /**
     * Shows the knowledge bases.
     *
     * @param knowledgeBases the knowledge bases
     */
    public void setKnowledgeBases(List<KnowledgeBase> knowledgeBases) {
        rows.setAll(knowledgeBases.stream().map(Row::new).toList());
    }

    /**
     * Shows the Komet versions.
     *
     * @param installations the Komet versions, newest first
     */
    public void setKomets(List<KometInstallation> installations) {
        komets.setAll(installations);
    }

    /**
     * The knowledge bases shown.
     *
     * @return the rows
     */
    public List<Row> rows() {
        return List.copyOf(rows);
    }

    /**
     * Selects a knowledge base by name.
     *
     * @param name the knowledge base's name
     */
    public void select(String name) {
        rows.stream().filter(row -> row.knowledgeBase().name().equals(name)).findFirst()
                .ifPresent(row -> knowledgeBaseTable.getSelectionModel().select(row));
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
     * What Open in Komet does, given the chosen Komet and knowledge base.
     *
     * @param action the action
     */
    public void setOnOpen(BiConsumer<KometInstallation, KnowledgeBase> action) {
        open.setOnAction(event -> {
            Row row = knowledgeBaseTable.getSelectionModel().getSelectedItem();
            KometInstallation komet = kometChoice.getValue();
            if (row != null && komet != null) {
                action.accept(komet, row.knowledgeBase());
            }
        });
    }

    /** Releases the view's subscriptions. */
    public void dispose() {
        subscriptions.unsubscribe();
    }

    private static TableColumn<Row, String> column(String title, double width, Function<Row, String> value) {
        TableColumn<Row, String> column = new TableColumn<>(title);
        column.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(value.apply(cell.getValue())));
        column.setPrefWidth(width);
        return column;
    }

    private static TableColumn<Row, BuildStatus> statusColumn() {
        TableColumn<Row, BuildStatus> column = new TableColumn<>("Nexus");
        column.setCellValueFactory(cell -> cell.getValue().statusProperty());
        column.setPrefWidth(160);
        column.setCellFactory(table -> new TableCell<>() {
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

    private static final class KometCell extends ListCell<KometInstallation> {
        @Override
        protected void updateItem(KometInstallation komet, boolean empty) {
            super.updateItem(komet, empty);
            setText(empty || komet == null ? null : komet.version() + "  " + komet.name());
        }
    }
}
