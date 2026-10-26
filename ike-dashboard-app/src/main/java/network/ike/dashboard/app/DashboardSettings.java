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
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.prefs.Preferences;
import java.util.stream.Collectors;

/**
 * The dashboard's settings, kept the way Komet keeps preferences, through {@code java.util.prefs}
 * directly (decision 6 of the {@code dev-ike-dashboard} topic), under the user node
 * {@code network/ike/dashboard}. Credentials are never kept here.
 *
 * @param solor           the knowledge-base folder
 * @param installLocation where the dashboard installs Komet versions
 * @param repositoryUrl   the Nexus repository knowledge bases are read through
 * @param serverId        the {@code settings.xml} server id of its login
 * @param localRepository the local Maven repository downloads are cached in
 * @param logFolder       where the dashboard and the Komets it starts log
 * @param collections     the collections whose knowledge bases are kept at hand, downloaded or not
 *                        (decision 17): {@value #DEFAULT_COLLECTIONS} unless the person names others
 */
public record DashboardSettings(Path solor, Path installLocation, String repositoryUrl, String serverId,
                                Path localRepository, Path logFolder, Set<String> collections) {

    /** The preferences node path. */
    public static final String NODE = "network/ike/dashboard";

    /**
     * The collections tracked when the person has named none: the SOLOR pipeline's, every knowledge base
     * the {@code solor-kb-ws} working set publishes. The preference is a comma-separated list; an empty
     * one tracks none.
     */
    public static final String DEFAULT_COLLECTIONS = "SOLOR";

    /**
     * Copies the collections.
     *
     * @param solor           the knowledge-base folder
     * @param installLocation where the dashboard installs Komet versions
     * @param repositoryUrl   the Nexus repository knowledge bases are read through
     * @param serverId        the {@code settings.xml} server id of its login
     * @param localRepository the local Maven repository downloads are cached in
     * @param logFolder       where the dashboard and the Komets it starts log
     * @param collections     the collections tracked
     */
    public DashboardSettings {
        collections = Set.copyOf(collections);
    }

    /**
     * The collections a preference names.
     *
     * @param preference a comma-separated list
     * @return the names, without blanks, in their order
     */
    static Set<String> collections(String preference) {
        return Arrays.stream(preference.split(",")).map(String::strip).filter(name -> !name.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * The settings, from the preferences where set, the defaults otherwise.
     *
     * @param preferences the dashboard's node
     * @param home        the user's home folder
     * @return the settings
     */
    public static DashboardSettings read(Preferences preferences, Path home) {
        return new DashboardSettings(
                Path.of(preferences.get("solor", home.resolve("Solor").toString())),
                Path.of(preferences.get("installLocation", home.resolve("Applications/IKE/Komet").toString())),
                preferences.get("repositoryUrl", RepositoryAccess.RESTRICTED_URL),
                preferences.get("serverId", RepositoryAccess.RESTRICTED_SERVER_ID),
                Path.of(preferences.get("localRepository", home.resolve(".m2/repository").toString())),
                Path.of(preferences.get("logFolder", home.resolve("Library/Logs/IKE Dashboard").toString())),
                collections(preferences.get("collections", DEFAULT_COLLECTIONS)));
    }

    /**
     * The settings of the user running the dashboard.
     *
     * @return the settings
     */
    public static DashboardSettings current() {
        return read(Preferences.userRoot().node(NODE), Path.of(System.getProperty("user.home")));
    }
}
