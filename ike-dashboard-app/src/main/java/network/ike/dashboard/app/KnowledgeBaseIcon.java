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

import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.Optional;

/**
 * What tells one knowledge base from another at a glance: the icon its knowledge-base descriptor names,
 * which every managed store carries (the build draws a monogram for a project that keeps none). The
 * dashboard draws no icon of its own.
 */
final class KnowledgeBaseIcon {

    private KnowledgeBaseIcon() {
    }

    /**
     * The icon, at a size.
     *
     * @param image the icon
     * @param size  the width and height in pixels
     * @return the icon in a box of that size; the box alone when the image could not be read
     */
    static Node of(Optional<Image> image, double size) {
        StackPane box = new StackPane();
        image.filter(loaded -> !loaded.isError()).ifPresent(loaded -> {
            ImageView view = new ImageView(loaded);
            view.setFitWidth(size);
            view.setFitHeight(size);
            view.setPreserveRatio(true);
            view.setSmooth(true);
            box.getChildren().add(view);
        });
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
}
