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
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeTableCell;
import javafx.scene.control.TreeTableColumn;
import javafx.scene.control.TreeTableRow;
import javafx.scene.control.TreeTableView;
import javafx.scene.image.Image;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * The knowledge bases Nexus offers, searchable, as a tree (decision 18): each knowledge base beside its
 * icon, with its databases and protobufs to download, the knowledge bases it was built from, and those
 * built from it; only those that deploy a knowledge-base descriptor at a supported level. It is the
 * dashboard's secondary place: what is not yet in {@code ~/Solor} is found here and brought down, and
 * from then on the main window looks after it. The knowledge bases of the collections the dashboard
 * tracks are listed first, whether or not they are here, so a pipeline's knowledge bases are always at
 * hand.
 */
public final class NexusBrowser extends BorderPane {

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());
    private static final String MUTED = "-fx-text-fill: #6b6b6b;";

    private final TreeItem<KnowledgeBaseTree.Node> root = new TreeItem<>();
    private final TreeTableView<KnowledgeBaseTree.Node> table = new TreeTableView<>(root);
    private final TextField search = new TextField();
    private final Button download = new Button("Download");
    private final Label message = new Label();
    private Set<String> here = Set.of();
    private Set<String> collections = Set.of();
    private List<KnowledgeBaseTree.Node> knowledgeBases = List.of();
    private Map<Coordinate, Image> icons = Map.of();
    private Map<Coordinate, NexusCatalog.Described> described = Map.of();
    private HeapSizing.Rule heapRule = HeapSizing.Rule.DEFAULTS;
    private BiConsumer<Coordinate, String> onDownload = (coordinate, classifier) -> { };

    /** Builds the browser, empty until a catalog is set. */
    public NexusBrowser() {
        search.setPromptText("Search Nexus");
        HBox.setHgrow(search, Priority.ALWAYS);
        search.textProperty().addListener((observable, was, now) -> show());
        HBox top = new HBox(8, search);
        top.setPadding(new Insets(8));

        TreeTableColumn<KnowledgeBaseTree.Node, String> name = column("Knowledge base", 260, KnowledgeBaseTree.Node::label);
        name.setCellFactory(view -> new TreeTableCell<>() {
            @Override
            protected void updateItem(String label, boolean empty) {
                super.updateItem(label, empty);
                KnowledgeBaseTree.Node node = empty || getTableRow() == null ? null : getTableRow().getItem();
                setText(node == null ? null : label);
                setGraphic(node == null ? null : iconOf(node));
                setStyle(node != null && node.kind() == KnowledgeBaseTree.Kind.HEADING ? MUTED : "");
            }
        });
        table.getColumns().addAll(List.of(
                name,
                column("Collection", 90, node -> node.kind() == KnowledgeBaseTree.Kind.KNOWLEDGE_BASE
                        ? node.knowledgeBase().flatMap(this::collection).orElse("") : ""),
                column("What", 150, NexusBrowser::what),
                column("Version", 150, this::version),
                column("Size", 70, node -> node.offer().map(offer -> DashboardCommand.size(offer.sizeBytes())).orElse("")),
                column("Heap", 60, node -> node.offer().flatMap(this::heap).map(HeapSizing::gb).orElse("")),
                column("Deployed", 120, node -> node.offer().map(offer -> WHEN.format(offer.updated())).orElse("")),
                column("In ~/Solor", 70, node -> node.kind() == KnowledgeBaseTree.Kind.KNOWLEDGE_BASE
                        && node.knowledgeBase().filter(this::isHere).isPresent() ? "yes" : "")));
        table.setShowRoot(false);
        table.setColumnResizePolicy(TreeTableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        table.setPlaceholder(new Label("Asking Nexus …"));
        table.setRowFactory(view -> {
            TreeTableRow<KnowledgeBaseTree.Node> row = new TreeTableRow<>();
            row.setOnMouseClicked(event -> {
                KnowledgeBaseTree.Node node = row.getItem();
                if (event.getButton() != MouseButton.PRIMARY || event.getClickCount() != 2 || node == null) {
                    return;
                }
                if (node.offer().isPresent()) {
                    downloadSelected();
                } else if (node.kind() == KnowledgeBaseTree.Kind.INPUT || node.kind() == KnowledgeBaseTree.Kind.INCLUDED_IN) {
                    node.knowledgeBase().ifPresent(this::select);
                }
            });
            return row;
        });
        download.disableProperty().bind(table.getSelectionModel().selectedItemProperty().map(
                item -> item.getValue() == null || item.getValue().offer().isEmpty()).orElse(true));
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
     * @param catalog the described knowledge bases and their stores and protobufs
     * @param here    the names, in lower case, of the knowledge bases already in {@code ~/Solor}
     */
    public void setCatalog(NexusCatalog.Catalog catalog, Set<String> here) {
        described = catalog.described();
        Map<Coordinate, Image> read = new HashMap<>();
        described.forEach((coordinate, entry) -> read.put(coordinate, KnowledgeBaseIcon.load(entry.icon())));
        icons = Map.copyOf(read);
        knowledgeBases = KnowledgeBaseTree.of(catalog);
        this.here = Set.copyOf(here);
        show();
    }

    /**
     * The collections whose knowledge bases are listed first.
     *
     * @param collections the collections, as the settings name them
     */
    public void setCollections(Set<String> collections) {
        this.collections = Set.copyOf(collections);
        show();
    }

    /**
     * The collection an offer's knowledge base belongs to, as its descriptor names it.
     *
     * @param offer the offer
     * @return the collection, or empty when it names none
     */
    public Optional<String> collection(NexusCatalog.Offer offer) {
        return collection(offer.coordinate());
    }

    private Optional<String> collection(Coordinate knowledgeBase) {
        return Optional.ofNullable(described.get(knowledgeBase)).flatMap(entry -> entry.descriptor().collection());
    }

    /**
     * Whether an offer's knowledge base is in a collection the dashboard tracks.
     *
     * @param offer the offer
     * @return whether it is kept at hand
     */
    public boolean tracked(NexusCatalog.Offer offer) {
        return tracked(offer.coordinate());
    }

    private boolean tracked(Coordinate knowledgeBase) {
        NexusCatalog.Described entry = described.get(knowledgeBase);
        return entry != null && entry.descriptor().inCollection(collections);
    }

    /**
     * The rule the Heap column sizes by, as the person has it.
     *
     * @param rule the rule
     */
    public void setHeapRule(HeapSizing.Rule rule) {
        heapRule = rule;
        table.refresh();
    }

    /**
     * The heap a store needs, known before it is downloaded when its descriptor carries what its assembly
     * measured for that kind of store. A protobuf, which Komet loads into a store, has none.
     *
     * @param offer the offer
     * @return the heap, or empty when nothing was measured for it
     */
    public Optional<Long> heap(NexusCatalog.Offer offer) {
        NexusCatalog.Described entry = described.get(offer.coordinate());
        if (entry == null || !offer.store()) {
            return Optional.empty();
        }
        StoreKind kind = java.util.Arrays.stream(StoreKind.values())
                .filter(candidate -> candidate.classifier().equals(offer.classifier())).findFirst().orElse(StoreKind.UNKNOWN);
        return HeapSizing.measuredHeap(heapRule, entry.descriptor(), kind, Optional.empty());
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
     * Shows the knowledge bases the search matches, the tracked collections' first, each in the
     * catalog's order of names; a knowledge base that was open stays open.
     */
    private void show() {
        String needle = search.getText() == null ? "" : search.getText().strip().toLowerCase(Locale.ROOT);
        Set<Coordinate> expanded = new java.util.HashSet<>();
        root.getChildren().stream().filter(TreeItem::isExpanded)
                .forEach(item -> item.getValue().knowledgeBase().ifPresent(expanded::add));
        List<TreeItem<KnowledgeBaseTree.Node>> items = new ArrayList<>();
        knowledgeBases.stream()
                .filter(node -> needle.isEmpty() || node.label().toLowerCase(Locale.ROOT).contains(needle)
                        || node.knowledgeBase().map(coordinate -> coordinate.artifactId().toLowerCase(Locale.ROOT)
                        .contains(needle)).orElse(false))
                // Stable: within the tracked and the rest, the tree's own order.
                .sorted(Comparator.comparing((KnowledgeBaseTree.Node node) -> !node.knowledgeBase().map(this::tracked).orElse(false)))
                .forEach(node -> {
                    TreeItem<KnowledgeBaseTree.Node> item = item(node);
                    item.setExpanded(node.knowledgeBase().filter(expanded::contains).isPresent());
                    items.add(item);
                });
        root.getChildren().setAll(items);
        table.setPlaceholder(new Label(knowledgeBases.isEmpty() ? "Nexus offers no knowledge bases"
                : "No knowledge base on Nexus matches"));
        long kept = knowledgeBases.stream().filter(node -> node.knowledgeBase().map(this::tracked).orElse(false)).count();
        message.setText(knowledgeBases.size() + " knowledge bases on Nexus"
                + (kept == 0 ? "" : ", " + kept + " in " + String.join(", ", collections.stream().sorted().toList())
                        + " listed first"));
    }

    /** A node and everything beneath it; the headings open, so opening a knowledge base shows it all. */
    private static TreeItem<KnowledgeBaseTree.Node> item(KnowledgeBaseTree.Node node) {
        TreeItem<KnowledgeBaseTree.Node> item = new TreeItem<>(node);
        node.children().forEach(child -> item.getChildren().add(item(child)));
        item.setExpanded(node.kind() == KnowledgeBaseTree.Kind.HEADING);
        return item;
    }

    /**
     * Selects a knowledge base, opened, and scrolls to it; the search is cleared when it hides it.
     *
     * @param knowledgeBase the knowledge base, at any version Nexus describes
     * @return whether it is listed
     */
    public boolean select(Coordinate knowledgeBase) {
        Optional<TreeItem<KnowledgeBaseTree.Node>> found = topLevel(knowledgeBase);
        if (found.isEmpty() && !search.getText().isEmpty()) {
            search.clear();
            found = topLevel(knowledgeBase);
        }
        found.ifPresent(item -> {
            item.setExpanded(true);
            table.getSelectionModel().select(item);
            table.scrollTo(table.getRow(item));
        });
        return found.isPresent();
    }

    private Optional<TreeItem<KnowledgeBaseTree.Node>> topLevel(Coordinate knowledgeBase) {
        return root.getChildren().stream().filter(item -> item.getValue().knowledgeBase()
                .filter(coordinate -> coordinate.groupId().equals(knowledgeBase.groupId())
                        && coordinate.artifactId().equals(knowledgeBase.artifactId())).isPresent()).findFirst();
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
     * The knowledge bases shown, in order.
     *
     * @return their nodes, each with everything beneath it
     */
    public List<KnowledgeBaseTree.Node> knowledgeBasesShown() {
        return root.getChildren().stream().map(TreeItem::getValue).toList();
    }

    /**
     * The stores and protobufs of the knowledge bases shown, in order.
     *
     * @return the offers
     */
    public List<NexusCatalog.Offer> shown() {
        return knowledgeBasesShown().stream().flatMap(node -> node.children().stream())
                .flatMap(heading -> heading.children().stream()).flatMap(node -> node.offer().stream()).toList();
    }

    /**
     * Whether a knowledge base Nexus offers is already in {@code ~/Solor}.
     *
     * @param offer the offer
     * @return whether a copy of it is here
     */
    public boolean isHere(NexusCatalog.Offer offer) {
        return isHere(offer.coordinate());
    }

    private boolean isHere(Coordinate knowledgeBase) {
        return here.contains(knowledgeBase.artifactId().toLowerCase(Locale.ROOT));
    }

    /**
     * What Download does, given the artifact and the classifier of the store or protobuf.
     *
     * @param action the action
     */
    public void setOnDownload(BiConsumer<Coordinate, String> action) {
        onDownload = action;
    }

    private void downloadSelected() {
        TreeItem<KnowledgeBaseTree.Node> item = table.getSelectionModel().getSelectedItem();
        Optional<NexusCatalog.Offer> offer = item == null ? Optional.empty() : item.getValue().offer();
        offer.ifPresent(chosen -> {
            message.setText("Downloading " + chosen.coordinate().artifactId() + " (" + chosen.label() + ") …");
            onDownload.accept(chosen.coordinate(), chosen.classifier());
        });
    }

    /** The icon beside a knowledge base, and, smaller, beside a knowledge base it names. */
    private javafx.scene.Node iconOf(KnowledgeBaseTree.Node node) {
        return switch (node.kind()) {
            case KNOWLEDGE_BASE -> KnowledgeBaseIcon.of(node.knowledgeBase().map(icons::get), 18);
            case INPUT, INCLUDED_IN -> node.knowledgeBase().map(icons::get)
                    .map(icon -> KnowledgeBaseIcon.of(Optional.of(icon), 14)).orElse(null);
            case HEADING, OFFER -> null;
        };
    }

    private static String what(KnowledgeBaseTree.Node node) {
        return switch (node.kind()) {
            case OFFER -> node.offer().map(NexusCatalog.Offer::classifier).orElse("");
            case INPUT -> node.input().map(KnowledgeBaseTree::describe).orElse("");
            case KNOWLEDGE_BASE, HEADING, INCLUDED_IN -> "";
        };
    }

    private String version(KnowledgeBaseTree.Node node) {
        return switch (node.kind()) {
            case KNOWLEDGE_BASE -> node.knowledgeBase().map(Coordinate::version).orElse("");
            case INPUT -> node.input().flatMap(KnowledgeBaseDescriptor.Input::version).orElse("");
            case HEADING, OFFER, INCLUDED_IN -> "";
        };
    }

    private static TreeTableColumn<KnowledgeBaseTree.Node, String> column(String title, double width,
                                                                         Function<KnowledgeBaseTree.Node, String> value) {
        TreeTableColumn<KnowledgeBaseTree.Node, String> column = new TreeTableColumn<>(title);
        column.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue() == null || cell.getValue().getValue() == null
                ? "" : value.apply(cell.getValue().getValue())));
        column.setPrefWidth(width);
        return column;
    }
}
