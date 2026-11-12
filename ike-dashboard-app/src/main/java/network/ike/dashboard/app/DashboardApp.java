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
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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


    /** How often what needs the network is checked again while the dashboard runs. */
    static final Duration NETWORK_CHECK_INTERVAL = Duration.ofHours(1);

    /** Transfers under way at once; the next waits its turn, saying so. */
    static final int TRANSFERS_AT_ONCE = 3;

    /**
     * Reading {@code ~/Solor} and the installed Komets, and every check that needs the network, in order.
     * Its own thread, so Refresh and the hourly check never wait behind a transfer.
     */
    private final ExecutorService checks = Executors.newSingleThreadExecutor(daemon("ike-dashboard-checks"));
    /** Downloads, upgrades, reinstalls and installs, {@value #TRANSFERS_AT_ONCE} at a time. */
    private final ExecutorService transfers = Executors.newFixedThreadPool(TRANSFERS_AT_ONCE, daemon("ike-dashboard-transfer"));
    /** Quick changes to stores and to the dashboard itself: restore, move to the Trash, clean up, move. */
    private final ExecutorService files = Executors.newSingleThreadExecutor(daemon("ike-dashboard-files"));
    /** Maven, for a new knowledge base: its draft, its creation, its build. One at a time; the form waits on it. */
    private final ExecutorService maven = Executors.newSingleThreadExecutor(daemon("ike-dashboard-maven"));
    /** The Maven run under way, which Cancel stops. */
    private volatile java.util.concurrent.Future<?> mavenRun;
    private final ScheduledExecutorService clock = Executors.newSingleThreadScheduledExecutor(daemon("ike-dashboard-clock"));
    /**
     * One upgrade's check in Komet at a time: each runs a Komet with the store's heap, and two side by side
     * could take more memory than the machine has.
     */
    private final Semaphore kometCheck = new Semaphore(1);
    /** Stores an upgrade, reinstall, restore, move to the Trash or download is changing; a second is refused. */
    private final Set<Path> busy = ConcurrentHashMap.newKeySet();
    /** Stores still being written into the knowledge-base folder, which a scan leaves out. */
    private final Set<Path> arriving = ConcurrentHashMap.newKeySet();
    /** Transfers submitted and not yet ended, waiting or running. */
    private final AtomicInteger pendingTransfers = new AtomicInteger();
    private final Optional<RepositorySync.Git> git = RepositorySync.Git.find();
    private final Map<Path, Notice.Freshness> remoteChecked = new ConcurrentHashMap<>();
    private volatile List<KnowledgeBaseFamily> current = List.of();
    private volatile Instant nexusChecked;
    private final Map<String, BuildStatus> lastStatuses = new ConcurrentHashMap<>();
    private DashboardSettings settings;
    private OpenHistory history;
    private Preferences heapPreferences;
    private Preferences heapStores;

    /** How long Komet release notes are kept before GitHub is asked again. */
    private static final java.time.Duration NOTES_AGE = java.time.Duration.ofMinutes(10);
    private volatile Instant notesFetched;
    private volatile List<KometInstallation> komets = List.of();
    private final Map<Path, Long> dataBytes = new ConcurrentHashMap<>();
    private DashboardView view;
    private volatile String selectAfterRefresh;
    /** A project just created, which the Projects tab shows once it has been read. */
    private volatile Path showProjectAfterRefresh;
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

    private static ThreadFactory daemon(String name) {
        AtomicInteger made = new AtomicInteger();
        return runnable -> {
            int number = made.incrementAndGet();
            Thread thread = new Thread(runnable, number == 1 ? name : name + "-" + number);
            thread.setDaemon(true);
            return thread;
        };
    }

    @Override
    public void start(Stage stage) {
        settings = DashboardSettings.current();
        history = OpenHistory.current();
        DashboardPreferences preferences = DashboardPreferences.current();
        heapPreferences = preferences.node(DashboardPreferences.Node.HEAP);
        heapStores = preferences.node(DashboardPreferences.Node.HEAP_STORES);
        view = new DashboardView();
        view.setCollections(settings.collections());
        view.setLastOpened(history::lastOpened);
        view.setSortRecent(preferences.getBoolean(DashboardPreferences.Key.SORT_RECENT).orElse(false));
        view.setOnSortChange(recent -> preferences.putBoolean(DashboardPreferences.Key.SORT_RECENT, recent));
        view.setOnRefresh(this::refresh);
        view.setOnOpen(this::open);
        view.setHeapPlanner((komet, knowledgeBase) -> Optional.of(heapPlan(komet, knowledgeBase)));
        view.setOnEditHeap(this::editHeap);
        view.browser().setHeapRule(HeapSizing.Rule.read(heapPreferences));
        view.setOnDownload(this::download);
        view.setOnTrash(this::trash);
        view.setOnTrashKomets(this::trashKomets);
        view.setUnsavedWork(copy -> UnsavedWork.of(git, copy.path()));
        view.setOnOpenLink(uri -> getHostServices().showDocument(uri.toString()));
        view.setKometPins(KometPins.current());
        view.setOnBrowse(this::loadCatalog);
        NewKnowledgeBaseForm form = view.newKnowledgeBase();
        form.setProjectsFolder(settings.projects());
        form.setPrerequisites(KnowledgeBaseCreation.prerequisites().missing());
        form.setOnDraft(this::draftKnowledgeBase);
        form.setOnCreate(this::createKnowledgeBase);
        form.setOnCancel(() -> Optional.ofNullable(mavenRun).ifPresent(run -> run.cancel(true)));
        ProjectsPane projects = view.projects();
        projects.setOnBuild(this::buildProject);
        projects.setOnReveal(path -> run(() -> Finder.reveal(path), "Could not show " + Names.of(path)));
        projects.setOnCancel(() -> Optional.ofNullable(mavenRun).ifPresent(run -> run.cancel(true)));
        view.setOnInstallKomet(this::installKomet);
        view.setOnUpgrade(this::upgrade);
        view.setOnReinstall(this::reinstall);
        view.setOnCopy(this::copyStore);
        view.setOnUpdateDashboard(this::updateDashboard);
        view.setOnMoveDashboard(this::moveDashboard);
        view.setOnReveal(path -> run(() -> Finder.reveal(path), "Could not show " + Names.of(path)));
        view.setOnOpenFile(path -> run(() -> Finder.open(path), "Could not open " + Names.of(path)));
        NoticeDismissals dismissals = NoticeDismissals.current();
        view.setDismissed(dismissals::dismissed);
        view.setOnDismiss(dismissals::dismiss);
        stage.setTitle("IKE Dashboard");
        stage.getIcons().setAll(icons());
        stage.setScene(new Scene(view, 1080, 640));
        stage.show();
        placement = SelfUpdate.running().map(running -> SelfUpdate.place(running, Path.of(System.getProperty("user.home"))));
        view.setDashboard(placement.map(where -> new SelfUpdate.State(where, Optional.empty())));
        placement.ifPresent(where -> files.submit(() -> {
            try {
                SelfUpdate.cleanUp(where);
            } catch (IOException e) {
                Platform.runLater(() -> view.setMessage("Could not remove what the last update left in " + where.work()
                        + ": " + e.getMessage()));
            }
        }));
        refresh();
        long interval = NETWORK_CHECK_INTERVAL.toMinutes();
        clock.scheduleAtFixedRate(() -> checks.submit(() -> checkNetwork(current)), interval, interval, TimeUnit.MINUTES);
    }

    @Override
    public void stop() {
        clock.shutdownNow();
        checks.shutdownNow();
        transfers.shutdownNow();
        files.shutdownNow();
        maven.shutdownNow();
    }

    /** Runs a transfer on the transfer threads, counted from now until it ends. */
    private void submitTransfer(Runnable task) {
        pendingTransfers.incrementAndGet();
        transfers.submit(() -> {
            try {
                task.run();
            } finally {
                pendingTransfers.decrementAndGet();
            }
        });
    }

    /**
     * Claims stores for one operation, so no other changes them meanwhile.
     *
     * @return whether they were all free; when not, the person is told and nothing is claimed
     */
    private boolean claim(String action, Collection<Path> stores) {
        synchronized (busy) {
            Optional<Path> taken = stores.stream().filter(busy::contains).findFirst();
            if (taken.isPresent()) {
                view.setMessage("Cannot " + action + " " + taken.get().getFileName()
                        + " now: something else is changing it. Try again once it has finished.");
                return false;
            }
            busy.addAll(stores);
            return true;
        }
    }

    /** Claims where a download lands, as it is named; refuses it when something else is changing it. */
    private void claimArriving(Path destination) throws IOException {
        synchronized (busy) {
            if (busy.contains(destination)) {
                throw new IOException(destination.getFileName() + " is being changed by another transfer");
            }
            busy.add(destination);
            arriving.add(destination);
        }
    }

    private void release(Collection<Path> stores) {
        synchronized (busy) {
            busy.removeAll(stores);
            arriving.removeAll(stores);
        }
    }

    /** Reads {@code ~/Solor} and the installed Komets, then asks Nexus, in the background. */
    private void refresh() {
        view.setMessage("Reading " + settings.solor() + " …");
        checks.submit(() -> {
            List<KnowledgeBaseFamily> families;
            try {
                // A store a transfer is still writing is not one yet; its transfer refreshes when it ends.
                List<KnowledgeBase> knowledgeBases = KnowledgeBaseScanner.scan(settings.solor()).stream()
                        .filter(knowledgeBase -> !arriving.contains(knowledgeBase.path()))
                        .toList();
                List<KometInstallation> komets = KometInstallations.find(KometInstallations.defaultRoots(
                        Path.of(System.getProperty("user.home")), settings.installLocation()));
                families = KnowledgeBaseFamily.managed(knowledgeBases);
                List<KnowledgeBaseFamily> found = families;
                List<KnowledgeBase> unmanaged = KnowledgeBaseFamily.unmanaged(knowledgeBases);
                dataBytes.clear();
                this.komets = komets;
                KnowledgeBaseProject.Names names = KnowledgeBaseProject.Names.read(settings.projects(), settings.solor(), Set.of());
                List<KnowledgeBaseProjectFolder> projects = KnowledgeBaseProjectFolder.scan(settings.projects());
                Map<Path, RepositorySync.Status> projectGit = new java.util.HashMap<>();
                if (git.isPresent()) {
                    for (KnowledgeBaseProjectFolder project : projects) {
                        if (Files.exists(project.folder().resolve(".git"))) {
                            projectGit.put(project.folder(), RepositorySync.local(git.get(),
                                    new RepositorySync.Repository(project.folder(), "project")));
                        }
                    }
                }
                Platform.runLater(() -> {
                    view.newKnowledgeBase().setNamesHere(names);
                    view.projects().setProjects(settings.projects(), projects, projectGit, git.isPresent());
                    Path created = showProjectAfterRefresh;
                    if (created != null) {
                        showProjectAfterRefresh = null;
                        view.showProjects();
                        view.projects().select(created);
                    }
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
     * Runs on the checks thread: everything that needs the network. Nexus first, for newer builds, a newer
     * Komet and what is not yet downloaded; then each store's repositories against their remotes.
     */
    private void checkNetwork(List<KnowledgeBaseFamily> families) {
        checkNexus(families);
        checkCatalog();
        checkSync(families);
    }

    /** Runs on the checks thread: what Nexus offers, for the notices of knowledge bases not yet downloaded. */
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
     * Runs on the checks thread: the sync state of every git repository inside each store, first from its
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
     * Runs on the checks thread: the newer-build check for each knowledge base, and the newest Komet. A
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
        // What changed in each Komet version, from its checkpoint release; asked at most every ten minutes, so
        // GitHub's limit on unsigned requests is never near.
        if (notesFetched == null || notesFetched.isBefore(Instant.now().minus(NOTES_AGE))) {
            try {
                Map<String, KometNotes> notes = KometNotes.fetch(RepositoryHttpClients.newClient(),
                        KometReleases.GITHUB_REPOSITORY);
                notesFetched = Instant.now();
                Platform.runLater(() -> view.setKometNotes(notes));
            } catch (Exception e) {
                // The notes stay as they were; the Komet tab links to the releases page instead.
            }
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
                + (newer == 0 ? " · all up to date" : " · " + newer + " with a newer version")
                + (problem == null ? "" : " · " + problem);
        Platform.runLater(() -> view.setMessage(summary));
    }


    private void loadCatalog() {
        checks.submit(() -> {
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
     * Downloads a store or a protobuf on a transfer thread, beside other transfers, shown as a transfer the person can
     * watch and cancel: a download waiting its turn says so, and one cancelled before its turn never starts.
     */
    private void download(Coordinate coordinate, String classifier) {
        String layout = NexusCatalog.label(classifier);
        Optional<NexusCatalog.Described> described = view.described(coordinate);
        Transfer transfer = new Transfer(view.nameOf(coordinate) + " (" + layout + ")");
        view.showTransfer(transfer, Optional.of(DashboardView.downloadKey(coordinate, classifier)));
        submitTransfer(() -> {
            if (!transfer.started()) {
                return;
            }
            AtomicReference<Path> placed = new AtomicReference<>();
            try {
                Path store = StoreDownload.download(RepositoryHttpClients.newClient(), DashboardCommand.access(settings),
                        coordinate, classifier, described, settings.solor(), settings.localRepository(), transfer,
                        destination -> {
                            claimArriving(destination);
                            placed.set(destination);
                        });
                release(List.of(store));
                transfer.finished(Transfer.State.DONE, "Downloaded " + Names.of(store.getFileName()));
                Platform.runLater(() -> {
                    selectAfterRefresh = coordinate.artifactId();
                    refresh();
                    view.setMessage("Downloaded " + Names.of(store.getFileName()));
                });
            } catch (CancellationException e) {
                transfer.finished(Transfer.State.CANCELLED, "Cancelled. The part already downloaded is kept, so the next try continues from there.");
            } catch (Exception e) {
                transfer.finished(Transfer.State.FAILED, "Could not download: " + e.getMessage());
            } finally {
                Optional.ofNullable(placed.get()).ifPresent(destination -> release(List.of(destination)));
            }
        });
    }

    /**
     * Upgrades a store in place on a transfer thread (decision 20 of the design): the newest build downloads
     * into a working folder beside it, the person's work is carried in, Komet with no window checks that
     * the new build opens and loads every change set, and the folders swap by rename; the old build, confirmed
     * replaced, goes to the Trash (KEC, 2026-11-08). Every failure leaves the store as it was, carrying the work
     * back; a build that failed its check is kept beside the store for inspection.
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
            refusals = StoreUpgrade.refusals(store, syncOf(store.path()), HeapSizing.Machine.current(komets).running());
        } catch (IOException e) {
            view.setMessage("Could not read " + Names.of(store.name()) + ": " + e.getMessage());
            return;
        }
        if (!refusals.isEmpty()) {
            view.showRefusals("Upgrade " + store.displayName(), refusals);
            return;
        }
        if (!claim("upgrade", List.of(store.path()))) {
            return;
        }
        KometInstallation komet = validator.get();
        List<RepositorySync.Status> sync = syncOf(store.path());
        Path work = StoreUpgrade.workFolder(store.path());
        Path previous = StoreUpgrade.previousFolder(store.path());
        Transfer transfer = new Transfer("Upgrade " + view.nameOf(action.coordinate()));
        view.showTransfer(transfer, Optional.of(DashboardView.upgradeKey(store.path())));
        submitTransfer(() -> {
            if (!transfer.started()) {
                release(List.of(store.path()));
                return;
            }
            boolean carried = false;
            List<Path[]> setAside = List.of();
            try {
                transfer.phase("Downloading the latest version");
                StoreDownload.downloadTo(RepositoryHttpClients.newClient(), DashboardCommand.access(settings), action.coordinate(),
                        action.classifier(), work, settings.localRepository(), transfer);
                transfer.phase("Moving your change sets, preferences and repositories into it");
                StoreUpgrade.Carried carry = StoreUpgrade.carry(store.path(), work);
                carried = true;
                if (!carry.conflicts().isEmpty()) {
                    StoreUpgrade.carry(work, store.path());
                    StoreUpgrade.deleteTree(work);
                    transfer.finished(Transfer.State.FAILED, "Not upgraded. The new version has "
                            + carry.conflicts().size() + (carry.conflicts().size() == 1 ? " file" : " files")
                            + " with the same name as yours but different contents. The first is "
                            + Names.of(store.path().relativize(carry.conflicts().getFirst())) + ". Your folder is as it was.");
                    return;
                }
                Optional<KometLauncher.HeadlessRun> checked = check(komet, work, transfer);
                if (checked.isEmpty()) {
                    StoreUpgrade.carry(work, store.path());
                    carried = false;
                    StoreUpgrade.deleteTree(work);
                    transfer.finished(Transfer.State.CANCELLED, "Cancelled. Nothing in your folder changed. The download"
                            + " is kept, so the next upgrade does not download it again.");
                    return;
                }
                KometLauncher.HeadlessRun run = checked.get();
                List<RefusedChangeSets.Refusal> refused = run.exitStatus() == 1 ? RefusedChangeSets.parse(run.report()) : List.of();
                if (!run.clean() && !refused.isEmpty()) {
                    // Some change sets do not load (ike-issues#1356): the person cancels, or sets them aside and goes on.
                    List<RefusedChangeSets.Summary> summaries = refused.stream()
                            .map(refusal -> RefusedChangeSets.analyze(work, refusal)).toList();
                    Optional<String> whyNoAside = sharedRepository(store.path(), refused, sync);
                    transfer.phase("Waiting for your choice: " + DashboardView.refusedHeader(refused.size()).toLowerCase());
                    CompletableFuture<Boolean> choice = new CompletableFuture<>();
                    KometLauncher.HeadlessRun first = run;
                    Platform.runLater(() -> {
                        view.setLastLog(first.log());
                        choice.complete(view.chooseForRefused(store, summaries, whyNoAside, first));
                    });
                    if (!choice.get()) {
                        StoreUpgrade.carry(work, store.path());
                        carried = false;
                        StoreUpgrade.deleteTree(work);
                        transfer.finished(Transfer.State.CANCELLED, "Not upgraded. " + DashboardView.refusedHeader(refused.size())
                                + ". Your folder is as it was. The download is kept, so the next upgrade does not download"
                                + " it again.");
                        return;
                    }
                    transfer.phase("Setting aside " + refused.size() + (refused.size() == 1 ? " change set" : " change sets"));
                    setAside = RefusedChangeSets.setAside(work, refused);
                    // What remains must load: the files that did are registered, so Komet loads nothing twice.
                    checked = check(komet, work, transfer);
                    if (checked.isEmpty()) {
                        RefusedChangeSets.putBack(setAside);
                        setAside = List.of();
                        StoreUpgrade.carry(work, store.path());
                        carried = false;
                        StoreUpgrade.deleteTree(work);
                        transfer.finished(Transfer.State.CANCELLED, "Cancelled. Nothing in your folder changed. The"
                                + " download is kept, so the next upgrade does not download it again.");
                        return;
                    }
                    run = checked.get();
                }
                if (!run.clean()) {
                    RefusedChangeSets.putBack(setAside);
                    setAside = List.of();
                    StoreUpgrade.carry(work, store.path());
                    carried = false;
                    transfer.finished(Transfer.State.FAILED, "Not upgraded. Komet could not load every change set into the"
                            + " new version. Its exit status was " + run.exitStatus() + ". Your folder is as it was. The new"
                            + " version is kept next to it as " + Names.of(work.getFileName()) + " so you can look at it. Move it to the " + Finder.trashName()
                            + " to upgrade again.");
                    KometLauncher.HeadlessRun failed = run;
                    Platform.runLater(() -> {
                        view.setLastLog(failed.log());
                        view.showRunReport(store, failed);
                    });
                    return;
                }
                transfer.phase("Putting the new version in place");
                if (Files.exists(previous)) {
                    Finder.trash(List.of(previous));
                }
                StoreUpgrade.swap(store.path(), work);
                // The new build passed Komet's check and is in place: the old one goes to the Trash, the way back.
                String old;
                try {
                    Finder.trash(List.of(previous));
                    old = "The old version is in the " + Finder.trashName() + ". You can restore it from there if you need it.";
                } catch (IOException | InterruptedException e) {
                    old = "The old version is next to it as " + Names.of(previous.getFileName()) + ". It could not be moved to the "
                            + Finder.trashName() + " (" + e.getMessage() + ").";
                }
                release(List.of(store.path()));
                transfer.finished(Transfer.State.DONE, "Upgraded " + Names.of(store.name()) + ": " + carry.summary()
                        + (setAside.isEmpty() ? "" : "; " + setAside.size() + (setAside.size() == 1 ? " change set" : " change sets")
                        + " set aside in " + Names.of(RefusedChangeSets.UNPROCESSED_FOLDER)) + ". " + old);
                KometLauncher.HeadlessRun done = run;
                Platform.runLater(() -> {
                    selectAfterRefresh = KnowledgeBaseFamily.familyName(store);
                    view.setLastLog(done.log());
                    refresh();
                });
            } catch (CancellationException e) {
                transfer.finished(Transfer.State.CANCELLED, "Cancelled. Nothing in your folder changed. The part already"
                        + " downloaded is kept, so the next upgrade continues from there.");
            } catch (Exception e) {
                String kept = "";
                try {
                    RefusedChangeSets.putBack(setAside);
                    if (carried) {
                        StoreUpgrade.carry(work, store.path());
                    }
                } catch (IOException back) {
                    kept = " Your change sets and preferences may still be in " + Names.of(work.getFileName()) + ": " + back.getMessage();
                }
                release(List.of(store.path()));
                transfer.finished(Transfer.State.FAILED, "Could not upgrade: " + e.getMessage() + "."
                        + (kept.isEmpty() ? " Your folder is as it was." + (Files.exists(work) ? " " + Names.of(work.getFileName())
                        + " is kept next to it so you can look at it." : "") : kept));
                Platform.runLater(this::refresh);
            } finally {
                release(List.of(store.path()));
            }
        });
    }

    /**
     * Checks a new build in Komet with no window, one check at a time across upgrades.
     *
     * @param komet    the Komet that checks it
     * @param work     the new build, with the person's work carried in
     * @param transfer the upgrade
     * @return what Komet reported; empty when the person cancelled while it waited its turn
     */
    private Optional<KometLauncher.HeadlessRun> check(KometInstallation komet, Path work, Transfer transfer)
            throws IOException, InterruptedException {
        if (!kometCheck.tryAcquire()) {
            transfer.phase("Waiting for another upgrade to finish its check in Komet");
            while (!kometCheck.tryAcquire(1, TimeUnit.SECONDS)) {
                if (transfer.cancelled()) {
                    return Optional.empty();
                }
            }
        }
        try {
            transfer.phase("Checking in Komet " + komet.version() + ", in the background, that every change set loads");
            KnowledgeBase staged = KnowledgeBaseScanner.inspect(work);
            return Optional.of(KometLauncher.loadChangeSets(komet, staged, heapPlan(komet, staged).environment(),
                    settings.logFolder()));
        } finally {
            kometCheck.release();
        }
    }

    /**
     * Why refused change sets cannot be set aside: one is in a git repository with a remote, where moving it
     * out would reach everyone else as a deletion.
     *
     * @param store    the store
     * @param refused  the refused files, relative to the store
     * @param sync     the store's repositories
     * @return the reason, or empty when they can be set aside
     */
    static Optional<String> sharedRepository(Path store, List<RefusedChangeSets.Refusal> refused,
                                             List<RepositorySync.Status> sync) {
        for (RefusedChangeSets.Refusal refusal : refused) {
            Path file = store.resolve(refusal.file()).toAbsolutePath().normalize();
            for (RepositorySync.Status status : sync) {
                if (status.remote() != RepositorySync.Remote.NONE
                        && file.startsWith(status.repository().folder().toAbsolutePath().normalize())) {
                    return Optional.of(Names.of(refusal.file().getFileName()) + " is in the git repository "
                            + status.repository().label() + ", which is shared through a remote. Moving it out would delete"
                            + " it for everyone who shares that repository, so it cannot be set aside. Cancel the upgrade"
                            + " for now.");
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Reinstalls a store on a transfer thread (decision 20): the store goes to the Trash, with everything in
     * it, and the newest build downloads under the same name. The dashboard never writes over a store; the
     * Trash is the way back.
     */
    private void reinstall(NoticeAction.Reinstall action) {
        KnowledgeBase store = action.store();
        List<String> refusals;
        try {
            refusals = StoreUpgrade.refusals(store, syncOf(store.path()), HeapSizing.Machine.current(komets).running());
        } catch (IOException e) {
            view.setMessage("Could not read " + Names.of(store.name()) + ": " + e.getMessage());
            return;
        }
        if (!refusals.isEmpty()) {
            view.showRefusals("Reinstall " + store.displayName(), refusals);
            return;
        }
        if (!claim("reinstall", List.of(store.path()))) {
            return;
        }
        Transfer transfer = new Transfer("Reinstall " + view.nameOf(action.coordinate()));
        view.showTransfer(transfer, Optional.of(DashboardView.upgradeKey(store.path())));
        submitTransfer(() -> {
            if (!transfer.started()) {
                release(List.of(store.path()));
                return;
            }
            try {
                transfer.phase("Moving " + Names.of(store.name()) + " to the " + Finder.trashName());
                // From here until the download ends, a scan leaves the half-written store out.
                arriving.add(store.path());
                Finder.trash(List.of(store.path()));
                Platform.runLater(this::refresh);
                transfer.phase("Downloading the latest version");
                StoreDownload.downloadTo(RepositoryHttpClients.newClient(), DashboardCommand.access(settings), action.coordinate(),
                        action.classifier(), store.path(), settings.localRepository(), transfer);
                release(List.of(store.path()));
                transfer.finished(Transfer.State.DONE, "Reinstalled " + Names.of(store.name()) + ". The old folder is in the "
                        + Finder.trashName() + ".");
                Platform.runLater(() -> {
                    selectAfterRefresh = KnowledgeBaseFamily.familyName(store);
                    refresh();
                });
            } catch (CancellationException e) {
                release(List.of(store.path()));
                transfer.finished(Transfer.State.CANCELLED, "Cancelled. The old folder is in the " + Finder.trashName()
                        + ". The part already downloaded is kept, so the next try continues from there.");
                Platform.runLater(this::refresh);
            } catch (Exception e) {
                release(List.of(store.path()));
                transfer.finished(Transfer.State.FAILED, "Could not reinstall: " + e.getMessage());
                Platform.runLater(this::refresh);
            } finally {
                release(List.of(store.path()));
            }
        });
    }

    /**
     * Copies a store into a new folder beside it, everything in it included (KEC, 2026-11-08): each folder is a
     * copy of its own, with its own change sets, upgraded on its own.
     */
    private void copyStore(KnowledgeBase store, String name) {
        List<String> refusals;
        try {
            refusals = StoreCopy.refusals(store, HeapSizing.Machine.current(komets).running());
        } catch (IOException e) {
            view.setMessage("Could not read " + Names.of(store.name()) + ": " + e.getMessage());
            return;
        }
        if (!refusals.isEmpty()) {
            view.showRefusals("Copy " + store.name(), refusals);
            return;
        }
        Path target = store.path().resolveSibling(name.strip());
        if (!claim("copy", List.of(store.path(), target))) {
            return;
        }
        view.setMessage("Copying " + Names.of(store.name()) + " to " + Names.of(target.getFileName()) + " …");
        files.submit(() -> {
            try {
                StoreCopy.copy(store.path(), name);
                Platform.runLater(() -> {
                    selectAfterRefresh = KnowledgeBaseFamily.familyName(store);
                    refresh();
                    view.setMessage("Copied " + Names.of(store.name()) + " to " + Names.of(target.getFileName()) + ", with its change sets,"
                            + " preferences and repositories.");
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    view.setMessage("Could not copy " + Names.of(store.name()) + ": " + e.getMessage());
                    refresh();
                });
            } finally {
                release(List.of(store.path(), target));
            }
        });
    }

    private void installKomet(KometRelease release) {
        view.setInstalling(true);
        Transfer transfer = new Transfer("Komet " + release.version() + " from " + release.source().label());
        view.showTransfer(transfer, Optional.empty());
        submitTransfer(() -> {
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
                transfer.finished(Transfer.State.CANCELLED, "Cancelled. The part already downloaded is kept, so the next try continues from there.");
                Platform.runLater(() -> view.setInstalling(false));
            } catch (Exception e) {
                transfer.finished(Transfer.State.FAILED, "Could not install Komet " + release.version() + ": " + e.getMessage());
                Platform.runLater(() -> view.setInstalling(false));
            }
        });
    }

    /**
     * Downloads and checks a newer dashboard on a transfer thread, as a transfer the person can watch and
     * cancel, waits for every other transfer to end, then starts the helper that swaps it in once this one
     * has quit, and quits.
     */
    private void updateDashboard(KometRelease release) {
        SelfUpdate.Placement where = placement.orElseThrow();
        view.setUpdatingDashboard(true);
        Transfer transfer = new Transfer("IKE Dashboard " + release.version());
        view.showTransfer(transfer, Optional.empty());
        submitTransfer(() -> {
            if (!transfer.started()) {
                Platform.runLater(() -> view.setUpdatingDashboard(false));
                return;
            }
            try {
                Path staged = SelfUpdate.stage(RepositoryHttpClients.newClient(), release, where,
                        settings.localRepository(), transfer);
                // Quitting stops every transfer where it stands, an upgrade midway through its swap included.
                if (othersUnderWay(1)) {
                    transfer.phase("Waiting for the other transfers to finish");
                    while (othersUnderWay(1)) {
                        if (transfer.cancelled()) {
                            throw new CancellationException();
                        }
                        Thread.sleep(1000);
                    }
                }
                restartAs(where, staged);
                transfer.finished(Transfer.State.DONE, "Restarting as the IKE Dashboard " + release.version());
            } catch (CancellationException e) {
                transfer.finished(Transfer.State.CANCELLED, "Cancelled. The part already downloaded is kept, so the next try continues from there.");
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
        if (othersUnderWay(0)) {
            view.setMessage("Moving the dashboard restarts it. Move it once the downloads have finished.");
            return;
        }
        view.setUpdatingDashboard(true);
        view.setMessage("Moving the IKE Dashboard into " + where.target().getParent() + " …");
        files.submit(() -> {
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

    /**
     * Whether anything besides the caller's own transfers is under way: transfers waiting or running, or a
     * store being changed.
     *
     * @param own how many of the pending transfers are the caller's
     */
    private boolean othersUnderWay(int own) {
        return pendingTransfers.get() > own || !busy.isEmpty();
    }

    /** Starts the helper that swaps a staged dashboard in once this one has quit and opens it, then quits. */
    private void restartAs(SelfUpdate.Placement where, Path staged) throws IOException {
        SelfUpdate.swap(where, staged, ProcessHandle.current().pid(), SelfUpdate.relaunch(where.target()),
                settings.logFolder().resolve("self-update.log"));
        Platform.runLater(Platform::exit);
    }

    /**
     * The git state of the repositories in a store: what the last check found, or, for a folder the check does not
     * cover (any but a knowledge base's newest), read now from the repositories' own files.
     */
    private List<RepositorySync.Status> syncOf(Path store) {
        List<RepositorySync.Status> checked = view.sync(store);
        if (!checked.isEmpty() || git.isEmpty()) {
            return checked;
        }
        try {
            return RepositorySync.find(store).stream().map(repository -> RepositorySync.local(git.get(), repository)).toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /**
     * Moves Komet versions to the Trash (KEC, 2026-11-08). Knowledge bases are not touched, and the Trash can put a
     * Komet back.
     */
    private void trashKomets(List<KometInstallation> removing) {
        List<Path> bundles = removing.stream().map(KometInstallation::bundle).toList();
        if (!claim("move to the " + Finder.trashName(), bundles)) {
            return;
        }
        String what = removing.size() == 1 ? "Komet " + removing.getFirst().version() : removing.size() + " Komets";
        view.setMessage("Moving " + what + " to the " + Finder.trashName() + " …");
        files.submit(() -> {
            try {
                Finder.trash(bundles);
                Platform.runLater(() -> {
                    refresh();
                    view.setMessage("Moved " + what + " to the " + Finder.trashName() + ". You can restore "
                            + (removing.size() == 1 ? "it" : "them") + " from there if you need to.");
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    view.setMessage("Could not move " + what + " to the " + Finder.trashName() + ": " + e.getMessage());
                    refresh();
                });
            } finally {
                release(bundles);
            }
        });
    }

    private void trash(List<KnowledgeBase> copies) {
        List<String> open = copies.stream().filter(copy -> StoreLock.openInKomet(copy.path())).map(StoreLock::refusal).toList();
        if (!open.isEmpty()) {
            view.showRefusals("Move to " + Finder.trashName(), open);
            return;
        }
        List<Path> paths = copies.stream().map(KnowledgeBase::path).toList();
        if (!claim("move to the " + Finder.trashName(), paths)) {
            return;
        }
        view.setMessage("Moving " + copies.size() + (copies.size() == 1 ? " copy" : " copies") + " to the " + Finder.trashName() + " …");
        files.submit(() -> {
            try {
                Finder.trash(paths);
                release(paths);
                Platform.runLater(this::refresh);
            } catch (Exception e) {
                Platform.runLater(() -> view.setMessage(e.getMessage()));
            } finally {
                release(paths);
            }
        });
    }

    private void open(KometInstallation komet, KnowledgeBase knowledgeBase) {
        HeapSizing.Plan plan = heapPlan(komet, knowledgeBase);
        Optional<HeapSizing.Need> need = HeapView.confirm(view.getScene().getWindow(), knowledgeBase, komet, plan);
        if (need.isEmpty()) {
            view.setMessage("Did not open " + Names.of(knowledgeBase.name()));
            return;
        }
        HeapSizing.Plan launched = need.get().equals(plan.need()) ? plan : plan.with(need.get());
        try {
            Path log = KometLauncher.launch(komet, knowledgeBase, launched.environment(), settings.logFolder());
            history.opened(KnowledgeBaseFamily.familyName(knowledgeBase));
            view.setLastOpened(history::lastOpened);
            view.setLastLog(log);
            view.setMessage("Opening " + Names.of(knowledgeBase.name()) + " in Komet " + komet.version() + " with "
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
                HeapSizing.override(heapStores, knowledgeBase), komet,
                HeapSizing.Machine.current(komets));
    }

    /** The Memory window for a store; what the person changes is kept in the preferences. */
    private void editHeap(KnowledgeBase knowledgeBase) {
        HeapSizing.Rule rule = HeapSizing.Rule.read(heapPreferences);
        Preferences stores = heapStores;
        long data = dataBytes.getOrDefault(knowledgeBase.path(), knowledgeBase.sizeBytes());
        HeapView.edit(view.getScene().getWindow(), knowledgeBase, rule, HeapSizing.override(stores, knowledgeBase),
                HeapSizing.needed(rule, knowledgeBase, data))
                .ifPresent(edit -> {
            edit.rule().write(heapPreferences);
            view.browser().setHeapRule(edit.rule());
            HeapSizing.setOverride(stores, knowledgeBase, edit.override());
            view.heapChanged();
            view.setMessage(edit.override().map(need -> Names.of(knowledgeBase.name()) + " opens with " + need.text())
                    .orElse(Names.of(knowledgeBase.name()) + " opens with the memory the dashboard works out for it"));
        });
    }

    /** Shows what creating a knowledge base would do; Nexus is read to pin its layers, and nothing is written. */
    private void draftKnowledgeBase(KnowledgeBaseProject project) {
        NewKnowledgeBaseForm form = view.newKnowledgeBase();
        runMaven("kb-draft-" + project.name(), (creation, line) -> creation.draft(project, line),
                () -> form.working("Drafting " + project.name() + ": finding each layer's exact version on Nexus …"),
                form::progress, outcome -> form.drafted(project, outcome), form::failed);
    }

    /**
     * Creates the knowledge base the draft showed: its project, its git repository with one commit, and
     * a validation that every layer resolves. The Projects tab then shows it.
     */
    private void createKnowledgeBase(KnowledgeBaseProject project) {
        NewKnowledgeBaseForm form = view.newKnowledgeBase();
        runMaven("kb-new-" + project.name(), (creation, line) -> creation.create(project, line),
                () -> form.working("Creating " + project.name() + " in " + project.folder() + " …"),
                form::progress, outcome -> {
                    form.created(project, outcome);
                    if (outcome.succeeded()) {
                        view.setMessage("Created " + Names.of(project.name()) + " in " + project.folder());
                        showProjectAfterRefresh = project.folder();
                        refresh();                           // its name is now one in use
                    }
                }, form::failed);
    }

    /** Runs a phase of a project's own build, {@code verify} or {@code deploy}, shown on the Projects tab. */
    private void buildProject(KnowledgeBaseProjectFolder project, String phase) {
        ProjectsPane projects = view.projects();
        Path folder = project.folder();
        boolean deploy = "deploy".equals(phase);
        runMaven("kb-" + phase + "-" + project.artifactId(), (creation, line) -> creation.build(folder, phase, line),
                () -> projects.working(folder, (deploy ? "Deploying " : "Building ") + project.artifactId() + " …"),
                projects::progress, outcome -> {
                    projects.built(folder, phase, outcome);
                    view.setMessage((outcome.succeeded() ? (deploy ? "Deployed " : "Built ") : "Could not " + (deploy ? "deploy " : "build "))
                            + Names.of(project.artifactId()));
                    refresh();                               // when it was last built, and what is uncommitted
                }, reason -> projects.failed(folder, reason));
    }

    /**
     * Runs Maven for a knowledge-base project, off the JavaFX thread: each line is shown as it is
     * written, everything is kept in a log, and whoever asked is told how it ended. One runs at a time.
     */
    private void runMaven(String logName, MavenStep step, Runnable started, java.util.function.Consumer<String> progress,
                          java.util.function.Consumer<KnowledgeBaseCreation.Outcome> ended, java.util.function.Consumer<String> failed) {
        KnowledgeBaseCreation.Prerequisites prerequisites = KnowledgeBaseCreation.prerequisites();
        view.newKnowledgeBase().setPrerequisites(prerequisites.missing());
        if (!prerequisites.missing().isEmpty()) {
            failed.accept(String.join(" ", prerequisites.missing()));
            return;
        }
        java.util.concurrent.Future<?> running = mavenRun;
        if (running != null && !running.isDone()) {
            failed.accept("Maven is already running for another knowledge base. Try again once it has finished.");
            return;
        }
        started.run();
        mavenRun = maven.submit(() -> {
            try {
                KnowledgeBaseCreation.Outcome outcome = step.run(new KnowledgeBaseCreation(prerequisites),
                        line -> Platform.runLater(() -> progress.accept(line)));
                Path log = settings.logFolder().resolve(logName + "-"
                        + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".log");
                Files.createDirectories(log.getParent());
                Files.write(log, outcome.lines());
                Platform.runLater(() -> {
                    view.setLastLog(log);
                    ended.accept(outcome);
                });
            } catch (InterruptedException e) {
                Platform.runLater(() -> failed.accept("Cancelled."));
            } catch (Exception e) {
                Platform.runLater(() -> failed.accept(e.getMessage() == null ? e.toString() : e.getMessage()));
            }
        });
    }

    /** One run of Maven for a knowledge-base project. */
    @FunctionalInterface
    private interface MavenStep {
        KnowledgeBaseCreation.Outcome run(KnowledgeBaseCreation creation, java.util.function.Consumer<String> line) throws Exception;
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
