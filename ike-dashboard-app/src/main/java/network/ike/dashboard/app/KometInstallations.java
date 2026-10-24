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
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Finds the Komet versions installed: application bundles in the folders given, and one level of
 * version folders below them, whose {@code Info.plist} names Komet's bundle identifier.
 */
public final class KometInstallations {

    private KometInstallations() {
    }

    /**
     * The folders Komet versions are looked for in: {@code /Applications}, {@code ~/Applications},
     * and the dashboard's own install location.
     *
     * @param home            the user's home folder
     * @param installLocation the dashboard's install location for Komet versions
     * @return the folders
     */
    public static List<Path> defaultRoots(Path home, Path installLocation) {
        return List.of(Path.of("/Applications"), home.resolve("Applications"), installLocation);
    }

    /**
     * The Komet versions in the folders, newest version first.
     *
     * @param roots the folders to look in
     * @return the installations
     * @throws IOException if a folder cannot be listed
     */
    public static List<KometInstallation> find(List<Path> roots) throws IOException {
        List<KometInstallation> found = new ArrayList<>();
        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> entries = Files.list(root)) {
                for (Path entry : entries.toList()) {
                    if (entry.getFileName().toString().endsWith(".app")) {
                        read(entry).ifPresent(found::add);
                    } else if (Files.isDirectory(entry)) {
                        try (Stream<Path> versions = Files.list(entry)) {
                            for (Path bundle : versions.filter(path -> path.getFileName().toString().endsWith(".app"))
                                    .toList()) {
                                read(bundle).ifPresent(found::add);
                            }
                        }
                    }
                }
            }
        }
        found.sort(Comparator.comparing(KometInstallation::version, KometInstallations::compareVersions).reversed()
                .thenComparing(KometInstallation::name));
        return List.copyOf(found);
    }

    /**
     * Reads one bundle.
     *
     * @param bundle an {@code .app} folder
     * @return the installation, or empty when the bundle is not Komet or cannot be read
     */
    static Optional<KometInstallation> read(Path bundle) {
        Path plist = bundle.resolve("Contents").resolve("Info.plist");
        if (!Files.isRegularFile(plist)) {
            return Optional.empty();
        }
        Map<String, String> keys;
        try (InputStream in = new java.io.BufferedInputStream(Files.newInputStream(plist))) {
            if (binary(in)) {
                // Komet's bundles carry an XML property list; a binary one is another application's.
                return Optional.empty();
            }
            keys = plistStrings(in);
        } catch (IOException | SAXException | ParserConfigurationException e) {
            return Optional.empty();
        }
        String executable = keys.get("CFBundleExecutable");
        if (!KometInstallation.BUNDLE_IDENTIFIER.equals(keys.get("CFBundleIdentifier")) || executable == null) {
            return Optional.empty();
        }
        String name = keys.getOrDefault("CFBundleName", executable);
        String fileName = bundle.getFileName().toString();
        if (fileName.endsWith(".app")) {
            name = fileName.substring(0, fileName.length() - ".app".length());
        }
        String version = keys.getOrDefault("CFBundleShortVersionString", keys.getOrDefault("CFBundleVersion", "?"));
        return Optional.of(new KometInstallation(name, version, bundle,
                bundle.resolve("Contents").resolve("MacOS").resolve(executable)));
    }

    /**
     * The string values of an XML property list's top-level dictionary, by key.
     *
     * @param plist the property list
     * @return each key with a string value
     * @throws IOException                  if it cannot be read
     * @throws SAXException                 if it is not XML
     * @throws ParserConfigurationException if no parser can be made
     */
    static Map<String, String> plistStrings(InputStream plist)
            throws IOException, SAXException, ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        // Info.plist names Apple's DTD; it is never fetched.
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        // Errors are thrown, not printed: the default handler writes "[Fatal Error]" to stderr.
        builder.setErrorHandler(null);
        Document document = builder.parse(plist);
        NodeList dicts = document.getElementsByTagName("dict");
        Map<String, String> strings = new HashMap<>();
        if (dicts.getLength() == 0) {
            return strings;
        }
        String key = null;
        for (Node node = dicts.item(0).getFirstChild(); node != null; node = node.getNextSibling()) {
            if (!(node instanceof Element element)) {
                continue;
            }
            if (element.getTagName().equals("key")) {
                key = element.getTextContent();
            } else {
                if (key != null && element.getTagName().equals("string")) {
                    strings.put(key, element.getTextContent());
                }
                key = null;
            }
        }
        return strings;
    }

    /**
     * Whether a property list is in Apple's binary form, which starts {@code bplist}. The stream must
     * support mark and reset; it is left where it was.
     *
     * @param plist the property list
     * @return {@code true} for a binary property list
     * @throws IOException if it cannot be read
     */
    static boolean binary(InputStream plist) throws IOException {
        plist.mark(6);
        byte[] head = plist.readNBytes(6);
        plist.reset();
        return new String(head, java.nio.charset.StandardCharsets.US_ASCII).equals("bplist");
    }

    /**
     * Orders dotted versions by their numeric parts, such as {@code 26.1016.346} before
     * {@code 26.1023.12}; a part that is not a number orders as text.
     *
     * @param a one version
     * @param b the other
     * @return their order
     */
    static int compareVersions(String a, String b) {
        String[] left = a.split("\\.");
        String[] right = b.split("\\.");
        for (int i = 0; i < Math.max(left.length, right.length); i++) {
            String l = i < left.length ? left[i] : "0";
            String r = i < right.length ? right[i] : "0";
            int order;
            try {
                order = Long.compare(Long.parseLong(l), Long.parseLong(r));
            } catch (NumberFormatException e) {
                order = l.compareTo(r);
            }
            if (order != 0) {
                return order;
            }
        }
        return 0;
    }
}
