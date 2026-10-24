package network.ike.dashboard.app;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
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
 * Renders the dashboard window headless, under Monocle, from a knowledge-base folder built for the
 * test, and writes it to {@code target/dashboard-view.png} so a person can see what it shows.
 */
class DashboardViewSnapshotTest {

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
    void theWindowShowsEachKnowledgeBaseWithWhatItSaysAboutItself(@TempDir Path solor) throws Exception {
        Path pizza = Files.createDirectories(solor.resolve("pizza-kb/rocks"));
        Path maven = Files.createDirectories(solor.resolve("pizza-kb/META-INF/maven/network.ike.data/pizza-kb"));
        Files.writeString(maven.resolve("pom.properties"),
                "artifactId=pizza-kb\ngroupId=network.ike.data\nversion=20250627.1-SNAPSHOT\n");
        Files.writeString(solor.resolve("pizza-kb/META-INF/MANIFEST.MF"),
                "Manifest-Version: 1.0\nIke-Assembled-At: 2026-10-24T00:20:08Z\n\n");
        Files.createDirectories(solor.resolve("solor-kb/nidToByteArrayMap"));
        Files.createDirectories(solor.resolve("solor-kb/nidToCitingComponentNidMap"));
        Files.writeString(solor.resolve("ike-starter-set-pb.zip"), "zip");
        List<KnowledgeBase> knowledgeBases = KnowledgeBaseScanner.scan(solor);
        KometInstallation komet = new KometInstallation("Komet Desktop 2026-10-16 346s", "26.1016.346",
                Path.of("/Applications/Komet Desktop 2026-10-16 346s.app"),
                Path.of("/Applications/Komet Desktop 2026-10-16 346s.app/Contents/MacOS/Komet Desktop 2026-10-16 346s"));

        CompletableFuture<WritableImage> rendered = new CompletableFuture<>();
        CompletableFuture<DashboardView> built = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                DashboardView view = new DashboardView();
                view.setKnowledgeBases(knowledgeBases);
                view.setKomets(List.of(komet));
                view.rows().stream().filter(row -> row.knowledgeBase().name().equals("pizza-kb")).findFirst()
                        .ifPresent(row -> row.statusProperty().set(new BuildStatus(BuildStatus.State.CURRENT,
                                "the newest deploy", Optional.of(new SnapshotMetadata.Deploy(
                                "20250627.1-20261024.001942-7", Instant.parse("2026-10-24T00:20:14Z"))))));
                view.select("pizza-kb");
                view.setMessage(knowledgeBases.size() + " entries in " + solor.getFileName() + ", 1 of them says which build it is");
                Scene scene = new Scene(view, 1180, 420);
                view.applyCss();
                view.layout();
                built.complete(view);
                rendered.complete(scene.snapshot(null));
            } catch (RuntimeException e) {
                rendered.completeExceptionally(e);
            }
        });
        WritableImage image = rendered.get(30, TimeUnit.SECONDS);
        DashboardView view = built.get();

        assertThat(view.rows()).extracting(row -> row.knowledgeBase().name())
                .containsExactly("ike-starter-set-pb.zip", "pizza-kb", "solor-kb");
        assertThat(pizza).exists();
        Path png = Path.of("target", "dashboard-view.png");
        write(image, png);
        assertThat(png).isNotEmptyFile();
    }

    private static void write(WritableImage image, Path png) throws IOException {
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
