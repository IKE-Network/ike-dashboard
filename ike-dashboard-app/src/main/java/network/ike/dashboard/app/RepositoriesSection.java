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
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The git repositories inside a knowledge-base folder, in that folder's detail on the Knowledge Bases tab
 * (decision 21; KEC, 2026-11-12): each one's branch, whether everything is committed, and whether it has
 * a remote. Two of them are given a remote here, each its own: the change sets, which are shared with
 * the other authors, and the preferences, which are one person's and belong in a private repository. The
 * person types the address, Check shows what adding it will do, and adding is offered only for the
 * address just checked. A folder that is no repository yet is offered the same, and is made one. It does
 * no IO of its own; {@link DashboardApp} runs git.
 *
 * <p>What is typed and what was checked are kept here while the detail is drawn again around them.
 */
final class RepositoriesSection {

    private static final String MUTED = "-fx-text-fill: #6b6b6b;";
    private static final String PROBLEM = "-fx-text-fill: #c62828;";
    private static final String DONE = "-fx-text-fill: #2e7d32;";

    private final Runnable redraw;
    private final TextField url = new TextField();
    private Optional<Path> adding = Optional.empty();
    private Optional<RepositoryRemote.Check> checked = Optional.empty();
    private Optional<Path> working = Optional.empty();
    private final Map<Path, Said> said = new HashMap<>();
    private RemoteAction onRemote = (folder, address, add) -> { };

    /**
     * What is said of a repository after a check or an adding.
     *
     * @param text    the words
     * @param problem whether it failed
     */
    private record Said(String text, boolean problem) {
    }

    /** What checking a remote, or adding it, does. */
    @FunctionalInterface
    interface RemoteAction {

        /**
         * Checks or adds a remote.
         *
         * @param folder  the repository's folder
         * @param address the remote's git address
         * @param add     {@code false} to check what adding it would do, {@code true} to add it
         */
        void run(Path folder, String address, boolean add);
    }

    /**
     * A section that asks to be drawn again when what it shows changes.
     *
     * @param redraw draws the detail again
     */
    RepositoriesSection(Runnable redraw) {
        this.redraw = redraw;
        HBox.setHgrow(url, Priority.ALWAYS);
    }

    /**
     * What Check and Add remote do.
     *
     * @param action the action
     */
    void setOnRemote(RemoteAction action) {
        onRemote = action;
    }

    /**
     * Whether a repository is one the dashboard gives a remote: the change sets or the preferences, with
     * none yet.
     *
     * @param status the repository's state
     * @return whether Add remote is offered for it
     */
    static boolean offersRemote(RepositorySync.Status status) {
        Path name = status.repository().folder().getFileName();
        return status.remote() == RepositorySync.Remote.NONE && name != null
                && (name.toString().equals(RepositorySync.CHANGE_SETS) || name.toString().equals(RepositorySync.PREFERENCES));
    }

    /**
     * The section for one knowledge-base folder.
     *
     * @param statuses the state of each repository in it, and of a change-set folder that is none yet
     * @return the section's lines, none when the folder has no repository and no change-set folder
     */
    List<Node> nodes(List<RepositorySync.Status> statuses) {
        List<Node> nodes = new ArrayList<>();
        for (RepositorySync.Status status : statuses) {
            Path folder = status.repository().folder();
            nodes.add(SelectableText.of(describe(status)));
            if (offersRemote(status)) {
                if (adding.filter(folder::equals).isPresent()) {
                    nodes.add(box(status));
                } else {
                    Button add = new Button("Add remote…");
                    add.setDisable(working.isPresent());
                    add.setOnAction(event -> {
                        adding = Optional.of(folder);
                        checked = Optional.empty();
                        url.clear();
                        redraw.run();
                    });
                    nodes.add(add);
                }
            }
            Optional.ofNullable(said.get(folder)).ifPresent(words -> {
                SelectableText text = SelectableText.of(words.text());
                text.setStyle(words.problem() ? PROBLEM : DONE);
                nodes.add(text);
            });
        }
        return nodes;
    }

    /** A repository in a line: what it is for, its branch, what is uncommitted, and its remote. */
    static String describe(RepositorySync.Status status) {
        String label = status.repository().label();
        String name = label.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + label.substring(1);
        if (!status.isRepository()) {
            return name + "  ·  not a git repository yet";
        }
        List<String> facts = new ArrayList<>(List.of(name, "branch " + status.branch()));
        facts.add(status.uncommitted() == 0 ? "everything committed"
                : status.uncommitted() + (status.uncommitted() == 1 ? " file" : " files") + " not committed");
        if (status.remote() == RepositorySync.Remote.NONE) {
            facts.add("no remote yet");
        } else if (status.ahead() > 0) {
            facts.add(status.ahead() + (status.ahead() == 1 ? " commit" : " commits") + " not pushed");
        } else {
            facts.add("has a remote");
        }
        return String.join("  ·  ", facts);
    }

    /** Where the address is typed and checked, and then added as it was checked. */
    private Node box(RepositorySync.Status status) {
        Path folder = status.repository().folder();
        boolean busy = working.isPresent();
        boolean personal = RepositoryRemote.Kind.of(folder) == RepositoryRemote.Kind.PREFERENCES;
        SelectableText what = SelectableText.of(personal
                ? "Your preferences for this knowledge base are yours alone: your window layout, your journals, and your"
                + " conversations with the assistant, which contain whatever you typed. Use a private repository, so only"
                + " you can read them. One private repository can keep the preferences of all your knowledge bases, each"
                + " on a branch of its own."
                : "The change sets are shared with the others who author this knowledge base."
                + " Give the address of the repository they share, or of an empty one made on your git host.");
        what.setStyle(MUTED);
        url.setPromptText(personal ? "https://github.com/me/my-komet-preferences.git"
                : "https://github.com/my-org/my-change-sets.git");
        Button check = new Button("Check");
        check.disableProperty().bind(url.textProperty().map(text -> busy || text.isBlank()).orElse(true));
        check.setOnAction(event -> onRemote.run(folder, url.getText().strip(), false));
        Button close = new Button("Close");
        close.setOnAction(event -> {
            adding = Optional.empty();
            checked = Optional.empty();
            redraw.run();
        });
        HBox line = new HBox(8, url, check, close);
        line.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(8, what, line);
        if (working.filter(folder::equals).isPresent()) {
            SelectableText running = SelectableText.of("Asking git …");
            running.setStyle(MUTED);
            box.getChildren().add(running);
        }
        checked.filter(found -> found.folder().equals(folder)).ifPresent(found -> {
            VBox result = new VBox(6);
            if (found.refusal().isPresent()) {
                SelectableText refusal = SelectableText.of(found.refusal().get());
                refusal.setStyle(PROBLEM);
                result.getChildren().add(refusal);
            } else {
                int number = 1;
                for (String step : found.steps()) {
                    result.getChildren().add(SelectableText.of(number++ + ".  " + step));
                }
                Button add = new Button(found.pushes() ? "Add remote and push" : "Add remote");
                add.setDisable(busy);
                add.setOnAction(event -> onRemote.run(folder, found.url(), true));
                result.getChildren().add(add);
            }
            // What was checked is offered only while the address stays as it was checked.
            result.visibleProperty().bind(url.textProperty().map(text -> text.strip().equals(found.url())).orElse(false));
            result.managedProperty().bind(result.visibleProperty());
            box.getChildren().add(result);
        });
        box.setPadding(new Insets(10));
        box.setStyle("-fx-background-color: white; -fx-background-radius: 6; -fx-border-color: #d9d9d9; -fx-border-radius: 6;");
        return box;
    }

    /**
     * Says that git is being asked about a repository.
     *
     * @param folder the repository's folder
     */
    void working(Path folder) {
        working = Optional.of(folder);
        said.remove(folder);
        redraw.run();
    }

    /**
     * Shows what a check found.
     *
     * @param check what adding the remote will do, or why it cannot be added
     */
    void checked(RepositoryRemote.Check check) {
        working = Optional.empty();
        checked = Optional.of(check);
        redraw.run();
    }

    /**
     * Shows how adding a remote ended.
     *
     * @param folder  the repository's folder
     * @param failure why it failed, or empty when the remote was added
     */
    void added(Path folder, Optional<String> failure) {
        working = Optional.empty();
        if (failure.isPresent()) {
            said.put(folder, new Said(failure.get(), true));
        } else {
            said.put(folder, new Said(RepositoryRemote.Kind.of(folder) == RepositoryRemote.Kind.PREFERENCES
                    ? "The remote is set. Later changes are not sent by themselves yet."
                    : "The remote is set. Connect Komet's sync in Komet, with the same address.", false));
            adding = Optional.empty();
            checked = Optional.empty();
            url.clear();
        }
        redraw.run();
    }
}
