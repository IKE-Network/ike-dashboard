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

    /** Komet's option that upgrades change sets with no window ({@code LaunchOptions} in komet-desktop). */
    static final String UPGRADE_ARGUMENT = "upgrade-change-sets";
    /** The line Komet prints before the report ({@code ChangeSetUpgradeCommand.REPORT_START}). */
    static final String REPORT_START = "----- change-set upgrade report -----";
    /** The line Komet prints after the report. */
    static final String REPORT_END = "----- end of report -----";

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
     * The command that upgrades a knowledge base's change sets in a Komet with no window
     * (IKE-Network/ike-issues#1313): {@code ChangeSetUpgradeCommand} in komet-desktop.
     *
     * @param komet         the Komet, one {@link LauncherChoice#upgradesChangeSets} accepts
     * @param knowledgeBase the knowledge base whose change sets are upgraded against it
     * @return the command and its arguments
     */
    public static List<String> upgradeCommand(KometInstallation komet, KnowledgeBase knowledgeBase) {
        return List.of(komet.executable().toString(), "--kb=" + knowledgeBase.path(), "--" + UPGRADE_ARGUMENT);
    }

    /**
     * What an upgrade run did.
     *
     * @param exitStatus the Komet's exit status: 0 clean, 1 something left without a pattern or
     *                   failed, 2 nothing done
     * @param report     the report Komet printed, Markdown; empty when it printed none
     * @param log        the run's whole output
     */
    public record UpgradeRun(int exitStatus, String report, Path log) {

        /**
         * Whether every change set is upgraded or current.
         *
         * @return {@code true} for exit status 0
         */
        public boolean clean() {
            return exitStatus == 0;
        }

        /**
         * What a person reads: the report, or, when Komet printed none, why, from the end of its log.
         *
         * @return the text
         */
        public String text() {
            if (!report.isEmpty()) {
                return report;
            }
            try {
                List<String> lines = Files.readAllLines(log);
                return "Komet stopped with status " + exitStatus + " and no report. The end of its log, " + log + ":\n\n"
                        + String.join("\n", lines.subList(Math.max(0, lines.size() - 20), lines.size()));
            } catch (IOException e) {
                return "Komet stopped with status " + exitStatus + " and no report; its log " + log + " cannot be read.";
            }
        }
    }

    /**
     * Upgrades a knowledge base's change sets in a Komet with no window, and waits for it. Komet
     * archives the originals it replaces in {@code ~/ike-archive}. The store must not be open in
     * another Komet; Komet refuses, and says so, when it is.
     *
     * @param komet         the Komet
     * @param knowledgeBase the knowledge base
     * @param logFolder     where the run's output goes
     * @return what the run did
     * @throws IOException          if Komet cannot be started
     * @throws InterruptedException if the wait is interrupted
     */
    public static UpgradeRun upgradeChangeSets(KometInstallation komet, KnowledgeBase knowledgeBase, Path logFolder)
            throws IOException, InterruptedException {
        Files.createDirectories(logFolder);
        Path log = logFolder.resolve("komet-upgrade-" + knowledgeBase.name() + "-" + LocalDateTime.now().format(STAMP) + ".log");
        Process process = new ProcessBuilder(upgradeCommand(komet, knowledgeBase))
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        int status = process.waitFor();
        return new UpgradeRun(status, report(Files.readString(log)), log);
    }

    /**
     * The report among a run's output: the lines between {@value #REPORT_START} and
     * {@value #REPORT_END}.
     *
     * @param output the run's output
     * @return the report, or empty when there is none
     */
    static String report(String output) {
        int start = output.indexOf(REPORT_START);
        if (start < 0) {
            return "";
        }
        int from = output.indexOf('\n', start);
        int end = output.indexOf(REPORT_END, from < 0 ? start : from);
        if (from < 0 || end < 0) {
            return "";
        }
        return output.substring(from + 1, end).strip();
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
