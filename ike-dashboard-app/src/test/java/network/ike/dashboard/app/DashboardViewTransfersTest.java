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
import javafx.scene.control.ProgressBar;
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
 * Downloads in the window, as Komet shows its own progress: a row with the step, a bar, how far it has
 * come and Cancel; the Download buttons wait for a download under way. Drawn headless with Monocle.
 */
class DashboardViewTransfersTest {

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
    void aDownloadUnderWayShowsItsStepAndProgressAndItsButtonsWait() throws Exception {
        List<String> shown = onFx(() -> {
            DashboardView view = view();
            Transfer transfer = new Transfer("Pizza Knowledge Base (Rocks)");
            view.showTransfer(transfer, Optional.of(DashboardView.downloadKey(PIZZA, "rkb")));
            transfer.started();
            transfer.updateTitle("Downloading https://nexus.example/pizza-kb.zip");
            transfer.updateProgress(420_000, 1_000_000);
            view.applyCss();
            view.layout();
            ProgressBar bar = (ProgressBar) view.lookupAll(".progress-bar").iterator().next();
            assertThat(bar.getProgress()).isEqualTo(0.42);
            assertThat(view.transferring(DashboardView.downloadKey(PIZZA, "rkb"))).isTrue();
            return texts(view);
        });

        assertThat(shown).contains("Pizza Knowledge Base (Rocks)", "Downloading  ·  42%", "Cancel", "Downloading …");
        assertThat(shown).as("the notice's Download waits for the one under way").doesNotContain("Download");
    }

    @Test
    void cancelAsksTheDownloadToStop() throws Exception {
        boolean[] stopped = {false};
        Transfer.State state = onFx(() -> {
            DashboardView view = view();
            Transfer transfer = new Transfer("Pizza Knowledge Base (Rocks)");
            view.showTransfer(transfer, Optional.of(DashboardView.downloadKey(PIZZA, "rkb")));
            transfer.started();
            transfer.cancelWith(() -> stopped[0] = true);
            button(view, "Cancel").fire();
            return transfer.snapshot().state();
        });

        assertThat(state).isEqualTo(Transfer.State.CANCELLING);
        assertThat(stopped[0]).isTrue();
    }

    @Test
    void aFinishedDownloadGoesAwayAndOneThatFailedStaysUntilClosed() throws Exception {
        List<Integer> rows = onFx(() -> {
            DashboardView view = view();
            Transfer done = new Transfer("Pizza Knowledge Base (Rocks)");
            Transfer failed = new Transfer("SOLOR Knowledge Base (Rocks)");
            view.showTransfer(done, Optional.of(DashboardView.downloadKey(PIZZA, "rkb")));
            view.showTransfer(failed, Optional.empty());
            List<Integer> counts = new ArrayList<>();
            counts.add(view.transfersShown().size());
            done.started();
            done.finished(Transfer.State.DONE, "Downloaded pizza-kb");
            failed.started();
            failed.finished(Transfer.State.FAILED, "Could not download: Nexus answered 403");
            counts.add(view.transfersShown().size());
            assertThat(view.transferring(DashboardView.downloadKey(PIZZA, "rkb"))).isFalse();
            view.lookupAll(".hyperlink").stream().map(Hyperlink.class::cast)
                    .filter(link -> link.getText().equals("Close")).findFirst().orElseThrow().fire();
            counts.add(view.transfersShown().size());
            return counts;
        });

        assertThat(rows).containsExactly(2, 1, 0);
    }

    private static DashboardView view() {
        KnowledgeBaseDescriptor descriptor = new KnowledgeBaseDescriptor(1, PIZZA, Optional.empty(), "Pizza", "rocks", 3,
                Instant.parse("2026-10-23T23:23:18Z"), "public", "META-INF/ike/icon.png");
        KnowledgeBase pizza = new KnowledgeBase("pizza-kb", Path.of("/Solor/pizza-kb"), StoreKind.ROCKS, 1_000_000,
                Optional.of(PIZZA), Map.of(), Instant.parse("2026-10-23T23:23:18Z"), DescriptorCheck.met(descriptor));
        DashboardView view = new DashboardView();
        view.setKomets(List.of(new KometInstallation("Komet Desktop 26.1024.1820", "26.1024.1820",
                Path.of("/Applications/Komet.app"), Path.of("/Applications/Komet.app/Contents/MacOS/Komet"))));
        view.setFamilies(List.of(new KnowledgeBaseFamily("pizza-kb", List.of(pizza))));
        view.setStatus("pizza-kb", new BuildStatus(BuildStatus.State.NEWER, "newer build", Optional.of(
                new SnapshotMetadata.Deploy("20250627.1-20261024.001942-7", Instant.parse("2026-10-24T00:20:14Z")))));
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
