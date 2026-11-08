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

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Copies a store into a new folder beside it, everything in it included: its build, change sets, preferences and
 * repositories (KEC, 2026-11-08). Each folder of a knowledge base is a copy of its own, with its own change sets and
 * its own reason for being, and is upgraded on its own. The copy is made under {@code <name>.copying}, which the
 * dashboard does not list, and takes its name by one rename once it is whole, so a half copy is never a store. On
 * macOS the files are cloned (APFS {@code clonefile}), so a copy is quick and takes no space until one side changes.
 */
public final class StoreCopy {

    /** The suffix of a copy still being made. */
    public static final String PARTIAL_SUFFIX = ".copying";

    private StoreCopy() {
    }

    /**
     * A name for a new copy that no folder beside the store has: {@code <store>-copy}, then {@code <store>-copy-2}.
     *
     * @param store the store
     * @return the name
     */
    public static String suggestedName(Path store) {
        String base = store.getFileName() + "-copy";
        String name = base;
        for (int n = 2; Files.exists(store.resolveSibling(name)) || Files.exists(store.resolveSibling(name + PARTIAL_SUFFIX)); n++) {
            name = base + "-" + n;
        }
        return name;
    }

    /**
     * What is wrong with a name for a new copy, if anything.
     *
     * @param store the store
     * @param name  the name the person gave
     * @return why the name will not do, or empty when it will
     */
    public static Optional<String> nameProblem(Path store, String name) {
        String trimmed = name == null ? "" : name.strip();
        if (trimmed.isEmpty()) {
            return Optional.of("Give the new folder a name.");
        }
        if (trimmed.startsWith(".") || trimmed.contains("/") || trimmed.contains("\\") || trimmed.contains(":")) {
            return Optional.of("A folder name cannot start with a dot or hold / \\ or :.");
        }
        if (StoreUpgrade.isWork(trimmed) || trimmed.toLowerCase(Locale.ROOT).endsWith(StoreUpgrade.PREVIOUS_SUFFIX)) {
            return Optional.of("Names ending " + StoreUpgrade.WORK_SUFFIX + ", " + PARTIAL_SUFFIX + " or "
                    + StoreUpgrade.PREVIOUS_SUFFIX + " are the dashboard's own.");
        }
        if (Files.exists(store.resolveSibling(trimmed)) || Files.exists(store.resolveSibling(trimmed + PARTIAL_SUFFIX))) {
            return Optional.of(Names.of(trimmed) + " is already in " + Names.of(store.getParent()) + ".");
        }
        return Optional.empty();
    }

    /**
     * Why a copy must not start now, each with what to do; empty when it may. A store open in a Komet, or still
     * being written, would be copied half-way. A Komet is seen by the process the dashboard started, or by the
     * search index's lock ({@link StoreLock}) whoever started it.
     *
     * @param store   the store
     * @param running the Komets running on this machine
     * @return the refusals
     * @throws IOException if the store cannot be read
     */
    public static List<String> refusals(KnowledgeBase store, List<HeapSizing.Running> running) throws IOException {
        List<String> refusals = new ArrayList<>();
        Path path = store.path().toAbsolutePath().normalize();
        running.stream().filter(komet -> komet.store().map(open -> open.toAbsolutePath().normalize()).filter(path::equals).isPresent())
                .findFirst().ifPresent(komet -> refusals.add(Names.of(store.name()) + " is open in a Komet (process "
                        + komet.pid() + "); quit it first."));
        if (refusals.isEmpty() && StoreLock.openInKomet(store.path())) {
            refusals.add(StoreLock.refusal(store));
        }
        for (String folder : StoreUpgrade.CHANGE_SET_FOLDERS) {
            Path changeSets = store.path().resolve(folder);
            if (Files.isDirectory(changeSets)) {
                try (Stream<Path> files = Files.walk(changeSets, 4)) {
                    files.filter(file -> file.getFileName().toString().endsWith(".spool")).findFirst()
                            .ifPresent(spool -> refusals.add("Komet is still writing " + Names.of(store.path().relativize(spool))
                                    + "; quit it first."));
                }
            }
        }
        return List.copyOf(refusals);
    }

    /**
     * Copies a store into a new folder beside it.
     *
     * @param store the store
     * @param name  the new folder's name, which {@link #nameProblem} accepts
     * @return the new folder
     * @throws IOException if the name is taken or the copy fails; a partial copy is removed
     */
    public static Path copy(Path store, String name) throws IOException {
        Optional<String> problem = nameProblem(store, name);
        if (problem.isPresent()) {
            throw new IOException(problem.get());
        }
        Path target = store.resolveSibling(name.strip());
        Path partial = store.resolveSibling(name.strip() + PARTIAL_SUFFIX);
        try {
            if (!(OperatingSystem.current() == OperatingSystem.MAC && clone(store, partial))) {
                StoreUpgrade.deleteTree(partial);
                copyTree(store, partial);
            }
            Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE);
            return target;
        } catch (IOException e) {
            StoreUpgrade.deleteTree(partial);
            throw e;
        }
    }

    /** Clones a folder with {@code cp -c}, which fails where the volume cannot clone; then the copy is by bytes. */
    private static boolean clone(Path from, Path to) throws IOException {
        try {
            Process cp = new ProcessBuilder("/bin/cp", "-cRp", from.toString(), to.toString())
                    .redirectErrorStream(true).start();
            cp.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
            return cp.waitFor() == 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while copying " + from.getFileName(), e);
        }
    }

    /** Copies a folder file by file, keeping the files' times. */
    static void copyTree(Path from, Path to) throws IOException {
        Files.walkFileTree(from, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) throws IOException {
                Files.createDirectories(to.resolve(from.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.copy(file, to.resolve(from.relativize(file).toString()), StandardCopyOption.COPY_ATTRIBUTES,
                        java.nio.file.LinkOption.NOFOLLOW_LINKS);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
