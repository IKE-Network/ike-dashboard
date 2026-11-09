/*
 * Copyright © 2026 Knowledge Graphlet / IKE Network
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What changed in a Komet version (KEC, 2026-11-08): the notes of the checkpoint release on GitHub whose installer
 * is that version. A checkpoint's notes list, member by member, the commits since the checkpoint before, each linked;
 * they are read here into members and commits, so the dashboard shows them and links to each.
 *
 * @param version   the Komet version
 * @param title     the release's name, such as {@code Checkpoint main-20261108-191310}
 * @param url       the release on GitHub
 * @param published when it was published, if known
 * @param members   what changed in each member, in the order the notes list them
 */
public record KometNotes(String version, String title, URI url, Optional<Instant> published, List<Member> members) {

    /**
     * What changed in one member of the working set.
     *
     * @param name    the member, such as {@code komet-desktop}
     * @param compare the comparison of its two commits on GitHub, if the notes link one
     * @param commits its commits, oldest first, as the notes list them
     */
    public record Member(String name, Optional<URI> compare, List<Commit> commits) {
    }

    /**
     * One commit.
     *
     * @param sha     its short id
     * @param url     the commit on GitHub
     * @param subject its subject line
     */
    public record Commit(String sha, URI url, String subject) {
    }

    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final Pattern INSTALLER_VERSION = Pattern.compile("-(\\d+(?:\\.\\d+)+)-[a-z]+-[a-z0-9_]+\\.(?:pkg|msi)$");
    private static final Pattern MEMBER = Pattern.compile("^###\\s+(\\S+)(?:\\s+[—-]+\\s+\\[[^\\]]*\\]\\(([^)]+)\\))?");
    private static final Pattern COMMIT = Pattern.compile("^[-*]\\s+\\[`?([0-9a-f]{7,40})`?\\]\\(([^)]+)\\)\\s*(.*)$");

    /**
     * The notes of the recent checkpoint releases, by the Komet versions their installers carry.
     *
     * @param http       the client
     * @param repository the repository, {@code owner/name}
     * @return the notes, by version
     * @throws IOException          if GitHub cannot be asked
     * @throws InterruptedException if interrupted
     */
    public static Map<String, KometNotes> fetch(HttpClient http, String repository) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(
                        URI.create("https://api.github.com/repos/" + repository + "/releases?per_page=30"))
                .header("Accept", "application/vnd.github+json").timeout(TIMEOUT).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("GitHub answered " + response.statusCode() + " for " + repository + "'s releases");
        }
        return read(response.body());
    }

    /**
     * Reads GitHub's release list: each release, under every Komet version one of its installers carries.
     *
     * @param json the release list
     * @return the notes, by version
     */
    @SuppressWarnings("unchecked")
    static Map<String, KometNotes> read(String json) {
        Map<String, KometNotes> byVersion = new LinkedHashMap<>();
        for (Object item : (List<Object>) Json.parse(json)) {
            Map<String, Object> release = (Map<String, Object>) item;
            if (Boolean.TRUE.equals(release.get("draft"))) {
                continue;
            }
            String title = String.valueOf(release.getOrDefault("name", release.get("tag_name")));
            URI url = URI.create(String.valueOf(release.get("html_url")));
            Object published = release.get("published_at");
            Optional<Instant> when = published instanceof String text ? Optional.of(Instant.parse(text)) : Optional.empty();
            List<Member> members = members(String.valueOf(release.getOrDefault("body", "")));
            for (Object element : (List<Object>) release.getOrDefault("assets", List.of())) {
                Matcher matcher = INSTALLER_VERSION.matcher(String.valueOf(((Map<String, Object>) element).get("name")));
                if (matcher.find()) {
                    byVersion.putIfAbsent(matcher.group(1), new KometNotes(matcher.group(1), title, url, when, members));
                }
            }
        }
        return byVersion;
    }

    /** The members and commits of a checkpoint's notes. */
    static List<Member> members(String body) {
        List<Member> members = new ArrayList<>();
        String name = null;
        Optional<URI> compare = Optional.empty();
        List<Commit> commits = new ArrayList<>();
        for (String line : body.lines().map(String::strip).toList()) {
            Matcher member = MEMBER.matcher(line);
            if (member.find()) {
                if (name != null) {
                    members.add(new Member(name, compare, List.copyOf(commits)));
                }
                name = member.group(1);
                compare = Optional.ofNullable(member.group(2)).map(URI::create);
                commits = new ArrayList<>();
                continue;
            }
            Matcher commit = COMMIT.matcher(line);
            if (name != null && commit.find()) {
                commits.add(new Commit(commit.group(1), URI.create(commit.group(2)), commit.group(3).strip()));
            }
        }
        if (name != null) {
            members.add(new Member(name, compare, List.copyOf(commits)));
        }
        return List.copyOf(members);
    }
}
