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

import network.ike.dashboard.download.DownloadListener;
import network.ike.dashboard.download.RepositoryHttpClients;

import java.io.PrintStream;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Locale;

/**
 * The dashboard without its window, for scripts, machines without a display, and checking what the
 * window shows:
 *
 * <pre>
 * list                                  the knowledge bases in ~/Solor
 * check                                 the same, with the newer-build check against Nexus
 * notices                               the landing page's notices, with every check the window makes
 * komets                                the Komet versions installed
 * heap                                  what each knowledge base opens with, beside the Komets running
 * nexus                                 the knowledge bases Nexus offers
 * komet-latest [repository-url]         the newest Komet build: Nexus's komet-app, else GitHub's
 * komet-install [folder] [repository-url] install it beside the others (default: the install location)
 * download &lt;g:a:v&gt; &lt;classifier&gt;          the newest deploy of a store, into ~/Solor/&lt;artifactId&gt;
 * dashboard-latest [repository-url]     the newest IKE Dashboard on Nexus
 * self-update [application]             replace a dashboard with the newest, once this command has
 *                                       ended, and open it (default: the application running this)
 * </pre>
 */
public final class DashboardCommand {

    private DashboardCommand() {
    }

    /**
     * Runs one command.
     *
     * @param args the command and its arguments
     * @throws Exception if the command fails
     */
    public static void main(String[] args) throws Exception {
        PrintStream out = System.out;
        DashboardSettings settings = DashboardSettings.current();
        String command = args.length == 0 ? "list" : args[0];
        switch (command) {
            case "list" -> list(out, settings, false);
            case "check" -> list(out, settings, true);
            case "notices" -> notices(out, settings);
            case "komets" -> komets(out, settings);
            case "heap" -> heap(out, settings);
            case "nexus" -> nexus(out, settings);
            case "komet-latest" -> kometLatest(out, args.length > 1 ? args[1] : null).ifPresentOrElse(
                    release -> out.printf("Komet %s from %s: %s (%s)%n", release.version(), release.source().label(),
                            release.uri(), release.sizeBytes() > 0 ? size(release.sizeBytes()) : "size unknown"),
                    () -> out.println("No Komet build found"));
            case "komet-install" -> {
                KometRelease release = kometLatest(out, args.length > 2 ? args[2] : null)
                        .orElseThrow(() -> new IllegalStateException("No Komet build found"));
                Path folder = args.length > 1 ? Path.of(args[1]) : settings.installLocation();
                KometInstallation installed = KometInstaller.install(RepositoryHttpClients.newClient(), release, folder,
                        settings.localRepository(), out::println);
                out.printf("Installed Komet %s: %s%n", installed.version(), installed.executable());
            }
            case "download" -> {
                if (args.length != 3) {
                    throw new IllegalArgumentException("download <groupId:artifactId:version> <classifier>");
                }
                download(out, settings, Coordinate.parse(args[1]), args[2]);
            }
            case "dashboard-latest" -> SelfUpdate.newest(RepositoryHttpClients.newClient(),
                            args.length > 1 ? args[1] : KometReleases.PUBLIC_URL, KometReleases.platformClassifier())
                    .ifPresentOrElse(release -> out.printf("IKE Dashboard %s: %s (%s)%n", release.version(), release.uri(),
                                    release.sizeBytes() > 0 ? size(release.sizeBytes()) : "size unknown"),
                            () -> out.println("No IKE Dashboard build found"));
            case "self-update" -> selfUpdate(out, settings, args.length > 1 ? Optional.of(Path.of(args[1])) : Optional.empty());
            default -> throw new IllegalArgumentException("Unknown command " + command
                    + "; use list, check, notices, komets, heap, download, dashboard-latest or self-update");
        }
    }

    /**
     * Stages the newest dashboard beside an application and starts the helper that swaps it in once this
     * command has ended, as the window's update does once the window has quit.
     */
    private static void selfUpdate(PrintStream out, DashboardSettings settings, Optional<Path> application) throws Exception {
        SelfUpdate.Running running = (application.isPresent() ? SelfUpdate.read(application.get()) : SelfUpdate.running())
                .orElseThrow(() -> new IllegalArgumentException(application.map(path -> path + " is not the IKE Dashboard")
                        .orElse("Not running as the IKE Dashboard application; name one: self-update <application>")));
        SelfUpdate.Placement placement = SelfUpdate.place(running, Path.of(System.getProperty("user.home")));
        if (placement.location() == SelfUpdate.Location.TRANSLOCATED) {
            throw new IllegalStateException(running.bundle() + " is translocated; move it into Applications first");
        }
        HttpClient http = RepositoryHttpClients.newClient();
        SelfUpdate.State state = new SelfUpdate.State(placement, SelfUpdate.newest(http));
        Optional<KometRelease> offered = state.offered();
        if (offered.isEmpty()) {
            out.printf("The IKE Dashboard %s is the newest%s%n", running.version(),
                    state.newest().map(newest -> " (Nexus has " + newest.version() + ")").orElse(""));
            return;
        }
        Path staged = SelfUpdate.stage(http, offered.get(), placement, settings.localRepository(), out::println);
        Path log = settings.logFolder().resolve("self-update.log");
        SelfUpdate.swap(placement, staged, ProcessHandle.current().pid(), SelfUpdate.relaunch(placement.target()), log);
        out.printf("The IKE Dashboard %s replaces %s in %s once this command ends, and opens; see %s%n",
                offered.get().version(), running.version(), placement.target(), log);
    }

    private static void list(PrintStream out, DashboardSettings settings, boolean check) throws Exception {
        List<KnowledgeBase> knowledgeBases = KnowledgeBaseScanner.scan(settings.solor());
        List<KnowledgeBaseFamily> families = KnowledgeBaseFamily.managed(knowledgeBases);
        List<KnowledgeBase> unmanaged = KnowledgeBaseFamily.unmanaged(knowledgeBases);
        List<KometInstallation> komets = KometInstallations.find(KometInstallations.defaultRoots(
                Path.of(System.getProperty("user.home")), settings.installLocation()));
        NewerBuildCheck newer = check ? new NewerBuildCheck(RepositoryHttpClients.newClient(), access(settings)) : null;
        out.printf("%d knowledge bases (%d entries) in %s%n", families.size(), knowledgeBases.size(), settings.solor());
        for (KnowledgeBaseFamily family : families) {
            KnowledgeBase shown = family.latest().orElse(family.members().getFirst());
            out.printf("%-34s %-20s %9s  %-24s %s%n", family.name() + (family.latest().isPresent() ? " *" : ""),
                    shown.kind().label(), size(shown.sizeBytes()), opensWith(komets, shown),
                    shown.coordinate().map(Coordinate::toString).orElse("unidentified"));
            if (check) {
                BuildStatus status = family.latestIdentified().map(newer::check)
                        .orElse(BuildStatus.of(BuildStatus.State.UNIDENTIFIED, "no copy says which build it is"));
                out.printf("    nexus: %s, %s%n", status.state().label(), status.detail());
            }
            for (KnowledgeBase member : family.members()) {
                if (member != shown) {
                    out.printf("    %-30s %-20s %9s%n", member.name(), member.kind().label(), size(member.sizeBytes()));
                }
            }
        }
        if (!unmanaged.isEmpty()) {
            out.printf("Not managed (%d): no knowledge-base descriptor that meets level 1%n", unmanaged.size());
            for (KnowledgeBase entry : unmanaged) {
                out.printf("    %-30s %-20s %9s  %s%n", entry.name(), entry.kind().label(), size(entry.sizeBytes()),
                        String.join("; ", entry.descriptor().problems()));
            }
        }
        out.println("* the bold row in the window: the newest copy Komet can open");
    }

    private static void heap(PrintStream out, DashboardSettings settings) throws Exception {
        List<KnowledgeBaseFamily> families = KnowledgeBaseFamily.managed(KnowledgeBaseScanner.scan(settings.solor()));
        List<KometInstallation> komets = KometInstallations.find(KometInstallations.defaultRoots(
                Path.of(System.getProperty("user.home")), settings.installLocation()));
        java.util.prefs.Preferences heap = java.util.prefs.Preferences.userRoot().node(DashboardSettings.NODE)
                .node(HeapSizing.NODE);
        HeapSizing.Rule rule = HeapSizing.Rule.read(heap);
        HeapSizing.Machine machine = HeapSizing.Machine.current(komets);
        out.printf("%s of memory, %s kept for everything else, %s claimed by %d Komet%s running%n",
                HeapSizing.gb(machine.physical()), HeapSizing.gb(rule.reserve(machine.physical())),
                HeapSizing.gb(machine.claimed()), machine.running().size(), machine.running().size() == 1 ? "" : "s");
        machine.running().forEach(running -> out.printf("    %s%n", running.text()));
        out.println(rule.equals(HeapSizing.Rule.DEFAULTS) ? "The rule: the defaults" : "The rule: " + rule);
        for (KnowledgeBaseFamily family : families) {
            Optional<KnowledgeBase> latest = family.latest();
            Optional<KometInstallation> komet = latest.flatMap(copy -> LauncherChoice.choose(komets, copy));
            if (latest.isEmpty() || komet.isEmpty()) {
                continue;
            }
            KnowledgeBase copy = latest.get();
            HeapSizing.Plan plan = HeapSizing.plan(rule, copy, HeapSizing.dataBytes(copy),
                    HeapSizing.override(heap.node(HeapSizing.STORES_NODE), copy), komet.get(), machine);
            out.printf("%-34s %-20s %9s data  %-26s %-18s %s%n", family.name(), copy.kind().label(),
                    size(plan.data()), plan.need().text(), plan.fit().name().toLowerCase(Locale.ROOT),
                    plan.environment().isEmpty() ? "Komet " + komet.get().version() + " fixes its heap"
                            : plan.environment().entrySet().stream().map(entry -> entry.getKey() + "=" + entry.getValue())
                            .collect(java.util.stream.Collectors.joining(" ")));
        }
    }

    private static void notices(PrintStream out, DashboardSettings settings) throws Exception {
        List<KnowledgeBase> knowledgeBases = KnowledgeBaseScanner.scan(settings.solor());
        List<KnowledgeBaseFamily> families = KnowledgeBaseFamily.managed(knowledgeBases);
        List<KometInstallation> komets = KometInstallations.find(KometInstallations.defaultRoots(
                Path.of(System.getProperty("user.home")), settings.installLocation()));
        HttpClient http = RepositoryHttpClients.newClient();
        RepositoryAccess access = access(settings);
        NewerBuildCheck newer = new NewerBuildCheck(http, access);
        Map<String, BuildStatus> statuses = new HashMap<>();
        Map<Path, ChangeSetCheck.Finding> changeSets = new HashMap<>();
        Map<Path, List<RepositorySync.Status>> sync = new HashMap<>();
        Optional<RepositorySync.Git> git = RepositorySync.Git.find();
        Instant now = Instant.now();
        for (KnowledgeBaseFamily family : families) {
            family.latestIdentified().map(newer::check).ifPresent(status -> statuses.put(family.name(), status));
            Optional<KnowledgeBase> latest = family.latest().filter(copy -> copy.kind().opensInKomet());
            if (latest.isPresent()) {
                changeSets.put(latest.get().path(), ChangeSetCheck.check(latest.get().path()));
                List<RepositorySync.Status> states = new ArrayList<>();
                if (git.isPresent()) {
                    for (RepositorySync.Repository repository : RepositorySync.find(latest.get().path())) {
                        states.add(RepositorySync.remote(git.get(), RepositorySync.local(git.get(), repository),
                                Optional.empty(), now));
                    }
                }
                sync.put(latest.get().path(), states);
            }
        }
        Optional<NexusCatalog.Catalog> catalog;
        try {
            catalog = Optional.of(NexusCatalog.catalog(http, access));
        } catch (Exception e) {
            out.println("Could not ask Nexus what it offers: " + e.getMessage());
            catalog = Optional.empty();
        }
        Optional<KometRelease> release;
        try {
            release = KometReleases.newest(http);
        } catch (Exception e) {
            out.println("Could not look for a newer Komet: " + e.getMessage());
            release = Optional.empty();
        }
        Optional<SelfUpdate.State> dashboard = Optional.empty();
        Optional<SelfUpdate.Running> running = SelfUpdate.running();
        if (running.isPresent()) {
            try {
                dashboard = Optional.of(new SelfUpdate.State(SelfUpdate.place(running.get(),
                        Path.of(System.getProperty("user.home"))), SelfUpdate.newest(http)));
            } catch (Exception e) {
                out.println("Could not look for a newer dashboard: " + e.getMessage());
            }
        }
        List<Notice> notices = Notices.compute(new Notices.Inputs(families, KnowledgeBaseFamily.unmanaged(knowledgeBases), statuses,
                Optional.of(new Notice.Freshness(now, true)), changeSets, komets, release, catalog, sync,
                settings.collections(), dashboard, now),
                NoticeDismissals.current()::dismissed);
        out.printf("%d notices%s%n", notices.size(), git.isPresent() ? "" : " (no git found: repository sync not checked)");
        for (Notice notice : notices) {
            out.printf("%-8s %s%n", notice.urgency(), notice.title());
            if (!notice.detail().isBlank()) {
                out.printf("         %s%n", notice.detail());
            }
            notice.action().ifPresent(action -> out.printf("         [%s]%n", action.label()));
        }
    }

    private static String opensWith(List<KometInstallation> komets, KnowledgeBase knowledgeBase) {
        if (!knowledgeBase.kind().opensInKomet()) {
            return "";
        }
        return LauncherChoice.choose(komets, knowledgeBase).map(komet -> "Komet " + komet.version())
                .orElseGet(() -> LauncherChoice.requirement(knowledgeBase).map(need -> "needs " + need)
                        .orElse("no Komet installed"));
    }

    private static void komets(PrintStream out, DashboardSettings settings) throws Exception {
        List<KometInstallation> komets = KometInstallations.find(KometInstallations.defaultRoots(
                Path.of(System.getProperty("user.home")), settings.installLocation()));
        out.printf("%d Komet versions%n", komets.size());
        for (KometInstallation komet : komets) {
            out.printf("%-14s %s%n", komet.version(), komet.bundle());
        }
    }

    private static Optional<KometRelease> kometLatest(PrintStream out, String repositoryUrl) throws Exception {
        HttpClient http = RepositoryHttpClients.newClient();
        if (repositoryUrl == null) {
            return KometReleases.newest(http);
        }
        Optional<KometRelease> nexus = KometReleases.newestOnNexus(http, repositoryUrl, KometReleases.platformClassifier());
        return nexus.isPresent() ? nexus
                : KometReleases.newestOnGitHub(http, KometReleases.GITHUB_REPOSITORY, KometReleases.platformClassifier());
    }

    private static void nexus(PrintStream out, DashboardSettings settings) throws Exception {
        List<NexusCatalog.Offer> offers = NexusCatalog.list(RepositoryHttpClients.newClient(), access(settings));
        out.printf("%d stores on Nexus%n", offers.size());
        for (NexusCatalog.Offer offer : offers) {
            out.printf("%-28s %-12s %9s  %s  %s%n", offer.coordinate().artifactId(), offer.label(),
                    size(offer.sizeBytes()), offer.updated(), offer.repository());
        }
    }

    private static void download(PrintStream out, DashboardSettings settings, Coordinate coordinate, String classifier)
            throws Exception {
        HttpClient http = RepositoryHttpClients.newClient();
        Path store = StoreDownload.download(http, access(settings), coordinate, classifier, settings.solor(),
                settings.localRepository(), new DownloadListener<>() {
                    @Override
                    public void updateValue(Path result) {
                    }

                    @Override
                    public void updateMessage(String message) {
                        if (message != null) {
                            out.println(message);
                        }
                    }

                    @Override
                    public void updateTitle(String title) {
                        if (title != null) {
                            out.println(title);
                        }
                    }

                    @Override
                    public void updateProgress(double workDone, double max) {
                    }
                });
        KnowledgeBase knowledgeBase = KnowledgeBaseScanner.inspect(store);
        out.printf("%s: %s, %s, %s%n", store, knowledgeBase.kind().label(), size(knowledgeBase.sizeBytes()),
                knowledgeBase.coordinate().map(Coordinate::toString).orElse("unidentified"));
        knowledgeBase.attributes().forEach((name, value) -> out.printf("  %s: %s%n", name, value));
    }

    static RepositoryAccess access(DashboardSettings settings) throws Exception {
        return RepositoryAccess.fromSettings(settings.repositoryUrl(), settings.serverId(),
                Path.of(System.getProperty("user.home"), ".m2", "settings.xml"), System.getenv());
    }

    /**
     * A size for people: bytes, KB, MB or GB, decimal.
     *
     * @param bytes the size
     * @return the size, such as {@code 1.97 MB}
     */
    static String size(long bytes) {
        if (bytes < 1_000) {
            return bytes + " B";
        }
        String[] units = {"KB", "MB", "GB", "TB"};
        double value = bytes;
        int unit = -1;
        while (value >= 1_000 && unit < units.length - 1) {
            value /= 1_000;
            unit++;
        }
        return String.format(Locale.ROOT, value >= 100 ? "%.0f %s" : value >= 10 ? "%.1f %s" : "%.2f %s",
                value, units[unit]);
    }
}
