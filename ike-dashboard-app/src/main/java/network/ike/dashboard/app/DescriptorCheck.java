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

import java.util.List;
import java.util.Optional;

/**
 * Whether a store, or a knowledge base on Nexus, meets the minimum for the dashboard to manage it: a
 * knowledge-base descriptor at a supported level with every required field, and its icon.
 *
 * @param descriptor the descriptor, when it meets the minimum
 * @param problems   why it does not, when it does not; empty when it does
 */
public record DescriptorCheck(Optional<KnowledgeBaseDescriptor> descriptor, List<String> problems) {

    /**
     * Copies the problems.
     *
     * @param descriptor the descriptor, when it meets the minimum
     * @param problems   why it does not
     */
    public DescriptorCheck {
        problems = List.copyOf(problems);
    }

    /**
     * A descriptor that meets the minimum.
     *
     * @param descriptor the descriptor
     * @return the check
     */
    public static DescriptorCheck met(KnowledgeBaseDescriptor descriptor) {
        return new DescriptorCheck(Optional.of(descriptor), List.of());
    }

    /**
     * A store or artifact that does not meet the minimum.
     *
     * @param problems why
     * @return the check
     */
    public static DescriptorCheck unmet(List<String> problems) {
        return new DescriptorCheck(Optional.empty(), problems);
    }

    /**
     * Whether the dashboard manages it.
     *
     * @return {@code true} when the descriptor meets the minimum
     */
    public boolean met() {
        return descriptor.isPresent();
    }
}
