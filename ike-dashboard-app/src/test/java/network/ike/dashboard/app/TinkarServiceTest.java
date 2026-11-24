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
import javafx.scene.Parent;
import javafx.scene.Scene;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Service tab: tinkar-service builds found in a checkout, the provider each store layout opens through,
 * the command a start runs, and what refuses a start. The tab is drawn headless with Monocle and written to
 * {@code target/dashboard-service.png}.
 */
class TinkarServiceTest {

    private static final HeapSizing.Need EIGHT_GB = new HeapSizing.Need(8 * HeapSizing.GIB, 0);

    @TempDir
    Path folder;

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
    void findsTheServiceJarsInACheckoutsTargetNewestFirstAndLeavesOtherJarsOut() throws IOException {
        Path target = Files.createDirectories(folder.resolve("target"));
        jar(target.resolve("tinkar-service-1.0.4-SNAPSHOT.jar"), TinkarService.START_CLASS, "1.0.4-SNAPSHOT", "27",
                Instant.parse("2026-11-01T10:00:00Z"));
        jar(target.resolve("tinkar-service-1.0.5-SNAPSHOT.jar"), TinkarService.START_CLASS, "1.0.5-SNAPSHOT", "27",
                Instant.parse("2026-11-17T10:00:00Z"));
        jar(target.resolve("tinkar-service-1.0.5-SNAPSHOT-sources.jar"), null, "1.0.5-SNAPSHOT", "27", Instant.now());
        jar(target.resolve("something-else.jar"), "org.example.Main", "1", "27", Instant.now());

        List<TinkarService.Build> builds = TinkarService.builds(folder);

        assertThat(builds).extracting(build -> build.jar().getFileName().toString())
                .containsExactly("tinkar-service-1.0.5-SNAPSHOT.jar", "tinkar-service-1.0.4-SNAPSHOT.jar");
        assertThat(builds.getFirst().version()).isEqualTo("1.0.5-SNAPSHOT");
        assertThat(builds.getFirst().jdk()).isEqualTo(27);
        assertThat(builds.getFirst().workingFolder()).as("a checkout's jar runs in the checkout, where its .env is")
                .isEqualTo(folder.toAbsolutePath());
        assertThat(TinkarService.builds(folder.resolve("missing"))).isEmpty();
    }

    @Test
    void eachStoreLayoutOpensThroughItsProviderUnlessItsManifestNamesOne() {
        assertThat(TinkarService.controller(store("rocks-kb", StoreKind.ROCKS, Map.of()))).contains("Open Rocks KB");
        assertThat(TinkarService.controller(store("sa64", StoreKind.SPINED_64, Map.of()))).contains("Open 64-bit SpinedArrayStore");
        assertThat(TinkarService.controller(store("sa", StoreKind.SPINED_32, Map.of()))).contains("Open SpinedArrayStore");
        assertThat(TinkarService.controller(store("named", StoreKind.ROCKS,
                Map.of(KnowledgeBase.OPEN_CONTROLLER, "Open Something Else")))).contains("Open Something Else");
        assertThat(TinkarService.controller(store("changes.zip", StoreKind.CHANGE_SETS, Map.of()))).isEmpty();
    }

    @Test
    void theCommandOpensTheChosenStoreOnTheChosenPortsWithItsMemory() {
        TinkarService.Build build = new TinkarService.Build(Path.of("/work/tinkar-service/target/tinkar-service.jar"),
                "1.0.5-SNAPSHOT", 27, Instant.EPOCH);
        TinkarService.Jdk jdk = new TinkarService.Jdk(Path.of("/jdk-27"), 27);
        KnowledgeBase rocks = store("solor-kb", StoreKind.ROCKS, Map.of());

        List<String> command = TinkarService.command(new TinkarService.Launch(build, jdk, rocks,
                new HeapSizing.Need(12 * HeapSizing.GIB, 2 * HeapSizing.GIB), 8086, 9096));

        assertThat(command).containsExactly(jdk.java().toString(), "--enable-preview", "-Xmx12g",
                "-Drocks.blockCache.bytes=" + 2 * HeapSizing.GIB, "-jar", build.jar().toAbsolutePath().toString(),
                "--data.path.parent=" + rocks.path().toAbsolutePath().getParent(), "--data.path.child=solor-kb",
                "--data.controller.name=Open Rocks KB", "--dataset.name=", "--server.port=8086", "--grpc.server.port=9096");
    }

    @Test
    void refusesAJdkOfAnotherReleaseAPortInUseAndTheSamePortTwice() {
        TinkarService.Build build = new TinkarService.Build(Path.of("/work/tinkar-service.jar"), "", 27, Instant.EPOCH);
        KnowledgeBase store = store("solor-kb", StoreKind.ROCKS, Map.of());

        assertThat(TinkarService.refusals(new TinkarService.Launch(build, new TinkarService.Jdk(Path.of("/jdk"), 27), store,
                EIGHT_GB, 8085, 9095), port -> true)).isEmpty();
        assertThat(TinkarService.refusals(new TinkarService.Launch(build, new TinkarService.Jdk(Path.of("/jdk"), 25), store,
                EIGHT_GB, 8085, 8085), port -> port != 8085))
                .containsExactly(
                        "This build is for Java 27 and uses preview features, so it runs only on Java 27. The JDK chosen is Java 25.",
                        "The REST and gRPC ports must differ.",
                        "Port 8085 is in use, perhaps by a tinkar-service started elsewhere. Stop it, or choose another port.",
                        "Port 8085 is in use, perhaps by a tinkar-service started elsewhere. Stop it, or choose another port.");
    }

    @Test
    void aJdkHomeIsReadFromItsReleaseFile() throws IOException {
        Path home = Files.createDirectories(folder.resolve("zulu-27.jdk"));
        Files.writeString(home.resolve("release"), "IMPLEMENTOR=\"Azul Systems, Inc.\"\nJAVA_VERSION=\"27.0.1\"\n");
        Path java = Files.createDirectories(home.resolve("bin")).resolve(
                OperatingSystem.current() == OperatingSystem.WINDOWS ? "java.exe" : "java");
        Files.writeString(java, "");

        assertThat(TinkarService.jdk(home)).contains(new TinkarService.Jdk(home, 27));
        assertThat(TinkarService.jdk(folder)).as("not a JDK").isEmpty();
        assertThat(TinkarService.findJdk(27, home.toString())).contains(new TinkarService.Jdk(home, 27));
        assertThat(TinkarService.feature("1.8.0_402")).isEqualTo(8);
        assertThat(TinkarService.feature("27-ea")).isEqualTo(27);
    }

    @Test
    void theTabListsManagedAndNotManagedStoresAndStartsOnceABuildAJdkAndAStoreAreChosen() throws Exception {
        Path target = Files.createDirectories(folder.resolve("target"));
        jar(target.resolve("tinkar-service-1.0.5-SNAPSHOT.jar"), TinkarService.START_CLASS, "1.0.5-SNAPSHOT", "27",
                Instant.parse("2026-11-17T10:00:00Z"));
        List<TinkarService.Build> builds = TinkarService.builds(folder);
        KnowledgeBase loose = store("ike-starter-set-rocks", StoreKind.ROCKS, Map.of());
        KnowledgeBase spined = store("solor-us-tinkar.sa", StoreKind.SPINED_32, Map.of());
        List<ServicePane.StartRequest> asked = new java.util.ArrayList<>();

        List<Object> shown = onFx(() -> {
            DashboardView view = new DashboardView();
            new Scene(view, 1080, 640);
            view.setUnmanaged(List.of(loose, spined, store("changes.zip", StoreKind.CHANGE_SETS, Map.of())));
            view.showService();
            ServicePane pane = view.service();
            pane.setOnStart(asked::add);
            pane.setBuilds(Optional.of(folder), builds, Optional.empty(), TinkarService.Source.NONE);
            pane.setJdk(Optional.of(new TinkarService.Jdk(Path.of("/Library/Java/JavaVirtualMachines/zulu-27.jdk/Contents/Home"), 27)));
            pane.setChosenStore(Optional.of(spined.path()));
            layout(view);
            List<String> words = Words.of(pane);
            boolean startable = !button(pane, "Start").isDisable();
            write(view.getScene().snapshot(null), Path.of("target", "dashboard-service.png"));
            button(pane, "Start").fire();
            return List.of(words, startable, view.showingService(), view.getCenter() == pane);
        });

        @SuppressWarnings("unchecked")
        List<String> words = (List<String>) shown.getFirst();
        assertThat(words).contains("Tinkar Service", "Build", "Knowledge base", "REST port", "ike-starter-set-rocks",
                "solor-us-tinkar.sa");
        assertThat(words).as("a change-set zip is not a store the service opens").doesNotContain("changes.zip");
        assertThat(shown.subList(1, 4)).containsExactly(true, true, true);
        assertThat(asked).containsExactly(new ServicePane.StartRequest(builds.getFirst(), spined,
                TinkarService.DEFAULT_REST_PORT, TinkarService.DEFAULT_GRPC_PORT));
    }

    @Test
    void aBuildIsUpToDateUntilItsSourceChangesAndItsBranchIsRead() throws IOException {
        Path checkout = Files.createDirectories(folder.resolve("tinkar-service"));
        Files.createDirectories(checkout.resolve(".git"));
        Files.writeString(checkout.resolve(".git").resolve("HEAD"), "ref: refs/heads/feature/service_tab\n");
        Path source = Files.createDirectories(checkout.resolve("src/main/java")).resolve("Service.java");
        Files.writeString(source, "class Service {}");
        Files.setLastModifiedTime(source, FileTime.from(Instant.parse("2026-11-18T15:00:00Z")));
        Files.writeString(checkout.resolve("pom.xml"), "<project/>");
        Files.setLastModifiedTime(checkout.resolve("pom.xml"), FileTime.from(Instant.parse("2026-11-01T00:00:00Z")));
        TinkarService.Build before = new TinkarService.Build(Path.of("a.jar"), "", 27, Instant.parse("2026-11-18T14:00:00Z"));
        TinkarService.Build after = new TinkarService.Build(Path.of("a.jar"), "", 27, Instant.parse("2026-11-18T16:00:00Z"));

        TinkarService.Source read = TinkarService.source(checkout);

        assertThat(read.branch()).contains("feature/service_tab");
        assertThat(read.lastChange()).contains(Instant.parse("2026-11-18T15:00:00Z"));
        assertThat(read.changedSince(before)).isTrue();
        assertThat(read.changedSince(after)).isFalse();
        assertThat(TinkarService.source(folder.resolve("nothing"))).isEqualTo(TinkarService.Source.NONE);
    }

    @Test
    void aJarsBuildTimeIsWhenItWasWrittenNotTheReproducibleDateItCarries() throws IOException {
        Path jar = folder.resolve("tinkar-service.jar");
        Files.writeString(jar, "jar");
        Instant written = Instant.now().minusSeconds(5);
        Files.setLastModifiedTime(jar, FileTime.from(Instant.parse("2026-10-26T22:39:54Z")));

        assertThat(TinkarService.written(jar)).isAfter(written);
    }

    private static KnowledgeBase store(String name, StoreKind kind, Map<String, String> attributes) {
        return new KnowledgeBase(name, Path.of("/Solor", name), kind, 3_000_000_000L, Optional.empty(), attributes,
                Instant.parse("2026-11-02T17:05:55Z"));
    }

    private static void jar(Path path, String startClass, String version, String jdk, Instant modified) throws IOException {
        Manifest manifest = new Manifest();
        Attributes main = manifest.getMainAttributes();
        main.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (startClass != null) {
            main.putValue("Start-Class", startClass);
        }
        main.putValue("Implementation-Version", version);
        main.putValue("Build-Jdk-Spec", jdk);
        try (OutputStream out = Files.newOutputStream(path); JarOutputStream jar = new JarOutputStream(out, manifest)) {
            jar.flush();
        }
        Files.setLastModifiedTime(path, FileTime.from(modified));
    }

    private static javafx.scene.control.Button button(javafx.scene.Node root, String text) {
        return all(root).stream().filter(node -> node instanceof javafx.scene.control.Button button && text.equals(button.getText()))
                .map(javafx.scene.control.Button.class::cast).findFirst().orElseThrow(() -> new AssertionError("no button " + text));
    }

    private static List<javafx.scene.Node> all(javafx.scene.Node root) {
        List<javafx.scene.Node> found = new java.util.ArrayList<>();
        found.add(root);
        if (root instanceof javafx.scene.control.ScrollPane scroll && scroll.getContent() != null) {
            found.addAll(all(scroll.getContent()));
        }
        if (root instanceof Parent parent) {
            parent.getChildrenUnmodifiable().forEach(child -> found.addAll(all(child)));
        }
        return found;
    }

    private static void layout(Parent root) {
        root.applyCss();
        root.layout();
    }

    private static void write(javafx.scene.image.WritableImage image, Path png) throws IOException {
        java.awt.image.BufferedImage buffered = new java.awt.image.BufferedImage((int) image.getWidth(),
                (int) image.getHeight(), java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < buffered.getHeight(); y++) {
            for (int x = 0; x < buffered.getWidth(); x++) {
                buffered.setRGB(x, y, image.getPixelReader().getArgb(x, y));
            }
        }
        Files.createDirectories(png.getParent());
        javax.imageio.ImageIO.write(buffered, "png", png.toFile());
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
