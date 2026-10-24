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

import java.util.Optional;

/**
 * What the newer-build check found for one knowledge base.
 *
 * @param state  the finding
 * @param detail what to tell the person
 * @param newest the newest deploy, when Nexus listed one
 */
public record BuildStatus(State state, String detail, Optional<SnapshotMetadata.Deploy> newest) {

    /** The findings. */
    public enum State {
        /** Not checked yet. */
        UNCHECKED("not checked"),
        /** The store is the newest build. */
        CURRENT("current"),
        /** Nexus has a newer build. */
        NEWER("newer build"),
        /** Nexus has the coordinate's metadata, but no comparison could be made. */
        UNKNOWN("cannot tell"),
        /** The store does not say which build it is. */
        UNIDENTIFIED("unidentified"),
        /** Nexus has no such build. */
        NOT_ON_NEXUS("not on Nexus"),
        /** The check failed. */
        ERROR("check failed");

        private final String label;

        State(String label) {
            this.label = label;
        }

        /**
         * How the dashboard names the finding.
         *
         * @return the label
         */
        public String label() {
            return label;
        }
    }

    /** A knowledge base not checked yet. */
    public static final BuildStatus UNCHECKED = of(State.UNCHECKED, "");

    /**
     * A finding without a deploy.
     *
     * @param state  the finding
     * @param detail what to tell the person
     * @return the status
     */
    public static BuildStatus of(State state, String detail) {
        return new BuildStatus(state, detail, Optional.empty());
    }
}
