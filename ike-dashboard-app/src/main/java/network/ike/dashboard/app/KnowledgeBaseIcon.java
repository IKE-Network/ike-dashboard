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

import javafx.scene.Node;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.zip.CRC32;

/**
 * What tells one knowledge base from another at a glance: the icon its store carries
 * ({@code Ike-Icon}, a PNG the build copied from the project's {@code src/main/kb/icon.png}), or else a
 * monogram — the initials of its name on a rounded square, in a colour that is the same for the name
 * wherever it is shown.
 */
final class KnowledgeBaseIcon {

    /** Words a knowledge base's name carries that say nothing about which one it is. */
    private static final List<String> GENERIC = List.of("kb");

    private KnowledgeBaseIcon() {
    }

    /**
     * The icon for a knowledge base, at a size.
     *
     * @param name  the knowledge base's name, for the monogram
     * @param image the icon, when there is one
     * @param size  the width and height in pixels
     * @return the icon, or the monogram when there is none or it could not be read
     */
    static Node of(String name, Optional<Image> image, double size) {
        Node shown = image.filter(loaded -> !loaded.isError()).<Node>map(loaded -> {
            ImageView view = new ImageView(loaded);
            view.setFitWidth(size);
            view.setFitHeight(size);
            view.setPreserveRatio(true);
            view.setSmooth(true);
            return view;
        }).orElseGet(() -> monogram(name, size));
        StackPane box = new StackPane(shown);
        box.setMinSize(size, size);
        box.setPrefSize(size, size);
        box.setMaxSize(size, size);
        return box;
    }

    /**
     * Reads an icon file. JavaFX picks the {@code @2x} file beside it on a high-resolution screen.
     *
     * @param file the PNG
     * @return the image; {@link Image#isError()} says whether it could be read
     */
    static Image load(Path file) {
        return new Image(file.toUri().toString());
    }

    /**
     * Reads an icon from its bytes, as Nexus sent them.
     *
     * @param png the PNG's bytes
     * @return the image; {@link Image#isError()} says whether it could be read
     */
    static Image load(byte[] png) {
        return new Image(new ByteArrayInputStream(png));
    }

    /**
     * The monogram for a name: its initials on a rounded square in the name's colour.
     *
     * @param name the knowledge base's name
     * @param size the width and height in pixels
     * @return the monogram
     */
    static Node monogram(String name, double size) {
        Rectangle square = new Rectangle(size, size, color(name));
        square.setArcWidth(size * 0.45);
        square.setArcHeight(size * 0.45);
        String initials = initials(name);
        Text text = new Text(initials);
        text.setFill(Color.WHITE);
        text.setFont(Font.font(null, FontWeight.BOLD, size * (initials.length() > 2 ? 0.34 : 0.44)));
        return new StackPane(square, text);
    }

    /**
     * The initials of a name: the first letters of up to three of its words, or the first two letters
     * of a name of one word. Words are split at punctuation and where a lower-case letter meets an
     * upper-case one; a word such as {@code kb}, which every knowledge base has, is left out.
     *
     * @param name the knowledge base's name, such as {@code solor-gudid-kb}
     * @return the initials, such as {@code SG}; {@code ?} when the name has no letters or digits
     */
    static String initials(String name) {
        List<String> words = Arrays.stream(name.split("[^\\p{L}\\p{N}]+|(?<=\\p{Ll})(?=\\p{Lu})"))
                .filter(word -> !word.isEmpty()).toList();
        List<String> telling = words.stream().filter(word -> !GENERIC.contains(word.toLowerCase(Locale.ROOT))).toList();
        if (!telling.isEmpty()) {
            words = telling;
        }
        if (words.isEmpty()) {
            return "?";
        }
        if (words.size() == 1) {
            int[] letters = words.getFirst().codePoints().limit(2).toArray();
            return new String(letters, 0, 1).toUpperCase(Locale.ROOT)
                    + (letters.length > 1 ? new String(letters, 1, 1).toLowerCase(Locale.ROOT) : "");
        }
        StringBuilder initials = new StringBuilder();
        words.stream().limit(3).forEach(word -> initials.appendCodePoint(Character.toUpperCase(word.codePointAt(0))));
        return initials.toString();
    }

    /**
     * The colour of a name's monogram: a hue from a checksum of the name, the same on every run and
     * every machine, at a saturation and brightness white initials read on.
     *
     * @param name the knowledge base's name; case is ignored
     * @return the colour
     */
    static Color color(String name) {
        CRC32 checksum = new CRC32();
        checksum.update(name.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
        return Color.hsb(checksum.getValue() % 360, 0.6, 0.62);
    }
}
