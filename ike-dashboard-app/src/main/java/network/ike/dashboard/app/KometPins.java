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
 * Which Komet each knowledge base opens in (KEC, 2026-11-08): the latest Komet that can open it, unless the
 * person pins it to one installed version, for testing or otherwise. A pin is kept per knowledge base, by its
 * family name, in the dashboard's preferences ({@code network/ike/dashboard/komet-pins}), the way the notice
 * dismissals and the open history are kept.
 */
public final class KometPins {

    /** The node under the dashboard's settings. */
    static final String NODE = "komet-pins";

    /** No pins, kept nowhere: what a view starts with until it is given the person's. */
    static final KometPins NONE = new KometPins(null);

    private final Preferences preferences;

    /**
     * Keeps pins in a preferences node.
     *
     * @param preferences the node
     */
    public KometPins(Preferences preferences) {
        this.preferences = preferences;
    }

    /**
     * The pins of the person using the dashboard.
     *
     * @return them
     */
    public static KometPins current() {
        return new KometPins(Preferences.userRoot().node(DashboardSettings.NODE).node(NODE));
    }

    /**
     * The Komet version a knowledge base is pinned to.
     *
     * @param family the knowledge base's family name
     * @return the version, or empty for the latest
     */
    public Optional<String> of(String family) {
        return preferences == null ? Optional.empty()
                : Optional.ofNullable(preferences.get(NoticeDismissals.key(family), null)).filter(version -> !version.isBlank());
    }

    /**
     * Pins a knowledge base to a Komet version, or back to the latest.
     *
     * @param family  the knowledge base's family name
     * @param version the version, or empty for the latest
     */
    public void pin(String family, Optional<String> version) {
        if (preferences == null) {
            return;
        }
        version.ifPresentOrElse(pinned -> preferences.put(NoticeDismissals.key(family), pinned),
                () -> preferences.remove(NoticeDismissals.key(family)));
    }
}
