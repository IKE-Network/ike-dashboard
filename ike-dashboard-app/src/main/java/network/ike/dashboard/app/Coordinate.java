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

/**
 * A Maven coordinate without a classifier: what a store's {@code pom.properties} holds.
 *
 * @param groupId    the group
 * @param artifactId the artifact
 * @param version    the version, such as {@code 1-SNAPSHOT}
 */
public record Coordinate(String groupId, String artifactId, String version) {

    /**
     * Whether the version is a snapshot line.
     *
     * @return {@code true} for a {@code -SNAPSHOT} version
     */
    public boolean snapshot() {
        return version.endsWith("-SNAPSHOT");
    }

    /**
     * Reads {@code groupId:artifactId:version}.
     *
     * @param text the coordinate
     * @return the coordinate
     * @throws IllegalArgumentException if it has not three parts
     */
    public static Coordinate parse(String text) {
        String[] parts = text.split(":");
        if (parts.length != 3 || parts[0].isBlank() || parts[1].isBlank() || parts[2].isBlank()) {
            throw new IllegalArgumentException("Expected groupId:artifactId:version, got " + text);
        }
        return new Coordinate(parts[0], parts[1], parts[2]);
    }

    @Override
    public String toString() {
        return groupId + ":" + artifactId + ":" + version;
    }
}
