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

/**
 * Receives what a {@link DownloadTask} reports as it runs: its title, its latest message, its
 * progress, and its result. The callbacks are those of Tinkar's {@code TrackingListener}, so Komet
 * can bridge a task into its progress UI with a one-class adapter.
 *
 * <p>Callbacks arrive on the thread running the task; a UI moves them to its own thread.
 *
 * @param <V> the task's result type
 */
public interface DownloadListener<V> {

    /**
     * The task's result, or {@code null} before there is one.
     *
     * @param result the result
     */
    void updateValue(V result);

    /**
     * The task's latest message, or {@code null} before there is one.
     *
     * @param message the message
     */
    void updateMessage(String message);

    /**
     * What the task is doing now, or {@code null} before it says.
     *
     * @param title the title
     */
    void updateTitle(String title);

    /**
     * How far the task has come.
     *
     * @param workDone the work done
     * @param max      the total work
     */
    void updateProgress(double workDone, double max);
}
