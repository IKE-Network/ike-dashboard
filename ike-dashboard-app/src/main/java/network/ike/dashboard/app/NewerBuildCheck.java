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

import network.ike.dashboard.download.Credentials;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

/**
 * Asks Nexus whether a newer build of a knowledge base is deployed: rung 1 of decision 5 in the
 * {@code dev-ike-dashboard} topic, on the snapshot's {@code maven-metadata.xml}.
 */
public final class NewerBuildCheck {

    /** How much later than a store's assembly a deploy must be to be another build. */
    static final Duration SAME_BUILD_WINDOW = Duration.ofHours(1);

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private final HttpClient http;
    private final RepositoryAccess repository;

    /**
     * Creates a check against a repository.
     *
     * @param http       the client to ask with
     * @param repository the repository and its login
     */
    public NewerBuildCheck(HttpClient http, RepositoryAccess repository) {
        this.http = http;
        this.repository = repository;
    }

    /**
     * Checks one knowledge base.
     *
     * @param knowledgeBase the knowledge base
     * @return what the check found
     */
    public BuildStatus check(KnowledgeBase knowledgeBase) {
        if (knowledgeBase.coordinate().isEmpty()) {
            return BuildStatus.of(BuildStatus.State.UNIDENTIFIED, "no Maven descriptor in the store");
        }
        Coordinate coordinate = knowledgeBase.coordinate().get();
        String classifier = knowledgeBase.kind().classifier();
        if (classifier.isEmpty()) {
            return BuildStatus.of(BuildStatus.State.UNIDENTIFIED, "no published classifier for " + knowledgeBase.kind().label());
        }
        if (!coordinate.snapshot()) {
            return BuildStatus.of(BuildStatus.State.UNKNOWN, "release versions are not checked yet");
        }
        URI uri = metadataUri(repository.url(), coordinate);
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(TIMEOUT).GET();
        repository.credentials().ifPresent(login -> request.header("Authorization", basic(login)));
        try {
            HttpResponse<InputStream> response = http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() == 401 || response.statusCode() == 403) {
                    return BuildStatus.of(BuildStatus.State.ERROR, "Nexus refused the login for " + repository.serverId()
                            + (repository.credentials().isEmpty() ? " (no login in settings.xml)" : ""));
                }
                if (response.statusCode() == 404) {
                    return BuildStatus.of(BuildStatus.State.NOT_ON_NEXUS, coordinate + " is not deployed");
                }
                if (response.statusCode() != 200) {
                    return BuildStatus.of(BuildStatus.State.ERROR, "Nexus answered " + response.statusCode());
                }
                Optional<SnapshotMetadata.Deploy> newest = SnapshotMetadata.newest(body, classifier, "zip");
                if (newest.isEmpty()) {
                    return BuildStatus.of(BuildStatus.State.NOT_ON_NEXUS, "no " + classifier + " zip deployed");
                }
                return compare(knowledgeBase, newest.get());
            }
        } catch (IOException e) {
            return BuildStatus.of(BuildStatus.State.ERROR, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return BuildStatus.of(BuildStatus.State.ERROR, "interrupted");
        }
    }

    /**
     * Compares a store with the newest deploy. A store a download unpacked names its deploy, and is
     * current only when that is the newest. A store assembled locally names only when it was
     * assembled: a deploy more than {@link #SAME_BUILD_WINDOW} later is another build.
     *
     * @param knowledgeBase the store
     * @param newest        the newest deploy
     * @return the status
     */
    static BuildStatus compare(KnowledgeBase knowledgeBase, SnapshotMetadata.Deploy newest) {
        Optional<String> resolved = knowledgeBase.resolvedVersion();
        if (resolved.isPresent()) {
            return resolved.get().equals(newest.value())
                    ? new BuildStatus(BuildStatus.State.CURRENT, "the newest deploy, " + newest.value(), Optional.of(newest))
                    : new BuildStatus(BuildStatus.State.NEWER, "newer build " + newest.value() + " (this is "
                    + resolved.get() + ")", Optional.of(newest));
        }
        Optional<Instant> assembled = knowledgeBase.assembledAt();
        if (assembled.isEmpty()) {
            return new BuildStatus(BuildStatus.State.UNKNOWN, "newest deploy " + newest.value()
                    + "; the store does not say when it was assembled", Optional.of(newest));
        }
        return newest.updated().isAfter(assembled.get().plus(SAME_BUILD_WINDOW))
                ? new BuildStatus(BuildStatus.State.NEWER, "newer build " + newest.value(), Optional.of(newest))
                : new BuildStatus(BuildStatus.State.CURRENT, "no deploy since this store was assembled", Optional.of(newest));
    }

    /**
     * The HTTP Basic {@code Authorization} value of a login.
     *
     * @param login the login
     * @return the header value
     */
    static String basic(Credentials login) {
        String pair = login.username() + ":" + new String(login.password());
        return "Basic " + Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * A snapshot version's {@code maven-metadata.xml}.
     *
     * @param repositoryUrl the repository's base URL
     * @param coordinate    the coordinate
     * @return its address
     */
    static URI metadataUri(String repositoryUrl, Coordinate coordinate) {
        String base = repositoryUrl.endsWith("/") ? repositoryUrl : repositoryUrl + "/";
        return URI.create(base + coordinate.groupId().replace('.', '/') + "/" + coordinate.artifactId() + "/"
                + coordinate.version() + "/maven-metadata.xml");
    }
}
