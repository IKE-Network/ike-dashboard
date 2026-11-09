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

    /** A node of its own, removed after the tests, so a test never touches the person's pins. */
    private static final java.util.prefs.Preferences TEST_PINS = java.util.prefs.Preferences.userRoot()
            .node("network/ike/dashboard-test/komet-pins-" + java.util.UUID.randomUUID());

    @org.junit.jupiter.api.AfterAll
    static void removeTestPins() throws Exception {
        TEST_PINS.removeNode();
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
    void theKometTabIsAListAndADetailWithWhatChangedAndAnOpenForAnyKnowledgeBase() throws Exception {
        CompletableFuture<List<String>> newestWords = new CompletableFuture<>();
        CompletableFuture<List<String>> links = new CompletableFuture<>();
        CompletableFuture<List<String>> severalWords = new CompletableFuture<>();
        CompletableFuture<List<String>> severalButtons = new CompletableFuture<>();
        CompletableFuture<List<String>> tabs = new CompletableFuture<>();
        CompletableFuture<String> opened = new CompletableFuture<>();
        CompletableFuture<WritableImage> drawn = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                DashboardView view = new DashboardView();
                view.setKomets(List.of(NEWEST, OLDER, OLDEST));
                view.setFamilies(List.of(new KnowledgeBaseFamily("pizza-kb", List.of(store("pizza-kb", "3")))));
                view.setKometNotes(KometNotes.read(KometNotesTest.RELEASES.replace("26.1108.1917", NEWEST.version())));
                view.setOnOpen((komet, copy) -> opened.complete(komet.version() + " " + copy.name()));
                Scene scene = new Scene(view, 1080, 640);
                view.showKomets();
                view.applyCss();
                view.layout();
                assertThat(view.showingKomets()).isTrue();
                newestWords.complete(Words.of(view));
                links.complete(view.lookupAll(".hyperlink").stream().map(javafx.scene.control.Hyperlink.class::cast)
                        .map(javafx.scene.control.Hyperlink::getText).toList());
                tabs.complete(view.lookupAll(".toggle-button").stream().map(ToggleButton.class::cast)
                        .map(ToggleButton::getText).toList());
                drawn.complete(scene.snapshot(null));
                view.lookupAll(".button").stream().map(Button.class::cast).filter(button -> button.getText().equals("Open"))
                        .findFirst().orElseThrow().fire();
                @SuppressWarnings("unchecked")
                javafx.scene.control.ListView<KometInstallation> list =
                        (javafx.scene.control.ListView<KometInstallation>) view.lookup("#komet-list");
                list.getSelectionModel().clearSelection();
                list.getSelectionModel().selectIndices(1, 2);
                view.applyCss();
                view.layout();
                severalWords.complete(Words.of(view));
                severalButtons.complete(view.lookupAll(".button").stream().map(Button.class::cast)
                        .filter(button -> !button.isDisabled()).map(Button::getText).toList());
            } catch (RuntimeException | AssertionError e) {
                for (CompletableFuture<?> future : List.of(newestWords, links, severalWords, severalButtons, tabs, opened, drawn)) {
                    future.completeExceptionally(e);
                }
            }
        });

        assertThat(tabs.get(30, TimeUnit.SECONDS)).containsSubsequence("Knowledge Bases", "Komet", "Notices");
        assertThat(newestWords.get(30, TimeUnit.SECONDS)).contains("Komet 26.1108.1917", "Komet 26.1019.900",
                        "Komet 26.1016.346", "The newest. Knowledge bases open in it unless they are set to another.",
                        "Only it can open", "tinkar-core", "Give a STAMP its module's kind")
                .anyMatch(text -> text.startsWith("Application  komet-26.1108.1917.app in "));
        assertThat(links.get(30, TimeUnit.SECONDS)).contains("Checkpoint main-20261108-191310", "compare", "c371628",
                "pizza-kb");
        assertThat(opened.get(30, TimeUnit.SECONDS)).as("Open in this Komet, then and there").isEqualTo("26.1108.1917 pizza-kb");
        assertThat(severalWords.get(30, TimeUnit.SECONDS)).contains("2 Komets chosen");
        assertThat(severalButtons.get(30, TimeUnit.SECONDS)).contains("Move 2 Komets to the " + Finder.trashName() + "…");
        write(drawn.get(30, TimeUnit.SECONDS), Path.of("target", "dashboard-komets.png"));
    }

    @Test
    void aPinnedKnowledgeBaseOpensInItsKometAndTheLatestOtherwise() {
        KnowledgeBase format2 = store("pizza-kb", "2");
        List<KometInstallation> all = List.of(NEWEST, OLDER, OLDEST);

        assertThat(LauncherChoice.choose(all, format2, Optional.empty())).contains(NEWEST);
        assertThat(LauncherChoice.choose(all, format2, Optional.of(OLDER.version()))).contains(OLDER);
        assertThat(LauncherChoice.choose(all, format2, Optional.of("26.0101.1"))).as("a pin not installed: the latest")
                .contains(NEWEST);
        assertThat(LauncherChoice.choose(all, store("pizza-kb", "3"), Optional.of(OLDER.version())))
                .as("a pin that cannot open it: the latest that can").contains(NEWEST);
    }

    @Test
    void theKnowledgeBaseSaysWhichKometItOpensInAndThePinShowsOnTheKometTab() throws Exception {
        CompletableFuture<List<String>> choices = new CompletableFuture<>();
        CompletableFuture<String> chosen = new CompletableFuture<>();
        CompletableFuture<List<String>> kometWords = new CompletableFuture<>();
        CompletableFuture<List<String>> missingWords = new CompletableFuture<>();
        CompletableFuture<WritableImage> drawn = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                KometPins pins = new KometPins(TEST_PINS);
                pins.pin("pizza-kb", Optional.of(OLDER.version()));
                DashboardView view = new DashboardView();
                view.setKomets(List.of(NEWEST, OLDER, OLDEST));
                view.setFamilies(List.of(new KnowledgeBaseFamily("pizza-kb", List.of(store("pizza-kb", "2")))));
                view.setKometPins(pins);
                Scene scene = new Scene(view, 1080, 420);
                view.select("pizza-kb");
                view.applyCss();
                view.layout();
                javafx.scene.control.ChoiceBox<?> in = (javafx.scene.control.ChoiceBox<?>) view.lookup(".choice-box");
                choices.complete(in.getItems().stream().map(item -> in.getConverter() == null ? String.valueOf(item)
                        : ((javafx.util.StringConverter<Object>) (javafx.util.StringConverter<?>) in.getConverter()).toString(item)).toList());
                chosen.complete(((javafx.util.StringConverter<Object>) (javafx.util.StringConverter<?>) in.getConverter())
                        .toString(in.getValue()));
                drawn.complete(scene.snapshot(null));
                view.showKomets();
                view.applyCss();                         // a split pane adds its items once its skin exists
                @SuppressWarnings("unchecked")
                javafx.scene.control.ListView<KometInstallation> list =
                        (javafx.scene.control.ListView<KometInstallation>) view.lookup("#komet-list");
                list.getSelectionModel().clearAndSelect(1);
                view.applyCss();
                view.layout();
                List<String> seen = new java.util.ArrayList<>(Words.of(view));
                view.lookupAll(".hyperlink").stream().map(javafx.scene.control.Hyperlink.class::cast)
                        .map(javafx.scene.control.Hyperlink::getText).forEach(seen::add);
                kometWords.complete(seen);
                pins.pin("pizza-kb", Optional.of("26.0101.1"));
                view.select("pizza-kb");
                view.showKnowledgeBases();
                view.applyCss();
                view.layout();
                missingWords.complete(Words.of(view));
            } catch (RuntimeException | AssertionError e) {
                choices.completeExceptionally(e);
                chosen.completeExceptionally(e);
                kometWords.completeExceptionally(e);
                missingWords.completeExceptionally(e);
                drawn.completeExceptionally(e);
            }
        });

        assertThat(choices.get(30, TimeUnit.SECONDS)).containsExactly("Latest Komet (26.1108.1917)", "Komet 26.1108.1917",
                "Komet 26.1019.900", "Komet 26.1016.346");
        assertThat(chosen.get(30, TimeUnit.SECONDS)).isEqualTo("Komet 26.1019.900");
        assertThat(kometWords.get(30, TimeUnit.SECONDS)).contains("Set to open in it", "Pizza");
        assertThat(missingWords.get(30, TimeUnit.SECONDS)).anyMatch(text -> text.startsWith(
                "This knowledge base is set to open in Komet 26.0101.1, which is not installed."));
        write(drawn.get(30, TimeUnit.SECONDS), Path.of("target", "dashboard-komet-choice.png"));
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
