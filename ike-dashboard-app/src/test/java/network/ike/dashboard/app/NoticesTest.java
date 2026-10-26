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

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The computed notices of the landing page (the IKE Dashboard design, decision 14): each finding of the
 * dashboard's checks becomes one card with one action, work at risk first.
 */
class NoticesTest {

    private static final Instant NOW = Instant.parse("2026-10-24T20:00:00Z");
    private static final Coordinate PIZZA = new Coordinate("network.ike.data", "pizza-kb", "20250627.1-SNAPSHOT");
    private static final KometInstallation KOMET_1024 = komet("26.1024.1820");

    @Test
    void workAtRiskComesBeforeWhatCanBeDoneAndNewsComesLast() {
        KnowledgeBaseFamily pizza = family(store("pizza-kb", StoreKind.ROCKS, Map.of()));
        Path store = pizza.latest().orElseThrow().path();
        List<Notice> notices = Notices.compute(inputs(List.of(pizza), Map.of("pizza-kb", newer()),
                Map.of(store, new ChangeSetCheck.Finding(0, 2, 0, 5)), List.of(KOMET_1024),
                Optional.of(release("26.1025.900")), Optional.of(catalog(offer("solor-nuva-kb", NOW.minus(Duration.ofDays(1))))),
                Map.of()), id -> false);

        assertThat(notices).extracting(Notice::urgency).containsExactly(
                Notice.Urgency.AT_RISK, Notice.Urgency.ACTION, Notice.Urgency.ACTION, Notice.Urgency.ACTION);
        assertThat(notices.getFirst().title()).isEqualTo("2 change sets of Pizza need an upgrade");
        assertThat(notices.getFirst().action()).containsInstanceOf(NoticeAction.UpgradeChangeSets.class);
    }

    @Test
    void aNewerBuildOffersItsDownloadAsOfWhenNexusWasAsked() {
        KnowledgeBaseFamily pizza = family(store("pizza-kb", StoreKind.ROCKS, Map.of()));
        Notice.Freshness asked = new Notice.Freshness(NOW.minus(Duration.ofHours(3)), false);
        List<Notice> notices = Notices.compute(new Notices.Inputs(List.of(pizza), List.of(), Map.of("pizza-kb", newer()),
                Optional.of(asked), Map.of(), List.of(KOMET_1024), Optional.empty(), Optional.empty(), Map.of(), Set.of(), Optional.empty(), NOW),
                id -> false);

        assertThat(notices).singleElement().satisfies(notice -> {
            assertThat(notice.title()).isEqualTo("A newer build of Pizza is on Nexus");
            assertThat(notice.action()).contains(new NoticeAction.Download(PIZZA, "rkb"));
            assertThat(notice.freshness()).as("offline, it is shown as of the last check").contains(asked);
            assertThat(notice.id()).isEqualTo("newer:pizza-kb:20250627.1-20261024.001942-7");
        });
    }

    @Test
    void aDismissedNoticeIsLeftOut() {
        KnowledgeBaseFamily pizza = family(store("pizza-kb", StoreKind.ROCKS, Map.of()));
        List<Notice> notices = Notices.compute(inputs(List.of(pizza), Map.of("pizza-kb", newer()), Map.of(),
                List.of(KOMET_1024), Optional.empty(), Optional.empty(), Map.of()),
                "newer:pizza-kb:20250627.1-20261024.001942-7"::equals);

        assertThat(notices).isEmpty();
    }

    @Test
    void aKnowledgeBaseNotHereIsNewsOnlyWhileRecentlyDeployedAndTheRestAreCounted() {
        KnowledgeBaseFamily pizza = family(store("pizza-kb", StoreKind.ROCKS, Map.of()));
        NexusCatalog.Catalog catalog = catalog(
                offer("pizza-kb", NOW.minus(Duration.ofDays(1))),
                offer("solor-nuva-kb", NOW.minus(Duration.ofDays(2))),
                offer("rxnorm-kb", NOW.minus(Duration.ofDays(40))),
                offer("gudid-kb", NOW.minus(Duration.ofDays(90))));
        List<Notice> notices = Notices.compute(inputs(List.of(pizza), Map.of(), Map.of(), List.of(KOMET_1024),
                Optional.empty(), Optional.of(catalog), Map.of()), id -> false);

        assertThat(notices).extracting(Notice::title)
                .containsExactly("New on Nexus: solor-nuva-kb", "2 more knowledge bases on Nexus");
        assertThat(notices.getFirst().action()).contains(new NoticeAction.Download(
                new Coordinate("network.ike.data", "solor-nuva-kb", "1-SNAPSHOT"), "rkb"));
        assertThat(notices.getLast().action()).contains(new NoticeAction.Browse());
    }

    @Test
    void aProtobufIsNoNewsOfItsOwn() {
        NexusCatalog.Catalog catalog = catalog(
                new NexusCatalog.Offer(coordinate("snomedct-kb"), "unreasoned-pb", 52_000_000, NOW.minus(Duration.ofDays(1)),
                        "ike-snapshots"),
                offer("solor-kb", NOW.minus(Duration.ofDays(1))),
                new NexusCatalog.Offer(coordinate("solor-kb"), "reasoned-pb", 52_000_000, NOW.minus(Duration.ofDays(1)),
                        "ike-snapshots"));

        List<Notice> notices = Notices.compute(inputs(List.of(), Map.of(), Map.of(), List.of(KOMET_1024),
                Optional.empty(), Optional.of(catalog), Map.of()), id -> false);

        assertThat(notices).extracting(Notice::title).as("the Nexus window lists protobufs under their knowledge base")
                .containsExactly("New on Nexus: solor-kb");
        assertThat(notices.getFirst().action()).contains(new NoticeAction.Download(coordinate("solor-kb"), "rkb"));
    }

    @Test
    void aTrackedCollectionStaysAtHandHoweverLongAgoItWasDeployed() {
        KnowledgeBaseFamily pizza = family(store("pizza-kb", StoreKind.ROCKS, Map.of()));
        NexusCatalog.Catalog catalog = new NexusCatalog.Catalog(List.of(
                offer("pizza-kb", NOW.minus(Duration.ofDays(60))),
                offer("solor-nuva-kb", NOW.minus(Duration.ofDays(2))),
                offer("rxnorm-kb", NOW.minus(Duration.ofDays(40))),
                offer("solor-gudid-kb", NOW.minus(Duration.ofDays(90))),
                offer("dex-kb", NOW.minus(Duration.ofDays(90)))), Map.of(
                coordinate("pizza-kb"), inCollection("pizza-kb", "Pizza", "SOLOR"),
                coordinate("solor-nuva-kb"), inCollection("solor-nuva-kb", "SOLOR + NUVA", "SOLOR"),
                coordinate("rxnorm-kb"), inCollection("rxnorm-kb", "RxNorm", "SOLOR"),
                coordinate("solor-gudid-kb"), inCollection("solor-gudid-kb", "SOLOR + GUDID", "solor"),
                coordinate("dex-kb"), inCollection("dex-kb", "DeX", null)));

        List<Notice> notices = Notices.compute(inputs(List.of(pizza), Map.of(), Map.of(), List.of(KOMET_1024),
                Optional.empty(), Optional.of(catalog), Map.of()), id -> false);

        assertThat(notices).extracting(Notice::title).as("one recently deployed, the collection, and the rest counted")
                .containsExactly("New on Nexus: SOLOR + NUVA", "1 more knowledge base on Nexus",
                        "2 SOLOR knowledge bases not downloaded");
        Notice collection = notices.getLast();
        assertThat(collection.detail()).contains("RxNorm, SOLOR + GUDID").doesNotContain("Pizza");
        assertThat(collection.action()).contains(new NoticeAction.Browse());
        assertThat(collection.id()).isEqualTo("collection:SOLOR:RxNorm,SOLOR + GUDID");

        assertThat(Notices.compute(new Notices.Inputs(List.of(pizza), List.of(), Map.of(), Optional.empty(), Map.of(),
                List.of(KOMET_1024), Optional.empty(), Optional.of(catalog), Map.of(), Set.of(), Optional.empty(), NOW), id -> false))
                .extracting(Notice::title).as("tracking none, they are counted with the rest")
                .contains("3 more knowledge bases on Nexus");
    }

    @Test
    void anUnmanagedCopyIsNamedWhenNexusHasAManagedBuildOfIt() {
        KnowledgeBase oldCopy = new KnowledgeBase("solor-kb", Path.of("/Solor/solor-kb"), StoreKind.SPINED_32, 2_000_000_000L,
                Optional.empty(), Map.of(), Instant.parse("2026-10-16T13:02:00Z"));
        NexusCatalog.Catalog catalog = catalog(offer("solor-kb", NOW.minus(Duration.ofDays(1))));
        List<Notice> notices = Notices.compute(new Notices.Inputs(List.of(), List.of(oldCopy), Map.of(),
                Optional.of(new Notice.Freshness(NOW, true)), Map.of(), List.of(KOMET_1024), Optional.empty(),
                Optional.of(catalog), Map.of(), Set.of(), Optional.empty(), NOW), id -> false);

        assertThat(notices).singleElement().satisfies(notice -> {
            assertThat(notice.title()).isEqualTo("A managed build of solor-kb is on Nexus");
            assertThat(notice.detail()).contains("You have solor-kb, which the dashboard does not manage");
        });
    }

    @Test
    void aStoreNoInstalledKometOpensOffersTheKometThatDoes() {
        KnowledgeBaseFamily format3 = family(store("pizza-kb", StoreKind.ROCKS, Map.of(KnowledgeBase.ENTITY_FORMAT, "3")));
        KometRelease release = release("26.1025.900");
        List<Notice> notices = Notices.compute(inputs(List.of(format3), Map.of(), Map.of(), List.of(komet("26.1016.346")),
                Optional.of(release), Optional.empty(), Map.of()), id -> false);

        assertThat(notices).filteredOn(notice -> notice.title().startsWith("No installed Komet")).singleElement()
                .satisfies(notice -> assertThat(notice.action()).contains(new NoticeAction.InstallKomet(release)));
    }

    @Test
    void eachRepositoryInsideAStoreReportsItsOwnSyncState() {
        KnowledgeBaseFamily pizza = family(store("pizza-kb", StoreKind.ROCKS, Map.of()));
        Path store = pizza.latest().orElseThrow().path();
        Notice.Freshness checked = new Notice.Freshness(NOW, true);
        RepositorySync.Repository changeSets = new RepositorySync.Repository(store.resolve("changeSets"), "change sets");
        RepositorySync.Repository preferences = new RepositorySync.Repository(store.resolve("preferences"), "preferences");
        List<RepositorySync.Status> states = List.of(
                new RepositorySync.Status(changeSets, "main", 3, 1, RepositorySync.Remote.BEHIND, 2, Optional.of(checked), ""),
                new RepositorySync.Status(preferences, "main", 0, 0, RepositorySync.Remote.UNKNOWN, 0, Optional.empty(),
                        "Could not resolve host: github.com"));
        List<Notice> notices = Notices.compute(inputs(List.of(pizza), Map.of(), Map.of(), List.of(KOMET_1024),
                Optional.empty(), Optional.empty(), Map.of(store, states)), id -> false);

        assertThat(notices).extracting(Notice::urgency, Notice::title).containsExactly(
                org.assertj.core.groups.Tuple.tuple(Notice.Urgency.AT_RISK, "1 commit not pushed from Pizza, change sets"),
                org.assertj.core.groups.Tuple.tuple(Notice.Urgency.AT_RISK, "3 files not committed in Pizza, change sets"),
                org.assertj.core.groups.Tuple.tuple(Notice.Urgency.ACTION, "2 commits to pull into Pizza, change sets"),
                org.assertj.core.groups.Tuple.tuple(Notice.Urgency.NEWS, "Could not check Pizza, preferences against its remote"));
        assertThat(notices.get(2).freshness()).contains(checked);
        assertThat(notices.getLast().detail()).as("never shown as current when it could not be checked")
                .contains("whether it is behind is unknown");
    }

    @Test
    void aNewerKometIsOfferedAgainstTheNewestInstalled() {
        KometRelease release = release("26.1025.900");
        List<Notice> notices = Notices.compute(inputs(List.of(), Map.of(), Map.of(), List.of(KOMET_1024),
                Optional.of(release), Optional.empty(), Map.of()), id -> false);

        assertThat(notices).singleElement().satisfies(notice -> {
            assertThat(notice.title()).isEqualTo("Komet 26.1025.900 is available");
            assertThat(notice.action()).contains(new NoticeAction.InstallKomet(release));
        });
        assertThat(Notices.compute(inputs(List.of(), Map.of(), Map.of(), List.of(komet("26.1025.900")),
                Optional.of(release), Optional.empty(), Map.of()), id -> false)).as("not when it is installed").isEmpty();
    }

    @Test
    void aNewerDashboardIsOfferedWithOneActionAndOnlyWhenNewer() {
        KometRelease newer = dashboard("26.1026.15");
        SelfUpdate.Placement placement = new SelfUpdate.Placement(new SelfUpdate.Running(Path.of("/A/IKE Dashboard.app"),
                "26.1025.2202"), SelfUpdate.Location.IN_PLACE, Path.of("/A/IKE Dashboard.app"));

        assertThat(Notices.compute(withDashboard(new SelfUpdate.State(placement, Optional.of(newer))), id -> false))
                .singleElement().satisfies(notice -> {
                    assertThat(notice.title()).isEqualTo("IKE Dashboard 26.1026.15 is available");
                    assertThat(notice.detail()).startsWith("You have 26.1025.2202. It replaces this one");
                    assertThat(notice.action()).contains(new NoticeAction.UpdateDashboard(newer));
                    assertThat(notice.freshness()).as("as of when Nexus was asked").isPresent();
                });
        assertThat(Notices.compute(withDashboard(new SelfUpdate.State(placement, Optional.of(dashboard("26.1025.2202")))),
                id -> false)).as("not the version running").isEmpty();
    }

    @Test
    void aDashboardThatCannotChangeWhereItIsSaysWhereTheUpdateGoes() {
        SelfUpdate.Placement placement = new SelfUpdate.Placement(new SelfUpdate.Running(
                Path.of("/Applications/IKE Dashboard.app"), "26.1025.2202"), SelfUpdate.Location.APPLICATIONS,
                Path.of("/Users/someone/Applications/IKE Dashboard.app"));

        assertThat(Notices.compute(withDashboard(new SelfUpdate.State(placement, Optional.of(dashboard("26.1026.15")))),
                id -> false)).singleElement().extracting(Notice::detail).asString()
                .contains("This one is in /Applications, which you cannot change, so the new one goes in"
                        + " /Users/someone/Applications");
    }

    @Test
    void aTranslocatedDashboardAsksToBeMovedRatherThanUpdated() {
        SelfUpdate.Placement placement = SelfUpdate.place(new SelfUpdate.Running(Path.of(
                "/private/var/folders/x1/abc/T/AppTranslocation/3F1C2A/d/IKE Dashboard.app"), "26.1025.2011"),
                Path.of("/Users/someone"));

        assertThat(Notices.compute(withDashboard(new SelfUpdate.State(placement, Optional.of(dashboard("26.1026.15")))),
                id -> false)).singleElement().satisfies(notice -> {
                    assertThat(notice.title()).isEqualTo("Move the IKE Dashboard into your Applications folder");
                    assertThat(notice.action()).contains(new NoticeAction.MoveDashboard());
                });
    }

    private static Notices.Inputs withDashboard(SelfUpdate.State state) {
        return new Notices.Inputs(List.of(), List.of(), Map.of(), Optional.of(new Notice.Freshness(NOW, true)), Map.of(),
                List.of(KOMET_1024), Optional.empty(), Optional.empty(), Map.of(), Set.of(), Optional.of(state), NOW);
    }

    private static KometRelease dashboard(String version) {
        return new KometRelease(version, KometRelease.Source.NEXUS, URI.create("https://nexus.example/" + version), 1,
                Optional.empty(), Optional.empty(), Optional.of(new Coordinate(SelfUpdate.GROUP, SelfUpdate.ARTIFACT, version)),
                "osx-aarch64");
    }

    private static Notices.Inputs inputs(List<KnowledgeBaseFamily> families, Map<String, BuildStatus> statuses,
                                         Map<Path, ChangeSetCheck.Finding> changeSets, List<KometInstallation> komets,
                                         Optional<KometRelease> release, Optional<NexusCatalog.Catalog> catalog,
                                         Map<Path, List<RepositorySync.Status>> sync) {
        return new Notices.Inputs(families, List.of(), statuses, Optional.of(new Notice.Freshness(NOW, true)), changeSets, komets,
                release, catalog, sync, Set.of(DashboardSettings.DEFAULT_COLLECTIONS), Optional.empty(), NOW);
    }

    private static BuildStatus newer() {
        return new BuildStatus(BuildStatus.State.NEWER, "newer build", Optional.of(
                new SnapshotMetadata.Deploy("20250627.1-20261024.001942-7", Instant.parse("2026-10-24T00:20:14Z"))));
    }

    private static KnowledgeBase store(String name, StoreKind kind, Map<String, String> attributes) {
        Path path = Path.of("/Solor", name);
        KnowledgeBaseDescriptor descriptor = new KnowledgeBaseDescriptor(1, PIZZA, Optional.empty(), "Pizza",
                "rocks", 3, Instant.parse("2026-10-23T23:23:18Z"), "public", "META-INF/ike/icon.png");
        return new KnowledgeBase(name, path, kind, 1_000_000, Optional.of(PIZZA), attributes,
                Instant.parse("2026-10-23T23:23:18Z"), DescriptorCheck.met(descriptor));
    }

    private static KnowledgeBaseFamily family(KnowledgeBase store) {
        return new KnowledgeBaseFamily(store.name(), List.of(store));
    }

    private static KometInstallation komet(String version) {
        return new KometInstallation("Komet Desktop " + version, version, Path.of("/Applications/Komet " + version + ".app"),
                Path.of("/Applications/Komet " + version + ".app/Contents/MacOS/Komet"));
    }

    private static KometRelease release(String version) {
        return new KometRelease(version, KometRelease.Source.NEXUS, URI.create("https://nexus.example/komet.zip"),
                234_000_000, Optional.empty(), Optional.empty(), Optional.empty(), "osx-aarch64");
    }

    private static NexusCatalog.Offer offer(String artifactId, Instant updated) {
        return new NexusCatalog.Offer(new Coordinate("network.ike.data", artifactId, "1-SNAPSHOT"), "rkb", 52_000_000,
                updated, "ike-snapshots");
    }

    private static Coordinate coordinate(String artifactId) {
        return new Coordinate("network.ike.data", artifactId, "1-SNAPSHOT");
    }

    private static NexusCatalog.Described inCollection(String artifactId, String displayName, String collection) {
        return new NexusCatalog.Described(new KnowledgeBaseDescriptor(1, coordinate(artifactId), Optional.empty(), displayName,
                "rocks", 3, Instant.parse("2026-08-01T00:00:00Z"), "restricted", "META-INF/ike/icon.png",
                KnowledgeBaseDescriptor.Memory.NONE, Optional.ofNullable(collection)), new byte[] {1});
    }

    private static NexusCatalog.Catalog catalog(NexusCatalog.Offer... offers) {
        return new NexusCatalog.Catalog(List.of(offers), Map.of());
    }
}
