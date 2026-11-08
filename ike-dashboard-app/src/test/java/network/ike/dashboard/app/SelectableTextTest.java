package network.ike.dashboard.app;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.text.Text;
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
    void theUpgradeDialogNamesTheCopyByItsFolderInBold() {
        KnowledgeBase copy = copy(Path.of(System.getProperty("user.home"), "Solor", "solor-gudid-card-leak-demo"));

        String text = DashboardView.upgradeText(copy, "26.1108.1917");

        assertThat(DashboardView.upgradeHeader(copy)).isEqualTo("Upgrade SOLOR + GUDID Knowledge Base to the latest version?");
        assertThat(Names.plain(text)).startsWith("This upgrades the folder solor-gudid-card-leak-demo in ~/Solor."
                        + " The version in it was made on 2026-11-05 at ")
                .contains("downloads into a new folder, solor-gudid-card-leak-demo.upgrade.")
                .contains("the new folder is renamed solor-gudid-card-leak-demo")
                .contains("the old version goes to the " + Finder.trashName())
                .contains("You can restore it from the " + Finder.trashName() + " if you need it.")
                .contains("If the check fails, nothing changes")
                .contains("Your other folders of SOLOR + GUDID Knowledge Base are not changed.")
                .doesNotContain(".previous", "Quit any Komet", "way back", "build")
                .contains("Komet 26.1108.1917");
        assertThat(text).as("file and folder names are marked for bold")
                .contains(Names.of("solor-gudid-card-leak-demo"), Names.of("~/Solor"),
                        Names.of("solor-gudid-card-leak-demo.upgrade"));
    }

    @Test
    void aCopyOutsideTheHomeFolderIsNamedByItsWholePath() {
        assertThat(Names.plain(copy(Path.of("/Volumes/Data/Solor/solor-gudid-kb")).whereShown()))
                .isEqualTo("solor-gudid-kb in " + Path.of("/Volumes/Data/Solor").toAbsolutePath());
        assertThat(Names.plain("plain")).isEqualTo("plain");
    }

    @Test
    void aDialogsWordsCanBeSelectedAndItsNamesAreBold() throws Exception {
        KnowledgeBase copy = copy(Path.of(System.getProperty("user.home"), "Solor", "solor-gudid-card-leak-demo"));
        CompletableFuture<List<SelectableText>> words = new CompletableFuture<>();
        CompletableFuture<WritableImage> drawn = new CompletableFuture<>();
        CompletableFuture<List<String>> selected = new CompletableFuture<>();
        CompletableFuture<List<String>> bold = new CompletableFuture<>();
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
                List<SelectableText> areas = ((VBox) pane.getContent()).getChildren().stream()
                        .map(SelectableText.class::cast).toList();
                // The dialog's own scene, never shown: a snapshot draws it.
                Scene scene = pane.getScene();
                pane.applyCss();
                pane.layout();
                words.complete(areas);
                SelectableText body = areas.get(1);
                List<String> picked = new java.util.ArrayList<>();
                body.select(0, "This upgrades".length());
                picked.add(body.selectedText());
                int path = body.getText().indexOf("~/Solor");
                body.selectWordAt(path + 3);
                picked.add(body.selectedText());
                selected.complete(picked);
                bold.complete(body.lookupAll("Text").stream().map(Text.class::cast)
                        .filter(run -> run.getFont().getStyle().toLowerCase(java.util.Locale.ROOT).contains("bold"))
                        .map(Text::getText).toList());
                body.select(0, "This upgrades".length());
                drawn.complete(scene.snapshot(null));
            } catch (RuntimeException | AssertionError e) {
                words.completeExceptionally(e);
                drawn.completeExceptionally(e);
                selected.completeExceptionally(e);
                bold.completeExceptionally(e);
            }
        });

        List<SelectableText> areas = words.get(30, TimeUnit.SECONDS);
        assertThat(areas).hasSize(2).allSatisfy(area -> {
            assertThat(area.isFocusTraversable()).as("the keyboard goes to the buttons").isFalse();
            assertThat(area.getHeight()).as("as tall as its text").isGreaterThan(10);
        });
        assertThat(areas.get(0).getText()).isEqualTo("Upgrade SOLOR + GUDID Knowledge Base to the latest version?");
        assertThat(areas.get(1).getHeight()).as("several wrapped lines").isGreaterThan(3 * areas.get(0).getHeight());
        assertThat(selected.get(30, TimeUnit.SECONDS)).as("a drag's range, and a double-click's whole path")
                .containsExactly("This upgrades", "~/Solor");
        assertThat(bold.get(30, TimeUnit.SECONDS)).contains("solor-gudid-card-leak-demo", "~/Solor",
                "solor-gudid-card-leak-demo.upgrade").doesNotContain("This upgrades the folder ");
        write(drawn.get(30, TimeUnit.SECONDS), Path.of("target", "dashboard-upgrade-dialog.png"));
    }

    @Test
    void aStoreIsOpenInKometWhileItsSearchIndexLockIsHeld(@org.junit.jupiter.api.io.TempDir Path solor) throws Exception {
        Path store = solor.resolve("pizza-kb");
        assertThat(StoreLock.openInKomet(store)).as("no index").isFalse();
        Path lock = store.resolve(StoreLock.LUCENE_LOCK);
        Files.createDirectories(lock.getParent());
        Files.writeString(lock, "");
        assertThat(StoreLock.openInKomet(store)).as("the file stays after Komet closes; it is the lock that counts").isFalse();
        try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(lock,
                java.nio.file.StandardOpenOption.WRITE); java.nio.channels.FileLock held = channel.lock()) {
            assertThat(held.isValid()).isTrue();
            assertThat(StoreLock.openInKomet(store)).isTrue();
        }
        assertThat(StoreLock.openInKomet(store)).as("the lock let go").isFalse();
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
