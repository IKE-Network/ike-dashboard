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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The knowledge bases Nexus offers as a tree (decision 18 of the {@code dev-ike-dashboard} topic): each
 * knowledge base with the databases Komet opens, the protobufs Komet loads into a store, the knowledge
 * bases it was built from, and those built from it. What a knowledge base was built from is what its
 * descriptor records ({@code input.<n>}); what it is included in is every other descriptor that records
 * it.
 *
 * <pre>
 * SOLOR GUDID
 * ├─ Databases      Rocks, spined array
 * ├─ Protobufs      protobuf, reasoned
 * ├─ Built from     IKE Starter Set, SNOMED CT, …, GUDID
 * └─ Included in    …
 * </pre>
 */
public final class KnowledgeBaseTree {

    /** The heading of a knowledge base's stores. */
    public static final String DATABASES = "Databases";

    /** The heading of a knowledge base's protobufs. */
    public static final String PROTOBUFS = "Protobufs";

    /** The heading of what a knowledge base was built from. */
    public static final String BUILT_FROM = "Built from";

    /** The heading of the knowledge bases built from it. */
    public static final String INCLUDED_IN = "Included in";

    /** What a node of the tree is. */
    public enum Kind {
        /** A knowledge base, at the top. */
        KNOWLEDGE_BASE,
        /** A heading: Databases, Protobufs, Built from or Included in. */
        HEADING,
        /** A store or a protobuf to download. */
        OFFER,
        /** A knowledge base or file the parent was built from. */
        INPUT,
        /** A knowledge base built from the parent. */
        INCLUDED_IN
    }

    /**
     * One node.
     *
     * @param kind          what it is
     * @param label         what a person reads
     * @param knowledgeBase the knowledge base it is or names, when it names one
     * @param offer         the store or protobuf, for an offer
     * @param input         the input, for what the parent was built from
     * @param children      the nodes beneath it
     */
    public record Node(Kind kind, String label, Optional<Coordinate> knowledgeBase, Optional<NexusCatalog.Offer> offer,
                       Optional<KnowledgeBaseDescriptor.Input> input, List<Node> children) {

        /**
         * Copies the children.
         *
         * @param kind          what it is
         * @param label         what a person reads
         * @param knowledgeBase the knowledge base it is or names
         * @param offer         the store or protobuf
         * @param input         the input
         * @param children      the nodes beneath it
         */
        public Node {
            children = List.copyOf(children);
        }

        /**
         * The child under a heading.
         *
         * @param heading the heading's label
         * @return its children, empty when there is no such heading
         */
        public List<Node> under(String heading) {
            return children.stream().filter(child -> child.kind() == Kind.HEADING && child.label().equals(heading))
                    .findFirst().map(Node::children).orElse(List.of());
        }
    }

    private KnowledgeBaseTree() {
    }

    /**
     * The tree of what the catalog describes, a knowledge base for each described artifact, by name.
     *
     * @param catalog the described knowledge bases and their stores and protobufs
     * @return the knowledge bases
     */
    public static List<Node> of(NexusCatalog.Catalog catalog) {
        Map<Coordinate, NexusCatalog.Described> described = catalog.described();
        List<Node> knowledgeBases = new ArrayList<>();
        for (Map.Entry<Coordinate, NexusCatalog.Described> entry : described.entrySet()) {
            Coordinate coordinate = entry.getKey();
            KnowledgeBaseDescriptor descriptor = entry.getValue().descriptor();
            List<Node> headings = new ArrayList<>();
            List<NexusCatalog.Offer> offers = catalog.offers().stream()
                    .filter(offer -> offer.coordinate().equals(coordinate)).toList();
            heading(headings, DATABASES, offers.stream().filter(NexusCatalog.Offer::store).map(KnowledgeBaseTree::offer).toList());
            heading(headings, PROTOBUFS, offers.stream().filter(offer -> !offer.store()).map(KnowledgeBaseTree::offer).toList());
            heading(headings, BUILT_FROM, descriptor.inputs().stream().filter(input -> !input.of(coordinate))
                    .map(input -> input(input, described)).toList());
            heading(headings, INCLUDED_IN, described.entrySet().stream()
                    .filter(other -> !other.getKey().equals(coordinate) && other.getValue().descriptor().includes(coordinate))
                    .map(other -> new Node(Kind.INCLUDED_IN, other.getValue().descriptor().displayName(),
                            Optional.of(other.getKey()), Optional.empty(), Optional.empty(), List.of()))
                    .sorted(Comparator.comparing(node -> node.label().toLowerCase(Locale.ROOT)))
                    .toList());
            knowledgeBases.add(new Node(Kind.KNOWLEDGE_BASE, descriptor.displayName(), Optional.of(coordinate),
                    Optional.empty(), Optional.empty(), headings));
        }
        knowledgeBases.sort(Comparator.comparing((Node node) -> node.label().toLowerCase(Locale.ROOT))
                .thenComparing(node -> node.knowledgeBase().map(Coordinate::toString).orElse("")));
        return List.copyOf(knowledgeBases);
    }

    /**
     * What a knowledge base was built from, named as a person reads it: a described knowledge base by its
     * display name, any other artifact by its artifactId, a file by its name.
     *
     * @param input     the input
     * @param described the knowledge bases the catalog describes
     * @return the node
     */
    static Node input(KnowledgeBaseDescriptor.Input input, Map<Coordinate, NexusCatalog.Described> described) {
        Optional<Map.Entry<Coordinate, NexusCatalog.Described>> known = described.entrySet().stream()
                .filter(entry -> input.of(entry.getKey())).findFirst();
        String label = known.map(entry -> entry.getValue().descriptor().displayName())
                .or(() -> input.coordinate().map(Coordinate::artifactId))
                .or(input::file)
                .orElse(input.reference());
        return new Node(Kind.INPUT, label, known.map(Map.Entry::getKey).or(input::coordinate), Optional.empty(),
                Optional.of(input), List.of());
    }

    /**
     * How an input is described beside its name: its role and classifier, such as
     * {@code protobuf, unreasoned} or {@code source, read by snomed-rf2}.
     *
     * @param input the input
     * @return the description
     */
    public static String describe(KnowledgeBaseDescriptor.Input input) {
        return switch (input.role()) {
            case "PB", "STORE_SEED" -> input.classifier().map(NexusCatalog::label).orElse("protobuf");
            case "CHANGESET" -> "change sets";
            case "SOURCE" -> "source" + input.importer().map(importer -> ", read by " + importer).orElse("");
            default -> input.role().toLowerCase(Locale.ROOT);
        };
    }

    private static void heading(List<Node> headings, String label, List<Node> children) {
        if (!children.isEmpty()) {
            headings.add(new Node(Kind.HEADING, label, Optional.empty(), Optional.empty(), Optional.empty(), children));
        }
    }

    private static Node offer(NexusCatalog.Offer offer) {
        return new Node(Kind.OFFER, offer.label(), Optional.of(offer.coordinate()), Optional.of(offer), Optional.empty(),
                List.of());
    }
}
