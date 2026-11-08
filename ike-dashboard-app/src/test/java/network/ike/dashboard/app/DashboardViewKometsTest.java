package network.ike.dashboard.app;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ToggleButton;
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
 * The Komet tab (KEC, 2026-11-08): each installed Komet with where it is, what it is used for and Move to Trash,
 * and the older ones together. Drawn to {@code target/dashboard-komets.png}.
 */
class DashboardViewKometsTest {

    private static final Coordinate PIZZA = new Coordinate("network.ike.data", "pizza-kb", "20250627.1-SNAPSHOT");
    private static final KometInstallation NEWEST = komet("26.1108.1917");
    private static final KometInstallation OLDER = komet("26.1019.900");
    private static final KometInstallation OLDEST = komet("26.1016.346");

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
    void aKnowledgeBaseOnlyTheNewestKometOpensIsNamedBeforeThatKometGoes() {
        KnowledgeBase format3 = store("pizza-kb", "3");
        KnowledgeBase format2 = store("pizza-old", "2");
        List<KometInstallation> all = List.of(NEWEST, OLDER, OLDEST);

        assertThat(KometRemoval.stranded(all, List.of(NEWEST), List.of(format3, format2))).containsExactly(format3);
        assertThat(KometRemoval.stranded(all, List.of(OLDER, OLDEST), List.of(format3, format2)))
                .as("the older ones can go: the newest opens both").isEmpty();
        assertThat(KometRemoval.running(NEWEST)).as("nothing runs from a folder that does not exist").isFalse();
    }

    @Test
    void theKometTabListsEachKometWithItsUseAndMovesTheOlderOnesTogether() throws Exception {
        CompletableFuture<List<String>> words = new CompletableFuture<>();
        CompletableFuture<List<String>> buttons = new CompletableFuture<>();
        CompletableFuture<List<String>> tabs = new CompletableFuture<>();
        CompletableFuture<WritableImage> drawn = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                DashboardView view = new DashboardView();
                view.setKomets(List.of(NEWEST, OLDER, OLDEST));
                view.setFamilies(List.of(new KnowledgeBaseFamily("pizza-kb", List.of(store("pizza-kb", "3")))));
                Scene scene = new Scene(view, 1080, 520);
                view.showKomets();
                view.applyCss();
                view.layout();
                assertThat(view.showingKomets()).isTrue();
                words.complete(Words.of(view));
                buttons.complete(view.lookupAll(".button").stream().map(Button.class::cast)
                        .filter(button -> !button.isDisabled()).map(Button::getText).toList());
                tabs.complete(view.lookupAll(".toggle-button").stream().map(ToggleButton.class::cast)
                        .map(ToggleButton::getText).toList());
                drawn.complete(scene.snapshot(null));
            } catch (RuntimeException | AssertionError e) {
                words.completeExceptionally(e);
                buttons.completeExceptionally(e);
                tabs.completeExceptionally(e);
                drawn.completeExceptionally(e);
            }
        });

        assertThat(tabs.get(30, TimeUnit.SECONDS)).containsSubsequence("Knowledge Bases", "Komet", "Notices");
        assertThat(words.get(30, TimeUnit.SECONDS)).contains("Komet", "Komet 26.1108.1917", "Komet 26.1019.900",
                        "Komet 26.1016.346")
                .anyMatch(text -> text.contains("This is the newest. Knowledge bases open in it unless they need another."))
                .anyMatch(text -> text.contains("It is the only Komet that can open pizza-kb."));
        assertThat(buttons.get(30, TimeUnit.SECONDS)).contains("Move 2 older Komets to the " + Finder.trashName() + "…")
                .filteredOn(("Move to " + Finder.trashName() + "…")::equals).hasSize(3);
        write(drawn.get(30, TimeUnit.SECONDS), Path.of("target", "dashboard-komets.png"));
    }

    private static KometInstallation komet(String version) {
        Path bundle = Path.of("/Applications/IKE/Komet-test-does-not-exist/komet-" + version + ".app");
        return new KometInstallation("Komet Desktop " + version, version, bundle, bundle.resolve("Contents/MacOS/Komet"));
    }

    private static KnowledgeBase store(String name, String format) {
        KnowledgeBaseDescriptor descriptor = new KnowledgeBaseDescriptor(1, PIZZA, Optional.empty(), "Pizza", "rocks", 3,
                Instant.parse("2026-10-23T23:23:18Z"), "public", "META-INF/ike/icon.png");
        return new KnowledgeBase(name, Path.of("/Solor", name), StoreKind.ROCKS, 1_000_000, Optional.of(PIZZA),
                Map.of(KnowledgeBase.ENTITY_FORMAT, format), Instant.parse("2026-10-23T23:23:18Z"),
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
