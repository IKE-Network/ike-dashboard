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
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The heap as the dashboard shows it: a store's memory line, the question before a launch that does not
 * fit, and the Memory window where the person overrides the rule or one store's heap. Holds no IO; the
 * plans come from {@link DashboardApp}.
 */
final class HeapView {

    private static final String MUTED = "-fx-text-fill: #6b6b6b;";
    private static final String ATTENTION = "-fx-text-fill: #b35900;";
    private static final String PROBLEM = "-fx-text-fill: #c62828;";

    private HeapView() {
    }

    /**
     * A store's memory line, such as {@code Memory  20 GB heap · fits beside what is running}, with the
     * rule's working in its tooltip.
     *
     * @param plan  what the store is launched with
     * @param komet the Komet it opens in
     * @param store the store
     * @return the label
     */
    static Label line(HeapSizing.Plan plan, KometInstallation komet, KnowledgeBase store) {
        Label line = new Label("Memory  " + summary(plan, komet));
        line.setWrapText(true);
        line.setStyle(switch (plan.fit()) {
            case FITS -> plan.source() == HeapSizing.Source.FIXED_BY_KOMET ? ATTENTION : MUTED;
            case NOT_BESIDE_RUNNING -> ATTENTION;
            case EXCEEDS_MACHINE -> PROBLEM;
        });
        line.setTooltip(new Tooltip(working(plan, store)));
        return line;
    }

    /**
     * What the memory line says.
     *
     * @param plan  what the store is launched with
     * @param komet the Komet it opens in
     * @return the text
     */
    static String summary(HeapSizing.Plan plan, KometInstallation komet) {
        String need = plan.need().text();
        String fit = switch (plan.fit()) {
            case FITS -> "fits beside what is running";
            case NOT_BESIDE_RUNNING -> HeapSizing.gb(plan.available()) + " is free beside " + running(plan.machine());
            case EXCEEDS_MACHINE -> "this machine gives Komet at most " + HeapSizing.gb(plan.ceiling());
        };
        return switch (plan.source()) {
            case RULE -> need + "  ·  " + fit;
            case OVERRIDE -> need + ", set for this store  ·  " + fit;
            case FIXED_BY_KOMET -> need + ", fixed by Komet " + komet.version() + "; the rule asks "
                    + HeapSizing.gb(plan.computed().heap()) + "  ·  " + fit;
        };
    }

    /**
     * How the need was worked out, for a tooltip.
     *
     * @param plan the plan
     * @return the text
     */
    static String working(HeapSizing.Plan plan, KnowledgeBase store) {
        List<String> lines = new ArrayList<>();
        lines.add(DashboardCommand.size(plan.data()) + " of data, Lucene's index left out.");
        lines.add(store.statedAxiomBytes()
                .map(bytes -> DashboardCommand.size(bytes) + " of stated axioms, which the reasoner's room follows.")
                .orElse("The store does not say its stated axioms' size, so no room for the reasoner is added."));
        lines.add("The rule asks " + plan.computed().text() + ".");
        if (plan.source() == HeapSizing.Source.FIXED_BY_KOMET) {
            lines.add("This Komet starts at 42 GB whatever its store needs; a Komet built after it takes the rule's.");
        }
        HeapSizing.Machine machine = plan.machine();
        lines.add("This machine has " + HeapSizing.gb(machine.physical()) + " and gives Komet at most "
                + HeapSizing.gb(plan.ceiling()) + "; " + HeapSizing.gb(plan.available()) + " is free beside "
                + running(machine) + ".");
        machine.running().forEach(komet -> lines.add("    " + komet.text()));
        lines.add("⋯ → Memory… changes the rule, or this store's heap.");
        return String.join("\n", lines);
    }

    private static String running(HeapSizing.Machine machine) {
        int count = machine.running().size();
        return count == 0 ? "no Komet running" : count == 1 ? "the Komet running" : "the " + count + " Komets running";
    }

    /**
     * Asks before a launch that does not fit beside what is running, or on this machine at all.
     *
     * @param owner the window the question belongs to, or {@code null}
     * @param store the store
     * @param komet the Komet
     * @param plan  what the store would be launched with
     * @return the need to launch with, or empty to launch nothing
     */
    static Optional<HeapSizing.Need> confirm(Window owner, KnowledgeBase store, KometInstallation komet,
                                             HeapSizing.Plan plan) {
        if (plan.fit() == HeapSizing.Fit.FITS) {
            return Optional.of(plan.need());
        }
        boolean fixed = plan.source() == HeapSizing.Source.FIXED_BY_KOMET;
        ButtonType full = new ButtonType("Open with " + HeapSizing.gb(plan.need().total()), ButtonBar.ButtonData.OK_DONE);
        HeapSizing.Need smaller = plan.largestThatFits(plan.fit() == HeapSizing.Fit.EXCEEDS_MACHINE
                ? plan.ceiling() : plan.available());
        ButtonType reduced = new ButtonType("Open with " + HeapSizing.gb(smaller.total()), ButtonBar.ButtonData.OTHER);
        List<ButtonType> buttons = new ArrayList<>();
        buttons.add(ButtonType.CANCEL);
        // A spined store holds all of its data on the heap, so it must fit: only Rocks is offered less.
        boolean shrinks = store.kind() == StoreKind.ROCKS;
        if (!fixed && shrinks && smaller.heap() >= HeapSizing.GIB && smaller.total() < plan.need().total()) {
            buttons.add(reduced);
        }
        if (plan.fit() == HeapSizing.Fit.NOT_BESIDE_RUNNING || fixed) {
            buttons.add(full);
        }
        Alert alert = new Alert(plan.fit() == HeapSizing.Fit.EXCEEDS_MACHINE ? Alert.AlertType.WARNING
                : Alert.AlertType.CONFIRMATION, "", buttons.toArray(ButtonType[]::new));
        if (owner != null) {
            alert.initOwner(owner);
        }
        alert.setTitle("Memory");
        alert.setHeaderText(store.displayName() + " needs " + HeapSizing.gb(plan.need().total())
                + (plan.fit() == HeapSizing.Fit.EXCEEDS_MACHINE
                ? ", more than this machine gives Komet (" + HeapSizing.gb(plan.ceiling()) + ")."
                : "; " + HeapSizing.gb(plan.available()) + " is free beside what is running."));
        StringBuilder content = new StringBuilder();
        if (plan.fit() == HeapSizing.Fit.NOT_BESIDE_RUNNING) {
            content.append("Closing one of these makes room:\n").append(plan.machine().running().stream()
                    .map(running -> "    " + running.text()).collect(Collectors.joining("\n"))).append("\n\n");
            content.append("Opened anyway, the Komets together can claim more memory than the machine has, and macOS "
                    + "swaps or one of them runs out.");
        } else if (shrinks) {
            content.append("Rocks keeps its data off the heap, so a smaller heap may open and browse it, and run out "
                    + "under a heavier load.");
        } else {
            content.append("A spined store holds all of its data on the heap, so it does not open in less.");
            content.append(" A Rocks copy keeps its data off the heap: the rule asks ")
                    .append(HeapSizing.gb(HeapSizing.need(HeapSizing.Rule.DEFAULTS, StoreKind.ROCKS, plan.data(),
                            store.statedAxiomBytes().orElse(0L)).total()))
                    .append(" for one.");
        }
        if (fixed) {
            content.append("\n\nKomet ").append(komet.version())
                    .append(" starts at 42 GB whatever its store needs; a Komet built after it takes the heap the rule asks.");
        }
        alert.setContentText(content.toString());
        alert.getDialogPane().setMinWidth(520);
        Optional<ButtonType> answer = alert.showAndWait();
        if (answer.isEmpty() || answer.get() == ButtonType.CANCEL) {
            return Optional.empty();
        }
        return Optional.of(answer.get() == reduced ? smaller : plan.need());
    }

    /**
     * What the Memory window changed.
     *
     * @param rule     the rule for every store
     * @param override the heap and cache for this store, or empty for the rule's
     */
    record Edit(HeapSizing.Rule rule, Optional<HeapSizing.Need> override) {
    }

    /**
     * The Memory window: this store's heap, and the rule for every store.
     *
     * @param owner    the window it belongs to, or {@code null}
     * @param store    the store
     * @param rule     the rule as it is
     * @param override this store's heap and cache, if set
     * @param computed what the rule asks for this store
     * @return what the person changed, or empty when cancelled
     */
    static Optional<Edit> edit(Window owner, KnowledgeBase store, HeapSizing.Rule rule,
                               Optional<HeapSizing.Need> override, HeapSizing.Need computed) {
        Dialog<ButtonType> dialog = new Dialog<>();
        if (owner != null) {
            dialog.initOwner(owner);
        }
        dialog.setTitle("Memory — " + store.displayName());
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, ButtonType.OK);

        boolean rocks = store.kind() == StoreKind.ROCKS;
        CheckBox useRule = new CheckBox("Use the rule: " + computed.text());
        useRule.setSelected(override.isEmpty());
        TextField heap = number(override.map(HeapSizing.Need::heap).orElse(computed.heap()) / (double) HeapSizing.GIB);
        TextField cache = number(override.map(HeapSizing.Need::cache).orElse(computed.cache()) / (double) HeapSizing.GIB);
        heap.disableProperty().bind(useRule.selectedProperty());
        cache.disableProperty().bind(useRule.selectedProperty());
        GridPane forStore = grid();
        forStore.addRow(0, useRule);
        GridPane.setColumnSpan(useRule, 2);
        forStore.addRow(1, new Label("Heap, GB"), heap);
        if (rocks) {
            forStore.addRow(2, new Label("Cache, GB"), cache);
        }

        TextField spined = number(rule.spined64Share() * 100);
        TextField rocksShare = number(rule.rocksShare() * 100);
        TextField cacheShare = number(rule.cacheShare() * 100);
        TextField axiomShare = number(rule.axiomShare());
        TextField minimum = number(rule.minimumHeap() / (double) HeapSizing.GIB);
        TextField reserveFloor = number(rule.reserveFloor() / (double) HeapSizing.GIB);
        TextField reserveShare = number(rule.reserveShare() * 100);
        GridPane every = grid();
        every.addRow(0, new Label("Heap, % of a spined store's size"), spined);
        every.addRow(1, new Label("Heap, % of a Rocks store's size"), rocksShare);
        every.addRow(2, new Label("Rocks cache, % of its size"), cacheShare);
        every.addRow(3, new Label("For the reasoner, × the stated axioms' size"), axiomShare);
        every.addRow(4, new Label("Least heap, GB"), minimum);
        every.addRow(5, new Label("Kept for everything else: at least, GB"), reserveFloor);
        every.addRow(6, new Label("… or this % of the machine's memory"), reserveShare);
        Hyperlink defaults = new Hyperlink("Restore the defaults");
        defaults.setOnAction(event -> {
            HeapSizing.Rule shipped = HeapSizing.Rule.DEFAULTS;
            spined.setText(text(shipped.spined64Share() * 100));
            rocksShare.setText(text(shipped.rocksShare() * 100));
            cacheShare.setText(text(shipped.cacheShare() * 100));
            axiomShare.setText(text(shipped.axiomShare()));
            minimum.setText(text(shipped.minimumHeap() / (double) HeapSizing.GIB));
            reserveFloor.setText(text(shipped.reserveFloor() / (double) HeapSizing.GIB));
            reserveShare.setText(text(shipped.reserveShare() * 100));
        });
        Label problem = new Label();
        problem.setStyle(PROBLEM);

        Label storeHeading = heading("This store");
        Label everyHeading = heading("Every store");
        Label provisional = new Label("The defaults are provisional, until measured on the corpus stores. A heap is a "
                + "ceiling: Komet takes memory only as it uses it, so the rule matters most when several run at once.");
        provisional.setWrapText(true);
        provisional.setStyle(MUTED);
        provisional.setMaxWidth(460);
        VBox content = new VBox(10, storeHeading, forStore, everyHeading, every, defaults, provisional, problem);
        content.setPadding(new Insets(12));
        dialog.getDialogPane().setContent(content);

        Edit[] result = new Edit[1];
        dialog.getDialogPane().lookupButton(ButtonType.OK).addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            try {
                HeapSizing.Rule edited = new HeapSizing.Rule(parse(spined) / 100, parse(rocksShare) / 100,
                        parse(cacheShare) / 100, parse(axiomShare), gib(minimum), gib(reserveFloor),
                        parse(reserveShare) / 100);
                Optional<HeapSizing.Need> storeHeap = useRule.isSelected() ? Optional.empty()
                        : Optional.of(new HeapSizing.Need(HeapSizing.roundUp(gib(heap)), rocks ? gib(cache) : 0));
                if (storeHeap.filter(need -> need.heap() < HeapSizing.GIB).isPresent()) {
                    throw new NumberFormatException("A heap below 1 GB does not open a store.");
                }
                result[0] = new Edit(edited, storeHeap);
            } catch (NumberFormatException e) {
                problem.setText(e.getMessage() == null || e.getMessage().startsWith("For input")
                        ? "Every field takes a number of zero or more." : e.getMessage());
                event.consume();
            }
        });
        return dialog.showAndWait().filter(answer -> answer == ButtonType.OK).map(answer -> result[0]);
    }

    private static GridPane grid() {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(6);
        return grid;
    }

    private static Label heading(String text) {
        Label label = new Label(text);
        label.setStyle("-fx-font-weight: bold;");
        return label;
    }

    private static TextField number(double value) {
        TextField field = new TextField(text(value));
        field.setPrefColumnCount(6);
        return field;
    }

    static String text(double value) {
        return value == Math.rint(value) ? String.format(Locale.ROOT, "%.0f", value)
                : String.format(Locale.ROOT, "%.2f", value).replaceAll("0+$", "");
    }

    private static double parse(TextField field) {
        double value = Double.parseDouble(field.getText().strip());
        if (value < 0 || Double.isNaN(value) || Double.isInfinite(value)) {
            throw new NumberFormatException("Every field takes a number of zero or more.");
        }
        return value;
    }

    private static long gib(TextField field) {
        return Math.round(parse(field) * HeapSizing.GIB);
    }
}
