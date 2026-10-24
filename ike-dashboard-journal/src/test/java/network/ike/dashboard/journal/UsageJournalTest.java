package network.ike.dashboard.journal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The usage journal records which files of a local repository were used, and on which day, so the
 * dashboard's collector can tell used from unused when the file system cannot.
 */
class UsageJournalTest {

    private static final LocalDate MONDAY = LocalDate.of(2026, 10, 19);
    private static final LocalDate FRIDAY = LocalDate.of(2026, 10, 23);

    @TempDir
    Path repository;

    @Test
    void theLastUseOfEachFileIsItsNewestDay() throws IOException {
        Path rkb = repository.resolve("network/ike/data/solor-kb/1-SNAPSHOT/solor-kb-1-SNAPSHOT-rkb.zip");
        Path pom = repository.resolve("network/ike/data/solor-kb/1-SNAPSHOT/solor-kb-1-SNAPSHOT.pom");

        UsageJournal.record(repository, UsageJournal.MAVEN, MONDAY, List.of(rkb, pom));
        UsageJournal.record(repository, UsageJournal.DASHBOARD, FRIDAY, List.of(rkb));

        assertThat(UsageJournal.lastUsed(repository)).containsOnly(
                Map.entry(Path.of("network/ike/data/solor-kb/1-SNAPSHOT/solor-kb-1-SNAPSHOT-rkb.zip"), FRIDAY),
                Map.entry(Path.of("network/ike/data/solor-kb/1-SNAPSHOT/solor-kb-1-SNAPSHOT.pom"), MONDAY));
    }

    @Test
    void filesOutsideTheRepositoryAndDuplicatesAreLeftOut() throws IOException {
        Path jar = repository.resolve("org/slf4j/slf4j-api/2.0.17/slf4j-api-2.0.17.jar");
        Path reactorClasses = repository.resolveSibling("project/target/classes");

        int written = UsageJournal.record(repository, UsageJournal.MAVEN, FRIDAY,
                List.of(jar, jar, Path.of("org/slf4j/slf4j-api/2.0.17/slf4j-api-2.0.17.jar"), reactorClasses,
                        repository.resolve("../escape.jar"), UsageJournal.file(repository)));

        assertThat(written).isEqualTo(1);
        assertThat(Files.readAllLines(UsageJournal.file(repository))).containsExactly(
                "# IKE usage journal: <day> TAB <tool> TAB <path relative to the local repository>",
                "2026-10-23\tmaven\torg/slf4j/slf4j-api/2.0.17/slf4j-api-2.0.17.jar");
    }

    @Test
    void nothingToRecordWritesNoJournal() throws IOException {
        assertThat(UsageJournal.record(repository, UsageJournal.MAVEN, FRIDAY, List.of())).isZero();
        assertThat(UsageJournal.file(repository)).doesNotExist();
        assertThat(UsageJournal.lastUsed(repository)).isEmpty();
    }

    @Test
    void unreadableLinesAreSkipped() throws IOException {
        Files.writeString(UsageJournal.file(repository), """
                # a comment
                not a line
                2026-13-40\tmaven\ta/b.jar
                2026-10-23\tmaven
                2026-10-23\tmaven\ta/b/1/b-1.jar

                """);

        assertThat(UsageJournal.lastUsed(repository)).containsOnly(Map.entry(Path.of("a/b/1/b-1.jar"), FRIDAY));
    }

    @Test
    void compactingKeepsOneLinePerFileAtItsLastUse() throws IOException {
        Path jar = repository.resolve("a/b/1/b-1.jar");
        Path zip = repository.resolve("a/c/1/c-1-rkb.zip");
        UsageJournal.record(repository, UsageJournal.MAVEN, MONDAY, List.of(jar, zip));
        UsageJournal.record(repository, UsageJournal.MAVEN, FRIDAY, List.of(jar));
        UsageJournal.record(repository, UsageJournal.MAVEN, MONDAY.plusDays(1), List.of(jar));
        Map<Path, LocalDate> before = UsageJournal.lastUsed(repository);

        UsageJournal.compact(repository);

        assertThat(UsageJournal.lastUsed(repository)).isEqualTo(before);
        assertThat(Files.readAllLines(UsageJournal.file(repository))).containsExactly(
                "# IKE usage journal: <day> TAB <tool> TAB <path relative to the local repository>",
                "2026-10-23\tmaven\ta/b/1/b-1.jar",
                "2026-10-19\tmaven\ta/c/1/c-1-rkb.zip");
    }

    @Test
    void writersAtOnceInterleaveWholeLines() throws Exception {
        int writers = 8;
        int filesEach = 200;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int w = 0; w < writers; w++) {
                int writer = w;
                results.add(pool.submit(() -> {
                    List<Path> files = new ArrayList<>();
                    for (int f = 0; f < filesEach; f++) {
                        files.add(repository.resolve("g/w" + writer + "/1/w" + writer + "-" + f + ".jar"));
                    }
                    return UsageJournal.record(repository, UsageJournal.MAVEN, FRIDAY, files);
                }));
            }
            for (Future<Integer> result : results) {
                assertThat(result.get()).isEqualTo(filesEach);
            }
        } finally {
            pool.shutdown();
        }

        List<String> lines = Files.readAllLines(UsageJournal.file(repository));
        assertThat(lines).hasSize(1 + writers * filesEach);
        assertThat(lines.subList(1, lines.size())).allMatch(line -> line.matches("2026-10-23\tmaven\tg/w\\d/1/w\\d-\\d+\\.jar"));
        assertThat(UsageJournal.lastUsed(repository)).hasSize(writers * filesEach);
    }

    @Test
    void aToolNameIsOneWord() {
        assertThatThrownBy(() -> UsageJournal.record(repository, "two\twords", FRIDAY, List.of(Path.of("a/b.jar"))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
