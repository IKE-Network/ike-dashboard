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

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Moving installed Komet versions to the Trash (KEC, 2026-11-08): which are running, which knowledge bases only a
 * given Komet can open, and what would be left with no Komet to open it. Knowledge bases are never touched; a
 * Komet in the Trash can be put back.
 */
public final class KometRemoval {

    private KometRemoval() {
    }

    /**
     * Whether a Komet is running: a process started from inside its application folder.
     *
     * @param komet the Komet
     * @return {@code true} when one is
     */
    public static boolean running(KometInstallation komet) {
        Path bundle = komet.bundle().toAbsolutePath().normalize();
        return ProcessHandle.allProcesses().anyMatch(process -> {
            ProcessHandle.Info info = process.info();
            return Stream.concat(info.command().stream(), info.arguments().stream().flatMap(Stream::of))
                    .anyMatch(text -> text.startsWith(bundle.toString()));
        });
    }

    /**
     * The knowledge bases no Komet could open once these are gone, though one installed now can.
     *
     * @param komets   every Komet installed
     * @param removing the Komets to move to the Trash
     * @param stores   the knowledge bases
     * @return the knowledge bases left with no Komet that opens them
     */
    public static List<KnowledgeBase> stranded(List<KometInstallation> komets, List<KometInstallation> removing,
                                               List<KnowledgeBase> stores) {
        List<KometInstallation> left = komets.stream().filter(komet -> !removing.contains(komet)).toList();
        return stores.stream().filter(store -> store.kind().opensInKomet())
                .filter(store -> komets.stream().anyMatch(komet -> LauncherChoice.reads(komet, store)))
                .filter(store -> left.stream().noneMatch(komet -> LauncherChoice.reads(komet, store)))
                .toList();
    }
}
