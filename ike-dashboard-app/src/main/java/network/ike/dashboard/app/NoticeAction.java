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
     * Download a store from Nexus into {@code ~/Solor}: a knowledge base not yet here.
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
     * Upgrade a store to the newest build in place, keeping every change set, the preferences and every
     * repository in it, with the previous build kept beside it (decision 20).
     *
     * @param store      the store
     * @param coordinate the knowledge base's coordinate
     * @param classifier the store's classifier
     */
    record Upgrade(KnowledgeBase store, Coordinate coordinate, String classifier) implements NoticeAction {
        @Override
        public String label() {
            return "Upgrade";
        }
    }

    /**
     * Reinstall a store: the newest build under the same name, with nothing of the person's; the old
     * store goes to the Trash (decision 20).
     *
     * @param store      the store
     * @param coordinate the knowledge base's coordinate
     * @param classifier the store's classifier
     */
    record Reinstall(KnowledgeBase store, Coordinate coordinate, String classifier) implements NoticeAction {
        @Override
        public String label() {
            return "Reinstall";
        }
    }

    /**
     * Install a copy of the newest build beside the copy the person has, with the version appended
     * (decision 20).
     *
     * @param coordinate the knowledge base's coordinate
     * @param classifier the store's classifier
     */
    record InstallCopy(Coordinate coordinate, String classifier) implements NoticeAction {
        @Override
        public String label() {
            return "Install a copy";
        }
    }

    /**
     * Put a store's previous build back, carrying the work done since (decision 20).
     *
     * @param store the store
     */
    record Restore(KnowledgeBase store) implements NoticeAction {
        @Override
        public String label() {
            return "Restore";
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
     * Replace the dashboard with a newer build and restart it.
     *
     * @param release the build
     */
    record UpdateDashboard(KometRelease release) implements NoticeAction {
        @Override
        public String label() {
            return "Update and restart";
        }
    }

    /** Move a translocated dashboard into {@code ~/Applications}, where it can update itself, and restart it there. */
    record MoveDashboard() implements NoticeAction {
        @Override
        public String label() {
            return "Move and restart";
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
