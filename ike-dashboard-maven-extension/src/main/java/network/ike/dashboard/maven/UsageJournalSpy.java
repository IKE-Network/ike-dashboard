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
package network.ike.dashboard.maven;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;

import javax.inject.Named;
import javax.inject.Singleton;

import network.ike.dashboard.journal.UsageJournal;
import org.apache.maven.eventspy.EventSpy;
import org.apache.maven.execution.ExecutionEvent;
import org.eclipse.aether.RepositoryEvent;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Records every artifact a build resolves into the local repository's usage journal, once, at the
 * end of the build.
 *
 * <p>It never fails a build and never throws out of {@link #onEvent(Object)}: a journal that cannot
 * be written costs a log line, not the build. {@code -Dike.usageJournal.skip=true} turns it off for
 * one build.
 *
 * <p>Registered through the hand-written sisu index at {@code META-INF/sisu/javax.inject.Named}.
 */
@Named("ike-dashboard-usage-journal")
@Singleton
public class UsageJournalSpy implements EventSpy {

    /** The system property that turns the journal off for one build. */
    public static final String SKIP_PROPERTY = "ike.usageJournal.skip";

    private static final Logger LOG = LoggerFactory.getLogger(UsageJournalSpy.class);

    private final UsageCollector collector = new UsageCollector();

    /** Creates the spy; the Maven container instantiates it through the sisu index. */
    public UsageJournalSpy() {
    }

    /**
     * Initializes the spy; it needs nothing from the context.
     *
     * @param context the spy context supplied by Maven
     */
    @Override
    public void init(Context context) {
    }

    /**
     * Notes each resolved artifact, and writes the journal when the session ends.
     *
     * @param event the event published by Maven or the resolver
     */
    @Override
    public void onEvent(Object event) {
        try {
            if (Boolean.getBoolean(SKIP_PROPERTY)) {
                return;
            }
            if (event instanceof RepositoryEvent repositoryEvent) {
                onRepositoryEvent(repositoryEvent);
            } else if (event instanceof ExecutionEvent executionEvent
                    && executionEvent.getType() == ExecutionEvent.Type.SessionEnded) {
                write();
            }
        } catch (RuntimeException e) {
            LOG.warn("ike-dashboard usage journal: event not recorded ({})", e.toString());
        }
    }

    /** Writes whatever the session left unwritten, if it ended without a {@code SessionEnded} event. */
    @Override
    public void close() {
        try {
            write();
        } catch (RuntimeException e) {
            LOG.warn("ike-dashboard usage journal: not written ({})", e.toString());
        }
    }

    private void onRepositoryEvent(RepositoryEvent event) {
        if (event.getType() != RepositoryEvent.EventType.ARTIFACT_RESOLVED
                || (event.getExceptions() != null && !event.getExceptions().isEmpty())) {
            return;
        }
        Artifact artifact = event.getArtifact();
        RepositorySystemSession session = event.getSession();
        if (artifact == null || artifact.getPath() == null || session == null
                || session.getLocalRepository() == null) {
            return;
        }
        collector.resolved(session.getLocalRepository().getBasePath(), artifact.getPath());
    }

    private void write() {
        LocalDate today = LocalDate.now();
        for (Map.Entry<Path, Set<Path>> entry : collector.drain().entrySet()) {
            try {
                int lines = UsageJournal.record(entry.getKey(), UsageJournal.MAVEN, today, entry.getValue());
                LOG.debug("ike-dashboard usage journal: {} files recorded in {}", lines,
                        UsageJournal.file(entry.getKey()));
            } catch (Exception e) {
                LOG.warn("ike-dashboard usage journal: not written to {} ({})", UsageJournal.file(entry.getKey()),
                        e.toString());
            }
        }
    }
}
