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

import javafx.application.Platform;
import javafx.scene.image.Image;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The window carries the IKE icon at every size Windows and Linux choose from, each drawn, not
 * missing or empty. Loaded headless with Monocle.
 */
class WindowIconTest {

    @BeforeAll
    static void startHeadless() throws Exception {
        System.setProperty("glass.platform", "Monocle");
        System.setProperty("monocle.platform", "Headless");
        System.setProperty("prism.order", "sw");
        CompletableFuture<Void> started = new CompletableFuture<>();
        try {
            Platform.startup(() -> started.complete(null));
        } catch (IllegalStateException alreadyStarted) {
            started.complete(null);
        }
        started.get(30, TimeUnit.SECONDS);
    }

    @Test
    void theWindowCarriesTheIconAtEachSize() {
        List<Image> icons = DashboardApp.icons();

        assertThat(icons).extracting(Image::getWidth).containsExactly(16.0, 32.0, 48.0, 64.0, 128.0, 256.0);
        assertThat(icons).allSatisfy(icon -> {
            assertThat(icon.isError()).isFalse();
            assertThat(icon.getHeight()).isEqualTo(icon.getWidth());
        });
        // The middle of the tile is Midnight (#282040), the brand's deep Horizon shade.
        Image large = icons.getLast();
        assertThat(large.getPixelReader().getArgb(128, 40)).isEqualTo(0xFF282040);
    }
}
