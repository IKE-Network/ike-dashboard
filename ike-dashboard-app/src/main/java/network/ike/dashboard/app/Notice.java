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

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * One card of the dashboard's landing page (the IKE Dashboard design, decision 14).
 *
 * @param id        stable for the fact the notice reports, so a dismissal holds until the fact changes
 * @param urgency   where the card sorts
 * @param title     the card's heading
 * @param detail    a line or two under it
 * @param family    the knowledge base it is about, if any
 * @param action    its one button, if it has one
 * @param freshness when the fact was checked against the network, for a fact that needs it
 */
public record Notice(String id, Urgency urgency, String title, String detail, Optional<String> family,
                     Optional<NoticeAction> action, Optional<Freshness> freshness) {

    /** Where a notice sorts: work at risk first, then what can be done, then news. */
    public enum Urgency {
        /** Work that could be lost or cannot be used as it is. */
        AT_RISK,
        /** Something available to do. */
        ACTION,
        /** Information. */
        NEWS
    }

    /**
     * When a fact that needs the network was last checked, and whether the latest check succeeded. The
     * dashboard checks whenever the network allows and, when it cannot, shows what it last fetched as
     * of then, never as current.
     *
     * @param checkedAt when the fact was last fetched
     * @param current   whether the most recent check succeeded
     */
    public record Freshness(Instant checkedAt, boolean current) {

        /**
         * Requires the time.
         *
         * @param checkedAt when the fact was last fetched
         * @param current   whether the most recent check succeeded
         */
        public Freshness {
            Objects.requireNonNull(checkedAt, "checkedAt");
        }
    }

    /**
     * Requires the fields.
     *
     * @param id        stable for the fact the notice reports
     * @param urgency   where the card sorts
     * @param title     the card's heading
     * @param detail    a line or two under it
     * @param family    the knowledge base it is about, if any
     * @param action    its one button, if it has one
     * @param freshness when the fact was checked against the network
     */
    public Notice {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(urgency, "urgency");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(detail, "detail");
        Objects.requireNonNull(family, "family");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(freshness, "freshness");
    }
}
