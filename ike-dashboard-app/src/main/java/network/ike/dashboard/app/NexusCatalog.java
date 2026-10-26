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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.stream.Collectors;

/**
 * The knowledge bases Nexus offers, asked of its search API: the store zips Komet opens once unpacked
 * ({@code rkb} for Rocks, {@code reasoned-sa} for a spined array) and the protobufs Komet loads into a
 * store ({@code reasoned-pb}, {@code unreasoned-pb}), newest deploy per artifact, version and
 * classifier. The {@link #catalog catalog} keeps only the artifacts that deploy a knowledge-base
 * descriptor ({@code properties:kb-descriptor}) at a supported level and their icon
 * ({@code png:icon}): small files read instead of the stores. Nexus answers search only for a
 * signed-in user, so this needs the repository's credentials.
 */
public final class NexusCatalog {

    /** The group knowledge bases deploy under. */
    public static final String GROUP = "network.ike.data";

    /** The classifiers of stores Komet opens, Rocks first. */
    public static final List<String> CLASSIFIERS = List.of("rkb", "reasoned-sa");

    /** The classifiers of protobufs: change sets Komet loads into a store, reasoned or not. */
    public static final List<String> PROTOBUF_CLASSIFIERS = List.of("reasoned-pb", "unreasoned-pb");

    /** Every classifier the catalog lists, stores first. */
    public static final List<String> OFFERED = java.util.stream.Stream.concat(CLASSIFIERS.stream(),
            PROTOBUF_CLASSIFIERS.stream()).toList();

    /** The classifier a knowledge base's icon deploys under, a PNG beside its store zips. */
    public static final String ICON_CLASSIFIER = "icon";

    /** The classifier a knowledge base's descriptor deploys under, a properties file beside its store zips. */
    public static final String DESCRIPTOR_CLASSIFIER = "kb-descriptor";

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    /**
     * A store or a protobuf Nexus offers.
     *
     * @param coordinate the artifact, at its base version ({@code -SNAPSHOT} for a snapshot)
     * @param classifier one of {@link #OFFERED}
     * @param sizeBytes  the zip's size
     * @param updated    when it was deployed
     * @param repository the hosted repository holding it
     */
    public record Offer(Coordinate coordinate, String classifier, long sizeBytes, Instant updated, String repository) {

        /**
         * How the dashboard names what the offer is.
         *
         * @return such as "Rocks" or "protobuf, reasoned"
         */
        public String label() {
            return NexusCatalog.label(classifier);
        }

        /**
         * Whether the offer is a store Komet opens, not a protobuf it loads.
         *
         * @return whether it is a store
         */
        public boolean store() {
            return CLASSIFIERS.contains(classifier);
        }
    }

    /**
     * A small file deployed beside a knowledge base's stores, its descriptor or its icon.
     *
     * @param coordinate  the artifact, at its base version
     * @param downloadUrl where the file is
     * @param updated     when it was deployed
     */
    record Attachment(Coordinate coordinate, URI downloadUrl, Instant updated) {
    }

    /**
     * A knowledge base on Nexus the dashboard manages: its descriptor meets the minimum and its icon is
     * beside it.
     *
     * @param descriptor the descriptor
     * @param icon       the icon's bytes, a PNG
     */
    public record Described(KnowledgeBaseDescriptor descriptor, byte[] icon) {
    }

    /**
     * What the Nexus window shows.
     *
     * @param offers    the stores of described artifacts
     * @param described each described artifact, by its coordinate at its base version
     */
    public record Catalog(List<Offer> offers, Map<Coordinate, Described> described) {

        /**
         * Copies the lists.
         *
         * @param offers    the stores of described artifacts
         * @param described each described artifact
         */
        public Catalog {
            offers = List.copyOf(offers);
            described = Map.copyOf(described);
        }

        /**
         * The catalog of the offers whose knowledge base is described; the others are left out.
         *
         * @param offers    every store Nexus offers
         * @param described each described artifact, by its coordinate at its base version
         * @return the catalog
         */
        public static Catalog of(List<Offer> offers, Map<Coordinate, Described> described) {
            return new Catalog(offers.stream().filter(offer -> described.containsKey(offer.coordinate())).toList(), described);
        }
    }

    /**
     * One page of a search answer.
     *
     * @param offers            the stores on the page
     * @param continuationToken where the next page starts, if there is one
     */
    record Page(List<Offer> offers, Optional<String> continuationToken) {
    }

    private NexusCatalog() {
    }

    /**
     * How the dashboard names what a classifier deploys.
     *
     * @param classifier one of {@link #OFFERED}
     * @return such as "Rocks" or "protobuf, reasoned"
     */
    public static String label(String classifier) {
        return switch (classifier) {
            case "rkb" -> "Rocks";
            case "reasoned-sa" -> "spined array";
            case "reasoned-pb" -> "protobuf, reasoned";
            case "unreasoned-pb" -> "protobuf, unreasoned";
            default -> classifier;
        };
    }

    /**
     * Lists the knowledge bases Nexus offers, the newest deploy of each.
     *
     * @param http       the client
     * @param repository the repository and its credentials
     * @return the offers, by artifact then classifier
     * @throws IOException          if Nexus cannot be asked or refuses
     * @throws InterruptedException if interrupted
     */
    public static List<Offer> list(HttpClient http, RepositoryAccess repository) throws IOException, InterruptedException {
        List<Offer> all = new ArrayList<>();
        for (String classifier : OFFERED) {
            Optional<String> token = Optional.empty();
            do {
                Map<String, String> query = new LinkedHashMap<>();
                query.put("group", GROUP);
                query.put("maven.extension", "zip");
                query.put("maven.classifier", classifier);
                token.ifPresent(value -> query.put("continuationToken", value));
                HttpRequest.Builder request = HttpRequest.newBuilder(searchUri(repository.url(), query)).timeout(TIMEOUT).GET();
                repository.credentials().ifPresent(credentials -> request.header("Authorization", NewerBuildCheck.basic(credentials)));
                HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    throw new IOException("Nexus search answered " + response.statusCode()
                            + (repository.credentials().isEmpty() ? " (no credentials for " + repository.serverId() + ")" : ""));
                }
                Page page = parse(response.body());
                all.addAll(page.offers());
                token = page.continuationToken();
            } while (token.isPresent());
        }
        return newest(all);
    }

    /**
     * The search address under the server that serves a repository.
     *
     * @param repositoryUrl a repository on the server
     * @param query         the search's parameters
     * @return the address
     */
    static URI searchUri(String repositoryUrl, Map<String, String> query) {
        String parameters = query.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        return URI.create(repositoryUrl).resolve("/service/rest/v1/search/assets?" + parameters);
    }

    /**
     * Reads a page of Nexus's asset search.
     *
     * @param json the answer
     * @return the stores and protobufs on it
     */
    @SuppressWarnings("unchecked")
    static Page parse(String json) {
        Map<String, Object> answer = (Map<String, Object>) Json.parse(json);
        List<Offer> offers = new ArrayList<>();
        for (Object item : (List<Object>) answer.getOrDefault("items", List.of())) {
            Map<String, Object> asset = (Map<String, Object>) item;
            Map<String, Object> maven = (Map<String, Object>) asset.get("maven2");
            String path = (String) asset.get("path");
            if (maven == null || path == null || !OFFERED.contains((String) maven.get("classifier"))) {
                continue;
            }
            String[] segments = path.split("/");
            String baseVersion = segments.length >= 2 ? segments[segments.length - 2] : (String) maven.get("version");
            Object size = asset.get("fileSize");
            Object modified = asset.get("lastModified");
            offers.add(new Offer(new Coordinate((String) maven.get("groupId"), (String) maven.get("artifactId"), baseVersion),
                    (String) maven.get("classifier"), size instanceof Number number ? number.longValue() : 0,
                    modified instanceof String text ? OffsetDateTime.parse(text).toInstant() : Instant.EPOCH,
                    (String) asset.get("repository")));
        }
        return new Page(offers, Optional.ofNullable((String) answer.get("continuationToken")));
    }

    /**
     * The stores Nexus offers of the knowledge bases that deploy a descriptor at a supported level and
     * an icon, with both read.
     *
     * @param http       the client
     * @param repository the repository and its credentials
     * @return the catalog
     * @throws IOException          if Nexus cannot be asked
     * @throws InterruptedException if interrupted
     */
    public static Catalog catalog(HttpClient http, RepositoryAccess repository) throws IOException, InterruptedException {
        Map<Coordinate, Attachment> icons = newestEach(search(http, repository, ICON_CLASSIFIER, "png"));
        Map<Coordinate, Described> described = new LinkedHashMap<>();
        for (Attachment file : newestEach(search(http, repository, DESCRIPTOR_CLASSIFIER, "properties")).values()) {
            Attachment icon = icons.get(file.coordinate());
            if (icon == null) {
                continue;
            }
            Optional<byte[]> properties = fetch(http, repository, file.downloadUrl());
            Optional<byte[]> png = fetch(http, repository, icon.downloadUrl());
            if (properties.isEmpty() || png.isEmpty()) {
                continue;
            }
            describedBy(properties.get()).ifPresent(descriptor -> described.put(file.coordinate(), new Described(descriptor, png.get())));
        }
        return Catalog.of(list(http, repository), described);
    }

    /**
     * Reads a descriptor deployed beside a knowledge base, as {@link KnowledgeBaseScanner} holds it to
     * the minimum.
     *
     * @param properties the file's bytes
     * @return the descriptor, when it meets the minimum
     */
    static Optional<KnowledgeBaseDescriptor> describedBy(byte[] properties) {
        Properties entries = new Properties();
        try {
            entries.load(new ByteArrayInputStream(properties));
        } catch (IOException | IllegalArgumentException e) {
            return Optional.empty();
        }
        return KnowledgeBaseScanner.knowledgeBaseDescriptor(entries).descriptor();
    }

    private static List<Attachment> search(HttpClient http, RepositoryAccess repository, String classifier, String extension)
            throws IOException, InterruptedException {
        List<Attachment> all = new ArrayList<>();
        Optional<String> token = Optional.empty();
        do {
            Map<String, String> query = new LinkedHashMap<>();
            query.put("group", GROUP);
            query.put("maven.extension", extension);
            query.put("maven.classifier", classifier);
            token.ifPresent(value -> query.put("continuationToken", value));
            HttpResponse<String> response = http.send(authorized(searchUri(repository.url(), query), repository),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException("Nexus search answered " + response.statusCode());
            }
            all.addAll(parseAttachments(response.body(), classifier, extension));
            token = Optional.ofNullable(continuation(response.body()));
        } while (token.isPresent());
        return all;
    }

    private static Map<Coordinate, Attachment> newestEach(List<Attachment> attachments) {
        Map<Coordinate, Attachment> newest = new LinkedHashMap<>();
        attachments.forEach(file -> newest.merge(file.coordinate(), file, (a, b) -> a.updated().isAfter(b.updated()) ? a : b));
        return newest;
    }

    private static Optional<byte[]> fetch(HttpClient http, RepositoryAccess repository, URI uri)
            throws IOException, InterruptedException {
        HttpResponse<byte[]> response = http.send(authorized(uri, repository), HttpResponse.BodyHandlers.ofByteArray());
        return response.statusCode() == 200 ? Optional.of(response.body()) : Optional.empty();
    }

    private static HttpRequest authorized(URI uri, RepositoryAccess repository) {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(TIMEOUT).GET();
        repository.credentials().ifPresent(credentials -> request.header("Authorization", NewerBuildCheck.basic(credentials)));
        return request.build();
    }

    @SuppressWarnings("unchecked")
    private static String continuation(String json) {
        return (String) ((Map<String, Object>) Json.parse(json)).get("continuationToken");
    }

    /**
     * Reads the files of one classifier and extension on one page of a search answer.
     *
     * @param json       the answer
     * @param classifier the classifier, such as {@code kb-descriptor}
     * @param extension  the extension, such as {@code properties}
     * @return the files, each at its artifact's base version
     */
    @SuppressWarnings("unchecked")
    static List<Attachment> parseAttachments(String json, String classifier, String extension) {
        Map<String, Object> answer = (Map<String, Object>) Json.parse(json);
        List<Attachment> files = new ArrayList<>();
        for (Object item : (List<Object>) answer.getOrDefault("items", List.of())) {
            Map<String, Object> asset = (Map<String, Object>) item;
            Map<String, Object> maven = (Map<String, Object>) asset.get("maven2");
            String url = (String) asset.get("downloadUrl");
            String path = (String) asset.get("path");
            if (maven == null || url == null || path == null || !classifier.equals(maven.get("classifier"))
                    || !extension.equals(maven.get("extension"))) {
                continue;
            }
            String[] segments = path.split("/");
            String baseVersion = segments.length >= 2 ? segments[segments.length - 2] : (String) maven.get("version");
            Object modified = asset.get("lastModified");
            files.add(new Attachment(new Coordinate((String) maven.get("groupId"), (String) maven.get("artifactId"), baseVersion),
                    URI.create(url), modified instanceof String text ? OffsetDateTime.parse(text).toInstant() : Instant.EPOCH));
        }
        return files;
    }

    /**
     * Keeps the newest deploy of each artifact, version and classifier.
     *
     * @param offers every deploy
     * @return one offer each, by artifact, then version, then classifier as {@link #OFFERED} orders them
     */
    static List<Offer> newest(List<Offer> offers) {
        Map<String, Offer> newest = new LinkedHashMap<>();
        for (Offer offer : offers) {
            newest.merge(offer.coordinate() + ":" + offer.classifier(), offer,
                    (a, b) -> a.updated().isAfter(b.updated()) ? a : b);
        }
        return newest.values().stream()
                .sorted(Comparator.comparing((Offer offer) -> offer.coordinate().artifactId())
                        .thenComparing(offer -> offer.coordinate().version())
                        .thenComparing(offer -> OFFERED.indexOf(offer.classifier())))
                .toList();
    }
}
