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

import java.util.Optional;
import java.util.prefs.Preferences;

/**
 * Every preference the dashboard keeps, named once (KEC, 2026-11-08: align with {@code dev.ikm.komet.preferences}).
 * Like Komet's {@code KometPreferences}, a value is read and written by an enum key, and a read that may find
 * nothing returns an {@link Optional}. The values are kept in the JDK's user preferences, the store Komet's
 * {@code PreferencesService.userPreferences()} wraps, so the dashboard needs no Komet library. Everything here is
 * this person's, on this machine: which Komets are installed differs from machine to machine, so nothing here
 * belongs in a knowledge base's own configuration preferences, which travel with it.
 *
 * <p>Unlike {@code KometPreferences.enumToGeneralKey}, which derives a key from the enum's class name, each
 * constant here carries the exact name it is stored under. Renaming a constant, or moving this class to another
 * package, changes nothing on disk, so saved values are never lost that way. The stored names are the ones the
 * dashboard has always used.
 */
public final class DashboardPreferences {

    /** A preferences node of the dashboard's: the root, or a node beneath it. */
    public enum Node {
        /** The dashboard's root node. */
        DASHBOARD(null, "network/ike/dashboard"),
        /** The memory rule ({@link HeapSizing.Rule}). */
        HEAP(DASHBOARD, "heap"),
        /** Each store's memory, set by the person, keyed by store. */
        HEAP_STORES(HEAP, "stores"),
        /** The notices the person dismissed, keyed by notice id. */
        DISMISSED_NOTICES(DASHBOARD, "dismissed-notices"),
        /** When each knowledge base was last opened, keyed by family name. */
        OPENED(DASHBOARD, "opened"),
        /** The Komet each knowledge base is pinned to, keyed by family name. */
        KOMET_PINS(DASHBOARD, "komet-pins");

        private final Node parent;
        private final String stored;

        Node(Node parent, String stored) {
            this.parent = parent;
            this.stored = stored;
        }

        /**
         * The name the node is stored under, relative to its parent.
         *
         * @return the stored name
         */
        public String stored() {
            return stored;
        }
    }

    /** A value of the dashboard's, in the node it belongs to. */
    public enum Key {
        /** Whether the list is sorted by when each knowledge base was last opened. */
        SORT_RECENT(Node.DASHBOARD, "sortRecent"),
        /** The folder of knowledge bases. */
        SOLOR(Node.DASHBOARD, "solor"),
        /** Where the dashboard installs Komet versions. */
        INSTALL_LOCATION(Node.DASHBOARD, "installLocation"),
        /** The Maven repository the dashboard reads Nexus through. */
        REPOSITORY_URL(Node.DASHBOARD, "repositoryUrl"),
        /** The {@code settings.xml} server whose login reaches it. */
        SERVER_ID(Node.DASHBOARD, "serverId"),
        /** The local Maven repository. */
        LOCAL_REPOSITORY(Node.DASHBOARD, "localRepository"),
        /** Where the dashboard writes its logs. */
        LOG_FOLDER(Node.DASHBOARD, "logFolder"),
        /** The collections whose knowledge bases the dashboard keeps at hand. */
        COLLECTIONS(Node.DASHBOARD, "collections"),
        /** The folder new knowledge-base projects are made in. */
        PROJECTS(Node.DASHBOARD, "projects"),
        /** Heap, as a share of a spined store's size. */
        HEAP_SPINED64_SHARE(Node.HEAP, "spined64Share"),
        /** Heap, as a share of a Rocks store's size. */
        HEAP_ROCKS_SHARE(Node.HEAP, "rocksShare"),
        /** Rocks cache, as a share of its size. */
        HEAP_CACHE_SHARE(Node.HEAP, "cacheShare"),
        /** For the reasoner, times the stated axioms' size. */
        HEAP_AXIOM_SHARE(Node.HEAP, "axiomShare"),
        /** Measured at assembly, times the live heap. */
        HEAP_MEASURED_ROOM(Node.HEAP, "measuredRoom"),
        /** The least heap. */
        HEAP_MINIMUM(Node.HEAP, "minimumHeap"),
        /** Kept for everything else: at least this. */
        HEAP_RESERVE_FLOOR(Node.HEAP, "reserveFloor"),
        /** Kept for everything else: or this share of the machine's memory. */
        HEAP_RESERVE_SHARE(Node.HEAP, "reserveShare");

        private final Node node;
        private final String stored;

        Key(Node node, String stored) {
            this.node = node;
            this.stored = stored;
        }

        /**
         * The node the value is kept in.
         *
         * @return the node
         */
        public Node node() {
            return node;
        }

        /**
         * The name the value is stored under in its node.
         *
         * @return the stored name
         */
        public String stored() {
            return stored;
        }
    }

    private final Preferences root;

    /**
     * The dashboard's preferences under a root node: the person's, or a test's.
     *
     * @param root the node that stands for {@link Node#DASHBOARD}
     */
    public DashboardPreferences(Preferences root) {
        this.root = root;
    }

    /**
     * The preferences of the person using the dashboard.
     *
     * @return them
     */
    public static DashboardPreferences current() {
        return new DashboardPreferences(Preferences.userRoot().node(Node.DASHBOARD.stored()));
    }

    /**
     * A node of the dashboard's, for values kept under keys of their own, such as one per store.
     *
     * @param node the node
     * @return its preferences node
     */
    public Preferences node(Node node) {
        return node == Node.DASHBOARD ? root : node(node.parent).node(node.stored());
    }

    /**
     * A value.
     *
     * @param key the key
     * @return the value, or empty when none is kept
     */
    public Optional<String> get(Key key) {
        return Optional.ofNullable(node(key.node()).get(key.stored(), null));
    }

    /**
     * Keeps a value.
     *
     * @param key   the key
     * @param value the value
     */
    public void put(Key key, String value) {
        node(key.node()).put(key.stored(), value);
    }

    /**
     * Removes a value.
     *
     * @param key the key
     */
    public void remove(Key key) {
        node(key.node()).remove(key.stored());
    }

    /**
     * A true-or-false value.
     *
     * @param key the key
     * @return the value, or empty when none is kept
     */
    public Optional<Boolean> getBoolean(Key key) {
        return get(key).map(Boolean::parseBoolean);
    }

    /**
     * Keeps a true-or-false value.
     *
     * @param key   the key
     * @param value the value
     */
    public void putBoolean(Key key, boolean value) {
        node(key.node()).putBoolean(key.stored(), value);
    }
}
