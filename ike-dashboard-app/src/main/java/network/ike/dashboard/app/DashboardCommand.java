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
 * projects                              the projects that build knowledge bases, in ~/knowledge-bases
 * changes-remote-check &lt;folder&gt; &lt;git-url&gt;  what changes-remote would do for a knowledge base's change sets
 * changes-remote &lt;folder&gt; &lt;git-url&gt;      give the change sets of a folder in ~/Solor their remote, with git:
 *                                       make the repository when there is none, set origin, and push only
 *                                       what adds to the remote
 * preferences-remote-check &lt;folder&gt; &lt;git-url&gt;  what preferences-remote would do
 * preferences-remote &lt;folder&gt; &lt;git-url&gt;  give your preferences for a folder in ~/Solor a remote of their own,
 *                                       which should be private: commit them on a branch named for the
 *                                       knowledge base, set origin, and push
 * preferences-send &lt;folder&gt;             commit your preferences for a folder in ~/Solor and push them to their remote
 * ws-new &lt;name&gt; [groupId]                an empty working set in ~/knowledge-bases, to build projects together
 * ws-add &lt;working-set&gt; &lt;project&gt;        move a project into a working set, which then lists it as a member
 * ws-remove &lt;working-set&gt; &lt;member&gt;      stop listing a member, and move it back out into ~/knowledge-bases
 * kb-remote-draft &lt;project&gt; &lt;git-url&gt;   what kb-remote would do; the remote is asked, nothing is changed
 * kb-remote &lt;project&gt; &lt;git-url&gt;         give a project its remote: origin, the POM's scm, a commit, the
 *                                       push; the project by its name in ~/knowledge-bases, or its folder
 * kb-draft &lt;name&gt; [property=value …]    what kb-new would create, each layer pinned; nothing is written
 * kb-new &lt;name&gt; [property=value …]      create a knowledge-base project in a local git repository:
 *                                       layers=&lt;g:a:zip:classifier:v,…&gt; in load order, folder=&lt;path&gt;
 *                                       (default: ~/knowledge-bases/&lt;name&gt;), and any other property of
 *                                       ike:kb-create-publish, such as kind, collection, rocks; a name
 *                                       already used by a project, in ~/Solor or on Nexus is refused,
 *                                       with one that is free
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
            case "projects" -> projects(out, settings);
            case "preferences-send" -> {
                if (args.length != 2) {
                    throw new IllegalArgumentException("preferences-send <folder>");
                }
                Path named = settings.solor().resolve(args[1]);
                Path store = java.nio.file.Files.isDirectory(named) ? named : Path.of(args[1]).toAbsolutePath().normalize();
                out.println(RepositoryRemote.send(RepositorySync.Git.find().orElseThrow(() -> new IllegalStateException("git is not installed")),
                        store.resolve(RepositorySync.PREFERENCES)));
            }
            case "changes-remote-check", "changes-remote", "preferences-remote-check", "preferences-remote" -> {
                if (args.length != 3) {
                    throw new IllegalArgumentException(command + " <folder> <git-url>");
                }
                repositoryRemote(out, settings, command.startsWith("preferences") ? RepositorySync.PREFERENCES : RepositorySync.CHANGE_SETS,
                        !command.endsWith("-check"), args[1], args[2]);
            }
            case "ws-new", "ws-add", "ws-remove" -> workingSet(out, settings, command, List.of(args).subList(1, args.length));
            case "kb-remote-draft", "kb-remote" -> {
                if (args.length != 3) {
                    throw new IllegalArgumentException(command + " <project> <git-url>");
                }
                remote(out, settings, command.equals("kb-remote"), args[1], args[2]);
            }
            case "kb-draft", "kb-new" -> {
                if (args.length < 2) {
                    throw new IllegalArgumentException(command + " <name> [property=value …], such as layers=<g:a:zip:classifier:v,…>");
                }
                newKnowledgeBase(out, settings, command.equals("kb-new"),
                        KnowledgeBaseProject.parse(args[1], List.of(args).subList(2, args.length), settings.projects()));
            }
            case "dashboard-latest" -> SelfUpdate.newest(RepositoryHttpClients.newClient(),
                            args.length > 1 ? args[1] : KometReleases.PUBLIC_URL, KometReleases.platformClassifier())
                    .ifPresentOrElse(release -> out.printf("IKE Dashboard %s: %s (%s)%n", release.version(), release.uri(),
                                    release.sizeBytes() > 0 ? size(release.sizeBytes()) : "size unknown"),
                            () -> out.println("No IKE Dashboard build found"));
            case "self-update" -> selfUpdate(out, settings, args.length > 1 ? Optional.of(Path.of(args[1])) : Optional.empty());
            default -> throw new IllegalArgumentException("Unknown command " + command
                    + "; use list, check, notices, komets, heap, download, projects, kb-draft, kb-new, kb-remote-draft, kb-remote,"
                    + " ws-new, ws-add, ws-remove, changes-remote-check, changes-remote, preferences-remote-check,"
                    + " preferences-remote, preferences-send,"
                    + " dashboard-latest or self-update");
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
        java.util.prefs.Preferences heap = DashboardPreferences.current().node(DashboardPreferences.Node.HEAP);
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
                    HeapSizing.override(DashboardPreferences.current().node(DashboardPreferences.Node.HEAP_STORES), copy), komet.get(), machine);
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
        Map<Path, List<RepositorySync.Status>> sync = new HashMap<>();
        Optional<RepositorySync.Git> git = RepositorySync.Git.find();
        Instant now = Instant.now();
        for (KnowledgeBaseFamily family : families) {
            family.latestIdentified().map(newer::check).ifPresent(status -> statuses.put(family.name(), status));
            Optional<KnowledgeBase> latest = family.latest().filter(copy -> copy.kind().opensInKomet());
            if (latest.isPresent()) {
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
                Optional.of(new Notice.Freshness(now, true)), komets, release, catalog, sync,
                settings.collections(), dashboard, now),
                NoticeDismissals.current()::dismissed);
        out.printf("%d notices%s%n", notices.size(), git.isPresent() ? "" : " (no git found: repository sync not checked)");
        for (Notice notice : notices) {
            out.printf("%-8s %s%n", notice.urgency(), notice.title());
            if (!notice.detail().isBlank()) {
                out.printf("         %s%n", Names.plain(notice.detail()));
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

    /**
     * Checks what giving a knowledge base's change sets or preferences their remote would do, or does it, as
     * the window does.
     */
    private static void repositoryRemote(PrintStream out, DashboardSettings settings, String inside, boolean add, String folder,
                                         String address) throws Exception {
        Path named = settings.solor().resolve(folder);
        Path store = java.nio.file.Files.isDirectory(named) ? named : Path.of(folder).toAbsolutePath().normalize();
        Path changeSets = store.resolve(inside);
        if (!java.nio.file.Files.isDirectory(changeSets)) {
            throw new IllegalArgumentException(store + " has no " + inside + " folder");
        }
        RepositorySync.Git git = RepositorySync.Git.find().orElseThrow(() -> new IllegalStateException("git is not installed"));
        // A person's preferences go on a branch named for the knowledge base, so one remote can keep them all.
        String personal = KnowledgeBaseFamily.familyName(KnowledgeBaseScanner.inspect(store));
        RepositoryRemote.Check check = add ? RepositoryRemote.add(git, changeSets, address, personal, true)
                : RepositoryRemote.check(git, changeSets, address, personal, true);
        if (check.refusal().isPresent()) {
            throw new IllegalStateException(check.refusal().get());
        }
        out.println((add ? "Done for " : "For ") + changeSets + ":");
        for (int index = 0; index < check.steps().size(); index++) {
            out.println("  " + (index + 1) + ". " + check.steps().get(index));
        }
    }

    /** Makes a working set, or adds or removes a member, as the Projects tab does. */
    private static void workingSet(PrintStream out, DashboardSettings settings, String command, List<String> words) throws Exception {
        KnowledgeBaseCreation.Prerequisites prerequisites = KnowledgeBaseCreation.prerequisites();
        if (!prerequisites.missing().isEmpty()) {
            throw new IllegalStateException(String.join(" ", prerequisites.missing()));
        }
        KnowledgeBaseCreation creation = new KnowledgeBaseCreation(prerequisites);
        Path projects = settings.projects();
        KnowledgeBaseCreation.Outcome outcome;
        String done;
        if (command.equals("ws-new")) {
            if (words.isEmpty() || words.size() > 2) {
                throw new IllegalArgumentException("ws-new <name> [groupId]");
            }
            String name = words.getFirst();
            String groupId = words.size() == 2 ? words.get(1) : KnowledgeBaseProject.DEFAULT_GROUP;
            List<String> problems = KnowledgeBaseProject.nameProblems(name, groupId);
            if (!problems.isEmpty()) {
                throw new IllegalArgumentException(String.join(" ", problems));
            }
            KnowledgeBaseProject.Names names = KnowledgeBaseProject.Names.read(projects, settings.solor(), java.util.Set.of());
            Optional<String> taken = names.taken(name, groupId);
            if (taken.isPresent()) {
                throw new IllegalArgumentException(taken.get() + " " + names.free(name, groupId) + " is free.");
            }
            outcome = creation.newWorkingSet(projects, name, groupId, out::println);
            done = "Made the working set " + projects.resolve(name);
        } else {
            if (words.size() != 2) {
                throw new IllegalArgumentException(command + " <working-set> <" + (command.equals("ws-add") ? "project" : "member") + ">");
            }
            Path workingSet = projects.resolve(words.getFirst());
            if (!java.nio.file.Files.isRegularFile(workingSet.resolve(KnowledgeBaseProjectFolder.MANIFEST))) {
                throw new IllegalArgumentException(words.getFirst() + " is no working set in " + projects);
            }
            if (command.equals("ws-add")) {
                Path project = projects.resolve(words.get(1));
                if (!KnowledgeBaseProjectFolder.isProject(project)
                        || java.nio.file.Files.isRegularFile(project.resolve(KnowledgeBaseProjectFolder.MANIFEST))) {
                    throw new IllegalArgumentException(words.get(1) + " is no project on its own in " + projects);
                }
                outcome = creation.addToWorkingSet(workingSet, project, out::println);
                done = words.get(1) + " is a member of " + words.getFirst() + ", in " + workingSet.resolve(words.get(1));
            } else {
                if (!KnowledgeBaseProjectFolder.isProject(workingSet.resolve(words.get(1)))) {
                    throw new IllegalArgumentException(words.get(1) + " is no member of " + words.getFirst());
                }
                outcome = creation.removeFromWorkingSet(workingSet, words.get(1), projects, out::println);
                done = words.get(1) + " is on its own again, in " + projects.resolve(words.get(1));
            }
        }
        if (!outcome.succeeded()) {
            throw new IllegalStateException(outcome.failure().orElse("Maven ended with " + outcome.exit()));
        }
        out.println(done);
    }

    /** Checks what giving a project its remote would do, or does it, as the Projects tab does. */
    private static void remote(PrintStream out, DashboardSettings settings, boolean publish, String project, String url)
            throws Exception {
        Path named = settings.projects().resolve(project);
        Path folder = KnowledgeBaseProjectFolder.isProject(named) ? named : Path.of(project).toAbsolutePath().normalize();
        if (!KnowledgeBaseProjectFolder.isProject(folder)) {
            throw new IllegalArgumentException(project + " is no project in " + settings.projects() + ", and no project's folder");
        }
        KnowledgeBaseCreation.Prerequisites prerequisites = KnowledgeBaseCreation.prerequisites();
        if (!prerequisites.missing().isEmpty()) {
            throw new IllegalStateException(String.join(" ", prerequisites.missing()));
        }
        KnowledgeBaseCreation creation = new KnowledgeBaseCreation(prerequisites);
        KnowledgeBaseCreation.Outcome outcome = publish ? creation.remotePublish(folder, url, out::println)
                : creation.remoteDraft(folder, url, out::println);
        if (!outcome.succeeded()) {
            throw new IllegalStateException(outcome.failure().orElse("Maven ended with " + outcome.exit()));
        }
        out.println(publish ? "The remote of " + folder.getFileName() + " is " + url
                : outcome.refusal().map(reason -> "It cannot be added as it stands: " + reason)
                .orElse("Draft only: kb-remote with the same words does it"));
    }

    /** Lists the projects that build knowledge bases, each with what it is built from and its repository's state. */
    private static void projects(PrintStream out, DashboardSettings settings) throws Exception {
        List<KnowledgeBaseProjectFolder> projects = KnowledgeBaseProjectFolder.scan(settings.projects());
        Optional<RepositorySync.Git> git = RepositorySync.Git.find();
        out.printf("%d projects in %s%n", projects.size(), settings.projects());
        for (KnowledgeBaseProjectFolder project : projects) {
            String repository = git.isEmpty() || !java.nio.file.Files.exists(project.folder().resolve(".git")) ? "no git repository"
                    : describe(RepositorySync.local(git.get(), new RepositorySync.Repository(project.folder(), "project")));
            out.printf("%-28s %-44s %s%n", project.artifactId(), project.coordinates(), repository);
            for (KnowledgeBaseProject.Layer layer : project.layers()) {
                out.printf("    %-30s %-22s %s%n", layer.artifactId(), NexusCatalog.label(layer.classifier()), layer.version());
            }
            for (KnowledgeBaseProjectFolder member : project.members()) {
                out.printf("    member %-23s %s%n", member.artifactId(), member.coordinates());
            }
        }
    }

    private static String describe(RepositorySync.Status status) {
        return "branch " + status.branch() + ", " + (status.uncommitted() == 0 ? "everything committed"
                : status.uncommitted() + " not committed") + (status.remote() == RepositorySync.Remote.NONE ? ", no remote" : "");
    }

    /**
     * Drafts or creates a knowledge-base project, as the New knowledge base window does: the same checks,
     * the same goals, Maven's output as it is written.
     */
    private static void newKnowledgeBase(PrintStream out, DashboardSettings settings, boolean create, KnowledgeBaseProject project)
            throws Exception {
        List<String> problems = project.problems();
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(String.join(" ", problems));
        }
        java.util.Set<String> onNexus = new java.util.HashSet<>();
        try {
            NexusCatalog.list(RepositoryHttpClients.newClient(), access(settings))
                    .forEach(offer -> onNexus.add(offer.coordinate().groupId() + ":" + offer.coordinate().artifactId()));
        } catch (Exception e) {
            out.println("Could not ask Nexus which names are in use: " + e.getMessage());
        }
        String groupId = project.properties().getOrDefault("groupId", KnowledgeBaseProject.DEFAULT_GROUP);
        KnowledgeBaseProject.Names names = KnowledgeBaseProject.Names.read(settings.projects(), settings.solor(), onNexus);
        Optional<String> taken = names.taken(project.name(), groupId);
        if (taken.isPresent()) {
            throw new IllegalArgumentException(taken.get() + " " + names.free(project.name(), groupId) + " is free.");
        }
        KnowledgeBaseCreation.Prerequisites prerequisites = KnowledgeBaseCreation.prerequisites();
        if (!prerequisites.missing().isEmpty()) {
            throw new IllegalStateException(String.join(" ", prerequisites.missing()));
        }
        KnowledgeBaseCreation creation = new KnowledgeBaseCreation(prerequisites);
        KnowledgeBaseCreation.Outcome outcome = create ? creation.create(project, out::println) : creation.draft(project, out::println);
        if (!outcome.succeeded()) {
            throw new IllegalStateException(outcome.failure().orElse("Maven ended with " + outcome.exit()));
        }
        out.println(create ? "Created " + project.name() + " in " + project.folder()
                : "Draft only: kb-new with the same words creates it in " + project.folder());
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
