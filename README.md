# IKE Dashboard

One window that keeps Komet versions installed side by side and current, keeps the knowledge bases
in `~/Solor` current, and launches any Komet version on any of them as an independent process. It
watches Nexus for knowledge bases and application updates, and updates itself.

The design is the `dev-ike-dashboard` topic in `ike-lab-documents`.

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
  `dashboard-latest`, `self-update [application]`). Its first screen is Notices: one card per finding of
  its checks (newer builds, knowledge bases not yet downloaded, change sets to upgrade, a newer Komet, a
  newer dashboard, the sync state of the git repositories inside each store), work at risk first, each
  with one action.

  The knowledge bases of the collections it tracks stay at hand whether downloaded or not: the Nexus
  window lists them first, and the Notices screen keeps them however long ago they were deployed. A
  knowledge base is in a collection when its descriptor names one (`collection=`, from
  `ike.kbAssemble.collection`); the `collections` preference (comma-separated, under
  `network/ike/dashboard`) is `SOLOR` unless set, every knowledge base the `solor-kb-ws` pipeline publishes.

## Run

```bash
./mvnw install -DskipTests
./mvnw -pl ike-dashboard-app javafx:run
```

The Nexus check reads the `ike-restricted` login from `~/.m2/settings.xml`, filling `${env.…}`
placeholders from the environment, so run it from a shell that has them.

## The application

```bash
./mvnw -Papp -pl ike-dashboard-app -am package -DskipTests
open "ike-dashboard-app/target/app/IKE Dashboard.app"
```

`-Papp` makes `IKE Dashboard.app` with jpackage, on a runtime jlinked from the dashboard's modules, its
version the build clock's (`ike:jpackage-props`), unsigned. Adding `-Psigned-app` signs it with the Developer
ID under the hardened runtime and notarizes and staples it (`ike:notarize`, ike-tooling 271 on); that needs the
signing identity and the `notarytool` keychain profile, which the installer build's agent holds.

## Getting it

Download the newest dashboard for a Mac with Apple silicon:

<https://github.com/IKE-Network/ike-dashboard/releases/latest/download/IKE-Dashboard-osx-aarch64.zip>

Unzip it and move `IKE Dashboard.app` into `~/Applications` or `/Applications` before opening it.
From then on it updates itself (below).

The link never changes. TeamCity's **Publish to GitHub** (IKE Dashboard project, run by hand) runs
**Installer macOS**, which signs the application with the Developer ID, notarizes and staples it, and
deploys it to Nexus `ike-public` as `network.ike.dashboard:ike-dashboard-app:<version>:zip:osx-aarch64`.
It then publishes the same zip as release `v<version>`, under the version-free name
`IKE-Dashboard-osx-aarch64.zip` with its `.sha256`, and marks it Latest. Older versions stay on the
releases page and on Nexus.

## Self-update

The dashboard updates itself in plain Java (decision 2 of `dev-ike-dashboard`), with the code that
installs Komet versions. When it runs as the application, it asks Nexus, on start and every hour, for
the newest `ike-dashboard-app` with a zip for this machine, as it asks for `komet-app`. A newer one
than the one running is a Notices card, *IKE Dashboard <version> is available*, whose one action,
*Update and restart*:

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

A dashboard run from a build (`javafx:run`, or `DashboardCommand` on a JDK) is not an application and
offers no update. From a shell, `DashboardCommand self-update "<path>/IKE Dashboard.app"` does steps
1–3 for a named application, with the helper waiting for the command to end.

## Build

```bash
./mvnw clean verify
```

Java 27 with preview features, as Komet's working set.
