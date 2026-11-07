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
import javafx.scene.image.Image;
import javafx.stage.Stage;
import network.ike.dashboard.download.RepositoryHttpClients;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
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
    private Preferences heapPreferences;
    private volatile List<KometInstallation> komets = List.of();
    private final Map<Path, Long> dataBytes = new ConcurrentHashMap<>();
    private DashboardView view;
    private volatile String selectAfterRefresh;
    private Optional<SelfUpdate.Placement> placement = Optional.empty();

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

    /**
     * The window's icon, the IKE pill of the brand guide, at the sizes Windows and Linux choose from;
     * macOS shows the application bundle's icon instead. Rendered by {@code src/main/icons/render-icons.sh}.
     *
     * @return the icon, smallest first
     */
    static List<Image> icons() {
        List<Image> icons = new ArrayList<>();
        for (int pixels : new int[] {16, 32, 48, 64, 128, 256}) {
            try (InputStream png = DashboardApp.class.getResourceAsStream("icon-" + pixels + ".png")) {
                icons.add(new Image(png));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return icons;
    }

    @Override
    public void start(Stage stage) {
        settings = DashboardSettings.current();
        history = OpenHistory.current();
        Preferences preferences = Preferences.userRoot().node(DashboardSettings.NODE);
        heapPreferences = preferences.node(HeapSizing.NODE);
        view = new DashboardView();
        view.setCollections(settings.collections());
        view.setLastOpened(history::lastOpened);
        view.setSortRecent(preferences.getBoolean(SORT_RECENT, false));
        view.setOnSortChange(recent -> preferences.putBoolean(SORT_RECENT, recent));
        view.setOnRefresh(this::refresh);
        view.setOnOpen(this::open);
        view.setHeapPlanner((komet, knowledgeBase) -> Optional.of(heapPlan(komet, knowledgeBase)));
        view.setOnEditHeap(this::editHeap);
        view.browser().setHeapRule(HeapSizing.Rule.read(heapPreferences));
        view.setOnDownload(this::download);
        view.setOnTrash(this::trash);
        view.setOnBrowse(this::loadCatalog);
        view.setOnInstallKomet(this::installKomet);
        view.setOnUpgrade(this::upgrade);
        view.setOnReinstall(this::reinstall);
        view.setOnRestore(this::restore);
        view.setOnUpdateDashboard(this::updateDashboard);
        view.setOnMoveDashboard(this::moveDashboard);
        view.setOnReveal(path -> run(() -> Finder.reveal(path), "Could not show " + path));
        view.setOnOpenFile(path -> run(() -> Finder.open(path), "Could not open " + path));
        NoticeDismissals dismissals = NoticeDismissals.current();
        view.setDismissed(dismissals::dismissed);
        view.setOnDismiss(dismissals::dismiss);
        stage.setTitle("IKE Dashboard");
        stage.getIcons().setAll(icons());
        stage.setScene(new Scene(view, 1080, 640));
        stage.show();
        placement = SelfUpdate.running().map(running -> SelfUpdate.place(running, Path.of(System.getProperty("user.home"))));
        view.setDashboard(placement.map(where -> new SelfUpdate.State(where, Optional.empty())));
        placement.ifPresent(where -> work.submit(() -> {
            try {
                SelfUpdate.cleanUp(where);
            } catch (IOException e) {
                Platform.runLater(() -> view.setMessage("Could not remove what the last update left in " + where.work()
                        + ": " + e.getMessage()));
            }
        }));
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
                dataBytes.clear();
                this.komets = komets;
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
        if (placement.isPresent()) {
            try {
                Optional<SelfUpdate.State> state = Optional.of(new SelfUpdate.State(placement.get(),
                        SelfUpdate.newest(RepositoryHttpClients.newClient())));
                Platform.runLater(() -> view.setDashboard(state));
            } catch (Exception e) {
                problem = (problem == null ? "" : problem + "; ") + "could not look for a newer dashboard: " + e.getMessage();
            }
        }
        String summary = families.size() + " knowledge bases"
                + (newer == 0 ? " · all current with Nexus" : " · " + newer + " with a newer build")
                + (problem == null ? "" : " · " + problem);
        Platform.runLater(() -> view.setMessage(summary));
    }


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

    /**
     * Downloads a store or a protobuf on the work thread, one at a time, each shown as a transfer the person can watch
     * and cancel: a download waiting its turn says so, and one cancelled before its turn never starts.
     */
    private void download(Coordinate coordinate, String classifier) {
        String layout = NexusCatalog.label(classifier);
        Optional<NexusCatalog.Described> described = view.described(coordinate);
        Transfer transfer = new Transfer(view.nameOf(coordinate) + " (" + layout + ")");
        view.showTransfer(transfer, Optional.of(DashboardView.downloadKey(coordinate, classifier)));
        work.submit(() -> {
            if (!transfer.started()) {
                return;
            }
            try {
                Path store = StoreDownload.download(RepositoryHttpClients.newClient(), DashboardCommand.access(settings),
                        coordinate, classifier, described, settings.solor(), settings.localRepository(), transfer);
                transfer.finished(Transfer.State.DONE, "Downloaded " + store.getFileName());
                Platform.runLater(() -> {
                    selectAfterRefresh = coordinate.artifactId();
                    refresh();
                    view.setMessage("Downloaded " + store.getFileName());
                });
            } catch (CancellationException e) {
                transfer.finished(Transfer.State.CANCELLED, "Cancelled. What had downloaded is kept, so downloading it"
                        + " again picks up where this one stopped.");
            } catch (Exception e) {
                transfer.finished(Transfer.State.FAILED, "Could not download: " + e.getMessage());
            }
        });
    }

    /**
     * Upgrades a store in place on the work thread (decision 20 of the design): the newest build downloads
     * into a working folder beside it, the person's work is carried in, Komet with no window checks that
     * the new build opens and holds every change set, and the folders swap by rename, the old store kept
     * as {@code <store>.previous}. Every failure leaves the store as it was, carrying the work back; a
     * build that failed its check is kept beside the store for inspection.
     */
    private void upgrade(NoticeAction.Upgrade action) {
        KnowledgeBase store = action.store();
        Optional<KometInstallation> validator = LauncherChoice.chooseForLoad(komets, store);
        if (validator.isEmpty()) {
            view.setMessage("Upgrading needs Komet " + LauncherChoice.LOAD_CHANGE_SETS_SINCE + " or later");
            return;
        }
        List<String> refusals;
        try {
            refusals = StoreUpgrade.refusals(store, view.sync(store.path()), HeapSizing.Machine.current(komets).running());
        } catch (IOException e) {
            view.setMessage("Could not read " + store.name() + ": " + e.getMessage());
            return;
        }
        if (!refusals.isEmpty()) {
            view.showRefusals("Upgrade " + store.displayName(), refusals);
            return;
        }
        KometInstallation komet = validator.get();
        Path work = StoreUpgrade.workFolder(store.path());
        Path previous = StoreUpgrade.previousFolder(store.path());
        Transfer transfer = new Transfer("Upgrade " + view.nameOf(action.coordinate()));
        view.showTransfer(transfer, Optional.of(DashboardView.upgradeKey(store.path())));
        this.work.submit(() -> {
            if (!transfer.started()) {
                return;
            }
            boolean carried = false;
            try {
                transfer.phase("Downloading the newest build");
                StoreDownload.downloadTo(RepositoryHttpClients.newClient(), DashboardCommand.access(settings), action.coordinate(),
                        action.classifier(), work, settings.localRepository(), transfer);
                transfer.phase("Carrying your change sets and preferences");
                StoreUpgrade.Carried carry = StoreUpgrade.carry(store.path(), work);
                carried = true;
                if (!carry.conflicts().isEmpty()) {
                    StoreUpgrade.carry(work, store.path());
                    StoreUpgrade.deleteTree(work);
                    transfer.finished(Transfer.State.FAILED, "Not upgraded: the new build holds " + carry.conflicts().size()
                            + (carry.conflicts().size() == 1 ? " file" : " files") + " of the same name as yours with other"
                            + " contents, the first " + store.path().relativize(carry.conflicts().getFirst())
                            + ". Your store is as it was.");
                    return;
                }
                transfer.phase("Checking the new build in Komet " + komet.version());
                KnowledgeBase staged = KnowledgeBaseScanner.inspect(work);
                KometLauncher.HeadlessRun run = KometLauncher.loadChangeSets(komet, staged, heapPlan(komet, staged).environment(),
                        settings.logFolder());
                if (!run.clean()) {
                    StoreUpgrade.carry(work, store.path());
                    transfer.finished(Transfer.State.FAILED, "Not upgraded: Komet could not load every change set into the new"
                            + " build (status " + run.exitStatus() + "). Your store is as it was; the new build is kept beside"
                            + " it as " + work.getFileName() + " for inspection. Move it to the " + Finder.trashName()
                            + " to upgrade again.");
                    Platform.runLater(() -> {
                        view.setLastLog(run.log());
                        view.showRunReport(store, run);
                    });
                    return;
                }
                transfer.phase("Swapping the new build in");
                if (Files.exists(previous)) {
                    Finder.trash(List.of(previous));
                }
                StoreUpgrade.swap(store.path(), work);
                transfer.finished(Transfer.State.DONE, "Upgraded " + store.name() + ": " + carry.summary() + ". The build you"
                        + " had is beside it as " + previous.getFileName() + "; Restore puts it back.");
                Platform.runLater(() -> {
                    selectAfterRefresh = KnowledgeBaseFamily.familyName(store);
                    view.setLastLog(run.log());
                    refresh();
                });
            } catch (CancellationException e) {
                transfer.finished(Transfer.State.CANCELLED, "Cancelled before anything changed. What had downloaded is kept,"
                        + " so upgrading again picks up where this one stopped.");
            } catch (Exception e) {
                String kept = "";
                if (carried) {
                    try {
                        StoreUpgrade.carry(work, store.path());
                    } catch (IOException back) {
                        kept = " Your change sets and preferences may be in " + work.getFileName() + ": " + back.getMessage();
                    }
                }
                transfer.finished(Transfer.State.FAILED, "Could not upgrade: " + e.getMessage() + "."
                        + (kept.isEmpty() ? " Your store is as it was" + (Files.exists(work) ? "; " + work.getFileName()
                        + " is kept beside it for inspection." : ".") : kept));
                Platform.runLater(this::refresh);
            }
        });
    }

    /**
     * Reinstalls a store on the work thread (decision 20): the store goes to the Trash, with everything in
     * it, and the newest build downloads under the same name. The dashboard never writes over a store; the
     * Trash is the way back.
     */
    private void reinstall(NoticeAction.Reinstall action) {
        KnowledgeBase store = action.store();
        List<String> refusals;
        try {
            refusals = StoreUpgrade.refusals(store, view.sync(store.path()), HeapSizing.Machine.current(komets).running());
        } catch (IOException e) {
            view.setMessage("Could not read " + store.name() + ": " + e.getMessage());
            return;
        }
        if (!refusals.isEmpty()) {
            view.showRefusals("Reinstall " + store.displayName(), refusals);
            return;
        }
        Transfer transfer = new Transfer("Reinstall " + view.nameOf(action.coordinate()));
        view.showTransfer(transfer, Optional.of(DashboardView.upgradeKey(store.path())));
        work.submit(() -> {
            if (!transfer.started()) {
                return;
            }
            try {
                transfer.phase("Moving " + store.name() + " to the " + Finder.trashName());
                Finder.trash(List.of(store.path()));
                Platform.runLater(this::refresh);
                transfer.phase("Downloading the newest build");
                StoreDownload.downloadTo(RepositoryHttpClients.newClient(), DashboardCommand.access(settings), action.coordinate(),
                        action.classifier(), store.path(), settings.localRepository(), transfer);
                transfer.finished(Transfer.State.DONE, "Reinstalled " + store.name() + ". The store you had is in the "
                        + Finder.trashName() + ".");
                Platform.runLater(() -> {
                    selectAfterRefresh = KnowledgeBaseFamily.familyName(store);
                    refresh();
                });
            } catch (CancellationException e) {
                transfer.finished(Transfer.State.CANCELLED, "Cancelled. The store you had is in the " + Finder.trashName()
                        + "; what had downloaded is kept, so downloading again picks up where this one stopped.");
                Platform.runLater(this::refresh);
            } catch (Exception e) {
                transfer.finished(Transfer.State.FAILED, "Could not reinstall: " + e.getMessage());
                Platform.runLater(this::refresh);
            }
        });
    }

    /** Puts a store's previous build back on the work thread, carrying the work done since (decision 20). */
    private void restore(KnowledgeBase store) {
        List<String> refusals;
        try {
            refusals = StoreUpgrade.refusals(store, List.of(), HeapSizing.Machine.current(komets).running());
        } catch (IOException e) {
            view.setMessage("Could not read " + store.name() + ": " + e.getMessage());
            return;
        }
        if (!refusals.isEmpty()) {
            view.showRefusals("Restore " + store.displayName(), refusals);
            return;
        }
        view.setMessage("Restoring the previous build of " + store.name() + " …");
        work.submit(() -> {
            try {
                StoreUpgrade.Carried carried = StoreUpgrade.restore(store.path());
                Platform.runLater(() -> {
                    selectAfterRefresh = KnowledgeBaseFamily.familyName(store);
                    refresh();
                    view.setMessage("Restored the previous build of " + store.name() + "; " + carried.summary()
                            + ". The build it replaced is now the previous one.");
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    view.setMessage("Could not restore " + store.name() + ": " + e.getMessage());
                    refresh();
                });
            }
        });
    }

    private void installKomet(KometRelease release) {
        view.setInstalling(true);
        Transfer transfer = new Transfer("Komet " + release.version() + " from " + release.source().label());
        view.showTransfer(transfer, Optional.empty());
        work.submit(() -> {
            if (!transfer.started()) {
                Platform.runLater(() -> view.setInstalling(false));
                return;
            }
            try {
                KometInstallation installed = KometInstaller.install(RepositoryHttpClients.newClient(), release,
                        settings.installLocation(), settings.localRepository(), transfer);
                transfer.finished(Transfer.State.DONE, "Installed Komet " + installed.version());
                Platform.runLater(() -> {
                    view.setInstalling(false);
                    refresh();
                    view.setMessage("Installed Komet " + installed.version() + " in " + installed.bundle().getParent());
                });
            } catch (CancellationException e) {
                transfer.finished(Transfer.State.CANCELLED, "Cancelled. What had downloaded is kept, so installing it"
                        + " again picks up where this one stopped.");
                Platform.runLater(() -> view.setInstalling(false));
            } catch (Exception e) {
                transfer.finished(Transfer.State.FAILED, "Could not install Komet " + release.version() + ": " + e.getMessage());
                Platform.runLater(() -> view.setInstalling(false));
            }
        });
    }

    /**
     * Downloads and checks a newer dashboard on the work thread, as a transfer the person can watch and
     * cancel, then starts the helper that swaps it in once this one has quit, and quits.
     */
    private void updateDashboard(KometRelease release) {
        SelfUpdate.Placement where = placement.orElseThrow();
        view.setUpdatingDashboard(true);
        Transfer transfer = new Transfer("IKE Dashboard " + release.version());
        view.showTransfer(transfer, Optional.empty());
        work.submit(() -> {
            if (!transfer.started()) {
                Platform.runLater(() -> view.setUpdatingDashboard(false));
                return;
            }
            try {
                Path staged = SelfUpdate.stage(RepositoryHttpClients.newClient(), release, where,
                        settings.localRepository(), transfer);
                restartAs(where, staged);
                transfer.finished(Transfer.State.DONE, "Restarting as the IKE Dashboard " + release.version());
            } catch (CancellationException e) {
                transfer.finished(Transfer.State.CANCELLED, "Cancelled. What had downloaded is kept, so updating"
                        + " again picks up where this one stopped.");
                Platform.runLater(() -> view.setUpdatingDashboard(false));
            } catch (Exception e) {
                transfer.finished(Transfer.State.FAILED, "Could not update the dashboard: " + e.getMessage());
                Platform.runLater(() -> view.setUpdatingDashboard(false));
            }
        });
    }

    /**
     * Moves a translocated dashboard into {@code ~/Applications} and restarts it there; when a dashboard
     * as new is already there, opens that one instead.
     */
    private void moveDashboard() {
        SelfUpdate.Placement where = placement.orElseThrow();
        view.setUpdatingDashboard(true);
        view.setMessage("Moving the IKE Dashboard into " + where.target().getParent() + " …");
        work.submit(() -> {
            try {
                Optional<SelfUpdate.Running> there = SelfUpdate.read(where.target());
                if (there.isPresent()
                        && KometInstallations.compareVersions(there.get().version(), where.running().version()) >= 0) {
                    new ProcessBuilder(SelfUpdate.relaunch(where.target())).start();
                    Platform.runLater(Platform::exit);
                    return;
                }
                restartAs(where, SelfUpdate.stageRunning(where));
            } catch (Exception e) {
                Platform.runLater(() -> {
                    view.setUpdatingDashboard(false);
                    view.setMessage("Could not move the dashboard: " + e.getMessage());
                });
            }
        });
    }

    /** Starts the helper that swaps a staged dashboard in once this one has quit and opens it, then quits. */
    private void restartAs(SelfUpdate.Placement where, Path staged) throws IOException {
        SelfUpdate.swap(where, staged, ProcessHandle.current().pid(), SelfUpdate.relaunch(where.target()),
                settings.logFolder().resolve("self-update.log"));
        Platform.runLater(Platform::exit);
    }

    private void trash(List<KnowledgeBase> copies) {
        view.setMessage("Moving " + copies.size() + (copies.size() == 1 ? " copy" : " copies") + " to the " + Finder.trashName() + " …");
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
        HeapSizing.Plan plan = heapPlan(komet, knowledgeBase);
        Optional<HeapSizing.Need> need = HeapView.confirm(view.getScene().getWindow(), knowledgeBase, komet, plan);
        if (need.isEmpty()) {
            view.setMessage("Did not open " + knowledgeBase.name());
            return;
        }
        HeapSizing.Plan launched = need.get().equals(plan.need()) ? plan : plan.with(need.get());
        try {
            Path log = KometLauncher.launch(komet, knowledgeBase, launched.environment(), settings.logFolder());
            history.opened(KnowledgeBaseFamily.familyName(knowledgeBase));
            view.setLastOpened(history::lastOpened);
            view.setLastLog(log);
            view.setMessage("Opening " + knowledgeBase.name() + " in Komet " + komet.version() + " with "
                    + launched.need().text());
            view.heapChanged();
        } catch (Exception e) {
            view.setMessage("Could not start Komet " + komet.version() + ": " + e.getMessage());
        }
    }

    /**
     * What a store is launched with in a Komet, on this machine as it is now: the rule and any override
     * from the preferences, the store's data measured once per refresh, and the Komets running.
     */
    private HeapSizing.Plan heapPlan(KometInstallation komet, KnowledgeBase knowledgeBase) {
        long data = dataBytes.computeIfAbsent(knowledgeBase.path(), path -> {
            try {
                return HeapSizing.dataBytes(knowledgeBase);
            } catch (IOException e) {
                return knowledgeBase.sizeBytes();
            }
        });
        return HeapSizing.plan(HeapSizing.Rule.read(heapPreferences), knowledgeBase, data,
                HeapSizing.override(heapPreferences.node(HeapSizing.STORES_NODE), knowledgeBase), komet,
                HeapSizing.Machine.current(komets));
    }

    /** The Memory window for a store; what the person changes is kept in the preferences. */
    private void editHeap(KnowledgeBase knowledgeBase) {
        HeapSizing.Rule rule = HeapSizing.Rule.read(heapPreferences);
        Preferences stores = heapPreferences.node(HeapSizing.STORES_NODE);
        long data = dataBytes.getOrDefault(knowledgeBase.path(), knowledgeBase.sizeBytes());
        HeapView.edit(view.getScene().getWindow(), knowledgeBase, rule, HeapSizing.override(stores, knowledgeBase),
                HeapSizing.needed(rule, knowledgeBase, data))
                .ifPresent(edit -> {
            edit.rule().write(heapPreferences);
            view.browser().setHeapRule(edit.rule());
            HeapSizing.setOverride(stores, knowledgeBase, edit.override());
            view.heapChanged();
            view.setMessage(edit.override().map(need -> knowledgeBase.name() + " opens with " + need.text())
                    .orElse(knowledgeBase.name() + " opens with the rule's heap"));
        });
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
