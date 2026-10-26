package network.ike.dashboard.app;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Copies of one knowledge base fold into one row: the dashboard does not show the same knowledge base
 * as many choices. The names are the ones in Graphlet's {@code ~/Solor} on 2026-10-23.
 */
class KnowledgeBaseFamilyTest {

    @Test
    void copySuffixesComeOffFolderNames() {
        assertThat(List.of("*DeX-4-17-2026", "*DeX-4-17-2026-64bit", "*DeX-4-17-2026-64bit-4",
                "*DeX-4-17-2026-migration-2-pb.zip", "*DeX-4-17-2026-migration-pb.zip", "*DeX-4-17-2026.zip",
                "DeX-2026-10-08-pb.zip")).allSatisfy(name -> assertThat(KnowledgeBaseFamily.familyName(name)).isEqualTo("DeX"));
        assertThat(List.of("*SNOMED Rocks", "*SNOMED Rocks.zip", "SNOMED-2026-10-08-pb.zip"))
                .allSatisfy(name -> assertThat(KnowledgeBaseFamily.familyName(name)).isEqualTo("SNOMED"));
        assertThat(List.of("solor-gudid-kb", "solor-gudid-kb-1-20261016-033650.rkb",
                "solor-gudid-kb-1-20261016.033650-1-reasoned-pb.zip", "solor-gudid-kb-rocks"))
                .allSatisfy(name -> assertThat(KnowledgeBaseFamily.familyName(name)).isEqualTo("solor-gudid-kb"));
        assertThat(KnowledgeBaseFamily.familyName("ike-starter-set-2026-10-08-pb.zip")).isEqualTo("ike-starter-set");
    }

    @Test
    void distinctKnowledgeBasesStayApart() {
        assertThat(KnowledgeBaseFamily.familyName("ike-kb")).isEqualTo("ike-kb");
        assertThat(KnowledgeBaseFamily.familyName("ike-kb-anf-editor-demo")).isEqualTo("ike-kb-anf-editor-demo");
        assertThat(KnowledgeBaseFamily.familyName("solor-kb-anf-editor-demo")).isEqualTo("solor-kb-anf-editor-demo");
        assertThat(KnowledgeBaseFamily.familyName("snomed-starter-data")).isEqualTo("snomed-starter-data");
        assertThat(KnowledgeBaseFamily.familyName("format3-sa64-demo")).isEqualTo("format3-sa64-demo");
    }

    @Test
    void theNewestOpenableCopyIsTheOneOpenUses() {
        KnowledgeBase oldRocks = copy("*DeX-4-17-2026", StoreKind.ROCKS, "2026-06-29T21:21:00Z", Map.of());
        KnowledgeBase newRocks = copy("*DeX-4-17-2026-64bit-4", StoreKind.ROCKS, "2026-10-09T16:02:00Z", Map.of());
        KnowledgeBase newestZip = copy("*DeX-4-17-2026-migration-4-pb.zip", StoreKind.CHANGE_SETS,
                "2026-10-09T15:58:00Z", Map.of());

        List<KnowledgeBaseFamily> families = KnowledgeBaseFamily.group(List.of(oldRocks, newestZip, newRocks));

        assertThat(families).hasSize(1);
        assertThat(families.getFirst().members()).containsExactly(newRocks, newestZip, oldRocks);
        assertThat(families.getFirst().latest()).contains(newRocks);
    }

    @Test
    void aStoreThatNamesItsArtifactJoinsThatFamilyAndItsDeployDatesIt() {
        KnowledgeBase downloaded = new KnowledgeBase("pizza-kb-20250627.1-20261024.001942-7", Path.of("x"), StoreKind.ROCKS,
                0, Optional.of(new Coordinate("network.ike.data", "pizza-kb", "20250627.1-SNAPSHOT")),
                Map.of(KnowledgeBase.RESOLVED_VERSION, "20250627.1-20261024.001942-7"), Instant.parse("2026-01-01T00:00:00Z"));
        KnowledgeBase older = new KnowledgeBase("pizza-kb", Path.of("y"), StoreKind.ROCKS,
                0, Optional.of(new Coordinate("network.ike.data", "pizza-kb", "20250627.1-SNAPSHOT")),
                Map.of(KnowledgeBase.RESOLVED_VERSION, "20250627.1-20261023.232318-6"), Instant.parse("2026-10-24T00:00:00Z"));

        KnowledgeBaseFamily pizza = KnowledgeBaseFamily.group(List.of(older, downloaded)).getFirst();

        assertThat(pizza.name()).isEqualTo("pizza-kb");
        assertThat(pizza.latest()).as("the deploy's date, not the folder's").contains(downloaded);
        assertThat(pizza.latestIdentified()).contains(downloaded);
    }

    @Test
    void theKometChosenIsTheNewestThatReadsTheStore() {
        KometInstallation october16 = komet("26.1016.346");
        KometInstallation october23 = komet("26.1023.12");
        KnowledgeBase format3 = copy("pizza-kb", StoreKind.ROCKS, "2026-10-24T00:20:00Z",
                Map.of(KnowledgeBase.ENTITY_FORMAT, "3"));
        KnowledgeBase legacy = copy("solor-kb", StoreKind.SPINED_32, "2026-10-16T13:02:00Z", Map.of());
        KnowledgeBase zip = copy("DeX-2026-10-08-pb.zip", StoreKind.CHANGE_SETS, "2026-10-08T14:44:00Z", Map.of());

        assertThat(LauncherChoice.choose(List.of(october23, october16), format3)).contains(october23);
        assertThat(LauncherChoice.choose(List.of(october16), format3)).isEmpty();
        assertThat(LauncherChoice.requirement(format3)).contains("Komet 26.1021 or later");
        assertThat(LauncherChoice.choose(List.of(october23, october16), legacy)).contains(october23);
        assertThat(LauncherChoice.requirement(legacy)).isEmpty();
        assertThat(LauncherChoice.choose(List.of(october23), zip)).as("Komet loads a zip, it does not open one").isEmpty();
        KnowledgeBase spined64 = copy("format3-sa64-demo", StoreKind.SPINED_64, "2026-10-23T19:22:00Z", Map.of());
        assertThat(LauncherChoice.choose(List.of(october16), spined64)).as("the 64-bit layout arrived 2026-10-21").isEmpty();
        assertThat(LauncherChoice.choose(List.of(october23, october16), spined64)).contains(october23);
    }

    @Test
    void foldersNoProviderListsAreNotKnowledgeBases() {
        KnowledgeBase komet = copy("komet", StoreKind.UNKNOWN, "2026-08-21T15:48:00Z", Map.of());
        KnowledgeBase solorKb = copy("solor-kb", StoreKind.SPINED_32, "2026-10-16T13:02:00Z", Map.of());

        assertThat(KnowledgeBaseFamily.group(List.of(komet, solorKb))).extracting(KnowledgeBaseFamily::name)
                .containsExactly("solor-kb");
    }

    private static KnowledgeBase copy(String name, StoreKind kind, String modified, Map<String, String> attributes) {
        // A macOS folder may start with '*'; no Windows file name may hold one, and only the name matters here.
        return new KnowledgeBase(name, Path.of(name.replace('*', '_')), kind, 0, Optional.empty(), attributes, Instant.parse(modified));
    }

    private static KometInstallation komet(String version) {
        return new KometInstallation("Komet " + version, version, Path.of(version + ".app"), Path.of(version));
    }
}
