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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * One knowledge base and every copy of it in {@code ~/Solor}: its versions, its layouts, and the
 * change-set zips it was loaded from. The dashboard shows a family as one row, its newest copy Komet
 * can open in bold, and the rest folded beneath it.
 *
 * @param name    the knowledge base's name: the artifact a store's descriptor names, or its folder name
 *                without copy counters, dates, build stamps and layout suffixes
 * @param members every copy, newest first
 */
public record KnowledgeBaseFamily(String name, List<KnowledgeBase> members) {

    /**
     * Some folder-name suffixes say which copy, not which knowledge base: a copy counter, a date, a
     * build stamp, a layout or a classifier. They are taken off, repeatedly, to find the family.
     */
    private static final List<Pattern> COPY_SUFFIXES = List.of(
            Pattern.compile("\\.(zip|rkb)$"),
            Pattern.compile("-(reasoned|unreasoned)-(pb|sa)$"),
            Pattern.compile("-(pb|sa|rkb)$"),
            Pattern.compile("-\\d+-\\d{8}[.-]\\d{6}(-\\d+)?$"),
            Pattern.compile("-\\d{4}-\\d{2}-\\d{2}$"),
            Pattern.compile("-\\d{1,2}-\\d{1,2}-\\d{4}$"),
            Pattern.compile("[- ](64|32)bit$"),
            Pattern.compile("[- ]migration$"),
            Pattern.compile("[- ]rocks$", Pattern.CASE_INSENSITIVE),
            Pattern.compile("-\\d{1,2}$"));

    /**
     * Copies the members.
     *
     * @param name    the knowledge base's name
     * @param members every copy, newest first
     */
    public KnowledgeBaseFamily {
        members = List.copyOf(members);
    }

    /**
     * The copy Open uses: the newest Komet can open.
     *
     * @return the newest openable copy, or empty when the family has only change-set zips or folders
     *         no provider lists
     */
    public Optional<KnowledgeBase> latest() {
        return members.stream().filter(member -> member.kind().opensInKomet()).findFirst();
    }

    /**
     * The newest copy that says which build it is, the one the newer-build check asks about.
     *
     * @return the newest identified openable copy
     */
    public Optional<KnowledgeBase> latestIdentified() {
        return members.stream().filter(member -> member.kind().opensInKomet() && member.identified()).findFirst();
    }

    /**
     * The knowledge base's icon: the one the newest copy that carries one names.
     *
     * @return the icon file, or empty when no copy carries one
     */
    public Optional<Path> icon() {
        return members.stream().map(KnowledgeBase::icon).flatMap(Optional::stream).findFirst();
    }

    /**
     * Groups knowledge bases into families, sorted by name; each family's copies newest first. Folders no
     * provider would list are left out: they are not knowledge bases.
     *
     * @param knowledgeBases the knowledge bases
     * @return the families
     */
    public static List<KnowledgeBaseFamily> group(List<KnowledgeBase> knowledgeBases) {
        Map<String, List<KnowledgeBase>> byKey = new LinkedHashMap<>();
        Map<String, String> names = new LinkedHashMap<>();
        for (KnowledgeBase knowledgeBase : knowledgeBases) {
            if (knowledgeBase.kind() == StoreKind.UNKNOWN) {
                continue;
            }
            String name = familyName(knowledgeBase);
            String key = name.toLowerCase(Locale.ROOT);
            byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(knowledgeBase);
            names.putIfAbsent(key, name);
        }
        List<KnowledgeBaseFamily> families = new ArrayList<>();
        byKey.forEach((key, members) -> {
            members.sort(Comparator.comparing(KnowledgeBase::recency).reversed().thenComparing(KnowledgeBase::name));
            families.add(new KnowledgeBaseFamily(names.get(key), members));
        });
        families.sort(Comparator.comparing(family -> family.name().toLowerCase(Locale.ROOT)));
        return List.copyOf(families);
    }

    /**
     * The family a knowledge base belongs to: the artifact its descriptor names, or else its name with
     * the copy suffixes taken off.
     *
     * @param knowledgeBase the knowledge base
     * @return the family's name
     */
    static String familyName(KnowledgeBase knowledgeBase) {
        return knowledgeBase.coordinate().map(Coordinate::artifactId).orElseGet(() -> familyName(knowledgeBase.name()));
    }

    /**
     * A folder or file name with its copy suffixes taken off.
     *
     * @param name the name
     * @return the family's name; the name itself when nothing is left
     */
    static String familyName(String name) {
        String stripped = name.startsWith("*") ? name.substring(1) : name;
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Pattern suffix : COPY_SUFFIXES) {
                String shorter = suffix.matcher(stripped).replaceFirst("");
                if (!shorter.equals(stripped) && !shorter.isBlank()) {
                    stripped = shorter;
                    changed = true;
                }
            }
        }
        return stripped;
    }
}
