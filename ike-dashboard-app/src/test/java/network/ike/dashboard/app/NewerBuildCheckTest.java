package network.ike.dashboard.app;

import network.ike.dashboard.download.Credentials;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The newer-build check reads a snapshot's {@code maven-metadata.xml} and compares its newest deploy
 * with what the store says about itself; the login comes from {@code settings.xml}.
 */
class NewerBuildCheckTest {

    /** The shape Nexus serves for pizza-kb's snapshot, trimmed to two deploys of two classifiers. */
    private static final String METADATA = """
            <?xml version="1.0" encoding="UTF-8"?>
            <metadata modelVersion="1.1.0">
              <groupId>network.ike.data</groupId>
              <artifactId>pizza-kb</artifactId>
              <version>20250627.1-SNAPSHOT</version>
              <versioning>
                <snapshotVersions>
                  <snapshotVersion>
                    <classifier>rkb</classifier>
                    <extension>zip</extension>
                    <value>20250627.1-20261023.232318-6</value>
                    <updated>20261023232340</updated>
                  </snapshotVersion>
                  <snapshotVersion>
                    <classifier>rkb</classifier>
                    <extension>zip</extension>
                    <value>20250627.1-20261024.001942-7</value>
                    <updated>20261024002014</updated>
                  </snapshotVersion>
                  <snapshotVersion>
                    <classifier>reasoned-pb</classifier>
                    <extension>zip</extension>
                    <value>20250627.1-20261024.001942-7</value>
                    <updated>20261024002014</updated>
                  </snapshotVersion>
                </snapshotVersions>
              </versioning>
            </metadata>
            """;

    @Test
    void theNewestDeployOfAClassifierIsRead() throws IOException {
        Optional<SnapshotMetadata.Deploy> newest = SnapshotMetadata.newest(
                new ByteArrayInputStream(METADATA.getBytes(StandardCharsets.UTF_8)), "rkb", "zip");

        assertThat(newest).contains(new SnapshotMetadata.Deploy("20250627.1-20261024.001942-7",
                Instant.parse("2026-10-24T00:20:14Z")));
        assertThat(SnapshotMetadata.newest(new ByteArrayInputStream(METADATA.getBytes(StandardCharsets.UTF_8)),
                "reasoned-sa", "zip")).isEmpty();
    }

    @Test
    void aDownloadedStoreIsCurrentOnlyAtTheNewestDeploy() {
        SnapshotMetadata.Deploy newest = new SnapshotMetadata.Deploy("20250627.1-20261024.001942-7",
                Instant.parse("2026-10-24T00:20:14Z"));

        assertThat(NewerBuildCheck.compare(store(Map.of(KnowledgeBase.RESOLVED_VERSION, "20250627.1-20261024.001942-7")),
                newest).state()).isEqualTo(BuildStatus.State.CURRENT);
        assertThat(NewerBuildCheck.compare(store(Map.of(KnowledgeBase.RESOLVED_VERSION, "20250627.1-20261023.232318-6")),
                newest).state()).isEqualTo(BuildStatus.State.NEWER);
    }

    @Test
    void anAssembledStoreIsJudgedByWhenItWasAssembled() {
        SnapshotMetadata.Deploy newest = new SnapshotMetadata.Deploy("20250627.1-20261024.001942-7",
                Instant.parse("2026-10-24T00:20:14Z"));

        assertThat(NewerBuildCheck.compare(store(Map.of(KnowledgeBase.ASSEMBLED_AT, "2026-10-24T00:20:08Z")), newest)
                .state()).as("its own deploy, seconds later").isEqualTo(BuildStatus.State.CURRENT);
        assertThat(NewerBuildCheck.compare(store(Map.of(KnowledgeBase.ASSEMBLED_AT, "2026-10-21T12:30:08Z")), newest)
                .state()).as("deployed days later").isEqualTo(BuildStatus.State.NEWER);
        assertThat(NewerBuildCheck.compare(store(Map.of()), newest).state()).isEqualTo(BuildStatus.State.UNKNOWN);
    }

    @Test
    void anUnidentifiedStoreIsNotAskedAbout() {
        NewerBuildCheck check = new NewerBuildCheck(java.net.http.HttpClient.newHttpClient(),
                new RepositoryAccess("http://127.0.0.1:9/", "none", Optional.empty()));
        KnowledgeBase solorKb = new KnowledgeBase("solor-kb", Path.of("solor-kb"), StoreKind.SPINED_32, 0,
                Optional.empty(), Map.of());

        assertThat(check.check(solorKb).state()).isEqualTo(BuildStatus.State.UNIDENTIFIED);
    }

    @Test
    void theMetadataAddressFollowsTheMavenLayout() {
        assertThat(NewerBuildCheck.metadataUri(RepositoryAccess.RESTRICTED_URL,
                new Coordinate("network.ike.data", "pizza-kb", "20250627.1-SNAPSHOT")).toString())
                .isEqualTo("https://nexus.tinkar.org/repository/ike-restricted/network/ike/data/pizza-kb/"
                        + "20250627.1-SNAPSHOT/maven-metadata.xml");
    }

    @Test
    void theLoginComesFromSettingsXmlWithItsEnvironmentPlaceholdersFilled(@TempDir Path home) throws IOException {
        Path settings = Files.writeString(home.resolve("settings.xml"), """
                <settings>
                  <servers>
                    <server>
                      <id>ike-restricted</id>
                      <username>${env.IKE_USER}</username>
                      <password>${env.IKE_PWD_RELEASES}</password>
                    </server>
                  </servers>
                </settings>
                """);

        RepositoryAccess filled = RepositoryAccess.fromSettings(RepositoryAccess.RESTRICTED_URL, "ike-restricted",
                settings, Map.of("IKE_USER", "reader", "IKE_PWD_RELEASES", "s3cret"));
        RepositoryAccess unfilled = RepositoryAccess.fromSettings(RepositoryAccess.RESTRICTED_URL, "ike-restricted",
                settings, Map.of("IKE_USER", "reader"));

        assertThat(filled.credentials()).isPresent();
        assertThat(filled.credentials().get().username()).isEqualTo("reader");
        assertThat(NewerBuildCheck.basic(filled.credentials().get())).isEqualTo(
                "Basic " + Base64.getEncoder().encodeToString("reader:s3cret".getBytes(StandardCharsets.UTF_8)));
        assertThat(unfilled.credentials()).as("a placeholder without a value gives no login").isEmpty();
        assertThat(RepositoryAccess.fromSettings(RepositoryAccess.RESTRICTED_URL, "ike-restricted",
                home.resolve("absent.xml"), Map.of()).credentials()).isEmpty();
    }

    @Test
    void aLoginIsSentAsHttpBasic() {
        assertThat(NewerBuildCheck.basic(new Credentials("a", "b".toCharArray()))).isEqualTo("Basic YTpi");
    }

    private static KnowledgeBase store(Map<String, String> attributes) {
        return new KnowledgeBase("pizza-kb", Path.of("pizza-kb"), StoreKind.ROCKS, 0,
                Optional.of(new Coordinate("network.ike.data", "pizza-kb", "20250627.1-SNAPSHOT")), attributes);
    }
}
