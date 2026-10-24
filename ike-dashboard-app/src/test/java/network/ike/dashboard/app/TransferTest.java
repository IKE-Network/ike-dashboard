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

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A transfer's progress as the person watches it, in Komet's words, with a clock the test turns.
 */
class TransferTest {

    private final AtomicLong nanos = new AtomicLong(1_000_000_000L);

    @Test
    void itWaitsUntilStartedAndThenNamesTheStepNotThePath() {
        Transfer transfer = new Transfer("SOLOR Knowledge Base (Rocks)", nanos::get);
        assertThat(transfer.snapshot().state()).isEqualTo(Transfer.State.WAITING);
        assertThat(transfer.snapshot().phase()).isEqualTo("Waiting");

        assertThat(transfer.started()).isTrue();
        transfer.updateTitle("Downloading https://nexus.tinkar.org/repository/ike-snapshots/solor-kb.zip");
        assertThat(transfer.snapshot().phase()).isEqualTo("Downloading");
        transfer.updateTitle("Unpacking /Users/kec/Solor/solor-kb");
        assertThat(transfer.snapshot().phase()).isEqualTo("Unpacking");
        transfer.updateTitle("Using cached copy at /Users/kec/.m2/repository/x.zip");
        assertThat(transfer.snapshot().phase()).isEqualTo("Using the copy already downloaded");
    }

    @Test
    void howFarItHasComeAndHowLongItHasRunReadAsKometShowsThem() {
        Transfer transfer = new Transfer("x", nanos::get);
        transfer.started();
        transfer.updateProgress(420_000, 1_000_000);
        advance(Duration.ofSeconds(72));

        assertThat(transfer.snapshot().percent()).isEqualTo("42%");
        assertThat(transfer.snapshot().elapsedText()).isEqualTo("1m 12s");
        assertThat(Transfer.stopwatch(Duration.ofSeconds(22))).isEqualTo("22s");
        assertThat(Transfer.stopwatch(Duration.ofMinutes(63))).isEqualTo("1h 3m");
        assertThat(Transfer.approximate(Duration.ofSeconds(5))).isEqualTo("a few seconds");
        assertThat(Transfer.approximate(Duration.ofSeconds(47))).isEqualTo("40 seconds");
        assertThat(Transfer.approximate(Duration.ofSeconds(250))).isEqualTo("4 minutes");
        assertThat(Transfer.approximate(Duration.ofMinutes(75))).isEqualTo("1 h 15 min");
    }

    @Test
    void theTimeLeftIsEstimatedFromTheRecentRateOnceEnoughHasHappened() {
        Transfer transfer = new Transfer("x", nanos::get);
        transfer.started();
        transfer.updateProgress(0.005, 1);
        advance(Duration.ofSeconds(1));
        assertThat(transfer.snapshot().remainingText()).as("too early to tell").isEmpty();

        // One percent every ten seconds from here: 1 % → 20 % is 190 seconds, and 80 % is left.
        transfer.updateProgress(0.01, 1);
        advance(Duration.ofSeconds(190));
        transfer.updateProgress(0.20, 1);

        assertThat(transfer.snapshot().remaining()).hasValueSatisfying(left ->
                assertThat(left).isBetween(Duration.ofSeconds(790), Duration.ofSeconds(810)));
        assertThat(transfer.snapshot().remainingText()).isEqualTo("About 13 minutes remaining.");
    }

    @Test
    void cancellingOneThatWaitsMeansItNeverStarts() {
        Transfer transfer = new Transfer("x", nanos::get);
        AtomicInteger stopped = new AtomicInteger();
        transfer.cancelWith(stopped::incrementAndGet);

        transfer.cancel();

        assertThat(transfer.snapshot().state()).isEqualTo(Transfer.State.CANCELLED);
        assertThat(transfer.started()).isFalse();
        assertThat(stopped).as("there was no task to stop").hasValue(0);
    }

    @Test
    void cancellingOneThatRunsStopsItsTaskEvenBeforeTheTaskIsKnown() {
        Transfer transfer = new Transfer("x", nanos::get);
        transfer.started();
        transfer.cancel();
        assertThat(transfer.snapshot().state()).isEqualTo(Transfer.State.CANCELLING);

        AtomicInteger stopped = new AtomicInteger();
        transfer.cancelWith(stopped::incrementAndGet);

        assertThat(stopped).as("Cancel was pressed before the download could be stopped").hasValue(1);
        transfer.updateTitle("Unpacking /x");
        assertThat(transfer.snapshot().phase()).as("still cancelling, whatever step the task reports").isEqualTo("Cancelling");
    }

    @Test
    void everyChangeIsToldAndAnEndedTransferStopsItsClock() {
        Transfer transfer = new Transfer("x", nanos::get);
        List<Transfer.State> told = new ArrayList<>();
        transfer.onChange(snapshot -> told.add(snapshot.state()));
        transfer.started();
        advance(Duration.ofSeconds(30));
        transfer.finished(Transfer.State.FAILED, "Could not download: 403");
        advance(Duration.ofMinutes(5));

        assertThat(told).containsSubsequence(Transfer.State.WAITING, Transfer.State.RUNNING, Transfer.State.FAILED);
        assertThat(transfer.snapshot().elapsedText()).isEqualTo("30s");
        assertThat(transfer.snapshot().outcome()).isEqualTo("Could not download: 403");
    }

    private void advance(Duration duration) {
        nanos.addAndGet(duration.toNanos());
    }
}
