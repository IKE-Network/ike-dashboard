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
 * per knowledge base on the left, each with its icon or monogram, the selected one's detail on the
 * right; the Nexus window goes to {@code target/nexus-browser.png}.
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
        icon(solor.resolve("pizza-kb"));
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

        java.io.ByteArrayOutputStream gudidIcon = new java.io.ByteArrayOutputStream();
        ImageIO.write(drawn(64, new java.awt.Color(0x1565c0)), "png", gudidIcon);

        CompletableFuture<WritableImage> rendered = new CompletableFuture<>();
        CompletableFuture<WritableImage> nexus = new CompletableFuture<>();
        CompletableFuture<DashboardView> built = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                DashboardView view = new DashboardView();
                view.setKomets(List.of(komet));
                view.setFamilies(families);
                view.setOffers(offers);
                view.setNexusIcons(java.util.Map.of("solor-gudid-kb", gudidIcon.toByteArray()));
                view.setStatus("pizza-kb", new BuildStatus(BuildStatus.State.NEWER, "newer build",
                        Optional.of(new SnapshotMetadata.Deploy("20250627.1-20261024.001942-7", Instant.parse("2026-10-24T00:20:14Z")))));
                view.setKometRelease(Optional.of(new KometRelease("26.1024.1324", KometRelease.Source.NEXUS,
                        java.net.URI.create("https://nexus.tinkar.org/x.zip"), 234_000_000, Optional.empty(), Optional.empty(),
                        Optional.empty(), "osx-aarch64")));
                view.select("pizza-kb");
                view.setMessage(families.size() + " knowledge bases · 1 with a newer build");
                Scene scene = new Scene(view, 1080, 480);
                view.applyCss();
                view.layout();
                built.complete(view);
                rendered.complete(scene.snapshot(null));
                Scene browserScene = new Scene(view.browser(), 720, 200);
                view.browser().applyCss();
                view.browser().layout();
                nexus.complete(browserScene.snapshot(null));
            } catch (RuntimeException e) {
                rendered.completeExceptionally(e);
                nexus.completeExceptionally(e);
            }
        });
        WritableImage image = rendered.get(30, TimeUnit.SECONDS);
        DashboardView view = built.get();

        assertThat(view.listed()).as("only what is downloaded").containsExactly("DeX", "pizza-kb", "solor-kb");
        assertThat(view.selected()).map(KnowledgeBaseFamily::name).contains("pizza-kb");
        assertThat(families.getFirst().latest()).map(KnowledgeBase::name).contains("*DeX-4-17-2026-64bit-2");
        assertThat(view.browser().shown()).extracting(offer -> offer.coordinate().artifactId(), view.browser()::isHere)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("pizza-kb", true),
                        org.assertj.core.groups.Tuple.tuple("solor-gudid-kb", false),
                        org.assertj.core.groups.Tuple.tuple("solor-gudid-kb", false));
        assertThat(view.status("pizza-kb").state()).isEqualTo(BuildStatus.State.NEWER);
        assertThat(families).filteredOn(family -> family.name().equals("pizza-kb")).singleElement()
                .satisfies(pizza -> assertThat(pizza.icon()).isPresent());
        assertThat(view.lookupAll(".image-view")).as("pizza-kb's icon in its list line and in the detail").hasSize(2);
        assertThat(monograms(view)).as("the others are told apart by colour")
                .contains(KnowledgeBaseIcon.color("DeX"), KnowledgeBaseIcon.color("solor-kb"))
                .doesNotContain(KnowledgeBaseIcon.color("pizza-kb"));
        assertThat(view.browser().hasIcon("solor-gudid-kb")).isTrue();
        assertThat(view.browser().hasIcon("pizza-kb")).as("Nexus holds none for it here").isFalse();
        Path png = Path.of("target", "dashboard-view.png");
        write(image, png);
        assertThat(png).isNotEmptyFile();
        write(nexus.get(30, TimeUnit.SECONDS), Path.of("target", "nexus-browser.png"));
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

    /** The fills of the monograms shown, read from the rounded squares they are drawn on. */
    private static List<javafx.scene.paint.Paint> monograms(javafx.scene.Parent parent) {
        List<javafx.scene.paint.Paint> fills = new java.util.ArrayList<>();
        for (javafx.scene.Node child : parent.getChildrenUnmodifiable()) {
            if (child instanceof javafx.scene.shape.Rectangle square && square.getArcWidth() > 0) {
                fills.add(square.getFill());
            } else if (child instanceof javafx.scene.Parent inner) {
                fills.addAll(monograms(inner));
            }
        }
        return fills;
    }

    /** Gives a store an icon as ike:kb-assemble writes it. */
    private static void icon(Path store) throws IOException {
        Path ike = Files.createDirectories(store.resolve("META-INF/ike"));
        ImageIO.write(drawn(64, new java.awt.Color(0xe65100)), "png", ike.resolve("icon.png").toFile());
        Path manifest = store.resolve("META-INF/MANIFEST.MF");
        Files.writeString(manifest, Files.readString(manifest).stripTrailing() + "\nIke-Icon: META-INF/ike/icon.png\n\n");
    }

    /** A round icon with a lighter centre, so it reads as an image and not a monogram. */
    private static BufferedImage drawn(int size, java.awt.Color color) {
        BufferedImage icon = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D graphics = icon.createGraphics();
        graphics.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setColor(color);
        graphics.fillOval(2, 2, size - 4, size - 4);
        graphics.setColor(new java.awt.Color(0xffe0b2));
        graphics.fillOval(size / 4, size / 4, size / 2, size / 2);
        graphics.dispose();
        return icon;
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
