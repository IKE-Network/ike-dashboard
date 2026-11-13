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
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Where the knowledge sources of a new knowledge base are chosen (IKE-Network/ike-issues#1370; KEC, 2026-11-12:
 * "we want checkboxes for what to include, and more of an idea what each of the options are beyond just
 * the name"). Every protobuf Nexus offers is listed with a checkbox, and under its name what it is: one
 * source or a whole knowledge base, its coordinates, version, size and when it was deployed, and what it
 * was built from. The two kinds are listed apart, because they cannot be combined yet (decision 10):
 * choosing one of a kind sets the other kind aside, and says why.
 */
final class KnowledgeSourceChooser extends BorderPane {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault());
    private static final String MUTED = "-fx-text-fill: #6b6b6b;";
    private static final String NAME = "-fx-font-weight: bold;";

    private final NexusCatalog.Catalog catalog;
    private final Map<NexusCatalog.Offer, CheckBox> boxes = new LinkedHashMap<>();
    private final Map<NexusCatalog.Offer, Node> rows = new LinkedHashMap<>();
    private final TextField search = new TextField();
    private final VBox list = new VBox(6);
    private final Label sourcesNote = new Label();
    private final Label wholeNote = new Label();
    private final Label summary = new Label();
    private final Button done = new Button("Use these knowledge sources");
    private final Button cancel = new Button("Cancel");
    private Consumer<List<NexusCatalog.Offer>> onDone = offers -> { };
    private Runnable onCancel = () -> { };

    /**
     * A chooser of what Nexus offers, with the knowledge sources already chosen checked.
     *
     * @param catalog the described knowledge bases and their protobufs
     * @param chosen  the knowledge sources chosen so far
     */
    KnowledgeSourceChooser(NexusCatalog.Catalog catalog, List<KnowledgeBaseProject.KnowledgeSource> chosen) {
        this.catalog = catalog;
        search.setPromptText("Search");
        search.textProperty().addListener((observable, was, now) -> show());
        BorderPane.setMargin(search, new Insets(8));
        setTop(search);
        for (NexusCatalog.Offer offer : catalog.offers()) {
            if (!NexusCatalog.PROTOBUF_CLASSIFIERS.contains(offer.classifier())) {
                continue;
            }
            KnowledgeBaseProject.KnowledgeSource knowledgeSource = KnowledgeBaseProject.KnowledgeSource.of(offer);
            CheckBox box = new CheckBox();
            box.setAccessibleText("Include " + name(catalog, offer.coordinate()));
            box.setSelected(chosen.stream().anyMatch(was -> was.sameArtifact(knowledgeSource) && was.classifier().equals(knowledgeSource.classifier())));
            box.selectedProperty().addListener((observable, was, now) -> changed());
            boxes.put(offer, box);
            rows.put(offer, row(offer, box));
        }
        list.setPadding(new Insets(4, 12, 12, 12));
        ScrollPane scroll = new ScrollPane(list);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color: transparent; -fx-background-insets: 0;");
        setCenter(scroll);
        for (Label note : List.of(sourcesNote, wholeNote)) {
            note.setStyle(MUTED);
            note.setWrapText(true);
        }
        summary.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(summary, Priority.ALWAYS);
        done.setDefaultButton(true);
        done.setMinWidth(Region.USE_PREF_SIZE);
        cancel.setMinWidth(Region.USE_PREF_SIZE);
        done.setOnAction(event -> onDone.accept(checked()));
        cancel.setOnAction(event -> onCancel.run());
        HBox bottom = new HBox(8, summary, cancel, done);
        bottom.setAlignment(Pos.CENTER_LEFT);
        bottom.setPadding(new Insets(8, 12, 10, 12));
        setBottom(bottom);
        show();
        changed();
    }

    /**
     * What Use these knowledge sources does, given the protobufs checked, sources first, each kind in the order listed.
     *
     * @param action the action
     */
    void setOnDone(Consumer<List<NexusCatalog.Offer>> action) {
        onDone = action;
    }

    /**
     * What Cancel does.
     *
     * @param action the action
     */
    void setOnCancel(Runnable action) {
        onCancel = action;
    }

    /**
     * The protobufs checked, in the order listed.
     *
     * @return the offers
     */
    List<NexusCatalog.Offer> checked() {
        return boxes.entrySet().stream().filter(entry -> entry.getValue().isSelected()).map(Map.Entry::getKey).toList();
    }

    /**
     * The checkbox of a protobuf.
     *
     * @param artifactId the artifact it is a protobuf of
     * @return its checkbox, or empty when Nexus offers none
     */
    Optional<CheckBox> box(String artifactId) {
        return boxes.entrySet().stream().filter(entry -> entry.getKey().coordinate().artifactId().equals(artifactId))
                .map(Map.Entry::getValue).findFirst();
    }

    /** One protobuf: its checkbox, its name, what it is, its facts, and what it was built from. */
    private Node row(NexusCatalog.Offer offer, CheckBox box) {
        Label title = new Label(name(catalog, offer.coordinate()));
        title.setStyle(NAME);
        VBox lines = new VBox(2, title, line(whole(offer)
                ? "A whole knowledge base, already run through the reasoner. Use it as the base, and add your own edits."
                : "One source, read from its release. Combine it with other sources. The reasoner runs when your"
                + " knowledge base is built."));
        Optional<KnowledgeBaseDescriptor> descriptor = Optional.ofNullable(catalog.described().get(offer.coordinate()))
                .map(NexusCatalog.Described::descriptor);
        List<String> facts = new ArrayList<>(List.of(offer.coordinate().groupId() + ":" + offer.coordinate().artifactId(),
                "version " + offer.coordinate().version(), "deployed " + DAY.format(offer.updated()),
                KnowledgeBaseProject.Estimate.size(offer.sizeBytes())));
        descriptor.flatMap(KnowledgeBaseDescriptor::collection).ifPresent(collection -> facts.add("collection " + collection));
        descriptor.map(KnowledgeBaseDescriptor::access).filter(access -> !access.isBlank()).ifPresent(facts::add);
        lines.getChildren().add(line(String.join("  ·  ", facts)));
        String contents = contents(catalog, offer);
        if (!contents.isEmpty()) {
            lines.getChildren().add(line(contents));
        }
        HBox.setHgrow(lines, Priority.ALWAYS);
        // A click anywhere on the row checks it, as a click on its checkbox does.
        lines.setOnMouseClicked(event -> {
            if (!box.isDisable()) {
                box.setSelected(!box.isSelected());
            }
        });
        HBox row = new HBox(10, box, lines);
        row.setPadding(new Insets(8, 10, 8, 10));
        row.setStyle("-fx-background-color: white; -fx-background-radius: 6; -fx-border-color: #d9d9d9; -fx-border-radius: 6;");
        return row;
    }

    private static Label line(String text) {
        Label label = new Label(text);
        label.setStyle(MUTED);
        label.setWrapText(true);
        return label;
    }

    private static boolean whole(NexusCatalog.Offer offer) {
        return KnowledgeBaseProject.KnowledgeSource.of(offer).reasoned();
    }

    /** Lists the protobufs the search matches, the sources first and the whole knowledge bases after them. */
    private void show() {
        String needle = search.getText() == null ? "" : search.getText().strip().toLowerCase(Locale.ROOT);
        List<Node> shown = new ArrayList<>();
        for (boolean whole : new boolean[] {false, true}) {
            List<Node> matching = rows.entrySet().stream().filter(entry -> whole(entry.getKey()) == whole)
                    .filter(entry -> needle.isEmpty() || text(entry.getKey()).contains(needle)).map(Map.Entry::getValue).toList();
            if (matching.isEmpty()) {
                continue;
            }
            Label heading = new Label(whole ? "Whole knowledge bases" : "Single sources");
            heading.setStyle(NAME + " -fx-padding: 8 0 0 0;");
            shown.add(heading);
            shown.add(whole ? wholeNote : sourcesNote);
            shown.addAll(matching);
        }
        if (shown.isEmpty()) {
            shown.add(line(rows.isEmpty() ? "Nexus offers no protobufs yet." : "Nothing matches."));
        }
        list.getChildren().setAll(shown);
    }

    /** Everything a search can match of a protobuf: its name, its coordinates, and what it was built from. */
    private String text(NexusCatalog.Offer offer) {
        return (name(catalog, offer.coordinate()) + " " + offer.coordinate() + " " + contents(catalog, offer))
                .toLowerCase(Locale.ROOT);
    }

    /** A box was checked or cleared: the other kind is set aside or offered again, and the total is said. */
    private void changed() {
        boolean sources = checked().stream().anyMatch(offer -> !whole(offer));
        boolean wholes = checked().stream().anyMatch(KnowledgeSourceChooser::whole);
        boxes.forEach((offer, box) -> box.setDisable(whole(offer) ? sources : wholes));
        sourcesNote.setText(wholes ? "Not offered while a whole knowledge base is chosen: the two cannot be combined yet."
                : "Each is one terminology or data set. Choose any number. The IKE Starter Set is always included first.");
        wholeNote.setText(sources ? "Not offered while a source is chosen: the two cannot be combined yet."
                : "Each already contains its sources and the IKE Starter Set.");
        List<NexusCatalog.Offer> chosen = checked();
        long bytes = chosen.stream().mapToLong(NexusCatalog.Offer::sizeBytes).filter(size -> size > 0).sum();
        summary.setText(chosen.isEmpty() ? "Nothing chosen" : chosen.size() + " chosen  ·  "
                + KnowledgeBaseProject.Estimate.size(bytes));
    }

    /**
     * The name a person reads for a knowledge base: its descriptor's, else its artifactId.
     *
     * @param catalog       what Nexus offers
     * @param knowledgeBase the knowledge base
     * @return the name
     */
    static String name(NexusCatalog.Catalog catalog, Coordinate knowledgeBase) {
        NexusCatalog.Described exact = catalog.described().get(knowledgeBase);
        if (exact != null) {
            return exact.descriptor().displayName();
        }
        return catalog.described().entrySet().stream().filter(entry -> entry.getKey().sameArtifact(knowledgeBase))
                .map(entry -> entry.getValue().descriptor().displayName()).findFirst().orElse(knowledgeBase.artifactId());
    }

    /**
     * What a protobuf was made of, as its own descriptor records it, in a sentence: the release a source was
     * read from and the importer that read it, and the knowledge bases a whole one contains, each by the
     * name a person reads when Nexus describes it.
     *
     * @param catalog what Nexus offers
     * @param offer   the protobuf
     * @return the sentence, or blank when its descriptor records no inputs
     */
    static String contents(NexusCatalog.Catalog catalog, NexusCatalog.Offer offer) {
        NexusCatalog.Described entry = catalog.described().get(offer.coordinate());
        if (entry == null) {
            return "";
        }
        List<String> releases = new ArrayList<>();
        List<String> knowledgeSources = new ArrayList<>();
        for (KnowledgeBaseDescriptor.Input input : entry.descriptor().inputs()) {
            if (input.of(offer.coordinate())) {
                continue;                                // a store records the protobuf it was loaded from
            }
            Optional<Coordinate> coordinate = input.coordinate();
            String what = coordinate.map(named -> named.artifactId() + input.version().map(text -> " " + text).orElse(""))
                    .or(input::file).orElse(input.reference());
            switch (input.role()) {
                case "SOURCE" -> releases.add(what + input.importer().map(importer -> " by the " + importer + " importer").orElse(""));
                case "PB", "STORE_SEED" -> knowledgeSources.add(coordinate.map(named -> name(catalog, named)).orElse(what));
                default -> {
                    // Change sets folded in at assembly are the knowledge base's own edits, not a knowledge source to name.
                }
            }
        }
        List<String> sentences = new ArrayList<>();
        if (!releases.isEmpty()) {
            sentences.add("Read from " + String.join(", ", releases) + ".");
        }
        if (!knowledgeSources.isEmpty()) {
            sentences.add((whole(offer) ? "Contains " : "Built on ") + String.join(", ", knowledgeSources) + ".");
        }
        return String.join(" ", sentences);
    }
}
