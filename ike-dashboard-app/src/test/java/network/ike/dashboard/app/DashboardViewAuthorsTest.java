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
import javafx.scene.Scene;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.prefs.Preferences;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A knowledge base's Open row reads as one sentence, and its last part is who Komet opens as (KEC,
 * 2026-11-22; IKE-Network/ike-issues#1399): the person with the assistant they direct, or with none, from
 * the file of authors, chosen there and kept for the folder. The panel says why a file cannot be used, and
 * nothing of authors when there is no file.
 */
class DashboardViewAuthorsTest {

    private static final Coordinate PIZZA = new Coordinate("network.ike.data", "pizza-kb", "20250627.1-SNAPSHOT");
    private static final Path FILE = Path.of("home", ".ike", "authors.properties");
    private static final Path FOLDER = Path.of("Solor", "pizza-kb");
    private static final Authors ADA = new Authors(new Authors.Author("Ada Lovelace", "ada@komet.ike.network"),
            Optional.of(new Authors.Author("Ada's Claude", "ada-claude@komet.ike.network")));
    private static final KometInstallation OLDER = komet("26.1108.1917");
    private static final KometInstallation TAKES_THE_CHOICE = komet(OpenAs.SINCE + ".900");

    /** A node of its own, removed after the tests, so a test never touches the person's choices. */
    private static final Preferences TEST_CHOICES = Preferences.userRoot()
            .node("network/ike/dashboard-test/open-as-view-" + UUID.randomUUID());

    @AfterAll
    static void removeTestChoices() throws Exception {
        TEST_CHOICES.removeNode();
    }

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
    void theOpenRowEndsWithWhoKometOpensAs() throws Exception {
        List<String> row = onFx(() -> {
            DashboardView view = view(new Authors.Found(FILE, ADA), OpenAsChoices.NONE, OLDER);
            ChoiceBox<OpenAs> as = openAs(view);
            assertThat(as.getItems()).extracting(choice -> as.getConverter().toString(choice)).containsExactly(
                    "Ada Lovelace, assisted by Ada's Claude", "Ada Lovelace, with no assistant");
            assertThat(as.getConverter().toString(as.getValue())).as("a folder starts with the assistant")
                    .isEqualTo("Ada Lovelace, assisted by Ada's Claude");
            javafx.scene.Parent sentence = as.getParent().getParent();
            assertThat(sentence.getChildrenUnmodifiable().getFirst()).isInstanceOfSatisfying(javafx.scene.control.Button.class,
                    open -> assertThat(open.getText()).isEqualTo("Open"));
            return Words.of(sentence).stream().map(Names::plain).toList();
        });

        assertThat(row).as("after Open: in a Komet, as an author").containsExactly(
                "in", "Latest Komet (26.1108.1917)", "as", "Ada Lovelace, assisted by Ada's Claude");
    }

    @Test
    void choosingNoAssistantIsKeptForTheFolder() throws Exception {
        OpenAsChoices choices = new OpenAsChoices(TEST_CHOICES);
        List<String> words = onFx(() -> {
            DashboardView view = view(new Authors.Found(FILE, ADA), choices, OLDER);
            openAs(view).getSelectionModel().select(1);
            view.applyCss();
            view.layout();
            assertThat(openAs(view).getValue().assistant()).as("the row shows what was chosen")
                    .isEqualTo(OpenAs.Assistant.NONE);
            return Words.of(view).stream().map(Names::plain).toList();
        });

        assertThat(choices.of(FOLDER)).isEqualTo(OpenAs.Assistant.NONE);
        assertThat(new OpenAsChoices(TEST_CHOICES).openAs(new Authors.Found(FILE, ADA), FOLDER).environment())
                .as("what the Open button, and a request from a link, then start Komet with")
                .containsEntry("IKE_ASSISTANT", "none");
        assertThat(words).as("a Komet from before the choice does not read it").contains("Komet 26.1108.1917 is from before"
                + " this choice and does not read it. Komet " + OpenAs.SINCE + " or later does.");

        List<String> newer = onFx(() -> Words.of(view(new Authors.Found(FILE, ADA), choices, TAKES_THE_CHOICE))
                .stream().map(Names::plain).toList());
        assertThat(newer).noneMatch(text -> text.contains("from before this choice"));

        choices.choose(FOLDER, OpenAs.Assistant.NAMED);
    }

    @Test
    void aKometsOwnOpenRowShowsTheChoiceOfTheFolderItOpens() throws Exception {
        OpenAsChoices choices = new OpenAsChoices(TEST_CHOICES);
        choices.choose(FOLDER, OpenAs.Assistant.NONE);
        try {
            String shown = onFx(() -> {
                DashboardView view = view(new Authors.Found(FILE, ADA), choices, TAKES_THE_CHOICE);
                view.showKomets();
                view.applyCss();                         // a split pane adds its items once its skin exists
                @SuppressWarnings("unchecked")
                ListView<KometInstallation> list = (ListView<KometInstallation>) view.lookup("#komet-list");
                list.getSelectionModel().clearAndSelect(0);
                view.applyCss();
                view.layout();
                ChoiceBox<OpenAs> as = openAs(view);
                String kept = as.getConverter().toString(as.getValue());
                as.getSelectionModel().select(0);
                return kept;
            });

            assertThat(shown).isEqualTo("Ada Lovelace, with no assistant");
            assertThat(choices.of(FOLDER)).as("changed there, it is the folder's choice that changes")
                    .isEqualTo(OpenAs.Assistant.NAMED);
        } finally {
            choices.choose(FOLDER, OpenAs.Assistant.NAMED);
        }
    }

    @Test
    void aFileWithNoAssistantNamesThePersonAlone() throws Exception {
        String person = onFx(() -> {
            DashboardView view = view(new Authors.Found(FILE, ADA.withoutAssistant()), OpenAsChoices.NONE, OLDER);
            Node only = view.lookup("#" + DashboardView.OPEN_AS_ID);
            assertThat(only).as("nothing to choose, so no selector").isInstanceOf(Label.class);
            return ((Label) only).getText();
        });

        assertThat(person).isEqualTo("Ada Lovelace");
    }

    @Test
    void thePanelSaysWhyTheFileCannotBeUsed() throws Exception {
        List<String> words = onFx(() -> {
            DashboardView view = view(new Authors.Unreadable(FILE, "it names no person: person.name and person.email"
                    + " are both needed"), OpenAsChoices.NONE, OLDER);
            assertThat(view.lookup("#" + DashboardView.OPEN_AS_ID)).as("nothing is taken from the file").isNull();
            return Words.of(view).stream().map(Names::plain).toList();
        });

        assertThat(words).anyMatch(text -> text.startsWith("The authors in ") && text.contains("cannot be used: it names no person")
                && text.endsWith("Komet opens under the name in git's configuration."));
    }

    @Test
    void withNoFileTheRowEndsAtTheKomet() throws Exception {
        List<String> words = onFx(() -> {
            DashboardView view = view(new Authors.Absent(FILE), OpenAsChoices.NONE, OLDER);
            assertThat(view.lookup("#" + DashboardView.OPEN_AS_ID)).isNull();
            return Words.of(view).stream().map(Names::plain).toList();
        });

        assertThat(words).contains("in").doesNotContain("as").noneMatch(text -> text.contains("authors"));
    }

    /** The selector of who the chosen folder is opened as. */
    @SuppressWarnings("unchecked")
    private static ChoiceBox<OpenAs> openAs(DashboardView view) {
        return (ChoiceBox<OpenAs>) view.lookup("#" + DashboardView.OPEN_AS_ID);
    }

    /** A window on one knowledge base with one Komet, when the file of authors gives what is read. */
    private static DashboardView view(Authors.Reading reading, OpenAsChoices choices, KometInstallation komet) {
        DashboardView view = new DashboardView();
        view.setAuthors(() -> reading);
        view.setOpenAsChoices(choices);
        view.setKomets(List.of(komet));
        view.setFamilies(List.of(new KnowledgeBaseFamily("pizza-kb", List.of(copy()))));
        view.select("pizza-kb");
        new Scene(view, 1080, 720);
        view.applyCss();
        view.layout();
        return view;
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

    private static KometInstallation komet(String version) {
        return new KometInstallation("Komet Desktop " + version, version, Path.of("Applications", "Komet.app"),
                Path.of("Applications", "Komet.app", "Contents", "MacOS", "Komet"));
    }

    private static KnowledgeBase copy() {
        KnowledgeBaseDescriptor descriptor = new KnowledgeBaseDescriptor(1, PIZZA, Optional.empty(), "Pizza", "rocks", 3,
                Instant.parse("2026-10-23T23:23:18Z"), "public", "META-INF/ike/icon.png");
        return new KnowledgeBase("pizza-kb", FOLDER, StoreKind.ROCKS, 1_000_000, Optional.of(PIZZA),
                Map.of(KnowledgeBase.RESOLVED_VERSION, "20250627.1-20261024.001942-7"), Instant.parse("2026-10-23T23:23:18Z"),
                DescriptorCheck.met(descriptor));
    }
}
