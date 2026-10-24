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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Starts a Komet on a knowledge base as an independent process, through the option Komet already
 * takes ({@code --kb=<path>}, {@code LaunchOptions} in komet-desktop). Komet asks for the user and
 * password itself. Its output goes to a log of its own, so the dashboard can quit while Komet runs.
 */
public final class KometLauncher {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private KometLauncher() {
    }

    /**
     * The command that opens a knowledge base in a Komet.
     *
     * @param komet         the Komet to start
     * @param knowledgeBase the knowledge base to open
     * @return the command and its arguments
     */
    public static List<String> command(KometInstallation komet, KnowledgeBase knowledgeBase) {
        return List.of(komet.executable().toString(), "--kb=" + knowledgeBase.path());
    }

    /**
     * Starts the Komet.
     *
     * @param komet         the Komet to start
     * @param knowledgeBase the knowledge base to open
     * @param logFolder     where its log goes
     * @return the log file the process writes to
     * @throws IOException if the process cannot be started
     */
    public static Path launch(KometInstallation komet, KnowledgeBase knowledgeBase, Path logFolder) throws IOException {
        Files.createDirectories(logFolder);
        Path log = logFolder.resolve("komet-" + knowledgeBase.name() + "-" + LocalDateTime.now().format(STAMP) + ".log");
        new ProcessBuilder(command(komet, knowledgeBase))
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        return log;
    }
}
