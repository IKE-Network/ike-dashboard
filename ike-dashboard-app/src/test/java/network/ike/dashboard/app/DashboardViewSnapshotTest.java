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
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Renders the dashboard window headless, under Monocle, from a knowledge-base folder built for the
 * test, and writes it to {@code target/dashboard-view.png} so a person can see what it shows: one line
 * per knowledge base on the left, the selected one's detail on the right.
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
    void eachKnowledgeBaseIsOneLineAndTheSelectedOneFillsTheDetail(@TempDir Path solor) throws Exception {
        store(solor, "pizza-kb", "20250627.1-20261023.232318-6");
        rocks(solor, "*DeX-4-17-2026", "2026-06-29T21:21:00Z");
        rocks(solor, "*DeX-4-17-2026-64bit-2", "2026-10-09T03:26:00Z");
        Files.writeString(solor.resolve("DeX-2026-10-08-pb.zip"), "zip");
        Files.setLastModifiedTime(solor.resolve("DeX-2026-10-08-pb.zip"), FileTime.from(Instant.parse("2026-10-08T14:44:00Z")));
        Files.createDirectories(solor.resolve("solor-kb/nidToByteArrayMap"));
        Files.createDirectories(solor.resolve("solor-kb/nidToCitingComponentNidMap"));
        List<KnowledgeBaseFamily> families = KnowledgeBaseFamily.group(KnowledgeBaseScanner.scan(solor));
        KometInstallation komet = new KometInstallation("Komet Desktop 2026-10-16 346s", "26.1016.346",
                Path.of("/Applications/Komet Desktop 2026-10-16 346s.app"),
                Path.of("/Applications/Komet Desktop 2026-10-16 346s.app/Contents/MacOS/Komet Desktop 2026-10-16 346s"));
        List<NexusCatalog.Offer> offers = List.of(
                offer("pizza-kb", "rkb"),
                offer("solor-gudid-kb", "rkb"),
                offer("solor-gudid-kb", "reasoned-sa"));

        CompletableFuture<WritableImage> rendered = new CompletableFuture<>();
        CompletableFuture<DashboardView> built = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                DashboardView view = new DashboardView();
                view.setKomets(List.of(komet));
                view.setFamilies(families);
                view.setOffers(offers);
                view.setStatus("pizza-kb", new BuildStatus(BuildStatus.State.NEWER, "newer build",
                        Optional.of(new SnapshotMetadata.Deploy("20250627.1-20261024.001942-7", Instant.parse("2026-10-24T00:20:14Z")))));
                view.select("DeX");
                view.setMessage(families.size() + " knowledge bases · 1 with a newer build");
                Scene scene = new Scene(view, 1080, 480);
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

        assertThat(view.listed()).as("only what is downloaded").containsExactly("DeX", "pizza-kb", "solor-kb");
        assertThat(view.selected()).map(KnowledgeBaseFamily::name).contains("DeX");
        assertThat(view.selected().flatMap(KnowledgeBaseFamily::latest))
                .map(KnowledgeBase::name).contains("*DeX-4-17-2026-64bit-2");
        assertThat(view.browser().shown()).extracting(offer -> offer.coordinate().artifactId(), view.browser()::isHere)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("pizza-kb", true),
                        org.assertj.core.groups.Tuple.tuple("solor-gudid-kb", false),
                        org.assertj.core.groups.Tuple.tuple("solor-gudid-kb", false));
        assertThat(view.status("pizza-kb").state()).isEqualTo(BuildStatus.State.NEWER);
        Path png = Path.of("target", "dashboard-view.png");
        write(image, png);
        assertThat(png).isNotEmptyFile();
    }

    private static NexusCatalog.Offer offer(String artifactId, String classifier) {
        return new NexusCatalog.Offer(new Coordinate("network.ike.data", artifactId, "1-SNAPSHOT"), classifier,
                3_920_000, Instant.parse("2026-10-24T00:19:42Z"), "ike-restricted-snapshots");
    }

    private static void store(Path solor, String name, String resolvedVersion) throws IOException {
        Path store = Files.createDirectories(solor.resolve(name).resolve("rocks"));
        Path maven = Files.createDirectories(solor.resolve(name).resolve("META-INF/maven/network.ike.data/pizza-kb"));
        Files.writeString(maven.resolve("pom.properties"),
                "artifactId=pizza-kb\ngroupId=network.ike.data\nversion=20250627.1-SNAPSHOT\n");
        Files.writeString(store.getParent().resolve("META-INF/MANIFEST.MF"), "Manifest-Version: 1.0\n"
                + "Ike-Entity-Format: 3\nIke-Resolved-Version: " + resolvedVersion + "\n\n");
    }

    private static void rocks(Path solor, String name, String modified) throws IOException {
        Path store = Files.createDirectories(solor.resolve(name).resolve("rocks"));
        Files.setLastModifiedTime(store.getParent(), FileTime.from(Instant.parse(modified)));
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
