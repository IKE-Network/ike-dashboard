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

/** How the dashboard runs Komet with no window to check an upgraded store (decision 20 of {@code dev-ike-dashboard}). */
class HeadlessLoadLaunchTest {

    private static final KnowledgeBase STORE = new KnowledgeBase("pizza-kb", Path.of("/Solor/pizza-kb"), StoreKind.ROCKS, 10,
            Optional.empty(), Map.of(), Instant.parse("2026-11-01T00:00:00Z"));

    @TempDir
    Path dir;

    @Test
    void theNewestKometThatKnowsTheLoadIsChosen() {
        KometInstallation newer = komet("26.1108.1200");
        KometInstallation older = komet("26.1107.1831");
        assertThat(LauncherChoice.chooseForLoad(List.of(newer, older), STORE)).contains(newer);
    }

    @Test
    void aKometFromBeforeTheLoadIsNeverChosenForIt() {
        // It would not know --load-change-sets and would open its window instead.
        KometInstallation older = komet("26.1107.1500");
        assertThat(LauncherChoice.loadsChangeSets(older)).isFalse();
        assertThat(LauncherChoice.chooseForLoad(List.of(older), STORE)).isEmpty();
        assertThat(LauncherChoice.choose(List.of(older), STORE)).as("it still opens the store").contains(older);
    }

    @Test
    void theCheckRunsKometOnTheStoreWithTheLoadOption() {
        KometInstallation komet = komet("26.1108.1200");
        assertThat(KometLauncher.loadCommand(komet, STORE)).containsExactly(
                komet.executable().toString(), "--kb=" + STORE.path(), "--load-change-sets");
    }

    @Test
    void theReportIsWhatKometPrintsBetweenItsMarkers() {
        String output = "11:01:28 [INFO ] log lines\n" + KometLauncher.REPORT_START + "\n# Change-set load\n\n- 4 loaded\n"
                + KometLauncher.REPORT_END + "\n11:01:30 [INFO ] stopping\n";
        assertThat(KometLauncher.report(output)).isEqualTo("# Change-set load\n\n- 4 loaded");
        assertThat(KometLauncher.report("only log lines\n")).isEmpty();
    }

    @Test
    void withNoReportTheEndOfTheLogSaysWhy() throws IOException {
        Path log = Files.writeString(dir.resolve("run.log"), "starting\n--kb=ike-kb: No knowledge base folder at /x.\n");
        KometLauncher.HeadlessRun run = new KometLauncher.HeadlessRun(2, "", log);
        assertThat(run.clean()).isFalse();
        assertThat(run.text()).contains("status 2").contains("No knowledge base folder at /x.");
    }

    private static KometInstallation komet(String version) {
        return new KometInstallation("Komet " + version, version, Path.of("/Applications/IKE/Komet/komet-" + version),
                Path.of("/Applications/IKE/Komet/komet-" + version + "/bin/launchKomet"));
    }
}
