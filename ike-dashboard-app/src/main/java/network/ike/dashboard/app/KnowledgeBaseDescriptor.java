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
import java.util.List;
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
 * @param collection      the collection it belongs to, such as {@code SOLOR}: the knowledge bases one
 *                        pipeline builds and publishes together, which the dashboard keeps at hand when
 *                        it tracks the collection (decision 17); optional at level 1
 * @param inputs          what the store was assembled from, in order: the protobufs and sources it
 *                        includes (decision 18); optional at level 1
 */
public record KnowledgeBaseDescriptor(int level, Coordinate coordinate, Optional<String> resolvedVersion,
                                      String displayName, String layout, int entityFormat, Instant assembledAt,
                                      String access, String icon, Memory memory, Optional<String> collection,
                                      List<Input> inputs) {

    /**
     * Copies the inputs.
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
     * @param memory          what the assembly measured of the store's memory
     * @param collection      the collection it belongs to
     * @param inputs          what the store was assembled from, in order
     */
    public KnowledgeBaseDescriptor {
        inputs = List.copyOf(inputs);
    }

    /**
     * A descriptor that records no inputs.
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
     * @param memory          what the assembly measured of the store's memory
     * @param collection      the collection it belongs to
     */
    public KnowledgeBaseDescriptor(int level, Coordinate coordinate, Optional<String> resolvedVersion,
                                   String displayName, String layout, int entityFormat, Instant assembledAt,
                                   String access, String icon, Memory memory, Optional<String> collection) {
        this(level, coordinate, resolvedVersion, displayName, layout, entityFormat, assembledAt, access, icon,
                memory, collection, List.of());
    }

    /**
     * A descriptor in no collection.
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
     * @param memory          what the assembly measured of the store's memory
     */
    public KnowledgeBaseDescriptor(int level, Coordinate coordinate, Optional<String> resolvedVersion,
                                   String displayName, String layout, int entityFormat, Instant assembledAt,
                                   String access, String icon, Memory memory) {
        this(level, coordinate, resolvedVersion, displayName, layout, entityFormat, assembledAt, access, icon,
                memory, Optional.empty());
    }

    /**
     * A descriptor with no memory measured, in no collection.
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
     * Whether the knowledge base is in one of the collections the dashboard tracks.
     *
     * @param tracked the collections, as the settings name them
     * @return whether its collection is one of them, matched without regard to case
     */
    public boolean inCollection(java.util.Set<String> tracked) {
        return collection.map(name -> tracked.stream().anyMatch(name::equalsIgnoreCase)).orElse(false);
    }

    /**
     * The descriptor as {@code knowledge-base.properties} holds it, to keep beside a download that cannot
     * carry its own, such as a protobuf's zip.
     *
     * @return the entries
     */
    public java.util.Properties toProperties() {
        java.util.Properties entries = new java.util.Properties();
        entries.setProperty("descriptorLevel", Integer.toString(level));
        entries.setProperty("groupId", coordinate.groupId());
        entries.setProperty("artifactId", coordinate.artifactId());
        entries.setProperty("version", coordinate.version());
        resolvedVersion.ifPresent(version -> entries.setProperty("resolvedVersion", version));
        entries.setProperty("displayName", displayName);
        entries.setProperty("layout", layout);
        entries.setProperty("entityFormat", Integer.toString(entityFormat));
        entries.setProperty("assembledAt", assembledAt.toString());
        entries.setProperty("access", access);
        entries.setProperty("icon", icon);
        collection.ifPresent(name -> entries.setProperty("collection", name));
        memory.statedAxiomBytes().ifPresent(bytes -> entries.setProperty("statedAxiomBytes", Long.toString(bytes)));
        memory.loadedHeapBytes().ifPresent(bytes -> entries.setProperty("loadedHeapBytes", Long.toString(bytes)));
        memory.reasonerHeapBytes().ifPresent(bytes -> entries.setProperty("reasonerHeapBytes", Long.toString(bytes)));
        for (int i = 0; i < inputs.size(); i++) {
            Input input = inputs.get(i);
            String key = "input." + (i + 1);
            entries.setProperty(key, input.role() + " " + input.reference());
            input.resolvedVersion().ifPresent(version -> entries.setProperty(key + ".resolvedVersion", version));
            input.importer().ifPresent(importer -> entries.setProperty(key + ".importer", importer));
        }
        return entries;
    }

    /**
     * Whether the store includes a knowledge base: whether one of its inputs is an artifact of it, at
     * any version.
     *
     * @param knowledgeBase the knowledge base
     * @return whether it was assembled from it
     */
    public boolean includes(Coordinate knowledgeBase) {
        return inputs.stream().anyMatch(input -> input.of(knowledgeBase));
    }

    /**
     * One input a store was assembled from, as {@code input.<n>} records it: kb-assemble's role and the
     * Maven coordinate it resolved, or the name of a file that is no repository's artifact.
     *
     * @param role            {@code STORE_SEED}, {@code PB}, {@code CHANGESET} or {@code SOURCE}
     * @param reference       {@code g:a[:extension[:classifier]]:v}, or {@code file:<name>}
     * @param resolvedVersion the version the coordinate resolved to, when it differs: a snapshot's deploy
     * @param importer        a {@code SOURCE} input's importer
     */
    public record Input(String role, String reference, Optional<String> resolvedVersion, Optional<String> importer) {

        /** What marks a reference to a file. */
        static final String FILE = "file:";

        /**
         * The knowledge base the input is an artifact of, at the version written.
         *
         * @return the coordinate, or empty for a file
         */
        public Optional<Coordinate> coordinate() {
            String[] parts = parts();
            return parts.length >= 3 ? Optional.of(new Coordinate(parts[0], parts[1], parts[parts.length - 1]))
                    : Optional.empty();
        }

        /**
         * The input's classifier, such as {@code unreasoned-pb}.
         *
         * @return the classifier, or empty when the coordinate names none or the input is a file
         */
        public Optional<String> classifier() {
            String[] parts = parts();
            return parts.length == 5 ? Optional.of(parts[3]) : Optional.empty();
        }

        /**
         * Whether the input is an artifact of a knowledge base, at any version.
         *
         * @param knowledgeBase the knowledge base
         * @return whether it is
         */
        public boolean of(Coordinate knowledgeBase) {
            return coordinate().filter(coordinate -> coordinate.groupId().equals(knowledgeBase.groupId())
                    && coordinate.artifactId().equals(knowledgeBase.artifactId())).isPresent();
        }

        /**
         * The file the input was, when it is no repository's artifact.
         *
         * @return the file's name
         */
        public Optional<String> file() {
            return reference.startsWith(FILE) ? Optional.of(reference.substring(FILE.length())) : Optional.empty();
        }

        /**
         * The version a person reads: the deploy the coordinate resolved to, else the one written.
         *
         * @return the version, or empty for a file
         */
        public Optional<String> version() {
            return resolvedVersion.or(() -> coordinate().map(Coordinate::version));
        }

        private String[] parts() {
            return reference.startsWith(FILE) ? new String[0] : reference.split(":");
        }
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
