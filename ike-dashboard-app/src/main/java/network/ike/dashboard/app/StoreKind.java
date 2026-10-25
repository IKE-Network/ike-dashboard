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
 * What an entry in {@code ~/Solor} is, told from its files alone: the dashboard opens no store.
 * The folder tests are the providers' own ({@code isValidDataLocation}), as the
 * {@code dev-ike-dashboard} topic tabulates them.
 */
public enum StoreKind {

    /** A 64-bit spined-array store: {@code store-format.properties} says {@code layout=64-bit}. */
    SPINED_64("64-bit spined array", "reasoned-sa"),

    /** A legacy 32-bit spined-array store: {@code nidToByteArrayMap/} and {@code nidToCitingComponentNidMap/}. */
    SPINED_32("32-bit spined array", "reasoned-sa"),

    /** A Rocks store: {@code rocks/}. */
    ROCKS("Rocks", "rkb"),

    /** A zip of change sets, which Komet loads into a store rather than opens. */
    CHANGE_SETS("change sets (zip)", "reasoned-pb"),

    /** Anything else: a folder no provider would list. */
    UNKNOWN("unknown", "");

    private final String label;
    private final String classifier;

    StoreKind(String label, String classifier) {
        this.label = label;
        this.classifier = classifier;
    }

    /**
     * How the dashboard names the kind.
     *
     * @return the label
     */
    public String label() {
        return label;
    }

    /**
     * The classifier a knowledge-base build publishes this kind of store under, or empty when it
     * publishes none.
     *
     * @return the classifier
     */
    public String classifier() {
        return classifier;
    }

    /**
     * The kind a knowledge-base descriptor's layout names.
     *
     * @param layout {@code rocks}, {@code spined-64} or {@code spined-32}
     * @return the kind, {@link #UNKNOWN} for any other
     */
    public static StoreKind ofLayout(String layout) {
        return switch (layout) {
            case "rocks" -> ROCKS;
            case "spined-64" -> SPINED_64;
            case "spined-32" -> SPINED_32;
            default -> UNKNOWN;
        };
    }

    /**
     * Whether Komet opens this kind of entry as a store.
     *
     * @return {@code true} for the three store layouts
     */
    public boolean opensInKomet() {
        return this == SPINED_64 || this == SPINED_32 || this == ROCKS;
    }
}
