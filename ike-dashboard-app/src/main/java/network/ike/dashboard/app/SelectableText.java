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

import javafx.beans.binding.Bindings;
import javafx.scene.Node;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.TextArea;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Text a person can select and copy but not change, which reads as a label: the dashboard's dialogs say what
 * they say this way, so a folder name, a reason or a version can be copied out of them. A JavaFX label cannot be
 * selected, so this is a read-only text area without a border, background or scroll bars, as tall as its text.
 */
final class SelectableText {

    /** How wide a dialog's text is, unless a dialog says otherwise. */
    static final double DIALOG_WIDTH = 480;

    private static final String CSS = """
            .selectable-text, .selectable-text:focused {
                -fx-background-color: transparent;
                -fx-background-insets: 0;
                -fx-background-radius: 0;
                -fx-padding: 0;
                -fx-focus-color: transparent;
                -fx-faint-focus-color: transparent;
                -fx-text-fill: -fx-text-background-color;
                -fx-highlight-fill: #b3d7ff;
                -fx-highlight-text-fill: -fx-text-background-color;
                -fx-display-caret: false;
            }
            .selectable-text .content {
                -fx-background-color: transparent;
                -fx-padding: 0;
            }
            .selectable-text .scroll-pane {
                -fx-background-color: transparent;
                -fx-hbar-policy: never;
                -fx-vbar-policy: never;
            }
            .selectable-text .scroll-pane .viewport {
                -fx-background-color: transparent;
            }
            .selectable-text.heading {
                -fx-font-weight: bold;
                -fx-font-size: 1.15em;
            }
            """;

    /** The style sheet a scene or dialog takes so its selectable text reads as a label. */
    static final String STYLESHEET = "data:text/css;base64,"
            + Base64.getEncoder().encodeToString(CSS.getBytes(StandardCharsets.UTF_8));

    /** Leaves room so the measure never wraps later than the text area does. */
    private static final double WRAP_SLACK = 4;

    private SelectableText() {
    }

    /**
     * Text that can be selected and copied, wrapped at the given width and as tall as it needs. Its scene, or its
     * dialog, takes {@link #STYLESHEET}.
     *
     * @param text  the text
     * @param width the width it wraps at, unless laid out wider or narrower
     * @return the text
     */
    static TextArea of(String text, double width) {
        TextArea area = new TextArea(text);
        area.getStyleClass().add("selectable-text");
        area.setEditable(false);
        area.setWrapText(true);
        // Clicking still selects; the keyboard goes past it to the buttons.
        area.setFocusTraversable(false);
        area.setPrefWidth(width);
        area.setMinHeight(Region.USE_PREF_SIZE);
        area.prefHeightProperty().bind(Bindings.createDoubleBinding(
                () -> height(area.getText(), area.getWidth() > 0 ? area.getWidth() : width, area),
                area.textProperty(), area.widthProperty(), area.fontProperty()));
        return area;
    }

    /**
     * Like {@link #of}, set as a heading.
     *
     * @param text  the heading
     * @param width the width it wraps at
     * @return the heading
     */
    static TextArea heading(String text, double width) {
        TextArea area = of(text, width);
        area.getStyleClass().add("heading");
        return area;
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
        if (pane.getHeader() != null || (blank(header) && blank(content))) {
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
        pane.getStylesheets().add(STYLESHEET);
        return dialog;
    }

    private static boolean blank(String text) {
        return text == null || text.isBlank();
    }

    /** How tall the text is when wrapped at the width, in the area's font. */
    private static double height(String text, double width, TextArea area) {
        Text measure = new Text(text.isEmpty() ? " " : text);
        measure.setFont(area.getFont());
        measure.setWrappingWidth(Math.max(1, width - WRAP_SLACK));
        return Math.ceil(measure.getLayoutBounds().getHeight()) + 2;
    }
}
