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

## Build

```bash
./mvnw clean verify
```

Java 27 with preview features, as Komet's working set.
