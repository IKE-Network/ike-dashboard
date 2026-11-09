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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * What in a store is saved nowhere else, read before the store goes to the Trash (KEC, 2026-11-08: "Can't you
 * tell if there is unsaved changes? i.e. change sets not synchronized with git"). For each git repository in the
 * store: files not committed, commits not pushed, and history with no remote at all; and change sets in no
 * repository. Read from the repositories' own files, with no network: "not pushed" is as of the last push or fetch
 * from this machine.
 */
public final class UnsavedWork {

    /** The folders change sets are kept in. */
    private static final List<String> CHANGE_SET_FOLDERS = StoreUpgrade.CHANGE_SET_FOLDERS;

    /**
     * What a store contains that is saved nowhere else, and whether that could be read.
     *
     * @param findings   each thing not saved, one sentence each, empty when everything is
     * @param repositories how many git repositories the store has
     * @param readable   {@code false} when git could not be found to read them
     */
    public record Report(List<String> findings, int repositories, boolean readable) {

        /**
         * Whether everything in the store is saved elsewhere, as far as could be read.
         *
         * @return {@code true} when nothing was found unsaved and the repositories could be read
         */
        public boolean saved() {
            return readable && findings.isEmpty();
        }
    }

    private UnsavedWork() {
    }

    /**
     * Reads a store's repositories and change sets.
     *
     * @param git   the git command, if one was found
     * @param store the store's folder
     * @return what is saved nowhere else
     */
    public static Report of(Optional<RepositorySync.Git> git, Path store) {
        List<RepositorySync.Repository> repositories;
        try {
            repositories = RepositorySync.find(store);
        } catch (IOException e) {
            return new Report(List.of("Its folders could not be read: " + e.getMessage() + "."), 0, false);
        }
        List<String> findings = new ArrayList<>();
        int outside = changeSetsOutside(store, repositories);
        if (outside > 0) {
            findings.add(outside + (outside == 1 ? " change set is" : " change sets are")
                    + " in no git repository, so " + (outside == 1 ? "it is" : "they are") + " only in this folder.");
        }
        if (repositories.isEmpty()) {
            return new Report(List.copyOf(findings), 0, true);
        }
        if (git.isEmpty()) {
            findings.add("git was not found, so the dashboard cannot tell whether its "
                    + (repositories.size() == 1 ? "repository is" : repositories.size() + " repositories are") + " saved.");
            return new Report(List.copyOf(findings), repositories.size(), false);
        }
        List<RepositorySync.Status> statuses = repositories.stream()
                .map(repository -> RepositorySync.local(git.get(), repository)).toList();
        findings.addAll(describe(statuses));
        return new Report(List.copyOf(findings), repositories.size(), true);
    }

    /**
     * Each repository's unsaved work in words.
     *
     * @param statuses the repositories' states from their files
     * @return one sentence for each thing not saved
     */
    static List<String> describe(List<RepositorySync.Status> statuses) {
        List<String> findings = new ArrayList<>();
        for (RepositorySync.Status status : statuses) {
            String name = Names.of(status.repository().folder().getFileName());
            if (!status.problem().isBlank() && status.remote() == RepositorySync.Remote.UNKNOWN) {
                findings.add(name + " could not be read by git: " + status.problem() + ".");
                continue;
            }
            if (status.uncommitted() > 0) {
                findings.add(name + " has " + Notices.plural(status.uncommitted(), "file", "files") + " not committed.");
            }
            if (status.remote() == RepositorySync.Remote.NONE) {
                findings.add(name + " has no remote, so its history is only in this folder.");
            } else if (status.ahead() > 0) {
                findings.add(name + " has " + Notices.plural(status.ahead(), "commit", "commits") + " not pushed.");
            }
        }
        return findings;
    }

    /** Change-set files in the store that no repository contains. */
    private static int changeSetsOutside(Path store, List<RepositorySync.Repository> repositories) {
        int count = 0;
        for (String folder : CHANGE_SET_FOLDERS) {
            Path changeSets = store.resolve(folder);
            if (!Files.isDirectory(changeSets)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(changeSets)) {
                count += (int) files.filter(Files::isRegularFile)
                        .filter(file -> file.getFileName().toString().endsWith(".zip"))
                        .filter(file -> repositories.stream().noneMatch(repository -> file.startsWith(repository.folder())))
                        .count();
            } catch (IOException e) {
                // A folder that cannot be walked is reported by git, or not at all; it is not counted here.
            }
        }
        return count;
    }
}
