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

import network.ike.dashboard.download.DownloadListener;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * One download or install as the person watches it: what it is, which step it is on, how far it has
 * come, how long it has run and about how long is left, and a way to cancel it. It listens to a download
 * task as Komet's progress row does, and words what it shows the way Komet does: "About 4 minutes
 * remaining." and a stopwatch reading such as "1m 12s". It holds no JavaFX; the view draws it.
 */
public final class Transfer implements DownloadListener<Path> {

    /** Where a transfer is. */
    public enum State {
        /** Queued behind another. */
        WAITING,
        /** Under way. */
        RUNNING,
        /** Asked to stop; the task stops at its next step. */
        CANCELLING,
        /** Stopped before it finished. */
        CANCELLED,
        /** Ended with an error. */
        FAILED,
        /** Finished. */
        DONE;

        /**
         * Whether the transfer has ended.
         *
         * @return {@code true} once cancelled, failed or done
         */
        public boolean ended() {
            return this == CANCELLED || this == FAILED || this == DONE;
        }
    }

    /**
     * What the view draws.
     *
     * @param title     what is being transferred
     * @param state     where it is
     * @param phase     the step it is on: downloading, unpacking …
     * @param note      a passing remark from the task, such as a resumed connection, or empty
     * @param fraction  how far it has come, from 0 to 1
     * @param elapsed   how long it has run
     * @param remaining about how long is left, once that can be estimated
     * @param outcome   what happened, once it ended, or empty
     */
    public record Snapshot(String title, State state, String phase, String note, double fraction, Duration elapsed,
                           Optional<Duration> remaining, String outcome) {

        /**
         * How far it has come, as a percentage.
         *
         * @return such as {@code 42%}
         */
        public String percent() {
            return Math.round(fraction * 100) + "%";
        }

        /**
         * About how long is left, in Komet's words.
         *
         * @return such as {@code About 4 minutes remaining.}, or empty before it can be estimated
         */
        public String remainingText() {
            return remaining.map(left -> "About " + approximate(left) + " remaining.").orElse("");
        }

        /**
         * How long it has run, as a stopwatch reads it.
         *
         * @return such as {@code 1m 12s}
         */
        public String elapsedText() {
            return stopwatch(elapsed);
        }
    }

    /** The progress before which no estimate is made: too little has happened to tell a rate. */
    static final double MIN_FRACTION = 0.01;
    /** The time before which no estimate is made. */
    static final Duration MIN_ELAPSED = Duration.ofSeconds(3);
    /** The recent stretch the rate is measured over, so an estimate follows a connection that speeds up or slows. */
    static final Duration WINDOW = Duration.ofSeconds(30);

    private final String title;
    private final LongSupplier nanoClock;
    private final Deque<long[]> samples = new ArrayDeque<>();
    private State state = State.WAITING;
    private String phase = "Waiting";
    private String note = "";
    private double fraction;
    private long startNanos;
    private long endNanos;
    private String outcome = "";
    private Runnable cancel = () -> { };
    private Consumer<Snapshot> onChange = snapshot -> { };

    /**
     * A transfer not started yet.
     *
     * @param title what is being transferred, such as {@code SOLOR Knowledge Base}
     */
    public Transfer(String title) {
        this(title, System::nanoTime);
    }

    Transfer(String title, LongSupplier nanoClock) {
        this.title = title;
        this.nanoClock = nanoClock;
    }

    /**
     * Who is told of each change, on whichever thread the change happens.
     *
     * @param listener the listener
     */
    public void onChange(Consumer<Snapshot> listener) {
        Snapshot now;
        synchronized (this) {
            onChange = listener;
            now = snapshot();
        }
        listener.accept(now);
    }

    /**
     * How to stop the task once it runs; the download sets it. When Cancel was pressed before the task
     * could be stopped, it is stopped now.
     *
     * @param cancel what stops it
     */
    public void cancelWith(Runnable cancel) {
        boolean already;
        synchronized (this) {
            this.cancel = cancel;
            already = state == State.CANCELLING;
        }
        if (already) {
            cancel.run();
        }
    }

    /** Asks the transfer to stop: one still waiting never starts, and one running stops at its next step. */
    public void cancel() {
        Runnable stop;
        synchronized (this) {
            if (state == State.WAITING) {
                state = State.CANCELLED;
                outcome = "Cancelled before it started.";
                stop = () -> { };
            } else if (state == State.RUNNING) {
                state = State.CANCELLING;
                phase = "Cancelling";
                stop = cancel;
            } else {
                return;
            }
        }
        stop.run();
        changed();
    }

    /**
     * Whether the person asked it to stop.
     *
     * @return {@code true} once cancelled or cancelling
     */
    public synchronized boolean cancelled() {
        return state == State.CANCELLING || state == State.CANCELLED;
    }

    /**
     * Marks it under way, from now, unless the person cancelled it while it waited.
     *
     * @return {@code true} when it may start
     */
    public boolean started() {
        synchronized (this) {
            if (state != State.WAITING) {
                return false;
            }
            state = State.RUNNING;
            phase = "Starting";
            startNanos = nanoClock.getAsLong();
        }
        changed();
        return true;
    }

    /**
     * Names the step it is on, for a step the task does not report, such as an installer's unpacking.
     *
     * @param step the step
     */
    public void phase(String step) {
        synchronized (this) {
            phase = step;
            samples.clear();
        }
        changed();
    }

    /**
     * Ends it.
     *
     * @param ended   how it ended: {@link State#DONE}, {@link State#FAILED} or {@link State#CANCELLED}
     * @param message what happened, for the person
     */
    public void finished(State ended, String message) {
        if (!ended.ended()) {
            throw new IllegalArgumentException(ended + " does not end a transfer");
        }
        synchronized (this) {
            state = ended;
            outcome = message;
            endNanos = nanoClock.getAsLong();
            if (ended == State.DONE) {
                fraction = 1.0;
            }
        }
        changed();
    }

    /**
     * What it shows now.
     *
     * @return the snapshot
     */
    public synchronized Snapshot snapshot() {
        long now = state.ended() ? endNanos : nanoClock.getAsLong();
        Duration elapsed = state == State.WAITING || startNanos == 0 ? Duration.ZERO : Duration.ofNanos(now - startNanos);
        Optional<Duration> remaining = state == State.RUNNING ? remaining(now) : Optional.empty();
        return new Snapshot(title, state, phase, note, fraction, elapsed, remaining, outcome);
    }

    @Override
    public void updateValue(Path result) {
    }

    @Override
    public void updateMessage(String message) {
        synchronized (this) {
            note = message == null || message.startsWith("Materialized ") ? "" : message;
        }
        changed();
    }

    @Override
    public void updateTitle(String step) {
        if (step == null) {
            return;
        }
        synchronized (this) {
            if (state != State.CANCELLING) {
                phase = phaseOf(step);
            }
        }
        changed();
    }

    @Override
    public void updateProgress(double workDone, double max) {
        if (max <= 0) {
            return;
        }
        synchronized (this) {
            fraction = Math.min(1.0, Math.max(0.0, workDone / max));
            long now = nanoClock.getAsLong();
            samples.addLast(new long[] {now, Double.doubleToRawLongBits(fraction)});
            while (samples.size() > 2 && now - samples.peekFirst()[0] > WINDOW.toNanos()) {
                samples.removeFirst();
            }
        }
        changed();
    }

    /** The task's own title is a path or a URL; the person reads only the step. */
    static String phaseOf(String step) {
        if (step.startsWith("Downloading ")) {
            return "Downloading";
        }
        if (step.startsWith("Verifying cached copy")) {
            return "Checking the copy already downloaded";
        }
        if (step.startsWith("Using cached copy")) {
            return "Using the copy already downloaded";
        }
        if (step.startsWith("Unpacking ")) {
            return "Unpacking";
        }
        if (step.startsWith("Copying ")) {
            return "Copying";
        }
        return step;
    }

    /** About how long is left: the rate over the recent window, once enough has happened to tell one. */
    private Optional<Duration> remaining(long now) {
        if (samples.size() < 2 || fraction < MIN_FRACTION || fraction >= 1.0
                || now - startNanos < MIN_ELAPSED.toNanos()) {
            return Optional.empty();
        }
        long[] oldest = samples.peekFirst();
        long[] latest = samples.peekLast();
        double done = Double.longBitsToDouble(latest[1]) - Double.longBitsToDouble(oldest[1]);
        long spent = latest[0] - oldest[0];
        if (done <= 0 || spent <= 0) {
            return Optional.empty();
        }
        double nanosLeft = (1.0 - fraction) * spent / done - (now - latest[0]);
        return Optional.of(Duration.ofNanos(Math.max(0, Math.round(nanosLeft))));
    }

    private void changed() {
        Consumer<Snapshot> listener;
        Snapshot now;
        synchronized (this) {
            listener = onChange;
            now = snapshot();
        }
        listener.accept(now);
    }

    /**
     * A duration in round words, as Komet says how long is left: "a few seconds", "40 seconds",
     * "4 minutes", "1 h 15 min".
     *
     * @param duration the duration
     * @return the words
     */
    static String approximate(Duration duration) {
        long seconds = Math.max(0, duration.getSeconds());
        if (seconds < 10) {
            return "a few seconds";
        }
        if (seconds < 60) {
            return (seconds / 10) * 10 + " seconds";
        }
        if (seconds < 3600) {
            long minutes = Math.max(1, Math.round(seconds / 60.0));
            return minutes == 1 ? "1 minute" : minutes + " minutes";
        }
        if (seconds < 86400) {
            long fiveMinutes = Math.round(seconds / 300.0) * 5;
            long hours = fiveMinutes / 60;
            long minutes = fiveMinutes % 60;
            return minutes == 0 ? hours + " h" : hours + " h " + minutes + " min";
        }
        return "more than a day";
    }

    /**
     * A duration as a stopwatch reads it, as Komet shows how long a task has run: "22s", "12m 5s", "1h 3m".
     *
     * @param duration the duration
     * @return the reading
     */
    static String stopwatch(Duration duration) {
        long seconds = Math.max(0, duration.getSeconds());
        if (seconds < 60) {
            return seconds + "s";
        }
        if (seconds < 3600) {
            return seconds / 60 + "m " + seconds % 60 + "s";
        }
        return seconds / 3600 + "h " + (seconds % 3600) / 60 + "m";
    }
}
