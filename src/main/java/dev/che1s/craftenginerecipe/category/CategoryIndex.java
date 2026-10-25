package dev.che1s.craftenginerecipe.category;

import dev.che1s.craftenginerecipe.util.DisplayNames;
import dev.che1s.craftenginerecipe.util.Pinyin;
import dev.che1s.craftenginerecipe.search.RankedMatches;
import net.momirealms.craftengine.core.plugin.gui.category.Category;
import net.momirealms.craftengine.core.util.Key;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class CategoryIndex {

    private final List<Entry> entries;

    private CategoryIndex(List<Entry> entries) {
        this.entries = entries;
    }

    public static CategoryIndex build(Map<Key, Category> categories) {
        java.util.LinkedHashMap<Key, String> names = new java.util.LinkedHashMap<>();
        categories.forEach((id, category) -> names.put(id, DisplayNames.of(category)));
        return fromNames(names);
    }

    public static CategoryIndex fromNames(Map<Key, String> names) {
        List<Entry> entries = new ArrayList<>(names.size());
        for (Map.Entry<Key, String> e : names.entrySet()) {
            Key id = e.getKey();
            String name = normalize(e.getValue());
            String full = "";
            String initials = "";
            String chars = "";
            if (!name.isEmpty()) {
                full = normalize(Pinyin.full(name));
                initials = normalize(Pinyin.initials(name));
                chars = normalize(Pinyin.perCharJoined(name));
            }
            entries.add(new Entry(id, normalize(id.toString()), normalize(id.value()), name, full, initials, chars));
        }
        return new CategoryIndex(Collections.unmodifiableList(entries));
    }

    public List<Key> search(String keyword, int maxResults) {
        if (keyword == null || keyword.isBlank() || maxResults <= 0) {
            return List.of();
        }
        String kw = normalize(keyword.trim());
        String nsStripped = kw.contains(":") ? kw.substring(kw.indexOf(':') + 1) : kw;

        RankedMatches matches = new RankedMatches(maxResults);

        for (Entry entry : this.entries) {
            if (entry.idFull.equals(kw) || entry.idPath.equals(nsStripped)) {
                matches.add(0, entry.id);
                continue;
            }
            if (!entry.name.isEmpty() && entry.name.equals(nsStripped)) {
                matches.add(1, entry.id);
                continue;
            }
            if ((!entry.name.isEmpty() && entry.name.startsWith(kw)) || (!entry.pinyinFull.isEmpty() && entry.pinyinFull.startsWith(kw))) {
                matches.add(2, entry.id);
                continue;
            }
            if (!entry.pinyinInitials.isEmpty() && entry.pinyinInitials.startsWith(kw)) {
                matches.add(3, entry.id);
                continue;
            }
            if ((!entry.name.isEmpty() && entry.name.contains(kw))
                    || (!entry.pinyinFull.isEmpty() && entry.pinyinFull.contains(kw))
                    || (!entry.pinyinChars.isEmpty() && entry.pinyinChars.contains(kw))) {
                matches.add(4, entry.id);
                continue;
            }
            if (!entry.idPath.contains(nsStripped) && !entry.idFull.contains(kw)) continue;
            matches.add(5, entry.id);
        }

        return matches.result();
    }

    public int size() {
        return this.entries.size();
    }

    private static String normalize(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    private record Entry(Key id, String idFull, String idPath, String name, String pinyinFull, String pinyinInitials, String pinyinChars) {
    }
}
