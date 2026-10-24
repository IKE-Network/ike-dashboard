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
package network.ike.dashboard.maven;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The files a build resolved from each local repository, held in memory until the build ends.
 * Builds resolve from many threads at once, so it is safe to call from any of them.
 */
final class UsageCollector {

    private final Map<Path, Set<Path>> used = new ConcurrentHashMap<>();

    /**
     * Notes that a build resolved a file. A file outside the local repository, such as a reactor
     * module's {@code target/classes}, is noted too and left out when the journal is written.
     *
     * @param localRepository the local repository the build resolves into
     * @param file            the resolved file
     */
    void resolved(Path localRepository, Path file) {
        used.computeIfAbsent(localRepository.toAbsolutePath().normalize(), repository -> ConcurrentHashMap.newKeySet())
                .add(file.toAbsolutePath().normalize());
    }

    /**
     * Takes what has been noted, leaving nothing behind, so a long-lived Maven daemon writes each
     * build's files once.
     *
     * @return the files noted, by local repository
     */
    Map<Path, Set<Path>> drain() {
        Map<Path, Set<Path>> drained = new LinkedHashMap<>();
        for (Path repository : Set.copyOf(used.keySet())) {
            Set<Path> files = used.remove(repository);
            if (files != null && !files.isEmpty()) {
                drained.put(repository, Set.copyOf(files));
            }
        }
        return drained;
    }
}
