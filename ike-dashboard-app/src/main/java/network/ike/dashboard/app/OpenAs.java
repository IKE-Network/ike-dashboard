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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Who a Komet is opened as: the person in the file of {@link Authors}, with the assistant they direct or
 * with no assistant. It is the last part of the sentence a knowledge base's Open row reads as (KEC,
 * 2026-11-22: "[Open] in [latest komet] as [kec assisted by kec-claude]"; IKE-Network/ike-issues#1399).
 *
 * <p>Settled by KEC on 2026-11-22:
 * <ul>
 * <li>The choices are the two the file gives: the person with the assistant, and the person with no
 *     assistant. A file that names no assistant gives one. With no file there is no choice, and Komet's
 *     author screen offers the users it offers now.</li>
 * <li>Komet is given git's four variables naming the person, as before, and {@code --user} with the
 *     person's name, so its author screen has the person selected in a knowledge base that has them as a
 *     user. The screen still opens: no password is passed.</li>
 * <li>No assistant reaches Komet as a variable beside git's, {@value Authors#ASSISTANT_VARIABLE} set to
 *     {@value Authors#NO_ASSISTANT}. Komet still reads the file itself and leaves the assistant out, and
 *     the tools Komet starts inherit the variable.</li>
 * <li>The choice is kept for each folder ({@link OpenAsChoices}), and a request from a link opens as its
 *     folder's choice.</li>
 * </ul>
 *
 * @param file      what the file of authors gives
 * @param assistant whether the assistant the file names is kept or left out
 */
public record OpenAs(Authors.Reading file, Assistant assistant) {

    /** Whether the assistant the file names opens with the person. */
    public enum Assistant {
        /** The assistant the file names, when it names one. */
        NAMED,
        /** No assistant, whatever the file names. */
        NONE
    }

    /**
     * The first Komet version that takes the choice: it reads {@value Authors#ASSISTANT_VARIABLE}, and it
     * says quietly that {@code --user} names no user of a knowledge base, where an older Komet writes a
     * warning in its log at each launch. An older Komet is started as before, with git's variables alone.
     *
     * <p>This is the first build after that Komet's change landed, 2026-11-22 20:27 UTC (komet-desktop
     * a4b67d1, komet-stamped-document-plugin 533a961), as {@code LauncherChoice.LOAD_CHANGE_SETS_SINCE}
     * was set. A build made after that time from a main line that does not have the change yet is taken
     * for one that takes the choice, and does not.
     */
    static final String SINCE = "26.1122.2027";

    /** Komet's option that names the author to select ({@code LaunchOptions.Option.USER} in komet-desktop). */
    static final String USER_ARGUMENT = "user";

    /**
     * Checks that both are given.
     *
     * @param file      what the file of authors gives
     * @param assistant the choice
     */
    public OpenAs {
        if (file == null || assistant == null) {
            throw new IllegalArgumentException("The file's reading and the choice of assistant are both needed");
        }
    }

    /**
     * The choices a file of authors gives, the first being the one a folder starts with: the person with
     * the assistant, then the person with none; the person alone, when the file names no assistant; and
     * none, when there is no file or it cannot be used.
     *
     * @param file what the file of authors gives
     * @return the choices, in the order they are listed
     */
    public static List<OpenAs> choices(Authors.Reading file) {
        return file.found().map(authors -> authors.assistant().isPresent()
                        ? List.of(new OpenAs(file, Assistant.NAMED), new OpenAs(file, Assistant.NONE))
                        : List.of(new OpenAs(file, Assistant.NAMED)))
                .orElse(List.of());
    }

    /**
     * Who a Komet is opened as, for a file of authors and the choice kept for a folder. A file that names
     * no assistant has nothing to leave out, so the choice kept is passed over.
     *
     * @param file what the file of authors gives
     * @param kept the choice kept for the folder
     * @return who Komet is opened as
     */
    public static OpenAs of(Authors.Reading file, Assistant kept) {
        return choices(file).stream().filter(choice -> choice.assistant() == kept).findFirst()
                .orElse(new OpenAs(file, Assistant.NAMED));
    }

    /**
     * The authors Komet is opened as: the file's, with the assistant left out when that was chosen.
     *
     * @return the reading Komet is started with
     */
    public Authors.Reading authors() {
        return assistant == Assistant.NONE ? file.withoutAssistant() : file;
    }

    /**
     * Whether the person chose to leave out an assistant the file names.
     *
     * @return {@code true} when Komet must be told to pass the file's assistant over
     */
    public boolean leavesAssistantOut() {
        return assistant == Assistant.NONE && file.found().flatMap(Authors::assistant).isPresent();
    }

    /**
     * The choice as the Open row words it, after "as".
     *
     * @return the person, then their assistant or that there is none; empty when the file gives no authors
     */
    public Optional<String> label() {
        return file.found().map(authors -> authors.person().name() + authors.assistant()
                .map(named -> assistant == Assistant.NAMED ? ", assisted by " + named.name() : ", with no assistant")
                .orElse(""));
    }

    /**
     * What the choice does, in sentences, with each author as git shows one.
     *
     * @return the sentences; empty when the file gives no authors
     */
    public Optional<String> detail() {
        return file.found().map(authors -> {
            String person = authors.person().ident();
            String opens = "Komet opens as " + person + ". ";
            if (authors.assistant().isEmpty()) {
                return opens + "Every commit names them. The file names no assistant.";
            }
            String named = authors.assistant().get().ident();
            return assistant == Assistant.NAMED
                    ? opens + "Their own work is committed as them. Work the assistant does is committed as " + named
                            + ", with " + authors.person().name() + " as committer."
                    : opens + "Every commit names them, the work of " + authors.assistant().get().name() + " included.";
        });
    }

    /**
     * Whether a Komet takes the choice.
     *
     * @param komet the Komet
     * @return {@code true} from {@value #SINCE} on
     */
    public static boolean taken(KometInstallation komet) {
        return KometInstallations.compareVersions(komet.version(), SINCE) >= 0;
    }

    /**
     * The variables a launch sets for the choice: git's four naming the person, for every Komet, and
     * {@value Authors#ASSISTANT_VARIABLE} when the person left out an assistant the file names. An older
     * Komet passes that variable over, and the tools it starts still read it.
     *
     * @return the variables, git's first; none when the file gives no authors
     */
    public Map<String, String> environment() {
        Map<String, String> variables = new LinkedHashMap<>(file.gitEnvironment());
        if (leavesAssistantOut()) {
            variables.put(Authors.ASSISTANT_VARIABLE, Authors.NO_ASSISTANT);
        }
        return variables;
    }

    /**
     * The arguments a launch adds for the choice: {@code --user} with the person's name, for a Komet that
     * {@link #taken takes the choice}. No password goes with it, so Komet's author screen opens with the
     * person selected when the knowledge base has them as a user, and as it always has when it does not.
     *
     * <p>Windows' {@code launchKomet.bat} is run by the command interpreter, which reads some characters of
     * an argument as its own, and Java refuses to start a batch file with a quotation mark in an argument.
     * So there the name is passed only when it {@link #travelsThroughBatch travels unchanged}; a name that
     * does not is left out, and the author screen opens as it always has.
     *
     * @param komet the Komet being started
     * @return the arguments; none for an older Komet, or when the file gives no authors
     */
    public List<String> arguments(KometInstallation komet) {
        return file.found().filter(_ -> taken(komet)).map(authors -> authors.person().name())
                .filter(name -> !batchLauncher(komet) || travelsThroughBatch(name))
                .map(name -> List.of("--" + USER_ARGUMENT + "=" + name))
                .orElse(List.of());
    }

    private static boolean batchLauncher(KometInstallation komet) {
        return komet.executable().getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".bat");
    }

    /**
     * Whether a name reaches a Windows batch file as it is written: letters, digits, spaces, and the full
     * stop, comma, apostrophe and hyphen of a name. Anything else, a quotation mark or a percent sign for
     * one, may be read by the command interpreter as its own.
     *
     * @param name a person's name
     * @return {@code true} when every character is one of those
     */
    static boolean travelsThroughBatch(String name) {
        return name.chars().allMatch(c -> Character.isLetterOrDigit(c) || " .,'-".indexOf(c) >= 0);
    }
}
