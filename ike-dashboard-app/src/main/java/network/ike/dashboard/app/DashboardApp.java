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

import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.stage.Stage;
import network.ike.dashboard.download.RepositoryHttpClients;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.prefs.Preferences;

/**
 * The IKE Dashboard: one window, in its own process, that lists the knowledge bases in
 * {@code ~/Solor} and those Nexus has, asks Nexus whether newer builds exist, and opens any of them
 * in an installed Komet as an independent process. Its design is the {@code dev-ike-dashboard} topic
 * in {@code ike-lab-documents}.
 *
 * <p>It uses the network whenever it can and fails gracefully without it (decision 14): what needs the
 * network, Nexus and the remotes of the repositories inside stores, is checked on start and every
 * {@link #NETWORK_CHECK_INTERVAL} after; a check that fails keeps what the last one found, shown as of
 * then.
 */
public final class DashboardApp extends Application {

    private static final String SORT_RECENT = "sortRecent";

    /** How often what needs the network is checked again while the dashboard runs. */
    static final Duration NETWORK_CHECK_INTERVAL = Duration.ofHours(1);

    private final ExecutorService work = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ike-dashboard-work");
        thread.setDaemon(true);
        return thread;
    });
    private final ScheduledExecutorService clock = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ike-dashboard-clock");
        thread.setDaemon(true);
        return thread;
    });
    private final Optional<RepositorySync.Git> git = RepositorySync.Git.find();
    private final Map<Path, Notice.Freshness> remoteChecked = new ConcurrentHashMap<>();
    private volatile List<KnowledgeBaseFamily> current = List.of();
    private volatile Instant nexusChecked;
    private final Map<String, BuildStatus> lastStatuses = new ConcurrentHashMap<>();
    private DashboardSettings settings;
    private OpenHistory history;
    private DashboardView view;
    private volatile String selectAfterRefresh;

    /** Creates the application; JavaFX instantiates it. */
    public DashboardApp() {
    }

    /**
     * Starts the dashboard.
     *
     * @param args the command line, passed to JavaFX
     */
    public static void main(String[] args) {
        launch(DashboardApp.class, args);
    }

    @Override
    public void start(Stage stage) {
        settings = DashboardSettings.current();
        history = OpenHistory.current();
        Preferences preferences = Preferences.userRoot().node(DashboardSettings.NODE);
        view = new DashboardView();
        view.setLastOpened(history::lastOpened);
        view.setSortRecent(preferences.getBoolean(SORT_RECENT, false));
        view.setOnSortChange(recent -> preferences.putBoolean(SORT_RECENT, recent));
        view.setOnRefresh(this::refresh);
        view.setOnOpen(this::open);
        view.setOnDownload(this::download);
        view.setOnTrash(this::trash);
        view.setOnBrowse(this::loadCatalog);
        view.setOnInstallKomet(this::installKomet);
        view.setOnUpgradeChangeSets(this::upgradeChangeSets);
        view.setOnReveal(path -> run(() -> Finder.reveal(path), "Could not show " + path));
        view.setOnOpenFile(path -> run(() -> Finder.open(path), "Could not open " + path));
        NoticeDismissals dismissals = NoticeDismissals.current();
        view.setDismissed(dismissals::dismissed);
        view.setOnDismiss(dismissals::dismiss);
        stage.setTitle("IKE Dashboard");
        stage.setScene(new Scene(view, 1080, 640));
        stage.show();
        refresh();
        long interval = NETWORK_CHECK_INTERVAL.toMinutes();
        clock.scheduleAtFixedRate(() -> work.submit(() -> checkNetwork(current)), interval, interval, TimeUnit.MINUTES);
    }

    @Override
    public void stop() {
        clock.shutdownNow();
        work.shutdownNow();
    }

    /** Reads {@code ~/Solor} and the installed Komets, then asks Nexus, in the background. */
    private void refresh() {
        view.setMessage("Reading " + settings.solor() + " …");
        work.submit(() -> {
            List<KnowledgeBaseFamily> families;
            try {
                List<KnowledgeBase> knowledgeBases = KnowledgeBaseScanner.scan(settings.solor());
                List<KometInstallation> komets = KometInstallations.find(KometInstallations.defaultRoots(
                        Path.of(System.getProperty("user.home")), settings.installLocation()));
                families = KnowledgeBaseFamily.managed(knowledgeBases);
                List<KnowledgeBaseFamily> found = families;
                List<KnowledgeBase> unmanaged = KnowledgeBaseFamily.unmanaged(knowledgeBases);
                Platform.runLater(() -> {
                    view.setKomets(komets);
                    view.setFamilies(found);
                    view.setUnmanaged(unmanaged);
                    String select = selectAfterRefresh;
                    if (select != null) {
                        selectAfterRefresh = null;
                        view.select(select);
                    }
                    view.setMessage(found.size() + " knowledge bases · checking Nexus …");
                });
            } catch (Exception e) {
                Platform.runLater(() -> view.setMessage("Could not read " + settings.solor() + ": " + e.getMessage()));
                return;
            }
            current = families;
            checkChangeSets(families);
            checkNetwork(families);
        });
    }

    /**
     * Runs on the work thread: everything that needs the network. Nexus first, for newer builds, a newer
     * Komet and what is not yet downloaded; then each store's repositories against their remotes.
     */
    private void checkNetwork(List<KnowledgeBaseFamily> families) {
        checkNexus(families);
        checkCatalog();
        checkSync(families);
    }

    /** Runs on the work thread: what Nexus offers, for the notices of knowledge bases not yet downloaded. */
    private void checkCatalog() {
        try {
            NexusCatalog.Catalog catalog = NexusCatalog.catalog(RepositoryHttpClients.newClient(),
                    DashboardCommand.access(settings));
            Platform.runLater(() -> view.setCatalog(catalog));
        } catch (Exception e) {
            // The notices keep what the last catalog showed; checkNexus has already said Nexus failed.
        }
    }

    /**
     * Runs on the work thread: the sync state of every git repository inside each store, first from its
     * files, then against its remote.
     */
    private void checkSync(List<KnowledgeBaseFamily> families) {
        if (git.isEmpty()) {
            return;
        }
        for (KnowledgeBaseFamily family : families) {
            Optional<KnowledgeBase> latest = family.latest().filter(copy -> copy.kind().opensInKomet());
            if (latest.isEmpty()) {
                continue;
            }
            Path store = latest.get().path();
            try {
                List<RepositorySync.Status> local = new ArrayList<>();
                for (RepositorySync.Repository repository : RepositorySync.find(store)) {
                    local.add(RepositorySync.local(git.get(), repository));
                }
                List<RepositorySync.Status> shown = List.copyOf(local);
                Platform.runLater(() -> view.setSync(store, shown));
                List<RepositorySync.Status> checked = new ArrayList<>();
                Instant now = Instant.now();
                for (RepositorySync.Status status : local) {
                    Path folder = status.repository().folder();
                    RepositorySync.Status remote = RepositorySync.remote(git.get(), status,
                            Optional.ofNullable(remoteChecked.get(folder)), now);
                    remote.freshness().filter(Notice.Freshness::current).ifPresent(fresh -> remoteChecked.put(folder, fresh));
                    checked.add(remote);
                }
                List<RepositorySync.Status> answered = List.copyOf(checked);
                Platform.runLater(() -> view.setSync(store, answered));
            } catch (Exception e) {
                Platform.runLater(() -> view.setMessage("Could not read the repositories in " + family.name() + ": "
                        + e.getMessage()));
            }
        }
    }

    /**
     * Runs on the work thread: the newer-build check for each knowledge base, and the newest Komet. A
     * knowledge base whose check fails keeps the status the last check found, and Nexus counts as
     * reached only when some check was answered, so offline the notices show the last findings as of
     * then rather than errors.
     */
    private void checkNexus(List<KnowledgeBaseFamily> families) {
        int newer = 0;
        int answered = 0;
        int failed = 0;
        String problem = null;
        try {
            HttpClient http = RepositoryHttpClients.newClient();
            RepositoryAccess access = DashboardCommand.access(settings);
            NewerBuildCheck check = new NewerBuildCheck(http, access);
            for (KnowledgeBaseFamily family : families) {
                BuildStatus status = family.latestIdentified().map(check::check)
                        .orElse(BuildStatus.of(BuildStatus.State.UNIDENTIFIED, "no copy says which build it is"));
                if (status.state() == BuildStatus.State.ERROR) {
                    failed++;
                    problem = "could not check Nexus: " + status.detail();
                    BuildStatus last = lastStatuses.get(family.name());
                    if (last != null) {
                        status = last;
                    }
                } else {
                    answered++;
                    lastStatuses.put(family.name(), status);
                }
                if (status.state() == BuildStatus.State.NEWER) {
                    newer++;
                }
                BuildStatus shown = status;
                Platform.runLater(() -> view.setStatus(family.name(), shown));
            }
        } catch (Exception e) {
            failed++;
            problem = "could not check Nexus: " + e.getMessage();
        }
        if (answered > 0 || failed == 0) {
            nexusChecked = Instant.now();
            Notice.Freshness fresh = new Notice.Freshness(nexusChecked, true);
            Platform.runLater(() -> view.setNexusChecked(fresh));
        } else if (nexusChecked != null) {
            Notice.Freshness stale = new Notice.Freshness(nexusChecked, false);
            Platform.runLater(() -> view.setNexusChecked(stale));
        }
        try {
            Optional<KometRelease> release = KometReleases.newest(RepositoryHttpClients.newClient());
            Platform.runLater(() -> view.setKometRelease(release));
        } catch (Exception e) {
            problem = (problem == null ? "" : problem + "; ") + "could not look for a newer Komet: " + e.getMessage();
        }
        String summary = families.size() + " knowledge bases"
                + (newer == 0 ? " · all current with Nexus" : " · " + newer + " with a newer build")
                + (problem == null ? "" : " · " + problem);
        Platform.runLater(() -> view.setMessage(summary));
    }

    /** Runs on the work thread: which change sets of each store need an upgrade (IKE-Network/ike-issues#1313). */
    private void checkChangeSets(List<KnowledgeBaseFamily> families) {
        for (KnowledgeBaseFamily family : families) {
            family.latest().filter(copy -> copy.kind().opensInKomet()).ifPresent(copy -> {
                try {
                    ChangeSetCheck.Finding finding = ChangeSetCheck.check(copy.path());
                    Platform.runLater(() -> view.setChangeSets(copy.path(), finding));
                } catch (Exception e) {
                    Platform.runLater(() -> view.setMessage("Could not read the change sets of " + copy.name() + ": "
                            + e.getMessage()));
                }
            });
        }
    }

    /** Upgrades a store's change sets in a Komet with no window, on a thread of its own, and shows the report. */
    private void upgradeChangeSets(KometInstallation komet, KnowledgeBase knowledgeBase) {
        view.setUpgrading(knowledgeBase.path(), true);
        view.setMessage("Upgrading the change sets of " + knowledgeBase.name() + " in Komet " + komet.version() + " …");
        Thread.ofVirtual().name("ike-dashboard-upgrade").start(() -> {
            try {
                KometLauncher.UpgradeRun run = KometLauncher.upgradeChangeSets(komet, knowledgeBase, settings.logFolder());
                ChangeSetCheck.Finding finding = ChangeSetCheck.check(knowledgeBase.path());
                Platform.runLater(() -> {
                    view.setUpgrading(knowledgeBase.path(), false);
                    view.setChangeSets(knowledgeBase.path(), finding);
                    view.setLastLog(run.log());
                    view.setMessage(run.clean() ? "Upgraded the change sets of " + knowledgeBase.name()
                            : "The change-set upgrade of " + knowledgeBase.name() + " ended with status " + run.exitStatus());
                    view.showUpgradeReport(knowledgeBase, run);
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    view.setUpgrading(knowledgeBase.path(), false);
                    view.setMessage("Could not upgrade the change sets of " + knowledgeBase.name() + ": " + e.getMessage());
                });
            }
        });
    }

    /** Asks Nexus which knowledge bases it offers, for the Nexus window. */
    private void loadCatalog() {
        work.submit(() -> {
            try {
                NexusCatalog.Catalog catalog = NexusCatalog.catalog(RepositoryHttpClients.newClient(),
                        DashboardCommand.access(settings));
                Platform.runLater(() -> view.setCatalog(catalog));
            } catch (Exception e) {
                Platform.runLater(() -> view.setOffersProblem("Could not ask Nexus: " + e.getMessage()));
            }
        });
    }

    private void download(Coordinate coordinate, String classifier) {
        view.setMessage("Downloading " + coordinate + " " + classifier + " …");
        work.submit(() -> {
            try {
                Path store = StoreDownload.download(RepositoryHttpClients.newClient(), DashboardCommand.access(settings),
                        coordinate, classifier, settings.solor(), settings.localRepository(), null);
                Platform.runLater(() -> {
                    selectAfterRefresh = coordinate.artifactId();
                    refresh();
                    view.setMessage("Downloaded " + store.getFileName());
                });
            } catch (Exception e) {
                Platform.runLater(() -> view.setMessage("Could not download " + coordinate + ": " + e.getMessage()));
            }
        });
    }

    private void installKomet(KometRelease release) {
        view.setInstalling(true);
        view.setMessage("Installing Komet " + release.version() + " from " + release.source().label() + " …");
        work.submit(() -> {
            try {
                KometInstallation installed = KometInstaller.install(RepositoryHttpClients.newClient(), release,
                        settings.installLocation(), settings.localRepository(),
                        text -> Platform.runLater(() -> view.setMessage(text)));
                Platform.runLater(() -> {
                    view.setInstalling(false);
                    refresh();
                    view.setMessage("Installed Komet " + installed.version() + " in " + installed.bundle().getParent());
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    view.setInstalling(false);
                    view.setMessage("Could not install Komet " + release.version() + ": " + e.getMessage());
                });
            }
        });
    }

    private void trash(List<KnowledgeBase> copies) {
        view.setMessage("Moving " + copies.size() + (copies.size() == 1 ? " copy" : " copies") + " to the Trash …");
        work.submit(() -> {
            try {
                Finder.trash(copies.stream().map(KnowledgeBase::path).toList());
                Platform.runLater(this::refresh);
            } catch (Exception e) {
                Platform.runLater(() -> view.setMessage(e.getMessage()));
            }
        });
    }

    private void open(KometInstallation komet, KnowledgeBase knowledgeBase) {
        try {
            Path log = KometLauncher.launch(komet, knowledgeBase, settings.logFolder());
            history.opened(KnowledgeBaseFamily.familyName(knowledgeBase));
            view.setLastOpened(history::lastOpened);
            view.setLastLog(log);
            view.setMessage("Opening " + knowledgeBase.name() + " in Komet " + komet.version());
        } catch (Exception e) {
            view.setMessage("Could not start Komet " + komet.version() + ": " + e.getMessage());
        }
    }

    private void run(Action action, String failure) {
        try {
            action.run();
        } catch (Exception e) {
            view.setMessage(failure + ": " + e.getMessage());
        }
    }

    /** Something that may fail. */
    @FunctionalInterface
    private interface Action {
        void run() throws Exception;
    }
}
