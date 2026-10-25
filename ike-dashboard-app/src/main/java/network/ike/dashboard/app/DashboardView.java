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

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * The dashboard's window content, master and detail, about what is downloaded and keeping it current.
 * The list on the left names each managed knowledge base in {@code ~/Solor} once, beside its icon,
 * marked only when something needs attention: a newer build on Nexus, or no installed Komet that opens
 * it; above it, when Nexus has newer builds, a line to download them all. A store is managed when its
 * knowledge-base descriptor meets the minimum (level 1, complete, its icon there); the rest fold into
 * a Not managed section at the foot of the list, offering only Show in Finder and Move to Trash. The pane on the right is the selected one: its facts,
 * one Open, a newer build to download, and the older copies and change sets folded into sections, with
 * the space they take. What is not yet here is found in a window of its own, the {@link NexusBrowser}.
 * It holds no IO of its own; {@link DashboardApp} supplies the data and the actions.
 *
 * <p>Above the list, Notices is the landing page and the first screen (the IKE Dashboard design,
 * decision 14): one card per finding of the dashboard's checks, work at risk first, each with one
 * action and a Dismiss. Above the footer, each download or install under way, or waiting its turn, has a
 * row as Komet shows its own: the step, a bar, how far it has come and about how long is left, how long
 * it has run, and Cancel.
 */
public final class DashboardView extends BorderPane {

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());
    private static final String MUTED = "-fx-text-fill: #6b6b6b;";
    private static final String ATTENTION = "-fx-text-fill: #b35900;";
    private static final String PROBLEM = "-fx-text-fill: #c62828;";
    private static final int TABLE_ROWS = 8;

    private final ObservableList<KnowledgeBaseFamily> listings = FXCollections.observableArrayList();
    private final FilteredList<KnowledgeBaseFamily> shown = new FilteredList<>(listings);
    private final ListView<KnowledgeBaseFamily> list = new ListView<>(shown);
    private final TextField search = new TextField();
    private final ToggleButton byName = new ToggleButton("Name");
    private final ToggleButton byRecent = new ToggleButton("Recent");
    private final Button refresh = new Button("Refresh");
    private final Label updates = new Label();
    private final Button updateAll = new Button("Download all");
    private final HBox updateBar = new HBox(8);
    private final Button fromNexus = new Button("Get from Nexus…");
    private final NexusBrowser browser = new NexusBrowser();
    private Stage browserWindow;
    private final VBox detail = new VBox(10);
    private final Label message = new Label();
    private final MenuButton kometMenu = new MenuButton();
    private final Hyperlink showLog = new Hyperlink("Show last log");

    private List<KnowledgeBaseFamily> families = List.of();
    private NexusCatalog.Catalog catalog = new NexusCatalog.Catalog(List.of(), java.util.Map.of());
    private final ObservableList<KnowledgeBase> unmanaged = FXCollections.observableArrayList();
    private final ListView<KnowledgeBase> unmanagedList = new ListView<>(unmanaged);
    private final TitledPane unmanagedPane = new TitledPane("", unmanagedList);
    private List<KometInstallation> komets = List.of();
    private final Map<String, BuildStatus> statuses = new HashMap<>();
    private final Map<Path, Image> icons = new HashMap<>();
    private Function<String, Optional<Instant>> lastOpened = name -> Optional.empty();
    private Path lastLog;

    private BiConsumer<KometInstallation, KnowledgeBase> onOpen = (komet, knowledgeBase) -> { };
    private BiFunction<KometInstallation, KnowledgeBase, Optional<HeapSizing.Plan>> heapPlanner =
            (komet, knowledgeBase) -> Optional.empty();
    private Consumer<KnowledgeBase> onEditHeap = knowledgeBase -> { };
    private BiConsumer<Coordinate, String> onDownload = (coordinate, classifier) -> { };
    private Consumer<List<KnowledgeBase>> onTrash = copies -> { };
    private Consumer<Path> onReveal = path -> { };
    private Consumer<Path> onOpenFile = path -> { };
    private Consumer<Boolean> onSortChange = recent -> { };
    private Runnable onBrowse = () -> { };
    private Consumer<KometRelease> onInstallKomet = release -> { };
    private Optional<KometRelease> kometRelease = Optional.empty();
    private boolean installing;
    private final Map<Path, ChangeSetCheck.Finding> changeSets = new HashMap<>();
    private final Set<Path> upgrading = new java.util.HashSet<>();
    private BiConsumer<KometInstallation, KnowledgeBase> onUpgradeChangeSets = (komet, knowledgeBase) -> { };
    private final Button home = new Button("Notices");
    private boolean showingNotices = true;
    private List<Notice> notices = List.of();
    private final Map<Path, List<RepositorySync.Status>> sync = new HashMap<>();
    private Optional<Notice.Freshness> nexusChecked = Optional.empty();
    private boolean catalogLoaded;
    private Predicate<String> dismissed = id -> false;
    private Consumer<String> onDismiss = id -> { };
    private final VBox transfers = new VBox(6);
    private final Map<Transfer, TransferRow> transferRows = new LinkedHashMap<>();
    private final Map<String, Transfer> transferring = new HashMap<>();
    private final Timeline ticker = new Timeline(new KeyFrame(javafx.util.Duration.seconds(1),
            event -> transferRows.forEach((transfer, row) -> row.update(transfer.snapshot()))));

    /** Builds the view, empty until data is set. */
    public DashboardView() {
        search.setPromptText("Search");
        HBox.setHgrow(search, Priority.ALWAYS);
        ToggleGroup sort = new ToggleGroup();
        byName.setToggleGroup(sort);
        byRecent.setToggleGroup(sort);
        byName.getStyleClass().add("left-pill");
        byRecent.getStyleClass().add("right-pill");
        byName.setSelected(true);
        sort.selectedToggleProperty().addListener((observable, was, now) -> {
            if (now == null) {
                was.setSelected(true);
                return;
            }
            rebuild();
            onSortChange.accept(now == byRecent);
        });
        for (Button button : List.of(refresh, updateAll, fromNexus)) {
            button.setMinWidth(Region.USE_PREF_SIZE);
        }
        byName.setMinWidth(Region.USE_PREF_SIZE);
        byRecent.setMinWidth(Region.USE_PREF_SIZE);
        HBox sortControl = new HBox(byName, byRecent);
        sortControl.setMinWidth(Region.USE_PREF_SIZE);
        HBox listBar = new HBox(6, search, sortControl, refresh);
        listBar.setAlignment(Pos.CENTER_LEFT);
        listBar.setPadding(new Insets(8));

        updates.setStyle(ATTENTION);
        updates.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(updates, Priority.ALWAYS);
        updateBar.getChildren().setAll(updates, updateAll);
        updateBar.setAlignment(Pos.CENTER_LEFT);
        updateBar.setPadding(new Insets(6, 8, 6, 10));
        updateBar.setStyle("-fx-background-color: #fff3e0;");
        updateBar.managedProperty().bind(updateBar.visibleProperty());
        updateBar.setVisible(false);
        updateAll.setOnAction(event -> newerBuilds().forEach(family -> download(family)));
        home.setMaxWidth(Double.MAX_VALUE);
        home.setAlignment(Pos.CENTER_LEFT);
        home.setOnAction(event -> showNotices());
        HBox.setHgrow(home, Priority.ALWAYS);
        HBox homeBar = new HBox(home);
        homeBar.setPadding(new Insets(8, 8, 0, 8));
        VBox masterTop = new VBox(homeBar, listBar, updateBar);

        fromNexus.setOnAction(event -> openBrowser());
        browser.setOnDownload((coordinate, classifier) -> onDownload.accept(coordinate, classifier));
        HBox masterBottom = new HBox(fromNexus);
        masterBottom.setPadding(new Insets(8));

        list.setCellFactory(view -> new ListingCell());
        list.setPlaceholder(new Label("No managed knowledge bases downloaded"));
        list.getSelectionModel().selectedItemProperty().addListener((observable, was, now) -> {
            if (now != null) {
                showingNotices = false;
            }
            showDetail();
        });
        search.textProperty().addListener((observable, was, now) -> {
            String needle = now == null ? "" : now.strip().toLowerCase(Locale.ROOT);
            shown.setPredicate(family -> needle.isEmpty() || family.name().toLowerCase(Locale.ROOT).contains(needle)
                    || family.displayName().toLowerCase(Locale.ROOT).contains(needle));
        });
        unmanagedList.setCellFactory(view -> new UnmanagedCell());
        unmanagedList.setPrefHeight(150);
        unmanagedList.getSelectionModel().selectedItemProperty().addListener((observable, was, now) -> {
            if (now != null) {
                showingNotices = false;
                list.getSelectionModel().clearSelection();
            }
            showDetail();
        });
        list.getSelectionModel().selectedItemProperty().addListener((observable, was, now) -> {
            if (now != null) {
                unmanagedList.getSelectionModel().clearSelection();
            }
        });
        unmanagedPane.setText("Not managed (0)");
        unmanagedPane.setExpanded(false);
        unmanagedPane.setAnimated(false);
        unmanagedPane.managedProperty().bind(unmanagedPane.visibleProperty());
        unmanagedPane.setVisible(false);
        BorderPane master = new BorderPane(list);
        master.setTop(masterTop);
        master.setBottom(new VBox(unmanagedPane, masterBottom));
        master.setMinWidth(260);

        detail.setPadding(new Insets(16, 20, 20, 20));
        ScrollPane detailScroll = new ScrollPane(detail);
        detailScroll.setFitToWidth(true);
        detailScroll.setStyle("-fx-background-color: transparent; -fx-background-insets: 0;");

        SplitPane split = new SplitPane(master, detailScroll);
        split.setOrientation(Orientation.HORIZONTAL);
        split.setDividerPositions(0.32);
        SplitPane.setResizableWithParent(master, false);
        setCenter(split);

        message.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(message, Priority.ALWAYS);
        showLog.setDisable(true);
        showLog.setOnAction(event -> {
            if (lastLog != null) {
                onOpenFile.accept(lastLog);
            }
        });
        HBox footer = new HBox(10, message, kometMenu, showLog);
        footer.setAlignment(Pos.CENTER_LEFT);
        footer.setPadding(new Insets(4, 8, 6, 8));
        transfers.setPadding(new Insets(6, 8, 0, 8));
        transfers.managedProperty().bind(transfers.visibleProperty());
        transfers.setVisible(false);
        ticker.setCycleCount(Animation.INDEFINITE);
        setBottom(new VBox(transfers, footer));
        setKomets(List.of());
        showDetail();
    }

    /**
     * Shows the knowledge bases in {@code ~/Solor}.
     *
     * @param families the knowledge bases
     */
    public void setFamilies(List<KnowledgeBaseFamily> families) {
        this.families = List.copyOf(families);
        // A store may have been replaced by one with another icon at the same path.
        icons.clear();
        statuses.keySet().retainAll(families.stream().map(KnowledgeBaseFamily::name).collect(Collectors.toSet()));
        rebuild();
        browser.setCatalog(catalog, here());
    }

    /**
     * Shows, folded at the foot of the list, what is in {@code ~/Solor} but not managed: stores
     * without a knowledge-base descriptor that meets the minimum, and change-set zips. Each can only
     * be shown in the Finder or moved to the Trash.
     *
     * @param entries the entries
     */
    public void setUnmanaged(List<KnowledgeBase> entries) {
        String keep = Optional.ofNullable(unmanagedList.getSelectionModel().getSelectedItem()).map(KnowledgeBase::name).orElse(null);
        unmanaged.setAll(entries);
        unmanagedPane.setText("Not managed (" + entries.size() + ")");
        refreshNotices();
        unmanagedPane.setVisible(!entries.isEmpty());
        unmanaged.stream().filter(entry -> entry.name().equals(keep)).findFirst()
                .ifPresent(entry -> unmanagedList.getSelectionModel().select(entry));
    }

    /**
     * Opens the Not managed section and selects an entry in it, showing its detail.
     *
     * @param name the entry's folder or file name
     */
    public void selectUnmanaged(String name) {
        unmanaged.stream().filter(entry -> entry.name().equals(name)).findFirst().ifPresent(entry -> {
            unmanagedPane.setExpanded(true);
            unmanagedList.getSelectionModel().select(entry);
        });
    }

    /**
     * What is listed as not managed, by name.
     *
     * @return the names
     */
    public List<String> unmanagedListed() {
        return unmanaged.stream().map(KnowledgeBase::name).toList();
    }

    /**
     * Gives the Nexus window what Nexus offers of the knowledge bases it describes.
     *
     * @param catalog the described knowledge bases and their stores
     */
    public void setCatalog(NexusCatalog.Catalog catalog) {
        this.catalog = catalog;
        catalogLoaded = true;
        browser.setCatalog(catalog, here());
        refreshNotices();
    }

    /**
     * Tells the Nexus window why it has nothing to show.
     *
     * @param text the reason
     */
    public void setOffersProblem(String text) {
        browser.setProblem(text);
    }

    /**
     * The Nexus window's content.
     *
     * @return the browser
     */
    public NexusBrowser browser() {
        return browser;
    }

    /**
     * Shows the Komet versions installed.
     *
     * @param installations the Komet versions, newest first
     */
    public void setKomets(List<KometInstallation> installations) {
        komets = List.copyOf(installations);
        kometChanged();
    }

    /**
     * Offers the newest Komet build there is, where a store needs it and in the footer when it is
     * newer than any installed.
     *
     * @param release the newest build, if one was found
     */
    public void setKometRelease(Optional<KometRelease> release) {
        kometRelease = release;
        kometChanged();
    }

    /**
     * Marks a Komet install as running, so it is not started twice.
     *
     * @param running whether an install is running
     */
    public void setInstalling(boolean running) {
        installing = running;
        kometChanged();
    }

    /**
     * What installing a Komet build does.
     *
     * @param action the action
     */
    public void setOnInstallKomet(Consumer<KometRelease> action) {
        onInstallKomet = action;
    }

    private Optional<KometRelease> newerKomet() {
        return kometRelease.filter(release -> komets.isEmpty() || release.newerThan(komets.getFirst().version()));
    }

    private void kometChanged() {
        kometMenu.getItems().clear();
        Optional<KometRelease> newer = newerKomet();
        String installed = komets.isEmpty() ? "No Komet installed" : "Komet " + komets.getFirst().version()
                + (komets.size() > 1 ? " and " + (komets.size() - 1) + " older" : "");
        kometMenu.setText(newer.map(release -> installed + "  ·  " + release.version() + " available").orElse(installed));
        kometMenu.setStyle(newer.isPresent() ? ATTENTION : "");
        newer.ifPresent(release -> {
            MenuItem install = new MenuItem("Install Komet " + release.version() + " from " + release.source().label());
            install.setDisable(installing);
            install.setOnAction(event -> onInstallKomet.accept(release));
            kometMenu.getItems().add(install);
        });
        komets.forEach(komet -> {
            MenuItem item = new MenuItem("Komet " + komet.version() + "  —  " + komet.name());
            item.setOnAction(event -> onReveal.accept(komet.bundle()));
            kometMenu.getItems().add(item);
        });
        kometMenu.setDisable(kometMenu.getItems().isEmpty());
        list.refresh();
        refreshNotices();
        showDetail();
    }

    /**
     * Records what the newer-build check found for a knowledge base.
     *
     * @param name   the knowledge base's name
     * @param status what the check found
     */
    public void setStatus(String name, BuildStatus status) {
        statuses.put(name, status);
        list.refresh();
        updateBanner();
        refreshNotices();
        selected().filter(family -> family.name().equals(name)).ifPresent(family -> showDetail());
    }

    /**
     * What the check last found for a knowledge base.
     *
     * @param name the knowledge base's name
     * @return the status
     */
    public BuildStatus status(String name) {
        return statuses.getOrDefault(name, BuildStatus.UNCHECKED);
    }

    /**
     * Records which of a store's change sets need an upgrade (IKE-Network/ike-issues#1313).
     *
     * @param store   the store's folder
     * @param finding what its change-set folder holds
     */
    public void setChangeSets(Path store, ChangeSetCheck.Finding finding) {
        changeSets.put(store, finding);
        refreshNotices();
        selected().filter(family -> family.latest().map(KnowledgeBase::path).filter(store::equals).isPresent())
                .ifPresent(family -> showDetail());
    }

    /**
     * What the check last found in a store's change-set folder.
     *
     * @param store the store's folder
     * @return the finding; {@link ChangeSetCheck.Finding#NONE} before a check
     */
    public ChangeSetCheck.Finding changeSets(Path store) {
        return changeSets.getOrDefault(store, ChangeSetCheck.Finding.NONE);
    }

    /**
     * Marks a store's change-set upgrade as running, so it is not started twice.
     *
     * @param store   the store's folder
     * @param running whether its upgrade is running
     */
    public void setUpgrading(Path store, boolean running) {
        if (running) {
            upgrading.add(store);
        } else {
            upgrading.remove(store);
        }
        showDetail();
    }

    /**
     * What Upgrade change sets does, once confirmed, given the Komet and the store.
     *
     * @param action the action
     */
    public void setOnUpgradeChangeSets(BiConsumer<KometInstallation, KnowledgeBase> action) {
        onUpgradeChangeSets = action;
    }

    /**
     * Shows what an upgrade run reported, in a window of its own.
     *
     * @param knowledgeBase the store
     * @param run           what the run did
     */
    public void showUpgradeReport(KnowledgeBase knowledgeBase, KometLauncher.UpgradeRun run) {
        Stage window = new Stage();
        window.setTitle("Change-set upgrade — " + knowledgeBase.displayName());
        if (getScene() != null) {
            window.initOwner(getScene().getWindow());
        }
        window.setScene(new Scene(upgradeReport(run), 820, 560));
        window.show();
    }

    /**
     * The report of an upgrade run: its outcome, then the report's text, selectable.
     *
     * @param run what the run did
     * @return the pane
     */
    static BorderPane upgradeReport(KometLauncher.UpgradeRun run) {
        Label outcome = new Label(switch (run.exitStatus()) {
            case 0 -> "Every change set is upgraded or current. The originals replaced are in ~/ike-archive.";
            case 1 -> "Some change sets are left as they were; the report names what their knowledge base does not hold.";
            default -> "Komet did not upgrade: status " + run.exitStatus() + ".";
        });
        outcome.setWrapText(true);
        outcome.setStyle(run.clean() ? "-fx-text-fill: #2e7d32;" : run.exitStatus() == 1 ? ATTENTION : PROBLEM);
        TextArea text = new TextArea(run.text());
        text.setEditable(false);
        text.setWrapText(true);
        text.setStyle("-fx-font-family: 'Menlo', monospace;");
        Label log = muted("Log  " + run.log());
        BorderPane pane = new BorderPane(text);
        pane.setTop(outcome);
        pane.setBottom(log);
        BorderPane.setMargin(outcome, new Insets(12, 12, 8, 12));
        BorderPane.setMargin(text, new Insets(0, 12, 0, 12));
        BorderPane.setMargin(log, new Insets(8, 12, 12, 12));
        return pane;
    }

    /**
     * Shows the landing page: the notices, in place of a knowledge base's detail.
     */
    public void showNotices() {
        showingNotices = true;
        list.getSelectionModel().clearSelection();
        unmanagedList.getSelectionModel().clearSelection();
        showDetail();
    }

    /**
     * Whether the landing page is shown.
     *
     * @return {@code true} when the notices are
     */
    public boolean showingNotices() {
        return showingNotices;
    }

    /**
     * The notices the landing page shows, most urgent first.
     *
     * @return the notices
     */
    public List<Notice> notices() {
        return notices;
    }

    /**
     * Records the sync state of the git repositories inside a store.
     *
     * @param store    the store's folder
     * @param statuses its repositories' states; none when it holds no repository
     */
    public void setSync(Path store, List<RepositorySync.Status> statuses) {
        sync.put(store, List.copyOf(statuses));
        refreshNotices();
    }

    /**
     * Records when Nexus was last asked, and whether that worked.
     *
     * @param freshness when, and whether it was current
     */
    public void setNexusChecked(Notice.Freshness freshness) {
        nexusChecked = Optional.of(freshness);
        refreshNotices();
    }

    /**
     * Which notices the person dismissed.
     *
     * @param dismissed whether a notice's id was dismissed
     */
    public void setDismissed(Predicate<String> dismissed) {
        this.dismissed = dismissed;
        refreshNotices();
    }

    /**
     * What dismissing a notice does, given its id; the notices are recomputed after.
     *
     * @param action the action
     */
    public void setOnDismiss(Consumer<String> action) {
        onDismiss = action;
    }

    private void refreshNotices() {
        notices = Notices.compute(new Notices.Inputs(families, List.copyOf(unmanaged), statuses, nexusChecked, changeSets, komets, kometRelease,
                catalogLoaded ? Optional.of(catalog) : Optional.empty(), sync, Instant.now()), dismissed);
        long atRisk = notices.stream().filter(notice -> notice.urgency() == Notice.Urgency.AT_RISK).count();
        home.setText(notices.isEmpty() ? "Notices" : "Notices  ·  " + notices.size()
                + (atRisk == 0 ? "" : "  (" + atRisk + " at risk)"));
        home.setStyle(atRisk > 0 ? PROBLEM : notices.isEmpty() ? "" : ATTENTION);
        if (showingNotices && unmanagedList.getSelectionModel().getSelectedItem() == null) {
            showDetail();
        }
    }

    private void showNoticesPane() {
        Label title = new Label("Notices");
        title.setStyle("-fx-font-size: 20px; -fx-font-weight: bold;");
        detail.getChildren().add(title);
        if (notices.isEmpty()) {
            detail.getChildren().add(muted(families.isEmpty()
                    ? "No knowledge bases downloaded. Get one from Nexus to begin." : "Nothing needs your attention."));
        }
        notices.forEach(notice -> detail.getChildren().add(noticeCard(notice)));
        detail.getChildren().add(muted(nexusChecked
                .map(checked -> checked.current() ? "Nexus checked " + WHEN.format(checked.checkedAt())
                        : "Nexus could not be reached; what it showed as of " + WHEN.format(checked.checkedAt()))
                .orElse("Nexus not checked yet")));
    }

    private Node noticeCard(Notice notice) {
        Label heading = new Label(notice.title());
        heading.setStyle("-fx-font-weight: bold;");
        heading.setWrapText(true);
        VBox text = new VBox(2, heading);
        if (!notice.detail().isBlank()) {
            text.getChildren().add(muted(notice.detail()));
        }
        notice.freshness().ifPresent(freshness -> {
            Label checked = muted(freshness.current() ? "checked " + WHEN.format(freshness.checkedAt())
                    : "as of " + WHEN.format(freshness.checkedAt()) + "; the last check failed");
            checked.setStyle("-fx-font-size: 11px;" + MUTED);
            text.getChildren().add(checked);
        });
        HBox.setHgrow(text, Priority.ALWAYS);
        text.setMaxWidth(Double.MAX_VALUE);
        HBox row = new HBox(10, text);
        row.setAlignment(Pos.CENTER_LEFT);
        notice.action().ifPresent(action -> {
            boolean running = action instanceof NoticeAction.Download(Coordinate coordinate, String classifier)
                    && transferring(downloadKey(coordinate, classifier));
            Button button = new Button(running ? "Downloading …" : action.label());
            button.setDisable(running);
            button.setMinWidth(Region.USE_PREF_SIZE);
            button.setOnAction(event -> act(action));
            row.getChildren().add(button);
        });
        Hyperlink dismiss = new Hyperlink("Dismiss");
        dismiss.setMinWidth(Region.USE_PREF_SIZE);
        dismiss.setOnAction(event -> {
            onDismiss.accept(notice.id());
            refreshNotices();
        });
        row.getChildren().add(dismiss);
        row.setPadding(new Insets(8, 10, 8, 10));
        row.setStyle("-fx-background-radius: 6; -fx-background-color: " + switch (notice.urgency()) {
            case AT_RISK -> "#fdecea;";
            case ACTION -> "#fff3e0;";
            case NEWS -> "#eef3f8;";
        });
        return row;
    }

    private void act(NoticeAction action) {
        switch (action) {
            case NoticeAction.Download(Coordinate coordinate, String classifier) -> onDownload.accept(coordinate, classifier);
            case NoticeAction.UpgradeChangeSets(KnowledgeBase store) -> LauncherChoice.chooseForUpgrade(komets, store)
                    .ifPresentOrElse(komet -> confirmUpgrade(komet, store, changeSets(store.path())),
                            () -> setMessage("Upgrading change sets needs Komet " + LauncherChoice.UPGRADE_CHANGE_SETS_SINCE
                                    + " or later"));
            case NoticeAction.InstallKomet(KometRelease release) -> onInstallKomet.accept(release);
            case NoticeAction.Show(String family) -> select(family);
            case NoticeAction.Browse() -> openBrowser();
        }
    }

    /**
     * The key a download is known by while it runs, so it is not started twice.
     *
     * @param coordinate the knowledge base
     * @param classifier the store's classifier
     * @return the key
     */
    public static String downloadKey(Coordinate coordinate, String classifier) {
        return coordinate + ":" + classifier;
    }

    /**
     * The name a person reads for a knowledge base: its descriptor's display name, from a copy here or
     * from Nexus, else its artifactId.
     *
     * @param coordinate the knowledge base
     * @return the name
     */
    public String nameOf(Coordinate coordinate) {
        return families.stream()
                .filter(family -> family.members().stream().anyMatch(member -> member.coordinate()
                        .map(named -> named.artifactId().equals(coordinate.artifactId())).orElse(false)))
                .map(KnowledgeBaseFamily::displayName).findFirst()
                .or(() -> catalog.described().entrySet().stream()
                        .filter(entry -> entry.getKey().artifactId().equals(coordinate.artifactId()))
                        .map(entry -> entry.getValue().descriptor().displayName()).findFirst())
                .orElse(coordinate.artifactId());
    }

    /**
     * Shows a download or install, as a row above the footer, until it ends: a finished one goes away, and
     * one that failed or was cancelled stays, saying why, until the person closes it.
     *
     * @param transfer the transfer; its changes may come on any thread
     * @param key      what it downloads, if it is a store download, so its buttons wait for it
     */
    public void showTransfer(Transfer transfer, Optional<String> key) {
        TransferRow row = new TransferRow(transfer);
        transferRows.put(transfer, row);
        key.ifPresent(name -> transferring.put(name, transfer));
        transfers.getChildren().add(row);
        transfers.setVisible(true);
        ticker.play();
        transfer.onChange(snapshot -> {
            if (Platform.isFxApplicationThread()) {
                transferChanged(transfer, snapshot);
            } else {
                Platform.runLater(() -> transferChanged(transfer, snapshot));
            }
        });
        refreshAfterTransfer();
    }

    /**
     * Whether a store download is under way or waiting.
     *
     * @param key the download's {@link #downloadKey}
     * @return {@code true} while it is
     */
    public boolean transferring(String key) {
        Transfer transfer = transferring.get(key);
        return transfer != null && !transfer.snapshot().state().ended();
    }

    /**
     * The transfers shown, in order.
     *
     * @return what each shows now
     */
    public List<Transfer.Snapshot> transfersShown() {
        return transferRows.keySet().stream().map(Transfer::snapshot).toList();
    }

    private void transferChanged(Transfer transfer, Transfer.Snapshot snapshot) {
        TransferRow row = transferRows.get(transfer);
        if (row == null) {
            return;
        }
        if (snapshot.state() == Transfer.State.DONE) {
            removeTransfer(transfer);
            return;
        }
        row.update(snapshot);
        if (snapshot.state().ended()) {
            transferring.values().removeIf(transfer::equals);
            refreshAfterTransfer();
        }
    }

    private void removeTransfer(Transfer transfer) {
        TransferRow row = transferRows.remove(transfer);
        if (row != null) {
            transfers.getChildren().remove(row);
        }
        transferring.values().removeIf(transfer::equals);
        if (transferRows.isEmpty()) {
            transfers.setVisible(false);
            ticker.stop();
        }
        refreshAfterTransfer();
    }

    /** The Download buttons wait for a download under way, so the notices and the detail are drawn again. */
    private void refreshAfterTransfer() {
        refreshNotices();
        if (!showingNotices) {
            showDetail();
        }
    }

    /**
     * One transfer's row, laid out as Komet's progress row: what it is and how long it has run, the bar,
     * then the step, how far it has come and about how long is left.
     */
    private final class TransferRow extends VBox {
        private final Label title = new Label();
        private final Label clock = new Label();
        private final ProgressBar bar = new ProgressBar(0);
        private final Label status = new Label();
        private final Button cancel = new Button("Cancel");
        private final Hyperlink close = new Hyperlink("Close");

        TransferRow(Transfer transfer) {
            super(4);
            title.setStyle("-fx-font-weight: bold;");
            clock.setStyle("-fx-font-size: 11px;" + MUTED);
            status.setStyle(MUTED);
            status.setWrapText(true);
            bar.setMaxWidth(Double.MAX_VALUE);
            bar.setStyle("-fx-accent: #2e7d32;");
            cancel.setOnAction(event -> transfer.cancel());
            close.setOnAction(event -> removeTransfer(transfer));
            close.setVisible(false);
            close.managedProperty().bind(close.visibleProperty());
            cancel.managedProperty().bind(cancel.visibleProperty());
            Region gap = new Region();
            HBox.setHgrow(gap, Priority.ALWAYS);
            HBox heading = new HBox(10, title, gap, clock, cancel, close);
            heading.setAlignment(Pos.CENTER_LEFT);
            getChildren().addAll(heading, bar, status);
            setPadding(new Insets(8, 10, 8, 10));
            setStyle("-fx-background-color: #f4f6f8; -fx-background-radius: 6;");
        }

        void update(Transfer.Snapshot snapshot) {
            title.setText(snapshot.title());
            clock.setText(snapshot.state() == Transfer.State.WAITING ? "" : snapshot.elapsedText());
            boolean ended = snapshot.state().ended();
            bar.setVisible(!ended);
            bar.setManaged(!ended);
            bar.setProgress(switch (snapshot.state()) {
                case WAITING -> 0;
                case RUNNING, CANCELLING -> snapshot.fraction() > 0 ? snapshot.fraction() : ProgressBar.INDETERMINATE_PROGRESS;
                case CANCELLED, FAILED, DONE -> 1;
            });
            cancel.setVisible(!ended);
            cancel.setDisable(snapshot.state() == Transfer.State.CANCELLING);
            close.setVisible(ended);
            if (ended) {
                status.setText(snapshot.outcome());
                status.setStyle(snapshot.state() == Transfer.State.FAILED ? PROBLEM : MUTED);
                return;
            }
            List<String> parts = new ArrayList<>();
            parts.add(snapshot.phase() + (snapshot.note().isBlank() ? "" : " (" + snapshot.note() + ")"));
            if (snapshot.state() == Transfer.State.RUNNING && snapshot.fraction() > 0) {
                parts.add(snapshot.percent());
            }
            if (!snapshot.remainingText().isEmpty()) {
                parts.add(snapshot.remainingText());
            }
            status.setText(String.join("  ·  ", parts));
            status.setStyle(MUTED);
        }
    }

    /**
     * Where the Recent order learns when each knowledge base was last opened.
     *
     * @param lastOpened the time a name was last opened
     */
    public void setLastOpened(Function<String, Optional<Instant>> lastOpened) {
        this.lastOpened = lastOpened;
        if (byRecent.isSelected()) {
            rebuild();
        }
    }

    /**
     * Orders the list by most recently opened, or by name.
     *
     * @param recent whether the knowledge bases opened most recently come first
     */
    public void setSortRecent(boolean recent) {
        (recent ? byRecent : byName).setSelected(true);
    }

    /**
     * The names in the list, in order.
     *
     * @return the names
     */
    public List<String> listed() {
        return shown.stream().map(KnowledgeBaseFamily::name).toList();
    }

    /**
     * Selects a knowledge base by name.
     *
     * @param name the knowledge base's name
     */
    public void select(String name) {
        shown.stream().filter(family -> family.name().equals(name)).findFirst()
                .ifPresent(family -> list.getSelectionModel().select(family));
    }

    /**
     * The selected knowledge base.
     *
     * @return its copies, if one is selected
     */
    public Optional<KnowledgeBaseFamily> selected() {
        return Optional.ofNullable(list.getSelectionModel().getSelectedItem());
    }

    /**
     * Shows a line at the foot of the window.
     *
     * @param text the line
     */
    public void setMessage(String text) {
        message.setText(text);
    }

    /**
     * The log of the Komet last started, which Show last log opens.
     *
     * @param log the log file
     */
    public void setLastLog(Path log) {
        lastLog = log;
        showLog.setDisable(log == null);
    }

    /**
     * What Refresh does.
     *
     * @param action the action
     */
    public void setOnRefresh(Runnable action) {
        refresh.setOnAction(event -> action.run());
    }

    /**
     * What opening does, given the Komet and the copy.
     *
     * @param action the action
     */
    public void setOnOpen(BiConsumer<KometInstallation, KnowledgeBase> action) {
        onOpen = action;
    }

    /**
     * Where a store's memory line comes from: what it is launched with in a Komet, on this machine now.
     *
     * @param planner the plan of a store in a Komet, or empty when it cannot be worked out
     */
    public void setHeapPlanner(BiFunction<KometInstallation, KnowledgeBase, Optional<HeapSizing.Plan>> planner) {
        heapPlanner = planner;
        if (!showingNotices) {
            showDetail();
        }
    }

    /**
     * What Memory… does for a store: the window that overrides the rule or the store's heap.
     *
     * @param action given the store
     */
    public void setOnEditHeap(Consumer<KnowledgeBase> action) {
        onEditHeap = action;
    }

    /** Draws the detail again, after the rule or a store's heap changed. */
    public void heapChanged() {
        if (!showingNotices) {
            showDetail();
        }
    }

    /**
     * What Download does, given the artifact and the classifier of the store.
     *
     * @param action the action
     */
    public void setOnDownload(BiConsumer<Coordinate, String> action) {
        onDownload = action;
    }

    /**
     * What moving copies to the Trash does, once the person has confirmed it.
     *
     * @param action the action
     */
    public void setOnTrash(Consumer<List<KnowledgeBase>> action) {
        onTrash = action;
    }

    /**
     * What showing a file in the Finder does.
     *
     * @param action the action
     */
    public void setOnReveal(Consumer<Path> action) {
        onReveal = action;
    }

    /**
     * What opening a file, such as a log, does.
     *
     * @param action the action
     */
    public void setOnOpenFile(Consumer<Path> action) {
        onOpenFile = action;
    }

    /**
     * What a change of order does, so it can be remembered.
     *
     * @param action given whether Recent is chosen
     */
    public void setOnSortChange(Consumer<Boolean> action) {
        onSortChange = action;
    }

    /**
     * What Get from Nexus does before its window shows, such as asking Nexus again.
     *
     * @param action the action
     */
    public void setOnBrowse(Runnable action) {
        onBrowse = action;
    }

    private Set<String> here() {
        return families.stream().map(family -> family.name().toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
    }

    private void openBrowser() {
        if (browserWindow == null) {
            browserWindow = new Stage();
            browserWindow.setTitle("Knowledge bases on Nexus");
            browserWindow.setScene(new Scene(browser, 720, 460));
            if (getScene() != null) {
                browserWindow.initOwner(getScene().getWindow());
            }
        }
        onBrowse.run();
        browserWindow.show();
        browserWindow.toFront();
    }

    private List<KnowledgeBaseFamily> newerBuilds() {
        return families.stream().filter(family -> status(family.name()).state() == BuildStatus.State.NEWER).toList();
    }

    private void updateBanner() {
        int newer = newerBuilds().size();
        updateBar.setVisible(newer > 0);
        updates.setText(newer == 1 ? "1 newer build on Nexus" : newer + " newer builds on Nexus");
    }

    private void download(KnowledgeBaseFamily family) {
        family.latestIdentified().ifPresent(identified -> identified.coordinate()
                .filter(coordinate -> !transferring(downloadKey(coordinate, identified.kind().classifier())))
                .ifPresent(coordinate -> onDownload.accept(coordinate, identified.kind().classifier())));
    }

    private void rebuild() {
        String keep = selected().map(KnowledgeBaseFamily::name).orElse(null);
        Comparator<KnowledgeBaseFamily> byNameOrder = Comparator.comparing(family -> family.name().toLowerCase(Locale.ROOT));
        Comparator<KnowledgeBaseFamily> order = byRecent.isSelected()
                ? Comparator.comparing((KnowledgeBaseFamily family) -> lastOpened.apply(family.name()).orElse(Instant.EPOCH))
                        .reversed().thenComparing(byNameOrder)
                : byNameOrder;
        listings.setAll(families.stream().sorted(order).toList());
        updateBanner();
        refreshNotices();
        if (showingNotices && keep == null) {
            return;
        }
        Optional<KnowledgeBaseFamily> again = shown.stream().filter(family -> family.name().equals(keep)).findFirst()
                .or(() -> shown.stream().findFirst());
        again.ifPresentOrElse(family -> list.getSelectionModel().select(family), this::showDetail);
    }

    private Optional<KometInstallation> launcher(KnowledgeBase copy) {
        return LauncherChoice.choose(komets, copy);
    }

    private void showDetail() {
        detail.getChildren().clear();
        KnowledgeBase loose = unmanagedList.getSelectionModel().getSelectedItem();
        if (loose != null) {
            showUnmanaged(loose);
            return;
        }
        if (showingNotices) {
            showNoticesPane();
            return;
        }
        Optional<KnowledgeBaseFamily> chosen = selected();
        if (chosen.isEmpty()) {
            Label none = new Label(families.isEmpty()
                    ? "No knowledge bases downloaded. Get one from Nexus to begin." : "Choose a knowledge base");
            none.setStyle(MUTED);
            detail.getChildren().add(none);
            return;
        }
        Label title = new Label(chosen.get().displayName());
        title.setStyle("-fx-font-size: 20px; -fx-font-weight: bold;");
        HBox heading = new HBox(12, icon(chosen.get(), 48), title);
        heading.setAlignment(Pos.CENTER_LEFT);
        detail.getChildren().add(heading);
        showFamily(chosen.get());
    }

    private void showUnmanaged(KnowledgeBase entry) {
        Label title = new Label(entry.name());
        title.setStyle("-fx-font-size: 20px; -fx-font-weight: bold;");
        detail.getChildren().add(title);
        detail.getChildren().add(muted(entry.kind().label() + "  ·  " + DashboardCommand.size(entry.sizeBytes())
                + "  ·  " + WHEN.format(entry.recency())));
        entry.coordinate().ifPresent(coordinate -> detail.getChildren().add(muted(coordinate.toString())));
        Label why = new Label("Not managed: " + String.join("; ", entry.descriptor().problems()) + ".");
        why.setStyle(ATTENTION);
        why.setWrapText(true);
        detail.getChildren().add(why);
        detail.getChildren().add(muted("The dashboard manages a store that carries a complete knowledge-base descriptor"
                + " (level 1) and its icon, as builds by ike-maven-plugin 268 or later do. Download a newer build to"
                + " manage this knowledge base; this copy can be shown in the Finder or moved to the Trash."));
        Button reveal = new Button("Show in Finder");
        reveal.setOnAction(event -> onReveal.accept(entry.path()));
        Button trash = new Button("Move to Trash…");
        trash.setOnAction(event -> confirmTrash(List.of(entry)));
        HBox actions = new HBox(8, reveal, trash);
        actions.setPadding(new Insets(6, 0, 0, 0));
        detail.getChildren().add(actions);
    }

    private void showFamily(KnowledgeBaseFamily family) {
        Optional<KnowledgeBase> latest = family.latest();
        List<KnowledgeBase> changeSets = family.members().stream().filter(member -> member.kind() == StoreKind.CHANGE_SETS).toList();
        if (latest.isEmpty()) {
            detail.getChildren().add(muted("Change sets only. Komet loads change sets into a store; it does not open them."));
            detail.getChildren().add(section("Change sets", changeSets, true, false));
            return;
        }
        KnowledgeBase copy = latest.get();
        detail.getChildren().add(muted(copy.kind().label() + "  ·  " + DashboardCommand.size(copy.sizeBytes())
                + "  ·  " + WHEN.format(copy.recency())));
        copy.coordinate().ifPresent(coordinate -> {
            BuildStatus status = status(family.name());
            Label build = muted(coordinate.artifactId() + " " + copy.resolvedVersion().orElse(coordinate.version())
                    + nexusNote(status));
            if (status.state() == BuildStatus.State.CURRENT) {
                build.setStyle("-fx-text-fill: #2e7d32;");
            }
            build.setTooltip(new Tooltip(coordinate.toString() + (status.detail().isEmpty() ? "" : "\n" + status.detail())));
            detail.getChildren().add(build);
        });

        BuildStatus status = status(family.name());
        if (status.state() == BuildStatus.State.NEWER && status.newest().isPresent()) {
            Label newer = new Label("A newer build is on Nexus, deployed " + WHEN.format(status.newest().get().updated()));
            KnowledgeBase identified = family.latestIdentified().orElse(copy);
            boolean running = identified.coordinate()
                    .map(coordinate -> transferring(downloadKey(coordinate, identified.kind().classifier()))).orElse(false);
            Button download = new Button(running ? "Downloading …" : "Download");
            download.setDisable(running);
            download.setOnAction(event -> identified.coordinate()
                    .ifPresent(coordinate -> onDownload.accept(coordinate, identified.kind().classifier())));
            Region gap = new Region();
            HBox.setHgrow(gap, Priority.ALWAYS);
            HBox box = new HBox(10, newer, gap, download);
            box.setAlignment(Pos.CENTER_LEFT);
            box.setPadding(new Insets(8, 10, 8, 10));
            box.setStyle("-fx-background-color: #fff3e0; -fx-background-radius: 6;");
            detail.getChildren().add(box);
        }

        changeSetBox(copy).ifPresent(detail.getChildren()::add);

        Optional<KometInstallation> komet = launcher(copy);
        Button open = new Button("Open");
        open.setDefaultButton(true);
        open.setStyle("-fx-font-size: 14px; -fx-padding: 6 28 6 28;");
        open.setDisable(komet.isEmpty());
        open.setOnAction(event -> komet.ifPresent(chosen -> onOpen.accept(chosen, copy)));
        HBox actions = new HBox(8, open, moreMenu(copy));
        actions.setAlignment(Pos.CENTER_LEFT);
        actions.setPadding(new Insets(6, 0, 0, 0));
        detail.getChildren().add(actions);
        if (komet.isEmpty()) {
            Optional<KometRelease> fix = kometRelease.filter(release -> LauncherChoice.reads(release.version(), copy));
            Label need = new Label(LauncherChoice.requirement(copy).map(requirement -> "Needs " + requirement
                    + (fix.isPresent() ? "." : "; none installed reads it, and no newer build was found."))
                    .orElse(fix.isPresent() ? "No Komet installed." : "No Komet installed, and none was found to install."));
            need.setStyle(PROBLEM);
            detail.getChildren().add(need);
            fix.ifPresent(release -> {
                Button install = new Button(installing ? "Installing Komet " + release.version() + " …"
                        : "Install Komet " + release.version());
                install.setDisable(installing);
                install.setOnAction(event -> onInstallKomet.accept(release));
                Label from = muted("from " + release.source().label()
                        + (release.sizeBytes() > 0 ? ", " + DashboardCommand.size(release.sizeBytes()) : "")
                        + "; installed beside the Komets you have");
                HBox row = new HBox(10, install, from);
                row.setAlignment(Pos.CENTER_LEFT);
                detail.getChildren().add(row);
            });
        } else if (komet.get() != komets.getFirst()) {
            detail.getChildren().add(muted("Opens in Komet " + komet.get().version()));
        }
        komet.flatMap(chosen -> heapPlanner.apply(chosen, copy))
                .ifPresent(plan -> detail.getChildren().add(HeapView.line(plan, komet.get())));
        Label folder = muted("Folder  " + copy.path().getFileName());
        folder.setTooltip(new Tooltip(copy.path().toString()));
        detail.getChildren().add(folder);

        List<KnowledgeBase> older = family.members().stream()
                .filter(member -> member != copy && member.kind().opensInKomet()).toList();
        if (!older.isEmpty()) {
            detail.getChildren().add(section("Older copies", older, true, true));
        }
        if (!changeSets.isEmpty()) {
            detail.getChildren().add(section("Change sets", changeSets, false, false));
        }
    }

    /** The offer to upgrade a store's change sets, when some need it. */
    private Optional<Node> changeSetBox(KnowledgeBase copy) {
        ChangeSetCheck.Finding finding = changeSets(copy.path());
        if (finding.needed() == 0) {
            return Optional.empty();
        }
        Label text = new Label(finding.text() + ".");
        text.setWrapText(true);
        Optional<KometInstallation> komet = LauncherChoice.chooseForUpgrade(komets, copy);
        boolean running = upgrading.contains(copy.path());
        Button upgrade = new Button(running ? "Upgrading change sets …" : "Upgrade change sets");
        upgrade.setDisable(running || komet.isEmpty());
        upgrade.setOnAction(event -> komet.ifPresent(chosen -> confirmUpgrade(chosen, copy, finding)));
        upgrade.setMinWidth(Region.USE_PREF_SIZE);
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox row = new HBox(10, text, gap, upgrade);
        row.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(text, Priority.ALWAYS);
        VBox box = new VBox(4, row);
        if (komet.isEmpty()) {
            Label need = muted("Needs Komet " + LauncherChoice.UPGRADE_CHANGE_SETS_SINCE + " or later, which upgrades"
                    + " change sets with no window.");
            box.getChildren().add(need);
        }
        box.setPadding(new Insets(8, 10, 8, 10));
        box.setStyle("-fx-background-color: #fff3e0; -fx-background-radius: 6;");
        return Optional.of(box);
    }

    private void confirmUpgrade(KometInstallation komet, KnowledgeBase copy, ChangeSetCheck.Finding finding) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Upgrade change sets");
        alert.setHeaderText("Upgrade " + finding.needed() + " change set" + (finding.needed() == 1 ? "" : "s") + " of "
                + copy.displayName() + "?");
        alert.setContentText("Komet " + komet.version() + " opens " + copy.name() + " with no window, rewrites each"
                + " change set in format 3 with the pattern of every component it refers to, as the store holds it,"
                + " and replaces the originals, which it first archives in ~/ike-archive. A change set that refers to"
                + " a component the store does not hold is left as it was. Quit any Komet that has this store open"
                + " first.");
        alert.getButtonTypes().setAll(ButtonType.CANCEL, ButtonType.OK);
        if (getScene() != null) {
            alert.initOwner(getScene().getWindow());
        }
        alert.showAndWait().filter(ButtonType.OK::equals).ifPresent(ok -> onUpgradeChangeSets.accept(komet, copy));
    }

    private static String nexusNote(BuildStatus status) {
        return switch (status.state()) {
            case CURRENT -> "  ·  current with Nexus";
            case NEWER -> "  ·  newer build on Nexus";
            case NOT_ON_NEXUS, UNKNOWN, ERROR -> "  ·  " + status.state().label();
            case UNCHECKED, UNIDENTIFIED -> "";
        };
    }

    private Node section(String name, List<KnowledgeBase> copies, boolean expanded, boolean trashAll) {
        long bytes = copies.stream().mapToLong(KnowledgeBase::sizeBytes).sum();
        String heading = name + " (" + copies.size() + ")  ·  " + DashboardCommand.size(bytes);
        TableView<KnowledgeBase> table = copiesTable(copies);
        TitledPane pane = new TitledPane(heading, table);
        pane.setExpanded(expanded);
        pane.setAnimated(false);
        if (!trashAll) {
            return pane;
        }
        Button trash = new Button("Move to Trash…");
        trash.setOnAction(event -> confirmTrash(copies));
        HBox bar = new HBox(trash);
        bar.setAlignment(Pos.CENTER_RIGHT);
        return new VBox(4, pane, bar);
    }

    private TableView<KnowledgeBase> copiesTable(List<KnowledgeBase> copies) {
        TableView<KnowledgeBase> table = new TableView<>(FXCollections.observableArrayList(copies));
        table.getColumns().addAll(List.of(
                column("Folder", 280, KnowledgeBase::name),
                column("Kind", 140, copy -> copy.kind().label()),
                column("Size", 80, copy -> DashboardCommand.size(copy.sizeBytes())),
                column("Date", 130, copy -> WHEN.format(copy.recency()))));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        table.setFixedCellSize(24);
        table.setPrefHeight(28 + 24 * Math.min(copies.size(), TABLE_ROWS) + 4);
        table.setRowFactory(view -> {
            TableRow<KnowledgeBase> row = new TableRow<>();
            row.itemProperty().addListener((observable, was, copy) -> row.setContextMenu(copy == null ? null : copyMenu(copy)));
            row.setOnMouseClicked(event -> {
                if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2 && row.getItem() != null) {
                    launcher(row.getItem()).ifPresent(komet -> onOpen.accept(komet, row.getItem()));
                }
            });
            return row;
        });
        return table;
    }

    private static TableColumn<KnowledgeBase, String> column(String title, double width, Function<KnowledgeBase, String> value) {
        TableColumn<KnowledgeBase, String> column = new TableColumn<>(title);
        column.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(value.apply(cell.getValue())));
        column.setPrefWidth(width);
        return column;
    }

    private MenuButton moreMenu(KnowledgeBase copy) {
        MenuButton more = new MenuButton("⋯");
        more.getItems().addAll(menuItems(copy, false));
        return more;
    }

    private ContextMenu copyMenu(KnowledgeBase copy) {
        return new ContextMenu(menuItems(copy, true).toArray(MenuItem[]::new));
    }

    private List<MenuItem> menuItems(KnowledgeBase copy, boolean withOpenAndTrash) {
        List<MenuItem> items = new ArrayList<>();
        if (copy.kind().opensInKomet()) {
            if (withOpenAndTrash) {
                MenuItem open = new MenuItem("Open");
                Optional<KometInstallation> komet = launcher(copy);
                open.setDisable(komet.isEmpty());
                open.setOnAction(event -> komet.ifPresent(chosen -> onOpen.accept(chosen, copy)));
                items.add(open);
            }
            Menu openWith = new Menu("Open with");
            komets.stream().filter(komet -> LauncherChoice.reads(komet, copy)).forEach(komet -> {
                MenuItem item = new MenuItem("Komet " + komet.version());
                item.setOnAction(event -> onOpen.accept(komet, copy));
                openWith.getItems().add(item);
            });
            openWith.setDisable(openWith.getItems().isEmpty());
            items.add(openWith);
            MenuItem memory = new MenuItem("Memory…");
            memory.setOnAction(event -> onEditHeap.accept(copy));
            items.add(memory);
        }
        MenuItem reveal = new MenuItem("Show in Finder");
        reveal.setOnAction(event -> onReveal.accept(copy.path()));
        MenuItem copyPath = new MenuItem("Copy path");
        copyPath.setOnAction(event -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(copy.path().toString());
            Clipboard.getSystemClipboard().setContent(content);
        });
        items.add(reveal);
        items.add(copyPath);
        if (withOpenAndTrash) {
            MenuItem trash = new MenuItem("Move to Trash…");
            trash.setOnAction(event -> confirmTrash(List.of(copy)));
            items.add(trash);
        }
        return items;
    }

    private void confirmTrash(List<KnowledgeBase> copies) {
        long bytes = copies.stream().mapToLong(KnowledgeBase::sizeBytes).sum();
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Move to Trash");
        alert.setHeaderText("Move " + (copies.size() == 1 ? copies.getFirst().name() : copies.size() + " copies")
                + " (" + DashboardCommand.size(bytes) + ") to the Trash?");
        alert.setContentText((copies.size() == 1 ? "" : copies.stream().map(KnowledgeBase::name)
                .collect(Collectors.joining("\n", "", "\n\n")))
                + "A copy you edited may hold work no build has. Quit any Komet that has one open first. "
                + "The space comes back when the Trash is emptied.");
        alert.getButtonTypes().setAll(ButtonType.CANCEL, ButtonType.OK);
        if (getScene() != null) {
            alert.initOwner(getScene().getWindow());
        }
        alert.showAndWait().filter(ButtonType.OK::equals).ifPresent(ok -> onTrash.accept(copies));
    }

    /** A knowledge base's icon, read once per refresh. */
    private Node icon(KnowledgeBaseFamily family, double size) {
        return KnowledgeBaseIcon.of(family.icon().map(file -> icons.computeIfAbsent(file, KnowledgeBaseIcon::load)), size);
    }

    private static Label muted(String text) {
        Label label = new Label(text);
        label.setStyle(MUTED);
        label.setWrapText(true);
        return label;
    }

    /** A line of the list: the icon, the name, and a mark only when something needs attention. */
    private final class ListingCell extends ListCell<KnowledgeBaseFamily> {
        @Override
        protected void updateItem(KnowledgeBaseFamily family, boolean empty) {
            super.updateItem(family, empty);
            setText(null);
            if (empty || family == null) {
                setGraphic(null);
                return;
            }
            Label name = new Label(family.displayName());
            Label mark = new Label();
            mark.setStyle("-fx-font-size: 11px;");
            Optional<KnowledgeBase> latest = family.latest();
            if (status(family.name()).state() == BuildStatus.State.NEWER) {
                mark.setText("▲ newer build");
                mark.setStyle("-fx-font-size: 11px;" + ATTENTION);
            } else if (latest.isEmpty()) {
                mark.setText("change sets");
                mark.setStyle("-fx-font-size: 11px;" + MUTED);
                name.setStyle(MUTED);
            } else if (launcher(latest.get()).isEmpty()) {
                mark.setText("needs newer Komet");
                mark.setStyle("-fx-font-size: 11px;" + PROBLEM);
            }
            Region gap = new Region();
            HBox.setHgrow(gap, Priority.ALWAYS);
            HBox line = new HBox(6, icon(family, 20), name, gap, mark);
            line.setAlignment(Pos.CENTER_LEFT);
            line.setMinWidth(0);
            line.prefWidthProperty().bind(list.widthProperty().subtract(36));
            setGraphic(line);
        }
    }

    /** A line of the Not managed section: the folder or file name, what it is, and only Finder and Trash. */
    private final class UnmanagedCell extends ListCell<KnowledgeBase> {
        @Override
        protected void updateItem(KnowledgeBase entry, boolean empty) {
            super.updateItem(entry, empty);
            setText(null);
            if (empty || entry == null) {
                setGraphic(null);
                setContextMenu(null);
                return;
            }
            Label name = new Label(entry.name());
            name.setStyle(MUTED);
            Label kind = new Label(entry.kind().label());
            kind.setStyle("-fx-font-size: 11px;" + MUTED);
            Region gap = new Region();
            HBox.setHgrow(gap, Priority.ALWAYS);
            HBox line = new HBox(6, name, gap, kind);
            line.setAlignment(Pos.CENTER_LEFT);
            line.setMinWidth(0);
            line.prefWidthProperty().bind(unmanagedList.widthProperty().subtract(36));
            setGraphic(line);
            MenuItem reveal = new MenuItem("Show in Finder");
            reveal.setOnAction(event -> onReveal.accept(entry.path()));
            MenuItem trash = new MenuItem("Move to Trash…");
            trash.setOnAction(event -> confirmTrash(List.of(entry)));
            setContextMenu(new ContextMenu(reveal, trash));
        }
    }
}
