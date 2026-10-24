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
 * The one button a notice carries: a verb and its argument (the IKE Dashboard design, decision 14).
 * A computed notice and, later, an authored one name their action from this vocabulary, so the same
 * card serves "a newer build of solor-kb is available" and "try the new SOLOR-NUVA store".
 */
public sealed interface NoticeAction {

    /**
     * What the button says.
     *
     * @return the label
     */
    String label();

    /**
     * Download a store from Nexus into {@code ~/Solor}: a knowledge base not yet here, or a newer build of one that is.
     *
     * @param coordinate the knowledge base's coordinate
     * @param classifier the store's classifier
     */
    record Download(Coordinate coordinate, String classifier) implements NoticeAction {
        @Override
        public String label() {
            return "Download";
        }
    }

    /**
     * Upgrade the change sets of a store in a Komet with no window.
     *
     * @param store the store
     */
    record UpgradeChangeSets(KnowledgeBase store) implements NoticeAction {
        @Override
        public String label() {
            return "Upgrade change sets";
        }
    }

    /**
     * Install a Komet build beside the ones installed.
     *
     * @param release the build
     */
    record InstallKomet(KometRelease release) implements NoticeAction {
        @Override
        public String label() {
            return "Install Komet " + release.version();
        }
    }

    /**
     * Show a knowledge base in the list, where its detail offers what can be done with it.
     *
     * @param family the knowledge base's name
     */
    record Show(String family) implements NoticeAction {
        @Override
        public String label() {
            return "Show";
        }
    }

    /** Open the window of the knowledge bases Nexus offers. */
    record Browse() implements NoticeAction {
        @Override
        public String label() {
            return "Browse Nexus";
        }
    }
}
