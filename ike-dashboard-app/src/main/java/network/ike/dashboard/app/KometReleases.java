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

import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the newest Komet build for this machine: on Nexus first, the jlink image the installer build
 * deploys as {@code network.ike.platform:komet-app}, from the public repository, without credentials;
 * on GitHub only while Nexus has none, the newest release's installer package.
 */
public final class KometReleases {

    /** The Komet app's group. */
    public static final String GROUP = "network.ike.platform";

    /** The Komet app's artifact: the runnable jlink image, zipped. */
    public static final String ARTIFACT = "komet-app";

    /** The public repository group the image deploys into. */
    public static final String PUBLIC_URL = "https://nexus.tinkar.org/repository/ike-public/";

    /** The repository whose releases carry the installers. */
    public static final String GITHUB_REPOSITORY = "IKE-Network/ike-komet-wsr";

    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final Pattern PKG_VERSION = Pattern.compile("-(\\d+(?:\\.\\d+)+)-([a-z]+-[a-z0-9_]+)\\.pkg$");

    private KometReleases() {
    }

    /**
     * The platform classifier of this machine, as the installer build names its images.
     *
     * @return such as {@code osx-aarch64}
     */
    public static String platformClassifier() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String platform = os.contains("mac") ? "osx" : os.contains("win") ? "windows" : "linux";
        String cpu = arch.equals("aarch64") || arch.equals("arm64") ? "aarch64" : "x86_64";
        return platform + "-" + cpu;
    }

    /**
     * The newest Komet build: Nexus's when it has one, else GitHub's.
     *
     * @param http the client
     * @return the build, if either source has one for this machine
     * @throws IOException          if neither can be asked
     * @throws InterruptedException if interrupted
     */
    public static Optional<KometRelease> newest(HttpClient http) throws IOException, InterruptedException {
        String classifier = platformClassifier();
        Optional<KometRelease> nexus = newestOnNexus(http, PUBLIC_URL, classifier);
        return nexus.isPresent() ? nexus : newestOnGitHub(http, GITHUB_REPOSITORY, classifier);
    }

    /**
     * The newest {@code komet-app} image on Nexus for a platform.
     *
     * @param http          the client
     * @param repositoryUrl the repository
     * @param classifier    the platform
     * @return the image, if one is deployed
     * @throws IOException          if Nexus cannot be asked
     * @throws InterruptedException if interrupted
     */
    public static Optional<KometRelease> newestOnNexus(HttpClient http, String repositoryUrl, String classifier)
            throws IOException, InterruptedException {
        return newestOnNexus(http, repositoryUrl, GROUP, ARTIFACT, classifier);
    }

    /**
     * The newest version of a zipped application on Nexus that has a zip for a platform: the newest
     * version in its {@code maven-metadata.xml} whose classified zip answers, with the {@code .sha1}
     * Nexus keeps beside it. Komet's image and the dashboard's own application are found this way.
     *
     * @param http          the client
     * @param repositoryUrl the repository
     * @param group         the group
     * @param artifact      the artifact
     * @param classifier    the platform
     * @return the zip, if one is deployed
     * @throws IOException          if Nexus cannot be asked
     * @throws InterruptedException if interrupted
     */
    public static Optional<KometRelease> newestOnNexus(HttpClient http, String repositoryUrl, String group,
                                                       String artifact, String classifier)
            throws IOException, InterruptedException {
        String base = repositoryUrl.endsWith("/") ? repositoryUrl : repositoryUrl + "/";
        String directory = base + group.replace('.', '/') + "/" + artifact + "/";
        HttpResponse<byte[]> metadata = http.send(HttpRequest.newBuilder(URI.create(directory + "maven-metadata.xml"))
                .timeout(TIMEOUT).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        if (metadata.statusCode() == 404) {
            return Optional.empty();
        }
        if (metadata.statusCode() != 200) {
            throw new IOException("Nexus answered " + metadata.statusCode() + " for the " + artifact + " metadata");
        }
        List<String> versions = new ArrayList<>(versions(metadata.body()));
        versions.sort(Comparator.comparing((String version) -> version, KometInstallations::compareVersions).reversed());
        for (String version : versions) {
            URI zip = URI.create(directory + version + "/" + artifact + "-" + version + "-" + classifier + ".zip");
            HttpResponse<Void> head = http.send(HttpRequest.newBuilder(zip).timeout(TIMEOUT)
                    .method("HEAD", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding());
            if (head.statusCode() != 200) {
                continue;
            }
            HttpResponse<String> sha1 = http.send(HttpRequest.newBuilder(URI.create(zip + ".sha1")).timeout(TIMEOUT).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            Optional<String> checksum = sha1.statusCode() == 200 && !sha1.body().isBlank()
                    ? Optional.of("sha1:" + sha1.body().strip().split("\\s+")[0]) : Optional.empty();
            return Optional.of(new KometRelease(version, KometRelease.Source.NEXUS, zip,
                    head.headers().firstValueAsLong("Content-Length").orElse(-1), checksum, Optional.empty(),
                    Optional.of(new Coordinate(group, artifact, version)), classifier));
        }
        return Optional.empty();
    }

    /**
     * The newest GitHub release carrying an installer package for a platform.
     *
     * @param http       the client
     * @param repository the repository, {@code owner/name}
     * @param classifier the platform
     * @return the package, if a recent release has one
     * @throws IOException          if GitHub cannot be asked
     * @throws InterruptedException if interrupted
     */
    public static Optional<KometRelease> newestOnGitHub(HttpClient http, String repository, String classifier)
            throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(
                        URI.create("https://api.github.com/repos/" + repository + "/releases?per_page=20"))
                .header("Accept", "application/vnd.github+json").timeout(TIMEOUT).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("GitHub answered " + response.statusCode() + " for " + repository + "'s releases");
        }
        return fromGitHub(response.body(), classifier);
    }

    /**
     * Reads GitHub's release list, newest first, for the first installer package of a platform.
     *
     * @param json       the release list
     * @param classifier the platform
     * @return the package, if any release has one
     */
    @SuppressWarnings("unchecked")
    static Optional<KometRelease> fromGitHub(String json, String classifier) {
        for (Object item : (List<Object>) Json.parse(json)) {
            Map<String, Object> release = (Map<String, Object>) item;
            if (Boolean.TRUE.equals(release.get("draft"))) {
                continue;
            }
            for (Object element : (List<Object>) release.getOrDefault("assets", List.of())) {
                Map<String, Object> asset = (Map<String, Object>) element;
                Matcher matcher = PKG_VERSION.matcher(String.valueOf(asset.get("name")));
                if (!matcher.find() || !matcher.group(2).equals(classifier)) {
                    continue;
                }
                Object size = asset.get("size");
                Object published = release.get("published_at");
                return Optional.of(new KometRelease(matcher.group(1), KometRelease.Source.GITHUB,
                        URI.create(String.valueOf(asset.get("browser_download_url"))),
                        size instanceof Number number ? number.longValue() : -1,
                        Optional.ofNullable((String) asset.get("digest")),
                        published instanceof String text ? Optional.of(Instant.parse(text)) : Optional.empty(),
                        Optional.empty(), classifier));
            }
        }
        return Optional.empty();
    }

    /**
     * The versions a {@code maven-metadata.xml} lists.
     *
     * @param metadata the metadata
     * @return the versions, in the order listed
     * @throws IOException if it is not metadata
     */
    static List<String> versions(byte[] metadata) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(null);
            Document document = builder.parse(new ByteArrayInputStream(metadata));
            NodeList nodes = document.getElementsByTagName("version");
            List<String> versions = new ArrayList<>();
            for (int i = 0; i < nodes.getLength(); i++) {
                if (nodes.item(i).getParentNode().getNodeName().equals("versions")) {
                    versions.add(nodes.item(i).getTextContent().strip());
                }
            }
            return versions;
        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException("Not Maven metadata: " + e.getMessage(), e);
        }
    }
}
