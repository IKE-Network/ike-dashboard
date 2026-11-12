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
import javafx.geometry.VPos;
import javafx.geometry.HPos;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.ChoiceBox;
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
import javafx.scene.layout.GridPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
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
 * The projects that build knowledge bases are kept apart, on a tab of their own, the {@link ProjectsPane}.
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
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());
    private static final String MUTED = "-fx-text-fill: #6b6b6b;";

    /** The section that lists every folder of a knowledge base. */
    static final String FOLDERS = "Folders";

    /** The folder chosen in each knowledge base's Folders table, by family name, kept while the window is open. */
    private final Map<String, String> chosenFolders = new java.util.HashMap<>();
    private static final String ATTENTION = "-fx-text-fill: #b35900;";
    private static final String PROBLEM = "-fx-text-fill: #c62828;";
    private static final int TABLE_ROWS = 8;

    private final ObservableList<KnowledgeBaseFamily> listings = FXCollections.observableArrayList();
    private final FilteredList<KnowledgeBaseFamily> shown = new FilteredList<>(listings);
    private final ListView<KnowledgeBaseFamily> list = new ListView<>(shown);
    private final TextField search = new TextField();
    private final ToggleButton byName = new ToggleButton("Name");
    private final ToggleButton byRecent = new ToggleButton("Recent");
    private final Button refresh = new Button("↻");
    private final Label updates = new Label();
    /** Opens the notices, where each newer version is offered folder by folder, each with its own confirmation. */
    private final Hyperlink updateAll = new Hyperlink("Show");
    private final HBox updateBar = new HBox(8);
    private final Button fromNexus = new Button("Get from Nexus…");
    private final NexusBrowser browser = new NexusBrowser();
    private Stage browserWindow;
    /** The git repositories inside the chosen folder, and the remote its change sets are given. */
    private final RepositoriesSection repositories = new RepositoriesSection(this::showDetail);
    /** The Projects tab: the projects that build knowledge bases, apart from the knowledge bases themselves. */
    private final ProjectsPane projects = new ProjectsPane();
    private final ToggleButton projectsTab = new ToggleButton("Projects");
    private boolean showingProjects;
    private final NewKnowledgeBaseForm newKnowledgeBaseForm = new NewKnowledgeBaseForm();
    private Stage newKnowledgeBaseWindow;
    private final VBox detail = new VBox(10);
    private final SelectableText message = SelectableText.of("");
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
    private final ToggleButton kometsTab = new ToggleButton("Komet");
    /** The Komet tab, laid out as the Knowledge Bases tab: the Komets on the left, the chosen one's detail. */
    private final ListView<KometInstallation> kometList = new ListView<>();
    private final VBox kometDetail = new VBox(10);
    private final HBox kometBanner = new HBox(8);
    private SplitPane kometsView;
    private Map<String, KometNotes> kometNotes = Map.of();
    private Consumer<java.net.URI> onOpenLink = uri -> { };
    private boolean showingKomets;
    private Consumer<List<KometInstallation>> onTrashKomets = komets -> { };
    private KometPins pins = KometPins.NONE;
    private java.util.function.Function<KnowledgeBase, UnsavedWork.Report> unsavedWork =
            copy -> new UnsavedWork.Report(List.of(), 0, true);
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
        updateAll.setMinWidth(Region.USE_PREF_SIZE);
        for (Button button : List.of(refresh, fromNexus)) {
            button.setMinWidth(Region.USE_PREF_SIZE);
        }
        byName.setMinWidth(Region.USE_PREF_SIZE);
        byRecent.setMinWidth(Region.USE_PREF_SIZE);
        HBox sortControl = new HBox(byName, byRecent);
        sortControl.setMinWidth(Region.USE_PREF_SIZE);
        HBox listBar = new HBox(6, search, sortControl);
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
        // No "Upgrade all" (KEC, 2026-11-08): a knowledge base can have several folders, and each is upgraded on its
        // own, after a confirmation that names it.
        updateAll.setOnAction(event -> showNotices());
        ToggleGroup views = new ToggleGroup();
        knowledgeBasesTab.setToggleGroup(views);
        noticesTab.setToggleGroup(views);
        kometsTab.setToggleGroup(views);
        projectsTab.setToggleGroup(views);
        projectsTab.getStyleClass().add("center-pill");
        projectsTab.setMinWidth(Region.USE_PREF_SIZE);
        knowledgeBasesTab.getStyleClass().add("left-pill");
        kometsTab.getStyleClass().add("center-pill");
        noticesTab.getStyleClass().add("right-pill");
        knowledgeBasesTab.setMinWidth(Region.USE_PREF_SIZE);
        kometsTab.setMinWidth(Region.USE_PREF_SIZE);
        noticesTab.setMinWidth(Region.USE_PREF_SIZE);
        noticesTab.setSelected(true);
        views.selectedToggleProperty().addListener((observable, was, now) -> {
            if (now == null) {
                was.setSelected(true);                   // one view is always shown
                return;
            }
            if (now == noticesTab) {
                showNotices();
            } else if (now == projectsTab) {
                showProjects();
            } else if (now == kometsTab) {
                showKomets();
            } else {
                showKnowledgeBases();
            }
        });
        HBox views3 = new HBox(knowledgeBasesTab, projectsTab, kometsTab, noticesTab);
        views3.setAlignment(Pos.CENTER);
        views3.setMaxWidth(Region.USE_PREF_SIZE);
        // Refresh is at the top right on every view, whichever is shown (KEC, 2026-11-08).
        refresh.setStyle("-fx-font-size: 15px; -fx-padding: 2 9 2 9;");
        refresh.setTooltip(new Tooltip("Refresh: look again at your knowledge bases, the Komets installed, and Nexus"));
        refresh.setAccessibleText("Refresh");
        StackPane viewBar = new StackPane(views3, refresh);
        StackPane.setAlignment(refresh, Pos.CENTER_RIGHT);
        viewBar.setPadding(new Insets(8, 12, 6, 12));
        setTop(viewBar);
        VBox masterTop = new VBox(listBar, updateBar);

        fromNexus.setOnAction(event -> openBrowser());
        browser.setOnDownload((coordinate, classifier) -> onDownload.accept(coordinate, classifier));
        projects.setOnNew(this::openNewKnowledgeBase);
        HBox masterBottom = new HBox(fromNexus);
        masterBottom.setPadding(new Insets(8));

        list.setCellFactory(view -> new ListingCell());
        list.setPlaceholder(new Label("No managed knowledge bases downloaded"));
        list.getSelectionModel().selectedItemProperty().addListener((observable, was, now) -> {
            if (now != null) {
                showingNotices = false;
                showingKomets = false;
                showingProjects = false;
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
                showingKomets = false;
                showingProjects = false;
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
        kometsView = kometSplit();
        setCenter(noticesView);

        message.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(message, Priority.ALWAYS);
        showLog.setDisable(true);
        showLog.setOnAction(event -> {
            if (lastLog != null) {
                onOpenFile.accept(lastLog);
            }
        });
        HBox footer = new HBox(10, message, showLog);
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
        newKnowledgeBaseForm.setCatalog(catalog);
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
     * The New knowledge base window's content.
     *
     * @return the form
     */
    public NewKnowledgeBaseForm newKnowledgeBase() {
        return newKnowledgeBaseForm;
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
        // The Komet tab is where Komets are installed and removed; the switch says when a newer one is there
        // (KEC, 2026-11-08: the footer's Komet menu was "irrelevant for this frame").
        Optional<KometRelease> newer = newerKomet();
        kometsTab.setText(newer.isPresent() ? "Komet  ·  new" : "Komet");
        kometsTab.setStyle(newer.isPresent() ? ATTENTION : "");
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
        SelectableText text = SelectableText.of(refusedText(summaries, whyNoAside), 600);
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
        return (count == 1 ? "A change set" : count + " change sets") + " could not be loaded into the new version";
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
            out.append(Names.of(summary.fileName())).append('\n');
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
        String them = summaries.size() == 1 ? "it" : "them";
        out.append(whyNoAside.orElse("Set aside moves " + them + " into the folder "
                + Names.of(RefusedChangeSets.UNPROCESSED_FOLDER) + ", where Komet does not load " + them
                + ", and finishes the upgrade. " + (summaries.size() == 1 ? "It stays" : "They stay")
                + " there through later upgrades.\n\nCancel stops the upgrade and leaves your folder as it was."));
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
            case 0 -> "Every change set loaded.";
            case 1 -> "Some change sets could not be loaded. The report below says what each one refers to that this"
                    + " knowledge base does not contain.";
            default -> "Komet stopped before it loaded the change sets. Its exit status was " + run.exitStatus() + ".";
        });
        outcome.setWrapText(true);
        outcome.setStyle(run.clean() ? "-fx-text-fill: #2e7d32;" : run.exitStatus() == 1 ? ATTENTION : PROBLEM);
        TextArea text = new TextArea(run.text());
        text.setEditable(false);
        text.setWrapText(true);
        text.setStyle("-fx-font-family: 'Menlo', monospace;");
        SelectableText log = muted("Log  " + Names.of(run.log()));
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
        showingKomets = false;
        showingProjects = false;
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
        showingKomets = false;
        showingProjects = false;
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
     * Shows the Komet versions installed on this machine, across the window, each with what it is used for and
     * Move to Trash (KEC, 2026-11-08). The knowledge base chosen stays chosen.
     */
    public void showKomets() {
        lastChosen = selected().map(KnowledgeBaseFamily::name).orElse(lastChosen);
        showingKomets = true;
        showingNotices = false;
        showingProjects = false;
        kometsTab.setSelected(true);
        list.getSelectionModel().clearSelection();
        unmanagedList.getSelectionModel().clearSelection();
        showDetail();
    }

    /**
     * Shows the projects that build knowledge bases, across the window (decision 21; KEC, 2026-11-12). The
     * knowledge base chosen stays chosen.
     */
    public void showProjects() {
        lastChosen = selected().map(KnowledgeBaseFamily::name).orElse(lastChosen);
        showingProjects = true;
        showingKomets = false;
        showingNotices = false;
        projectsTab.setSelected(true);
        list.getSelectionModel().clearSelection();
        unmanagedList.getSelectionModel().clearSelection();
        showDetail();
    }

    /**
     * Whether the projects are shown.
     *
     * @return {@code true} when they are
     */
    public boolean showingProjects() {
        return showingProjects;
    }

    /**
     * The repositories shown in a knowledge-base folder's detail, where its change sets are given a remote.
     *
     * @return the section
     */
    RepositoriesSection repositories() {
        return repositories;
    }

    /**
     * What checking a remote for a knowledge base's change sets, and adding it, do.
     *
     * @param action given the repository's folder, the address, and whether to add it
     */
    public void setOnRepositoryRemote(RepositoriesSection.RemoteAction action) {
        repositories.setOnRemote(action);
    }

    /**
     * Says that git is being asked about a repository inside a knowledge base.
     *
     * @param folder the repository's folder
     */
    public void repositoryWorking(Path folder) {
        repositories.working(folder);
    }

    /**
     * Shows what a check of a remote for a repository inside a knowledge base found.
     *
     * @param check what adding it will do, or why it cannot be added
     */
    public void repositoryChecked(RepositoryRemote.Check check) {
        repositories.checked(check);
    }

    /**
     * Shows how adding a remote to a repository inside a knowledge base ended.
     *
     * @param folder  the repository's folder
     * @param failure why it failed, or empty when the remote was added
     */
    public void repositoryRemoteAdded(Path folder, Optional<String> failure) {
        repositories.added(folder, failure);
    }

    /**
     * The Projects tab's content.
     *
     * @return the pane
     */
    public ProjectsPane projects() {
        return projects;
    }

    /**
     * Whether the Komet versions are shown.
     *
     * @return {@code true} when they are
     */
    public boolean showingKomets() {
        return showingKomets;
    }

    /**
     * What Move to Trash does for Komet versions.
     *
     * @param action given the Komets to move
     */
    public void setOnTrashKomets(Consumer<List<KometInstallation>> action) {
        onTrashKomets = action;
    }

    /**
     * How Move to Trash reads what a store contains that is saved nowhere else.
     *
     * @param reader given the store, what is unsaved in it
     */
    public void setUnsavedWork(java.util.function.Function<KnowledgeBase, UnsavedWork.Report> reader) {
        unsavedWork = reader;
    }

    /**
     * What changed in each Komet version, from its checkpoint release.
     *
     * @param notes the notes, by Komet version
     */
    public void setKometNotes(Map<String, KometNotes> notes) {
        kometNotes = Map.copyOf(notes);
        if (showingKomets) {
            showKometDetail();
        }
    }

    /**
     * How a link to the web is opened.
     *
     * @param action given the link
     */
    public void setOnOpenLink(Consumer<java.net.URI> action) {
        onOpenLink = action;
    }

    /**
     * Which Komet each knowledge base opens in: the latest, or one it is pinned to.
     *
     * @param kometPins the pins
     */
    public void setKometPins(KometPins kometPins) {
        pins = kometPins;
        list.refresh();
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
        boolean changed = !List.copyOf(statuses).equals(sync.get(store));
        sync.put(store, List.copyOf(statuses));
        refreshNotices();
        if (changed && !showingNotices && !showingKomets && !showingProjects) {
            showDetail();                                // the chosen folder lists its repositories
        }
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
        SelectableText title = SelectableText.of("Notices");
        title.setStyle("-fx-font-size: 20px; -fx-font-weight: bold;");
        box.getChildren().add(title);
        if (notices.isEmpty()) {
            box.getChildren().add(muted(families.isEmpty()
                    ? "No knowledge bases downloaded. Get one from Nexus to begin." : "Nothing needs your attention."));
        }
        if (!notices.isEmpty()) {
            box.getChildren().add(noticeGrid(notices));
        }
        box.getChildren().add(muted(nexusChecked
                .map(checked -> checked.current() ? "Nexus checked " + WHEN.format(checked.checkedAt())
                        : "Could not reach Nexus. This is what it listed at " + WHEN.format(checked.checkedAt()) + ".")
                .orElse("Nexus not checked yet")));
    }

    /** The Komet tab: the installed Komets as a list, as the knowledge bases are, and the chosen one's detail. */
    private SplitPane kometSplit() {
        kometList.setId("komet-list");
        kometList.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        kometList.setCellFactory(view -> new KometCell());
        kometList.setPlaceholder(new Label("No Komet is installed"));
        kometList.getSelectionModel().getSelectedItems().addListener(
                (javafx.collections.ListChangeListener<KometInstallation>) change -> showKometDetail());
        kometList.setOnKeyPressed(event -> {
            // ⌘⌫ moves the selection to the Trash, as in the Finder.
            if (event.getCode() == javafx.scene.input.KeyCode.BACK_SPACE && event.isShortcutDown()
                    && !kometList.getSelectionModel().getSelectedItems().isEmpty()) {
                confirmTrashKomets(List.copyOf(kometList.getSelectionModel().getSelectedItems()));
            }
        });
        kometBanner.setAlignment(Pos.CENTER_LEFT);
        kometBanner.setPadding(new Insets(6, 8, 6, 10));
        kometBanner.setStyle("-fx-background-color: #fff3e0;");
        kometBanner.managedProperty().bind(kometBanner.visibleProperty());
        kometBanner.setVisible(false);
        BorderPane master = new BorderPane(kometList);
        master.setTop(kometBanner);
        master.setMinWidth(260);
        kometDetail.setPadding(new Insets(16, 20, 20, 20));
        ScrollPane detailScroll = new ScrollPane(kometDetail);
        detailScroll.setFitToWidth(true);
        detailScroll.setStyle("-fx-background-color: transparent; -fx-background-insets: 0;");
        SplitPane split = new SplitPane(master, detailScroll);
        split.setOrientation(Orientation.HORIZONTAL);
        split.setDividerPositions(0.32);
        SplitPane.setResizableWithParent(master, false);
        return split;
    }

    /** Fills the Komet list again, keeping what was chosen, the newest when nothing was. */
    private void showKometsPane() {
        List<String> chosen = kometList.getSelectionModel().getSelectedItems().stream().map(KometInstallation::version).toList();
        kometList.getItems().setAll(komets);
        newerKomet().ifPresentOrElse(release -> {
            Label about = new Label("Komet " + release.version() + " is available");
            about.setStyle(ATTENTION);
            about.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(about, Priority.ALWAYS);
            Hyperlink install = new Hyperlink(installing ? "Installing …" : "Install");
            install.setDisable(installing);
            install.setMinWidth(Region.USE_PREF_SIZE);
            install.setOnAction(event -> onInstallKomet.accept(release));
            kometBanner.getChildren().setAll(about, install);
            kometBanner.setVisible(true);
        }, () -> kometBanner.setVisible(false));
        boolean any = false;
        for (int i = 0; i < komets.size(); i++) {
            if (chosen.contains(komets.get(i).version())) {
                kometList.getSelectionModel().select(i);
                any = true;
            }
        }
        if (!any && !komets.isEmpty()) {
            kometList.getSelectionModel().select(0);
        }
        showKometDetail();
    }

    /** The chosen Komet's detail, or what several chosen have in common. */
    private void showKometDetail() {
        kometDetail.getChildren().clear();
        List<KometInstallation> chosen = List.copyOf(kometList.getSelectionModel().getSelectedItems());
        if (chosen.isEmpty()) {
            kometDetail.getChildren().add(muted(komets.isEmpty() ? "No Komet is installed." : "Choose a Komet"));
            return;
        }
        if (chosen.size() > 1) {
            showKomets(chosen);
            return;
        }
        KometInstallation komet = chosen.getFirst();
        boolean running = KometRemoval.running(komet);
        boolean newest = komet == komets.getFirst();
        SelectableText title = SelectableText.of("Komet " + komet.version());
        title.setStyle("-fx-font-size: 20px; -fx-font-weight: bold;");
        HBox heading = new HBox(12, kometTile(48), title);
        heading.setAlignment(Pos.CENTER_LEFT);
        kometDetail.getChildren().add(heading);
        kometDetail.getChildren().add(muted("Application  " + Names.of(komet.bundle().getFileName()) + " in "
                + Names.of(shownFolder(komet.bundle().getParent()))));
        SelectableText status = SelectableText.of(newest
                ? "The newest. Knowledge bases open in it unless they are set to another."
                : "An older version. A knowledge base opens in it only when it is set to it.");
        status.setStyle(newest ? "-fx-text-fill: #2e7d32;" : MUTED);
        kometDetail.getChildren().add(status);
        if (running) {
            kometDetail.getChildren().add(muted("It is running now."));
        }

        // Open a knowledge base in this Komet then and there, without changing which Komet it is set to.
        List<KnowledgeBase> openable = families.stream().map(KnowledgeBaseFamily::latest).flatMap(Optional::stream)
                .filter(copy -> LauncherChoice.reads(komet, copy)).toList();
        if (!openable.isEmpty()) {
            ChoiceBox<KnowledgeBase> which = new ChoiceBox<>();
            which.getItems().setAll(openable);
            which.setConverter(new javafx.util.StringConverter<>() {
                @Override
                public String toString(KnowledgeBase copy) {
                    return copy == null ? "" : copy.displayName();
                }

                @Override
                public KnowledgeBase fromString(String text) {
                    return null;
                }
            });
            which.getSelectionModel().selectFirst();
            Button open = new Button("Open");
            open.setDefaultButton(true);
            open.setStyle("-fx-font-size: 14px; -fx-padding: 6 28 6 28;");
            open.setOnAction(event -> Optional.ofNullable(which.getValue()).ifPresent(copy -> onOpen.accept(komet, copy)));
            Label in = new Label("in this Komet");
            in.setStyle(MUTED);
            HBox openRow = new HBox(8, open, which, in);
            openRow.setAlignment(Pos.CENTER_LEFT);
            openRow.setPadding(new Insets(6, 0, 0, 0));
            kometDetail.getChildren().add(openRow);
            kometDetail.getChildren().add(muted("This opens it once in Komet " + komet.version()
                    + ". Which Komet a knowledge base opens in is set beside its own Open."));
        }

        Button reveal = new Button("Show in " + Finder.name());
        reveal.setOnAction(event -> onReveal.accept(komet.bundle()));
        Button trash = new Button("Move to " + Finder.trashName() + "…");
        trash.setDisable(running);
        trash.setOnAction(event -> confirmTrashKomets(List.of(komet)));
        if (running) {
            Tooltip.install(trash, new Tooltip("Quit this Komet first."));
        }
        HBox actions = new HBox(8, reveal, trash);
        actions.setAlignment(Pos.CENTER_LEFT);
        kometDetail.getChildren().add(actions);

        List<KnowledgeBaseFamily> pinned = families.stream()
                .filter(family -> pins.of(family.name()).filter(komet.version()::equals).isPresent()).toList();
        List<KnowledgeBase> only = KometRemoval.stranded(komets, List.of(komet),
                families.stream().flatMap(family -> family.members().stream()).toList());
        if (!pinned.isEmpty() || !only.isEmpty()) {
            VBox box = new VBox(6);
            if (!pinned.isEmpty()) {
                box.getChildren().add(heading("Set to open in it"));
                pinned.forEach(family -> {
                    Hyperlink link = new Hyperlink(family.displayName());
                    link.setOnAction(event -> select(family.name()));
                    box.getChildren().add(link);
                });
            }
            if (!only.isEmpty()) {
                box.getChildren().add(heading("Only it can open"));
                only.forEach(store -> {
                    Hyperlink link = new Hyperlink(store.name());
                    link.setOnAction(event -> select(KnowledgeBaseFamily.familyName(store)));
                    box.getChildren().add(link);
                });
            }
            TitledPane pane = new TitledPane("Knowledge bases", box);
            pane.setAnimated(false);
            kometDetail.getChildren().add(pane);
        }
        kometDetail.getChildren().add(whatChanged(komet));
    }

    /** What changed in a Komet version: its checkpoint's notes, each commit linked, and the release itself. */
    private Node whatChanged(KometInstallation komet) {
        VBox box = new VBox(6);
        KometNotes notes = kometNotes.get(komet.version());
        if (notes == null) {
            box.getChildren().add(muted("No release notes were found for this version. The releases on GitHub list"
                    + " the recent checkpoints."));
            Hyperlink releases = new Hyperlink("Releases on GitHub");
            releases.setOnAction(event -> onOpenLink.accept(java.net.URI.create(
                    "https://github.com/" + KometReleases.GITHUB_REPOSITORY + "/releases")));
            box.getChildren().add(releases);
        } else {
            Hyperlink release = new Hyperlink(notes.title());
            release.setOnAction(event -> onOpenLink.accept(notes.url()));
            HBox top = new HBox(6, release);
            notes.published().ifPresent(when -> top.getChildren().add(muted("published " + WHEN.format(when))));
            top.setAlignment(Pos.CENTER_LEFT);
            box.getChildren().add(top);
            if (notes.members().isEmpty()) {
                box.getChildren().add(muted("Its notes list no changes."));
            }
            for (KometNotes.Member member : notes.members()) {
                SelectableText name = SelectableText.of(member.name());
                name.setStyle("-fx-font-weight: bold;");
                HBox memberRow = new HBox(6, name);
                memberRow.setAlignment(Pos.CENTER_LEFT);
                member.compare().ifPresent(compare -> {
                    Hyperlink diff = new Hyperlink("compare");
                    diff.setOnAction(event -> onOpenLink.accept(compare));
                    memberRow.getChildren().add(diff);
                });
                VBox.setMargin(memberRow, new Insets(6, 0, 0, 0));
                box.getChildren().add(memberRow);
                for (KometNotes.Commit commit : member.commits()) {
                    Hyperlink sha = new Hyperlink(commit.sha());
                    sha.setStyle("-fx-font-family: 'Menlo', monospace; -fx-font-size: 11px;");
                    sha.setMinWidth(Region.USE_PREF_SIZE);
                    sha.setOnAction(event -> onOpenLink.accept(commit.url()));
                    SelectableText subject = SelectableText.of(commit.subject());
                    HBox.setHgrow(subject, Priority.ALWAYS);
                    HBox commitRow = new HBox(4, sha, subject);
                    commitRow.setAlignment(Pos.CENTER_LEFT);
                    commitRow.setPadding(new Insets(0, 0, 0, 12));
                    box.getChildren().add(commitRow);
                }
            }
        }
        TitledPane pane = new TitledPane("What changed", box);
        pane.setAnimated(false);
        return pane;
    }

    /** Several Komets chosen: which they are, and Move to Trash for all of them. */
    private void showKomets(List<KometInstallation> chosen) {
        SelectableText title = SelectableText.of(chosen.size() + " Komets chosen");
        title.setStyle("-fx-font-size: 20px; -fx-font-weight: bold;");
        kometDetail.getChildren().add(title);
        kometDetail.getChildren().add(muted(chosen.stream().map(komet -> "Komet " + komet.version())
                .collect(Collectors.joining(", ")) + "."));
        List<KometInstallation> running = chosen.stream().filter(KometRemoval::running).toList();
        Button trash = new Button("Move " + chosen.size() + " Komets to the " + Finder.trashName() + "…");
        trash.setDisable(!running.isEmpty());
        trash.setOnAction(event -> confirmTrashKomets(chosen));
        kometDetail.getChildren().add(trash);
        if (!running.isEmpty()) {
            SelectableText why = SelectableText.of(running.stream().map(komet -> "Komet " + komet.version())
                    .collect(Collectors.joining(", ")) + (running.size() == 1 ? " is" : " are")
                    + " running. Quit " + (running.size() == 1 ? "it" : "them") + " first.");
            why.setStyle(ATTENTION);
            kometDetail.getChildren().add(why);
        }
    }

    /** A Komet's tile: the same shape as a knowledge base's icon, so the two lists read alike. */
    private static Node kometTile(double size) {
        Label letter = new Label("K");
        letter.setStyle("-fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: " + Math.round(size * 0.5) + "px;");
        StackPane tile = new StackPane(letter);
        tile.setMinSize(size, size);
        tile.setPrefSize(size, size);
        tile.setMaxSize(size, size);
        tile.setStyle("-fx-background-color: #2f5f8a; -fx-background-radius: " + Math.round(size * 0.22) + ";");
        return tile;
    }

    /** A line of the Komet list: the tile, the version, and a mark for what sets it apart. */
    private final class KometCell extends ListCell<KometInstallation> {
        @Override
        protected void updateItem(KometInstallation komet, boolean empty) {
            super.updateItem(komet, empty);
            setText(null);
            if (empty || komet == null) {
                setGraphic(null);
                return;
            }
            Label name = new Label("Komet " + komet.version());
            Label mark = new Label();
            mark.setStyle("-fx-font-size: 11px;" + MUTED);
            List<String> marks = new ArrayList<>();
            if (komet == komets.getFirst()) {
                marks.add("newest");
            }
            if (families.stream().anyMatch(family -> pins.of(family.name()).filter(komet.version()::equals).isPresent())) {
                marks.add("set for some");
            }
            if (KometRemoval.running(komet)) {
                marks.add("running");
            }
            mark.setText(String.join(" · ", marks));
            if (komet == komets.getFirst()) {
                mark.setStyle("-fx-font-size: 11px; -fx-text-fill: #2e7d32;");
            }
            Region gap = new Region();
            HBox.setHgrow(gap, Priority.ALWAYS);
            HBox line = new HBox(6, kometTile(20), name, gap, mark);
            line.setAlignment(Pos.CENTER_LEFT);
            line.setMinWidth(0);
            line.prefWidthProperty().bind(kometList.widthProperty().subtract(36));
            setGraphic(line);
        }
    }

    /** The knowledge bases set to open in a Komet version, by their names. */
    private List<String> pinnedTo(String version) {
        return families.stream().filter(family -> pins.of(family.name()).filter(version::equals).isPresent())
                .map(KnowledgeBaseFamily::displayName).sorted().toList();
    }

    /** A folder as a person reads it: the home folder as {@code ~}. */
    private static String shownFolder(java.nio.file.Path folder) {
        java.nio.file.Path home = java.nio.file.Path.of(System.getProperty("user.home")).toAbsolutePath();
        java.nio.file.Path absolute = folder.toAbsolutePath();
        return absolute.startsWith(home) && !absolute.equals(home)
                ? "~/" + home.relativize(absolute).toString().replace('\\', '/') : absolute.toString();
    }

    /** Asks before moving Komet versions to the Trash, naming what no Komet could open afterwards. */
    private void confirmTrashKomets(List<KometInstallation> removing) {
        List<String> running = removing.stream().filter(KometRemoval::running)
                .map(komet -> "Komet " + komet.version() + " is running. Quit it, then try again.").toList();
        if (!running.isEmpty()) {
            showRefusals("Move to " + Finder.trashName(), running);
            return;
        }
        List<KnowledgeBase> stranded = KometRemoval.stranded(komets,
                removing, families.stream().flatMap(family -> family.members().stream()).toList());
        Alert alert = new Alert(stranded.isEmpty() ? Alert.AlertType.CONFIRMATION : Alert.AlertType.WARNING);
        alert.setTitle("Move to " + Finder.trashName());
        alert.setHeaderText(removing.size() == 1 ? "Move Komet " + removing.getFirst().version() + " to the "
                + Finder.trashName() + "?" : "Move " + removing.size() + " older Komets to the " + Finder.trashName() + "?");
        StringBuilder text = new StringBuilder();
        if (removing.size() > 1) {
            removing.forEach(komet -> text.append("Komet ").append(komet.version()).append(", ")
                    .append(Names.of(komet.bundle().getFileName())).append('\n'));
            text.append('\n');
        }
        text.append("Your knowledge bases are not changed. You can restore a Komet from the ").append(Finder.trashName())
                .append(" if you need it.");
        List<String> pinned = removing.stream().flatMap(komet -> pinnedTo(komet.version()).stream()).distinct().toList();
        if (!pinned.isEmpty()) {
            text.append("\n\n").append(String.join(", ", pinned)).append(pinned.size() == 1 ? " is" : " are")
                    .append(" set to open in ").append(removing.size() == 1 ? "this Komet" : "one of these Komets")
                    .append(". After this, ").append(pinned.size() == 1 ? "it opens" : "they open")
                    .append(" in the latest Komet.");
        }
        if (!stranded.isEmpty()) {
            text.append("\n\nAfter this, no installed Komet can open ")
                    .append(stranded.stream().map(store -> Names.of(store.name())).collect(Collectors.joining(", ")))
                    .append(". Keep the Komet they need, or install a newer one first.");
        }
        alert.setContentText(text.toString());
        alert.getButtonTypes().setAll(ButtonType.CANCEL, ButtonType.OK);
        if (getScene() != null) {
            alert.initOwner(getScene().getWindow());
        }
        SelectableText.in(alert);
        alert.showAndWait().filter(ButtonType.OK::equals).ifPresent(ok -> onTrashKomets.accept(removing));
    }

    /**
     * The notices as one grid, so every card's actions line up (KEC, 2026-11-08: "make it smoother and pixel
     * perfect"): the words take the free width; then one column for the action button, every button as wide as the
     * widest; one for Show; one for Dismiss. A card's background spans its row, behind its cells. Nothing in the
     * action columns is ever narrower than its words, so no label shrinks to an ellipsis.
     */
    private GridPane noticeGrid(List<Notice> shown) {
        GridPane grid = new GridPane();
        grid.setVgap(10);
        ColumnConstraints words = new ColumnConstraints();
        words.setHgrow(Priority.ALWAYS);
        words.setFillWidth(true);
        words.setMinWidth(160);
        ColumnConstraints act = new ColumnConstraints();
        act.setMinWidth(Region.USE_PREF_SIZE);
        act.setFillWidth(true);
        ColumnConstraints link = new ColumnConstraints();
        link.setMinWidth(Region.USE_PREF_SIZE);
        link.setHalignment(HPos.CENTER);
        ColumnConstraints dismissColumn = new ColumnConstraints();
        dismissColumn.setMinWidth(Region.USE_PREF_SIZE);
        dismissColumn.setHalignment(HPos.CENTER);
        grid.getColumnConstraints().setAll(words, act, link, dismissColumn);
        int row = 0;
        for (Notice notice : shown) {
            noticeRow(grid, row++, notice);
        }
        return grid;
    }

    /** One notice as a row of the grid: its card, its words, and its actions in their columns. */
    private void noticeRow(GridPane grid, int row, Notice notice) {
        Region card = new Region();
        card.setStyle("-fx-background-radius: 6; -fx-background-color: " + switch (notice.urgency()) {
            case AT_RISK -> "#fdecea;";
            case ACTION -> "#fff3e0;";
            case NEWS -> "#eef3f8;";
        });
        card.setMaxWidth(Double.MAX_VALUE);
        card.setMaxHeight(Double.MAX_VALUE);
        grid.add(card, 0, row, 4, 1);

        SelectableText heading = SelectableText.of(notice.title());
        heading.setStyle("-fx-font-weight: bold;");
        VBox text = new VBox(3, heading);
        if (!notice.detail().isBlank()) {
            text.getChildren().add(muted(notice.detail()));
        }
        notice.freshness().ifPresent(freshness -> {
            SelectableText checked = muted(freshness.current() ? "checked " + WHEN.format(freshness.checkedAt())
                    : "from " + WHEN.format(freshness.checkedAt()) + ". The latest check failed.");
            checked.setStyle("-fx-font-size: 11px;" + MUTED);
            text.getChildren().add(checked);
        });
        text.setMaxWidth(Double.MAX_VALUE);
        GridPane.setValignment(text, VPos.CENTER);
        GridPane.setMargin(text, new Insets(10, 16, 10, 14));
        grid.add(text, 0, row);

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
            Button button = new Button(updating ? "Updating …" : upgrading ? "Upgrading …" : downloading ? "Downloading …"
                    : action.label());
            button.setDisable(updating || downloading || upgrading);
            button.setMinWidth(Region.USE_PREF_SIZE);
            button.setMaxWidth(Double.MAX_VALUE);
            button.setOnAction(event -> act(action));
            GridPane.setValignment(button, VPos.CENTER);
            GridPane.setMargin(button, new Insets(10, 6, 10, 0));
            grid.add(button, 1, row);
        });
        // A notice about one knowledge base opens it, beside whatever it offers to do (KEC, 2026-11-08).
        boolean showsAlready = notice.action().filter(NoticeAction.Show.class::isInstance).isPresent();
        notice.family().filter(family -> !showsAlready).ifPresent(family -> {
            Hyperlink show = new Hyperlink("Show");
            show.setMinWidth(Region.USE_PREF_SIZE);
            show.setOnAction(event -> select(family));
            GridPane.setValignment(show, VPos.CENTER);
            GridPane.setMargin(show, new Insets(10, 2, 10, 2));
            grid.add(show, 2, row);
        });
        Hyperlink dismiss = new Hyperlink("Dismiss");
        dismiss.setMinWidth(Region.USE_PREF_SIZE);
        dismiss.setOnAction(event -> {
            onDismiss.accept(notice.id());
            refreshNotices();
        });
        GridPane.setValignment(dismiss, VPos.CENTER);
        GridPane.setMargin(dismiss, new Insets(10, 10, 10, 2));
        grid.add(dismiss, 3, row);
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
        return familyOf(coordinate).map(KnowledgeBaseFamily::displayName)
                .or(() -> catalog.described().entrySet().stream()
                        .filter(entry -> entry.getKey().sameArtifact(coordinate))
                        .map(entry -> entry.getValue().descriptor().displayName()).findFirst())
                .orElse(coordinate.artifactId());
    }

    /** The family in {@code ~/Solor} with a copy of a knowledge base, matched by groupId and artifactId. */
    private Optional<KnowledgeBaseFamily> familyOf(Coordinate coordinate) {
        return families.stream().filter(family -> family.members().stream()
                .flatMap(member -> member.coordinate().stream()).anyMatch(coordinate::sameArtifact)).findFirst();
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
        private final SelectableText status = SelectableText.of("");
        private final Button cancel = new Button("Cancel");
        private final Hyperlink close = new Hyperlink("Close");

        TransferRow(Transfer transfer) {
            super(4);
            title.setStyle("-fx-font-weight: bold;");
            clock.setStyle("-fx-font-size: 11px;" + MUTED);
            status.setStyle(MUTED);
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
     * What Get from Nexus and New knowledge base do before their windows show, such as asking Nexus again.
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

    /** Opens the form that creates a knowledge base, asking Nexus again for the layers to pick from. */
    void openNewKnowledgeBase() {
        if (newKnowledgeBaseWindow == null) {
            newKnowledgeBaseWindow = new Stage();
            newKnowledgeBaseWindow.setTitle("New knowledge base");
            newKnowledgeBaseWindow.setScene(new Scene(newKnowledgeBaseForm, 760, 780));
            if (getScene() != null) {
                newKnowledgeBaseWindow.initOwner(getScene().getWindow());
            }
        }
        onBrowse.run();
        newKnowledgeBaseWindow.show();
        newKnowledgeBaseWindow.toFront();
    }

    private List<KnowledgeBaseFamily> newerBuilds() {
        return families.stream().filter(family -> status(family.name()).state() == BuildStatus.State.NEWER).toList();
    }

    private void updateBanner() {
        int newer = newerBuilds().size();
        updateBar.setVisible(newer > 0);
        updates.setText(newer == 1 ? "1 newer version on Nexus" : newer + " newer versions on Nexus");
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
        if ((showingNotices || showingKomets || showingProjects) && keep == null) {
            return;
        }
        Optional<KnowledgeBaseFamily> again = shown.stream().filter(family -> family.name().equals(keep)).findFirst()
                .or(() -> shown.stream().findFirst());
        again.ifPresentOrElse(family -> list.getSelectionModel().select(family), this::showDetail);
    }

    private Optional<KometInstallation> launcher(KnowledgeBase copy) {
        return LauncherChoice.choose(komets, copy, pins.of(KnowledgeBaseFamily.familyName(copy)));
    }

    private void showDetail() {
        if (showingProjects) {
            setCenter(projects);
            return;
        }
        if (showingKomets) {
            setCenter(kometsView);
            showKometsPane();
            return;
        }
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
            SelectableText none = SelectableText.of(families.isEmpty()
                    ? "No knowledge bases downloaded. Get one from Nexus to begin." : "Choose a knowledge base");
            none.setStyle(MUTED);
            detail.getChildren().add(none);
            return;
        }
        SelectableText title = SelectableText.of(chosen.get().displayName());
        title.setStyle("-fx-font-size: 20px; -fx-font-weight: bold;");
        HBox heading = new HBox(12, icon(chosen.get(), 48), title);
        heading.setAlignment(Pos.CENTER_LEFT);
        detail.getChildren().add(heading);
        showFamily(chosen.get());
    }

    private void showUnmanaged(KnowledgeBase entry) {
        SelectableText title = SelectableText.of(entry.name());
        title.setStyle("-fx-font-size: 20px; -fx-font-weight: bold;");
        detail.getChildren().add(title);
        detail.getChildren().add(muted(entry.kind().label() + "  ·  " + DashboardCommand.size(entry.sizeBytes())
                + "  ·  " + WHEN.format(entry.recency())));
        entry.coordinate().ifPresent(coordinate -> detail.getChildren().add(muted(coordinate.toString())));
        SelectableText why = SelectableText.of("Not managed: " + String.join("; ", entry.descriptor().problems()) + ".");
        why.setStyle(ATTENTION);
        detail.getChildren().add(why);
        detail.getChildren().add(muted("This folder does not include the description the dashboard needs: the"
                + " knowledge base's name, version and icon. Newer versions include it, so download one to manage this"
                + " knowledge base. You can still show this folder in " + (OperatingSystem.current() == OperatingSystem.WINDOWS ? "File Explorer" : "the Finder")
                + " or move it to the " + Finder.trashName() + "."));
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
            detail.getChildren().add(muted("Only protobuf files. Komet cannot open these directly. It loads them into a knowledge base."));
            detail.getChildren().add(section(KnowledgeBaseTree.PROTOBUFS, changeSets, true));
            relations(family).ifPresent(detail.getChildren()::add);
            return;
        }
        List<KnowledgeBase> folders = family.members().stream().filter(member -> member.kind().opensInKomet()).toList();
        // One folder is chosen, and every folder action acts on it (KEC, 2026-11-08): the newest until another is.
        KnowledgeBase copy = folders.stream().filter(member -> member.name().equals(chosenFolders.get(family.name())))
                .findFirst().orElse(latest.get());
        BuildStatus status = status(family.name());
        latest.get().coordinate().ifPresent(coordinate -> {
            long bytes = folders.stream().mapToLong(KnowledgeBase::sizeBytes).sum();
            // The knowledge base by groupId and artifactId (KEC, 2026-11-10): the list names folders, the detail
            // says which artifact they are.
            SelectableText about = muted(coordinate.groupId() + ":" + coordinate.artifactId() + "  ·  "
                    + (folders.size() == 1 ? "1 folder"
                    : folders.size() + " folders") + ", " + DashboardCommand.size(bytes) + nexusNote(status));
            if (status.state() == BuildStatus.State.CURRENT) {
                about.setStyle("-fx-text-fill: #2e7d32;");
            }
            Tooltip.install(about, new Tooltip(coordinate.toString() + (status.detail().isEmpty() ? "" : "\n" + status.detail())));
            detail.getChildren().add(about);
        });

        if (status.state() == BuildStatus.State.NEWER && status.newest().isPresent()) {
            KnowledgeBase identified = family.latestIdentified().orElse(copy);
            long behind = folders.stream().filter(folder -> upgradeOf(folder).isPresent()).count();
            String which = folders.size() == 1 ? "Your folder has an older version."
                    : behind == folders.size() ? (folders.size() == 2 ? "Both your folders have an older version."
                    : "All " + folders.size() + " of your folders have an older version.")
                    : behind + " of your " + folders.size() + " folders " + (behind == 1 ? "has" : "have") + " an older version.";
            SelectableText newer = SelectableText.of("A newer version is on Nexus, published "
                    + WHEN.format(status.newest().get().updated()) + ". " + which);
            String classifier = identified.kind().classifier();
            boolean downloading = identified.coordinate()
                    .map(coordinate -> transferring(downloadKey(coordinate, classifier))).orElse(false);
            Button copyBeside = new Button(downloading ? "Downloading …" : "Install a new copy");
            copyBeside.setDisable(downloading);
            copyBeside.setMinWidth(Region.USE_PREF_SIZE);
            copyBeside.setOnAction(event -> identified.coordinate()
                    .ifPresent(coordinate -> onDownload.accept(coordinate, classifier)));
            HBox.setHgrow(newer, Priority.ALWAYS);
            HBox row = new HBox(10, newer, copyBeside);
            row.setAlignment(Pos.CENTER_LEFT);
            VBox box = new VBox(4, row, muted("Upgrade, on a folder below, brings that folder to it and keeps its change"
                    + " sets, preferences and repositories. Install a new copy puts it in a folder of its own."));
            box.setPadding(new Insets(8, 10, 8, 10));
            box.setStyle("-fx-background-color: #fff3e0; -fx-background-radius: 6;");
            detail.getChildren().add(box);
        }

        if (folders.size() > 1) {
            detail.getChildren().add(foldersTable(family, folders, copy));
        }
        detail.getChildren().add(folderPanel(copy));
        List<Node> inside = repositories.nodes(sync(copy.path()));
        if (!inside.isEmpty()) {
            SelectableText heading = SelectableText.of("Repositories");
            heading.setStyle("-fx-font-weight: bold; -fx-padding: 8 0 0 0;");
            detail.getChildren().add(heading);
            detail.getChildren().addAll(inside);
        }

        if (!changeSets.isEmpty()) {
            detail.getChildren().add(section(KnowledgeBaseTree.PROTOBUFS, changeSets, false));
        }
        relations(family).ifPresent(detail.getChildren()::add);
    }

    /**
     * Every folder of a knowledge base, the chosen one selected: choosing a row makes it the folder the actions below
     * act on, and double-clicking one opens it.
     */
    private Node foldersTable(KnowledgeBaseFamily family, List<KnowledgeBase> folders, KnowledgeBase chosen) {
        TableView<KnowledgeBase> table = copiesTable(folders);
        TableColumn<KnowledgeBase, String> version = column("Version", 120, this::standing);
        version.setMinWidth(100);
        table.getColumns().add(version);
        table.getSelectionModel().select(chosen);
        table.getSelectionModel().selectedItemProperty().addListener((observable, was, now) -> {
            if (now != null && !now.name().equals(chosenFolders.get(family.name()))) {
                chosenFolders.put(family.name(), now.name());
                showDetail();
            }
        });
        long bytes = folders.stream().mapToLong(KnowledgeBase::sizeBytes).sum();
        TitledPane pane = new TitledPane(FOLDERS + " (" + folders.size() + ")  ·  " + DashboardCommand.size(bytes), table);
        pane.setAnimated(false);
        pane.setExpanded(true);
        return new VBox(4, pane, muted("Each folder is a separate copy with its own change sets. Choose one to open,"
                + " copy, upgrade or move it."));
    }

    /** The chosen folder: where it is, what it is, and everything that can be done with it. */
    private Node folderPanel(KnowledgeBase copy) {
        VBox panel = new VBox(10);
        SelectableText where = SelectableText.of("Folder  " + copy.whereShown());
        where.setStyle("-fx-font-weight: bold;");
        panel.getChildren().add(where);
        panel.getChildren().add(muted(copy.kind().label() + "  ·  " + DashboardCommand.size(copy.sizeBytes())
                + "  ·  " + WHEN.format(copy.recency())
                + copy.resolvedVersion().map(version -> "  ·  " + version).orElse("")
                + (standing(copy).isEmpty() ? "" : "  ·  " + standing(copy))));

        Optional<KometInstallation> komet = launcher(copy);
        Button open = new Button("Open");
        open.setDefaultButton(true);
        open.setStyle("-fx-font-size: 14px; -fx-padding: 6 28 6 28;");
        open.setDisable(komet.isEmpty());
        open.setOnAction(event -> komet.ifPresent(chosen -> onOpen.accept(chosen, copy)));
        HBox actions = new HBox(8, open);
        String pinKey = KnowledgeBaseFamily.familyName(copy);
        Optional<String> pin = pins.of(pinKey);
        List<KometInstallation> readers = komets.stream().filter(candidate -> LauncherChoice.reads(candidate, copy)).toList();
        if (!readers.isEmpty()) {
            // Which Komet it opens in, chosen here for this knowledge base (KEC, 2026-11-08): the latest, or a pin.
            ChoiceBox<KometChoice> in = new ChoiceBox<>();
            in.getItems().add(new KometChoice("Latest Komet (" + readers.getFirst().version() + ")", Optional.empty()));
            readers.forEach(reader -> in.getItems().add(new KometChoice("Komet " + reader.version(), Optional.of(reader.version()))));
            pin.filter(version -> readers.stream().noneMatch(reader -> reader.version().equals(version)))
                    .ifPresent(missing -> in.getItems().add(new KometChoice("Komet " + missing + ", not installed",
                            Optional.of(missing))));
            in.getSelectionModel().select(in.getItems().stream().filter(choice -> choice.version().equals(pin))
                    .findFirst().orElse(in.getItems().getFirst()));
            in.setConverter(new javafx.util.StringConverter<>() {
                @Override
                public String toString(KometChoice choice) {
                    return choice == null ? "" : choice.label();
                }

                @Override
                public KometChoice fromString(String text) {
                    return null;
                }
            });
            in.setOnAction(event -> {
                KometChoice chosen = in.getValue();
                if (chosen != null && !chosen.version().equals(pins.of(pinKey))) {
                    pins.pin(pinKey, chosen.version());
                    showDetail();
                }
            });
            Tooltip.install(in, new Tooltip("The Komet this knowledge base opens in, whichever folder. Latest follows"
                    + " each new Komet you install; a version stays until you change it."));
            Label inLabel = new Label("in");
            inLabel.setStyle(MUTED);
            actions.getChildren().addAll(inLabel, in);
        }
        actions.setAlignment(Pos.CENTER_LEFT);
        panel.getChildren().add(actions);
        pin.filter(version -> readers.stream().noneMatch(reader -> reader.version().equals(version)))
                .ifPresent(missing -> {
                    SelectableText gone = SelectableText.of("This knowledge base is set to open in Komet " + missing
                            + ", which is not installed. It opens in the latest Komet until you choose another.");
                    gone.setStyle(PROBLEM);
                    panel.getChildren().add(gone);
                });
        if (StoreLock.openInKomet(copy.path())) {
            panel.getChildren().add(muted("Open in a Komet now."));
        }
        if (komet.isEmpty()) {
            Optional<KometRelease> fix = kometRelease.filter(release -> LauncherChoice.reads(release.version(), copy));
            SelectableText need = SelectableText.of(LauncherChoice.requirement(copy).map(requirement -> "Needs " + requirement
                    + (fix.isPresent() ? "." : ". No installed Komet can open it, and no newer Komet was found."))
                    .orElse(fix.isPresent() ? "No Komet is installed." : "No Komet is installed, and none was found to install."));
            need.setStyle(PROBLEM);
            panel.getChildren().add(need);
            fix.ifPresent(release -> {
                Button install = new Button(installing ? "Installing Komet " + release.version() + " …"
                        : "Install Komet " + release.version());
                install.setDisable(installing);
                install.setOnAction(event -> onInstallKomet.accept(release));
                SelectableText from = muted("from " + release.source().label()
                        + (release.sizeBytes() > 0 ? ", " + DashboardCommand.size(release.sizeBytes()) : "")
                        + ". It installs next to the Komets you have.");
                HBox row = new HBox(10, install, from);
                row.setAlignment(Pos.CENTER_LEFT);
                panel.getChildren().add(row);
            });
        }
        komet.flatMap(chosen -> heapPlanner.apply(chosen, copy)).ifPresent(plan -> {
            Hyperlink change = new Hyperlink("Change…");
            change.setOnAction(event -> onEditHeap.accept(copy));
            HBox memory = new HBox(6, HeapView.line(plan, komet.get(), copy), change);
            memory.setAlignment(Pos.CENTER_LEFT);
            panel.getChildren().add(memory);
        });
        panel.getChildren().add(folderActions(copy));
        if (upgradeOf(copy).isPresent() && LauncherChoice.chooseForLoad(komets, copy).isEmpty()) {
            panel.getChildren().add(muted("To upgrade this folder you need Komet " + LauncherChoice.LOAD_CHANGE_SETS_SINCE
                    + " or later. Until then, Install a new copy puts the new version in a separate folder."));
        }
        panel.setPadding(new Insets(12, 14, 14, 14));
        panel.setStyle("-fx-background-color: #f7f8fa; -fx-background-radius: 8; -fx-border-color: #e1e4e8;"
                + " -fx-border-radius: 8;");
        return panel;
    }

    /**
     * What a knowledge base was built from and what is built from it (decision 18): the inputs its newest
     * descriptor records, and every knowledge base here or on Nexus whose descriptor records it. Each one
     * the dashboard knows is a link: to its line here, else to it in the Nexus window. Beneath each knowledge
     * base it was built from are the source releases that one's own descriptor records.
     *
     * <p>A store loaded from its knowledge base's own protobuf may record that protobuf alone (stores
     * assembled before kb-assemble carried the protobuf's inputs into them). When the folder is the newest
     * version on Nexus, the descriptor Nexus has beside it is of the same version, and says what it was
     * built from.
     */
    private Optional<Node> relations(KnowledgeBaseFamily family) {
        Optional<KnowledgeBaseDescriptor> descriptor = family.members().stream()
                .flatMap(member -> member.descriptor().descriptor().stream()).findFirst();
        if (descriptor.isEmpty()) {
            return Optional.empty();
        }
        Coordinate self = descriptor.get().coordinate();
        Map<Coordinate, NexusCatalog.Described> described = catalog.described();
        List<KnowledgeBaseDescriptor.Input> inputs = descriptor.get().inputs().stream()
                .filter(input -> !input.of(self)).toList();
        if (inputs.isEmpty() && status(family.name()).state() == BuildStatus.State.CURRENT) {
            inputs = Optional.ofNullable(described.get(self)).map(NexusCatalog.Described::descriptor).stream()
                    .flatMap(onNexus -> onNexus.inputs().stream()).filter(input -> !input.of(self)).toList();
        }
        Map<String, Coordinate> including = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        families.stream().filter(other -> other != family)
                .forEach(other -> other.members().stream().flatMap(member -> member.descriptor().descriptor().stream())
                        .filter(found -> found.includes(self)).findFirst()
                        .ifPresent(found -> including.putIfAbsent(found.displayName(), found.coordinate())));
        described.forEach((coordinate, entry) -> {
            if (!coordinate.sameArtifact(self) && entry.descriptor().includes(self)) {
                including.putIfAbsent(entry.descriptor().displayName(), coordinate);
            }
        });
        if (inputs.isEmpty() && including.isEmpty()) {
            return Optional.empty();
        }
        VBox box = new VBox(6);
        if (!inputs.isEmpty()) {
            box.getChildren().add(heading(KnowledgeBaseTree.BUILT_FROM));
            inputs.forEach(input -> {
                KnowledgeBaseTree.Node node = KnowledgeBaseTree.input(input, this::describedHereOrOnNexus);
                box.getChildren().add(relationEntry(node.label(), node.knowledgeBase(), Optional.of(input), 0));
                node.children().forEach(source -> box.getChildren().add(
                        relationEntry(source.label(), source.knowledgeBase(), source.input(), 1)));
            });
        }
        if (!including.isEmpty()) {
            Node heading = heading(KnowledgeBaseTree.INCLUDED_IN);
            if (!inputs.isEmpty()) {
                VBox.setMargin(heading, new Insets(6, 0, 0, 0));
            }
            box.getChildren().add(heading);
            including.forEach((name, coordinate) -> box.getChildren().add(
                    relationEntry(name, Optional.of(coordinate), Optional.empty(), 0)));
        }
        TitledPane pane = new TitledPane("Knowledge bases", box);
        pane.setAnimated(false);
        pane.setExpanded(true);
        return Optional.of(pane);
    }

    /**
     * One knowledge base or source a relation names, on two lines: its name, a link when the dashboard can
     * show it, with the version beside it; beneath, its group and how the assembly took it. Each line is
     * short, so neither wraps inside an identifier.
     */
    private Node relationEntry(String named, Optional<Coordinate> knowledgeBase,
                               Optional<KnowledgeBaseDescriptor.Input> input, int depth) {
        HBox first = new HBox(10, relationName(named, knowledgeBase));
        input.flatMap(KnowledgeBaseDescriptor.Input::version).ifPresent(version -> first.getChildren().add(muted(version)));
        first.setAlignment(Pos.BASELINE_LEFT);
        List<String> about = new java.util.ArrayList<>();
        knowledgeBase.map(Coordinate::groupId).ifPresent(about::add);
        input.map(KnowledgeBaseTree::describe).ifPresent(about::add);
        VBox entry = new VBox(1, first);
        if (!about.isEmpty()) {
            entry.getChildren().add(muted(String.join("  ·  ", about)));
        }
        VBox.setMargin(entry, new Insets(0, 0, 0, 18 * depth));
        return entry;
    }

    /**
     * The descriptor of the knowledge base an input is an artifact of: a folder's in {@code ~/Solor}, of
     * the version the input names when there is one, else the one Nexus has.
     */
    private Optional<KnowledgeBaseDescriptor> describedHereOrOnNexus(KnowledgeBaseDescriptor.Input input) {
        List<KnowledgeBaseDescriptor> here = families.stream().flatMap(family -> family.members().stream())
                .flatMap(member -> member.descriptor().descriptor().stream())
                .filter(found -> input.of(found.coordinate())).toList();
        return here.stream().filter(found -> !KnowledgeBaseTree.sources(input, found).isEmpty()).findFirst()
                .or(() -> catalog.described().entrySet().stream().filter(entry -> input.of(entry.getKey()))
                        .findFirst().map(entry -> entry.getValue().descriptor()))
                .or(() -> here.stream().findFirst());
    }

    /**
     * The name of one knowledge base a relation names, by its name here when it is here: a link when the
     * dashboard can show it. It is the same knowledge base only when the groupId matches as well as the
     * artifactId.
     */
    private Node relationName(String named, Optional<Coordinate> knowledgeBase) {
        Optional<KnowledgeBaseFamily> here = knowledgeBase.flatMap(this::familyOf);
        String name = here.map(KnowledgeBaseFamily::displayName).orElse(named);
        Optional<Coordinate> onNexus = knowledgeBase.flatMap(coordinate -> catalog.described().keySet().stream()
                .filter(coordinate::sameArtifact).findFirst());
        Node label;
        if (here.isPresent()) {
            Hyperlink link = relationLink(name);
            link.setOnAction(event -> select(here.get().name()));
            Tooltip.install(link, new Tooltip("In ~/Solor: show it"));
            label = link;
        } else if (onNexus.isPresent()) {
            Hyperlink link = relationLink(name);
            link.setOnAction(event -> {
                openBrowser();
                browser.select(onNexus.get());
            });
            Tooltip.install(link, new Tooltip("Not downloaded. Show it in the Nexus window."));
            label = link;
        } else {
            label = SelectableText.of(name);
        }
        return label;
    }

    /** A link without a link's own padding, so it lines up with the line beneath it. */
    private static Hyperlink relationLink(String name) {
        Hyperlink link = new Hyperlink(name);
        link.setPadding(Insets.EMPTY);
        return link;
    }

    private static SelectableText heading(String text) {
        SelectableText label = SelectableText.of(text);
        label.setStyle("-fx-font-weight: bold;");
        return label;
    }


    private void confirmUpgrade(NoticeAction.Upgrade action) {
        KnowledgeBase store = action.store();
        if (refusedWhileOpen("Upgrade " + store.displayName(), List.of(store))) {
            return;
        }
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
        return "Upgrade " + store.displayName() + " to the latest version?";
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
        String assembled = store.assembledAt().map(at -> " The version in it was made on " + DAY.format(at) + " at "
                + TIME.format(at) + ".").orElse("");
        String name = Names.of(store.name());
        return "This upgrades the folder " + store.whereShown() + "." + assembled + "\n\n"
                + "1. The latest version downloads into a new folder, "
                + Names.of(StoreUpgrade.workFolder(store.path()).getFileName()) + ".\n"
                + "2. Your change sets, preferences and repositories move into it.\n"
                + "3. Komet " + validator + " opens it in the background to check that every change set loads.\n"
                + "4. If they all load, the new folder is renamed " + name + " and the old version goes to the "
                + Finder.trashName() + ". You can restore it from the " + Finder.trashName() + " if you need it.\n\n"
                + "If the check fails, nothing changes and " + name + " stays as it is. Your other folders of "
                + store.displayName() + " are not changed.";
    }

    /**
     * Says so, before a dialog asks anything, when a Komet has one of the stores open: its search index's lock is
     * held ({@link StoreLock}), so the person need not be asked to quit Komets that may not be running.
     */
    private boolean refusedWhileOpen(String title, List<KnowledgeBase> stores) {
        List<String> open = stores.stream().filter(store -> StoreLock.openInKomet(store.path()))
                .map(StoreLock::refusal).toList();
        if (open.isEmpty()) {
            return false;
        }
        showRefusals(title, open);
        return true;
    }

    /** Asks for the new folder's name, then copies the store into it. */
    private void askCopy(KnowledgeBase store) {
        if (refusedWhileOpen("Copy " + store.name(), List.of(store))) {
            return;
        }
        javafx.scene.control.Dialog<String> dialog = copyDialog(store);
        if (getScene() != null) {
            dialog.initOwner(getScene().getWindow());
        }
        dialog.showAndWait().filter(text -> StoreCopy.nameProblem(store.path(), text).isEmpty())
                .ifPresent(text -> onCopy.accept(store, text));
    }

    /**
     * The dialog Copy to a new folder asks with: what a copy is, and the new folder's name, ready to type over.
     * A plain dialog with a field of its own: a TextInputDialog puts its editor back into its own grid whenever its
     * content text changes, which took the field out of this layout.
     *
     * @param store the folder to copy
     * @return the dialog, answering the name, or nothing when cancelled
     */
    static javafx.scene.control.Dialog<String> copyDialog(KnowledgeBase store) {
        javafx.scene.control.Dialog<String> dialog = new javafx.scene.control.Dialog<>();
        dialog.setTitle("Copy to a new folder");
        dialog.setHeaderText("Copy " + Names.of(store.name()) + " to a new folder?");
        ButtonType copy = new ButtonType("Copy", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().setAll(ButtonType.CANCEL, copy);
        SelectableText about = SelectableText.of(copyText(store), SelectableText.DIALOG_WIDTH);
        SelectableText problem = SelectableText.of("", SelectableText.DIALOG_WIDTH);
        problem.setStyle(PROBLEM);
        javafx.scene.control.TextField name = new javafx.scene.control.TextField(StoreCopy.suggestedName(store.path()));
        name.setPrefColumnCount(28);
        Node ok = dialog.getDialogPane().lookupButton(copy);
        Runnable check = () -> {
            Optional<String> wrong = StoreCopy.nameProblem(store.path(), name.getText());
            problem.setText(wrong.orElse(""));
            ok.setDisable(wrong.isPresent());
        };
        name.textProperty().addListener((observable, was, now) -> check.run());
        check.run();
        Label nameLabel = new Label("New folder name");
        nameLabel.setMinWidth(Region.USE_PREF_SIZE);
        HBox field = new HBox(8, nameLabel, name);
        field.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(name, Priority.ALWAYS);
        dialog.getDialogPane().setContent(new VBox(10, about, field, problem));
        dialog.setResultConverter(button -> button == copy ? name.getText().strip() : null);
        SelectableText.in(dialog);
        // The name is ready to type over, as the Finder offers a new name.
        dialog.setOnShown(event -> {
            name.requestFocus();
            name.selectAll();
        });
        return dialog;
    }

    /**
     * What Copy to a new folder does.
     *
     * @param store the copy to copy
     * @return the text
     */
    static String copyText(KnowledgeBase store) {
        return "This copies everything in the folder " + store.whereShown() + " into a new folder next to it: the"
                + " knowledge base, your change sets, preferences and repositories. After that the two folders are"
                + " separate. Each keeps its own change sets and is upgraded on its own.";
    }

    private void confirmReinstall(NoticeAction.Reinstall action) {
        KnowledgeBase store = action.store();
        if (refusedWhileOpen("Reinstall " + store.displayName(), List.of(store))) {
            return;
        }
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Reinstall");
        alert.setHeaderText("Reinstall " + store.displayName() + " from scratch?");
        alert.setContentText("This moves the folder " + store.whereShown() + " to the " + Finder.trashName()
                + " with everything in it, including your change sets, preferences and repositories. It takes up "
                + DashboardCommand.size(store.sizeBytes()) + ".\n\nThen the latest version downloads into a new folder"
                + " with the same name. You can restore the old folder from the " + Finder.trashName()
                + " if you need it.");
        alert.getButtonTypes().setAll(ButtonType.CANCEL, ButtonType.OK);
        if (getScene() != null) {
            alert.initOwner(getScene().getWindow());
        }
        SelectableText.in(alert);
        alert.showAndWait().filter(ButtonType.OK::equals).ifPresent(ok -> onReinstall.accept(action));
    }


    private static String nexusNote(BuildStatus status) {
        return switch (status.state()) {
            case CURRENT -> "  ·  latest version";
            case NEWER -> "  ·  newer version on Nexus";
            case NOT_ON_NEXUS, UNKNOWN, ERROR -> "  ·  " + status.state().label();
            case UNCHECKED, UNIDENTIFIED -> "";
        };
    }

    private Node section(String name, List<KnowledgeBase> copies, boolean expanded) {
        long bytes = copies.stream().mapToLong(KnowledgeBase::sizeBytes).sum();
        String heading = name + " (" + copies.size() + ")  ·  " + DashboardCommand.size(bytes);
        TitledPane pane = new TitledPane(heading, copiesTable(copies));
        pane.setExpanded(expanded);
        pane.setAnimated(false);
        return pane;
    }



    /** What a copy that is not behind says in the Nexus column. */
    private String standing(KnowledgeBase copy) {
        return newestFor(copy).map(newest -> NewerBuildCheck.compare(copy, newest).state())
                .map(state -> switch (state) {
                    case CURRENT -> "latest version";
                    case NEWER -> "older version";
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
        // A size or a date cut short says nothing; the folder name gives way first.
        table.getColumns().get(2).setMinWidth(72);
        table.getColumns().get(3).setMinWidth(122);
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

    /** What can be done to the folder the detail shows, as labelled buttons on its own line. */
    private HBox folderActions(KnowledgeBase copy) {
        boolean busy = transferring(upgradeKey(copy.path()));
        Button reveal = new Button("Show in " + Finder.name());
        reveal.setOnAction(event -> onReveal.accept(copy.path()));
        Button duplicate = new Button("Copy to a new folder…");
        duplicate.setDisable(busy);
        duplicate.setOnAction(event -> askCopy(copy));
        HBox row = new HBox(8, reveal, duplicate);
        upgradeOf(copy).ifPresent(upgrade -> {
            Button button = new Button(upgradeBusy(copy) ? "Upgrading …" : "Upgrade…");
            button.setDisable(upgradeBusy(copy) || LauncherChoice.chooseForLoad(komets, copy).isEmpty());
            button.setOnAction(event -> confirmUpgrade(upgrade));
            if (LauncherChoice.chooseForLoad(komets, copy).isEmpty()) {
                Tooltip.install(button, new Tooltip("Upgrading needs Komet " + LauncherChoice.LOAD_CHANGE_SETS_SINCE
                        + " or later."));
            }
            row.getChildren().add(button);
        });
        copy.coordinate().filter(coordinate -> copy.identified()).ifPresent(coordinate -> {
            Button reinstall = new Button("Reinstall…");
            reinstall.setDisable(busy || transferring(downloadKey(coordinate, copy.kind().classifier())));
            reinstall.setOnAction(event -> confirmReinstall(new NoticeAction.Reinstall(copy, coordinate, copy.kind().classifier())));
            row.getChildren().add(reinstall);
        });
        Button trash = new Button("Move to " + Finder.trashName() + "…");
        trash.setDisable(busy);
        trash.setOnAction(event -> confirmTrash(List.of(copy)));
        row.getChildren().add(trash);
        row.getChildren().forEach(node -> ((Region) node).setMinWidth(Region.USE_PREF_SIZE));
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
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
        MenuItem trash = new MenuItem("Move to " + Finder.trashName() + "…");
        trash.setDisable(transferring(upgradeKey(copy.path())));
        trash.setOnAction(event -> confirmTrash(List.of(copy)));
        items.add(trash);
        return items;
    }

    private void confirmTrash(List<KnowledgeBase> copies) {
        if (refusedWhileOpen("Move to " + Finder.trashName(), copies)) {
            return;
        }
        long bytes = copies.stream().mapToLong(KnowledgeBase::sizeBytes).sum();
        // What would go to the Trash that is saved nowhere else, read from each folder's git repositories
        // (KEC, 2026-11-08), so the dialog says what is at stake rather than that something may be.
        Map<KnowledgeBase, UnsavedWork.Report> reports = new java.util.LinkedHashMap<>();
        copies.forEach(copy -> reports.put(copy, unsavedWork.apply(copy)));
        boolean unsaved = reports.values().stream().anyMatch(report -> !report.findings().isEmpty());
        Alert alert = new Alert(unsaved ? Alert.AlertType.WARNING : Alert.AlertType.CONFIRMATION);
        alert.setTitle("Move to " + Finder.trashName());
        alert.setHeaderText("Move " + (copies.size() == 1 ? Names.of(copies.getFirst().name()) : copies.size() + " copies")
                + " (" + DashboardCommand.size(bytes) + ") to the " + Finder.trashName() + "?");
        alert.setContentText(trashText(reports));
        ButtonType move = new ButtonType("Move to " + Finder.trashName(), ButtonBar.ButtonData.OK_DONE);
        alert.getButtonTypes().setAll(ButtonType.CANCEL, move);
        if (unsaved) {
            // With work at stake, Return cancels.
            ((Button) alert.getDialogPane().lookupButton(move)).setDefaultButton(false);
            ((Button) alert.getDialogPane().lookupButton(ButtonType.CANCEL)).setDefaultButton(true);
        }
        if (getScene() != null) {
            alert.initOwner(getScene().getWindow());
        }
        SelectableText.in(alert);
        alert.showAndWait().filter(move::equals).ifPresent(ok -> onTrash.accept(copies));
    }

    /**
     * What Move to Trash says: for each folder, what in it is saved nowhere else, or that everything is.
     *
     * @param reports each folder's unsaved work
     * @return the text
     */
    static String trashText(Map<KnowledgeBase, UnsavedWork.Report> reports) {
        StringBuilder text = new StringBuilder();
        boolean several = reports.size() > 1;
        boolean unsaved = false;
        for (Map.Entry<KnowledgeBase, UnsavedWork.Report> entry : reports.entrySet()) {
            UnsavedWork.Report report = entry.getValue();
            String folder = Names.of(entry.getKey().name());
            if (!report.findings().isEmpty()) {
                unsaved = true;
                text.append(several ? folder + " has work that is saved nowhere else:\n"
                        : "This folder has work that is saved nowhere else:\n");
                report.findings().forEach(finding -> text.append("• ").append(finding).append('\n'));
                text.append('\n');
            } else if (several) {
                text.append(folder).append(report.repositories() > 0
                        ? ": every change is committed and pushed.\n" : ": no change sets of yours.\n");
            } else {
                text.append(report.repositories() > 0
                        ? "Every change in its git repositories is committed and pushed, so nothing is lost.\n\n"
                        : "It has no change sets of yours.\n\n");
            }
        }
        if (several && !unsaved) {
            text.append('\n');
        }
        if (unsaved) {
            text.append("That work goes to the ").append(Finder.trashName()).append(" with the folder. You can restore it")
                    .append(" from the ").append(Finder.trashName()).append(" if you need it.\n\n");
        }
        text.append("The disk space is freed when you empty the ").append(Finder.trashName()).append('.');
        return text.toString();
    }

    /** A knowledge base's icon, read once per refresh. */
    private Node icon(KnowledgeBaseFamily family, double size) {
        return KnowledgeBaseIcon.of(family.icon().map(file -> icons.computeIfAbsent(file, KnowledgeBaseIcon::load)), size);
    }

    private static SelectableText muted(String text) {
        SelectableText area = SelectableText.of(text);
        area.setStyle(MUTED);
        return area;
    }

    /** One choice of the Komet a knowledge base opens in: the latest, or a pinned version. */
    private record KometChoice(String label, Optional<String> version) {
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
                mark.setText("▲ newer version");
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
