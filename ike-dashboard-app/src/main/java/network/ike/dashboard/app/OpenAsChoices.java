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
import java.util.prefs.Preferences;

/**
 * Who each folder of a knowledge base is opened as (KEC, 2026-11-22: the choice is kept for each folder):
 * with the assistant the file of {@link Authors} names, which every folder starts with, or with no
 * assistant. Only a folder opened with no assistant is written down, in the dashboard's preferences
 * ({@code network/ike/dashboard/open-as}), keyed by store the way each store's memory is
 * ({@link HeapSizing#storeKey}). A request from a link reads the same choice, so it opens as the folder's
 * Open button would.
 */
public final class OpenAsChoices {

    /** No choices, kept nowhere: what a view starts with until it is given the person's. */
    static final OpenAsChoices NONE = new OpenAsChoices(null);

    /** What is stored for a folder opened with no assistant. */
    static final String NO_ASSISTANT = "no-assistant";

    private final Preferences preferences;

    /**
     * Keeps choices in a preferences node.
     *
     * @param preferences the node
     */
    public OpenAsChoices(Preferences preferences) {
        this.preferences = preferences;
    }

    /**
     * The choices of the person using the dashboard.
     *
     * @return them
     */
    public static OpenAsChoices current() {
        return new OpenAsChoices(DashboardPreferences.current().node(DashboardPreferences.Node.OPEN_AS));
    }

    /**
     * The choice kept for a folder.
     *
     * @param folder the folder of a knowledge base
     * @return no assistant when that was chosen for it, else the assistant the file names
     */
    public OpenAs.Assistant of(Path folder) {
        return preferences != null && NO_ASSISTANT.equals(preferences.get(HeapSizing.storeKey(folder), null))
                ? OpenAs.Assistant.NONE : OpenAs.Assistant.NAMED;
    }

    /**
     * Keeps a choice for a folder.
     *
     * @param folder    the folder of a knowledge base
     * @param assistant the choice
     */
    public void choose(Path folder, OpenAs.Assistant assistant) {
        if (preferences == null) {
            return;
        }
        if (assistant == OpenAs.Assistant.NONE) {
            preferences.put(HeapSizing.storeKey(folder), NO_ASSISTANT);
        } else {
            preferences.remove(HeapSizing.storeKey(folder));
        }
    }

    /**
     * Who a Komet is opened as on a folder: what the file of authors gives, with the folder's choice.
     *
     * @param file   what the file of authors gives
     * @param folder the folder of a knowledge base
     * @return who Komet is opened as
     */
    public OpenAs openAs(Authors.Reading file, Path folder) {
        return OpenAs.of(file, of(folder));
    }
}
