package network.ike.dashboard.app;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The dashboard reads Nexus's asset search as Nexus writes it, keeping the newest deploy of each store
 * and protobuf.
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
    void aPageReadsAsTheStoresAndProtobufsOnIt() {
        NexusCatalog.Page page = NexusCatalog.parse(PAGE);

        Coordinate pizza = new Coordinate("network.ike.data", "pizza-kb", "20250627.1-SNAPSHOT");
        assertThat(page.offers()).containsExactly(
                new NexusCatalog.Offer(pizza, "rkb", 1_795_725, Instant.parse("2026-10-16T15:32:40.775Z"),
                        "ike-restricted-snapshots"),
                new NexusCatalog.Offer(pizza, "reasoned-pb", 99, Instant.parse("2026-10-16T15:32:41Z"),
                        "ike-restricted-snapshots"));
        assertThat(page.offers()).extracting(NexusCatalog.Offer::label, NexusCatalog.Offer::store).containsExactly(
                org.assertj.core.groups.Tuple.tuple("Rocks", true),
                org.assertj.core.groups.Tuple.tuple("protobuf, reasoned", false));
        assertThat(page.continuationToken()).contains("8a2f1");
    }

    @Test
    void descriptorsAndIconsAreReadAtTheirArtifactsBaseVersion() {
        String answer = """
                {
                  "items" : [ {
                    "downloadUrl" : "https://nexus.tinkar.org/repository/ike-restricted-snapshots/network/ike/data/pizza-kb/20250627.1-SNAPSHOT/pizza-kb-20250627.1-20261024.140000-8-kb-descriptor.properties",
                    "path" : "/network/ike/data/pizza-kb/20250627.1-SNAPSHOT/pizza-kb-20250627.1-20261024.140000-8-kb-descriptor.properties",
                    "lastModified" : "2026-10-24T14:00:10.000+00:00",
                    "maven2" : { "classifier" : "kb-descriptor", "extension" : "properties", "artifactId" : "pizza-kb",
                                 "version" : "20250627.1-20261024.140000-8", "groupId" : "network.ike.data" }
                  }, {
                    "downloadUrl" : "https://nexus.tinkar.org/repository/ike-restricted-snapshots/network/ike/data/pizza-kb/20250627.1-SNAPSHOT/pizza-kb-20250627.1-20261024.140000-8-kb-descriptor.properties.sha1",
                    "path" : "/network/ike/data/pizza-kb/20250627.1-SNAPSHOT/pizza-kb-20250627.1-20261024.140000-8-kb-descriptor.properties.sha1",
                    "maven2" : { "classifier" : "kb-descriptor", "extension" : "properties.sha1", "artifactId" : "pizza-kb",
                                 "version" : "20250627.1-20261024.140000-8", "groupId" : "network.ike.data" }
                  } ]
                }
                """;

        assertThat(NexusCatalog.parseAttachments(answer, "kb-descriptor", "properties")).containsExactly(new NexusCatalog.Attachment(
                new Coordinate("network.ike.data", "pizza-kb", "20250627.1-SNAPSHOT"),
                java.net.URI.create("https://nexus.tinkar.org/repository/ike-restricted-snapshots/network/ike/data/pizza-kb/20250627.1-SNAPSHOT/pizza-kb-20250627.1-20261024.140000-8-kb-descriptor.properties"),
                Instant.parse("2026-10-24T14:00:10Z")));
        assertThat(NexusCatalog.parseAttachments(answer, "icon", "png")).isEmpty();
    }

    @Test
    void onlyKnowledgeBasesWithADescriptorAtASupportedLevelAreOffered() {
        String levelOne = "descriptorLevel=1\ngroupId=network.ike.data\nartifactId=pizza-kb\nversion=20250627.1-SNAPSHOT\n"
                + "displayName=Pizza Knowledge Base\nlayout=spined-64\nentityFormat=3\nassembledAt=2026-10-24T14\\:05\\:03Z\n"
                + "access=restricted\nicon=META-INF/ike/icon.png\n";
        Coordinate pizza = new Coordinate("network.ike.data", "pizza-kb", "20250627.1-SNAPSHOT");
        Coordinate other = new Coordinate("network.ike.data", "other-kb", "1-SNAPSHOT");

        assertThat(NexusCatalog.describedBy(levelOne.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1)))
                .map(KnowledgeBaseDescriptor::displayName).contains("Pizza Knowledge Base");
        assertThat(NexusCatalog.describedBy(levelOne.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1)))
                .flatMap(KnowledgeBaseDescriptor::collection).as("optional at level 1").isEmpty();
        assertThat(NexusCatalog.describedBy((levelOne + "collection=SOLOR\n").getBytes(java.nio.charset.StandardCharsets.ISO_8859_1)))
                .hasValueSatisfying(descriptor -> {
                    assertThat(descriptor.collection()).contains("SOLOR");
                    assertThat(descriptor.inCollection(java.util.Set.of("solor"))).as("matched without regard to case").isTrue();
                    assertThat(descriptor.inCollection(java.util.Set.of())).isFalse();
                });
        assertThat(NexusCatalog.describedBy(levelOne.replace("descriptorLevel=1", "descriptorLevel=9")
                .getBytes(java.nio.charset.StandardCharsets.ISO_8859_1))).isEmpty();
        NexusCatalog.Offer described = new NexusCatalog.Offer(pizza, "rkb", 1, Instant.EPOCH, "r");
        NexusCatalog.Offer undescribed = new NexusCatalog.Offer(other, "rkb", 1, Instant.EPOCH, "r");
        NexusCatalog.Catalog catalog = NexusCatalog.Catalog.of(List.of(described, undescribed), Map.of(pizza,
                new NexusCatalog.Described(NexusCatalog.describedBy(levelOne.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1))
                        .orElseThrow(), new byte[] {1})));
        assertThat(catalog.offers()).containsExactly(described);
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
