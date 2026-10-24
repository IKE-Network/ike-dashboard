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
package network.ike.dashboard.download;

import java.util.concurrent.Callable;

/**
 * A cancellable task that reports its title, message and progress to one {@link DownloadListener}.
 *
 * <p>It stands in for Tinkar's {@code TrackingCallable}, which the copied download code extended in
 * Komet, with the same reporting rules: a message is passed on only when it changes, and progress
 * only when it moves by more than half a percent or its total changes. It keeps no time estimate;
 * a surface that shows one measures it from the progress it receives.
 *
 * @param <V> the task's result type
 */
public abstract class DownloadTask<V> implements Callable<V> {

    /** The smallest move, as a fraction of the total, that is passed on as progress. */
    private static final double UPDATE_THRESHOLD = 0.005;

    private volatile boolean cancelled;
    private DownloadListener<V> listener;
    private double workDone;
    private double maxWork;
    private String title;
    private String message;
    private V value;

    /** Creates a task. */
    protected DownloadTask() {
    }

    /**
     * Runs the task.
     *
     * @return the result
     * @throws Exception whatever the task throws; an {@link Error} is wrapped
     */
    @Override
    public final V call() throws Exception {
        try {
            return compute();
        } catch (Exception e) {
            throw e;
        } catch (Throwable t) {
            throw new Exception(t);
        }
    }

    /**
     * The task's work.
     *
     * @return the result
     * @throws Exception if the work fails
     */
    protected abstract V compute() throws Exception;

    /**
     * Whether {@link #cancel()} has been called. The task checks it as it works.
     *
     * @return {@code true} once cancelled
     */
    public boolean isCancelled() {
        return cancelled;
    }

    /** Asks the task to stop at its next check. */
    public void cancel() {
        cancelled = true;
    }

    /**
     * Sets the one listener and tells it the state so far.
     *
     * @param listener the listener
     * @throws IllegalStateException if a listener is already set
     */
    public void addListener(DownloadListener<V> listener) {
        if (this.listener != null) {
            throw new IllegalStateException("Listener already set");
        }
        this.listener = listener;
        listener.updateValue(value);
        listener.updateMessage(message);
        listener.updateTitle(title);
        listener.updateProgress(workDone, maxWork);
    }

    /**
     * Reports the result.
     *
     * @param result the result
     */
    public void updateValue(V result) {
        this.value = result;
        if (listener != null) {
            listener.updateValue(result);
        }
    }

    /**
     * Reports a message, passed on only when it differs from the last.
     *
     * @param message the message
     */
    public void updateMessage(String message) {
        boolean changed = message == null ? this.message != null : !message.equals(this.message);
        this.message = message;
        if (changed && listener != null) {
            listener.updateMessage(message);
        }
    }

    /**
     * Reports what the task is doing now.
     *
     * @param title the title
     */
    public void updateTitle(String title) {
        this.title = title;
        if (listener != null) {
            listener.updateTitle(title);
        }
    }

    /**
     * Reports progress, passed on when the total changes or the work moves by more than half a
     * percent of it.
     *
     * @param workDone the work done
     * @param maxWork  the total work
     */
    public void updateProgress(double workDone, double maxWork) {
        boolean update;
        if (this.maxWork != maxWork) {
            update = true;
        } else {
            update = (workDone - this.workDone) / maxWork > UPDATE_THRESHOLD;
        }
        if (update) {
            this.maxWork = maxWork;
            this.workDone = workDone;
            if (listener != null) {
                listener.updateProgress(workDone, maxWork);
            }
        }
    }

    /**
     * Reports progress in whole units.
     *
     * @param workDone the work done
     * @param maxWork  the total work
     */
    public void updateProgress(long workDone, long maxWork) {
        updateProgress((double) workDone, (double) maxWork);
    }
}
