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

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * The computed notices of the landing page (the IKE Dashboard design, decision 14): facts about this
 * machine against the world, which no one writes. Each comes from a check the dashboard already makes;
 * this only turns their findings into cards, each with one action, ordered by urgency. It does no IO.
 */
public final class Notices {

    /** How recently a knowledge base must have been deployed for Nexus to announce it as new. */
    static final Duration NEW_ON_NEXUS = Duration.ofDays(14);

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    private Notices() {
    }

    /**
     * What the notices are computed from: the findings of the dashboard's checks so far.
     *
     * @param families     the managed knowledge bases in {@code ~/Solor}
     * @param unmanaged    the entries in {@code ~/Solor} the dashboard does not manage
     * @param statuses     the newer-build check's finding for each, by name
     * @param nexusChecked when Nexus was last asked, and whether that worked
     * @param changeSets   the change-set check's finding for each store, by its folder
     * @param komets       the Komets installed, newest first
     * @param kometRelease the newest Komet build found, if one was
     * @param catalog      what Nexus offers, once asked
     * @param sync         the sync state of the repositories inside each store, by its folder
     * @param collections  the collections whose knowledge bases are kept at hand, downloaded or not
     * @param now          the time the notices are for
     */
    public record Inputs(List<KnowledgeBaseFamily> families, List<KnowledgeBase> unmanaged, Map<String, BuildStatus> statuses,
                         Optional<Notice.Freshness> nexusChecked, Map<Path, ChangeSetCheck.Finding> changeSets,
                         List<KometInstallation> komets, Optional<KometRelease> kometRelease,
                         Optional<NexusCatalog.Catalog> catalog, Map<Path, List<RepositorySync.Status>> sync,
                         Set<String> collections, Instant now) {

        /**
         * Copies the collections.
         *
         * @param families     the managed knowledge bases
         * @param unmanaged    the entries not managed
         * @param statuses     the newer-build findings
         * @param nexusChecked when Nexus was last asked
         * @param changeSets   the change-set findings
         * @param komets       the Komets installed
         * @param kometRelease the newest Komet build found
         * @param catalog      what Nexus offers
         * @param sync         the repositories' sync states
         * @param collections  the collections tracked
         * @param now          the time
         */
        public Inputs {
            families = List.copyOf(families);
            unmanaged = List.copyOf(unmanaged);
            statuses = Map.copyOf(statuses);
            changeSets = Map.copyOf(changeSets);
            komets = List.copyOf(komets);
            sync = Map.copyOf(sync);
            collections = Set.copyOf(collections);
        }
    }

    /**
     * The notices, most urgent first, without those the person dismissed.
     *
     * @param inputs    the findings
     * @param dismissed whether a notice's id was dismissed
     * @return the notices
     */
    public static List<Notice> compute(Inputs inputs, Predicate<String> dismissed) {
        List<Notice> notices = new ArrayList<>();
        for (KnowledgeBaseFamily family : inputs.families()) {
            family.latest().ifPresent(copy -> {
                inputs.sync().getOrDefault(copy.path(), List.of())
                        .forEach(status -> sync(notices, family, status));
                changeSets(notices, family, copy, inputs.changeSets().getOrDefault(copy.path(), ChangeSetCheck.Finding.NONE));
                noKomet(notices, family, copy, inputs);
            });
            newerBuild(notices, family, inputs);
        }
        newerKomet(notices, inputs);
        newOnNexus(notices, inputs);
        return notices.stream()
                .filter(notice -> !dismissed.test(notice.id()))
                .sorted(Comparator.comparing(Notice::urgency).thenComparing(notice -> notice.title().toLowerCase(Locale.ROOT)))
                .toList();
    }

    private static void sync(List<Notice> notices, KnowledgeBaseFamily family, RepositorySync.Status status) {
        String where = family.displayName() + ", " + status.repository().label();
        Path folder = status.repository().folder();
        Optional<NoticeAction> show = Optional.of(new NoticeAction.Show(family.name()));
        if (status.uncommitted() > 0) {
            notices.add(new Notice("sync-uncommitted:" + folder + ":" + status.uncommitted(), Notice.Urgency.AT_RISK,
                    plural(status.uncommitted(), "file", "files") + " not committed in " + where,
                    "Commit them, so they are kept in the repository and can be pushed. " + howToSync(status),
                    Optional.of(family.name()), show, Optional.empty()));
        }
        if (status.ahead() > 0) {
            notices.add(new Notice("sync-ahead:" + folder + ":" + status.ahead(), Notice.Urgency.AT_RISK,
                    plural(status.ahead(), "commit", "commits") + " not pushed from " + where,
                    "Until they are pushed they are only on this machine. " + howToSync(status),
                    Optional.of(family.name()), show, Optional.empty()));
        }
        switch (status.remote()) {
            case BEHIND -> notices.add(new Notice("sync-behind:" + folder + ":" + status.behind(), Notice.Urgency.ACTION,
                    (status.behind() < 0 ? "Commits" : plural(status.behind(), "commit", "commits")) + " to pull into " + where,
                    "The remote has work this copy does not; pull it before you change the same things. " + howToSync(status),
                    Optional.of(family.name()), show, status.freshness()));
            case UNKNOWN -> notices.add(new Notice("sync-unknown:" + folder, Notice.Urgency.NEWS,
                    "Could not check " + where + " against its remote",
                    (status.problem().isBlank() ? "" : status.problem() + ". ")
                            + status.freshness().map(last -> "Last checked " + WHEN.format(last.checkedAt()) + ".")
                            .orElse("It has not been checked yet, so whether it is behind is unknown."),
                    Optional.of(family.name()), show, status.freshness()));
            case NONE, UNCHECKED, CURRENT -> {
            }
        }
    }

    /** Komet's sync keeps the change sets; any other repository in a store is synced with git itself, for now. */
    private static String howToSync(RepositorySync.Status status) {
        return status.repository().folder().getFileName() != null
                && status.repository().folder().getFileName().toString().equals("changeSets")
                ? "Komet's sync does this." : "Use git in " + status.repository().folder() + ".";
    }

    private static void changeSets(List<Notice> notices, KnowledgeBaseFamily family, KnowledgeBase copy,
                                   ChangeSetCheck.Finding finding) {
        if (finding.needed() == 0) {
            return;
        }
        notices.add(new Notice("change-sets:" + copy.path() + ":" + finding.needed(), Notice.Urgency.AT_RISK,
                plural(finding.needed(), "change set", "change sets") + " of " + family.displayName() + " need an upgrade",
                finding.text() + ".", Optional.of(family.name()),
                Optional.of(new NoticeAction.UpgradeChangeSets(copy)), Optional.empty()));
    }

    private static void noKomet(List<Notice> notices, KnowledgeBaseFamily family, KnowledgeBase copy, Inputs inputs) {
        if (!copy.kind().opensInKomet() || LauncherChoice.choose(inputs.komets(), copy).isPresent()) {
            return;
        }
        Optional<KometRelease> fix = inputs.kometRelease().filter(release -> LauncherChoice.reads(release.version(), copy));
        String needs = LauncherChoice.requirement(copy).map(requirement -> "It needs " + requirement + ". ").orElse("");
        notices.add(new Notice("no-komet:" + copy.path() + ":" + fix.map(KometRelease::version).orElse(""),
                Notice.Urgency.ACTION, "No installed Komet opens " + family.displayName(),
                needs + fix.map(release -> "Komet " + release.version() + " does.").orElse("No Komet build found reads it yet."),
                Optional.of(family.name()),
                Optional.of(fix.<NoticeAction>map(NoticeAction.InstallKomet::new).orElse(new NoticeAction.Show(family.name()))),
                Optional.empty()));
    }

    private static void newerBuild(List<Notice> notices, KnowledgeBaseFamily family, Inputs inputs) {
        BuildStatus status = inputs.statuses().getOrDefault(family.name(), BuildStatus.UNCHECKED);
        if (status.state() != BuildStatus.State.NEWER) {
            return;
        }
        family.latestIdentified().ifPresent(identified -> identified.coordinate().ifPresent(coordinate ->
                notices.add(new Notice("newer:" + family.name() + ":" + status.newest().map(SnapshotMetadata.Deploy::value)
                        .orElse(""), Notice.Urgency.ACTION, "A newer build of " + family.displayName() + " is on Nexus",
                        status.newest().map(deploy -> "Deployed " + WHEN.format(deploy.updated()) + ". ").orElse("")
                                + "It downloads beside the copy you have, which stays as it is.",
                        Optional.of(family.name()),
                        Optional.of(new NoticeAction.Download(coordinate, identified.kind().classifier())),
                        inputs.nexusChecked()))));
    }

    private static void newerKomet(List<Notice> notices, Inputs inputs) {
        inputs.kometRelease()
                .filter(release -> inputs.komets().isEmpty() || release.newerThan(inputs.komets().getFirst().version()))
                .ifPresent(release -> notices.add(new Notice("komet:" + release.version(), Notice.Urgency.ACTION,
                        inputs.komets().isEmpty() ? "No Komet is installed" : "Komet " + release.version() + " is available",
                        (inputs.komets().isEmpty() ? "" : "You have Komet " + inputs.komets().getFirst().version() + ". ")
                                + "It installs beside the Komets you have, from " + release.source().label() + ".",
                        Optional.empty(), Optional.of(new NoticeAction.InstallKomet(release)), inputs.nexusChecked())));
    }

    private static void newOnNexus(List<Notice> notices, Inputs inputs) {
        if (inputs.catalog().isEmpty()) {
            return;
        }
        NexusCatalog.Catalog catalog = inputs.catalog().get();
        Set<String> here = inputs.families().stream()
                .flatMap(family -> family.members().stream())
                .flatMap(member -> member.coordinate().stream())
                .map(coordinate -> coordinate.artifactId().toLowerCase(Locale.ROOT))
                .collect(Collectors.toCollection(HashSet::new));
        inputs.families().forEach(family -> here.add(family.name().toLowerCase(Locale.ROOT)));
        // The catalog lists each coordinate's offers in the order the dashboard prefers their classifiers.
        Map<Coordinate, NexusCatalog.Offer> preferred = new LinkedHashMap<>();
        catalog.offers().stream()
                .filter(offer -> !here.contains(offer.coordinate().artifactId().toLowerCase(Locale.ROOT)))
                .forEach(offer -> preferred.putIfAbsent(offer.coordinate(), offer));
        Instant since = inputs.now().minus(NEW_ON_NEXUS);
        int older = 0;
        // A tracked collection's knowledge bases are never folded into the count: they stay at hand.
        Map<String, List<String>> kept = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (NexusCatalog.Offer offer : preferred.values()) {
            Optional<KnowledgeBaseDescriptor> descriptor = Optional.ofNullable(catalog.described().get(offer.coordinate()))
                    .map(NexusCatalog.Described::descriptor);
            String name = descriptor.map(KnowledgeBaseDescriptor::displayName).orElse(offer.coordinate().artifactId());
            if (offer.updated().isBefore(since)) {
                Optional<String> collection = descriptor.filter(found -> found.inCollection(inputs.collections()))
                        .flatMap(KnowledgeBaseDescriptor::collection);
                if (collection.isPresent()) {
                    kept.computeIfAbsent(collection.get(), key -> new ArrayList<>()).add(name);
                } else {
                    older++;
                }
                continue;
            }
            List<String> copies = unmanagedCopies(inputs.unmanaged(), offer.coordinate());
            String deployed = "Deployed " + WHEN.format(offer.updated()) + ", " + DashboardCommand.size(offer.sizeBytes()) + ". ";
            notices.add(new Notice("nexus-new:" + offer.coordinate() + ":" + offer.updated(), Notice.Urgency.ACTION,
                    copies.isEmpty() ? "New on Nexus: " + name : "A managed build of " + name + " is on Nexus",
                    deployed + (copies.isEmpty() ? "Not in your Solor folder."
                            : "You have " + String.join(", ", copies) + ", which the dashboard does not manage;"
                                    + " this build carries its descriptor."),
                    Optional.empty(), Optional.of(new NoticeAction.Download(offer.coordinate(), offer.classifier())),
                    inputs.nexusChecked()));
        }
        kept.forEach((collection, names) -> {
            List<String> sorted = names.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
            notices.add(new Notice("collection:" + collection + ":" + String.join(",", sorted), Notice.Urgency.NEWS,
                    plural(sorted.size(), collection + " knowledge base", collection + " knowledge bases") + " not downloaded",
                    "The dashboard keeps the " + collection + " collection at hand: " + String.join(", ", sorted)
                            + ". The Nexus window lists them first.",
                    Optional.empty(), Optional.of(new NoticeAction.Browse()), inputs.nexusChecked()));
        });
        if (older > 0) {
            notices.add(new Notice("nexus-more:" + older, Notice.Urgency.NEWS,
                    plural(older, "more knowledge base", "more knowledge bases") + " on Nexus",
                    "Knowledge bases you have not downloaded, deployed more than " + NEW_ON_NEXUS.toDays() + " days ago.",
                    Optional.empty(), Optional.of(new NoticeAction.Browse()), inputs.nexusChecked()));
        }
    }

    /** The unmanaged entries that are copies of an artifact: by the coordinate they name, else by their folder name. */
    private static List<String> unmanagedCopies(List<KnowledgeBase> unmanaged, Coordinate coordinate) {
        String artifact = coordinate.artifactId().toLowerCase(Locale.ROOT);
        return unmanaged.stream()
                .filter(entry -> entry.kind().opensInKomet())
                .filter(entry -> entry.coordinate().map(named -> named.artifactId().toLowerCase(Locale.ROOT).equals(artifact))
                        .orElse(KnowledgeBaseFamily.familyName(entry).toLowerCase(Locale.ROOT).equals(artifact)))
                .map(KnowledgeBase::name)
                .toList();
    }

    private static String plural(int count, String one, String many) {
        return count + " " + (count == 1 ? one : many);
    }
}
