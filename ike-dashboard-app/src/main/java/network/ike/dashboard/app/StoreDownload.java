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

import network.ike.dashboard.download.ArtifactCoordinates;
import network.ike.dashboard.download.DownloadListener;
import network.ike.dashboard.download.MavenDataStoreDownloadTask;
import network.ike.dashboard.journal.UsageJournal;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.stream.Stream;

/**
 * Downloads a knowledge base's store from Nexus into a new folder of {@code ~/Solor}, through the
 * local repository as {@code mvn} would cache it, and adds to the store's manifest the facts only a
 * download knows (decision 4 of the {@code dev-ike-dashboard} topic): which deploy it is, which
 * repository it came from, when, and the checksum Nexus serves for it. A protobuf, which Komet loads
 * into a store rather than opens, is placed as its zip, {@code <artifactId>-<deploy>-<classifier>.zip},
 * the name its knowledge base's family is read from.
 */
public final class StoreDownload {

    /** The manifest attribute naming the repository a download came from, by its server id. */
    public static final String RESOLVED_REPOSITORY = "Ike-Resolved-Repository";

    /** The manifest attribute holding when a download unpacked the store. */
    public static final String DOWNLOADED_AT = "Ike-Downloaded-At";

    /** The manifest attribute holding the SHA-1 of the zip, the checksum Nexus serves. */
    public static final String SOURCE_SHA1 = "Ike-Source-Sha1";

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private StoreDownload() {
    }

    /**
     * Downloads the newest deploy of a store and unpacks it as {@code <solor>/<artifactId>}, or, when a
     * copy is already there, beside it as {@code <solor>/<artifactId>-<deploy>}: a download never writes
     * over a store.
     *
     * @param http            the client to download with
     * @param repository      the repository and its login
     * @param coordinate      the knowledge base's coordinate
     * @param classifier      the store's classifier, such as {@code rkb}
     * @param solor           the knowledge-base folder
     * @param localRepository the local Maven repository the zip is cached in
     * @param listener        told of progress
     * @return the unpacked store
     * @throws Exception if that deploy is already unpacked, Nexus has no such deploy, or the download fails
     */
    public static Path download(HttpClient http, RepositoryAccess repository, Coordinate coordinate, String classifier,
                                Path solor, Path localRepository, DownloadListener<Path> listener) throws Exception {
        return download(http, repository, coordinate, classifier, Optional.empty(), solor, localRepository, listener,
                cancel -> { });
    }

    /**
     * Downloads the newest deploy of a store as {@link #download(HttpClient, RepositoryAccess, Coordinate, String,
     * Path, Path, DownloadListener)} does, showing its progress on a transfer the person can cancel. A cancelled
     * download leaves no partial store in the knowledge-base folder; the bytes already downloaded stay in the
     * local repository, so the next download of the same deploy resumes them.
     *
     * @param http            the client to download with
     * @param repository      the repository and its login
     * @param coordinate      the knowledge base's coordinate
     * @param classifier      the store's classifier
     * @param solor           the knowledge-base folder
     * @param localRepository the local Maven repository the zip is cached in
     * @param transfer        shows the progress, and cancels the download when asked
     * @return the unpacked store
     * @throws CancellationException if the person cancelled it
     * @throws Exception if that deploy is already unpacked, Nexus has no such deploy, or the download fails
     */
    public static Path download(HttpClient http, RepositoryAccess repository, Coordinate coordinate, String classifier,
                                Path solor, Path localRepository, Transfer transfer) throws Exception {
        return download(http, repository, coordinate, classifier, Optional.empty(), solor, localRepository, transfer,
                transfer::cancelWith);
    }

    /**
     * Downloads the newest deploy of a store or a protobuf, as the other {@code download} does, keeping
     * with a protobuf what Nexus describes of its knowledge base, so the dashboard manages it as it
     * manages a store.
     *
     * @param http            the client to download with
     * @param repository      the repository and its login
     * @param coordinate      the knowledge base's coordinate
     * @param classifier      the store's or protobuf's classifier
     * @param described       the knowledge base's descriptor and icon, as Nexus deploys them
     * @param solor           the knowledge-base folder
     * @param localRepository the local Maven repository the zip is cached in
     * @param transfer        shows the progress, and cancels the download when asked
     * @return the unpacked store, or the protobuf's zip
     * @throws CancellationException if the person cancelled it
     * @throws Exception if that deploy is already here, Nexus has no such deploy, or the download fails
     */
    public static Path download(HttpClient http, RepositoryAccess repository, Coordinate coordinate, String classifier,
                                Optional<NexusCatalog.Described> described, Path solor, Path localRepository,
                                Transfer transfer) throws Exception {
        return download(http, repository, coordinate, classifier, described, solor, localRepository, transfer,
                transfer::cancelWith);
    }

    private static Path download(HttpClient http, RepositoryAccess repository, Coordinate coordinate, String classifier,
                                 Optional<NexusCatalog.Described> described, Path solor, Path localRepository,
                                 DownloadListener<Path> listener, Consumer<Runnable> cancelWith) throws Exception {
        String fileVersion = coordinate.version();
        if (coordinate.snapshot()) {
            fileVersion = newestDeploy(http, repository, coordinate, classifier)
                    .orElseThrow(() -> new IOException("Nexus lists no " + classifier + " zip for " + coordinate))
                    .value();
        }
        ArtifactCoordinates artifact = new ArtifactCoordinates(coordinate.groupId(), coordinate.artifactId());
        URI uri = MavenDataStoreDownloadTask.downloadUri(repository.url(), artifact, coordinate.version(), fileVersion,
                classifier);
        Path cached = MavenDataStoreDownloadTask.localRepositoryPath(localRepository, artifact, coordinate.version(),
                fileVersion, classifier);
        if (NexusCatalog.PROTOBUF_CLASSIFIERS.contains(classifier)) {
            Path zip = placeProtobuf(http, repository, uri, cached,
                    protobufFile(solor, coordinate.artifactId(), fileVersion, classifier), localRepository, listener,
                    cancelWith);
            if (described.isPresent()) {
                Map<String, String> attributes = new TreeMap<>();
                attributes.put(KnowledgeBase.RESOLVED_VERSION, fileVersion);
                attributes.put(RESOLVED_REPOSITORY, repository.serverId());
                attributes.put(DOWNLOADED_AT, Instant.now().toString());
                attributes.put(SOURCE_SHA1, sha1(cached));
                describeProtobuf(zip, coordinate, described.get(), attributes);
            }
            return zip;
        }
        Path destination = destination(solor, coordinate.artifactId(), fileVersion);
        MavenDataStoreDownloadTask task = repository.credentials().isPresent()
                ? MavenDataStoreDownloadTask.unpackTask(http, uri, cached, destination,
                MavenDataStoreDownloadTask.AssetVerification.NONE, repository.credentials().get())
                : MavenDataStoreDownloadTask.unpackTask(http, uri, cached, destination,
                MavenDataStoreDownloadTask.AssetVerification.NONE);
        if (listener != null) {
            task.addListener(listener);
        }
        cancelWith.accept(task::cancel);
        Path store;
        try {
            store = task.call();
        } catch (CancellationException e) {
            // destination() named a folder that did not exist, so whatever is there is this download's half.
            deleteTree(destination);
            throw e;
        }
        Map<String, String> attributes = new TreeMap<>();
        attributes.put(KnowledgeBase.RESOLVED_VERSION, fileVersion);
        attributes.put(RESOLVED_REPOSITORY, repository.serverId());
        attributes.put(DOWNLOADED_AT, Instant.now().toString());
        attributes.put(SOURCE_SHA1, sha1(cached));
        addToManifest(store, attributes);
        UsageJournal.record(localRepository, UsageJournal.DASHBOARD, LocalDate.now(), List.of(cached));
        return store;
    }

    private static Path placeProtobuf(HttpClient http, RepositoryAccess repository, URI uri, Path cached, Path file,
                                      Path localRepository, DownloadListener<Path> listener,
                                      Consumer<Runnable> cancelWith) throws Exception {
        MavenDataStoreDownloadTask task = repository.credentials().isPresent()
                ? MavenDataStoreDownloadTask.placeAsFileTask(http, uri, cached, file,
                MavenDataStoreDownloadTask.AssetVerification.NONE, repository.credentials().get())
                : MavenDataStoreDownloadTask.placeAsFileTask(http, uri, cached, file,
                MavenDataStoreDownloadTask.AssetVerification.NONE);
        if (listener != null) {
            task.addListener(listener);
        }
        cancelWith.accept(task::cancel);
        Path placed;
        try {
            placed = task.call();
        } catch (CancellationException e) {
            Files.deleteIfExists(file);
            throw e;
        }
        UsageJournal.record(localRepository, UsageJournal.DASHBOARD, LocalDate.now(), List.of(cached));
        return placed;
    }

    /**
     * Keeps what a protobuf's zip cannot carry in its folder under
     * {@value KnowledgeBaseScanner#PROTOBUF_DESCRIPTIONS}: its knowledge base's descriptor and icon, its
     * Maven coordinate, and the facts of the download, laid out as in a store.
     *
     * @param zip        the protobuf
     * @param coordinate the knowledge base
     * @param described  its descriptor and icon, as Nexus deploys them
     * @param attributes the download's facts, {@code Ike-*} manifest attributes
     * @throws IOException if they cannot be written
     */
    static void describeProtobuf(Path zip, Coordinate coordinate, NexusCatalog.Described described,
                                 Map<String, String> attributes) throws IOException {
        Path folder = KnowledgeBaseScanner.describing(zip);
        deleteTree(folder);
        Path descriptor = folder.resolve(KnowledgeBaseScanner.DESCRIPTOR);
        Files.createDirectories(descriptor.getParent());
        try (OutputStream out = Files.newOutputStream(descriptor)) {
            described.descriptor().toProperties().store(out, "Knowledge-base descriptor, as Nexus deploys it beside "
                    + zip.getFileName());
        }
        Path icon = folder.resolve(described.descriptor().icon()).normalize();
        if (!icon.startsWith(folder)) {
            throw new IOException("The descriptor's icon " + described.descriptor().icon() + " is outside its folder");
        }
        Files.createDirectories(icon.getParent());
        Files.write(icon, described.icon());
        Path pom = folder.resolve("META-INF/maven").resolve(coordinate.groupId()).resolve(coordinate.artifactId())
                .resolve("pom.properties");
        Files.createDirectories(pom.getParent());
        Properties maven = new Properties();
        maven.setProperty("groupId", coordinate.groupId());
        maven.setProperty("artifactId", coordinate.artifactId());
        maven.setProperty("version", coordinate.version());
        try (OutputStream out = Files.newOutputStream(pom)) {
            maven.store(out, null);
        }
        addToManifest(folder, attributes);
    }

    /**
     * Where a protobuf is placed: {@code <solor>/<artifactId>-<deploy>-<classifier>.zip}, never over a
     * file already there.
     *
     * @param solor       the knowledge-base folder
     * @param artifactId  the knowledge base
     * @param fileVersion the deploy
     * @param classifier  {@code reasoned-pb} or {@code unreasoned-pb}
     * @return the file
     * @throws IOException if that deploy is already there
     */
    static Path protobufFile(Path solor, String artifactId, String fileVersion, String classifier) throws IOException {
        Path file = solor.resolve(artifactId + "-" + fileVersion + "-" + classifier + ".zip");
        if (Files.exists(file)) {
            throw new IOException(file + " is already there");
        }
        Files.createDirectories(solor);
        return file;
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        List<Path> paths;
        try (Stream<Path> walk = Files.walk(root)) {
            paths = new ArrayList<>(walk.toList());
        }
        for (int i = paths.size() - 1; i >= 0; i--) {
            Files.deleteIfExists(paths.get(i));
        }
    }

    /**
     * Where a download unpacks: {@code <artifactId>} when free, else {@code <artifactId>-<deploy>}.
     *
     * @param solor       the knowledge-base folder
     * @param artifactId  the knowledge base
     * @param fileVersion the deploy
     * @return the new folder
     * @throws IOException if both are taken: that deploy is already unpacked
     */
    static Path destination(Path solor, String artifactId, String fileVersion) throws IOException {
        Path plain = solor.resolve(artifactId);
        if (!Files.exists(plain)) {
            return plain;
        }
        if (fileVersion.equals(KnowledgeBaseScanner.ikeAttributes(plain).get(KnowledgeBase.RESOLVED_VERSION))) {
            throw new IOException(plain + " already holds " + fileVersion);
        }
        Path beside = solor.resolve(artifactId + "-" + fileVersion);
        if (Files.exists(beside)) {
            throw new IOException(beside + " already exists; the dashboard never writes over a store");
        }
        return beside;
    }

    /**
     * The newest deploy of a snapshot's classified zip.
     *
     * @param http       the client
     * @param repository the repository and its login
     * @param coordinate the coordinate, a snapshot
     * @param classifier the classifier
     * @return the deploy, or empty when Nexus lists none
     * @throws IOException          if Nexus cannot be read
     * @throws InterruptedException if interrupted
     */
    static Optional<SnapshotMetadata.Deploy> newestDeploy(HttpClient http, RepositoryAccess repository,
                                                          Coordinate coordinate, String classifier)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(NewerBuildCheck.metadataUri(repository.url(), coordinate))
                .timeout(TIMEOUT).GET();
        repository.credentials().ifPresent(login -> request.header("Authorization", NewerBuildCheck.basic(login)));
        HttpResponse<InputStream> response = http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = response.body()) {
            if (response.statusCode() != 200) {
                throw new IOException("Nexus answered " + response.statusCode() + " for " + coordinate);
            }
            return SnapshotMetadata.newest(body, classifier, "zip");
        }
    }

    /**
     * Merges attributes into a store's {@code META-INF/MANIFEST.MF}, keeping those already there.
     *
     * @param store      the store
     * @param attributes the attributes to write
     * @throws IOException if the manifest cannot be written
     */
    static void addToManifest(Path store, Map<String, String> attributes) throws IOException {
        Path file = store.resolve("META-INF").resolve("MANIFEST.MF");
        Files.createDirectories(file.getParent());
        Manifest manifest = new Manifest();
        if (Files.isRegularFile(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                manifest.read(in);
            }
        }
        Attributes main = manifest.getMainAttributes();
        main.putIfAbsent(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.forEach(main::putValue);
        try (OutputStream out = Files.newOutputStream(file)) {
            manifest.write(out);
        }
    }

    /**
     * A file's SHA-1, in hex.
     *
     * @param file the file
     * @return its SHA-1
     * @throws IOException if it cannot be read
     */
    static String sha1(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every JDK has SHA-1", e);
        }
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[1 << 16];
            for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
