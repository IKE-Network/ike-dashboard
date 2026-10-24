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

import java.time.Instant;
import java.util.prefs.Preferences;

/**
 * The notices the person dismissed, by {@link Notice#id()}, beside the dashboard's other settings in
 * {@code java.util.prefs}. They never leave the machine. A notice's id names the fact it reports, so
 * a dismissed "newer build" comes back when a newer build still is deployed.
 */
public final class NoticeDismissals {

    /** The node under the dashboard's settings. */
    static final String NODE = "dismissed-notices";

    private final Preferences preferences;

    /**
     * Keeps dismissals in a preferences node.
     *
     * @param preferences the node
     */
    public NoticeDismissals(Preferences preferences) {
        this.preferences = preferences;
    }

    /**
     * The dismissals of the person using the dashboard.
     *
     * @return them
     */
    public static NoticeDismissals current() {
        return new NoticeDismissals(Preferences.userRoot().node(DashboardSettings.NODE).node(NODE));
    }

    /**
     * Dismisses a notice.
     *
     * @param id the notice's id
     */
    public void dismiss(String id) {
        preferences.put(key(id), Instant.now().toString());
    }

    /**
     * Whether a notice was dismissed.
     *
     * @param id the notice's id
     * @return {@code true} when it was
     */
    public boolean dismissed(String id) {
        return preferences.get(key(id), null) != null;
    }

    /** A preferences key holds at most {@link Preferences#MAX_KEY_LENGTH} characters; an id may be longer. */
    static String key(String id) {
        return id.length() <= Preferences.MAX_KEY_LENGTH ? id
                : id.substring(0, Preferences.MAX_KEY_LENGTH - 9) + "#" + Integer.toHexString(id.hashCode());
    }
}
