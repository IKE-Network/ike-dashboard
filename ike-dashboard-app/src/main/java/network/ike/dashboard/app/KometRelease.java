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

import java.net.URI;
import java.time.Instant;
import java.util.Optional;

/**
 * A Komet build the dashboard can install: a jlink image zip on Nexus
 * ({@code network.ike.platform:komet-app}), or, until the first of those is deployed, the signed
 * installer package of a GitHub pre-release.
 *
 * @param version    the app version, such as {@code 26.1024.212}
 * @param source     where it comes from
 * @param uri        the file to download
 * @param sizeBytes  its size, or {@code -1} when unknown
 * @param checksum   its checksum as {@code sha1:<hex>} or {@code sha256:<hex>}, if published
 * @param published  when it was published, if known
 * @param coordinate the Maven artifact, for one on Nexus
 * @param classifier the platform classifier, such as {@code osx-aarch64}
 */
public record KometRelease(String version, Source source, URI uri, long sizeBytes, Optional<String> checksum,
                           Optional<Instant> published, Optional<Coordinate> coordinate, String classifier) {

    /** Where a Komet build comes from. */
    public enum Source {
        /** The jlink image zip on Nexus, unpacked and run as it is. */
        NEXUS("Nexus"),
        /** The installer package of a GitHub release, whose application is taken out of it. */
        GITHUB("GitHub");

        private final String label;

        Source(String label) {
            this.label = label;
        }

        /**
         * How the dashboard names the source.
         *
         * @return the label
         */
        public String label() {
            return label;
        }
    }

    /**
     * Whether this build is newer than an installed version.
     *
     * @param installed the installed version
     * @return {@code true} when this one is newer
     */
    public boolean newerThan(String installed) {
        return KometInstallations.compareVersions(version, installed) > 0;
    }
}
