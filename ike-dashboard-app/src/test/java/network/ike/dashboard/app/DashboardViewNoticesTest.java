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
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The landing page in the window (the IKE Dashboard design, decision 14): Notices is the first screen,
 * a card's action does what it says, and Dismiss removes the card. Drawn headless with Monocle.
 */
class DashboardViewNoticesTest {

    private static final Coordinate PIZZA = new Coordinate("network.ike.data", "pizza-kb", "20250627.1-SNAPSHOT");

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
    void theNoticesAreTheFirstScreenAndTheirEntryCountsWhatIsAtRisk() throws Exception {
        List<String> shown = onFx(() -> {
            DashboardView view = view();
            assertThat(view.showingNotices()).isTrue();
            assertThat(view.selected()).as("no knowledge base is chosen for the person").isEmpty();
            return texts(view);
        });

        assertThat(shown).contains("Notices", "1 file not committed in Pizza, change sets",
                "A newer build of Pizza is on Nexus", "Notices  ·  2  (1 at risk)");
    }

    @Test
    void showTakesThePersonToTheKnowledgeBase() throws Exception {
        boolean[] after = onFx(() -> {
            DashboardView view = view();
            button(view, "Show").fire();
            return new boolean[] {view.showingNotices(), view.selected().map(family -> family.name().equals("pizza-kb"))
                    .orElse(false)};
        });

        assertThat(after[0]).as("the knowledge base's detail replaces the notices").isFalse();
        assertThat(after[1]).isTrue();
    }

    @Test
    void dismissRemovesTheCardAndRecordsItsId() throws Exception {
        Set<String> dismissed = new HashSet<>();
        List<String> left = onFx(() -> {
            DashboardView view = view();
            view.setDismissed(dismissed::contains);
            view.setOnDismiss(dismissed::add);
            view.lookupAll(".hyperlink").stream().map(Hyperlink.class::cast)
                    .filter(link -> link.getText().equals("Dismiss")).findFirst().orElseThrow().fire();
            return view.notices().stream().map(Notice::title).toList();
        });

        assertThat(dismissed).singleElement().asString().startsWith("sync-uncommitted:");
        assertThat(left).containsExactly("A newer build of Pizza is on Nexus");
    }

    private static DashboardView view() {
        KnowledgeBaseDescriptor descriptor = new KnowledgeBaseDescriptor(1, PIZZA, Optional.empty(), "Pizza", "rocks", 3,
                Instant.parse("2026-10-23T23:23:18Z"), "public", "META-INF/ike/icon.png");
        Path store = Path.of("/Solor/pizza-kb");
        KnowledgeBase pizza = new KnowledgeBase("pizza-kb", store, StoreKind.ROCKS, 1_000_000, Optional.of(PIZZA), Map.of(),
                Instant.parse("2026-10-23T23:23:18Z"), DescriptorCheck.met(descriptor));
        DashboardView view = new DashboardView();
        view.setKomets(List.of(new KometInstallation("Komet Desktop 26.1024.1820", "26.1024.1820",
                Path.of("/Applications/Komet.app"), Path.of("/Applications/Komet.app/Contents/MacOS/Komet"))));
        view.setFamilies(List.of(new KnowledgeBaseFamily("pizza-kb", List.of(pizza))));
        view.setStatus("pizza-kb", new BuildStatus(BuildStatus.State.NEWER, "newer build", Optional.of(
                new SnapshotMetadata.Deploy("20250627.1-20261024.001942-7", Instant.parse("2026-10-24T00:20:14Z")))));
        RepositorySync.Repository changeSets = new RepositorySync.Repository(store.resolve("changeSets"), "change sets");
        view.setSync(store, List.of(new RepositorySync.Status(changeSets, "main", 1, 0, RepositorySync.Remote.CURRENT, 0,
                Optional.empty(), "")));
        new Scene(view, 1080, 480);
        view.applyCss();
        view.layout();
        return view;
    }

    /** The text of every label and button drawn. */
    private static List<String> texts(DashboardView view) {
        List<String> texts = new ArrayList<>();
        view.lookupAll(".label").stream().map(Label.class::cast).map(Labeled::getText).forEach(texts::add);
        view.lookupAll(".button").stream().map(Button.class::cast).map(Labeled::getText).forEach(texts::add);
        return texts;
    }

    private static Button button(DashboardView view, String text) {
        return view.lookupAll(".button").stream().map(Button.class::cast).filter(button -> button.getText().equals(text))
                .findFirst().orElseThrow(() -> new AssertionError("no button " + text));
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
