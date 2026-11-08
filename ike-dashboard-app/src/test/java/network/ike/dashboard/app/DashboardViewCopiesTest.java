package network.ike.dashboard.app;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The same knowledge base in several folders (KEC, 2026-11-08): each folder is a copy of its own, and a copy behind
 * the newest build on Nexus offers Upgrade for that folder, even when the copy the detail shows is current. The
 * detail is drawn to {@code target/dashboard-other-copies.png}.
 */
class DashboardViewCopiesTest {

    private static final Coordinate PIZZA = new Coordinate("network.ike.data", "pizza-kb", "20250627.1-SNAPSHOT");
    private static final String NEWEST = "20250627.1-20261024.001942-7";

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
    void eachFolderOfAKnowledgeBaseIsUpgradedOnItsOwn() throws Exception {
        KnowledgeBase current = copy("pizza-kb", NEWEST);
        KnowledgeBase demo = copy("pizza-card-leak-demo", "20250627.1-20261001.101010-1");
        KnowledgeBase teaching = copy("pizza-teaching", "20250627.1-20260901.101010-1");
        CompletableFuture<List<String>> words = new CompletableFuture<>();
        CompletableFuture<List<String>> enabledButtons = new CompletableFuture<>();
        CompletableFuture<List<Optional<NoticeAction.Upgrade>>> upgrades = new CompletableFuture<>();
        CompletableFuture<WritableImage> drawn = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                DashboardView view = new DashboardView();
                view.setKomets(List.of(new KometInstallation("Komet Desktop 26.1108.1917", "26.1108.1917",
                        Path.of("/Applications/Komet.app"), Path.of("/Applications/Komet.app/Contents/MacOS/Komet"))));
                view.setFamilies(List.of(new KnowledgeBaseFamily("pizza-kb", List.of(current, demo, teaching))));
                view.setStatus("pizza-kb", new BuildStatus(BuildStatus.State.CURRENT, "the newest deploy", Optional.of(
                        new SnapshotMetadata.Deploy(NEWEST, Instant.parse("2026-10-24T00:20:14Z")))));
                view.select("pizza-kb");
                Scene scene = new Scene(view, 1080, 640);
                view.applyCss();
                view.layout();
                upgrades.complete(List.of(view.upgradeOf(current), view.upgradeOf(demo), view.upgradeOf(teaching)));
                List<String> seen = new java.util.ArrayList<>(Words.of(view));
                view.lookupAll(".titled-pane").stream().map(javafx.scene.control.TitledPane.class::cast)
                        .map(javafx.scene.control.TitledPane::getText).forEach(seen::add);
                view.lookupAll(".table-cell").stream().map(javafx.scene.control.Labeled.class::cast)
                        .map(javafx.scene.control.Labeled::getText).filter(java.util.Objects::nonNull).forEach(seen::add);
                words.complete(seen);
                enabledButtons.complete(view.lookupAll(".button").stream().map(Button.class::cast)
                        .filter(button -> !button.isDisabled()).map(Button::getText).toList());
                drawn.complete(scene.snapshot(null));
            } catch (RuntimeException | AssertionError e) {
                upgrades.completeExceptionally(e);
                words.completeExceptionally(e);
                enabledButtons.completeExceptionally(e);
                drawn.completeExceptionally(e);
            }
        });

        List<Optional<NoticeAction.Upgrade>> offered = upgrades.get(30, TimeUnit.SECONDS);
        assertThat(offered.get(0)).as("the copy the detail shows is current").isEmpty();
        assertThat(offered.get(1)).as("the folder behind the newest build upgrades on its own")
                .contains(new NoticeAction.Upgrade(demo, PIZZA, "rkb"));
        assertThat(offered.get(2)).as("every folder behind the newest build, each its own")
                .contains(new NoticeAction.Upgrade(teaching, PIZZA, "rkb"));
        assertThat(words.get(30, TimeUnit.SECONDS))
                .anyMatch(text -> text.startsWith(DashboardView.OTHER_COPIES + " (2)"))
                .anyMatch(text -> text.startsWith("Each folder is a separate copy"))
                .contains("pizza-card-leak-demo", "pizza-teaching")
                .noneMatch(text -> text.startsWith("A newer build is on Nexus"));
        assertThat(enabledButtons.get(30, TimeUnit.SECONDS)).filteredOn("Upgrade…"::equals).hasSize(2);
        assertThat(enabledButtons.get(30, TimeUnit.SECONDS)).as("each folder's own Move to Trash, and none for all at once")
                .filteredOn(("Move to " + Finder.trashName() + "…")::equals).hasSize(2);
        write(drawn.get(30, TimeUnit.SECONDS), Path.of("target", "dashboard-other-copies.png"));
    }

    private static KnowledgeBase copy(String name, String resolved) {
        KnowledgeBaseDescriptor descriptor = new KnowledgeBaseDescriptor(1, PIZZA, Optional.empty(), "Pizza", "rocks", 3,
                Instant.parse("2026-10-23T23:23:18Z"), "public", "META-INF/ike/icon.png");
        return new KnowledgeBase(name, Path.of("/Solor", name), StoreKind.ROCKS, 1_000_000, Optional.of(PIZZA),
                Map.of(KnowledgeBase.RESOLVED_VERSION, resolved), Instant.parse("2026-10-23T23:23:18Z"),
                DescriptorCheck.met(descriptor));
    }

    private static void write(WritableImage image, Path png) throws Exception {
        int width = (int) image.getWidth();
        int height = (int) image.getHeight();
        BufferedImage buffered = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        PixelReader pixels = image.getPixelReader();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                buffered.setRGB(x, y, pixels.getArgb(x, y));
            }
        }
        Files.createDirectories(png.getParent());
        ImageIO.write(buffered, "png", png.toFile());
    }
}
