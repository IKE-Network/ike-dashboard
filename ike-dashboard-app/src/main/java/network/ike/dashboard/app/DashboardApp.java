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

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The IKE Dashboard: one window, in its own process, that lists the knowledge bases in
 * {@code ~/Solor}, asks Nexus whether newer builds exist, and opens any of them in any installed
 * Komet as an independent process. Its design is the {@code dev-ike-dashboard} topic in
 * {@code ike-lab-documents}.
 */
public final class DashboardApp extends Application {

    private final ExecutorService work = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ike-dashboard-work");
        thread.setDaemon(true);
        return thread;
    });
    private DashboardSettings settings;
    private DashboardView view;

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
        view = new DashboardView();
        view.setOnRefresh(this::refresh);
        view.setOnCheckNexus(this::checkNexus);
        view.setOnOpen(this::open);
        stage.setTitle("IKE Dashboard");
        stage.setScene(new Scene(view, 1180, 660));
        stage.show();
        refresh();
    }

    @Override
    public void stop() {
        view.dispose();
        work.shutdownNow();
    }

    private void refresh() {
        view.setMessage("Reading " + settings.solor() + " …");
        work.submit(() -> {
            try {
                List<KnowledgeBase> knowledgeBases = KnowledgeBaseScanner.scan(settings.solor());
                List<KometInstallation> komets = KometInstallations.find(KometInstallations.defaultRoots(
                        Path.of(System.getProperty("user.home")), settings.installLocation()));
                long identified = knowledgeBases.stream().filter(KnowledgeBase::identified).count();
                Platform.runLater(() -> {
                    view.setKnowledgeBases(knowledgeBases);
                    view.setKomets(komets);
                    view.setMessage(knowledgeBases.size() + " entries in " + settings.solor() + ", " + identified
                            + " of them say which build they are; " + komets.size() + " Komet versions installed");
                });
            } catch (Exception e) {
                Platform.runLater(() -> view.setMessage("Could not read " + settings.solor() + ": " + e.getMessage()));
            }
        });
    }

    private void checkNexus() {
        List<DashboardView.Row> rows = view.rows().stream()
                .filter(row -> row.knowledgeBase().kind().opensInKomet()).toList();
        view.setMessage("Asking " + settings.repositoryUrl() + " about " + rows.size() + " stores …");
        work.submit(() -> {
            try {
                NewerBuildCheck check = new NewerBuildCheck(RepositoryHttpClients.newClient(),
                        DashboardCommand.access(settings));
                int newer = 0;
                for (DashboardView.Row row : rows) {
                    BuildStatus status = check.check(row.knowledgeBase());
                    if (status.state() == BuildStatus.State.NEWER) {
                        newer++;
                    }
                    Platform.runLater(() -> row.statusProperty().set(status));
                }
                int found = newer;
                Platform.runLater(() -> view.setMessage(found == 0 ? "No newer builds found"
                        : found + (found == 1 ? " store has" : " stores have") + " a newer build on Nexus"));
            } catch (Exception e) {
                Platform.runLater(() -> view.setMessage("Could not check Nexus: " + e.getMessage()));
            }
        });
    }

    private void open(KometInstallation komet, KnowledgeBase knowledgeBase) {
        try {
            Path log = KometLauncher.launch(komet, knowledgeBase, settings.logFolder());
            view.setMessage("Opening " + knowledgeBase.name() + " in Komet " + komet.version() + "; its log is " + log);
        } catch (Exception e) {
            view.setMessage("Could not start Komet " + komet.version() + ": " + e.getMessage());
        }
    }
}
