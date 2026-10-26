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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which Komet upgrades a store's change sets, the command it runs, and the report read back from
 * its output (IKE-Network/ike-issues#1313).
 */
class ChangeSetUpgradeLaunchTest {

    @TempDir
    Path dir;

    private static final KnowledgeBase STORE = new KnowledgeBase("ike-kb", Path.of("/Users/x/Solor/ike-kb"),
            StoreKind.SPINED_64, 1, Optional.empty(), Map.of(), Instant.EPOCH);

    private static KometInstallation komet(String version) {
        return new KometInstallation("Komet " + version, version, Path.of("/k/" + version),
                Path.of("/k/" + version + "/bin/launchKomet"));
    }

    @Test
    void theNewestKometThatKnowsTheUpgradeIsChosen() {
        KometInstallation newer = komet("26.1101.900");
        KometInstallation older = komet("26.1024.1356");
        assertThat(LauncherChoice.chooseForUpgrade(List.of(newer, older), STORE)).contains(newer);
    }

    @Test
    void aKometFromBeforeTheUpgradeIsNeverChosenForIt() {
        // It would not know --upgrade-change-sets and would open its window instead.
        KometInstallation older = komet("26.1024.1356");
        assertThat(LauncherChoice.upgradesChangeSets(older)).isFalse();
        assertThat(LauncherChoice.chooseForUpgrade(List.of(older), STORE)).isEmpty();
        assertThat(LauncherChoice.choose(List.of(older), STORE)).as("it still opens the store").contains(older);
    }

    @Test
    void theUpgradeRunsKometOnTheStoreWithTheUpgradeOption() {
        KometInstallation komet = komet("26.1101.900");
        assertThat(KometLauncher.upgradeCommand(komet, STORE)).containsExactly(
                komet.executable().toString(), "--kb=" + STORE.path(), "--upgrade-change-sets");
    }

    @Test
    void theReportIsWhatKometPrintsBetweenItsMarkers() {
        String output = "11:01:28 [INFO ] log lines\n" + KometLauncher.REPORT_START + "\n# Change-set upgrade\n\n- 4 upgraded\n"
                + KometLauncher.REPORT_END + "\n11:01:30 [INFO ] stopping\n";
        assertThat(KometLauncher.report(output)).isEqualTo("# Change-set upgrade\n\n- 4 upgraded");
        assertThat(KometLauncher.report("only log lines\n")).isEmpty();
    }

    @Test
    void withNoReportTheEndOfTheLogSaysWhy() throws IOException {
        Path log = Files.writeString(dir.resolve("run.log"), "starting\n--kb=ike-kb: No knowledge base folder at /x.\n");
        KometLauncher.UpgradeRun run = new KometLauncher.UpgradeRun(2, "", log);
        assertThat(run.clean()).isFalse();
        assertThat(run.text()).contains("status 2").contains("No knowledge base folder at /x.");
    }
}
