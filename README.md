# IKE Dashboard

One window that keeps Komet versions installed side by side and current, keeps the knowledge bases
in `~/Solor` current, and launches any Komet version on any of them as an independent process. It
watches Nexus for knowledge bases and application updates, and updates itself.

The design is the `dev-ike-dashboard` topic in `ike-lab-documents`.

## Download

The newest IKE Dashboard, as an installer like Komet's:

- **Mac with Apple silicon**, signed and notarized:
  <https://github.com/IKE-Network/ike-dashboard/releases/latest/download/IKE-Dashboard-osx-aarch64.pkg>.
  Open it and follow the installer, which puts `IKE Dashboard.app` in `/Applications`.
- **Windows (64-bit)**:
  <https://github.com/IKE-Network/ike-dashboard/releases/latest/download/IKE-Dashboard-windows-x86_64.msi>.
  It installs for you alone, with no administrator password, and adds IKE Dashboard to the Start
  menu. It is not signed yet, so SmartScreen may say *Windows protected your PC*: choose *More info*,
  then *Run anyway*.

From then on it updates itself (see [Self-update](#self-update)). The links never change, and
<https://ike.network/dashboard> is the page to send people to.
[Releases](https://github.com/IKE-Network/ike-dashboard/releases) keeps the older versions.

## Modules

- `ike-dashboard-download`: Maven repository access with the JDK and SLF4J alone (Nexus search,
  `maven-metadata.xml` versions, `settings.xml` proxies and servers, platform trust, and the
  resumable download and unpack of a classified artifact through the local repository). Copied from
  Komet's `artifact-resolution` module (`ikmdev/komet`, Apache 2.0); see `NOTICE`.

- `ike-dashboard-journal`: the usage journal beside a local repository (`.ike-usage.journal`), which
  files were used, by which tool, on which day. JDK only, Java 21.
- `ike-dashboard-maven-extension`: a Maven core extension that records, at the end of every build,
  the files of the local repository the build resolved. It never deletes and never fails a build;
  `-Dike.usageJournal.skip=true` turns it off. Java 21, so any Maven JVM loads it. Enabled
  user-wide in `~/.m2/extensions.xml`:

  ```xml
  <extensions>
      <extension>
          <groupId>network.ike.dashboard</groupId>
          <artifactId>ike-dashboard-maven-extension</artifactId>
          <version>1-SNAPSHOT</version>
      </extension>
  </extensions>
  ```

- `ike-dashboard-app`: the window. It lists the knowledge bases in `~/Solor` with what each store
  says about itself, asks Nexus whether a newer build is deployed, lists the Komet versions
  installed, and opens a knowledge base in a chosen Komet as an independent process. Also a headless
  `DashboardCommand` (`list`, `check`, `notices`, `komets`, `download <g:a:v> <classifier>`,
  `projects`, `kb-draft <name> [property=value …]`, `kb-new <name> [property=value …]`,
  `kb-remote-draft <project> <git-url>`, `kb-remote <project> <git-url>`,
  `ws-new <name> [groupId]`, `ws-add <working-set> <project>`, `ws-remove <working-set> <member>`,
  `changes-remote-check <folder> <git-url>`, `changes-remote <folder> <git-url>`,
  `preferences-remote-check <folder> <git-url>`, `preferences-remote <folder> <git-url>`,
  `preferences-send <folder>`,
  `dashboard-latest`, `self-update [application]`). The window has three views, one switch across its top:
  *Knowledge Bases*, the list and the chosen one's detail, and *Notices*, the first screen, across the
  window: one card per finding of its checks (newer builds, knowledge bases not yet downloaded, a newer
  Komet, a newer dashboard, the sync state of the git repositories inside each store), work at risk
  first, each with one action, and Show for a card about one knowledge base
  (`IKE-Network/ike-issues#1357`). *Komet*, between them, lists every installed Komet with where it is and what it is used for, and
  moves old ones to the Trash, one at a time or all the older ones together; it refuses a running Komet and names
  any knowledge base no remaining Komet could open (`IKE-Network/ike-issues#1363`). Each knowledge base opens in the
  Komet chosen beside its Open button: *Latest Komet*, which follows each new install, or one installed version,
  pinned until changed and kept in the `kometPins` preferences (`IKE-Network/ike-issues#1365`).
  A dialog names the copy it acts on by its folder (`solor-gudid-kb in ~/Solor`), since a knowledge base can
  have several copies, and the window's and every dialog's words can be selected and copied, read-only, with
  file, folder and path names in bold (`IKE-Network/ike-issues#1358`, `#1359`, `#1361`). A store is open in a
  Komet while a process holds its `lucene/write.lock` (the file itself stays after Komet closes); Upgrade,
  Reinstall, Copy and Move to Trash refuse such a store up front. The same knowledge base may be in several folders, each with its own
  change sets and its own reason for being, and each is upgraded on its own: the detail shows the newest, and
  its *Other copies* table offers *Upgrade…* for every folder behind the newest build. *Copy to a new folder…*,
  on any folder's menu, copies the whole folder beside it under a name you give, cloned on macOS
  (`IKE-Network/ike-issues#1360`).

  A store has three operations (decision 20 of `dev-ike-dashboard`). *Upgrade* brings it to the newest
  build in place: the build downloads into `<store>.upgrade` beside it, the change-set folders, the
  preferences and every git repository in the store are carried into it, a Komet from 26.1107.1831 on
  checks with no window (`--load-change-sets`) that it opens and loads every change set, and the two
  folders swap by rename; the old build, confirmed replaced, goes to the Trash, which is the way back. When some change sets do not load, the dashboard says
  what each contains, who packaged it and why it was refused, and the person cancels the upgrade or
  sets them aside in `unprocessedChangeSets/`, which Komet never reads and later upgrades carry along
  (`IKE-Network/ike-issues#1356`); a change set in a git repository with a remote is never moved out.
  Transfers run three at a time, one check in Komet at a time (`IKE-Network/ike-issues#1354`). Every
  failure leaves the store as it was. *Reinstall*
  moves the store to the Trash and downloads the newest build under the same name. *Install a new copy*
  unpacks the newest build beside the copy there is, with the version appended. Nothing is ever written
  over. Older change sets are loaded by Komet at open, never rewritten: the dashboard no longer offers a
  change-set upgrade.

  The knowledge bases of the collections it tracks stay at hand whether downloaded or not: the Nexus
  window lists them first, and the Notices screen keeps them however long ago they were deployed. A
  knowledge base is in a collection when its descriptor names one (`collection=`, from
  `ike.kbAssemble.collection`); the `collections` preference (comma-separated, under
  `network/ike/dashboard`) is `SOLOR` unless set, every knowledge base the `solor-kb-ws` pipeline publishes.

  The Nexus window is a tree: each knowledge base holds its Databases (the stores Komet opens), its
  Protobufs (`reasoned-pb`, `unreasoned-pb`, which Komet loads into a store), what it was Built from, and what
  it is Included in. Both come from the descriptors' `input.<n>` entries, which `ike:kb-assemble` writes from
  its inputs. A downloaded protobuf goes into `~/Solor` as `<artifactId>-<deploy>-<classifier>.zip`, and its
  knowledge base's descriptor and icon go beside it in `~/Solor/.ike/protobufs/<zip name>/`, so the
  dashboard manages it as it manages a store. The main window's detail links the same relations.

## Run

```bash
./mvnw install -DskipTests
./mvnw -pl ike-dashboard-app javafx:run
```

The Nexus check reads the `ike-restricted` login from `~/.m2/settings.xml`, filling `${env.…}`
placeholders from the environment, so run it from a shell that has them.

## Remotes for a knowledge base's change sets and preferences

On the *Knowledge Bases* tab, a folder's detail lists the git repositories inside it under
*Repositories*: each one's branch, whether everything is committed, and whether it has a remote. The
change sets, which are shared with the others who author the knowledge base, are given a remote
there. *Add remote…* takes the address, *Check* shows what adding it will do, and the remote is added
only as it was checked. It is done with git alone:

1. A change-set folder that is no repository yet is made one, on the branch `main`.
2. Its remote, `origin`, is set.
3. It is pushed only when that adds to what the remote has. For an empty remote and a new repository a
   `README.md` is committed and pushed, as Komet does when it connects. When the remote already has
   other change sets, nothing is pushed and nothing is merged: Komet's sync pulls them and loads them.

Komet keeps its own record of the address and the login it syncs with, so its sync is still connected
in Komet, with the same address.

Your preferences for a knowledge base are given a remote of their own in the same place. They are
yours alone: your window layout, your journals, and your conversations with the assistant, which
contain whatever you typed. So use a private repository, where only you can read them. One private
repository can keep the preferences of all your knowledge bases: each is committed on a branch named
for its knowledge base. Sending them is your choice. When the remote is added, *Commit and push what
is there now* is ticked, and you can clear it to set the remote alone. Afterwards the folder's
*Repositories* offer *Commit and push* whenever there is something not yet sent. Nothing is sent by
itself. The dashboard does nothing while the knowledge base is open in Komet, which writes
preferences as it runs, and it never merges with what another machine pushed.

```bash
DashboardCommand changes-remote-check     solor-kb https://github.com/my-org/solor-changes.git
DashboardCommand changes-remote           solor-kb https://github.com/my-org/solor-changes.git
DashboardCommand preferences-remote-check solor-kb git@github.com:me/my-komet-preferences.git
DashboardCommand preferences-remote       solor-kb git@github.com:me/my-komet-preferences.git
DashboardCommand preferences-send         solor-kb
```

## New knowledge base

The *Projects* tab lists the projects that build knowledge bases. They are kept in
`~/knowledge-bases`, apart from the knowledge bases Komet opens, which are in `~/Solor`. Each project
shows the knowledge sources it is built from, where it deploys, the state of its git repository, and when it was
last built. *Build* runs `./mvnw verify` in it. *Deploy…* asks first, then runs `./mvnw deploy`.

A project with no remote offers *Add remote…*. Create the repository on your git host first, and
leave it empty. *Check* asks the remote what it has and shows what adding it will do. *Add remote and
push* then sets `origin`, has the POM's `<scm>` name the remote in place of the project's own folder,
commits that change alone, and pushes. This is the remote of the project that builds the knowledge
base, not of the knowledge base. A remote that cannot be read, or that already has other commits on
the branch, is refused before anything is changed.

A working set builds several projects together, in order: a knowledge base and the sources
it is assembled from, for example. *New working set…* makes an empty one, in a folder of its own in
`~/knowledge-bases` with its own git repository. A project joins a working set only once it builds
on its own: its page offers *Add to working set*, which moves its folder into the working set's
folder and has the working set list it as a member. *Remove from working set* moves it back out. The
list shows each working set with its members beneath it. A working set is built, not deployed; each
member is deployed from its own page.

*New knowledge base…*, on that tab, creates a Maven 4 project for a knowledge base built from
knowledge sources already on Nexus (IKE-Network/ike-issues#1370). The form asks for a name, the
knowledge sources in the order they are loaded, and a folder. *Choose knowledge sources…* lists every
protobuf on Nexus with a checkbox and, under its name, what it is: a single source or a whole
knowledge base, its coordinates, version, size and deploy date, and the release it was read from or
the knowledge bases it contains. Single sources and whole knowledge bases are listed apart, because
they cannot be combined yet. *Draft* shows what will be created, with each knowledge source at its
exact version on Nexus, and writes nothing. *Create knowledge base* then makes the project in a local
git repository with one commit and no remote, and the Projects tab shows it.

The work is done by two goals of `ike-maven-plugin`, `kb-create-draft` and `kb-create-publish`, run
by their full coordinates through a Maven wrapper the dashboard writes first, so no installed Maven is
needed. The build needs JDK 27 or later and git; the form says which is missing. The same steps run
from a shell:

```bash
DashboardCommand kb-draft my-kb knowledgeSources=network.ike.data:ncbi-gene-kb:zip:unreasoned-pb:20261020.1-SNAPSHOT
DashboardCommand kb-new   my-kb knowledgeSources=network.ike.data:ncbi-gene-kb:zip:unreasoned-pb:20261020.1-SNAPSHOT
DashboardCommand projects
DashboardCommand kb-remote-draft my-kb https://github.com/my-org/my-kb.git
DashboardCommand kb-remote       my-kb https://github.com/my-org/my-kb.git
DashboardCommand ws-new    my-ws
DashboardCommand ws-add    my-ws my-kb
DashboardCommand ws-remove my-ws my-kb
```

Each `property=value` is a property of the goals (`kind`, `collection`, `rocks`, and the others);
`folder=<path>` names the project's folder, `~/knowledge-bases/<name>` unless given. Projects are
kept apart from the knowledge bases in `~/Solor`. A name must be new: one that a project, a knowledge
base in `~/Solor` or a knowledge base on Nexus already has is refused, and a free one is offered. The dashboard
runs plugin version 282 to create a project, the first whose goals say `knowledgeSources` where 280
and 281 said `layers`, and 281 to add a remote. Set `IKE_DASHBOARD_TOOLING_VERSION` to run another for both, such as a snapshot under
test. Working sets are made and changed by `ike-workspace-maven-plugin` 227; `IKE_DASHBOARD_PLATFORM_VERSION`
names another.

## The application

```bash
./mvnw -Papp -pl ike-dashboard-app -am package -DskipTests
open "ike-dashboard-app/target/app/IKE Dashboard.app"
```

`-Papp` makes the application with jpackage, on a runtime jlinked from the dashboard's modules, its
version the build clock's (`ike:jpackage-props`), unsigned: `IKE Dashboard.app` on macOS, the folder
`IKE Dashboard` (with `IKE Dashboard.exe`) on Windows. Adding `-Psigned-app` on macOS signs it with the
Developer ID under the hardened runtime and notarizes and staples it (`ike:notarize`, ike-tooling 271 on);
that needs the signing identity and the `notarytool` keychain profile, which the installer build's agent
holds.

The installer is made from that application after the build, into `ike-dashboard-app/target/installer`:

```bash
./mvnw -pl ike-dashboard-app -Papp exec:exec@jpackage-installer
```

On macOS it is a `.pkg` (signed with the Developer ID Installer under `-Psigned-app`, then notarized by
`ike:notarize@notarize-installer`), whose postinstall hands the application to the person who installed
it so it can update itself in place. On Windows it is a per-user `.msi` (WiX 3 on the `PATH`), with a
Start-menu entry and a shortcut. An MSI's version allows at most 255.255.65535, so the `.msi` takes
`dashboard.msi.version`; the Windows installer build names both versions (see [Releases](#releases)).

The icon is the IKE pill of the brand guide (`brand-logo-powered-by` in `ike-lab-documents`) on a
Midnight tile: an `.icns` for the macOS application, an `.ico` for the Windows launcher and `.msi`, a
`.png` for Linux, and the window's own icon. The `.pkg` shows the pill in place of jpackage's Duke, and
the application's `Info.plist` (`src/main/jpackage-resources/mac`) names the macOS its runtime is
built for, 11.0. The SVGs in `ike-dashboard-app/src/main/icons` are the source; after changing one,
run `render-icons.sh` there on macOS and commit what it writes.

## Self-update

The dashboard updates itself in plain Java (decision 2 of `dev-ike-dashboard`), with the code that
installs Komet versions. When it runs as the application, it asks Nexus, on start and every hour, for
the newest `ike-dashboard-app` with a zip for this machine, as it asks for `komet-app`. A newer one
than the one running is a Notices card, *IKE Dashboard <version> is available*, whose one action,
*Update and restart*, on macOS:

1. downloads the zip through the local Maven repository, as a transfer that can be cancelled and
   resumed, and checks it against the `.sha1` Nexus keeps beside it;
2. unpacks it with `ditto` beside the application, in `.ike-dashboard-update/new`, and refuses it unless
   it is the dashboard (`network.ike.dashboard`) of that version, `codesign --verify --deep --strict`
   accepts it, and `spctl --assess` (Gatekeeper) would open it. A file Java downloads carries no
   quarantine, so Gatekeeper never assesses it unless asked;
3. starts a helper (`.ike-dashboard-update/swap.sh`, logging to
   `~/Library/Logs/IKE Dashboard/self-update.log`) and quits. The helper waits for the dashboard to exit,
   moves the old application to `.ike-dashboard-update/previous`, moves the new one in, and opens it.
   If the new one cannot be moved in, the old one is put back.

The new dashboard removes `.ike-dashboard-update` once it has started, so the previous application is
kept until then: if an update does not start, move `.ike-dashboard-update/previous/IKE Dashboard.app`
back. Running Komets are independent processes and are not affected.

Where the dashboard runs decides where the update goes:

- in place, when the folder it is in can be written;
- into `~/Applications`, when it cannot, such as an administrator's `/Applications` or a disk image.
  The card says so, and the old copy stays where it was;
- nowhere yet, when macOS runs it *translocated*: opened where it was downloaded, from a randomized
  read-only copy. The card is then *Move the IKE Dashboard into your Applications folder*; its action
  copies the running application into `~/Applications`, checks it as it checks a download, removes its
  quarantine so macOS opens it in place, and restarts it there. If a dashboard as new is already
  there, it opens that one instead.

On Windows the steps are the same, without a signature: the dashboard is not signed yet (KEC,
2026-10-26), so the `.sha1` is the check. Java unpacks the zip, which must hold the dashboard
(`app\IKE Dashboard.cfg` runs `network.ike.dashboard.app`) of that version; the helper is
`.ike-dashboard-update\swap.ps1`, run by PowerShell, logging to
`%LOCALAPPDATA%\IKE Dashboard\logs\self-update.log`, and it starts the new `IKE Dashboard.exe`. The
`.msi` installs into `%LOCALAPPDATA%\IKE Dashboard`, which the person can write, so the update goes in
place; one that cannot be written goes into `%LOCALAPPDATA%\Programs\IKE Dashboard`. Nothing is
translocated.

A dashboard run from a build (`javafx:run`, or `DashboardCommand` on a JDK) is not an application and
offers no update. From a shell, `DashboardCommand self-update "<path>/IKE Dashboard.app"` does steps
1–3 for a named application, with the helper waiting for the command to end.

## Releases

The dashboard comes as Komet does (KEC, 2026-10-26): an installer for people on each platform, and a
zipped application on Nexus for the self-update. TeamCity's **Publish to GitHub** (IKE Dashboard
project, run by hand) runs:

- **Installer macOS**, which signs the application with the Developer ID, notarizes and staples it, and
  deploys it to Nexus `ike-public` as `network.ike.dashboard:ike-dashboard-app:<version>:zip:osx-aarch64`;
  then makes the `.pkg` from it, signed, notarized and stapled. Its build number is the version.
- **Installer Windows**, on the Windows agent, which runs every test there, makes the application under
  the macOS build's version, deploys it as `…:zip:windows-x86_64`, and makes the `.msi` from it. The
  `.msi`'s own version is `yy.M.<minute of the month>`, which an MSI allows and which still sorts.

It then publishes both installers as release `v<version>`, under the version-free names
`IKE-Dashboard-osx-aarch64.pkg` and `IKE-Dashboard-windows-x86_64.msi`, each with its `.sha256`, and
marks it Latest, which is what keeps the download links stable. Older versions stay on the releases page
and on Nexus.

## Build

```bash
./mvnw clean verify
```

Java 27 with preview features, as Komet's working set.
