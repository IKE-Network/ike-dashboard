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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

import static network.ike.dashboard.app.HeapSizing.GIB;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The heap rule of decision 10: a share of the store's data, budgeted against what is left on the
 * machine beside the Komets running.
 */
class HeapSizingTest {

    private static final HeapSizing.Rule RULE = HeapSizing.Rule.DEFAULTS;
    private static final HeapSizing.Machine IDLE = new HeapSizing.Machine(128 * GIB, List.of());

    @TempDir
    Path root;

    private Preferences node;
    private KometInstallation sizing;
    private KometInstallation fixed;

    @BeforeEach
    void komets() throws IOException {
        sizing = image("26.1025.1", "JAVA_OPTS=\"$JAVA_OPTS -Xmx${KOMET_MAX_HEAP:-42g} $KOMET_JAVA_OPTS\"");
        fixed = image("26.1024.1841", "JAVA_OPTS=\"$JAVA_OPTS --enable-preview -Xmx42g\"");
        node = Preferences.userRoot().node("network/ike/dashboard-test/" + UUID.randomUUID());
    }

    @AfterEach
    void removeNode() throws BackingStoreException {
        node.removeNode();
    }

    @Test
    void aSpinedStoreGetsItsShareAndRoomToClassify() {
        // 2 GB of data: 4 × 2 to browse, 6 × 2 to classify.
        assertThat(HeapSizing.need(RULE, StoreKind.SPINED_64, 2 * GIB)).isEqualTo(new HeapSizing.Need(20 * GIB, 0));
        HeapSizing.Rule browsing = new HeapSizing.Rule(4.0, 0.5, 0.1, 6.0, 8 * GIB, 8 * GIB, 0.15, false);
        assertThat(HeapSizing.need(browsing, StoreKind.SPINED_64, 2 * GIB).heap()).isEqualTo(8 * GIB);
    }

    @Test
    void aSmallStoreGetsTheLeastHeapAndAThirtyTwoBitStoreIsSizedAsSixtyFour() {
        assertThat(HeapSizing.need(RULE, StoreKind.SPINED_32, 100L << 20).heap()).isEqualTo(8 * GIB);
        assertThat(HeapSizing.need(RULE, StoreKind.SPINED_32, 3 * GIB))
                .isEqualTo(HeapSizing.need(RULE, StoreKind.SPINED_64, 3 * GIB));
    }

    @Test
    void aRocksStoreGetsLessHeapAndACache() {
        // 13 GB of data: (0.5 + 6) × 13 = 84.5, rounded up to whole gigabytes; a tenth of it as cache.
        HeapSizing.Need need = HeapSizing.need(RULE, StoreKind.ROCKS, 13 * GIB);
        assertThat(need.heap()).isEqualTo(85 * GIB);
        assertThat(need.cache()).isEqualTo(Math.round(1.3 * GIB));
        assertThat(HeapSizing.need(RULE, StoreKind.ROCKS, GIB).cache()).isEqualTo(HeapSizing.DEFAULT_CACHE);
    }

    @Test
    void theReserveIsTheLargerOfItsFloorAndItsShare() {
        assertThat(RULE.reserve(128 * GIB)).isEqualTo(Math.round(0.15 * 128 * GIB));
        assertThat(RULE.reserve(32 * GIB)).isEqualTo(8 * GIB);
    }

    @Test
    void theBudgetIsWhatIsLeftBesideTheKometsRunning() {
        KnowledgeBase store = store("solor-kb", StoreKind.SPINED_64);
        HeapSizing.Plan alone = HeapSizing.plan(RULE, store, 2 * GIB, Optional.empty(), sizing, IDLE);
        assertThat(alone.fit()).isEqualTo(HeapSizing.Fit.FITS);
        assertThat(alone.environment()).containsExactly(Map.entry("KOMET_MAX_HEAP", "20g"));

        // 128 GB less a 19.2 GB reserve leaves 108.8; three Komets claim 104 of it, so 4.8 is free.
        HeapSizing.Machine busy = new HeapSizing.Machine(128 * GIB, List.of(
                new HeapSizing.Running(1, Optional.empty(), 42 * GIB, 0),
                new HeapSizing.Running(2, Optional.empty(), 42 * GIB, 0),
                new HeapSizing.Running(3, Optional.empty(), 20 * GIB, 0)));
        HeapSizing.Plan crowded = HeapSizing.plan(RULE, store, 2 * GIB, Optional.empty(), sizing, busy);
        assertThat(crowded.fit()).isEqualTo(HeapSizing.Fit.NOT_BESIDE_RUNNING);
        assertThat(crowded.available()).isLessThan(5 * GIB);
        assertThat(crowded.largestThatFits(crowded.available()).heap()).isEqualTo(4 * GIB);
    }

    @Test
    void aStoreTooBigForTheMachineSaysSo() {
        HeapSizing.Machine small = new HeapSizing.Machine(32 * GIB, List.of());
        HeapSizing.Plan plan = HeapSizing.plan(RULE, store("big", StoreKind.SPINED_64), 13 * GIB, Optional.empty(),
                sizing, small);
        assertThat(plan.fit()).isEqualTo(HeapSizing.Fit.EXCEEDS_MACHINE);
        assertThat(plan.ceiling()).isEqualTo(24 * GIB);
        HeapSizing.Plan reduced = plan.with(plan.largestThatFits(plan.ceiling()));
        assertThat(reduced.need().heap()).isEqualTo(24 * GIB);
        assertThat(reduced.fit()).isEqualTo(HeapSizing.Fit.FITS);
        assertThat(reduced.source()).isEqualTo(HeapSizing.Source.OVERRIDE);
    }

    @Test
    void aRocksCacheGivesWayBeforeTheHeap() {
        // 32 GB less 8 reserved leaves 24, and the Komet running claims 15 of it.
        HeapSizing.Machine machine = new HeapSizing.Machine(32 * GIB, List.of(
                new HeapSizing.Running(1, Optional.empty(), 15 * GIB, 0)));
        KnowledgeBase rocks = store("pizza-kb", StoreKind.ROCKS);
        // 1 GB of data: heap max(8, 6.5) = 8, cache 1, which just fits; with 8.5 free, the cache shrinks.
        HeapSizing.Plan plan = HeapSizing.plan(RULE, rocks, GIB, Optional.empty(), sizing, machine);
        assertThat(plan.fit()).isEqualTo(HeapSizing.Fit.FITS);
        assertThat(plan.environment()).containsEntry("KOMET_MAX_HEAP", "8g")
                .containsEntry("KOMET_JAVA_OPTS", "-Drocks.blockCache.bytes=" + GIB);

        HeapSizing.Machine fuller = new HeapSizing.Machine(32 * GIB, List.of(
                new HeapSizing.Running(1, Optional.empty(), 15 * GIB + (512L << 20), 0)));
        HeapSizing.Plan squeezed = HeapSizing.plan(RULE, rocks, GIB, Optional.empty(), sizing, fuller);
        assertThat(squeezed.fit()).isEqualTo(HeapSizing.Fit.FITS);
        assertThat(squeezed.need()).isEqualTo(new HeapSizing.Need(8 * GIB, 512L << 20));
    }

    @Test
    void aKometThatFixesItsHeapIsBudgetedAtFortyTwoAndGetsNoVariables() {
        HeapSizing.Plan plan = HeapSizing.plan(RULE, store("solor-kb", StoreKind.SPINED_64), 2 * GIB, Optional.empty(),
                fixed, IDLE);
        assertThat(plan.source()).isEqualTo(HeapSizing.Source.FIXED_BY_KOMET);
        assertThat(plan.need().heap()).isEqualTo(HeapSizing.FIXED_HEAP);
        assertThat(plan.computed().heap()).isEqualTo(20 * GIB);
        assertThat(plan.environment()).isEmpty();
    }

    @Test
    void onlyAnImageWhoseLauncherNamesTheVariableTakesAHeap() {
        assertThat(KometLauncher.takesHeap(sizing)).isTrue();
        assertThat(KometLauncher.takesHeap(fixed)).isFalse();
        KometInstallation bundle = new KometInstallation("Komet", "26.1016.346", Path.of("/Applications/Komet.app"),
                Path.of("/Applications/Komet.app/Contents/MacOS/Komet"));
        assertThat(KometLauncher.takesHeap(bundle)).isFalse();
    }

    @Test
    void theRuleAndAStoresHeapAreKeptInThePreferences() throws IOException {
        assertThat(HeapSizing.Rule.read(node)).isEqualTo(RULE);
        HeapSizing.Rule mine = new HeapSizing.Rule(3.0, 0.5, 0.1, 6.0, 8 * GIB, 16 * GIB, 0.15, false);
        mine.write(node);
        assertThat(HeapSizing.Rule.read(node)).isEqualTo(mine);
        // A term equal to its default is not kept, so a later default reaches it.
        assertThat(List.of(keys(node))).containsExactlyInAnyOrder("spined64Share", "reserveFloor", "budgetClassification");

        Preferences stores = node.node(HeapSizing.STORES_NODE);
        KnowledgeBase rocks = store("pizza-kb", StoreKind.ROCKS);
        assertThat(HeapSizing.override(stores, rocks)).isEmpty();
        HeapSizing.setOverride(stores, rocks, Optional.of(new HeapSizing.Need(12 * GIB + 1, 2 * GIB)));
        assertThat(HeapSizing.override(stores, rocks)).contains(new HeapSizing.Need(13 * GIB, 2 * GIB));
        HeapSizing.Plan plan = HeapSizing.plan(RULE, rocks, GIB, HeapSizing.override(stores, rocks), sizing, IDLE);
        assertThat(plan.source()).isEqualTo(HeapSizing.Source.OVERRIDE);
        HeapSizing.setOverride(stores, rocks, Optional.empty());
        assertThat(HeapSizing.override(stores, rocks)).isEmpty();
    }

    @Test
    void aLongFolderNameIsShortenedToAKey() {
        Path store = root.resolve("x".repeat(120));
        String key = HeapSizing.storeKey(store);
        assertThat(key.length() + ".cache".length()).isLessThanOrEqualTo(Preferences.MAX_KEY_LENGTH);
        assertThat(HeapSizing.storeKey(root.resolve("other").resolve("x".repeat(120)))).isNotEqualTo(key);
        assertThat(HeapSizing.storeKey(root.resolve("solor-kb"))).isEqualTo("solor-kb");
    }

    @Test
    void aRunningKometClaimsItsLastHeapAndCache() throws IOException {
        Path rocks = Files.createDirectories(root.resolve("pizza-kb/rocks")).getParent();
        HeapSizing.Running running = HeapSizing.running(7, List.of("-Xmx42g", "-Xmx24g", "-p", "lib",
                "-m", "dev.ikm.komet.desktop/dev.ikm.komet.desktop.KometLauncher", "--kb=" + rocks), 128 * GIB);
        assertThat(running.heap()).isEqualTo(24 * GIB);
        assertThat(running.cache()).isEqualTo(HeapSizing.DEFAULT_CACHE);
        assertThat(running.store()).contains(rocks);
        assertThat(running.text()).isEqualTo("pizza-kb (25 GB)");

        HeapSizing.Running sized = HeapSizing.running(8, List.of("-Xmx8g", "-Drocks.blockCache.bytes=2147483648",
                "--kb=" + rocks), 128 * GIB);
        assertThat(sized.cache()).isEqualTo(2 * GIB);
        // No -Xmx: the JVM's default, a quarter of physical memory.
        assertThat(HeapSizing.running(9, List.of("-m", "dev.ikm.komet.desktop/x"), 128 * GIB).heap()).isEqualTo(32 * GIB);
    }

    @Test
    void sizesParseAsTheJvmReadsThem() {
        assertThat(HeapSizing.parseSize("42g")).contains(42 * GIB);
        assertThat(HeapSizing.parseSize("512M")).contains(512L << 20);
        assertThat(HeapSizing.parseSize("1073741824")).contains(GIB);
        assertThat(HeapSizing.parseSize("lots")).isEmpty();
        assertThat(HeapSizing.gb(20 * GIB)).isEqualTo("20 GB");
        assertThat(HeapSizing.gb(Math.round(1.3 * GIB))).isEqualTo("1.3 GB");
    }

    @Test
    void lucenesIndexIsNotData() throws IOException {
        Path store = Files.createDirectories(root.resolve("solor-kb"));
        Files.write(store.resolve("spine.bin"), new byte[3000]);
        Files.write(Files.createDirectories(store.resolve("lucene")).resolve("_0.cfs"), new byte[1000]);
        KnowledgeBase knowledgeBase = new KnowledgeBase("solor-kb", store, StoreKind.SPINED_64,
                KnowledgeBaseScanner.size(store), Optional.empty(), Map.of(), Instant.EPOCH);
        assertThat(HeapSizing.dataBytes(knowledgeBase)).isEqualTo(3000);
    }

    private KometInstallation image(String version, String optionsLine) throws IOException {
        Path folder = Files.createDirectories(root.resolve("komet-" + version + "/bin"));
        Path launcher = folder.resolve("launchKomet");
        Files.writeString(launcher, "#!/usr/bin/env sh\n" + optionsLine + "\nexec \"$JAVACMD\" \"$@\"\n");
        return new KometInstallation("Komet " + version, version, folder.getParent(), launcher);
    }

    private KnowledgeBase store(String name, StoreKind kind) {
        return new KnowledgeBase(name, root.resolve(name), kind, 0, Optional.empty(), Map.of(), Instant.EPOCH);
    }

    private static String[] keys(Preferences node) {
        try {
            return node.keys();
        } catch (BackingStoreException e) {
            throw new IllegalStateException(e);
        }
    }
}
