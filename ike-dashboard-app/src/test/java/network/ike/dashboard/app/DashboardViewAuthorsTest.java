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

import javafx.application.Platform;
import javafx.scene.Scene;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A knowledge base's panel says who Komet is started as (IKE-Network/ike-issues#1399, step 1): the person
 * and the assistant they direct, from the file of authors; why the file cannot be used; or nothing, when
 * there is no file.
 */
class DashboardViewAuthorsTest {

    private static final Coordinate PIZZA = new Coordinate("network.ike.data", "pizza-kb", "20250627.1-SNAPSHOT");
    private static final Path FILE = Path.of("home", ".ike", "authors.properties");

    @BeforeAll
    static void startHeadless() throws Exception {
        System.setProperty("glass.platform", "Monocle");
        System.setProperty("monocle.platform", "Headless");
        System.setProperty("prism.order", "sw");
        CompletableFuture<Void> started = new CompletableFuture<>();
        try {
            Platform.startup(() -> started.complete(null));
        } catch (IllegalStateException alreadyStarted) {
            started.complete(null);
        }
        started.get(30, TimeUnit.SECONDS);
    }

    @Test
    void thePanelNamesThePersonAndTheirAssistant() throws Exception {
        Authors ada = new Authors(new Authors.Author("Ada Lovelace", "ada@komet.ike.network"),
                Optional.of(new Authors.Author("Ada's Claude", "ada-claude@komet.ike.network")));

        assertThat(words(new Authors.Found(FILE, ada))).contains(
                "Opens as Ada Lovelace <ada@komet.ike.network>, with Ada's Claude <ada-claude@komet.ike.network> as assistant");
    }

    @Test
    void thePanelSaysWhyTheFileCannotBeUsed() throws Exception {
        assertThat(words(new Authors.Unreadable(FILE, "it names no person: person.name and person.email are both needed")))
                .anyMatch(text -> text.startsWith("The authors in ") && text.contains("cannot be used: it names no person")
                        && text.endsWith("Komet opens under the name in git's configuration."));
    }

    @Test
    void withNoFileThePanelSaysNothingOfAuthors() throws Exception {
        assertThat(words(new Authors.Absent(FILE))).noneMatch(text -> text.startsWith("Opens as") || text.contains("authors"));
    }

    /** The words of a knowledge base's panel, when the file of authors gives what is read. */
    private static List<String> words(Authors.Reading reading) throws Exception {
        CompletableFuture<List<String>> words = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                DashboardView view = new DashboardView();
                view.setAuthors(() -> reading);
                view.setKomets(List.of(new KometInstallation("Komet Desktop 26.1108.1917", "26.1108.1917",
                        Path.of("Applications", "Komet.app"), Path.of("Applications", "Komet.app", "Contents", "MacOS", "Komet"))));
                view.setFamilies(List.of(new KnowledgeBaseFamily("pizza-kb", List.of(copy()))));
                view.select("pizza-kb");
                new Scene(view, 1080, 720);
                view.applyCss();
                view.layout();
                words.complete(Words.of(view).stream().map(Names::plain).toList());
            } catch (RuntimeException | AssertionError e) {
                words.completeExceptionally(e);
            }
        });
        return words.get(30, TimeUnit.SECONDS);
    }

    private static KnowledgeBase copy() {
        KnowledgeBaseDescriptor descriptor = new KnowledgeBaseDescriptor(1, PIZZA, Optional.empty(), "Pizza", "rocks", 3,
                Instant.parse("2026-10-23T23:23:18Z"), "public", "META-INF/ike/icon.png");
        return new KnowledgeBase("pizza-kb", Path.of("Solor", "pizza-kb"), StoreKind.ROCKS, 1_000_000, Optional.of(PIZZA),
                Map.of(KnowledgeBase.RESOLVED_VERSION, "20250627.1-20261024.001942-7"), Instant.parse("2026-10-23T23:23:18Z"),
                DescriptorCheck.met(descriptor));
    }
}
