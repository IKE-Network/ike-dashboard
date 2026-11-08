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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The file work of Upgrade and Restore (decision 20 of the {@code dev-ike-dashboard} topic). An upgrade
 * brings a store to the newest build in place, keeping the person's work: the new build is downloaded
 * into a working folder beside the store, the work is carried into it, Komet validates it with no
 * window ({@link KometLauncher#loadChangeSets}), and the two folders are swapped by rename, the old
 * store becoming {@code <store>.previous}. Nothing is ever written over, and each rename is one atomic
 * step on the one volume {@code ~/Solor} is on. Restore is the swap in reverse, carrying along the work
 * done since.
 *
 * <p>What is carried: the change-set folders ({@value #CHANGE_SET_FOLDERS}), merged file by file, since
 * a change set is immutable and named once; the {@value #PREFERENCES} folder, replaced whole, since the
 * latest session's windows and journals are the ones to keep (decision 16); and every other git
 * repository found in the store (decision 14), moved whole. {@value #NOT_CARRIED} is regenerated and
 * stays behind. The dashboard opens no store: what needs the store open is Komet's.
 */
public final class StoreUpgrade {

    /** The suffix of the folder a new build is downloaded into, beside the store, until the swap. */
    public static final String WORK_SUFFIX = ".upgrade";

    /**
     * The suffix the old store takes at the swap, for the moment before it goes to the Trash: a confirmed upgrade
     * keeps no previous build (KEC, 2026-11-08), and the Trash is the way back.
     */
    public static final String PREVIOUS_SUFFIX = ".previous";

    /**
     * The folders of change sets, merged file by file: the two Komet reads, and the one refused change sets
     * are set aside in, which Komet never reads ({@link RefusedChangeSets#UNPROCESSED_FOLDER}).
     */
    static final List<String> CHANGE_SET_FOLDERS = List.of("changeSets", "instanceChangeSets",
            RefusedChangeSets.UNPROCESSED_FOLDER);

    /** The preferences folder, replaced whole. */
    static final String PREFERENCES = "preferences";

    /** The folder that is regenerated and never carried. */
    static final String NOT_CARRIED = "ephemeralChangeSets";

    private StoreUpgrade() {
    }

    /**
     * Where a store's new build is downloaded: {@code <store>.upgrade} beside it.
     *
     * @param store the store
     * @return the working folder
     */
    public static Path workFolder(Path store) {
        return store.resolveSibling(store.getFileName() + WORK_SUFFIX);
    }

    /**
     * Where a store's old build steps aside at the swap, {@code <store>.previous} beside it, until it goes to
     * the Trash.
     *
     * @param store the store
     * @return the previous folder
     */
    public static Path previousFolder(Path store) {
        return store.resolveSibling(store.getFileName() + PREVIOUS_SUFFIX);
    }

    /**
     * Whether a folder name is a working folder, which the dashboard does not list: an upgrade's new build, or a
     * copy still being made ({@link StoreCopy#PARTIAL_SUFFIX}).
     *
     * @param name the folder name
     * @return {@code true} for {@code <store>.upgrade} and {@code <copy>.copying}
     */
    public static boolean isWork(String name) {
        return name.endsWith(WORK_SUFFIX) || name.endsWith(StoreCopy.PARTIAL_SUFFIX);
    }

    /**
     * Why an upgrade or a reinstall must not start now, each with what to do; empty when it may.
     * A store open in a Komet, or still being written, would be carried half-way; work not committed
     * or not pushed would travel on this machine alone; a working folder left by an earlier upgrade is
     * kept for inspection and must be moved away first.
     *
     * @param store   the store
     * @param sync    the states of the repositories inside it, as last checked
     * @param running the Komets running on this machine
     * @return the refusals, none when the upgrade may start
     * @throws IOException if the store cannot be read
     */
    public static List<String> refusals(KnowledgeBase store, List<RepositorySync.Status> sync,
                                        List<HeapSizing.Running> running) throws IOException {
        List<String> refusals = new ArrayList<>();
        Path path = store.path().toAbsolutePath().normalize();
        running.stream().filter(komet -> komet.store().map(open -> open.toAbsolutePath().normalize()).filter(path::equals).isPresent())
                .findFirst().ifPresent(komet -> refusals.add(StoreLock.refusal(store)));
        if (refusals.isEmpty() && StoreLock.openInKomet(store.path())) {
            refusals.add(StoreLock.refusal(store));
        }
        for (String folder : CHANGE_SET_FOLDERS) {
            Path changeSets = store.path().resolve(folder);
            if (Files.isDirectory(changeSets)) {
                try (Stream<Path> files = Files.walk(changeSets, 4)) {
                    files.filter(file -> file.getFileName().toString().endsWith(".spool")).findFirst()
                            .ifPresent(spool -> refusals.add("Komet is still writing " + Names.of(store.path().relativize(spool))
                                    + "; quit it first."));
                }
            }
        }
        for (RepositorySync.Status status : sync) {
            String where = status.repository().label();
            if (status.uncommitted() > 0) {
                refusals.add(Notices.plural(status.uncommitted(), "file", "files") + " not committed in " + where
                        + "; commit them first, so they are kept.");
            }
            if (status.ahead() > 0) {
                refusals.add(Notices.plural(status.ahead(), "commit", "commits") + " not pushed from " + where
                        + "; push them first, so they are not only on this machine.");
            }
        }
        Path work = workFolder(store.path());
        if (Files.exists(work)) {
            refusals.add("An earlier upgrade left the folder " + Names.of(work.getFileName()) + " next to this one. Move it to"
                    + " the " + Finder.trashName() + " to upgrade again.");
        }
        return List.copyOf(refusals);
    }

    /**
     * What a carry did.
     *
     * @param moved     the folders moved whole into the new store
     * @param merged    the files moved into folders the new store already had
     * @param conflicts the files left behind: the new store has a file of the same name with other bytes
     */
    public record Carried(List<Path> moved, List<Path> merged, List<Path> conflicts) {

        /** Nothing carried. */
        public static final Carried NONE = new Carried(List.of(), List.of(), List.of());

        /**
         * Copies the lists.
         *
         * @param moved     the folders moved
         * @param merged    the files merged
         * @param conflicts the files left behind
         */
        public Carried {
            moved = List.copyOf(moved);
            merged = List.copyOf(merged);
            conflicts = List.copyOf(conflicts);
        }

        /**
         * One line for a person.
         *
         * @return the summary
         */
        public String summary() {
            return moved.size() + (moved.size() == 1 ? " folder" : " folders") + " and " + merged.size()
                    + (merged.size() == 1 ? " file" : " files") + " moved into the new version"
                    + (conflicts.isEmpty() ? "" : ". " + conflicts.size() + " left behind, because the new version has a"
                    + " different file with the same name");
        }
    }

    /**
     * Carries a person's work from one store into another: the change-set folders merged, the
     * preferences replaced, every other repository moved. A file of the same name and size as one the
     * target has is left where it was, as the same change set; one of another size is a conflict, left
     * where it was and reported, never written over.
     *
     * @param from the store the work is in
     * @param to   the store it goes to
     * @return what was carried
     * @throws IOException if a move fails
     */
    public static Carried carry(Path from, Path to) throws IOException {
        List<Path> moved = new ArrayList<>();
        List<Path> merged = new ArrayList<>();
        List<Path> conflicts = new ArrayList<>();
        for (String folder : CHANGE_SET_FOLDERS) {
            Path source = from.resolve(folder);
            if (Files.isDirectory(source)) {
                merge(source, to.resolve(folder), moved, merged, conflicts);
            }
        }
        Path preferences = from.resolve(PREFERENCES);
        if (Files.isDirectory(preferences)) {
            Path target = to.resolve(PREFERENCES);
            if (Files.exists(target)) {
                deleteTree(target);
            }
            Files.move(preferences, target);
            moved.add(target);
        }
        for (RepositorySync.Repository repository : RepositorySync.find(from)) {
            Path relative = from.relativize(repository.folder());
            String top = relative.getNameCount() == 0 ? "" : relative.getName(0).toString();
            if (relative.getNameCount() == 0 || CHANGE_SET_FOLDERS.contains(top) || top.equals(PREFERENCES)
                    || top.equals(NOT_CARRIED) || !Files.exists(repository.folder())) {
                continue;
            }
            merge(repository.folder(), to.resolve(relative), moved, merged, conflicts);
        }
        return new Carried(moved, merged, conflicts);
    }

    private static void merge(Path source, Path target, List<Path> moved, List<Path> merged, List<Path> conflicts)
            throws IOException {
        if (!Files.exists(target)) {
            Files.createDirectories(target.getParent());
            Files.move(source, target);
            moved.add(target);
            return;
        }
        if (!Files.isDirectory(source) || !Files.isDirectory(target)) {
            if (Files.size(source) != Files.size(target)) {
                conflicts.add(source);
            }
            return;
        }
        List<Path> entries;
        try (Stream<Path> children = Files.list(source)) {
            entries = children.sorted(Comparator.comparing(path -> path.getFileName().toString())).toList();
        }
        for (Path entry : entries) {
            Path into = target.resolve(entry.getFileName().toString());
            if (!Files.exists(into)) {
                Files.move(entry, into);
                merged.add(into);
            } else {
                merge(entry, into, moved, merged, conflicts);
            }
        }
    }

    /**
     * Swaps the new build in: the store to {@code <store>.previous}, the working folder to the store's
     * name, each one atomic rename. If the second fails, the first is undone, so the store is never
     * absent for longer than the moment between them.
     *
     * @param store the store
     * @param work  the validated new build beside it
     * @throws IOException if {@code <store>.previous} exists, or a rename fails
     */
    public static void swap(Path store, Path work) throws IOException {
        Path previous = previousFolder(store);
        if (Files.exists(previous)) {
            throw new IOException(previous + " already exists; move it away before the swap");
        }
        Files.move(store, previous, StandardCopyOption.ATOMIC_MOVE);
        try {
            Files.move(work, store, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(previous, store, StandardCopyOption.ATOMIC_MOVE);
            throw e;
        }
    }

    static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        List<Path> paths;
        try (Stream<Path> walk = Files.walk(root)) {
            paths = walk.sorted(Comparator.reverseOrder()).toList();
        }
        for (Path path : paths) {
            Files.deleteIfExists(path);
        }
    }
}
