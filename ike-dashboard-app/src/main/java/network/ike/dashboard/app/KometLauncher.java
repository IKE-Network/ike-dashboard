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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Starts a Komet on a knowledge base as an independent process, through the option Komet already
 * takes ({@code --kb=<path>}, {@code LaunchOptions} in komet-desktop). Komet asks for the user and
 * password itself. Its output goes to a log of its own, so the dashboard can quit while Komet runs.
 * Its heap is the {@link HeapSizing} plan's, through the variables a Komet image's {@code launchKomet}
 * takes ({@code KOMET_MAX_HEAP}, {@code KOMET_JAVA_OPTS}); a Komet whose launcher takes none starts at
 * the heap it was built with. Windows' {@code launchKomet.bat} cannot default an unset variable inline,
 * so it takes only {@code KOMET_JAVA_OPTS}, after its own {@code -Xmx}: there the heap goes in as
 * {@code -Xmx}, which the JVM takes the last of ({@link #launcherEnvironment}).
 */
public final class KometLauncher {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /**
     * Komet's option that loads a store's unregistered change sets with no window
     * ({@code LaunchOptions} in komet-desktop; decision 20 of the {@code dev-ike-dashboard} topic).
     */
    static final String LOAD_ARGUMENT = "load-change-sets";
    /** The line Komet prints before the report ({@code ChangeSetLoadCommand.REPORT_START}). */
    static final String REPORT_START = "----- change-set load report -----";
    /** The line Komet prints after the report. */
    static final String REPORT_END = "----- end of report -----";

    private KometLauncher() {
    }

    /**
     * Whether a Komet's launcher takes its heap from {@value HeapSizing#MAX_HEAP_VARIABLE}: a jlink
     * image whose {@code bin/launchKomet} names the variable, which images built after
     * IKE-Network/ike-issues#1324 do. An application bundle's launcher fixes its heap at build.
     *
     * @param komet the Komet
     * @return {@code true} when the launch can size its heap
     */
    public static boolean takesHeap(KometInstallation komet) {
        Path launcher = komet.executable();
        String name = launcher.getFileName().toString();
        if (!(name.equals("launchKomet") || windowsLauncher(launcher)) || !Files.isRegularFile(launcher)) {
            return false;
        }
        try {
            return Files.readString(launcher).contains(windowsLauncher(launcher)
                    ? HeapSizing.JAVA_OPTS_VARIABLE : HeapSizing.MAX_HEAP_VARIABLE);
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * The variables a launch sets, for the launcher it runs: the plan's as they are, except that
     * Windows' {@code launchKomet.bat} takes the heap as {@code -Xmx} at the head of
     * {@value HeapSizing#JAVA_OPTS_VARIABLE}.
     *
     * @param komet       the Komet
     * @param environment the plan's variables ({@link HeapSizing.Plan#environment})
     * @return the variables to set
     */
    static Map<String, String> launcherEnvironment(KometInstallation komet, Map<String, String> environment) {
        String heap = environment.get(HeapSizing.MAX_HEAP_VARIABLE);
        if (!windowsLauncher(komet.executable()) || heap == null) {
            return environment;
        }
        Map<String, String> translated = new LinkedHashMap<>(environment);
        translated.remove(HeapSizing.MAX_HEAP_VARIABLE);
        String options = environment.get(HeapSizing.JAVA_OPTS_VARIABLE);
        translated.put(HeapSizing.JAVA_OPTS_VARIABLE, "-Xmx" + heap + (options == null || options.isBlank() ? "" : " " + options));
        return translated;
    }

    private static boolean windowsLauncher(Path launcher) {
        return launcher.getFileName().toString().equalsIgnoreCase("launchKomet.bat");
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
     * The command that loads a knowledge base's unregistered change sets in a Komet with no window:
     * {@code ChangeSetLoadCommand} in komet-desktop. The same load every open of the store runs, so a
     * clean run says the store opens and holds every change set carried into it.
     *
     * @param komet         the Komet, one {@link LauncherChoice#loadsChangeSets} accepts
     * @param knowledgeBase the knowledge base
     * @return the command and its arguments
     */
    public static List<String> loadCommand(KometInstallation komet, KnowledgeBase knowledgeBase) {
        return List.of(komet.executable().toString(), "--kb=" + knowledgeBase.path(), "--" + LOAD_ARGUMENT);
    }

    /**
     * What a run of Komet with no window did.
     *
     * @param exitStatus the Komet's exit status: 0 clean, 1 a change set refused or failed, 2 nothing
     *                   done
     * @param report     the report Komet printed, Markdown; empty when it printed none
     * @param log        the run's whole output
     */
    public record HeadlessRun(int exitStatus, String report, Path log) {

        /**
         * Whether every change set is in the store.
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
     * Loads a knowledge base's unregistered change sets in a Komet with no window, and waits for it:
     * the validation of an upgrade ({@link StoreUpgrade}). The store must not be open in another
     * Komet; Komet refuses, and says so, when it is.
     *
     * @param komet         the Komet
     * @param knowledgeBase the knowledge base
     * @param environment   the variables sizing its heap ({@link HeapSizing.Plan#environment})
     * @param logFolder     where the run's output goes
     * @return what the run did
     * @throws IOException          if Komet cannot be started
     * @throws InterruptedException if the wait is interrupted
     */
    public static HeadlessRun loadChangeSets(KometInstallation komet, KnowledgeBase knowledgeBase,
                                             Map<String, String> environment, Path logFolder)
            throws IOException, InterruptedException {
        Files.createDirectories(logFolder);
        Path log = logFolder.resolve("komet-load-" + knowledgeBase.name() + "-" + LocalDateTime.now().format(STAMP) + ".log");
        ProcessBuilder builder = new ProcessBuilder(loadCommand(komet, knowledgeBase))
                .redirectErrorStream(true)
                .redirectOutput(log.toFile());
        builder.environment().putAll(launcherEnvironment(komet, environment));
        Process process = builder.start();
        int status = process.waitFor();
        return new HeadlessRun(status, report(Files.readString(log)), log);
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
     * @param environment   the variables sizing its heap ({@link HeapSizing.Plan#environment})
     * @param logFolder     where its log goes
     * @return the log file the process writes to
     * @throws IOException if the process cannot be started
     */
    public static Path launch(KometInstallation komet, KnowledgeBase knowledgeBase, Map<String, String> environment,
                              Path logFolder) throws IOException {
        Files.createDirectories(logFolder);
        Path log = logFolder.resolve("komet-" + knowledgeBase.name() + "-" + LocalDateTime.now().format(STAMP) + ".log");
        ProcessBuilder builder = new ProcessBuilder(command(komet, knowledgeBase))
                .redirectErrorStream(true)
                .redirectOutput(log.toFile());
        builder.environment().putAll(launcherEnvironment(komet, environment));
        builder.start();
        return log;
    }
}
