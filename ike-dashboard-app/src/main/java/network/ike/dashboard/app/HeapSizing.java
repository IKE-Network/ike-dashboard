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

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.prefs.Preferences;

/**
 * How much memory a Komet is launched with: decision 10 of the {@code dev-ike-dashboard} design
 * (IKE-Network/ike-issues#1324). The heap is one percentage of the store's database size per kind
 * (KEC, 2026-10-25): 400 % for a spined store, which holds everything on the heap, and 50 % for Rocks,
 * which keeps its data in native memory and gets a block cache beside the heap, sized apart. No store
 * gets less than a floor. When the store's knowledge-base descriptor carries what its assembly measured
 * under Flight Recorder (the live heap with it loaded, and what the reasoner added), the heap is sized
 * from that instead, with room above the live set for the collector; the rule stands in for a store
 * without a measurement. Room for the reasoner is added apart: a share of the store's stated axioms,
 * which the heap the reasoner needs is proportional to (KEC, 2026-10-25), whichever kind holds them;
 * the store's manifest says their size ({@code Ike-Stated-Axiom-Bytes}), and a store that does not
 * say gets none. The rule budgets what is left on the machine, not the total: physical RAM less a
 * reserve for everything else, less what the Komets already running claim.
 *
 * <p>Every term is a default the person can override, kept in {@code java.util.prefs}, as can the
 * heap and cache of one store. The defaults are provisional until the calibration runs of the design
 * (live heap after open, a search warm-up and a run of the reasoner, on each corpus store) replace them.
 *
 * <p>Sizes are in bytes; a heap is launched in whole gibibytes, {@code KOMET_MAX_HEAP=<n>g}, which a
 * person reads as GB.
 */
public final class HeapSizing {

    /** One gibibyte. */
    public static final long GIB = 1L << 30;

    /** The heap a Komet whose launcher takes no heap starts with, whatever its store needs. */
    public static final long FIXED_HEAP = 42 * GIB;

    /** Rocks' block cache when nothing sets it ({@code Rocks64Store.BLOCK_CACHE_BYTES}). */
    public static final long DEFAULT_CACHE = GIB;

    /**
     * Heap per byte of stated axioms for the reasoner. Measured 2026-10-25 on SNOMED CT's export in a
     * 64-bit spined store: 168 MB of stated axioms, and running the ELK reasoner over its 371,295 concepts
     * raised the live heap after collection by 2.29 GiB, 14.6 bytes per byte; 25 leaves the collector
     * room above the live set.
     */
    public static final double DEFAULT_AXIOM_SHARE = 25.0;

    /**
     * Heap per byte of the live heap an assembly measured: the room a collector needs above the live
     * set, the same margin {@link #DEFAULT_AXIOM_SHARE} keeps over its measurement (25 over 14.6).
     */
    public static final double DEFAULT_MEASURED_ROOM = 1.7;

    /** The smallest cache the dashboard launches a Rocks store with. */
    static final long MINIMUM_CACHE = 256L << 20;

    /** The variable {@code launchKomet} takes the heap from. */
    public static final String MAX_HEAP_VARIABLE = "KOMET_MAX_HEAP";

    /** The variable {@code launchKomet} appends after its own options. */
    public static final String JAVA_OPTS_VARIABLE = "KOMET_JAVA_OPTS";

    /** The system property sizing Rocks' block cache. */
    public static final String CACHE_PROPERTY = "rocks.blockCache.bytes";

    /** The preferences node of the rule, beneath the dashboard's. */
    public static final String NODE = "heap";

    /** The preferences node of the per-store overrides, beneath the rule's. */
    public static final String STORES_NODE = "stores";

    private HeapSizing() {
    }

    /**
     * The terms of the rule.
     *
     * @param spined64Share heap per byte of a 64-bit (or 32-bit) spined store's data
     * @param rocksShare    heap per byte of a Rocks store's data
     * @param cacheShare    Rocks block cache per byte of data
     * @param axiomShare    heap added for the reasoner, per byte of the store's stated axioms
     * @param measuredRoom  heap per byte of live heap the assembly measured: room for the collector
     * @param minimumHeap   the least heap any store is launched with: Komet's own needs
     * @param reserveFloor  the least the machine keeps for everything but Komet
     * @param reserveShare  the share of physical RAM the machine keeps, when more than the floor
     */
    public record Rule(double spined64Share, double rocksShare, double cacheShare, double axiomShare,
                       double measuredRoom, long minimumHeap, long reserveFloor, double reserveShare) {

        /**
         * The shipped defaults (KEC, 2026-10-25): spined 400 % and Rocks 50 % of the database size, at
         * least 8 GB; a Rocks cache of 10 % of it, at least 1 GB. A 2 GB solor-kb spined store gets
         * 8 GB, and a 10 GB Rocks store the same. Room for the reasoner is {@value #DEFAULT_AXIOM_SHARE} ×
         * the stated axioms' size. A measured live heap gets {@value #DEFAULT_MEASURED_ROOM} × itself.
         * The reserve is the larger of 8 GB and 15 % of physical RAM.
         */
        public static final Rule DEFAULTS = new Rule(4.0, 0.5, 0.1, DEFAULT_AXIOM_SHARE, DEFAULT_MEASURED_ROOM,
                8 * GIB, 8 * GIB, 0.15);

        /**
         * The rule as the person has it: each term overridden in the preferences, else the default.
         *
         * @param node the rule's node
         * @return the rule
         */
        public static Rule read(Preferences node) {
            return new Rule(
                    node.getDouble("spined64Share", DEFAULTS.spined64Share),
                    node.getDouble("rocksShare", DEFAULTS.rocksShare),
                    node.getDouble("cacheShare", DEFAULTS.cacheShare),
                    node.getDouble("axiomShare", DEFAULTS.axiomShare),
                    node.getDouble("measuredRoom", DEFAULTS.measuredRoom),
                    node.getLong("minimumHeap", DEFAULTS.minimumHeap),
                    node.getLong("reserveFloor", DEFAULTS.reserveFloor),
                    node.getDouble("reserveShare", DEFAULTS.reserveShare));
        }

        /**
         * Keeps the rule as the person's: a term equal to its default is removed, so a later default
         * reaches it.
         *
         * @param node the rule's node
         */
        public void write(Preferences node) {
            putDouble(node, "spined64Share", spined64Share, DEFAULTS.spined64Share);
            putDouble(node, "rocksShare", rocksShare, DEFAULTS.rocksShare);
            putDouble(node, "cacheShare", cacheShare, DEFAULTS.cacheShare);
            putDouble(node, "axiomShare", axiomShare, DEFAULTS.axiomShare);
            putDouble(node, "measuredRoom", measuredRoom, DEFAULTS.measuredRoom);
            putLong(node, "minimumHeap", minimumHeap, DEFAULTS.minimumHeap);
            putLong(node, "reserveFloor", reserveFloor, DEFAULTS.reserveFloor);
            putDouble(node, "reserveShare", reserveShare, DEFAULTS.reserveShare);
        }

        /**
         * What the machine keeps for everything but Komet.
         *
         * @param physical the machine's physical RAM
         * @return the reserve
         */
        public long reserve(long physical) {
            return Math.max(reserveFloor, Math.round(reserveShare * physical));
        }

        private static void putDouble(Preferences node, String key, double value, double fallback) {
            if (value == fallback) {
                node.remove(key);
            } else {
                node.putDouble(key, value);
            }
        }

        private static void putLong(Preferences node, String key, long value, long fallback) {
            if (value == fallback) {
                node.remove(key);
            } else {
                node.putLong(key, value);
            }
        }
    }

    /**
     * What a store is launched with.
     *
     * @param heap  the heap, whole gibibytes
     * @param cache Rocks' block cache, or 0 for a spined store
     */
    public record Need(long heap, long cache) {

        /**
         * The memory the launch claims.
         *
         * @return heap and cache
         */
        public long total() {
            return heap + cache;
        }

        /**
         * How a person reads it, such as {@code 20 GB heap} or {@code 8 GB heap, 1.3 GB cache}.
         *
         * @return the text
         */
        public String text() {
            return gb(heap) + " heap" + (cache > 0 ? ", " + gb(cache) + " cache" : "");
        }
    }

    /**
     * A Komet running on this machine and what it claims, whoever launched it.
     *
     * @param pid   its process
     * @param store the store it was launched on, when its command line names one
     * @param heap  its maximum heap
     * @param cache its Rocks block cache, 0 for a spined store
     */
    public record Running(long pid, Optional<Path> store, long heap, long cache) {

        /**
         * The memory it claims.
         *
         * @return heap and cache
         */
        public long total() {
            return heap + cache;
        }

        /**
         * How a person reads it, such as {@code solor-kb-1 (20 GB)}.
         *
         * @return the text
         */
        public String text() {
            return store.map(path -> path.getFileName().toString()).orElse("a Komet, process " + pid)
                    + " (" + gb(total()) + ")";
        }
    }

    /**
     * The machine a store is launched on.
     *
     * @param physical its physical RAM
     * @param running  the Komets running on it
     */
    public record Machine(long physical, List<Running> running) {

        /**
         * Copies the list.
         *
         * @param physical its physical RAM
         * @param running  the Komets running on it
         */
        public Machine {
            running = List.copyOf(running);
        }

        /**
         * This machine, now.
         *
         * @param komets the Komets installed, whose application bundles are recognized as running Komets
         * @return the machine
         */
        public static Machine current(List<KometInstallation> komets) {
            long physical = ((com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean())
                    .getTotalMemorySize();
            List<Path> bundles = komets.stream().filter(komet -> !KometLauncher.takesHeap(komet))
                    .map(KometInstallation::executable).toList();
            long self = ProcessHandle.current().pid();
            List<Running> running = new ArrayList<>();
            ProcessHandle.allProcesses().filter(process -> process.pid() != self).forEach(process -> {
                ProcessHandle.Info info = process.info();
                List<String> arguments = info.arguments().map(List::of).orElse(List.of());
                Optional<String> command = info.command();
                if (arguments.stream().anyMatch(argument -> argument.contains("dev.ikm.komet.desktop"))) {
                    running.add(HeapSizing.running(process.pid(), arguments, physical));
                } else if (command.map(Path::of).filter(bundles::contains).isPresent()) {
                    // A jpackage launcher passes its options to the JVM inside the process, so its
                    // command line shows none: its heap is the fixed one every such Komet starts at.
                    Optional<Path> store = argument(arguments, "--kb=").map(Path::of);
                    running.add(new Running(process.pid(), store, FIXED_HEAP, store.map(HeapSizing::rocksDefaultCache).orElse(0L)));
                }
            });
            return new Machine(physical, running);
        }

        /**
         * What the Komets running claim.
         *
         * @return their heaps and caches
         */
        public long claimed() {
            return running.stream().mapToLong(Running::total).sum();
        }
    }

    /** How a store's need meets the machine. */
    public enum Fit {
        /** It fits beside what is running. */
        FITS,
        /** It fits on this machine, but not beside the Komets running. */
        NOT_BESIDE_RUNNING,
        /** It needs more than this machine can give Komet at all. */
        EXCEEDS_MACHINE
    }

    /**
     * What a launch will be given, and how it fits.
     *
     * @param need      what the store is launched with
     * @param computed  what the rule asks for the store, before any override or a Komet's fixed heap
     * @param fit       how it meets the machine
     * @param data      the store's data, Lucene's index left out
     * @param available what is left beside the Komets running
     * @param ceiling   the most this machine can give Komet: physical RAM less the reserve
     * @param machine   the machine
     * @param source    why the need is what it is
     */
    public record Plan(Need need, Need computed, Fit fit, long data, long available, long ceiling, Machine machine,
                       Source source) {

        /**
         * The variables the launch sets: the heap, and for Rocks the cache.
         *
         * @return the environment, empty when the Komet's launcher takes no heap
         */
        public Map<String, String> environment() {
            if (source == Source.FIXED_BY_KOMET) {
                return Map.of();
            }
            Map<String, String> environment = new LinkedHashMap<>();
            environment.put(MAX_HEAP_VARIABLE, need.heap / GIB + "g");
            if (need.cache > 0) {
                environment.put(JAVA_OPTS_VARIABLE, "-D" + CACHE_PROPERTY + "=" + need.cache);
            }
            return environment;
        }

        /**
         * The same plan given a different need, such as the most the machine allows.
         *
         * @param other the need
         * @return the plan
         */
        public Plan with(Need other) {
            return new Plan(other, computed, HeapSizing.fit(other.total(), available, ceiling), data, available, ceiling, machine,
                    source == Source.FIXED_BY_KOMET ? source : Source.OVERRIDE);
        }

        /**
         * The most a room can give the store, its cache shrunk first, and never more than it needs.
         *
         * @param room the memory to fit in: {@link #available} beside what is running, or the
         *             {@link #ceiling} of the machine
         * @return the need, at least 1 GB of heap
         */
        public Need largestThatFits(long room) {
            long heap = Math.max(GIB, room - (need.cache > 0 ? MINIMUM_CACHE : 0)) / GIB * GIB;
            long cache = need.cache > 0 ? Math.max(MINIMUM_CACHE, Math.min(need.cache, room - heap)) : 0;
            return new Need(Math.min(heap, need.heap), cache);
        }
    }

    /** Why a need is what it is. */
    public enum Source {
        /** The rule's, from the store's kind and data. */
        RULE,
        /** What the store's assembly measured, with room for the collector. */
        MEASURED,
        /** The person set it for this store. */
        OVERRIDE,
        /** The Komet's launcher takes no heap: it starts at 42 GB, whatever the store needs. */
        FIXED_BY_KOMET
    }

    /**
     * What the rule asks for a store, with no room for the reasoner.
     *
     * @param rule the rule
     * @param kind the store's kind
     * @param data the store's data, Lucene's index left out
     * @return the need
     */
    public static Need need(Rule rule, StoreKind kind, long data) {
        return need(rule, kind, data, 0);
    }

    /**
     * What the rule asks for a store: its kind's share of its data, at least the floor, and room to
     * run the reasoner over its stated axioms on top.
     *
     * @param rule        the rule
     * @param kind        the store's kind
     * @param data        the store's data, Lucene's index left out
     * @param statedBytes the size of its stated axioms, 0 when unknown
     * @return the need
     */
    public static Need need(Rule rule, StoreKind kind, long data, long statedBytes) {
        double share = kind == StoreKind.ROCKS ? rule.rocksShare() : rule.spined64Share();
        long heap = roundUp(Math.max(rule.minimumHeap(), Math.round(share * data))
                + Math.round(rule.axiomShare() * statedBytes));
        long cache = kind == StoreKind.ROCKS ? Math.max(DEFAULT_CACHE, Math.round(rule.cacheShare() * data)) : 0;
        return new Need(heap, cache);
    }

    /**
     * What a store is launched with in a Komet on a machine.
     *
     * @param rule      the rule
     * @param store     the store
     * @param data      its data, Lucene's index left out ({@link #dataBytes})
     * @param override  the person's heap and cache for this store, if set
     * @param komet     the Komet it opens in
     * @param machine   the machine
     * @return the plan
     */
    public static Plan plan(Rule rule, KnowledgeBase store, long data, Optional<Need> override, KometInstallation komet,
                            Machine machine) {
        Optional<Long> measured = measuredHeap(rule, store);
        Need computed = needed(rule, store, data);
        long ceiling = Math.max(0, machine.physical() - rule.reserve(machine.physical()));
        long available = Math.max(0, ceiling - machine.claimed());
        Need need;
        Source source;
        if (!KometLauncher.takesHeap(komet)) {
            need = new Need(FIXED_HEAP, store.kind() == StoreKind.ROCKS ? DEFAULT_CACHE : 0);
            source = Source.FIXED_BY_KOMET;
        } else if (override.isPresent()) {
            need = override.get();
            source = Source.OVERRIDE;
        } else {
            need = computed;
            source = measured.isPresent() ? Source.MEASURED : Source.RULE;
            // The cache gives way first: a Rocks store whose heap fits gets what cache is left.
            if (need.cache() > 0 && need.total() > available && need.heap() + MINIMUM_CACHE <= available) {
                need = new Need(need.heap(), Math.max(MINIMUM_CACHE, available - need.heap()));
            }
        }
        return new Plan(need, computed, fit(need.total(), available, ceiling), data, available, ceiling, machine, source);
    }

    /**
     * What a store needs, before any override: as its assembly measured it, else as the rule asks.
     *
     * @param rule  the rule
     * @param store the store
     * @param data  its data, Lucene's index left out
     * @return the need
     */
    public static Need needed(Rule rule, KnowledgeBase store, long data) {
        Need ruled = need(rule, store.kind(), data, store.statedAxiomBytes().orElse(0L));
        return measuredHeap(rule, store).map(heap -> new Need(heap, ruled.cache())).orElse(ruled);
    }

    private static Optional<Long> measuredHeap(Rule rule, KnowledgeBase store) {
        return store.descriptor().descriptor()
                .flatMap(descriptor -> measuredHeap(rule, descriptor, store.kind(), store.statedAxiomBytes()));
    }

    /**
     * The heap a store's assembly measured, with room for the collector: the live heap loaded, at least
     * the floor, and what the reasoner added, grown with the stated axioms when they have grown since.
     * Only for the kind of store that was measured; a measurement of the reasoner missing, it stands
     * in as the rule's share of the stated axioms.
     *
     * @param rule      the rule
     * @param described the knowledge-base descriptor, from the store or from Nexus
     * @param kind      the kind of store being sized
     * @param statedNow the size of the store's stated axioms now, if known
     * @return the heap, whole gibibytes, or empty when the descriptor measured none for this kind
     */
    public static Optional<Long> measuredHeap(Rule rule, KnowledgeBaseDescriptor described, StoreKind kind,
                                              Optional<Long> statedNow) {
        KnowledgeBaseDescriptor.Memory memory = described.memory();
        if (memory.loadedHeapBytes().isEmpty() || StoreKind.ofLayout(described.layout()) != kind) {
            return Optional.empty();
        }
        long loaded = Math.max(rule.minimumHeap(), Math.round(rule.measuredRoom() * memory.loadedHeapBytes().get()));
        Optional<Long> stated = statedNow.or(memory::statedAxiomBytes);
        long reasoner = memory.reasonerHeapBytes().map(bytes -> {
            double growth = stated.flatMap(now -> memory.statedAxiomBytes().filter(then -> then > 0)
                    .map(then -> Math.max(1.0, (double) now / then))).orElse(1.0);
            return Math.round(rule.measuredRoom() * bytes * growth);
        }).orElseGet(() -> Math.round(rule.axiomShare() * stated.orElse(0L)));
        return Optional.of(roundUp(loaded + reasoner));
    }

    private static Fit fit(long total, long available, long ceiling) {
        if (total <= available) {
            return Fit.FITS;
        }
        return total <= ceiling ? Fit.NOT_BESIDE_RUNNING : Fit.EXCEEDS_MACHINE;
    }

    /**
     * A store's data: its size on disk without Lucene's index, which is memory-mapped, outside the heap.
     *
     * @param store the store
     * @return the bytes
     * @throws IOException if the index cannot be measured
     */
    public static long dataBytes(KnowledgeBase store) throws IOException {
        Path lucene = store.path().resolve("lucene");
        if (!Files.isDirectory(lucene)) {
            return store.sizeBytes();
        }
        return Math.max(0, store.sizeBytes() - KnowledgeBaseScanner.size(lucene));
    }

    /**
     * The person's heap and cache for one store, if set.
     *
     * @param stores the overrides' node
     * @param store  the store
     * @return the override
     */
    public static Optional<Need> override(Preferences stores, KnowledgeBase store) {
        String key = storeKey(store.path());
        long heap = stores.getLong(key + ".heap", 0);
        if (heap <= 0) {
            return Optional.empty();
        }
        return Optional.of(new Need(roundUp(heap), store.kind() == StoreKind.ROCKS
                ? stores.getLong(key + ".cache", DEFAULT_CACHE) : 0));
    }

    /**
     * Sets, or with empty clears, the person's heap and cache for one store.
     *
     * @param stores   the overrides' node
     * @param store    the store
     * @param override the heap and cache, or empty for the rule's
     */
    public static void setOverride(Preferences stores, KnowledgeBase store, Optional<Need> override) {
        String key = storeKey(store.path());
        override.ifPresentOrElse(need -> {
            stores.putLong(key + ".heap", roundUp(need.heap()));
            if (need.cache() > 0) {
                stores.putLong(key + ".cache", need.cache());
            } else {
                stores.remove(key + ".cache");
            }
        }, () -> {
            stores.remove(key + ".heap");
            stores.remove(key + ".cache");
        });
    }

    /**
     * The preferences key of a store: its folder's name, which is unique in the knowledge-base folder,
     * shortened with a hash of its path when too long for a key. The override stays on this machine: a
     * store copied elsewhere brings no heap sized for this one.
     *
     * @param store the store's folder
     * @return the key, without its {@code .heap} or {@code .cache} suffix
     */
    static String storeKey(Path store) {
        String name = store.getFileName().toString();
        int room = Preferences.MAX_KEY_LENGTH - ".heap".length() - ".cache".length();
        if (name.length() <= room) {
            return name;
        }
        String hash = Integer.toHexString(store.toAbsolutePath().toString().hashCode());
        return name.substring(0, room - hash.length() - 1) + "~" + hash;
    }

    /**
     * A Komet's claim from its command line: its last {@code -Xmx}, else the JVM's default of a quarter
     * of physical RAM; its last cache property, else Rocks' default when its store is a Rocks store.
     *
     * @param pid       the process
     * @param arguments its arguments
     * @param physical  the machine's physical RAM
     * @return what it claims
     */
    static Running running(long pid, List<String> arguments, long physical) {
        long heap = physical / 4;
        long cache = -1;
        for (String argument : arguments) {
            if (argument.startsWith("-Xmx")) {
                heap = parseSize(argument.substring(4)).orElse(heap);
            } else if (argument.startsWith("-D" + CACHE_PROPERTY + "=")) {
                try {
                    cache = Long.parseLong(argument.substring(CACHE_PROPERTY.length() + 3));
                } catch (NumberFormatException e) {
                    // left as Rocks' default
                }
            }
        }
        Optional<Path> store = argument(arguments, "--kb=").map(Path::of);
        if (cache < 0) {
            cache = store.map(HeapSizing::rocksDefaultCache).orElse(0L);
        }
        return new Running(pid, store, heap, cache);
    }

    private static long rocksDefaultCache(Path store) {
        return Files.isDirectory(store.resolve("rocks")) ? DEFAULT_CACHE : 0;
    }

    private static Optional<String> argument(List<String> arguments, String prefix) {
        return arguments.stream().filter(argument -> argument.startsWith(prefix))
                .map(argument -> argument.substring(prefix.length())).reduce((first, last) -> last);
    }

    /**
     * A JVM size, such as {@code 42g}, {@code 512m} or {@code 1073741824}.
     *
     * @param text the size
     * @return the bytes, or empty when it is not a size
     */
    static Optional<Long> parseSize(String text) {
        if (text.isEmpty()) {
            return Optional.empty();
        }
        char unit = Character.toLowerCase(text.charAt(text.length() - 1));
        int shift = switch (unit) {
            case 'k' -> 10;
            case 'm' -> 20;
            case 'g' -> 30;
            case 't' -> 40;
            default -> 0;
        };
        try {
            long value = Long.parseLong(shift == 0 ? text : text.substring(0, text.length() - 1));
            return Optional.of(value << shift);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /**
     * Rounds up to a whole gibibyte, the unit a heap is launched in.
     *
     * @param bytes the size
     * @return the size, whole gibibytes
     */
    static long roundUp(long bytes) {
        return (bytes + GIB - 1) / GIB * GIB;
    }

    /**
     * A size as a person reads a heap, such as {@code 20 GB} or {@code 1.3 GB}.
     *
     * @param bytes the size
     * @return the text
     */
    public static String gb(long bytes) {
        double value = (double) bytes / GIB;
        return value == Math.rint(value) || value >= 10 ? String.format(Locale.ROOT, "%.0f GB", value)
                : String.format(Locale.ROOT, "%.1f GB", value);
    }
}
