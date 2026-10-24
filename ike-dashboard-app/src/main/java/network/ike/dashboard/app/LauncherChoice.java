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

import java.util.List;
import java.util.Optional;

/**
 * Which installed Komet opens a store: the newest one that can read it.
 *
 * <p>Until Komet's installation manifest says what each version reads (decision 7 of the
 * {@code dev-ike-dashboard} topic), one rule stands in, inferred from when the formats arrived: a 64-bit
 * spined-array store, or any store of entity format 3, needs a Komet from 2026-10-21 or later (version
 * {@code 26.1021} or higher), when the 64-bit provider arrived and began writing format 3. Every other
 * store opens in the newest Komet.
 */
public final class LauncherChoice {

    /** The first Komet version that reads 64-bit spined-array stores and entity format 3. */
    static final String FORMAT_3_SINCE = "26.1021";

    private LauncherChoice() {
    }

    /**
     * What a store needs of a Komet, for the person: empty when any Komet will do.
     *
     * @param knowledgeBase the store
     * @return the requirement, such as {@code Komet 26.1021 or later}
     */
    public static Optional<String> requirement(KnowledgeBase knowledgeBase) {
        return minimumVersion(knowledgeBase).map(version -> "Komet " + version + " or later");
    }

    /**
     * The Komet a store opens with.
     *
     * @param komets        the Komets installed, newest first
     * @param knowledgeBase the store
     * @return the newest Komet that reads it, or empty when none does
     */
    public static Optional<KometInstallation> choose(List<KometInstallation> komets, KnowledgeBase knowledgeBase) {
        if (!knowledgeBase.kind().opensInKomet()) {
            return Optional.empty();
        }
        return komets.stream().filter(komet -> reads(komet, knowledgeBase)).findFirst();
    }

    /**
     * Whether a Komet reads a store.
     *
     * @param komet         the Komet
     * @param knowledgeBase the store
     * @return {@code true} when the Komet is new enough
     */
    public static boolean reads(KometInstallation komet, KnowledgeBase knowledgeBase) {
        return minimumVersion(knowledgeBase)
                .map(minimum -> KometInstallations.compareVersions(komet.version(), minimum) >= 0)
                .orElse(true);
    }

    private static Optional<String> minimumVersion(KnowledgeBase knowledgeBase) {
        boolean format3 = knowledgeBase.kind() == StoreKind.SPINED_64
                || knowledgeBase.entityFormat().filter(format -> format >= 3).isPresent();
        return format3 ? Optional.of(FORMAT_3_SINCE) : Optional.empty();
    }
}
