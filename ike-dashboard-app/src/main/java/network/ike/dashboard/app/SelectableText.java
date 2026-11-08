/*
 * Copyright © 2026 Knowledge Graphlet / IKE Network
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package network.ike.dashboard.app;

import javafx.geometry.Orientation;
import javafx.geometry.Point2D;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.MenuItem;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Path;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * Text a person can select and copy but not change, which reads as a label (KEC, 2026-11-08: text is copyable by
 * default, even read-only), with the names of files, folders and paths in bold ({@link Names}). A JavaFX label
 * cannot be selected and a text area cannot mix bold with plain, so this is a {@link TextFlow} of runs with a
 * selection of its own: drag to select, double-click for a word or a whole path, triple-click for all, and
 * Copy with the shortcut or the context menu. It wraps at its width and is as tall as its text.
 *
 * <p>A style set on it with {@link #setStyle} is given to its runs, with {@code -fx-text-fill} read as the runs'
 * {@code -fx-fill}, so it takes the same styles as a label.
 */
final class SelectableText extends Region {

    /** How wide a dialog's text is, unless a dialog says otherwise. */
    static final double DIALOG_WIDTH = 480;

    private static final Color SELECTION = Color.web("#b3d7ff");
    private static final String NAME_STYLE = "-fx-font-weight: bold;";
    private static final KeyCombination COPY = new KeyCodeCombination(KeyCode.C, KeyCombination.SHORTCUT_DOWN);
    private static final KeyCombination SELECT_ALL = new KeyCodeCombination(KeyCode.A, KeyCombination.SHORTCUT_DOWN);

    /** The one text with a selection: choosing in another clears it, as in a document. */
    private static WeakReference<SelectableText> selecting = new WeakReference<>(null);

    private final TextFlow flow = new TextFlow();
    private final Path highlight = new Path();
    private final double fixedWidth;
    private String marked = "";
    private String plain = "";
    private List<Boolean> runIsName = List.of();
    private int anchor;
    private int start;
    private int end;

    private SelectableText(String text, double fixedWidth) {
        this.fixedWidth = fixedWidth;
        getStyleClass().add("selectable-text");
        highlight.setFill(SELECTION);
        highlight.setStroke(null);
        highlight.setManaged(false);
        highlight.setMouseTransparent(true);
        flow.setMouseTransparent(true);
        getChildren().addAll(highlight, flow);
        setCursor(Cursor.TEXT);
        setFocusTraversable(false);
        styleProperty().addListener((observable, was, now) -> styleRuns());
        setOnMousePressed(event -> {
            if (event.getButton() != MouseButton.PRIMARY) {
                return;
            }
            requestFocus();
            claimSelection();
            int at = indexAt(event.getX(), event.getY());
            switch (event.getClickCount()) {
                case 1 -> {
                    anchor = at;
                    select(at, at);
                }
                case 2 -> selectWordAt(at);
                default -> selectAll();
            }
        });
        setOnMouseDragged(event -> {
            if (event.getButton() == MouseButton.PRIMARY) {
                int at = indexAt(event.getX(), event.getY());
                select(Math.min(anchor, at), Math.max(anchor, at));
            }
        });
        setOnKeyPressed(event -> {
            if (COPY.match(event)) {
                copy();
                event.consume();
            } else if (SELECT_ALL.match(event)) {
                selectAll();
                event.consume();
            }
        });
        setOnContextMenuRequested(event -> {
            MenuItem copy = new MenuItem("Copy");
            copy.setDisable(start == end);
            copy.setOnAction(action -> copy());
            MenuItem all = new MenuItem("Select All");
            all.setOnAction(action -> {
                claimSelection();
                selectAll();
            });
            new ContextMenu(copy, all).show(this, event.getScreenX(), event.getScreenY());
            event.consume();
        });
        setText(text);
    }

    /**
     * Text as wide as its longest line unless laid out narrower, when it wraps: what a wrapping label is.
     *
     * @param text the text, names marked with {@link Names#of}
     * @return the text
     */
    static SelectableText of(String text) {
        return new SelectableText(text, -1);
    }

    /**
     * Text that wraps at the given width unless laid out otherwise.
     *
     * @param text  the text, names marked with {@link Names#of}
     * @param width the width it wraps at
     * @return the text
     */
    static SelectableText of(String text, double width) {
        return new SelectableText(text, width);
    }

    /**
     * Like {@link #of(String, double)}, set as a dialog's heading.
     *
     * @param text  the heading
     * @param width the width it wraps at
     * @return the heading
     */
    static SelectableText heading(String text, double width) {
        SelectableText heading = of(text, width);
        heading.getStyleClass().add("heading");
        heading.setStyle("-fx-font-weight: bold; -fx-font-size: 15px;");
        return heading;
    }

    /**
     * Makes a dialog's words selectable: its header text becomes a selectable heading above its content text, or
     * above the content it already has, and the dialog shows its graphic beside them, as a macOS alert does. Call
     * it once the dialog's texts and content are set, before showing it.
     *
     * @param dialog the dialog
     * @param <R>    what the dialog answers
     * @return the dialog
     */
    static <R> Dialog<R> in(Dialog<R> dialog) {
        DialogPane pane = dialog.getDialogPane();
        String header = pane.getHeaderText();
        String content = pane.getContentText();
        Node own = pane.getContent();
        if (pane.getHeader() != null || (blank(header) && blank(content) && own == null)) {
            return dialog;
        }
        VBox words = new VBox(10);
        if (!blank(header)) {
            words.getChildren().add(heading(header, DIALOG_WIDTH));
        }
        if (own != null) {
            words.getChildren().add(own);
        } else if (!blank(content)) {
            words.getChildren().add(of(content, DIALOG_WIDTH));
        }
        pane.setHeaderText(null);
        pane.setContentText(null);
        pane.setContent(words);
        return dialog;
    }

    /**
     * The text, without the marks of its names.
     *
     * @return the text
     */
    String getText() {
        return plain;
    }

    /**
     * Sets the text, clearing the selection.
     *
     * @param text the text, names marked with {@link Names#of}
     */
    void setText(String text) {
        String now = text == null ? "" : text;
        if (now.equals(marked)) {
            return;
        }
        marked = now;
        plain = Names.plain(now);
        List<Text> runs = new ArrayList<>();
        List<Boolean> names = new ArrayList<>();
        StringBuilder run = new StringBuilder();
        boolean inName = false;
        for (int i = 0; i < now.length(); i++) {
            char c = now.charAt(i);
            if (c == Names.START || c == Names.END) {
                if (!run.isEmpty()) {
                    runs.add(new Text(run.toString()));
                    names.add(inName);
                    run.setLength(0);
                }
                inName = c == Names.START;
            } else {
                run.append(c);
            }
        }
        if (!run.isEmpty() || runs.isEmpty()) {
            runs.add(new Text(run.toString()));
            names.add(inName);
        }
        runIsName = List.copyOf(names);
        flow.getChildren().setAll(runs);
        styleRuns();
        anchor = 0;
        select(0, 0);
        requestLayout();
    }

    /**
     * Selects a range of the text.
     *
     * @param from the first character
     * @param to   one past the last
     */
    void select(int from, int to) {
        start = Math.clamp(Math.min(from, to), 0, plain.length());
        end = Math.clamp(Math.max(from, to), 0, plain.length());
        drawSelection();
    }

    /** Selects the whole text. */
    void selectAll() {
        anchor = 0;
        select(0, plain.length());
    }

    /**
     * The selected text.
     *
     * @return what is selected, empty when nothing is
     */
    String selectedText() {
        return plain.substring(start, end);
    }

    /** Copies the selection, or the whole text when nothing is selected. */
    void copy() {
        ClipboardContent content = new ClipboardContent();
        content.putString(start == end ? plain : selectedText());
        Clipboard.getSystemClipboard().setContent(content);
    }

    /** A word, or a whole path or folder name: the run of characters around the index that are not spaces. */
    void selectWordAt(int at) {
        int from = Math.min(at, plain.length());
        int to = from;
        while (from > 0 && !Character.isWhitespace(plain.charAt(from - 1))) {
            from--;
        }
        while (to < plain.length() && !Character.isWhitespace(plain.charAt(to))) {
            to++;
        }
        // A sentence's closing punctuation is not part of the name before it.
        while (to > from && ",.;:)".indexOf(plain.charAt(to - 1)) >= 0) {
            to--;
        }
        anchor = from;
        select(from, to);
    }

    private void claimSelection() {
        SelectableText before = selecting.get();
        if (before != null && before != this) {
            before.select(0, 0);
        }
        selecting = new WeakReference<>(this);
    }

    private int indexAt(double x, double y) {
        Point2D inFlow = new Point2D(x - flow.getLayoutX(), y - flow.getLayoutY());
        return Math.clamp(flow.hitTest(inFlow).getInsertionIndex(), 0, plain.length());
    }

    private void drawSelection() {
        highlight.getElements().setAll(start == end ? List.of() : List.of(flow.getRangeShape(start, end, true)));
    }

    /** Gives the runs the style set on this text, names in bold. */
    private void styleRuns() {
        String own = getStyle() == null ? "" : getStyle().replace("-fx-text-fill", "-fx-fill");
        String base = "-fx-fill: -fx-text-background-color;" + own;
        for (int i = 0; i < flow.getChildren().size(); i++) {
            flow.getChildren().get(i).setStyle(base + (runIsName.get(i) ? NAME_STYLE : ""));
        }
    }

    private static boolean blank(String text) {
        return text == null || text.isBlank();
    }

    @Override
    public Orientation getContentBias() {
        return Orientation.HORIZONTAL;
    }

    @Override
    protected double computePrefWidth(double height) {
        double insets = snappedLeftInset() + snappedRightInset();
        return insets + (fixedWidth > 0 ? fixedWidth : Math.ceil(flow.prefWidth(-1)) + 2);
    }

    @Override
    protected double computeMinWidth(double height) {
        return Math.min(20, computePrefWidth(height));
    }

    @Override
    protected double computePrefHeight(double width) {
        double inner = (width < 0 ? computePrefWidth(-1) : width) - snappedLeftInset() - snappedRightInset();
        return snappedTopInset() + snappedBottomInset() + Math.ceil(flow.prefHeight(Math.max(1, inner)));
    }

    @Override
    protected double computeMinHeight(double width) {
        return computePrefHeight(width);
    }

    @Override
    protected void layoutChildren() {
        double left = snappedLeftInset();
        double top = snappedTopInset();
        double width = getWidth() - left - snappedRightInset();
        flow.resizeRelocate(left, top, width, flow.prefHeight(width));
        highlight.relocate(left, top);
        highlight.setLayoutX(left);
        highlight.setLayoutY(top);
        drawSelection();
    }
}
