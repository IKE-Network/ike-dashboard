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
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * A jpackage application image outside macOS, read from the launcher configuration jpackage writes
 * beside its launcher: on Windows {@code <image>\app\<name>.cfg} beside {@code <image>\<name>.exe}, on
 * Linux {@code <image>/lib/app/<name>.cfg} beside {@code <image>/bin/<name>}. The configuration names
 * the main module (or class) and carries the application's version as the
 * {@code -Djpackage.app-version} Java option. What a macOS bundle's {@code Info.plist} says, this says
 * on Windows.
 *
 * @param folder   the image's folder
 * @param name     the launcher's name, such as {@code IKE Dashboard}
 * @param main     the main module and class, such as {@code network.ike.dashboard.app/network.ike.dashboard.app.DashboardApp},
 *                 or the main class when the image runs from the class path
 * @param version  the application's version
 * @param launcher the launcher
 */
public record AppImage(Path folder, String name, String main, String version, Path launcher) {

    private static final String VERSION_OPTION = "-Djpackage.app-version=";

    /**
     * Whether the image runs a main module or class of a package, such as Komet's
     * {@code dev.ikm.komet.desktop}.
     *
     * @param pkg the package
     * @return {@code true} when its main is in that package
     */
    public boolean runs(String pkg) {
        return main.startsWith(pkg + "/") || main.startsWith(pkg + ".");
    }

    /**
     * Reads a folder as an application image.
     *
     * @param folder the folder
     * @return the image, or empty when the folder is not one or cannot be read
     */
    public static Optional<AppImage> read(Path folder) {
        for (Layout layout : Layout.values()) {
            Path app = folder.resolve(layout.configurations);
            if (!Files.isDirectory(app)) {
                continue;
            }
            List<Path> configurations;
            try (Stream<Path> entries = Files.list(app)) {
                configurations = entries.filter(path -> path.getFileName().toString().endsWith(".cfg")).sorted().toList();
            } catch (IOException e) {
                return Optional.empty();
            }
            for (Path configuration : configurations) {
                String file = configuration.getFileName().toString();
                String name = file.substring(0, file.length() - ".cfg".length());
                Path launcher = layout.launcher(folder, name);
                if (Files.isRegularFile(launcher)) {
                    return read(folder, name, configuration, launcher);
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<AppImage> read(Path folder, String name, Path configuration, Path launcher) {
        List<String> lines;
        try {
            lines = Files.readAllLines(configuration);
        } catch (IOException e) {
            return Optional.empty();
        }
        String main = null;
        String version = null;
        for (String line : lines) {
            String trimmed = line.strip();
            if (trimmed.startsWith("app.mainmodule=")) {
                main = trimmed.substring("app.mainmodule=".length());
            } else if (trimmed.startsWith("app.mainclass=") && main == null) {
                main = trimmed.substring("app.mainclass=".length());
            } else if (trimmed.startsWith("java-options=") && trimmed.contains(VERSION_OPTION)) {
                version = trimmed.substring(trimmed.indexOf(VERSION_OPTION) + VERSION_OPTION.length()).strip();
            }
        }
        return main == null || version == null || version.isEmpty() ? Optional.empty()
                : Optional.of(new AppImage(folder, name, main, version, launcher));
    }

    /** Where each platform's image keeps its launcher configurations and its launchers. */
    private enum Layout {
        WINDOWS("app") {
            @Override
            Path launcher(Path folder, String name) {
                return folder.resolve(name + ".exe");
            }
        },
        LINUX("lib/app") {
            @Override
            Path launcher(Path folder, String name) {
                return folder.resolve("bin").resolve(name);
            }
        };

        private final String configurations;

        Layout(String configurations) {
            this.configurations = configurations;
        }

        abstract Path launcher(Path folder, String name);
    }
}
