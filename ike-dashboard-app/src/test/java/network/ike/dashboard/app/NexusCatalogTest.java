package network.ike.dashboard.app;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The dashboard reads Nexus's asset search as Nexus writes it, keeping the newest deploy of each store.
 * The record is the one Nexus returned for pizza-kb on 2026-10-24, shortened.
 */
class NexusCatalogTest {

    private static final String PAGE = """
            {
              "items" : [ {
                "downloadUrl" : "https://nexus.tinkar.org/repository/ike-restricted-snapshots/network/ike/data/pizza-kb/20250627.1-SNAPSHOT/pizza-kb-20250627.1-20261016.153228-1-rkb.zip",
                "path" : "/network/ike/data/pizza-kb/20250627.1-SNAPSHOT/pizza-kb-20250627.1-20261016.153228-1-rkb.zip",
                "repository" : "ike-restricted-snapshots",
                "checksum" : { "sha1" : "be50a7c67e80f979749513943a4e51b8bea6e828" },
                "lastModified" : "2026-10-16T15:32:40.775+00:00",
                "lastDownloaded" : null,
                "fileSize" : 1795725,
                "maven2" : { "classifier" : "rkb", "extension" : "zip", "artifactId" : "pizza-kb",
                             "version" : "20250627.1-20261016.153228-1", "groupId" : "network.ike.data" }
              }, {
                "path" : "/network/ike/data/pizza-kb/20250627.1-SNAPSHOT/pizza-kb-20250627.1-20261016.153228-1-reasoned-pb.zip",
                "repository" : "ike-restricted-snapshots",
                "lastModified" : "2026-10-16T15:32:41.000+00:00",
                "fileSize" : 99,
                "maven2" : { "classifier" : "reasoned-pb", "extension" : "zip", "artifactId" : "pizza-kb",
                             "version" : "20250627.1-20261016.153228-1", "groupId" : "network.ike.data" }
              } ],
              "continuationToken" : "8a2f\\u0031"
            }
            """;

    @Test
    void aPageReadsAsTheStoresOnIt() {
        NexusCatalog.Page page = NexusCatalog.parse(PAGE);

        assertThat(page.offers()).containsExactly(new NexusCatalog.Offer(
                new Coordinate("network.ike.data", "pizza-kb", "20250627.1-SNAPSHOT"), "rkb", 1_795_725,
                Instant.parse("2026-10-16T15:32:40.775Z"), "ike-restricted-snapshots"));
        assertThat(page.continuationToken()).contains("8a2f1");
    }

    @Test
    void theNewestDeployOfEachStoreIsKept() {
        Coordinate pizza = new Coordinate("network.ike.data", "pizza-kb", "20250627.1-SNAPSHOT");
        NexusCatalog.Offer older = new NexusCatalog.Offer(pizza, "rkb", 1, Instant.parse("2026-10-16T15:32:40Z"), "r");
        NexusCatalog.Offer newer = new NexusCatalog.Offer(pizza, "rkb", 2, Instant.parse("2026-10-24T00:19:42Z"), "r");
        NexusCatalog.Offer spined = new NexusCatalog.Offer(pizza, "reasoned-sa", 3, Instant.parse("2026-10-24T00:19:43Z"), "r");

        assertThat(NexusCatalog.newest(List.of(spined, older, newer))).containsExactly(newer, spined);
    }

    @Test
    void searchGoesToTheServerOfTheRepository() {
        assertThat(NexusCatalog.searchUri(RepositoryAccess.RESTRICTED_URL, Map.of("group", "network.ike.data")))
                .hasToString("https://nexus.tinkar.org/service/rest/v1/search/assets?group=network.ike.data");
    }
}
