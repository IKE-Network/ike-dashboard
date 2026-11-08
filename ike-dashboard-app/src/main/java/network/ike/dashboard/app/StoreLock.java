/*
 * Copyright © 2026 Knowledge Graphlet / IKE Network
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package network.ike.dashboard.app;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Whether a Komet has a store open, read from the store's search index (KEC, 2026-11-08): a Komet that opens a
 * store holds Lucene's {@code lucene/write.lock}, an operating-system lock that ends with the process. The file
 * itself stays after Komet closes, so its being there says nothing; the lock is what is held. The dashboard asks
 * for the lock and lets it go at once, as Lucene's own check does, so it never keeps a store from opening.
 * This sees a Komet however it was started, which the dashboard's list of the Komets it started does not.
 */
public final class StoreLock {

    /** The search index's lock, relative to the store. */
    static final String LUCENE_LOCK = "lucene/write.lock";

    private StoreLock() {
    }

    /**
     * Whether a process holds the store's search-index lock, which a Komet does while the store is open.
     *
     * @param store the store
     * @return {@code true} when it is held; {@code false} when it is free, the store has no index, or the lock
     *         cannot be asked for
     */
    public static boolean openInKomet(Path store) {
        Path lock = store.resolve(LUCENE_LOCK);
        if (!Files.isRegularFile(lock)) {
            return false;
        }
        try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.WRITE)) {
            FileLock held = channel.tryLock();
            if (held == null) {
                return true;
            }
            held.release();
            return false;
        } catch (OverlappingFileLockException e) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * The refusal for a store a Komet has open, in the words the dashboard's refusals use.
     *
     * @param store the store
     * @return what to do
     */
    static String refusal(KnowledgeBase store) {
        return Names.of(store.name()) + " is open in Komet. Quit Komet, then try again.";
    }
}
