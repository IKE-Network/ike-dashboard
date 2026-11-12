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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A knowledge base to create (IKE-Network/ike-issues#1370): a Maven 4 project built from protobuf layers
 * already on Nexus, kept in a local git repository. It is written in the words of the goals that create
 * it, {@code ike:kb-create-draft} and {@code ike:kb-create-publish}: its name, its folder, and the goals'
 * own properties, so the window's form and the headless {@code kb-new} say the same thing and nothing is
 * translated between them. The goals are the judge of a project; {@link #problems()} only catches early
 * what they would refuse, and {@link #estimate} only previews the sizes they work out from the pinned
 * layers.
 *
 * @param name       the artifactId, which also names the folder
 * @param folder     the project's folder, the goals' {@code targetDirectory}
 * @param properties the goals' other properties, by name, in the order given; see {@link #PROPERTIES}
 */
public record KnowledgeBaseProject(String name, Path folder, Map<String, String> properties) {

    /** The property that names the layers, comma-separated, in load order. */
    public static final String LAYERS = "layers";

    /** The property that names the kind, {@code assembly} or {@code source-layer}. */
    public static final String KIND = "kind";

    /** The property that decides the Rocks store, {@code true} or {@code false}; by size when absent. */
    public static final String ROCKS = "rocks";

    /** The group knowledge bases deploy under, the goals' default. */
    public static final String DEFAULT_GROUP = NexusCatalog.GROUP;

    /** The version a new knowledge base starts at, the goals' default. */
    public static final String DEFAULT_VERSION = "1-SNAPSHOT";

    /** The server a new knowledge base deploys to, the goals' default (decision 5). */
    public static final String DEFAULT_DISTRIBUTION_ID = "ike-restricted";

    /** The releases repository a new knowledge base deploys to, the goals' default. */
    public static final String DEFAULT_RELEASES_URL = "https://nexus.tinkar.org/repository/ike-restricted-releases/";

    /** The snapshots repository a new knowledge base deploys to, the goals' default. */
    public static final String DEFAULT_SNAPSHOTS_URL = "https://nexus.tinkar.org/repository/ike-restricted-snapshots/";

    /** What the folder of the copy Komet edits is named after its knowledge base, as the generated POM has it. */
    public static final String AUTHORING_SUFFIX = "-authoring";

    /** Every property the goals take beside {@code name} and {@code targetDirectory}. */
    public static final List<String> PROPERTIES = List.of(KIND, "groupId", "version", "displayName", "description",
            LAYERS, "starterSetVersion", "importer", "release", "importerOptions", "collection", "distributionId",
            "releasesUrl", "snapshotsUrl", "siteUrl", ROCKS, "heap", "editModule", "parentVersion", "providerVersion",
            "tinkarCoreVersion", "rocksKbVersion", "rocksdbjniVersion", "rocksdbjniClassifier", "layerRepository",
            "layerRepositoryId", "skipVerify");

    private static final Pattern ARTIFACT_ID = Pattern.compile("[a-z0-9]+(?:[.-][a-z0-9]+)*");
    private static final Pattern GROUP_ID = Pattern.compile("[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+)*");

    /**
     * Copies the properties, keeping their order and leaving out the blank ones.
     *
     * @param name       the artifactId
     * @param folder     the project's folder
     * @param properties the goals' other properties
     * @throws IllegalArgumentException if a property is not one the goals take
     */
    public KnowledgeBaseProject {
        Map<String, String> kept = new LinkedHashMap<>();
        properties.forEach((property, value) -> {
            if (!PROPERTIES.contains(property)) {
                throw new IllegalArgumentException("Unknown property " + property + "; use one of "
                        + String.join(", ", PROPERTIES) + ", or folder");
            }
            if (value != null && !value.isBlank()) {
                kept.put(property, value.strip());
            }
        });
        properties = java.util.Collections.unmodifiableMap(kept);
        name = name == null ? "" : name.strip();
    }

    /** What a knowledge-base project is, as {@code kind} names it (decision 8). */
    public enum Kind {
        /** Combines layers, classifies, and publishes a reasoned protobuf. */
        ASSEMBLY("assembly", "Assembly"),
        /** Turns a release into an unreasoned protobuf, loaded only under an assembly. */
        SOURCE_LAYER("source-layer", "Source layer");

        private final String value;
        private final String label;

        Kind(String value, String label) {
            this.value = value;
            this.label = label;
        }

        /**
         * The kind as the goals' {@code kind} property writes it.
         *
         * @return {@code assembly} or {@code source-layer}
         */
        public String value() {
            return value;
        }

        /**
         * The kind as a person reads it.
         *
         * @return such as "Assembly"
         */
        public String label() {
            return label;
        }
    }

    /**
     * One protobuf layer, as the goals' {@code layers} property names it.
     *
     * @param groupId    the layer's group
     * @param artifactId the layer's artifact
     * @param classifier {@code unreasoned-pb} or {@code reasoned-pb}
     * @param version    the version, which the goals pin to its exact deploy (decision 9)
     */
    public record Layer(String groupId, String artifactId, String classifier, String version) {

        /** The group of the IKE starter set, which every knowledge base loads first. */
        public static final String STARTER_SET_GROUP = "network.ike.foundation";

        /** The artifact of the IKE starter set. */
        public static final String STARTER_SET_ARTIFACT = "ike-starter-set";

        /**
         * Reads a layer.
         *
         * @param text {@code groupId:artifactId:zip:classifier:version}
         * @return the layer
         * @throws IllegalArgumentException if the text is not a layer's coordinate
         */
        public static Layer parse(String text) {
            String[] parts = text.strip().split(":");
            if (parts.length != 5 || !"zip".equals(parts[2]) || java.util.Arrays.stream(parts).anyMatch(String::isBlank)) {
                throw new IllegalArgumentException("Expected groupId:artifactId:zip:classifier:version, got " + text);
            }
            return new Layer(parts[0], parts[1], parts[3], parts[4]);
        }

        /**
         * The layer a protobuf on Nexus is.
         *
         * @param offer the protobuf
         * @return the layer, at the offer's version
         */
        public static Layer of(NexusCatalog.Offer offer) {
            return new Layer(offer.coordinate().groupId(), offer.coordinate().artifactId(), offer.classifier(),
                    offer.coordinate().version());
        }

        /**
         * Whether the layer is the IKE starter set.
         *
         * @return whether it is
         */
        public boolean starterSet() {
            return STARTER_SET_GROUP.equals(groupId) && STARTER_SET_ARTIFACT.equals(artifactId);
        }

        /**
         * Whether the layer is a reasoned protobuf, which stands alone and already carries the starter set.
         *
         * @return whether it is
         */
        public boolean reasoned() {
            return "reasoned-pb".equals(classifier);
        }

        /**
         * Whether two layers are the same artifact, at any version.
         *
         * @param other the other layer
         * @return whether they are
         */
        public boolean sameArtifact(Layer other) {
            return groupId.equals(other.groupId) && artifactId.equals(other.artifactId);
        }

        @Override
        public String toString() {
            return groupId + ":" + artifactId + ":zip:" + classifier + ":" + version;
        }
    }

    /**
     * The sizes of a project, estimated before its layers are pinned, by the goals' own rule (decision 6;
     * the rule is {@code KbCreatePlan}'s in ike-tooling, and its draft is the figure that counts).
     *
     * @param layerBytes   the total of the layers whose size is known
     * @param unknownSizes how many layers' sizes are not known
     * @param loadedBytes  the heap a spined store of the layers is estimated to hold
     * @param heapGib      the heap the build is given, in whole GiB
     * @param rocks        whether a Rocks store is built
     * @param rocksReason  why, as a clause
     */
    public record Estimate(long layerBytes, int unknownSizes, long loadedBytes, long heapGib, boolean rocks,
                           String rocksReason) {

        /** Bytes in a GiB. */
        static final long GIB = 1L << 30;

        /** At this many GiB of layers or more, an assembly builds a Rocks store. */
        static final long ROCKS_THRESHOLD_GIB = 2;

        /** Loaded heap per byte of input protobuf: 3.3 to 3.9 measured, rounded up. */
        static final double LOADED_HEAP_PER_INPUT_BYTE = 4.0;

        /** Heap per byte of live heap: the collector's room above the live set. */
        static final double COLLECTOR_ROOM = 1.35;

        /** What the reasoner adds to an assembly's live heap, per byte loaded. */
        static final double REASONER_PER_LOADED_BYTE = 3.2;

        /** What an import adds to a source layer's live heap, per byte loaded. */
        static final double IMPORT_PER_LOADED_BYTE = 1.0;

        /** The least heap a generated project is given, in GiB. */
        static final long MINIMUM_HEAP_GIB = 8;

        /**
         * A size as the goals write it: GiB to two places, else MiB to one.
         *
         * @param bytes the size
         * @return such as {@code 2.74 GiB} or {@code 0.9 MiB}
         */
        public static String size(long bytes) {
            return bytes >= GIB ? String.format(Locale.ROOT, "%.2f GiB", bytes / (double) GIB)
                    : String.format(Locale.ROOT, "%.1f MiB", bytes / (double) (1L << 20));
        }
    }

    /**
     * Estimates a project's sizes from its layers' sizes.
     *
     * @param kind  the kind
     * @param sizes each layer's size in bytes, negative when not known
     * @param rocks the person's choice of a Rocks store, or empty to decide by size
     * @return the estimate
     */
    public static Estimate estimate(Kind kind, List<Long> sizes, Optional<Boolean> rocks) {
        long bytes = sizes.stream().mapToLong(Long::longValue).filter(size -> size > 0).sum();
        int unknown = (int) sizes.stream().filter(size -> size < 0).count();
        long loaded = (long) Math.ceil(Estimate.LOADED_HEAP_PER_INPUT_BYTE * bytes);
        double added = kind == Kind.ASSEMBLY ? Estimate.REASONER_PER_LOADED_BYTE : Estimate.IMPORT_PER_LOADED_BYTE;
        long heap = Math.max(Estimate.MINIMUM_HEAP_GIB,
                (long) Math.ceil(Estimate.COLLECTOR_ROOM * (1 + added) * loaded / Estimate.GIB));
        boolean bySize = bytes >= Estimate.ROCKS_THRESHOLD_GIB * Estimate.GIB;
        boolean built = kind == Kind.ASSEMBLY && rocks.orElse(bySize);
        String reason;
        if (kind == Kind.SOURCE_LAYER) {
            reason = "a source layer is loaded only under an assembly";
        } else if (rocks.isPresent()) {
            reason = rocks.get() ? "always, as chosen" : "never, as chosen";
        } else {
            reason = "the layers total " + Estimate.size(bytes) + (unknown == 0 ? "" : " (" + unknown + " not known)")
                    + (bySize ? ", at or above " : ", below ") + Estimate.ROCKS_THRESHOLD_GIB + " GiB";
        }
        return new Estimate(bytes, unknown, loaded, heap, built, reason);
    }

    /**
     * The names already in use, which a new knowledge base must not repeat. The project is kept apart
     * from the knowledge base it builds, but the two share a name: a version downloaded from Nexus goes
     * into the knowledge-base folder under that name, the copy Komet edits goes beside it with
     * {@value #AUTHORING_SUFFIX}, and a deploy goes to Nexus under it.
     *
     * @param projects the names of the projects in the projects folder, and of their folders, in lower case
     * @param here     the names of the knowledge bases in the knowledge-base folder, in lower case
     * @param folders  the names of everything in the knowledge-base folder, in lower case
     * @param nexus    each knowledge base on Nexus, as {@code groupId:artifactId}
     */
    public record Names(Set<String> projects, Set<String> here, Set<String> folders, Set<String> nexus) {

        /** No names in use. */
        public static final Names NONE = new Names(Set.of(), Set.of(), Set.of(), Set.of());

        /**
         * Copies the sets.
         *
         * @param projects the names of the projects and of their folders
         * @param here     the names of the knowledge bases in the knowledge-base folder
         * @param folders  the names of everything in the knowledge-base folder
         * @param nexus    each knowledge base on Nexus
         */
        public Names {
            projects = Set.copyOf(projects);
            here = Set.copyOf(here);
            folders = Set.copyOf(folders);
            nexus = Set.copyOf(nexus);
        }

        /**
         * The names in use on this machine, read from its two folders.
         *
         * @param projectsFolder the projects folder
         * @param solor          the knowledge-base folder
         * @param nexus          each knowledge base on Nexus, as {@code groupId:artifactId}
         * @return the names
         * @throws java.io.IOException if a folder cannot be read
         */
        public static Names read(Path projectsFolder, Path solor, Set<String> nexus) throws java.io.IOException {
            Set<String> projects = new HashSet<>();
            if (java.nio.file.Files.isDirectory(projectsFolder)) {
                try (java.util.stream.Stream<Path> entries = java.nio.file.Files.list(projectsFolder)) {
                    entries.filter(java.nio.file.Files::isDirectory)
                            .forEach(entry -> projects.add(entry.getFileName().toString().toLowerCase(Locale.ROOT)));
                }
            }
            for (KnowledgeBaseProjectFolder project : KnowledgeBaseProjectFolder.scan(projectsFolder)) {
                projects.add(project.artifactId().toLowerCase(Locale.ROOT));
            }
            Set<String> folders = new HashSet<>();
            if (java.nio.file.Files.isDirectory(solor)) {
                try (java.util.stream.Stream<Path> entries = java.nio.file.Files.list(solor)) {
                    entries.forEach(entry -> folders.add(entry.getFileName().toString().toLowerCase(Locale.ROOT)));
                }
            }
            Set<String> here = new HashSet<>();
            for (KnowledgeBase knowledgeBase : KnowledgeBaseScanner.scan(solor)) {
                here.add(KnowledgeBaseFamily.familyName(knowledgeBase).toLowerCase(Locale.ROOT));
            }
            return new Names(projects, here, folders, nexus);
        }

        /**
         * The same names, with those on Nexus.
         *
         * @param onNexus each knowledge base on Nexus, as {@code groupId:artifactId}
         * @return the names
         */
        public Names withNexus(Set<String> onNexus) {
            return new Names(projects, here, folders, onNexus);
        }

        /**
         * Why a name cannot be used for a new knowledge base in a group.
         *
         * @param name    the name
         * @param groupId the group
         * @return the reason, a sentence, or empty when the name is free
         */
        public Optional<String> taken(String name, String groupId) {
            String lower = name.toLowerCase(Locale.ROOT);
            if (projects.contains(lower)) {
                return Optional.of("A project named " + name + " is already in your projects folder.");
            }
            if (here.contains(lower)) {
                return Optional.of("A knowledge base named " + name + " is already in your knowledge-base folder.");
            }
            for (String folder : List.of(lower, lower + AUTHORING_SUFFIX)) {
                if (folders.contains(folder)) {
                    return Optional.of("Your knowledge-base folder already has a folder named " + folder + ".");
                }
            }
            if (nexus.contains(groupId + ":" + name)) {
                return Optional.of("A knowledge base named " + name + " is already on Nexus.");
            }
            return Optional.empty();
        }

        /**
         * A name like the one given that is free: the same name with the first number from 2 that makes
         * it so, before a closing {@code -kb}.
         *
         * @param name    the name that is taken
         * @param groupId the group
         * @return a free name
         */
        public String free(String name, String groupId) {
            String stem = name.endsWith("-kb") && name.length() > 3 ? name.substring(0, name.length() - 3) : name;
            String ending = stem.length() == name.length() ? "" : "-kb";
            for (int number = 2; ; number++) {
                String candidate = stem + "-" + number + ending;
                if (taken(candidate, groupId).isEmpty()) {
                    return candidate;
                }
            }
        }
    }

    /**
     * A project from the words of a command line.
     *
     * @param name     the artifactId
     * @param settings {@code property=value} each, {@code folder=<path>} among them
     * @param projects the projects folder, where the project is made when no {@code folder} is given
     * @return the project
     * @throws IllegalArgumentException if a word is not {@code property=value} or names no property
     */
    public static KnowledgeBaseProject parse(String name, List<String> settings, Path projects) {
        Map<String, String> properties = new LinkedHashMap<>();
        Path folder = projects.resolve(name);
        for (String setting : settings) {
            int equals = setting.indexOf('=');
            if (equals <= 0) {
                throw new IllegalArgumentException("Expected property=value, got " + setting);
            }
            String property = setting.substring(0, equals);
            String value = setting.substring(equals + 1);
            if ("folder".equals(property)) {
                folder = Path.of(value).toAbsolutePath().normalize();
            } else {
                properties.put(property, value);
            }
        }
        return new KnowledgeBaseProject(name, folder, properties);
    }

    /**
     * The kind of project.
     *
     * @return the kind, an assembly when none is named
     * @throws IllegalArgumentException if {@code kind} names neither
     */
    public Kind kind() {
        String value = properties.get(KIND);
        if (value == null) {
            return Kind.ASSEMBLY;
        }
        return java.util.Arrays.stream(Kind.values()).filter(kind -> kind.value().equals(value)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("kind is assembly or source-layer, got " + value));
    }

    /**
     * The layers, in load order, as listed; the goals put the starter set first when it is needed.
     *
     * @return the layers
     * @throws IllegalArgumentException if one is not a layer's coordinate
     */
    public List<Layer> layers() {
        String value = properties.get(LAYERS);
        if (value == null) {
            return List.of();
        }
        return java.util.Arrays.stream(value.split(",")).filter(text -> !text.isBlank()).map(Layer::parse).toList();
    }

    /**
     * Whether the goals put the IKE starter set before the layers listed: always, unless it is listed or
     * a reasoned protobuf, which already carries it, is among them.
     *
     * @param layers the layers listed
     * @return whether the starter set is put first
     */
    public static boolean starterSetFirst(List<Layer> layers) {
        return layers.stream().noneMatch(layer -> layer.starterSet() || layer.reasoned());
    }

    /**
     * What the goals would refuse, caught before they are run; empty when the project can be drafted.
     *
     * @return each problem, a sentence
     */
    public List<String> problems() {
        List<String> problems = new ArrayList<>();
        if (name.isEmpty()) {
            problems.add("Give the knowledge base a name.");
        } else if (!ARTIFACT_ID.matcher(name).matches()) {
            problems.add("The name is lower-case letters and digits joined by hyphens, such as my-kb.");
        }
        String group = properties.getOrDefault("groupId", DEFAULT_GROUP);
        if (!GROUP_ID.matcher(group).matches()) {
            problems.add("The group is not a Maven groupId: " + group + ".");
        }
        Kind kind;
        List<Layer> layers;
        try {
            kind = kind();
            layers = layers();
        } catch (IllegalArgumentException e) {
            problems.add(e.getMessage() + ".");
            return problems;
        }
        if (layers.isEmpty()) {
            problems.add("Add at least one protobuf layer.");
        }
        Set<String> seen = new HashSet<>();
        for (Layer layer : layers) {
            if (!seen.add(layer.groupId() + ":" + layer.artifactId())) {
                problems.add(layer.artifactId() + " is listed twice.");
            }
        }
        for (int index = 1; index < layers.size(); index++) {
            if (layers.get(index).starterSet()) {
                problems.add("The starter set must be first. It is at position " + (index + 1) + ".");
            }
        }
        if (layers.stream().anyMatch(Layer::reasoned) && layers.stream().anyMatch(layer -> !layer.reasoned())) {
            problems.add("Reasoned and unreasoned protobufs cannot be combined yet.");
        }
        boolean importer = properties.containsKey("importer");
        boolean release = properties.containsKey("release");
        if (kind == Kind.SOURCE_LAYER && (!importer || !release)) {
            problems.add("A source layer needs its importer and the release it reads.");
        }
        if (kind == Kind.ASSEMBLY && (importer || release)) {
            problems.add("An assembly imports no release. An importer and a release belong to a source layer.");
        }
        if (kind == Kind.SOURCE_LAYER && "true".equals(properties.get(ROCKS))) {
            problems.add("A source layer builds no Rocks database.");
        }
        return problems;
    }

    /**
     * The words that run one of the goals, after the Maven command.
     *
     * @param goal           {@code kb-create-draft} or {@code kb-create-publish}
     * @param toolingVersion the version of {@code ike-maven-plugin} that contains the goals
     * @return the arguments: plain batch output, the goal by its full coordinates, then each property
     */
    public List<String> arguments(String goal, String toolingVersion) {
        List<String> arguments = new ArrayList<>(KnowledgeBaseCreation.PLAIN);
        arguments.addAll(List.of(KnowledgeBaseCreation.PLUGIN + ":" + toolingVersion + ":" + goal,
                "-Dname=" + name, "-DtargetDirectory=" + folder));
        properties.forEach((property, value) -> arguments.add("-D" + property + "=" + value));
        return arguments;
    }
}
