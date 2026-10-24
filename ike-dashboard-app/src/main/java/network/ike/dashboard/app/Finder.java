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
import java.util.ArrayList;
import java.util.List;

/**
 * What the dashboard asks of the Finder: show a file, open one, and move copies to the Trash, where
 * the person can still take them back. Nothing the dashboard removes is deleted outright.
 */
public final class Finder {

    private static final Path TRASH = Path.of("/usr/bin/trash");

    private Finder() {
    }

    /**
     * Shows a file or folder selected in a Finder window.
     *
     * @param path the file or folder
     * @throws IOException if the Finder cannot be asked
     */
    public static void reveal(Path path) throws IOException {
        new ProcessBuilder("open", "-R", path.toString()).start();
    }

    /**
     * Opens a file in the application that opens it, as double-clicking it would.
     *
     * @param path the file
     * @throws IOException if it cannot be opened
     */
    public static void open(Path path) throws IOException {
        new ProcessBuilder("open", path.toString()).start();
    }

    /**
     * Moves files and folders to the Trash.
     *
     * @param paths what to move
     * @throws IOException          if any could not be moved
     * @throws InterruptedException if interrupted while waiting
     */
    public static void trash(List<Path> paths) throws IOException, InterruptedException {
        if (paths.isEmpty()) {
            return;
        }
        List<String> command = new ArrayList<>();
        if (Files.isExecutable(TRASH)) {
            command.add(TRASH.toString());
            paths.forEach(path -> command.add(path.toString()));
        } else {
            command.addAll(List.of("osascript", "-e", "on run paths",
                    "-e", "repeat with p in paths",
                    "-e", "tell application \"Finder\" to delete (POSIX file (p as text))",
                    "-e", "end repeat", "-e", "end run"));
            paths.forEach(path -> command.add(path.toString()));
        }
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes()).strip();
        if (process.waitFor() != 0) {
            throw new IOException("Could not move to the Trash" + (output.isEmpty() ? "" : ": " + output));
        }
    }
}
