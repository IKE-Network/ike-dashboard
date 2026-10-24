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

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * Reads a snapshot version's {@code maven-metadata.xml}: the newest deploy of one classifier and
 * extension, from {@code <snapshotVersions>}.
 */
public final class SnapshotMetadata {

    private static final DateTimeFormatter UPDATED = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private SnapshotMetadata() {
    }

    /**
     * One deploy of a classified file.
     *
     * @param value   the timestamped version, such as {@code 20250627.1-20261024.001942-7}
     * @param updated when it was deployed
     */
    public record Deploy(String value, Instant updated) {
    }

    /**
     * The newest deploy of a classifier and extension.
     *
     * @param metadata   the version's {@code maven-metadata.xml}
     * @param classifier the classifier, such as {@code rkb}
     * @param extension  the extension, such as {@code zip}
     * @return the deploy, or empty when the metadata lists none for them
     * @throws IOException if the metadata cannot be read or is not XML
     */
    public static Optional<Deploy> newest(InputStream metadata, String classifier, String extension)
            throws IOException {
        Document document;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            document = factory.newDocumentBuilder().parse(metadata);
        } catch (SAXException | ParserConfigurationException e) {
            throw new IOException("Not maven-metadata.xml: " + e.getMessage(), e);
        }
        NodeList versions = document.getElementsByTagName("snapshotVersion");
        Optional<Deploy> newest = Optional.empty();
        for (int i = 0; i < versions.getLength(); i++) {
            Element version = (Element) versions.item(i);
            if (!classifier.equals(text(version, "classifier")) || !extension.equals(text(version, "extension"))) {
                continue;
            }
            Optional<Instant> updated = updated(text(version, "updated"));
            String value = text(version, "value");
            if (value.isEmpty() || updated.isEmpty()) {
                continue;
            }
            Deploy deploy = new Deploy(value, updated.get());
            if (newest.isEmpty() || deploy.updated().isAfter(newest.get().updated())) {
                newest = Optional.of(deploy);
            }
        }
        return newest;
    }

    private static Optional<Instant> updated(String text) {
        try {
            return Optional.of(LocalDateTime.parse(text, UPDATED).toInstant(ZoneOffset.UTC));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    private static String text(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagName(tag);
        return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent().trim();
    }
}
