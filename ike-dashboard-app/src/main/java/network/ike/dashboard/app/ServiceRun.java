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
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * A tinkar-service the dashboard started, and where it stands: starting until its log says it has started,
 * then ready, until it is stopped or ends. The dashboard runs one at a time.
 */
public final class ServiceRun {

    /** Where a run stands. */
    public enum State {
        /** Started; the service is opening its store. */
        STARTING,
        /** The service answers. */
        READY,
        /** Asked to stop; it is flushing and closing its store. */
        STOPPING,
        /** The process has ended. */
        ENDED
    }

    private final TinkarService.Launch launch;
    private final Process process;
    private final Path log;
    private final Instant started;
    private volatile State state = State.STARTING;
    private volatile boolean stopAsked;
    private volatile Instant stopAskedAt;
    private volatile Instant readyAt;
    private long logRead;

    /**
     * A run.
     *
     * @param launch  what was started
     * @param process its process
     * @param log     its log
     * @param started when it was started
     */
    ServiceRun(TinkarService.Launch launch, Process process, Path log, Instant started) {
        this.launch = launch;
        this.process = process;
        this.log = log;
        this.started = started;
    }

    /**
     * What was started.
     *
     * @return the launch
     */
    public TinkarService.Launch launch() {
        return launch;
    }

    /**
     * Its log.
     *
     * @return the log file
     */
    public Path log() {
        return log;
    }

    /**
     * When it was started.
     *
     * @return the time
     */
    public Instant started() {
        return started;
    }

    /**
     * When it became ready, once it has.
     *
     * @return the time
     */
    public Optional<Instant> readyAt() {
        return Optional.ofNullable(readyAt);
    }

    /**
     * Its process id.
     *
     * @return the pid
     */
    public long pid() {
        return process.pid();
    }

    /**
     * Where it stands, reading any new lines of its log first.
     *
     * @return the state
     */
    public synchronized State state() {
        if (state == State.ENDED) {
            return state;
        }
        if (!process.isAlive()) {
            state = State.ENDED;
            return state;
        }
        if (state == State.STARTING && logSays(TinkarService.READY_LINE)) {
            state = State.READY;
            readyAt = Instant.now();
        }
        return state;
    }

    /**
     * Whether it had become ready before it ended, or was asked to stop: an end nobody asked for, before it
     * was ready, is a failure to start.
     *
     * @return {@code true} when it ended as expected
     */
    public boolean endedAsExpected() {
        return stopAsked || readyAt != null;
    }

    /**
     * When it was asked to stop, once it has been.
     *
     * @return the time
     */
    public Optional<Instant> stopAskedAt() {
        return Optional.ofNullable(stopAskedAt);
    }

    /**
     * Its exit status, once it has ended.
     *
     * @return the status
     */
    public Optional<Integer> exitStatus() {
        return process.isAlive() ? Optional.empty() : Optional.of(process.exitValue());
    }

    /**
     * Asks it to stop, gently (SIGTERM), so it flushes and closes its store.
     */
    public synchronized void stop() {
        if (!stopAsked) {
            stopAskedAt = Instant.now();
        }
        stopAsked = true;
        if (process.isAlive()) {
            state = State.STOPPING;
            process.destroy();
        }
    }

    /**
     * Ends it at once. Its store is not closed, and may need recovery the next time it is opened.
     */
    public synchronized void forceStop() {
        stopAsked = true;
        process.destroyForcibly();
    }

    /**
     * Waits for it to end.
     *
     * @param seconds how long at most
     * @return {@code true} when it ended in that time
     * @throws InterruptedException if the wait is interrupted
     */
    public boolean waitFor(long seconds) throws InterruptedException {
        return process.waitFor(seconds, TimeUnit.SECONDS);
    }

    /**
     * Runs an action when the process ends.
     *
     * @param action the action, on a thread of the JDK's
     */
    public void onExit(Runnable action) {
        process.onExit().thenRun(action);
    }

    /** Whether the lines written since the last look hold the text. */
    private boolean logSays(String text) {
        try (RandomAccessFile file = new RandomAccessFile(log.toFile(), "r")) {
            long length = file.length();
            if (length <= logRead) {
                return false;
            }
            // Read from a little before where the last look ended, so a line split between two looks is still seen.
            long from = Math.max(0, logRead - text.length());
            file.seek(from);
            byte[] bytes = new byte[(int) Math.min(length - from, Integer.MAX_VALUE)];
            file.readFully(bytes);
            logRead = length;
            return new String(bytes, StandardCharsets.UTF_8).contains(text);
        } catch (IOException e) {
            return false;
        }
    }
}
