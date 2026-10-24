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

/**
 * One installed Komet: a macOS application bundle whose bundle identifier is Komet's.
 *
 * @param name       the bundle's display name, such as {@code Komet Desktop 2026-10-16 346s}
 * @param version    its version, such as {@code 26.1016.346}
 * @param bundle     the {@code .app} folder
 * @param executable the launcher inside it, {@code Contents/MacOS/<executable>}
 */
public record KometInstallation(String name, String version, Path bundle, Path executable) {

    /** The bundle identifier every Komet build carries. */
    public static final String BUNDLE_IDENTIFIER = "dev.ikm.komet.desktop";
}
