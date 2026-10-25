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
 * per managed knowledge base on the left beside its icon, the rest folded into Not managed, the
 * selected one's detail on the right; a Not managed entry's detail goes to
 * {@code target/dashboard-not-managed.png}, the Nexus window to {@code target/nexus-browser.png}, and a
 * store's memory line to {@code target/dashboard-memory.png}.
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
    void managedKnowledgeBasesShowTheirIconsAndTheRestFoldIntoNotManaged(@TempDir Path solor) throws Exception {
        store(solor, "pizza-kb", "20250627.1-20261023.232318-6");
        describe(solor.resolve("pizza-kb"), "pizza-kb", "Pizza Knowledge Base", "rocks", new java.awt.Color(0xe65100), true);
        Files.createDirectories(solor.resolve("solor-kb/nidToByteArrayMap"));
        Files.createDirectories(solor.resolve("solor-kb/nidToCitingComponentNidMap"));
        describe(solor.resolve("solor-kb"), "solor-kb", "SOLOR", "spined-32", new java.awt.Color(0x00838f), true);
        Files.createDirectories(solor.resolve("solor-kb-broken/nidToByteArrayMap"));
        Files.createDirectories(solor.resolve("solor-kb-broken/nidToCitingComponentNidMap"));
        describe(solor.resolve("solor-kb-broken"), "solor-kb", "SOLOR", "spined-32", new java.awt.Color(0x00838f), false);
        store(solor, "pizza-kb-2026-10-01", "20250627.1-20261001.101010-1");
        rocks(solor, "*DeX-4-17-2026", "2026-06-29T21:21:00Z");
        rocks(solor, "*DeX-4-17-2026-64bit-2", "2026-10-09T03:26:00Z");
        Files.writeString(solor.resolve("DeX-2026-10-08-pb.zip"), "zip");
        Files.setLastModifiedTime(solor.resolve("DeX-2026-10-08-pb.zip"), FileTime.from(Instant.parse("2026-10-08T14:44:00Z")));
        Files.createDirectories(solor.resolve("notes"));
        List<KnowledgeBase> scanned = KnowledgeBaseScanner.scan(solor);
        List<KnowledgeBaseFamily> families = KnowledgeBaseFamily.managed(scanned);
        List<KnowledgeBase> unmanaged = KnowledgeBaseFamily.unmanaged(scanned);
        KometInstallation komet = new KometInstallation("Komet Desktop 2026-10-16 346s", "26.1016.346",
                Path.of("/Applications/Komet Desktop 2026-10-16 346s.app"),
                Path.of("/Applications/Komet Desktop 2026-10-16 346s.app/Contents/MacOS/Komet Desktop 2026-10-16 346s"));
        NexusCatalog.Catalog catalog = NexusCatalog.Catalog.of(List.of(
                        offer("pizza-kb", "rkb"),
                        offer("solor-gudid-kb", "rkb"),
                        offer("solor-gudid-kb", "reasoned-sa"),
                        offer("undescribed-kb", "rkb")),
                java.util.Map.of(
                        coordinate("pizza-kb"), described("pizza-kb", "Pizza Knowledge Base", new java.awt.Color(0xe65100)),
                        coordinate("solor-gudid-kb"), described("solor-gudid-kb", "SOLOR GUDID", new java.awt.Color(0x1565c0))));

        CompletableFuture<WritableImage> rendered = new CompletableFuture<>();
        CompletableFuture<WritableImage> notManaged = new CompletableFuture<>();
        CompletableFuture<WritableImage> nexus = new CompletableFuture<>();
        CompletableFuture<DashboardView> built = new CompletableFuture<>();
        CompletableFuture<List<String>> unmanagedDetail = new CompletableFuture<>();
        CompletableFuture<List<String>> memoryDetail = new CompletableFuture<>();
        CompletableFuture<WritableImage> memory = new CompletableFuture<>();
        HeapSizing.Machine machine = new HeapSizing.Machine(128 * HeapSizing.GIB, List.of(
                new HeapSizing.Running(1, Optional.of(solor.resolve("solor-kb")), 42 * HeapSizing.GIB, 0)));
        Platform.runLater(() -> {
            try {
                DashboardView view = new DashboardView();
                view.setKomets(List.of(komet));
                view.setFamilies(families);
                view.setUnmanaged(unmanaged);
                view.setCatalog(catalog);
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
                rendered.complete(scene.snapshot(null));
                built.complete(view);
            } catch (RuntimeException e) {
                rendered.completeExceptionally(e);
                built.completeExceptionally(e);
            }
        });
        WritableImage image = rendered.get(30, TimeUnit.SECONDS);
        DashboardView view = built.get();
        Platform.runLater(() -> {
            try {
                // A view of its own: a snapshot of a scene already drawn shows the detail pane as it was.
                DashboardView loose = new DashboardView();
                loose.setKomets(List.of(komet));
                loose.setFamilies(families);
                loose.setUnmanaged(unmanaged);
                loose.selectUnmanaged("solor-kb-broken");
                Scene looseScene = new Scene(loose, 1080, 480);
                loose.applyCss();
                loose.layout();
                unmanagedDetail.complete(loose.lookupAll(".label").stream()
                        .map(node -> ((javafx.scene.control.Label) node).getText()).toList());
                notManaged.complete(looseScene.snapshot(null));

                DashboardView sized = new DashboardView();
                sized.setKomets(List.of(komet));
                sized.setFamilies(families);
                sized.setHeapPlanner((chosen, store) -> Optional.of(HeapSizing.plan(HeapSizing.Rule.DEFAULTS, store,
                        2 * HeapSizing.GIB, Optional.empty(), chosen, machine)));
                sized.select("solor-kb");
                Scene sizedScene = new Scene(sized, 1080, 480);
                sized.applyCss();
                sized.layout();
                memoryDetail.complete(sized.lookupAll(".label").stream()
                        .map(node -> ((javafx.scene.control.Label) node).getText()).toList());
                memory.complete(sizedScene.snapshot(null));

                Scene browserScene = new Scene(view.browser(), 720, 200);
                view.browser().applyCss();
                view.browser().layout();
                nexus.complete(browserScene.snapshot(null));
            } catch (RuntimeException e) {
                notManaged.completeExceptionally(e);
                memory.completeExceptionally(e);
                memoryDetail.completeExceptionally(e);
                nexus.completeExceptionally(e);
                unmanagedDetail.completeExceptionally(e);
            }
        });

        assertThat(view.listed()).as("only managed stores").containsExactly("pizza-kb", "solor-kb");
        assertThat(view.unmanagedListed()).as("the rest, without folders that are no store").containsExactly(
                "*DeX-4-17-2026", "*DeX-4-17-2026-64bit-2", "DeX-2026-10-08-pb.zip", "pizza-kb-2026-10-01", "solor-kb-broken");
        assertThat(families).extracting(KnowledgeBaseFamily::displayName).containsExactly("Pizza Knowledge Base", "SOLOR");
        assertThat(view.status("pizza-kb").state()).isEqualTo(BuildStatus.State.NEWER);
        assertThat(memoryDetail.get(30, TimeUnit.SECONDS)).as("the heap of a Komet that fixes its own, beside one running")
                .contains("Memory  42 GB heap, fixed by Komet 26.1016.346; the rule asks 20 GB  ·  fits beside what is running");
        assertThat(unmanagedDetail.get()).as("why a store is not managed")
                .anyMatch(text -> text.contains("icon META-INF/ike/icon.png is missing"));
        assertThat(view.browser().shown()).as("only described knowledge bases on Nexus")
                .extracting(offer -> offer.coordinate().artifactId(), view.browser()::isHere, view.browser()::hasIcon)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("pizza-kb", true, true),
                        org.assertj.core.groups.Tuple.tuple("solor-gudid-kb", false, true),
                        org.assertj.core.groups.Tuple.tuple("solor-gudid-kb", false, true));
        Path png = Path.of("target", "dashboard-view.png");
        write(image, png);
        assertThat(png).isNotEmptyFile();
        write(notManaged.get(30, TimeUnit.SECONDS), Path.of("target", "dashboard-not-managed.png"));
        write(nexus.get(30, TimeUnit.SECONDS), Path.of("target", "nexus-browser.png"));
        write(memory.get(30, TimeUnit.SECONDS), Path.of("target", "dashboard-memory.png"));
        CompletableFuture<Integer> icons = new CompletableFuture<>();
        Platform.runLater(() -> icons.complete(view.lookupAll(".image-view").size()));
        assertThat(icons.get(30, TimeUnit.SECONDS)).as("each managed line's icon and the detail's").isEqualTo(3);
    }

    @Test
    void aStoreWhoseChangeSetsNeedAnUpgradeOffersIt(@TempDir Path solor) throws Exception {
        store(solor, "pizza-kb", "20250627.1-20261023.232318-6");
        describe(solor.resolve("pizza-kb"), "pizza-kb", "Pizza Knowledge Base", "rocks", new java.awt.Color(0xe65100), true);
        Path changeSets = Files.createDirectories(ChangeSetCheck.folder(solor.resolve("pizza-kb")));
        try (java.io.InputStream in = getClass().getResourceAsStream("/change-sets/format-3-without-patterns.zip")) {
            Files.copy(in, changeSets.resolve("Author 20261023T183430PDT EVY ike-cs.zip"));
        }
        List<KnowledgeBaseFamily> families = KnowledgeBaseFamily.managed(KnowledgeBaseScanner.scan(solor));
        Path store = families.getFirst().latest().orElseThrow().path();
        ChangeSetCheck.Finding finding = ChangeSetCheck.check(store);
        KometInstallation upgrades = new KometInstallation("Komet 26.1101.900", "26.1101.900",
                Path.of("/Applications/IKE/Komet/komet-26.1101.900"), Path.of("/Applications/IKE/Komet/komet-26.1101.900/bin/launchKomet"));
        KometInstallation tooOld = new KometInstallation("Komet 26.1024.1356", "26.1024.1356",
                Path.of("/Applications/IKE/Komet/komet-26.1024.1356"), Path.of("/Applications/IKE/Komet/komet-26.1024.1356/bin/launchKomet"));
        Path log = Files.writeString(solor.resolve("komet-upgrade.log"), "log");
        KometLauncher.UpgradeRun run = new KometLauncher.UpgradeRun(0, """
                # Change-set upgrade

                Knowledge base: /Users/kec/Solor/pizza-kb

                - 1 file(s) chosen
                - 1 change set(s) upgraded to format 3 (1 from format 3 without patterns)
                - 0 already current
                - 0 empty file(s) skipped (sessions that wrote nothing)
                - 0 other file(s) skipped, not change sets
                - 0 left as they were, naming components the knowledge base does not hold

                ## Replaced

                1 change set(s) replaced by their upgrades; the originals are in
                `/Users/kec/ike-archive/2026-10-24-change-set-originals.tar.gz`.""", log);

        CompletableFuture<WritableImage> offered = new CompletableFuture<>();
        CompletableFuture<List<String>> offeredButtons = new CompletableFuture<>();
        CompletableFuture<List<String>> oldKometTexts = new CompletableFuture<>();
        CompletableFuture<WritableImage> report = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                DashboardView view = new DashboardView();
                view.setKomets(List.of(upgrades));
                view.setFamilies(families);
                view.setChangeSets(store, finding);
                view.select("pizza-kb");
                Scene scene = new Scene(view, 1080, 480);
                view.applyCss();
                view.layout();
                offered.complete(scene.snapshot(null));
                offeredButtons.complete(view.lookupAll(".button").stream()
                        .map(node -> (javafx.scene.control.Button) node)
                        .filter(button -> !button.isDisable()).map(javafx.scene.control.Button::getText).toList());

                DashboardView old = new DashboardView();
                old.setKomets(List.of(tooOld));
                old.setFamilies(families);
                old.setChangeSets(store, finding);
                old.select("pizza-kb");
                new Scene(old, 1080, 480);
                old.applyCss();
                old.layout();
                oldKometTexts.complete(old.lookupAll(".label").stream()
                        .map(node -> ((javafx.scene.control.Label) node).getText()).toList());

                Scene reportScene = new Scene(DashboardView.upgradeReport(run), 820, 420);
                reportScene.getRoot().applyCss();
                reportScene.getRoot().layout();
                report.complete(reportScene.snapshot(null));
            } catch (RuntimeException e) {
                offered.completeExceptionally(e);
                offeredButtons.completeExceptionally(e);
                oldKometTexts.completeExceptionally(e);
                report.completeExceptionally(e);
            }
        });

        assertThat(finding.needed()).isEqualTo(1);
        assertThat(offeredButtons.get(30, TimeUnit.SECONDS)).contains("Upgrade change sets");
        assertThat(oldKometTexts.get(30, TimeUnit.SECONDS))
                .contains("1 change set needs an upgrade to load in a 64-bit store: 1 in format 3 naming no pattern.")
                .anyMatch(text -> text.startsWith("Needs Komet " + LauncherChoice.UPGRADE_CHANGE_SETS_SINCE));
        write(offered.get(30, TimeUnit.SECONDS), Path.of("target", "dashboard-change-sets.png"));
        write(report.get(30, TimeUnit.SECONDS), Path.of("target", "change-set-upgrade-report.png"));
        assertThat(Path.of("target", "dashboard-change-sets.png")).isNotEmptyFile();
    }

    private static Coordinate coordinate(String artifactId) {
        return new Coordinate("network.ike.data", artifactId, "1-SNAPSHOT");
    }

    private static NexusCatalog.Described described(String artifactId, String displayName, java.awt.Color color) throws IOException {
        java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream();
        ImageIO.write(drawn(64, color), "png", png);
        return new NexusCatalog.Described(new KnowledgeBaseDescriptor(1, coordinate(artifactId), Optional.empty(), displayName,
                "rocks", 3, Instant.parse("2026-10-24T00:19:00Z"), "restricted", "META-INF/ike/icon.png"), png.toByteArray());
    }

    /** Gives a store a level-1 knowledge-base descriptor as ike:kb-assemble writes it, with or without its icon. */
    private static void describe(Path store, String artifactId, String displayName, String layout, java.awt.Color color,
                                 boolean withIcon) throws IOException {
        Path ike = Files.createDirectories(store.resolve("META-INF/ike"));
        if (withIcon) {
            ImageIO.write(drawn(64, color), "png", ike.resolve("icon.png").toFile());
        }
        Files.writeString(ike.resolve("knowledge-base.properties"), "descriptorLevel=1\ngroupId=network.ike.data\n"
                + "artifactId=" + artifactId + "\nversion=20250627.1-SNAPSHOT\ndisplayName=" + displayName + "\n"
                + "layout=" + layout + "\nentityFormat=3\nassembledAt=2026-10-23T23\\:20\\:08Z\naccess=restricted\n"
                + "icon=META-INF/ike/icon.png\n");
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
