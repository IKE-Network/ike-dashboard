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

/**
 * What a store says it is in {@code META-INF/ike/knowledge-base.properties}, the file the dashboard
 * manages stores by (the IKE Dashboard design, {@code dev-ike-dashboard} in ike-lab-documents).
 * {@link KnowledgeBaseScanner} reads it and holds it to a supported level; at level 1 every field is
 * required but {@code resolvedVersion}, and the icon must be in the store.
 *
 * @param level           the descriptor level
 * @param coordinate      the Maven coordinate the store is a build of
 * @param resolvedVersion the deploy it is, when known: a release, or a snapshot a download unpacked
 * @param displayName     the name a person reads
 * @param layout          {@code rocks}, {@code spined-32} or {@code spined-64}
 * @param entityFormat    the entity format the store was written in
 * @param assembledAt     when it was assembled
 * @param access          {@code public} or {@code restricted}
 * @param icon            the icon's path relative to the store; on Nexus, the {@code png:icon} beside it
 * @param memory          what the assembly measured of the store's memory, optional at level 1
 */
public record KnowledgeBaseDescriptor(int level, Coordinate coordinate, Optional<String> resolvedVersion,
                                      String displayName, String layout, int entityFormat, Instant assembledAt,
                                      String access, String icon, Memory memory) {

    /**
     * A descriptor with no memory measured.
     *
     * @param level           the descriptor level
     * @param coordinate      the Maven coordinate the store is a build of
     * @param resolvedVersion the deploy it is, when known
     * @param displayName     the name a person reads
     * @param layout          {@code rocks}, {@code spined-32} or {@code spined-64}
     * @param entityFormat    the entity format the store was written in
     * @param assembledAt     when it was assembled
     * @param access          {@code public} or {@code restricted}
     * @param icon            the icon's path relative to the store
     */
    public KnowledgeBaseDescriptor(int level, Coordinate coordinate, Optional<String> resolvedVersion,
                                   String displayName, String layout, int entityFormat, Instant assembledAt,
                                   String access, String icon) {
        this(level, coordinate, resolvedVersion, displayName, layout, entityFormat, assembledAt, access, icon,
                Memory.NONE);
    }

    /**
     * What the assembly measured, under Flight Recorder, while its load step ran the reasoner
     * ({@code ike-knowledge-provider}'s {@code HeapRecorder}; kb-assemble carries it here), so the heap
     * of a store is known before it is downloaded. Each is in bytes, and absent when not measured.
     *
     * @param statedAxiomBytes  the size of the store's stated axioms
     * @param loadedHeapBytes   the live heap with the store loaded
     * @param reasonerHeapBytes what running the reasoner added to it
     */
    public record Memory(Optional<Long> statedAxiomBytes, Optional<Long> loadedHeapBytes,
                         Optional<Long> reasonerHeapBytes) {

        /** Nothing measured. */
        public static final Memory NONE = new Memory(Optional.empty(), Optional.empty(), Optional.empty());
    }
}
