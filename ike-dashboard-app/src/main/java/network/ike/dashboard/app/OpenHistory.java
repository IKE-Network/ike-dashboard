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
import java.util.Optional;
import java.util.prefs.Preferences;

/**
 * When each knowledge base was last opened from the dashboard, kept in preferences under its name, so
 * the list can put the ones in use first.
 */
public final class OpenHistory {

    private static final int MAX_KEY = Preferences.MAX_KEY_LENGTH;

    private final Preferences preferences;

    /**
     * Keeps the history in a preferences node.
     *
     * @param preferences the node
     */
    public OpenHistory(Preferences preferences) {
        this.preferences = preferences;
    }

    /**
     * The history the dashboard keeps.
     *
     * @return the history in the dashboard's preferences
     */
    public static OpenHistory current() {
        return new OpenHistory(Preferences.userRoot().node(DashboardSettings.NODE + "/opened"));
    }

    /**
     * Records that a knowledge base was opened now.
     *
     * @param name the knowledge base's name
     */
    public void opened(String name) {
        preferences.putLong(key(name), Instant.now().toEpochMilli());
    }

    /**
     * When a knowledge base was last opened.
     *
     * @param name the knowledge base's name
     * @return the time, if it has been opened from the dashboard
     */
    public Optional<Instant> lastOpened(String name) {
        long millis = preferences.getLong(key(name), 0);
        return millis == 0 ? Optional.empty() : Optional.of(Instant.ofEpochMilli(millis));
    }

    private static String key(String name) {
        return name.length() <= MAX_KEY ? name : name.substring(0, MAX_KEY);
    }
}
