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
import javafx.scene.control.ButtonBar;
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

    /** The section that lists a knowledge base's copies other than the one the detail shows. */
    static final String OTHER_COPIES = "Other copies";
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
    private final Button updateAll = new Button("Upgrade all");
    private final HBox updateBar = new HBox(8);
    private final Button fromNexus = new Button("Get from Nexus…");
    private final NexusBrowser browser = new NexusBrowser();
    private Stage browserWindow;
    private final VBox detail = new VBox(10);
    private final TextArea message = SelectableText.of("");
    private final MenuButton kometMenu = new MenuButton();
    private final Hyperlink showLog = new Hyperlink("Show last log");

    private List<KnowledgeBaseFamily> families = List.of();
    private Set<String> collections = Set.of();
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
    private Optional<SelfUpdate.State> dashboard = Optional.empty();
    private Consumer<KometRelease> onUpdateDashboard = release -> { };
    private Runnable onMoveDashboard = () -> { };
    private boolean updatingDashboard;
    private Consumer<NoticeAction.Upgrade> onUpgrade = action -> { };
    private Consumer<NoticeAction.Reinstall> onReinstall = action -> { };
    private java.util.function.BiConsumer<KnowledgeBase, String> onCopy = (store, name) -> { };
    /**
     * The window's two views, one segmented control (KEC, 2026-11-08): the knowledge bases, a list and the
     * chosen one's detail, and the notices, across the whole window. Notices is the landing page.
     */
    private final ToggleButton knowledgeBasesTab = new ToggleButton("Knowledge Bases");
    private final ToggleButton noticesTab = new ToggleButton("Notices");
    private final VBox noticesBox = new VBox(10);
    private final ScrollPane noticesView = new ScrollPane(noticesBox);
    private SplitPane knowledgeBasesView;
    private boolean showingNotices = true;
    /** The knowledge base last chosen before Notices was shown, which Knowledge Bases returns to. */
    private String lastChosen;
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
        getStylesheets().add(SelectableText.STYLESHEET);
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
        updateAll.setOnAction(event -> newerBuilds().forEach(this::upgradeOrCopy));
        ToggleGroup views = new ToggleGroup();
        knowledgeBasesTab.setToggleGroup(views);
        noticesTab.setToggleGroup(views);
        knowledgeBasesTab.getStyleClass().add("left-pill");
        noticesTab.getStyleClass().add("right-pill");
        knowledgeBasesTab.setMinWidth(Region.USE_PREF_SIZE);
        noticesTab.setMinWidth(Region.USE_PREF_SIZE);
        noticesTab.setSelected(true);
        views.selectedToggleProperty().addListener((observable, was, now) -> {
            if (now == null) {
                was.setSelected(true);                   // one view is always shown
                return;
            }
            if (now == noticesTab) {
                showNotices();
            } else {
                showKnowledgeBases();
            }
        });
        HBox viewBar = new HBox(knowledgeBasesTab, noticesTab);
        viewBar.setAlignment(Pos.CENTER);
        viewBar.setPadding(new Insets(8, 8, 6, 8));
        setTop(viewBar);
        VBox masterTop = new VBox(listBar, updateBar);

        fromNexus.setOnAction(event -> openBrowser());
        browser.setOnDownload((coordinate, classifier) -> onDownload.accept(coordinate, classifier));
        HBox masterBottom = new HBox(fromNexus);
        masterBottom.setPadding(new Insets(8));

        list.setCellFactory(view -> new ListingCell());
        list.setPlaceholder(new Label("No managed knowledge bases downloaded"));
        list.getSelectionModel().selectedItemProperty().addListener((observable, was, now) -> {
            if (now != null) {
                showingNotices = false;
                knowledgeBasesTab.setSelected(true);
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
                knowledgeBasesTab.setSelected(true);
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
        knowledgeBasesView = split;
        noticesBox.setPadding(new Insets(16, 24, 20, 24));
        noticesView.setFitToWidth(true);
        noticesView.setStyle("-fx-background-color: transparent; -fx-background-insets: 0;");
        setCenter(noticesView);

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
     * The collections whose knowledge bases are kept at hand, downloaded or not: listed first in the
     * Nexus window, and never folded into its count on the Notices screen.
     *
     * @param collections the collections, as the settings name them
     */
    public void setCollections(Set<String> collections) {
        this.collections = Set.copyOf(collections);
        browser.setCollections(this.collections);
        refreshNotices();
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

    /**
     * Where the dashboard runs and the newest build of it, for the notice that offers the update.
     *
     * @param state what the self-update check found; empty when the dashboard runs from a build
     */
    public void setDashboard(Optional<SelfUpdate.State> state) {
        dashboard = state;
        refreshNotices();
    }

    /**
     * Marks the dashboard's own update as running, so it is not started twice.
     *
     * @param running whether it is running
     */
    public void setUpdatingDashboard(boolean running) {
        updatingDashboard = running;
        refreshNotices();
    }

    /**
     * What updating the dashboard does.
     *
     * @param action the action
     */
    public void setOnUpdateDashboard(Consumer<KometRelease> action) {
        onUpdateDashboard = action;
    }

    /**
     * What moving a translocated dashboard into {@code ~/Applications} does.
     *
     * @param action the action
     */
    public void setOnMoveDashboard(Runnable action) {
        onMoveDashboard = action;
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
     * What Upgrade does, once confirmed.
     *
     * @param action the action
     */
    public void setOnUpgrade(Consumer<NoticeAction.Upgrade> action) {
        onUpgrade = action;
    }

    /**
     * What Reinstall does, once confirmed.
     *
     * @param action the action
     */
    public void setOnReinstall(Consumer<NoticeAction.Reinstall> action) {
        onReinstall = action;
    }

    /**
     * What Copy to a new folder does, once the person names the folder.
     *
     * @param action given the store and the new folder's name
     */
    public void setOnCopy(java.util.function.BiConsumer<KnowledgeBase, String> action) {
        onCopy = action;
    }

    /**
     * The sync state last recorded for a store's repositories.
     *
     * @param store the store's folder
     * @return its repositories' states; none before a check
     */
    public List<RepositorySync.Status> sync(Path store) {
        return sync.getOrDefault(store, List.of());
    }

    /**
     * The key an upgrade is known by while it runs, so it is not started twice.
     *
     * @param store the store's folder
     * @return the key
     */
    public static String upgradeKey(Path store) {
        return "upgrade:" + store;
    }

    /**
     * Says why an operation did not start, each refusal with what to do.
     *
     * @param title    the operation
     * @param refusals the refusals ({@link StoreUpgrade#refusals})
     */
    public void showRefusals(String title, List<String> refusals) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle(title);
        alert.setHeaderText(title + " did not start");
        alert.setContentText(String.join("\n\n", refusals));
        if (getScene() != null) {
            alert.initOwner(getScene().getWindow());
        }
        SelectableText.in(alert);
        alert.show();
    }

    /**
     * Asks what to do about the change sets an upgrade's check in Komet refused ({@code IKE-Network/ike-issues#1356}):
     * cancel the upgrade, leaving the store as it was, or set them aside and go on. Each is shown in words; Komet's
     * report is under Show Details. Runs on the JavaFX thread and waits for the answer.
     *
     * @param store      the store being upgraded
     * @param summaries  the refused change sets
     * @param whyNoAside why they cannot be set aside, or empty when they can
     * @param run        the check, for its report
     * @return {@code true} to set them aside and go on; {@code false} to cancel the upgrade
     */
    public boolean chooseForRefused(KnowledgeBase store, List<RefusedChangeSets.Summary> summaries,
                                    Optional<String> whyNoAside, KometLauncher.HeadlessRun run) {
        ButtonType cancel = new ButtonType("Cancel upgrade", ButtonBar.ButtonData.CANCEL_CLOSE);
        ButtonType aside = new ButtonType("Set aside and upgrade", ButtonBar.ButtonData.OK_DONE);
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle("Upgrade " + store.displayName());
        alert.setHeaderText(refusedHeader(summaries.size()));
        TextArea text = SelectableText.of(refusedText(summaries, whyNoAside), 600);
        ScrollPane content = new ScrollPane(text);
        content.setFitToWidth(true);
        content.setPrefViewportHeight(Math.min(360, 90 + 80 * summaries.size()));
        alert.getDialogPane().setContent(content);
        alert.getDialogPane().setExpandableContent(runReport(run));
        alert.getButtonTypes().setAll(whyNoAside.isPresent() ? List.of(cancel) : List.of(cancel, aside));
        if (getScene() != null) {
            alert.initOwner(getScene().getWindow());
        }
        SelectableText.in(alert);
        return alert.showAndWait().filter(aside::equals).isPresent();
    }

    /**
     * The heading of the choice about refused change sets.
     *
     * @param count how many were refused
     * @return the heading
     */
    static String refusedHeader(int count) {
        return (count == 1 ? "A change set does" : count + " change sets do") + " not load into the new build";
    }

    /**
     * The refused change sets in words, then the choice.
     *
     * @param summaries  the refused change sets
     * @param whyNoAside why they cannot be set aside, or empty when they can
     * @return the text
     */
    static String refusedText(List<RefusedChangeSets.Summary> summaries, Optional<String> whyNoAside) {
        StringBuilder out = new StringBuilder();
        for (RefusedChangeSets.Summary summary : summaries) {
            out.append(summary.fileName()).append('\n');
            List<String> what = new ArrayList<>();
            if (!summary.contents().isEmpty()) {
                what.add(summary.contents());
            }
            what.addAll(summary.names());
            if (!what.isEmpty()) {
                out.append("    ").append(String.join(" · ", what)).append('\n');
            }
            if (!summary.packager().isEmpty() || !summary.packaged().isEmpty()) {
                out.append("    Packaged").append(summary.packager().isEmpty() ? "" : " by " + summary.packager())
                        .append(summary.packaged().isEmpty() ? "" : " on " + when(summary.packaged())).append('\n');
            }
            out.append("    ").append(summary.reason()).append("\n\n");
        }
        out.append(whyNoAside.orElse("Set aside moves " + (summaries.size() == 1 ? "it" : "them") + " to "
                + RefusedChangeSets.UNPROCESSED_FOLDER + " in the store, which Komet does not read, and goes on with the"
                + " upgrade; " + (summaries.size() == 1 ? "it stays" : "they stay") + " with the store through later"
                + " upgrades. Cancel leaves your store as it was."));
        return out.toString();
    }

    /** A manifest's package date, {@code 2026-11-08T03:11:44.097814}, to the minute. */
    private static String when(String packaged) {
        String shown = packaged.replace('T', ' ');
        return shown.length() > 16 ? shown.substring(0, 16) : shown;
    }

    /**
     * Shows what a run of Komet with no window reported, in a window of its own.
     *
     * @param knowledgeBase the store
     * @param run           what the run did
     */
    public void showRunReport(KnowledgeBase knowledgeBase, KometLauncher.HeadlessRun run) {
        Stage window = new Stage();
        window.setTitle("Change-set load — " + knowledgeBase.displayName());
        if (getScene() != null) {
            window.initOwner(getScene().getWindow());
        }
        window.setScene(new Scene(runReport(run), 820, 560));
        window.show();
    }

    /**
     * The report of a run: its outcome, then the report's text, selectable.
     *
     * @param run what the run did
     * @return the pane
     */
    static BorderPane runReport(KometLauncher.HeadlessRun run) {
        Label outcome = new Label(switch (run.exitStatus()) {
            case 0 -> "Every change set is in the store.";
            case 1 -> "Some change sets were refused; the report names what the knowledge base does not contain.";
            default -> "Komet did not run the load: status " + run.exitStatus() + ".";
        });
        outcome.setWrapText(true);
        outcome.setStyle(run.clean() ? "-fx-text-fill: #2e7d32;" : run.exitStatus() == 1 ? ATTENTION : PROBLEM);
        TextArea text = new TextArea(run.text());
        text.setEditable(false);
        text.setWrapText(true);
        text.setStyle("-fx-font-family: 'Menlo', monospace;");
        TextArea log = muted("Log  " + run.log());
        BorderPane pane = new BorderPane(text);
        pane.setTop(outcome);
        pane.setBottom(log);
        BorderPane.setMargin(outcome, new Insets(12, 12, 8, 12));
        BorderPane.setMargin(text, new Insets(0, 12, 0, 12));
        BorderPane.setMargin(log, new Insets(8, 12, 12, 12));
        return pane;
    }

    /**
     * Shows the landing page: the notices, across the window. The knowledge base chosen stays chosen, so
     * Knowledge Bases returns to it.
     */
    public void showNotices() {
        // Nothing stays selected behind the notices, so a refresh re-selecting it cannot take the person away.
        lastChosen = selected().map(KnowledgeBaseFamily::name).orElse(lastChosen);
        showingNotices = true;
        noticesTab.setSelected(true);
        list.getSelectionModel().clearSelection();
        unmanagedList.getSelectionModel().clearSelection();
        showDetail();
    }

    /**
     * Shows the knowledge bases: the list, and the chosen one's detail; the first when none is chosen.
     */
    public void showKnowledgeBases() {
        showingNotices = false;
        knowledgeBasesTab.setSelected(true);
        if (list.getSelectionModel().isEmpty() && unmanagedList.getSelectionModel().isEmpty()) {
            String last = lastChosen;
            if (last != null) {
                list.getItems().stream().filter(family -> family.name().equals(last)).findFirst()
                        .ifPresent(family -> list.getSelectionModel().select(family));
            }
            if (list.getSelectionModel().isEmpty() && !list.getItems().isEmpty()) {
                list.getSelectionModel().selectFirst();
            }
        }
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
        notices = Notices.compute(new Notices.Inputs(families, List.copyOf(unmanaged), statuses, nexusChecked, komets, kometRelease,
                catalogLoaded ? Optional.of(catalog) : Optional.empty(), sync, collections, dashboard, Instant.now()),
                dismissed);
        long atRisk = notices.stream().filter(notice -> notice.urgency() == Notice.Urgency.AT_RISK).count();
        noticesTab.setText(notices.isEmpty() ? "Notices" : "Notices  ·  " + notices.size()
                + (atRisk == 0 ? "" : "  (" + atRisk + " at risk)"));
        noticesTab.setStyle(atRisk > 0 ? PROBLEM : notices.isEmpty() ? "" : ATTENTION);
        if (showingNotices) {
            showDetail();
        }
    }

    private void showNoticesPane() {
        VBox box = noticesBox;                           // the notices fill the window, not the detail pane
        box.getChildren().clear();
        TextArea title = SelectableText.of("Notices");
        title.setStyle("-fx-font-size: 20px; -fx-font-weight: bold;");
        box.getChildren().add(title);
        if (notices.isEmpty()) {
            box.getChildren().add(muted(families.isEmpty()
                    ? "No knowledge bases downloaded. Get one from Nexus to begin." : "Nothing needs your attention."));
        }
        notices.forEach(notice -> box.getChildren().add(noticeCard(notice)));
        box.getChildren().add(muted(nexusChecked
                .map(checked -> checked.current() ? "Nexus checked " + WHEN.format(checked.checkedAt())
                        : "Nexus could not be reached; what it showed as of " + WHEN.format(checked.checkedAt()))
                .orElse("Nexus not checked yet")));
    }

    private Node noticeCard(Notice notice) {
        TextArea heading = SelectableText.of(notice.title());
        heading.setStyle("-fx-font-weight: bold;");
        VBox text = new VBox(2, heading);
        if (!notice.detail().isBlank()) {
            text.getChildren().add(muted(notice.detail()));
        }
        notice.freshness().ifPresent(freshness -> {
            TextArea checked = muted(freshness.current() ? "checked " + WHEN.format(freshness.checkedAt())
                    : "as of " + WHEN.format(freshness.checkedAt()) + "; the last check failed");
            checked.setStyle("-fx-font-size: 11px;" + MUTED);
            text.getChildren().add(checked);
        });
        HBox.setHgrow(text, Priority.ALWAYS);
        text.setMaxWidth(Double.MAX_VALUE);
        HBox row = new HBox(10, text);
        row.setAlignment(Pos.CENTER_LEFT);
        notice.action().ifPresent(action -> {
            boolean updating = updatingDashboard
                    && (action instanceof NoticeAction.UpdateDashboard || action instanceof NoticeAction.MoveDashboard);
            boolean downloading = switch (action) {
                case NoticeAction.Download(Coordinate coordinate, String classifier) -> transferring(downloadKey(coordinate, classifier));
                case NoticeAction.InstallCopy(Coordinate coordinate, String classifier) -> transferring(downloadKey(coordinate, classifier));
                default -> false;
            };
            boolean upgrading = action instanceof NoticeAction.Upgrade(KnowledgeBase store, Coordinate c, String k)
                    && transferring(upgradeKey(store.path()));
            boolean running = updating || downloading || upgrading;
            Button button = new Button(updating ? "Updating …" : upgrading ? "Upgrading …" : downloading ? "Downloading …"
                    : action.label());
            button.setDisable(running);
            button.setMinWidth(Region.USE_PREF_SIZE);
            button.setOnAction(event -> act(action));
            row.getChildren().add(button);
        });
        // A notice about one knowledge base opens it, beside whatever it offers to do (KEC, 2026-11-08).
        boolean showsAlready = notice.action().filter(NoticeAction.Show.class::isInstance).isPresent();
        notice.family().filter(family -> !showsAlready).ifPresent(family -> {
            Hyperlink show = new Hyperlink("Show");
            show.setOnAction(event -> select(family));
            row.getChildren().add(show);
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
            case NoticeAction.InstallCopy(Coordinate coordinate, String classifier) -> onDownload.accept(coordinate, classifier);
            case NoticeAction.Upgrade upgrade -> confirmUpgrade(upgrade);
            case NoticeAction.Reinstall reinstall -> confirmReinstall(reinstall);
            case NoticeAction.InstallKomet(KometRelease release) -> onInstallKomet.accept(release);
            case NoticeAction.UpdateDashboard(KometRelease release) -> onUpdateDashboard.accept(release);
            case NoticeAction.MoveDashboard() -> onMoveDashboard.run();
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
     * What Nexus describes of a knowledge base.
     *
     * @param coordinate the knowledge base, at its base version
     * @return its descriptor and icon, when the catalog has them
     */
    public Optional<NexusCatalog.Described> described(Coordinate coordinate) {
        return Optional.ofNullable(catalog.described().get(coordinate));
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

    private void upgradeOrCopy(KnowledgeBaseFamily family) {
        family.latestIdentified().ifPresent(identified -> identified.coordinate()
                .filter(coordinate -> !transferring(downloadKey(coordinate, identified.kind().classifier()))
                        && !transferring(upgradeKey(identified.path())))
                .ifPresent(coordinate -> {
                    if (LauncherChoice.chooseForLoad(komets, identified).isPresent()) {
                        onUpgrade.accept(new NoticeAction.Upgrade(identified, coordinate, identified.kind().classifier()));
                    } else {
                        onDownload.accept(coordinate, identified.kind().classifier());
                    }
                }));
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
        if (showingNotices) {
            setCenter(noticesView);
            showNoticesPane();
            return;
        }
        setCenter(knowledgeBasesView);
        detail.getChildren().clear();
        KnowledgeBase loose = unmanagedList.getSelectionModel().getSelectedItem();
        if (loose != null) {
            showUnmanaged(loose);
            return;
        }
        Optional<KnowledgeBaseFamily> chosen = selected();
        if (chosen.isEmpty()) {
            TextArea none = SelectableText.of(families.isEmpty()
                    ? "No knowledge bases downloaded. Get one from Nexus to begin." : "Choose a knowledge base");
            none.setStyle(MUTED);
            detail.getChildren().add(none);
            return;
        }
        TextArea title = SelectableText.of(chosen.get().displayName());
        title.setStyle("-fx-font-size: 20px; -fx-font-weight: bold;");
        HBox heading = new HBox(12, icon(chosen.get(), 48), title);
        heading.setAlignment(Pos.CENTER_LEFT);
        detail.getChildren().add(heading);
        showFamily(chosen.get());
    }

    private void showUnmanaged(KnowledgeBase entry) {
        TextArea title = SelectableText.of(entry.name());
        title.setStyle("-fx-font-size: 20px; -fx-font-weight: bold;");
        detail.getChildren().add(title);
        detail.getChildren().add(muted(entry.kind().label() + "  ·  " + DashboardCommand.size(entry.sizeBytes())
                + "  ·  " + WHEN.format(entry.recency())));
        entry.coordinate().ifPresent(coordinate -> detail.getChildren().add(muted(coordinate.toString())));
        TextArea why = SelectableText.of("Not managed: " + String.join("; ", entry.descriptor().problems()) + ".");
        why.setStyle(ATTENTION);
        detail.getChildren().add(why);
        detail.getChildren().add(muted("The dashboard manages a store that carries a complete knowledge-base descriptor"
                + " (level 1) and its icon, as builds by ike-maven-plugin 268 or later do. Download a newer build to"
                + " manage this knowledge base; this copy can be shown in " + (OperatingSystem.current() == OperatingSystem.WINDOWS ? "File Explorer" : "the Finder")
                + " or moved to the " + Finder.trashName() + "."));
        Button reveal = new Button("Show in " + Finder.name());
        reveal.setOnAction(event -> onReveal.accept(entry.path()));
        Button trash = new Button("Move to " + Finder.trashName() + "…");
        trash.setOnAction(event -> confirmTrash(List.of(entry)));
        HBox actions = new HBox(8, reveal, trash);
        actions.setPadding(new Insets(6, 0, 0, 0));
        detail.getChildren().add(actions);
    }

    private void showFamily(KnowledgeBaseFamily family) {
        Optional<KnowledgeBase> latest = family.latest();
        List<KnowledgeBase> changeSets = family.members().stream().filter(member -> member.kind() == StoreKind.CHANGE_SETS).toList();
        if (latest.isEmpty()) {
            detail.getChildren().add(muted("Protobufs only. Komet loads a protobuf into a store; it does not open it."));
            detail.getChildren().add(section(KnowledgeBaseTree.PROTOBUFS, changeSets, true, false));
            relations(family).ifPresent(detail.getChildren()::add);
            return;
        }
        KnowledgeBase copy = latest.get();
        detail.getChildren().add(muted(copy.kind().label() + "  ·  " + DashboardCommand.size(copy.sizeBytes())
                + "  ·  " + WHEN.format(copy.recency())));
        copy.coordinate().ifPresent(coordinate -> {
            BuildStatus status = status(family.name());
            TextArea build = muted(coordinate.artifactId() + " " + copy.resolvedVersion().orElse(coordinate.version())
                    + nexusNote(status));
            if (status.state() == BuildStatus.State.CURRENT) {
                build.setStyle("-fx-text-fill: #2e7d32;");
            }
            build.setTooltip(new Tooltip(coordinate.toString() + (status.detail().isEmpty() ? "" : "\n" + status.detail())));
            detail.getChildren().add(build);
        });

        BuildStatus status = status(family.name());
        if (status.state() == BuildStatus.State.NEWER && status.newest().isPresent()) {
            KnowledgeBase identified = family.latestIdentified().orElse(copy);
            TextArea newer = SelectableText.of("A newer build is on Nexus, deployed "
                    + WHEN.format(status.newest().get().updated()) + ", for your copy " + identified.whereShown() + ".");
            String classifier = identified.kind().classifier();
            boolean downloading = identified.coordinate()
                    .map(coordinate -> transferring(downloadKey(coordinate, classifier))).orElse(false);
            boolean upgrading = transferring(upgradeKey(identified.path()));
            Optional<KometInstallation> validator = LauncherChoice.chooseForLoad(komets, identified);
            Button upgrade = new Button(upgrading ? "Upgrading …" : "Upgrade");
            upgrade.setDisable(upgrading || downloading || validator.isEmpty() || identified.coordinate().isEmpty());
            upgrade.setMinWidth(Region.USE_PREF_SIZE);
            upgrade.setOnAction(event -> identified.coordinate().ifPresent(coordinate ->
                    confirmUpgrade(new NoticeAction.Upgrade(identified, coordinate, classifier))));
            Button copyBeside = new Button(downloading ? "Downloading …" : "Install a copy");
            copyBeside.setDisable(downloading || upgrading);
            copyBeside.setMinWidth(Region.USE_PREF_SIZE);
            copyBeside.setOnAction(event -> identified.coordinate()
                    .ifPresent(coordinate -> onDownload.accept(coordinate, classifier)));
            Region gap = new Region();
            HBox.setHgrow(gap, Priority.ALWAYS);
            HBox.setHgrow(newer, Priority.ALWAYS);
            HBox row = new HBox(10, newer, gap, upgrade, copyBeside);
            row.setAlignment(Pos.CENTER_LEFT);
            VBox box = new VBox(4, row, muted(validator.isPresent()
                    ? "Upgrade keeps your change sets, preferences and repositories; the build you have goes to the "
                            + Finder.trashName() + "."
                    : "Upgrading in place needs Komet " + LauncherChoice.LOAD_CHANGE_SETS_SINCE + " or later, which checks the"
                            + " new build with no window; a copy can be installed beside."));
            box.setPadding(new Insets(8, 10, 8, 10));
            box.setStyle("-fx-background-color: #fff3e0; -fx-background-radius: 6;");
            detail.getChildren().add(box);
        }

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
            TextArea need = SelectableText.of(LauncherChoice.requirement(copy).map(requirement -> "Needs " + requirement
                    + (fix.isPresent() ? "." : "; none installed reads it, and no newer build was found."))
                    .orElse(fix.isPresent() ? "No Komet installed." : "No Komet installed, and none was found to install."));
            need.setStyle(PROBLEM);
            detail.getChildren().add(need);
            fix.ifPresent(release -> {
                Button install = new Button(installing ? "Installing Komet " + release.version() + " …"
                        : "Install Komet " + release.version());
                install.setDisable(installing);
                install.setOnAction(event -> onInstallKomet.accept(release));
                TextArea from = muted("from " + release.source().label()
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
                .ifPresent(plan -> detail.getChildren().add(HeapView.line(plan, komet.get(), copy)));
        TextArea folder = muted("Folder  " + copy.path().getFileName());
        folder.setTooltip(new Tooltip(copy.path().toString()));
        detail.getChildren().add(folder);

        List<KnowledgeBase> older = family.members().stream()
                .filter(member -> member != copy && member.kind().opensInKomet()).toList();
        if (!older.isEmpty()) {
            detail.getChildren().add(section(OTHER_COPIES, older, true, true));
        }
        if (!changeSets.isEmpty()) {
            detail.getChildren().add(section(KnowledgeBaseTree.PROTOBUFS, changeSets, false, false));
        }
        relations(family).ifPresent(detail.getChildren()::add);
    }

    /**
     * What a knowledge base was built from and what is built from it (decision 18): the inputs its newest
     * descriptor records, and every knowledge base here or on Nexus whose descriptor records it. Each one
     * the dashboard knows is a link: to its line here, else to it in the Nexus window.
     */
    private Optional<Node> relations(KnowledgeBaseFamily family) {
        Optional<KnowledgeBaseDescriptor> descriptor = family.members().stream()
                .flatMap(member -> member.descriptor().descriptor().stream()).findFirst();
        if (descriptor.isEmpty()) {
            return Optional.empty();
        }
        Coordinate self = descriptor.get().coordinate();
        Map<Coordinate, NexusCatalog.Described> described = catalog.described();
        VBox built = new VBox(2);
        descriptor.get().inputs().stream().filter(input -> !input.of(self)).forEach(input -> {
            KnowledgeBaseTree.Node node = KnowledgeBaseTree.input(input, described);
            String version = input.version().map(text -> "  " + text).orElse("");
            built.getChildren().add(relation(node.label(), node.knowledgeBase(),
                    KnowledgeBaseTree.describe(input) + version));
        });
        Map<String, Coordinate> including = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        families.stream().filter(other -> other != family)
                .forEach(other -> other.members().stream().flatMap(member -> member.descriptor().descriptor().stream())
                        .filter(found -> found.includes(self)).findFirst()
                        .ifPresent(found -> including.putIfAbsent(found.displayName(), found.coordinate())));
        described.forEach((coordinate, entry) -> {
            if (!coordinate.artifactId().equals(self.artifactId()) && entry.descriptor().includes(self)) {
                including.putIfAbsent(entry.descriptor().displayName(), coordinate);
            }
        });
        VBox included = new VBox(2);
        including.forEach((name, coordinate) -> included.getChildren().add(relation(name, Optional.of(coordinate), "")));
        if (built.getChildren().isEmpty() && included.getChildren().isEmpty()) {
            return Optional.empty();
        }
        VBox box = new VBox(6);
        if (!built.getChildren().isEmpty()) {
            box.getChildren().addAll(heading(KnowledgeBaseTree.BUILT_FROM), built);
        }
        if (!included.getChildren().isEmpty()) {
            box.getChildren().addAll(heading(KnowledgeBaseTree.INCLUDED_IN), included);
        }
        TitledPane pane = new TitledPane("Knowledge bases", box);
        pane.setAnimated(false);
        pane.setExpanded(true);
        return Optional.of(pane);
    }

    /**
     * One knowledge base a relation names, by its name here when it is here: a link when the dashboard can
     * show it, with what it is beside it.
     */
    private Node relation(String named, Optional<Coordinate> knowledgeBase, String what) {
        Optional<KnowledgeBaseFamily> here = knowledgeBase.flatMap(coordinate -> families.stream()
                .filter(family -> family.name().equalsIgnoreCase(coordinate.artifactId())).findFirst());
        String name = here.map(KnowledgeBaseFamily::displayName).orElse(named);
        Optional<Coordinate> onNexus = knowledgeBase.filter(coordinate -> catalog.described().keySet().stream()
                .anyMatch(described -> described.artifactId().equals(coordinate.artifactId())));
        Node label;
        if (here.isPresent()) {
            Hyperlink link = new Hyperlink(name);
            link.setOnAction(event -> select(here.get().name()));
            link.setTooltip(new Tooltip("In ~/Solor: show it"));
            label = link;
        } else if (onNexus.isPresent()) {
            Hyperlink link = new Hyperlink(name);
            link.setOnAction(event -> {
                openBrowser();
                browser.select(onNexus.get());
            });
            link.setTooltip(new Tooltip("Not in ~/Solor: show it in the Nexus window"));
            label = link;
        } else {
            label = SelectableText.of(name);
        }
        HBox row = new HBox(8, label);
        if (!what.isEmpty()) {
            row.getChildren().add(muted(what));
        }
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private static TextArea heading(String text) {
        TextArea label = SelectableText.of(text);
        label.setStyle("-fx-font-weight: bold;");
        return label;
    }


    private void confirmUpgrade(NoticeAction.Upgrade action) {
        KnowledgeBase store = action.store();
        Optional<KometInstallation> validator = LauncherChoice.chooseForLoad(komets, store);
        if (validator.isEmpty()) {
            setMessage("Upgrading needs Komet " + LauncherChoice.LOAD_CHANGE_SETS_SINCE + " or later");
            return;
        }
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Upgrade");
        alert.setHeaderText(upgradeHeader(store));
        alert.setContentText(upgradeText(store, validator.get().version()));
        alert.getButtonTypes().setAll(ButtonType.CANCEL, ButtonType.OK);
        if (getScene() != null) {
            alert.initOwner(getScene().getWindow());
        }
        SelectableText.in(alert);
        alert.showAndWait().filter(ButtonType.OK::equals).ifPresent(ok -> onUpgrade.accept(action));
    }

    /**
     * The question an upgrade asks.
     *
     * @param store the copy to upgrade
     * @return the question
     */
    static String upgradeHeader(KnowledgeBase store) {
        return "Upgrade " + store.displayName() + " to the newest build?";
    }

    /**
     * What an upgrade does, naming the copy it upgrades by its folder: a knowledge base can have several copies,
     * each under a name of its own, so the knowledge base's name alone does not say which.
     *
     * @param store     the copy to upgrade
     * @param validator the Komet that checks the new build
     * @return the text
     */
    static String upgradeText(KnowledgeBase store, String validator) {
        String assembled = store.assembledAt().map(at -> ", assembled " + WHEN.format(at)).orElse("");
        return "Your copy of " + store.displayName() + " is the folder " + store.whereShown() + assembled + ".\n\n"
                + "The newest build downloads into " + StoreUpgrade.workFolder(store.path()).getFileName()
                + " beside it, and your change sets, preferences and repositories are carried into it. Komet " + validator
                + " then checks, with no window, that it opens and loads every change set. If it does, the new build"
                + " takes the name " + store.name() + " and the build you have now goes to the " + Finder.trashName()
                + ", which is the way back. If it does not, " + store.name() + " stays as it is.\n\nOther folders of "
                + store.displayName() + " are not changed: each is upgraded on its own.\n\nQuit any Komet that has"
                + " this copy open first.";
    }

    /** Asks for the new folder's name, then copies the store into it. */
    private void askCopy(KnowledgeBase store) {
        javafx.scene.control.TextInputDialog dialog = new javafx.scene.control.TextInputDialog(
                StoreCopy.suggestedName(store.path()));
        dialog.setTitle("Copy to a new folder");
        dialog.setHeaderText("Copy " + store.name() + " to a new folder?");
        dialog.setContentText("Name");
        TextArea about = SelectableText.of(copyText(store), SelectableText.DIALOG_WIDTH);
        TextArea problem = SelectableText.of("", SelectableText.DIALOG_WIDTH);
        problem.setStyle(PROBLEM);
        javafx.scene.control.TextField name = dialog.getEditor();
        Node ok = dialog.getDialogPane().lookupButton(ButtonType.OK);
        Runnable check = () -> {
            Optional<String> wrong = StoreCopy.nameProblem(store.path(), name.getText());
            problem.setText(wrong.orElse(""));
            ok.setDisable(wrong.isPresent());
        };
        name.textProperty().addListener((observable, was, now) -> check.run());
        check.run();
        HBox field = new HBox(8, new Label("Name"), name);
        field.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(name, Priority.ALWAYS);
        dialog.getDialogPane().setContent(new VBox(10, about, field, problem));
        if (getScene() != null) {
            dialog.initOwner(getScene().getWindow());
        }
        SelectableText.in(dialog);
        dialog.showAndWait().map(String::strip).filter(text -> StoreCopy.nameProblem(store.path(), text).isEmpty())
                .ifPresent(text -> onCopy.accept(store, text));
    }

    /**
     * What Copy to a new folder does.
     *
     * @param store the copy to copy
     * @return the text
     */
    static String copyText(KnowledgeBase store) {
        return "The folder " + store.whereShown() + " is copied whole into a new folder beside it: its build, change sets,"
                + " preferences and repositories. The two are then separate copies of " + store.displayName()
                + ", each with its own change sets, opened and upgraded on its own.";
    }

    private void confirmReinstall(NoticeAction.Reinstall action) {
        KnowledgeBase store = action.store();
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Reinstall");
        alert.setHeaderText("Reinstall " + store.displayName() + " from scratch?");
        alert.setContentText("The folder " + store.whereShown() + " (" + DashboardCommand.size(store.sizeBytes()) + ") moves to the "
                + Finder.trashName() + " with everything in it: change sets, preferences and repositories. The newest build"
                + " then downloads under the same name. The " + Finder.trashName() + " is the way back. Quit any Komet that"
                + " has this store open first.");
        alert.getButtonTypes().setAll(ButtonType.CANCEL, ButtonType.OK);
        if (getScene() != null) {
            alert.initOwner(getScene().getWindow());
        }
        SelectableText.in(alert);
        alert.showAndWait().filter(ButtonType.OK::equals).ifPresent(ok -> onReinstall.accept(action));
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
        if (trashAll) {
            table.getColumns().add(nexusColumn());
        }
        TitledPane pane = new TitledPane(heading, table);
        pane.setExpanded(expanded);
        pane.setAnimated(false);
        if (!trashAll) {
            return pane;
        }
        Button trash = new Button("Move to " + Finder.trashName() + "…");
        trash.setOnAction(event -> confirmTrash(copies));
        HBox bar = new HBox(trash);
        bar.setAlignment(Pos.CENTER_RIGHT);
        VBox box = new VBox(4, pane, bar);
        if (copies.stream().anyMatch(copy -> upgradeOf(copy).isPresent())) {
            box.getChildren().add(1, muted("Each copy is a folder of its own, upgraded on its own: Upgrade brings that"
                    + " folder to the newest build where it is and keeps its change sets, preferences and repositories."));
        }
        return box;
    }

    /**
     * How a copy stands against the newest build on Nexus, with Upgrade for a copy that is behind it: every copy of
     * a knowledge base is a folder of its own, upgraded on its own.
     */
    private TableColumn<KnowledgeBase, KnowledgeBase> nexusColumn() {
        TableColumn<KnowledgeBase, KnowledgeBase> column = new TableColumn<>("Nexus");
        column.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue()));
        column.setPrefWidth(120);
        column.setCellFactory(view -> new javafx.scene.control.TableCell<>() {
            @Override
            protected void updateItem(KnowledgeBase copy, boolean empty) {
                super.updateItem(copy, empty);
                setText(null);
                setGraphic(null);
                if (empty || copy == null) {
                    return;
                }
                Optional<NoticeAction.Upgrade> upgrade = upgradeOf(copy);
                if (upgrade.isPresent()) {
                    Button button = new Button(upgradeBusy(copy) ? "Upgrading …" : "Upgrade…");
                    button.setStyle("-fx-font-size: 11px; -fx-padding: 1 8 1 8;");
                    button.setDisable(upgradeBusy(copy) || LauncherChoice.chooseForLoad(komets, copy).isEmpty());
                    button.setOnAction(event -> confirmUpgrade(upgrade.get()));
                    setGraphic(button);
                } else {
                    setText(standing(copy));
                }
            }
        });
        return column;
    }

    /** What a copy that is not behind says in the Nexus column. */
    private String standing(KnowledgeBase copy) {
        return newestFor(copy).map(newest -> NewerBuildCheck.compare(copy, newest).state())
                .map(state -> switch (state) {
                    case CURRENT -> "newest build";
                    case NEWER -> "older build";
                    default -> "";
                }).orElse("");
    }

    /** The newest build on Nexus of the knowledge base a copy belongs to, when it has been checked. */
    private Optional<SnapshotMetadata.Deploy> newestFor(KnowledgeBase copy) {
        return families.stream().filter(family -> family.members().contains(copy)).findFirst()
                .map(family -> status(family.name())).flatMap(BuildStatus::newest);
    }

    /**
     * The upgrade of one copy, when that folder is behind the newest build on Nexus and says which build it is.
     *
     * @param copy the copy
     * @return its upgrade
     */
    Optional<NoticeAction.Upgrade> upgradeOf(KnowledgeBase copy) {
        if (!copy.kind().opensInKomet() || !copy.identified() || copy.coordinate().isEmpty()) {
            return Optional.empty();
        }
        return newestFor(copy).filter(newest -> NewerBuildCheck.compare(copy, newest).state() == BuildStatus.State.NEWER)
                .map(newest -> new NoticeAction.Upgrade(copy, copy.coordinate().get(), copy.kind().classifier()));
    }

    /** Whether an upgrade, a download of its build or another change to the copy is under way. */
    private boolean upgradeBusy(KnowledgeBase copy) {
        return transferring(upgradeKey(copy.path()))
                || copy.coordinate().map(coordinate -> transferring(downloadKey(coordinate, copy.kind().classifier())))
                .orElse(false);
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
            if (withOpenAndTrash) {
                upgradeOf(copy).ifPresent(upgrade -> {
                    MenuItem item = new MenuItem("Upgrade…");
                    item.setDisable(upgradeBusy(copy) || LauncherChoice.chooseForLoad(komets, copy).isEmpty());
                    item.setOnAction(event -> confirmUpgrade(upgrade));
                    items.add(item);
                });
            }
            copy.coordinate().filter(coordinate -> copy.identified()).ifPresent(coordinate -> {
                MenuItem reinstall = new MenuItem("Reinstall…");
                reinstall.setDisable(transferring(upgradeKey(copy.path())) || transferring(downloadKey(coordinate, copy.kind().classifier())));
                reinstall.setOnAction(event -> confirmReinstall(new NoticeAction.Reinstall(copy, coordinate, copy.kind().classifier())));
                items.add(reinstall);
            });
        }
        MenuItem reveal = new MenuItem("Show in " + Finder.name());
        reveal.setOnAction(event -> onReveal.accept(copy.path()));
        MenuItem copyPath = new MenuItem("Copy path");
        copyPath.setOnAction(event -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(copy.path().toString());
            Clipboard.getSystemClipboard().setContent(content);
        });
        MenuItem duplicate = new MenuItem("Copy to a new folder…");
        duplicate.setDisable(transferring(upgradeKey(copy.path())));
        duplicate.setOnAction(event -> askCopy(copy));
        items.add(reveal);
        items.add(copyPath);
        if (copy.kind().opensInKomet()) {
            items.add(duplicate);
        }
        if (withOpenAndTrash) {
            MenuItem trash = new MenuItem("Move to " + Finder.trashName() + "…");
            trash.setOnAction(event -> confirmTrash(List.of(copy)));
            items.add(trash);
        }
        return items;
    }

    private void confirmTrash(List<KnowledgeBase> copies) {
        long bytes = copies.stream().mapToLong(KnowledgeBase::sizeBytes).sum();
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Move to " + Finder.trashName());
        alert.setHeaderText("Move " + (copies.size() == 1 ? copies.getFirst().name() : copies.size() + " copies")
                + " (" + DashboardCommand.size(bytes) + ") to the " + Finder.trashName() + "?");
        alert.setContentText((copies.size() == 1 ? "" : copies.stream().map(KnowledgeBase::name)
                .collect(Collectors.joining("\n", "", "\n\n")))
                + "A copy you edited may contain work that no build contains. Quit any Komet that has one open first. "
                + "The space comes back when the " + Finder.trashName() + " is emptied.");
        alert.getButtonTypes().setAll(ButtonType.CANCEL, ButtonType.OK);
        if (getScene() != null) {
            alert.initOwner(getScene().getWindow());
        }
        SelectableText.in(alert);
        alert.showAndWait().filter(ButtonType.OK::equals).ifPresent(ok -> onTrash.accept(copies));
    }

    /** A knowledge base's icon, read once per refresh. */
    private Node icon(KnowledgeBaseFamily family, double size) {
        return KnowledgeBaseIcon.of(family.icon().map(file -> icons.computeIfAbsent(file, KnowledgeBaseIcon::load)), size);
    }

    private static TextArea muted(String text) {
        TextArea area = SelectableText.of(text);
        area.setStyle(MUTED);
        return area;
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
                mark.setText("protobuf");
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
            MenuItem reveal = new MenuItem("Show in " + Finder.name());
            reveal.setOnAction(event -> onReveal.accept(entry.path()));
            MenuItem trash = new MenuItem("Move to " + Finder.trashName() + "…");
            trash.setOnAction(event -> confirmTrash(List.of(entry)));
            setContextMenu(new ContextMenu(reveal, trash));
        }
    }
}
