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

import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.BiConsumer;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

/**
 * Which Komet each knowledge base opens in (KEC, 2026-11-08): the latest Komet that can open it, unless the
 * person pins it to one installed version, for testing or otherwise. A pin is kept per knowledge base, by its
 * family name, in the dashboard's preferences ({@code network/ike/dashboard/kometPins}).
 */
public final class KometPins {

    /** The preferences node the pins are kept in, under the dashboard's. */
    public static final String NODE = "kometPins";

    private final Map<String, String> pins;
    private final BiConsumer<String, Optional<String>> store;

    private KometPins(Map<String, String> pins, BiConsumer<String, Optional<String>> store) {
        this.pins = new TreeMap<>(pins);
        this.store = store;
    }

    /**
     * Pins kept only in memory, for a view with nowhere to keep them.
     *
     * @return no pins
     */
    public static KometPins inMemory() {
        return new KometPins(Map.of(), (family, version) -> { });
    }

    /**
     * The pins kept in a preferences node, written back to it as they change.
     *
     * @param node the node
     * @return the pins
     */
    public static KometPins from(Preferences node) {
        Map<String, String> read = new TreeMap<>();
        try {
            for (String key : node.keys()) {
                String version = node.get(key, "");
                if (!version.isBlank()) {
                    read.put(key, version);
                }
            }
        } catch (BackingStoreException e) {
            // Unreadable preferences mean no pins: every knowledge base opens in the latest Komet.
        }
        return new KometPins(read, (family, version) -> {
            version.ifPresentOrElse(pinned -> node.put(family, pinned), () -> node.remove(family));
            try {
                node.flush();
            } catch (BackingStoreException e) {
                // Kept for this run; the next start reads what was flushed.
            }
        });
    }

    /**
     * The Komet version a knowledge base is pinned to.
     *
     * @param family the knowledge base's family name
     * @return the version, or empty for the latest
     */
    public Optional<String> of(String family) {
        return Optional.ofNullable(pins.get(family));
    }

    /**
     * Pins a knowledge base to a Komet version, or back to the latest.
     *
     * @param family  the knowledge base's family name
     * @param version the version, or empty for the latest
     */
    public void pin(String family, Optional<String> version) {
        version.ifPresentOrElse(pinned -> pins.put(family, pinned), () -> pins.remove(family));
        store.accept(family, version);
    }

    /**
     * Every pin, by family name.
     *
     * @return the pins
     */
    public Map<String, String> all() {
        return Map.copyOf(pins);
    }
}
