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
  `DashboardCommand` (`list`, `check`, `notices`, `komets`, `download <g:a:v> <classifier>`). Its first
  screen is Notices: one card per finding of its checks (newer builds, knowledge bases not yet downloaded,
  change sets to upgrade, a newer Komet, the sync state of the git repositories inside each store),
  work at risk first, each with one action.

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

## Build

```bash
./mvnw clean verify
```

Java 27 with preview features, as Komet's working set.
