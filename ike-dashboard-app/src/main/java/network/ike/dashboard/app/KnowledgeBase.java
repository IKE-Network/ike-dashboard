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

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One entry of {@code ~/Solor}, as its files describe it.
 *
 * @param name       the folder or file name
 * @param path       where it is
 * @param kind       what it is
 * @param sizeBytes  its size on disk
 * @param coordinate the Maven coordinate its descriptor names, if it carries one
 * @param attributes the {@code Ike-*} attributes of its {@code META-INF/MANIFEST.MF}, by name
 * @param modified   when the folder or file was last changed
 */
public record KnowledgeBase(String name, Path path, StoreKind kind, long sizeBytes, Optional<Coordinate> coordinate,
                            Map<String, String> attributes, Instant modified) {

    /** The manifest attribute holding when the store was assembled. */
    public static final String ASSEMBLED_AT = "Ike-Assembled-At";

    /** The manifest attribute naming the deploy a download unpacked. */
    public static final String RESOLVED_VERSION = "Ike-Resolved-Version";

    private static final Pattern DEPLOY_STAMP = Pattern.compile("-(\\d{8})\\.(\\d{6})-\\d+$");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /** The manifest attribute naming the store's entity format. */
    public static final String ENTITY_FORMAT = "Ike-Entity-Format";

    /** The manifest attribute naming the "Open …" provider. */
    public static final String OPEN_CONTROLLER = "Ike-Open-Controller";

    /**
     * Copies the attributes.
     *
     * @param name       the folder or file name
     * @param path       where it is
     * @param kind       what it is
     * @param sizeBytes  its size on disk
     * @param coordinate the Maven coordinate its descriptor names, if it carries one
     * @param attributes the {@code Ike-*} attributes
     * @param modified   when it was last changed
     */
    public KnowledgeBase {
        attributes = Map.copyOf(attributes);
    }

    /**
     * Whether the store says which build it is.
     *
     * @return {@code true} when it carries a Maven descriptor
     */
    public boolean identified() {
        return coordinate.isPresent();
    }

    /**
     * When the store was assembled, if its manifest says.
     *
     * @return the time of assembly
     */
    public Optional<Instant> assembledAt() {
        String value = attributes.get(ASSEMBLED_AT);
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(Instant.parse(value));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    /**
     * The store's entity format, if its manifest says.
     *
     * @return the entity format, such as 3
     */
    public Optional<Integer> entityFormat() {
        try {
            return Optional.ofNullable(attributes.get(ENTITY_FORMAT)).map(String::trim).map(Integer::valueOf);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /**
     * When this copy was made, as near as its files say: the deploy a download names, else when it was
     * assembled, else when its folder last changed.
     *
     * @return the time
     */
    public Instant recency() {
        Optional<Instant> deployed = resolvedVersion().flatMap(KnowledgeBase::deployTime);
        return deployed.or(this::assembledAt).orElse(modified);
    }

    /**
     * The deploy time a timestamped snapshot version carries, {@code <base>-yyyyMMdd.HHmmss-<n>}.
     *
     * @param version the version
     * @return the time, or empty when the version carries none
     */
    static Optional<Instant> deployTime(String version) {
        Matcher matcher = DEPLOY_STAMP.matcher(version);
        if (!matcher.find()) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDateTime.parse(matcher.group(1) + matcher.group(2), STAMP).toInstant(ZoneOffset.UTC));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    /**
     * The deploy a download unpacked, if a download wrote it.
     *
     * @return the resolved version, such as {@code 20250627.1-20261024.001942-7}
     */
    public Optional<String> resolvedVersion() {
        return Optional.ofNullable(attributes.get(RESOLVED_VERSION));
    }
}
