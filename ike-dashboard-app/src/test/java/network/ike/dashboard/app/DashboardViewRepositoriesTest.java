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

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A knowledge-base folder's detail lists the git repositories inside it, and gives its change sets a
 * remote (decision 21): the address is checked, what adding it will do is read, and it is added only as
 * it was checked. Drawn headless with Monocle.
 */
class DashboardViewRepositoriesTest {

    private static final Instant ASSEMBLED = Instant.parse("2026-10-26T17:11:30Z");
    private static final Path STORE = Path.of("/Solor", "solor-kb");
    private static final String ADDRESS = "https://github.com/my-org/solor-changes.git";

    @BeforeAll
    static void startHeadless() throws Exception {
        System.setProperty("glass.platform", "Monocle");
        System.setProperty("monocle.platform", "Headless");
        System.setProperty("prism.order", "sw");
        CompletableFuture<Void> started = new CompletableFuture<>();
        try {
            Platform.startup(() -> started.complete(null));
        } catch (IllegalStateException alreadyStarted) {
            started.complete(null);
        }
        started.get(30, TimeUnit.SECONDS);
    }

    @Test
    void theRepositoriesAreListedAndTheChangeSetsAreGivenARemoteAsItWasChecked() throws Exception {
        List<Object> shown = onFx(() -> {
            DashboardView view = view();
            Path changeSets = STORE.resolve("changeSets");
            List<String> asked = new ArrayList<>();
            view.setOnRepositoryRemote((folder, address, add, send) -> asked.add((add ? "add " : "check ") + folder.getFileName() + " " + address
                    + (RepositoryRemote.Kind.of(folder) == RepositoryRemote.Kind.PREFERENCES ? (send ? " and send" : " and send nothing") : "")));
            view.setSync(STORE, List.of(
                    RepositorySync.changeSetsNotYetIn(STORE),
                    new RepositorySync.Status(new RepositorySync.Repository(STORE.resolve("preferences"), "preferences"), "main", 3, 0,
                            RepositorySync.Remote.NONE, 0, Optional.empty(), "")));
            layout(view);
            List<String> listed = Words.of(view);
            long offered = all(view, Button.class).stream().filter(button -> "Add remote…".equals(button.getText())).count();
            button(view, "Add remote…").fire();
            layout(view);
            TextField address = all(view, TextField.class).stream()
                    .filter(field -> field.getPromptText() != null && field.getPromptText().endsWith("my-change-sets.git")).findFirst().orElseThrow();
            address.setText(" " + ADDRESS + " ");
            button(view, "Check").fire();
            view.repositoryWorking(changeSets);
            view.repositoryChecked(new RepositoryRemote.Check(changeSets, ADDRESS, List.of(), Optional.of(
                    "The remote could not be read: repository not found. Create the repository on your git host first, and check that you are signed in."),
                    false));
            layout(view);
            List<String> refused = Words.of(view);
            boolean notOffered = all(view, Button.class).stream().noneMatch(button -> button.getText().startsWith("Add remote and"));
            view.repositoryChecked(new RepositoryRemote.Check(changeSets, ADDRESS, List.of(
                    "Make changeSets a git repository, on the branch main.", "Set its remote, origin, to " + ADDRESS + ".",
                    "Komet's own sync is still connected in Komet, with the same address."), Optional.empty(), true));
            layout(view);
            List<String> checked = Words.of(view);
            address.setText("https://github.com/my-org/another.git");
            layout(view);
            boolean hidden = !shown(button(view, "Add remote and push"));
            address.setText(ADDRESS);
            layout(view);
            button(view, "Add remote and push").fire();
            view.repositoryRemoteAdded(changeSets, Optional.empty());
            layout(view);
            return List.of(listed, offered, refused, notOffered, checked, hidden, asked, Words.of(view));
        });

        assertThat(words(shown.getFirst())).as("each repository in the folder, and the change-set folder that is none yet")
                .contains("Repositories", "Change sets  ·  not a git repository yet",
                        "Preferences  ·  branch main  ·  3 files not committed  ·  no remote yet");
        assertThat(shown.get(1)).as("the change sets and the preferences are each offered a remote of their own").isEqualTo(2L);
        assertThat(words(shown.get(2))).as("a remote that cannot be read is refused, in words")
                .contains("The remote could not be read: repository not found. Create the repository on your git host first,"
                        + " and check that you are signed in.");
        assertThat(shown.get(3)).isEqualTo(true);
        assertThat(words(shown.get(4))).as("what adding it will do, step by step")
                .contains("1.  Make changeSets a git repository, on the branch main.", "2.  Set its remote, origin, to " + ADDRESS + ".",
                        "3.  Komet's own sync is still connected in Komet, with the same address.");
        assertThat(shown.get(5)).as("adding is offered only for the address as it was checked").isEqualTo(true);
        assertThat(shown.get(6)).as("the check, then the adding, each with the address trimmed")
                .isEqualTo(List.of("check changeSets " + ADDRESS, "add changeSets " + ADDRESS));
        assertThat(words(shown.get(7))).contains("The remote is set. Connect Komet's sync in Komet, with the same address.");
    }

    @Test
    void preferencesAreOfferedARemoteWithTheReasonItShouldBePrivate() throws Exception {
        List<Object> shown = onFx(() -> {
            DashboardView view = view();
            Path preferences = STORE.resolve("preferences");
            List<String> asked = new ArrayList<>();
            view.setOnRepositoryRemote((folder, address, add, send) -> asked.add((add ? "add " : "check ") + folder.getFileName() + " " + address
                    + (RepositoryRemote.Kind.of(folder) == RepositoryRemote.Kind.PREFERENCES ? (send ? " and send" : " and send nothing") : "")));
            view.setSync(STORE, List.of(new RepositorySync.Status(new RepositorySync.Repository(preferences, "preferences"), "", 0, 0,
                    RepositorySync.Remote.NONE, 0, Optional.empty(), RepositorySync.NOT_YET)));
            layout(view);
            List<String> listed = Words.of(view);
            button(view, "Add remote…").fire();
            layout(view);
            List<String> asking = Words.of(view);
            TextField address = all(view, TextField.class).stream()
                    .filter(field -> field.getPromptText() != null && field.getPromptText().endsWith("my-komet-preferences.git")).findFirst().orElseThrow();
            address.setText("git@github.com:me/my-komet-preferences.git");
            javafx.scene.control.CheckBox sendNow = all(view, javafx.scene.control.CheckBox.class).stream()
                    .filter(box -> "Commit and push what is there now".equals(box.getText())).findFirst().orElseThrow();
            boolean onUnlessCleared = sendNow.isSelected();
            sendNow.setSelected(false);
            button(view, "Check").fire();
            sendNow.setSelected(true);
            layout(view);
            button(view, "Check").fire();
            view.repositoryChecked(new RepositoryRemote.Check(preferences, "git@github.com:me/my-komet-preferences.git", List.of(
                    "Make preferences a git repository, on the branch solor-kb.", "Commit what is in it now: 59 files."),
                    Optional.empty(), true));
            layout(view);
            button(view, "Add remote and push").fire();
            view.repositoryRemoteAdded(preferences, Optional.empty());
            layout(view);
            List<String> added = Words.of(view);
            List<String> sent = new ArrayList<>();
            view.setOnRepositorySend(folder -> sent.add("send " + folder.getFileName()));
            view.setSync(STORE, List.of(new RepositorySync.Status(new RepositorySync.Repository(preferences, "preferences"), "solor-kb", 4, 0,
                    RepositorySync.Remote.CURRENT, 0, Optional.empty(), "")));
            layout(view);
            button(view, "Commit and push").fire();
            view.repositorySent(preferences, "Committed 4 files and pushed.", Optional.empty());
            layout(view);
            List<String> afterSend = Words.of(view);
            view.setSync(STORE, List.of(new RepositorySync.Status(new RepositorySync.Repository(preferences, "preferences"), "solor-kb", 0, 0,
                    RepositorySync.Remote.CURRENT, 0, Optional.empty(), "")));
            layout(view);
            boolean nothingToSend = all(view, Button.class).stream().noneMatch(button -> "Commit and push".equals(button.getText()));
            return List.of(listed, asking, asked, added, onUnlessCleared, sent, afterSend, nothingToSend);
        });

        assertThat(words(shown.getFirst())).contains("Preferences  ·  not a git repository yet");
        assertThat(words(shown.get(1))).as("why a private repository, said before an address is typed")
                .contains("Your preferences for this knowledge base are yours alone: your window layout, your journals, and your"
                        + " conversations with the assistant, which contain whatever you typed. Use a private repository, so only"
                        + " you can read them. One private repository can keep the preferences of all your knowledge bases, each"
                        + " on a branch of its own.");
        assertThat(shown.get(2)).as("whether to send now is the person's choice, and what is added is what was checked")
                .isEqualTo(List.of("check preferences git@github.com:me/my-komet-preferences.git and send nothing",
                        "check preferences git@github.com:me/my-komet-preferences.git and send",
                        "add preferences git@github.com:me/my-komet-preferences.git and send"));
        assertThat(words(shown.get(3))).contains("The remote is set. Your preferences are sent only when you choose Commit and push.");
        assertThat(shown.get(4)).as("sending now is chosen unless the person clears it").isEqualTo(true);
        assertThat(shown.get(5)).as("later, Commit and push is offered when there is something to send").isEqualTo(List.of("send preferences"));
        assertThat(words(shown.get(6))).contains("Preferences  ·  branch solor-kb  ·  4 files not committed  ·  has a remote",
                "Committed 4 files and pushed.");
        assertThat(shown.get(7)).as("and not when there is nothing to send").isEqualTo(true);
    }

    private static DashboardView view() {
        Coordinate coordinate = new Coordinate("network.ike.data", "solor-kb", "1-SNAPSHOT");
        KnowledgeBaseDescriptor descriptor = new KnowledgeBaseDescriptor(1, coordinate, Optional.empty(), "SOLOR", "spined-64", 3,
                ASSEMBLED, "restricted", "META-INF/ike/icon.png", KnowledgeBaseDescriptor.Memory.NONE, Optional.of("SOLOR"), List.of());
        KnowledgeBase store = new KnowledgeBase("solor-kb", STORE, StoreKind.SPINED_64, 1_000_000, Optional.of(coordinate), Map.of(),
                ASSEMBLED, DescriptorCheck.met(descriptor));
        DashboardView view = new DashboardView();
        view.setKomets(List.of(new KometInstallation("Komet Desktop 26.1024.1820", "26.1024.1820",
                Path.of("/Applications/Komet.app"), Path.of("/Applications/Komet.app/Contents/MacOS/Komet"))));
        view.setFamilies(List.of(new KnowledgeBaseFamily("solor-kb", List.of(store))));
        new Scene(view, 1080, 900);
        view.select("solor-kb");
        layout(view);
        return view;
    }

    @SuppressWarnings("unchecked")
    private static List<String> words(Object shown) {
        return (List<String>) shown;
    }

    private static void layout(Parent root) {
        root.applyCss();
        root.layout();
    }

    private static boolean shown(Node node) {
        for (Node at = node; at != null; at = at.getParent()) {
            if (!at.isVisible()) {
                return false;
            }
        }
        return true;
    }

    private static Button button(Node root, String text) {
        return all(root, Button.class).stream().filter(button -> text.equals(button.getText())).findFirst()
                .orElseThrow(() -> new AssertionError("no button " + text));
    }

    private static <T extends Node> List<T> all(Node root, Class<T> type) {
        List<T> found = new ArrayList<>();
        collect(root, type, found);
        return found;
    }

    private static <T extends Node> void collect(Node node, Class<T> type, List<T> found) {
        if (type.isInstance(node)) {
            found.add(type.cast(node));
        }
        if (node instanceof ScrollPane scroll && scroll.getContent() != null) {
            collect(scroll.getContent(), type, found);
        } else if (node instanceof Parent parent) {
            parent.getChildrenUnmodifiable().forEach(child -> collect(child, type, found));
        }
    }

    private static <T> T onFx(Callable<T> work) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                result.complete(work.call());
            } catch (Throwable e) {
                result.completeExceptionally(e);
            }
        });
        return result.get(30, TimeUnit.SECONDS);
    }
}
