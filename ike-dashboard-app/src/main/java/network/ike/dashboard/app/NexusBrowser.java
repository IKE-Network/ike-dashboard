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

import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * The knowledge bases Nexus offers, searchable, each store a row to download beside the knowledge base's
 * icon; only those that deploy a knowledge-base descriptor at a supported level. It is the dashboard's
 * secondary place: what is not yet in {@code ~/Solor} is found here and brought down, and from then on
 * the main window looks after it.
 */
public final class NexusBrowser extends BorderPane {

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    private final ObservableList<NexusCatalog.Offer> offers = FXCollections.observableArrayList();
    private final FilteredList<NexusCatalog.Offer> shown = new FilteredList<>(offers);
    private final TableView<NexusCatalog.Offer> table = new TableView<>(shown);
    private final TextField search = new TextField();
    private final Button download = new Button("Download");
    private final Label message = new Label();
    private Set<String> here = Set.of();
    private Map<Coordinate, Image> icons = Map.of();
    private Map<Coordinate, NexusCatalog.Described> described = Map.of();
    private BiConsumer<Coordinate, String> onDownload = (coordinate, classifier) -> { };

    /** Builds the browser, empty until offers are set. */
    public NexusBrowser() {
        search.setPromptText("Search Nexus");
        HBox.setHgrow(search, Priority.ALWAYS);
        search.textProperty().addListener((observable, was, now) -> {
            String needle = now == null ? "" : now.strip().toLowerCase(Locale.ROOT);
            shown.setPredicate(offer -> needle.isEmpty()
                    || offer.coordinate().artifactId().toLowerCase(Locale.ROOT).contains(needle)
                    || displayName(offer).toLowerCase(Locale.ROOT).contains(needle));
        });
        HBox top = new HBox(8, search);
        top.setPadding(new Insets(8));

        TableColumn<NexusCatalog.Offer, String> name = column("Knowledge base", 220, this::displayName);
        name.setCellFactory(view -> new TableCell<>() {
            @Override
            protected void updateItem(String displayName, boolean empty) {
                super.updateItem(displayName, empty);
                NexusCatalog.Offer offer = empty || getTableRow() == null ? null : getTableRow().getItem();
                setText(offer == null ? null : displayName);
                setGraphic(offer == null ? null : KnowledgeBaseIcon.of(Optional.ofNullable(icons.get(offer.coordinate())), 18));
            }
        });
        table.getColumns().addAll(List.of(
                name,
                column("Layout", 110, NexusCatalog.Offer::label),
                column("Size", 80, offer -> DashboardCommand.size(offer.sizeBytes())),
                column("Deployed", 130, offer -> WHEN.format(offer.updated())),
                column("In ~/Solor", 80, offer -> isHere(offer) ? "yes" : "")));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        table.setPlaceholder(new Label("Asking Nexus …"));
        table.setRowFactory(view -> {
            TableRow<NexusCatalog.Offer> row = new TableRow<>();
            row.setOnMouseClicked(event -> {
                if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2 && row.getItem() != null) {
                    downloadSelected();
                }
            });
            return row;
        });
        download.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        download.setDefaultButton(true);
        download.setOnAction(event -> downloadSelected());

        message.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(message, Priority.ALWAYS);
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox bottom = new HBox(8, message, gap, download);
        bottom.setAlignment(Pos.CENTER_LEFT);
        bottom.setPadding(new Insets(8));

        setTop(top);
        setCenter(table);
        setBottom(bottom);
    }

    /**
     * Shows what Nexus offers of the knowledge bases it describes, each beside its icon.
     *
     * @param catalog the described knowledge bases and their stores
     * @param here    the names, in lower case, of the knowledge bases already in {@code ~/Solor}
     */
    public void setCatalog(NexusCatalog.Catalog catalog, Set<String> here) {
        described = catalog.described();
        Map<Coordinate, Image> read = new HashMap<>();
        described.forEach((coordinate, entry) -> read.put(coordinate, KnowledgeBaseIcon.load(entry.icon())));
        icons = Map.copyOf(read);
        setOffers(catalog.offers(), here);
    }

    /**
     * The name a person reads for an offer: its descriptor's display name, else its artifactId.
     *
     * @param offer the offer
     * @return the name
     */
    public String displayName(NexusCatalog.Offer offer) {
        NexusCatalog.Described entry = described.get(offer.coordinate());
        return entry == null ? offer.coordinate().artifactId() : entry.descriptor().displayName();
    }

    /**
     * Shows offers.
     *
     * @param offers the offers
     * @param here   the names, in lower case, of the knowledge bases already in {@code ~/Solor}
     */
    private void setOffers(List<NexusCatalog.Offer> offers, Set<String> here) {
        this.here = Set.copyOf(here);
        this.offers.setAll(offers);
        table.setPlaceholder(new Label("Nexus offers no knowledge bases"));
        table.refresh();
        message.setText(offers.stream().map(offer -> offer.coordinate().artifactId()).distinct().count()
                + " knowledge bases on Nexus");
    }

    /**
     * Whether an offer shows its knowledge base's icon.
     *
     * @param offer the offer
     * @return whether its icon was read
     */
    public boolean hasIcon(NexusCatalog.Offer offer) {
        Image icon = icons.get(offer.coordinate());
        return icon != null && !icon.isError();
    }

    /**
     * Says why there is nothing to show.
     *
     * @param text the reason
     */
    public void setProblem(String text) {
        table.setPlaceholder(new Label(text));
        message.setText(text);
    }

    /**
     * The offers shown, in order.
     *
     * @return the offers
     */
    public List<NexusCatalog.Offer> shown() {
        return List.copyOf(shown);
    }

    /**
     * Whether a knowledge base Nexus offers is already in {@code ~/Solor}.
     *
     * @param offer the offer
     * @return whether a copy of it is here
     */
    public boolean isHere(NexusCatalog.Offer offer) {
        return here.contains(offer.coordinate().artifactId().toLowerCase(Locale.ROOT));
    }

    /**
     * What Download does, given the artifact and the classifier of the store.
     *
     * @param action the action
     */
    public void setOnDownload(BiConsumer<Coordinate, String> action) {
        onDownload = action;
    }

    private void downloadSelected() {
        NexusCatalog.Offer offer = table.getSelectionModel().getSelectedItem();
        if (offer != null) {
            message.setText("Downloading " + offer.coordinate().artifactId() + " (" + offer.label() + ") …");
            onDownload.accept(offer.coordinate(), offer.classifier());
        }
    }

    private static TableColumn<NexusCatalog.Offer, String> column(String title, double width,
                                                                 Function<NexusCatalog.Offer, String> value) {
        TableColumn<NexusCatalog.Offer, String> column = new TableColumn<>(title);
        column.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(value.apply(cell.getValue())));
        column.setPrefWidth(width);
        return column;
    }
}
