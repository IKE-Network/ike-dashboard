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

import java.nio.file.Path;

/**
 * Marks the names of files, folders and paths in a message, so the window shows them in bold (KEC, 2026-11-08):
 * {@code "Copied " + Names.of(from) + " to " + Names.of(to)}. A mark is a pair of private-use characters, which
 * {@link SelectableText} draws as bold and {@link #plain} removes for the command line and the clipboard. Plain
 * Java, with no JavaFX, so the model and the command line can mark and strip names.
 */
public final class Names {

    /** Opens a name. */
    static final char START = '';

    /** Closes a name. */
    static final char END = '';

    private Names() {
    }

    /**
     * A file, folder or path name, marked.
     *
     * @param name the name: a {@link Path} reads as its whole path, anything else as its text
     * @return the name between marks
     */
    public static String of(Object name) {
        return START + plain(String.valueOf(name)) + END;
    }

    /**
     * The text without its marks, as the command line prints it and the clipboard takes it.
     *
     * @param text the text
     * @return the text, unmarked
     */
    public static String plain(String text) {
        if (text == null || text.indexOf(START) < 0 && text.indexOf(END) < 0) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != START && c != END) {
                out.append(c);
            }
        }
        return out.toString();
    }
}
