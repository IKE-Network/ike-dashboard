package network.ike.dashboard.app;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.TextArea;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.VBox;
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
 * A dialog's words can be selected and copied (KEC, 2026-11-08): its header and text become read-only text
 * areas that read as labels, and the upgrade dialog names the copy it upgrades by its folder. The upgrade dialog is
 * drawn to {@code target/dashboard-upgrade-dialog.png}.
 */
class SelectableTextTest {

    private static final Coordinate GUDID = new Coordinate("network.ike.data", "solor-gudid-kb", "1-SNAPSHOT");

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
    void theUpgradeDialogNamesTheCopyByItsFolder() {
        KnowledgeBase copy = copy(Path.of(System.getProperty("user.home"), "Solor", "solor-gudid-card-leak-demo"));

        String text = DashboardView.upgradeText(copy, "26.1108.1917");

        assertThat(DashboardView.upgradeHeader(copy)).isEqualTo("Upgrade SOLOR + GUDID Knowledge Base to the newest build?");
        assertThat(text).startsWith("Your copy of SOLOR + GUDID Knowledge Base is the folder solor-gudid-card-leak-demo"
                        + " in ~/Solor, assembled ")
                .contains("downloads into solor-gudid-card-leak-demo.upgrade beside it")
                .contains("takes the name solor-gudid-card-leak-demo")
                .contains("kept as solor-gudid-card-leak-demo.previous")
                .contains("Komet 26.1108.1917");
    }

    @Test
    void aCopyOutsideTheHomeFolderIsNamedByItsWholePath() {
        assertThat(copy(Path.of("/Volumes/Data/Solor/solor-gudid-kb")).whereShown())
                .isEqualTo("solor-gudid-kb in /Volumes/Data/Solor");
    }

    @Test
    void aDialogsHeaderAndTextCanBeSelectedButNotChanged() throws Exception {
        KnowledgeBase copy = copy(Path.of(System.getProperty("user.home"), "Solor", "solor-gudid-card-leak-demo"));
        CompletableFuture<List<TextArea>> words = new CompletableFuture<>();
        CompletableFuture<WritableImage> drawn = new CompletableFuture<>();
        CompletableFuture<String> selected = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
                alert.setTitle("Upgrade");
                alert.setHeaderText(DashboardView.upgradeHeader(copy));
                alert.setContentText(DashboardView.upgradeText(copy, "26.1108.1917"));
                alert.getButtonTypes().setAll(ButtonType.CANCEL, ButtonType.OK);
                SelectableText.in(alert);
                DialogPane pane = alert.getDialogPane();
                assertThat(pane.getHeaderText()).isNull();
                List<TextArea> areas = ((VBox) pane.getContent()).getChildren().stream()
                        .map(TextArea.class::cast).toList();
                // The dialog's own scene, never shown: a snapshot draws it.
                Scene scene = pane.getScene();
                pane.applyCss();
                pane.layout();
                words.complete(areas);
                TextArea body = areas.get(1);
                body.selectRange(0, "Your copy of".length());
                selected.complete(body.getSelectedText());
                drawn.complete(scene.snapshot(null));
            } catch (RuntimeException | AssertionError e) {
                words.completeExceptionally(e);
                drawn.completeExceptionally(e);
                selected.completeExceptionally(e);
            }
        });

        List<TextArea> areas = words.get(30, TimeUnit.SECONDS);
        assertThat(areas).hasSize(2).allSatisfy(area -> {
            assertThat(area.isEditable()).isFalse();
            assertThat(area.isFocusTraversable()).as("the keyboard goes to the buttons").isFalse();
            assertThat(area.getPrefHeight()).as("as tall as its text").isGreaterThan(10);
        });
        assertThat(areas.get(0).getText()).isEqualTo("Upgrade SOLOR + GUDID Knowledge Base to the newest build?");
        assertThat(areas.get(0).getStyleClass()).contains("heading");
        assertThat(areas.get(1).getPrefHeight()).as("several wrapped lines").isGreaterThan(3 * areas.get(0).getPrefHeight());
        assertThat(selected.get(30, TimeUnit.SECONDS)).isEqualTo("Your copy of");
        write(drawn.get(30, TimeUnit.SECONDS), Path.of("target", "dashboard-upgrade-dialog.png"));
    }

    private static KnowledgeBase copy(Path path) {
        KnowledgeBaseDescriptor descriptor = new KnowledgeBaseDescriptor(1, GUDID, Optional.empty(),
                "SOLOR + GUDID Knowledge Base", "rocks", 3, Instant.parse("2026-11-06T18:31:00Z"), "public",
                "META-INF/ike/icon.png");
        return new KnowledgeBase(path.getFileName().toString(), path, StoreKind.ROCKS, 1_000_000, Optional.of(GUDID),
                Map.of(KnowledgeBase.ASSEMBLED_AT, "2026-11-05T22:10:00Z"), Instant.parse("2026-11-06T18:31:00Z"),
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
