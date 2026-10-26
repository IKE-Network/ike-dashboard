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

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/**
 * The operating system the dashboard runs on, for what differs by it: where things are installed and
 * logged, how a zip is unpacked, what checks an application, and how the dashboard swaps itself.
 * The dashboard ships for macOS and Windows; anything else is treated as Linux.
 */
public enum OperatingSystem {

    /** macOS. */
    MAC,
    /** Windows. */
    WINDOWS,
    /** Linux, and anything else. */
    LINUX;

    /**
     * The platform this JVM runs on.
     *
     * @return the platform
     */
    public static OperatingSystem current() {
        return of(System.getProperty("os.name", ""));
    }

    /**
     * The platform an {@code os.name} names.
     *
     * @param osName such as {@code Mac OS X} or {@code Windows 11}
     * @return the platform
     */
    static OperatingSystem of(String osName) {
        String name = osName.toLowerCase(Locale.ROOT);
        return name.contains("mac") ? MAC : name.contains("win") ? WINDOWS : LINUX;
    }

    /**
     * The folder a Windows user's applications and their data live in: {@code %LOCALAPPDATA%}, or
     * {@code AppData\Local} in the home folder when the variable is not set.
     *
     * @param home        the user's home folder
     * @param environment the environment
     * @return the folder
     */
    static Path localAppData(Path home, Map<String, String> environment) {
        String set = environment.get("LOCALAPPDATA");
        return set != null && !set.isBlank() ? Path.of(set) : home.resolve("AppData").resolve("Local");
    }
}
