package dev.che1s.craftenginerecipe.category;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Pure YAML mapping conversion for categories.yml and categories-unapplied.yml. */
final class CategoryNamespaceCodec {
    private CategoryNamespaceCodec() {
    }

    static CategoryNamespaces.NamespaceEntry namespace(Map<?, ?> raw) {
        String id = string(raw.get("id"));
        if (id.isBlank()) return null;
        return new CategoryNamespaces.NamespaceEntry(
                id,
                visibility(string(raw.get("visibility")), CategoryNamespaces.Visibility.ADMIN),
                string(raw.get("permission")),
                bool(raw.get("category-entry"), true),
                LocalizedCategoryText.text(raw.get("icon-name")),
                LocalizedCategoryText.lines(raw.get("item-lore")),
                LocalizedCategoryText.lines(raw.get("icon-lore")),
                LocalizedCategoryText.lines(raw.get("sub-icon-lore")),
                bool(raw.get("sort-by-namespace"), false),
                bool(raw.get("hide-items-without-recipes"), false),
                hiddenItems(raw.get("hidden-items")),
                hiddenCategories(raw.get("hidden-categories")),
                bool(raw.get("hidden-rules-affect-workstations"), true),
                bool(raw.get("hide-inaccessible"), false),
                string(raw.get("cer-managed-by")).trim());
    }

    static CategoryNamespaces.ButtonEntry button(Map<?, ?> raw) {
        if (!(raw.get("display") instanceof Map<?, ?> display)) return null;
        String material = string(display.get("material"));
        LocalizedCategoryText name = LocalizedCategoryText.text(display.get("name"));
        LocalizedCategoryText lore = LocalizedCategoryText.lines(display.get("lore"));
        String rawVisibility = string(raw.get("visibility")).trim();
        CategoryNamespaces.Visibility visibility = rawVisibility.isEmpty()
                ? CategoryNamespaces.Visibility.PLAYER
                : visibility(rawVisibility, CategoryNamespaces.Visibility.ADMIN);
        return new CategoryNamespaces.ButtonEntry(
                new CategoryNamespaces.ButtonDisplay(material, name, lore),
                actions(raw.get("actions")),
                visibility,
                string(raw.get("permission")),
                bool(raw.get("hide-inaccessible"), false));
    }

    static Map<String, Object> namespaceMap(CategoryNamespaces.NamespaceEntry namespace) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", namespace.id());
        map.put("visibility", namespace.visibility().name().toLowerCase(Locale.ROOT));
        map.put("permission", namespace.permission() == null ? "" : namespace.permission());
        if (!namespace.categoryEntry()) map.put("category-entry", false);
        if (!namespace.iconName().isBlank()) map.put("icon-name", namespace.iconName().yamlValue());
        if (!namespace.itemLore().isBlank()) map.put("item-lore", namespace.itemLore().yamlValue());
        if (!namespace.iconLore().isBlank()) map.put("icon-lore", namespace.iconLore().yamlValue());
        if (!namespace.subIconLore().isBlank()) {
            map.put("sub-icon-lore", namespace.subIconLore().yamlValue());
        }
        if (namespace.sortByNamespace()) map.put("sort-by-namespace", true);
        if (namespace.hideItemsWithoutRecipes()) map.put("hide-items-without-recipes", true);
        if (!namespace.hiddenItems().isEmpty()) {
            map.put("hidden-items", hiddenItemsList(namespace.hiddenItems()));
        }
        if (!namespace.hiddenCategories().isEmpty()) {
            map.put("hidden-categories", new ArrayList<>(namespace.hiddenCategories()));
        }
        if (!namespace.hiddenRulesAffectWorkstations()) {
            map.put("hidden-rules-affect-workstations", false);
        }
        if (namespace.hideInaccessible()) map.put("hide-inaccessible", true);
        if (!namespace.managedBy().isBlank()) {
            map.put("cer-managed-by", namespace.managedBy());
        }
        return map;
    }

    static Map<String, Object> buttonMap(CategoryNamespaces.ButtonEntry button) {
        Map<String, Object> map = new LinkedHashMap<>();
        Map<String, Object> display = new LinkedHashMap<>();
        display.put("material", button.display().material());
        display.put("name", button.display().name().yamlValue());
        if (!button.display().lore().isBlank()) {
            display.put("lore", button.display().lore().yamlValue());
        }
        map.put("display", display);
        if (button.visibility() != CategoryNamespaces.Visibility.PLAYER) {
            map.put("visibility", button.visibility().name().toLowerCase(Locale.ROOT));
        }
        if (!blank(button.permission())) map.put("permission", button.permission());
        if (button.hideInaccessible()) map.put("hide-inaccessible", true);
        if (!button.actions().isEmpty()) {
            Map<String, Object> actions = new LinkedHashMap<>();
            button.actions().forEach((key, values) -> actions.put(key, new ArrayList<>(values)));
            map.put("actions", actions);
        }
        return map;
    }

    private static Map<String, Set<String>> hiddenItems(Object value) {
        Map<String, Set<String>> rules = new LinkedHashMap<>();
        if (value instanceof List<?> hidden) {
            for (Object raw : hidden) {
                if (!(raw instanceof Map<?, ?> rule)) continue;
                String category = string(rule.get("category"));
                if (category.isBlank()) continue;
                Set<String> items = new LinkedHashSet<>();
                if (rule.get("items") instanceof List<?> configured) {
                    for (Object item : configured) {
                        String id = string(item);
                        if (!id.isBlank()) items.add(id);
                    }
                }
                if (!items.isEmpty()) {
                    rules.computeIfAbsent(category, ignored -> new LinkedHashSet<>()).addAll(items);
                }
            }
        }
        return rules;
    }

    private static Set<String> hiddenCategories(Object value) {
        Set<String> ids = new LinkedHashSet<>();
        for (String id : stringList(value)) {
            if (!id.isBlank()) ids.add(id);
        }
        return ids;
    }

    private static List<Map<String, Object>> hiddenItemsList(Map<String, Set<String>> rules) {
        List<Map<String, Object>> list = new ArrayList<>();
        rules.forEach((category, items) -> {
            Map<String, Object> rule = new LinkedHashMap<>();
            rule.put("category", category);
            rule.put("items", new ArrayList<>(items));
            list.add(rule);
        });
        return list;
    }

    private static Map<String, List<String>> actions(Object raw) {
        Map<String, List<String>> actions = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> map) {
            map.forEach((key, value) -> actions.put(string(key).toLowerCase(Locale.ROOT), stringList(value)));
        } else if (raw instanceof List<?>) {
            actions.put("all", stringList(raw));
        }
        return actions;
    }

    private static CategoryNamespaces.Visibility visibility(
            String value, CategoryNamespaces.Visibility fallback) {
        if (value == null) return fallback;
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "player", "all", "public" -> CategoryNamespaces.Visibility.PLAYER;
            case "permission", "perm" -> CategoryNamespaces.Visibility.PERMISSION;
            default -> fallback;
        };
    }

    private static List<String> stringList(Object value) {
        List<String> result = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) result.add(string(item));
        }
        return result;
    }

    private static boolean bool(Object value, boolean fallback) {
        if (value instanceof Boolean bool) return bool;
        String text = string(value).trim();
        if (text.isEmpty()) return fallback;
        if (text.equalsIgnoreCase("true")) return true;
        if (text.equalsIgnoreCase("false")) return false;
        return fallback;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
