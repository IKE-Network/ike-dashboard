package network.ike.dashboard.app;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.prefs.Preferences;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The dashboard's preferences, named once by enum (KEC, 2026-11-08). Each name below is what is on disk for every
 * person who has used the dashboard: changing one loses their saved value, so this test fails first.
 */
class DashboardPreferencesTest {

    private final Preferences root = Preferences.userRoot().node("network/ike/dashboard-test/preferences-" + UUID.randomUUID());

    @AfterEach
    void removeTestNode() throws Exception {
        root.removeNode();
    }

    @Test
    void theStoredNamesAreTheOnesTheDashboardHasAlwaysUsed() {
        Map<DashboardPreferences.Key, String> stored = Arrays.stream(DashboardPreferences.Key.values()).collect(
                Collectors.toMap(key -> key, DashboardPreferences.Key::stored, (a, b) -> a, LinkedHashMap::new));
        assertThat(stored).containsExactly(
                Map.entry(DashboardPreferences.Key.SORT_RECENT, "sortRecent"),
                Map.entry(DashboardPreferences.Key.SOLOR, "solor"),
                Map.entry(DashboardPreferences.Key.INSTALL_LOCATION, "installLocation"),
                Map.entry(DashboardPreferences.Key.REPOSITORY_URL, "repositoryUrl"),
                Map.entry(DashboardPreferences.Key.SERVER_ID, "serverId"),
                Map.entry(DashboardPreferences.Key.LOCAL_REPOSITORY, "localRepository"),
                Map.entry(DashboardPreferences.Key.LOG_FOLDER, "logFolder"),
                Map.entry(DashboardPreferences.Key.COLLECTIONS, "collections"),
                Map.entry(DashboardPreferences.Key.PROJECTS, "projects"),
                Map.entry(DashboardPreferences.Key.HEAP_SPINED64_SHARE, "spined64Share"),
                Map.entry(DashboardPreferences.Key.HEAP_ROCKS_SHARE, "rocksShare"),
                Map.entry(DashboardPreferences.Key.HEAP_CACHE_SHARE, "cacheShare"),
                Map.entry(DashboardPreferences.Key.HEAP_AXIOM_SHARE, "axiomShare"),
                Map.entry(DashboardPreferences.Key.HEAP_MEASURED_ROOM, "measuredRoom"),
                Map.entry(DashboardPreferences.Key.HEAP_MINIMUM, "minimumHeap"),
                Map.entry(DashboardPreferences.Key.HEAP_RESERVE_FLOOR, "reserveFloor"),
                Map.entry(DashboardPreferences.Key.HEAP_RESERVE_SHARE, "reserveShare"));
        DashboardPreferences preferences = new DashboardPreferences(root);
        assertThat(Arrays.stream(DashboardPreferences.Node.values())
                .map(node -> preferences.node(node).absolutePath().substring(root.absolutePath().length())))
                .containsExactly("", "/heap", "/heap/stores", "/dismissed-notices", "/opened", "/komet-pins");
        assertThat(DashboardPreferences.Node.DASHBOARD.stored()).isEqualTo("network/ike/dashboard");
    }

    @Test
    void valuesSavedUnderTheOldNamesReadThroughTheEnum() throws Exception {
        root.putBoolean("sortRecent", true);
        root.put("solor", "/Volumes/Data/Solor");
        root.node("heap").putDouble("rocksShare", 0.5);
        root.node("komet-pins").put("pizza-kb", "26.1019.900");
        root.node("dismissed-notices").put("newer:pizza-kb:1", "2026-11-08T00:00:00Z");
        DashboardPreferences preferences = new DashboardPreferences(root);

        assertThat(preferences.getBoolean(DashboardPreferences.Key.SORT_RECENT)).contains(true);
        assertThat(preferences.get(DashboardPreferences.Key.SOLOR)).contains("/Volumes/Data/Solor");
        assertThat(preferences.get(DashboardPreferences.Key.COLLECTIONS)).as("nothing saved: empty").isEmpty();
        assertThat(DashboardSettings.read(preferences.node(DashboardPreferences.Node.DASHBOARD), Path.of("/Users/someone"))
                .solor()).isEqualTo(Path.of("/Volumes/Data/Solor"));
        assertThat(HeapSizing.Rule.read(preferences.node(DashboardPreferences.Node.HEAP)).rocksShare()).isEqualTo(0.5);
        assertThat(new KometPins(preferences.node(DashboardPreferences.Node.KOMET_PINS)).of("pizza-kb")).contains("26.1019.900");
        assertThat(new NoticeDismissals(preferences.node(DashboardPreferences.Node.DISMISSED_NOTICES)).dismissed("newer:pizza-kb:1"))
                .isTrue();

        preferences.putBoolean(DashboardPreferences.Key.SORT_RECENT, false);
        assertThat(root.getBoolean("sortRecent", true)).as("written back under the same name").isFalse();
    }
}
