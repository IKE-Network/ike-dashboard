package network.ike.dashboard.app;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;

import java.util.ArrayList;
import java.util.List;

/** The words a window shows, in order: each label's, and each selectable text's ({@link SelectableText}). */
final class Words {

    private Words() {
    }

    static List<String> of(Node root) {
        List<String> words = new ArrayList<>();
        collect(root, words);
        return words;
    }

    private static void collect(Node node, List<String> words) {
        if (node instanceof Label label) {
            words.add(label.getText());
        } else if (node instanceof TextArea area && area.getStyleClass().contains("selectable-text")) {
            words.add(area.getText());
            return;
        }
        if (node instanceof Parent parent) {
            parent.getChildrenUnmodifiable().forEach(child -> collect(child, words));
        }
    }
}
