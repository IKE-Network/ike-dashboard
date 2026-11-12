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

import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * A project that builds a knowledge base, as it is on this machine: a folder with a Maven POM at its
 * root, read for what the dashboard shows of it (IKE-Network/ike-issues#1370). It is kept apart from the
 * knowledge base it builds: projects are in the projects folder, and the knowledge bases Komet opens are
 * in {@code ~/Solor} (KEC, 2026-11-12). Only the POM and the build's output folder are read; nothing is
 * run, and a POM that cannot be read still names a project, by its folder.
 *
 * @param folder      the project's folder
 * @param artifactId  the name its POM gives it, or the folder's name when the POM cannot be read
 * @param groupId     its group, its parent's when it names none
 * @param version     its version
 * @param displayName the name a person reads, the POM's {@code <name>}
 * @param collection  the collection its knowledge base is listed under
 * @param layers      the protobuf layers it is built from, in load order, each at the version it is fixed to
 * @param deploysTo   the repository a deploy of its version goes to
 * @param built       when it last built the protobuf it publishes, when it has
 */
public record KnowledgeBaseProjectFolder(Path folder, String artifactId, Optional<String> groupId, Optional<String> version,
                                         Optional<String> displayName, Optional<String> collection,
                                         List<KnowledgeBaseProject.Layer> layers, Optional<Repository> deploysTo,
                                         Optional<Instant> built) {

    /**
     * Where a deploy goes.
     *
     * @param id  the {@code settings.xml} server whose login reaches it
     * @param url the repository
     */
    public record Repository(String id, String url) {
    }

    /**
     * Copies the layers.
     *
     * @param folder      the project's folder
     * @param artifactId  its name
     * @param groupId     its group
     * @param version     its version
     * @param displayName the name a person reads
     * @param collection  its collection
     * @param layers      the layers it is built from
     * @param deploysTo   where a deploy goes
     * @param built       when it last built
     */
    public KnowledgeBaseProjectFolder {
        layers = List.copyOf(layers);
    }

    /**
     * Whether a folder is a project, not a knowledge base: it has a POM at its root, which no knowledge
     * base Komet opens has.
     *
     * @param folder the folder
     * @return whether it is a project
     */
    public static boolean isProject(Path folder) {
        return Files.isRegularFile(folder.resolve("pom.xml"));
    }

    /**
     * The projects in the projects folder.
     *
     * @param projects the projects folder
     * @return each project, by folder name; none when the folder is not there yet
     * @throws IOException if the folder cannot be listed
     */
    public static List<KnowledgeBaseProjectFolder> scan(Path projects) throws IOException {
        if (!Files.isDirectory(projects)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(projects)) {
            return entries.filter(Files::isDirectory).filter(KnowledgeBaseProjectFolder::isProject)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .map(KnowledgeBaseProjectFolder::read)
                    .toList();
        }
    }

    /**
     * Reads one project.
     *
     * @param folder its folder
     * @return what its POM says of it
     */
    public static KnowledgeBaseProjectFolder read(Path folder) {
        String folderName = folder.getFileName().toString();
        Element project;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            project = factory.newDocumentBuilder().parse(folder.resolve("pom.xml").toFile()).getDocumentElement();
        } catch (Exception unreadable) {
            return new KnowledgeBaseProjectFolder(folder, folderName, Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), List.of(), Optional.empty(), Optional.empty());
        }
        Map<String, String> properties = new HashMap<>();
        child(project, "properties").ifPresent(element -> children(element)
                .forEach(property -> properties.put(property.getNodeName(), property.getTextContent().strip())));
        String artifactId = text(project, "artifactId").orElse(folderName);
        Optional<String> groupId = text(project, "groupId").or(() -> child(project, "parent").flatMap(parent -> text(parent, "groupId")));
        Optional<String> version = text(project, "version").or(() -> child(project, "parent").flatMap(parent -> text(parent, "version")));
        List<KnowledgeBaseProject.Layer> layers = new ArrayList<>();
        child(project, "dependencies").ifPresent(dependencies -> {
            for (Element dependency : children(dependencies)) {
                Optional<String> classifier = text(dependency, "classifier").filter(NexusCatalog.PROTOBUF_CLASSIFIERS::contains);
                if (classifier.isEmpty() || !"zip".equals(text(dependency, "type").orElse(""))) {
                    continue;
                }
                layers.add(new KnowledgeBaseProject.Layer(text(dependency, "groupId").orElse(""),
                        text(dependency, "artifactId").orElse(""), classifier.get(),
                        resolve(text(dependency, "version").orElse(""), properties)));
            }
        });
        boolean snapshot = version.filter(text -> text.endsWith("-SNAPSHOT")).isPresent();
        Optional<Repository> deploysTo = child(project, "distributionManagement")
                .flatMap(management -> child(management, snapshot ? "snapshotRepository" : "repository"))
                .flatMap(repository -> text(repository, "url").map(url -> new Repository(text(repository, "id").orElse(""), url)));
        return new KnowledgeBaseProjectFolder(folder, artifactId, groupId, version, text(project, "name"),
                Optional.ofNullable(properties.get("ike.kbAssemble.collection")).filter(text -> !text.isEmpty()),
                layers, deploysTo, version.flatMap(text -> built(folder, artifactId, text)));
    }

    /** When the protobuf the project publishes was last written by a build. */
    private static Optional<Instant> built(Path folder, String artifactId, String version) {
        for (String classifier : NexusCatalog.PROTOBUF_CLASSIFIERS) {
            Path protobuf = folder.resolve("target").resolve(artifactId + "-" + version + "-" + classifier + ".zip");
            if (Files.isRegularFile(protobuf)) {
                try {
                    return Optional.of(Files.getLastModifiedTime(protobuf).toInstant());
                } catch (IOException unreadable) {
                    return Optional.empty();
                }
            }
        }
        return Optional.empty();
    }

    /** A version as written, with a {@code ${property}} the POM itself defines replaced by its value. */
    private static String resolve(String version, Map<String, String> properties) {
        if (version.startsWith("${") && version.endsWith("}")) {
            return properties.getOrDefault(version.substring(2, version.length() - 1), version);
        }
        return version;
    }

    private static List<Element> children(Element parent) {
        List<Element> children = new ArrayList<>();
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element) {
                children.add(element);
            }
        }
        return children;
    }

    private static Optional<Element> child(Element parent, String name) {
        return children(parent).stream().filter(element -> name.equals(element.getNodeName())).findFirst();
    }

    private static Optional<String> text(Element parent, String name) {
        return child(parent, name).map(element -> element.getTextContent().strip()).filter(text -> !text.isEmpty());
    }

    /**
     * The name a person reads: the POM's, else the artifactId.
     *
     * @return the name
     */
    public String title() {
        return displayName.orElse(artifactId);
    }

    /**
     * Whether the project builds a knowledge base: it is built from protobuf layers.
     *
     * @return whether it has layers
     */
    public boolean buildsKnowledgeBase() {
        return !layers.isEmpty();
    }

    /**
     * The project's coordinates, as far as its POM gives them.
     *
     * @return such as {@code network.ike.data:gene-kb:1-SNAPSHOT}
     */
    public String coordinates() {
        return groupId.map(group -> group + ":").orElse("") + artifactId + version.map(text -> ":" + text).orElse("");
    }
}
