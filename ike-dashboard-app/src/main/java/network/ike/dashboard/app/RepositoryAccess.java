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

import network.ike.dashboard.download.Credentials;
import network.ike.dashboard.download.SettingsXmlReader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Nexus repository the dashboard watches, and the person's login for it.
 *
 * <p>Knowledge bases live in the {@code ike-restricted} group, which answers only a person who is
 * signed in. The login is the {@code <server>} of that id in {@code ~/.m2/settings.xml}, as Maven
 * itself reads it; {@code ${env.NAME}} placeholders, the form IKE machines use, are filled from the
 * environment. It is never kept in the dashboard's preferences.
 *
 * @param url         the repository's base URL
 * @param serverId    the {@code settings.xml} server id its login is under
 * @param credentials the login, when {@code settings.xml} has one
 */
public record RepositoryAccess(String url, String serverId, Optional<Credentials> credentials) {

    /** The group knowledge bases are read through. */
    public static final String RESTRICTED_URL = "https://nexus.tinkar.org/repository/ike-restricted/";

    /** The {@code settings.xml} server id of its login. */
    public static final String RESTRICTED_SERVER_ID = "ike-restricted";

    private static final Pattern ENV = Pattern.compile("\\$\\{env\\.([A-Za-z_][A-Za-z0-9_]*)}");

    /**
     * Reads the login for a repository from a {@code settings.xml}.
     *
     * @param url         the repository's base URL
     * @param serverId    the server id
     * @param settingsXml the {@code settings.xml}
     * @param environment the environment the placeholders are filled from
     * @return the access, without credentials when the file, the server or a placeholder's value is missing
     * @throws IOException if the file exists but cannot be read
     */
    public static RepositoryAccess fromSettings(String url, String serverId, Path settingsXml,
                                                Map<String, String> environment) throws IOException {
        if (!Files.isRegularFile(settingsXml)) {
            return new RepositoryAccess(url, serverId, Optional.empty());
        }
        UnaryOperator<String> fill = value -> interpolate(value, environment);
        Optional<Credentials> credentials = SettingsXmlReader.read(settingsXml).servers().stream()
                .filter(server -> serverId.equals(server.id()))
                .findFirst()
                .flatMap(server -> {
                    String username = server.username() == null ? null : fill.apply(server.username());
                    String password = server.plaintextPassword() == null ? null : fill.apply(server.plaintextPassword());
                    if (username == null || password == null) {
                        return Optional.empty();
                    }
                    return Optional.of(new Credentials(username, password.toCharArray()));
                });
        return new RepositoryAccess(url, serverId, credentials);
    }

    /**
     * Fills {@code ${env.NAME}} placeholders.
     *
     * @param value       the value
     * @param environment the environment
     * @return the value filled, or {@code null} when a placeholder has no value
     */
    static String interpolate(String value, Map<String, String> environment) {
        Matcher matcher = ENV.matcher(value);
        StringBuilder filled = new StringBuilder();
        while (matcher.find()) {
            String replacement = environment.get(matcher.group(1));
            if (replacement == null) {
                return null;
            }
            matcher.appendReplacement(filled, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(filled);
        return filled.toString();
    }
}
