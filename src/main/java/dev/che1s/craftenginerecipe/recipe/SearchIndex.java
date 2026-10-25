package dev.che1s.craftenginerecipe.recipe;

import dev.che1s.craftenginerecipe.util.DisplayNames;
import dev.che1s.craftenginerecipe.util.Pinyin;
import dev.che1s.craftenginerecipe.search.RankedMatches;
import net.momirealms.craftengine.core.item.ItemDefinition;
import net.momirealms.craftengine.core.util.Key;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

public final class SearchIndex {

    private final List<Entry> entries;
    private final List<Key> ordered;

    private SearchIndex(List<Entry> entries, List<Key> ordered) {
        this.entries = entries;
        this.ordered = ordered;
    }

    public static SearchIndex build(Map<Key, ItemDefinition> items) {
        LinkedHashMap<Key, String> names = new LinkedHashMap<>();
        LinkedHashMap<Key, String> keys = new LinkedHashMap<>();
        items.forEach((id, definition) -> {
            names.put(id, DisplayNames.of(definition));
            keys.put(id, DisplayNames.translationKeyOf(definition));
        });
        return fromNames(names, keys);
    }

    /** 工作线程只需要不可变 ID 和普通字符串。 */
    public static SearchIndex fromNames(Map<Key, String> names) {
        return fromNames(names, Map.of());
    }

    public static SearchIndex fromNames(Map<Key, String> names, Map<Key, String> translationKeys) {
        List<Entry> entries = new ArrayList<>(names.size());
        names.forEach((id, name) -> entries.add(buildEntry(id, name, translationKeys.get(id))));
        return new SearchIndex(List.copyOf(entries), List.copyOf(names.keySet()));
    }

    private static Entry buildEntry(Key id, String raw, String translationKey) {
        String idFull = normalize(id.toString());
        String idPath = normalize(id.value());
        String idWords = normalize(id.value().replace('_', ' ').replace('/', ' '));
        if (raw == null || raw.isBlank()) raw = id.value();
        String name = normalize(raw);
        String pinyinFull = "";
        String pinyinInitials = "";
        String pinyinChars = "";
        if (!name.isEmpty()) {
            pinyinFull = normalize(Pinyin.full(name));
            pinyinInitials = normalize(Pinyin.initials(name));
            pinyinChars = normalize(Pinyin.perCharJoined(name));
        }
        return new Entry(id, idFull, idPath, idWords, name, pinyinFull, pinyinInitials, pinyinChars,
                normalize(translationKey));
    }

    public List<Key> search(String keyword, int maxResults) {
        return this.search(keyword, maxResults, id -> true);
    }

    public List<Key> search(String keyword, int maxResults, Predicate<Key> allowed) {
        if (keyword == null || keyword.isBlank() || maxResults <= 0) return List.of();
        String kw = normalize(keyword.trim());
        String nsStripped = kw.contains(":") ? kw.substring(kw.indexOf(':') + 1) : kw;
        RankedMatches matches = new RankedMatches(maxResults);

        for (Entry entry : this.entries) {
            if (!allowed.test(entry.id)) continue;
            if (entry.idFull.equals(kw) || entry.idPath.equals(nsStripped)) {
                matches.add(0, entry.id);
                continue;
            }
            if (!entry.name.isEmpty() && entry.name.equals(nsStripped)) {
                matches.add(1, entry.id);
                continue;
            }
            if (startsWithAny(entry.name, entry.pinyinFull, entry.idWords, kw)) {
                matches.add(2, entry.id);
                continue;
            }
            if (!entry.pinyinInitials.isEmpty() && entry.pinyinInitials.contains(kw)) {
                matches.add(3, entry.id);
                continue;
            }
            if (containsAny(entry.name, entry.pinyinFull, entry.pinyinChars, entry.idWords, kw)) {
                matches.add(4, entry.id);
                continue;
            }
            if (!entry.translationKey.isEmpty() && (entry.translationKey.contains(kw)
                    || entry.translationKey.contains(nsStripped))) {
                matches.add(5, entry.id);
                continue;
            }
            if (!entry.idPath.contains(nsStripped) && !entry.idFull.contains(kw)) continue;
            matches.add(6, entry.id);
        }

        return matches.result();
    }

    public int size() {
        return this.ordered.size();
    }

    public List<Key> orderedIds() {
        return this.ordered;
    }

    public Map<Key, String> namesById() {
        LinkedHashMap<Key, String> map = new LinkedHashMap<>();
        for (Entry entry : this.entries) {
            if (entry.name.isEmpty()) continue;
            map.put(entry.id, entry.name);
        }
        return Collections.unmodifiableMap(map);
    }

    private static boolean startsWithAny(String a, String b, String c, String prefix) {
        return (!a.isEmpty() && a.startsWith(prefix))
                || (!b.isEmpty() && b.startsWith(prefix))
                || (!c.isEmpty() && c.startsWith(prefix));
    }

    private static boolean containsAny(String a, String b, String c, String d, String needle) {
        return (!a.isEmpty() && a.contains(needle))
                || (!b.isEmpty() && b.contains(needle))
                || (!c.isEmpty() && c.contains(needle))
                || (!d.isEmpty() && d.contains(needle));
    }

    private static String normalize(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    private record Entry(Key id, String idFull, String idPath, String idWords, String name, String pinyinFull,
                         String pinyinInitials, String pinyinChars, String translationKey) {
    }
}
