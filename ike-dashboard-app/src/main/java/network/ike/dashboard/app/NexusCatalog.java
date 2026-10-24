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
import java.util.stream.Collectors;

/**
 * The knowledge bases Nexus offers, asked of its search API: the store zips Komet opens once unpacked
 * ({@code rkb} for Rocks, {@code reasoned-sa} for a spined array), newest deploy per artifact, version
 * and classifier. Nexus answers search only for a signed-in user, so this needs the repository's
 * credentials.
 */
public final class NexusCatalog {

    /** The group knowledge bases deploy under. */
    public static final String GROUP = "network.ike.data";

    /** The classifiers of stores Komet opens, Rocks first. */
    public static final List<String> CLASSIFIERS = List.of("rkb", "reasoned-sa");

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    /**
     * A store Nexus offers.
     *
     * @param coordinate the artifact, at its base version ({@code -SNAPSHOT} for a snapshot)
     * @param classifier {@code rkb} or {@code reasoned-sa}
     * @param sizeBytes  the zip's size
     * @param updated    when it was deployed
     * @param repository the hosted repository holding it
     */
    public record Offer(Coordinate coordinate, String classifier, long sizeBytes, Instant updated, String repository) {

        /**
         * How the dashboard names the store's layout.
         *
         * @return "Rocks" or "spined array"
         */
        public String label() {
            return "rkb".equals(classifier) ? "Rocks" : "spined array";
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
        for (String classifier : CLASSIFIERS) {
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
     * @return the stores on it
     */
    @SuppressWarnings("unchecked")
    static Page parse(String json) {
        Map<String, Object> answer = (Map<String, Object>) Json.parse(json);
        List<Offer> offers = new ArrayList<>();
        for (Object item : (List<Object>) answer.getOrDefault("items", List.of())) {
            Map<String, Object> asset = (Map<String, Object>) item;
            Map<String, Object> maven = (Map<String, Object>) asset.get("maven2");
            String path = (String) asset.get("path");
            if (maven == null || path == null || !CLASSIFIERS.contains((String) maven.get("classifier"))) {
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
     * Keeps the newest deploy of each artifact, version and classifier.
     *
     * @param offers every deploy
     * @return one offer each, by artifact, then version, then classifier as {@link #CLASSIFIERS} orders them
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
                        .thenComparing(offer -> CLASSIFIERS.indexOf(offer.classifier())))
                .toList();
    }
}
