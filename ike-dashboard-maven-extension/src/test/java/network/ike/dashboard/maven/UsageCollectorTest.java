package network.ike.dashboard.maven;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The collector holds what a build resolved, per local repository, until the build ends, and hands
 * each file over once.
 */
class UsageCollectorTest {

    private static final Path REPOSITORY = Path.of("/Users/someone/.m2/repository");
    private static final Path JAR = REPOSITORY.resolve("org/slf4j/slf4j-api/2.0.17/slf4j-api-2.0.17.jar");

    @Test
    void eachFileIsHandedOverOnce() {
        UsageCollector collector = new UsageCollector();
        collector.resolved(REPOSITORY, JAR);
        collector.resolved(REPOSITORY, JAR);

        assertThat(collector.drain()).containsOnly(Map.entry(REPOSITORY, Set.of(JAR)));
        assertThat(collector.drain()).as("a second build in the same daemon starts empty").isEmpty();
    }

    @Test
    void filesAreKeptPerLocalRepository() {
        Path ciRepository = Path.of("/Users/someone/.m2/solor-kb-ws-repository");
        Path zip = ciRepository.resolve("network/ike/data/solor-kb/1-SNAPSHOT/solor-kb-1-SNAPSHOT-rkb.zip");
        UsageCollector collector = new UsageCollector();
        collector.resolved(REPOSITORY, JAR);
        collector.resolved(ciRepository, zip);

        assertThat(collector.drain()).containsOnly(Map.entry(REPOSITORY, Set.of(JAR)),
                Map.entry(ciRepository, Set.of(zip)));
    }

    @Test
    void resolvingFromManyThreadsLosesNothing() {
        UsageCollector collector = new UsageCollector();
        List<Path> files = new ArrayList<>();
        IntStream.range(0, 2_000).forEach(i -> files.add(REPOSITORY.resolve("g/a" + i + "/1/a" + i + "-1.jar")));

        files.parallelStream().forEach(file -> collector.resolved(REPOSITORY, file));

        assertThat(collector.drain().get(REPOSITORY)).containsExactlyInAnyOrderElementsOf(files);
    }
}
