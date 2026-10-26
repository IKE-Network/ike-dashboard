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

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The SOLOR pipeline's collection is tracked unless the person names others, or none.
 */
class DashboardSettingsTest {

    @Test
    void theCollectionsPreferenceIsACommaSeparatedList() {
        assertThat(DashboardSettings.collections(DashboardSettings.DEFAULT_COLLECTIONS)).containsExactly("SOLOR");
        assertThat(DashboardSettings.collections(" SOLOR, ,Examples ")).containsExactly("SOLOR", "Examples");
        assertThat(DashboardSettings.collections("")).as("an empty preference tracks none").isEmpty();
    }

    @Test
    void onWindowsKometInstallsAndTheDashboardLogsUnderLocalAppData() throws BackingStoreException {
        Preferences empty = Preferences.userRoot().node("network/ike/dashboard-test-" + UUID.randomUUID());
        try {
            Path home = Path.of("home");
            Path local = Path.of("local");

            DashboardSettings windows = DashboardSettings.read(empty, home, OperatingSystem.WINDOWS,
                    Map.of("LOCALAPPDATA", local.toString()));
            assertThat(windows.installLocation()).isEqualTo(local.resolve("Programs/IKE/Komet"));
            assertThat(windows.logFolder()).isEqualTo(local.resolve("IKE Dashboard/logs"));
            assertThat(windows.solor()).isEqualTo(home.resolve("Solor"));
            assertThat(DashboardSettings.read(empty, home, OperatingSystem.WINDOWS, Map.of()).installLocation())
                    .as("without the variable").isEqualTo(home.resolve("AppData/Local/Programs/IKE/Komet"));

            DashboardSettings mac = DashboardSettings.read(empty, home, OperatingSystem.MAC, Map.of());
            assertThat(mac.installLocation()).isEqualTo(home.resolve("Applications/IKE/Komet"));
            assertThat(mac.logFolder()).isEqualTo(home.resolve("Library/Logs/IKE Dashboard"));
        } finally {
            empty.removeNode();
        }
    }
}
