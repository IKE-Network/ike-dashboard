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
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The file work of Upgrade (decision 20 of the {@code dev-ike-dashboard} topic). An upgrade
 * brings a store to the newest build in place, keeping the person's work: the new build is downloaded
 * into a working folder beside the store, the work is carried into it, Komet validates it with no
 * window ({@link KometLauncher#loadChangeSets}), and the two folders are swapped by rename, the old
 * store becoming {@code <store>.previous}. Nothing is ever written over, and each rename is one atomic
 * step on the one volume {@code ~/Solor} is on. An upgrade that does not finish undoes exactly the moves it
 * made ({@link Moves}), so the store is as it was.
 *
 * <p>What is carried: the change-set folders ({@link #CHANGE_SET_FOLDERS}), merged file by file, since
 * a change set is immutable and named once; the {@value #PREFERENCES} folder, replaced whole, since the
 * latest session's windows and journals are the ones to keep (decision 16); and every other git
 * repository found in the store (decision 14), moved whole. A change-set folder that is a git repository keeps
 * its history and its remote: its {@code .git} moves as one thing, and is never merged with another. The folders
 * Komet reads again from documents' files ({@link #NOT_CARRIED}) stay behind. The dashboard opens no store: what
 * needs the store open is Komet's.
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
     * The folders of local change sets, which are in no git repository because local data does not sync with
     * the knowledge base: the one Komet writes, and the one earlier Komets write, which every Komet reads
     * (IKE-Network/ike-issues#1313).
     */
    static final List<String> LOCAL_CHANGE_SET_FOLDERS = List.of("localChangeSets", "instanceChangeSets");

    /**
     * The folders of change sets, merged file by file: the knowledge, shared and local ones Komet reads, and the
     * one refused change sets are set aside in, which Komet never reads
     * ({@link RefusedChangeSets#UNPROCESSED_FOLDER}).
     */
    static final List<String> CHANGE_SET_FOLDERS = Stream.of(
            List.of(RepositorySync.CHANGE_SETS, RepositorySync.SHARED_CHANGE_SETS), LOCAL_CHANGE_SET_FOLDERS,
            List.of(RefusedChangeSets.UNPROCESSED_FOLDER)).flatMap(List::stream).toList();

    /** The preferences folder, replaced whole. */
    static final String PREFERENCES = "preferences";

    /** What makes a folder a git repository: its history, its index and its remotes. */
    private static final String GIT = ".git";

    /**
     * The folders that are regenerated and never carried: the change sets of documents saved in files, which Komet
     * reads from those files again. The second is the name earlier Komets write.
     */
    static final List<String> NOT_CARRIED = List.of("fileChangeSets", "ephemeralChangeSets");

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
     * kept for inspection and must be moved away first, after the work it contains that is not in the store.
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
            refusals.add(leftover(work, store.path()));
        }
        return List.copyOf(refusals);
    }

    /**
     * Why an upgrade cannot carry a store's work, with what to do instead: the store is itself a git repository.
     * Moved as other repositories are, it would take the whole old build into the new one; left behind, its history
     * would go to the Trash with the old build. Reinstall keeps nothing, so it does not ask.
     *
     * @param store the store's folder
     * @return the refusal; empty when the store is not a git repository
     */
    public static Optional<String> cannotCarry(Path store) {
        return Files.exists(store.resolve(GIT))
                ? Optional.of(Names.of(store.getFileName()) + " is itself a git repository, and an upgrade cannot keep that"
                        + " repository. Use Install a new copy, which leaves this folder as it is.")
                : Optional.empty();
    }

    /**
     * What to do about a folder an earlier upgrade left: move it to the Trash, unless it contains work that is not in
     * the store. It does when the upgrade could not undo its moves, as when the dashboard stopped before that or
     * something could not be put back, and that work may have no other copy.
     */
    private static String leftover(Path work, Path store) throws IOException {
        String left = "An earlier upgrade left the folder " + Names.of(work.getFileName()) + " next to this one";
        List<String> only = onlyIn(work, store);
        if (only.isEmpty()) {
            return left + ". Move it to the " + Finder.trashName() + " to upgrade again.";
        }
        String listed = only.size() == 1 ? only.getFirst()
                : String.join(", ", only.subList(0, only.size() - 1)) + " and " + only.getLast();
        return left + ", and it contains work that is not in " + Names.of(store.getFileName()) + ": " + listed + ". Do not"
                + " move the folder to the " + Finder.trashName() + " yet, because it may contain the only copy. First move that"
                + " work to the same place in " + Names.of(store.getFileName()) + ", without replacing anything there.";
    }

    /**
     * The work a folder an upgrade left contains and the store does not, of what an upgrade carries ({@link #carry}):
     * one phrase for each folder of it. A change set the store has with the same bytes is not such work.
     */
    private static List<String> onlyIn(Path work, Path store) throws IOException {
        List<String> found = new ArrayList<>();
        Set<String> repositories = new LinkedHashSet<>();
        for (RepositorySync.Repository repository : RepositorySync.find(work)) {
            Path relative = work.relativize(repository.folder());
            if (!relative.toString().isEmpty() && !Files.exists(store.resolve(relative).resolve(GIT))) {
                repositories.add(relative.toString());
            }
        }
        for (String folder : CHANGE_SET_FOLDERS) {
            int count = changeSetsOnlyIn(work, store, folder);
            String changeSets = Notices.plural(count, "change set", "change sets");
            if (repositories.remove(folder)) {
                found.add("the git repository " + Names.of(folder) + (count == 0 ? "" : " with " + changeSets));
            } else if (count > 0) {
                found.add(changeSets + " in " + Names.of(folder));
            }
        }
        if (Files.isDirectory(work.resolve(PREFERENCES)) && !Files.exists(store.resolve(PREFERENCES))) {
            repositories.remove(PREFERENCES);
            found.add("the folder " + Names.of(PREFERENCES));
        }
        repositories.forEach(repository -> found.add("the git repository " + Names.of(repository)));
        return List.copyOf(found);
    }

    private static int changeSetsOnlyIn(Path work, Path store, String folder) throws IOException {
        Path changeSets = work.resolve(folder);
        if (!Files.isDirectory(changeSets)) {
            return 0;
        }
        List<Path> files;
        try (Stream<Path> walk = Files.walk(changeSets)) {
            files = walk.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().endsWith(UnsavedWork.CHANGE_SET_SUFFIX)).toList();
        }
        int count = 0;
        for (Path file : files) {
            Path same = store.resolve(work.relativize(file));
            if (!Files.isRegularFile(same) || Files.mismatch(file, same) != -1) {
                count++;
            }
        }
        return count;
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
     * The moves of a person's work into a new build, kept as each is made, so that an upgrade that does not finish
     * undoes exactly those. Carrying from the new build to the old one would not: it would take along the new
     * build's own change sets, and replace the person's preferences with the new build's when the carry failed
     * before it reached them.
     */
    public static final class Moves {

        /** One move made. */
        private record Move(Path from, Path to) {
        }

        private final List<Move> made = new ArrayList<>();

        /** No moves yet. */
        public Moves() {
        }

        private void move(Path from, Path to) throws IOException {
            Files.move(from, to);
            made.add(new Move(from, to));
        }

        /**
         * Puts everything back where it was, latest first. A move that cannot be undone does not keep the others from
         * it, and stays to be tried again by another call. Nothing is written over: where something is in the old
         * place by now, what differs stays in the new one.
         *
         * @throws IOException if something cannot be put back
         */
        public void undo() throws IOException {
            IOException failure = null;
            for (int i = made.size() - 1; i >= 0; i--) {
                try {
                    putBack(made.get(i));
                    made.remove(i);
                } catch (IOException e) {
                    if (failure == null) {
                        failure = e;
                    } else {
                        failure.addSuppressed(e);
                    }
                }
            }
            if (failure != null) {
                throw failure;
            }
        }

        /** Keeps the moves for good: the upgrade finished, and there is nothing to undo. */
        public void keep() {
            made.clear();
        }

        private static void putBack(Move move) throws IOException {
            if (!Files.exists(move.to(), LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            List<Path> conflicts = new ArrayList<>();
            merge(move.to(), move.from(), new Moves(), new ArrayList<>(), new ArrayList<>(), conflicts);
            if (!conflicts.isEmpty()) {
                throw new IOException(Names.of(conflicts.getFirst()) + " could not be put back, because "
                        + Names.of(move.from().resolve(move.to().relativize(conflicts.getFirst()))) + " is different");
            }
        }
    }

    /**
     * Carries a person's work from one store into another: the change-set folders merged, the
     * preferences replaced, every other repository moved. A file of the same name and bytes as one the
     * target has is left where it was, as the same change set; one with other bytes is a conflict, left
     * where it was and reported, never written over. A folder that is a git repository on both sides is a
     * conflict as a whole: two histories are not merged by moving files, so both stay as they were. A store that is
     * itself a git repository is not carried ({@link #cannotCarry}): moving that repository would move the whole old
     * build into the new one.
     *
     * @param from  the store the work is in
     * @param to    the store it goes to
     * @param moves where each move made is kept, those before a failure included, so it can be undone
     * @return what was carried
     * @throws IOException if a move fails, or the store is itself a git repository
     */
    public static Carried carry(Path from, Path to, Moves moves) throws IOException {
        if (cannotCarry(from).isPresent()) {
            throw new IOException(Names.of(from.getFileName()) + " is itself a git repository");
        }
        List<Path> moved = new ArrayList<>();
        List<Path> merged = new ArrayList<>();
        List<Path> conflicts = new ArrayList<>();
        for (String folder : CHANGE_SET_FOLDERS) {
            Path source = from.resolve(folder);
            if (Files.isDirectory(source)) {
                merge(source, to.resolve(folder), moves, moved, merged, conflicts);
            }
        }
        Path preferences = from.resolve(PREFERENCES);
        if (Files.isDirectory(preferences)) {
            Path target = to.resolve(PREFERENCES);
            if (Files.exists(target)) {
                deleteTree(target);
            }
            moves.move(preferences, target);
            moved.add(target);
        }
        for (RepositorySync.Repository repository : RepositorySync.find(from)) {
            Path relative = from.relativize(repository.folder());
            String top = relative.getName(0).toString();
            if (CHANGE_SET_FOLDERS.contains(top) || top.equals(PREFERENCES)
                    || NOT_CARRIED.contains(top) || !Files.exists(repository.folder())) {
                continue;
            }
            merge(repository.folder(), to.resolve(relative), moves, moved, merged, conflicts);
        }
        return new Carried(moved, merged, conflicts);
    }

    private static void merge(Path source, Path target, Moves moves, List<Path> moved, List<Path> merged,
                              List<Path> conflicts) throws IOException {
        if (!Files.exists(target)) {
            Files.createDirectories(target.getParent());
            moves.move(source, target);
            moved.add(target);
            return;
        }
        if (!Files.isDirectory(source) || !Files.isDirectory(target)) {
            if (Files.isDirectory(source) || Files.isDirectory(target) || Files.mismatch(source, target) != -1) {
                conflicts.add(source);
            }
            return;
        }
        Path history = source.resolve(GIT);
        if (Files.exists(history) && Files.exists(target.resolve(GIT))) {
            conflicts.add(history);
            return;
        }
        List<Path> entries;
        try (Stream<Path> children = Files.list(source)) {
            entries = children.sorted(Comparator.comparing(path -> path.getFileName().toString())).toList();
        }
        for (Path entry : entries) {
            Path into = target.resolve(entry.getFileName().toString());
            if (!Files.exists(into)) {
                moves.move(entry, into);
                merged.add(into);
            } else {
                merge(entry, into, moves, moved, merged, conflicts);
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
