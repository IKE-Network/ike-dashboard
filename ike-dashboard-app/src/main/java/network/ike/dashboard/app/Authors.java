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
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

/**
 * The two authors the dashboard starts Komet as: the person using it, and the assistant the person
 * directs (IKE-Network/ike-issues#1399). The person is responsible for every commit, the assistant's
 * included, and commits as their identity address; the assistant is an author of its own, apart from
 * the person and from every other person's assistant.
 *
 * <p>Until the registry of people runs, the two are kept in a file of the dashboard's own,
 * {@code ~/.ike/authors.properties} (decision 1 of the issue), which other tools read as well. Its
 * keys are those of {@link Key}; the assistant may be left out, and keys the dashboard does not know
 * are passed over, so a file the registry writes later can say more. With invented values:
 *
 * <pre>
 * person.name=Ada Lovelace
 * person.email=ada@komet.ike.network
 * assistant.name=Ada's Claude
 * assistant.email=ada-claude@komet.ike.network
 * </pre>
 *
 * <p>The file is read as UTF-8 each time it is needed, so a change to it takes effect at the next
 * launch or commit, with no restart.
 *
 * @param person    the person, who commits and is responsible
 * @param assistant the assistant the person directs, when the file names one
 */
public record Authors(Author person, Optional<Author> assistant) {

    /** Git's variable for the name of a commit's author. */
    static final String AUTHOR_NAME = "GIT_AUTHOR_NAME";
    /** Git's variable for the email of a commit's author. */
    static final String AUTHOR_EMAIL = "GIT_AUTHOR_EMAIL";
    /** Git's variable for the name of a commit's committer. */
    static final String COMMITTER_NAME = "GIT_COMMITTER_NAME";
    /** Git's variable for the email of a commit's committer. */
    static final String COMMITTER_EMAIL = "GIT_COMMITTER_EMAIL";

    /**
     * The variable that leaves the assistant out for a process and the tools it starts (KEC, 2026-11-22):
     * set to {@value #NO_ASSISTANT}, the file's assistant is passed over, and the assistant's work is
     * committed as the person. The dashboard sets it on a Komet opened with no assistant ({@link OpenAs});
     * Komet reads it beside the file ({@code CommitAuthors} in komet-stamped-document-plugin), and so does
     * {@link #current}.
     */
    static final String ASSISTANT_VARIABLE = "IKE_ASSISTANT";
    /** The value of {@value #ASSISTANT_VARIABLE} that leaves the assistant out. Any other value says nothing. */
    static final String NO_ASSISTANT = "none";

    /**
     * Checks that both are given.
     *
     * @param person    the person
     * @param assistant the assistant, or empty
     */
    public Authors {
        if (person == null || assistant == null) {
            throw new IllegalArgumentException("The person, and the assistant or none, are both needed");
        }
    }

    /**
     * One author: a name and an email, as git writes them on a commit.
     *
     * @param name  the name, without the marks git puts round an email
     * @param email the email, an identity address under {@code komet.ike.network} by the design
     */
    public record Author(String name, String email) {

        /**
         * Checks the two as git would: a name and an email that are not empty, and neither holding
         * {@code <}, {@code >} or a line break, which git refuses; an email with one {@code @} between
         * two parts, and no space.
         *
         * @param name  the name
         * @param email the email
         */
        public Author {
            name = name == null ? "" : name.strip();
            email = email == null ? "" : email.strip();
            if (name.isEmpty()) {
                throw new IllegalArgumentException("An author's name is empty");
            }
            if (!plain(name)) {
                throw new IllegalArgumentException("The name " + name + " holds <, > or a line break, which git refuses");
            }
            int at = email.indexOf('@');
            if (email.isEmpty() || !plain(email) || email.chars().anyMatch(Character::isWhitespace)
                    || at <= 0 || at != email.lastIndexOf('@') || at == email.length() - 1) {
                throw new IllegalArgumentException("The email of " + name + ", " + (email.isEmpty() ? "an empty one" : email)
                        + ", is not an address of the form name@domain");
            }
        }

        private static boolean plain(String text) {
            return text.chars().noneMatch(c -> c == '<' || c == '>' || c == '\n' || c == '\r');
        }

        /**
         * The author as git shows one: the name, then the email between angle brackets.
         *
         * @return {@code Name <email>}
         */
        public String ident() {
            return name + " <" + email + ">";
        }
    }

    /** The keys of {@code authors.properties}. */
    public enum Key {
        /** The person's name. */
        PERSON_NAME("person.name"),
        /** The person's identity address. */
        PERSON_EMAIL("person.email"),
        /** The assistant's name. */
        ASSISTANT_NAME("assistant.name"),
        /** The assistant's identity address. */
        ASSISTANT_EMAIL("assistant.email");

        private final String stored;

        Key(String stored) {
            this.stored = stored;
        }

        /**
         * The key as the file writes it.
         *
         * @return the key
         */
        public String stored() {
            return stored;
        }
    }

    /** What reading the file found: the authors, no file, or a file that cannot be used. */
    public sealed interface Reading permits Found, Absent, Unreadable {

        /**
         * The file that was read.
         *
         * @return the file
         */
        Path file();

        /**
         * The authors, when the file gives them.
         *
         * @return the authors, or empty when there is no file or it cannot be used
         */
        default Optional<Authors> found() {
            return switch (this) {
                case Found found -> Optional.of(found.authors());
                case Absent _, Unreadable _ -> Optional.empty();
            };
        }

        /**
         * The variables that make git write the person as a commit's author and committer: none when
         * there are no authors, so git then writes the name and email of its own configuration.
         *
         * @return the variables to add to a process's environment
         */
        default Map<String, String> gitEnvironment() {
            return found().map(Authors::gitEnvironment).orElse(Map.of());
        }

        /**
         * The same reading with the assistant left out: the person alone, when the file gives authors, and
         * otherwise the reading as it is.
         *
         * @return the reading, naming no assistant
         */
        default Reading withoutAssistant() {
            return switch (this) {
                case Found(Path file, Authors authors) -> new Found(file, authors.withoutAssistant());
                case Absent _, Unreadable _ -> this;
            };
        }
    }

    /**
     * The file gives the authors.
     *
     * @param file    the file
     * @param authors the authors
     */
    public record Found(Path file, Authors authors) implements Reading {
    }

    /**
     * There is no file: Komet starts, and the dashboard commits, under the name in git's configuration,
     * as before the file existed.
     *
     * @param file the file looked for
     */
    public record Absent(Path file) implements Reading {
    }

    /**
     * The file is there and cannot be used. Nothing is taken from it, so git's own name is used, and
     * the dashboard says why.
     *
     * @param file    the file
     * @param problem what is wrong, in a sentence without its closing full stop
     */
    public record Unreadable(Path file, String problem) implements Reading {
    }

    /**
     * The variables that make git write the person as both author and committer of a commit. Git
     * prefers them to its configuration, and JGit reads them too.
     *
     * @return the four variables, in a fixed order
     */
    public Map<String, String> gitEnvironment() {
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put(AUTHOR_NAME, person.name());
        environment.put(AUTHOR_EMAIL, person.email());
        environment.put(COMMITTER_NAME, person.name());
        environment.put(COMMITTER_EMAIL, person.email());
        return environment;
    }

    /**
     * The person alone, with no assistant.
     *
     * @return the same person, and no assistant
     */
    public Authors withoutAssistant() {
        return assistant.isEmpty() ? this : new Authors(person, Optional.empty());
    }

    /**
     * Where the file is for a user.
     *
     * @param home the user's home folder
     * @return {@code ~/.ike/authors.properties}
     */
    public static Path file(Path home) {
        return home.resolve(".ike").resolve("authors.properties");
    }

    /**
     * Reads the file of the user running the dashboard, as this process was started: with
     * {@value #ASSISTANT_VARIABLE} set to {@value #NO_ASSISTANT} in its environment, the assistant is left
     * out.
     *
     * @return what the file gives
     */
    public static Reading current() {
        return current(Path.of(System.getProperty("user.home")), System.getenv());
    }

    /**
     * Reads a user's file as a process with an environment sees it.
     *
     * @param home        the user's home folder
     * @param environment the process's environment
     * @return what the file gives, with the assistant left out when the environment says so
     */
    static Reading current(Path home, Map<String, String> environment) {
        Reading reading = read(file(home));
        return leavesAssistantOut(environment) ? reading.withoutAssistant() : reading;
    }

    /**
     * Whether an environment leaves the assistant out.
     *
     * @param environment a process's environment
     * @return {@code true} when {@value #ASSISTANT_VARIABLE} is {@value #NO_ASSISTANT}, in any case
     */
    static boolean leavesAssistantOut(Map<String, String> environment) {
        String value = environment.get(ASSISTANT_VARIABLE);
        return value != null && value.strip().equalsIgnoreCase(NO_ASSISTANT);
    }

    /**
     * Reads a file of authors.
     *
     * @param file the file
     * @return the authors, or why there are none
     */
    public static Reading read(Path file) {
        if (!Files.exists(file)) {
            return new Absent(file);
        }
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException | IllegalArgumentException e) {
            return new Unreadable(file, "it cannot be read: " + e.getMessage());
        }
        try {
            return new Found(file, of(properties));
        } catch (IllegalArgumentException e) {
            return new Unreadable(file, e.getMessage());
        }
    }

    /**
     * The authors a file's properties name.
     *
     * @param properties the properties
     * @return the authors
     * @throws IllegalArgumentException if the person is not named in full, the assistant is named in
     *                                  part, or a name or an email is not one git takes
     */
    static Authors of(Properties properties) {
        Optional<String> personName = value(properties, Key.PERSON_NAME);
        Optional<String> personEmail = value(properties, Key.PERSON_EMAIL);
        if (personName.isEmpty() || personEmail.isEmpty()) {
            throw new IllegalArgumentException("it names no person: " + Key.PERSON_NAME.stored() + " and "
                    + Key.PERSON_EMAIL.stored() + " are both needed");
        }
        Optional<String> assistantName = value(properties, Key.ASSISTANT_NAME);
        Optional<String> assistantEmail = value(properties, Key.ASSISTANT_EMAIL);
        if (assistantName.isPresent() != assistantEmail.isPresent()) {
            throw new IllegalArgumentException(Key.ASSISTANT_NAME.stored() + " and " + Key.ASSISTANT_EMAIL.stored()
                    + " go together, and it has only " + (assistantName.isPresent() ? Key.ASSISTANT_NAME : Key.ASSISTANT_EMAIL).stored());
        }
        return new Authors(new Author(personName.get(), personEmail.get()),
                assistantName.map(name -> new Author(name, assistantEmail.get())));
    }

    private static Optional<String> value(Properties properties, Key key) {
        return Optional.ofNullable(properties.getProperty(key.stored())).map(String::strip).filter(text -> !text.isEmpty());
    }
}
