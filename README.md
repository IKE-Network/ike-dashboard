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

## Build

```bash
./mvnw clean verify
```

Java 27 with preview features, as Komet's working set.
