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
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.prefs.Preferences;

/**
 * The IKE Dashboard: one window, in its own process, that lists the knowledge bases in
 * {@code ~/Solor} and those Nexus has, asks Nexus whether newer builds exist, and opens any of them
 * in an installed Komet as an independent process. Its design is the {@code dev-ike-dashboard} topic
 * in {@code ike-lab-documents}.
 */
public final class DashboardApp extends Application {

    private static final String SORT_RECENT = "sortRecent";

    private final ExecutorService work = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ike-dashboard-work");
        thread.setDaemon(true);
        return thread;
    });
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
        view.setOnReveal(path -> run(() -> Finder.reveal(path), "Could not show " + path));
        view.setOnOpenFile(path -> run(() -> Finder.open(path), "Could not open " + path));
        stage.setTitle("IKE Dashboard");
        stage.setScene(new Scene(view, 1080, 640));
        stage.show();
        refresh();
    }

    @Override
    public void stop() {
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
                families = KnowledgeBaseFamily.group(knowledgeBases);
                List<KnowledgeBaseFamily> found = families;
                Platform.runLater(() -> {
                    view.setKomets(komets);
                    view.setFamilies(found);
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
            checkNexus(families);
        });
    }

    /** Runs on the work thread: the newer-build check for each knowledge base. */
    private void checkNexus(List<KnowledgeBaseFamily> families) {
        int newer = 0;
        String problem = null;
        try {
            HttpClient http = RepositoryHttpClients.newClient();
            RepositoryAccess access = DashboardCommand.access(settings);
            NewerBuildCheck check = new NewerBuildCheck(http, access);
            for (KnowledgeBaseFamily family : families) {
                BuildStatus status = family.latestIdentified().map(check::check)
                        .orElse(BuildStatus.of(BuildStatus.State.UNIDENTIFIED, "no copy says which build it is"));
                if (status.state() == BuildStatus.State.NEWER) {
                    newer++;
                }
                Platform.runLater(() -> view.setStatus(family.name(), status));
            }
        } catch (Exception e) {
            problem = "could not check Nexus: " + e.getMessage();
        }
        String summary = families.size() + " knowledge bases"
                + (newer == 0 ? " · all current with Nexus" : " · " + newer + " with a newer build")
                + (problem == null ? "" : " · " + problem);
        Platform.runLater(() -> view.setMessage(summary));
    }

    /** Asks Nexus which knowledge bases it offers, for the Nexus window. */
    private void loadCatalog() {
        work.submit(() -> {
            try {
                List<NexusCatalog.Offer> offers = NexusCatalog.list(RepositoryHttpClients.newClient(),
                        DashboardCommand.access(settings));
                Platform.runLater(() -> view.setOffers(offers));
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
