package dev.che1s.craftenginerecipe.category;

import dev.che1s.craftenginerecipe.i18n.LanguageManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A category entry text value that can be plain or contain locale-specific branches.
 *
 * <p>Supported YAML forms are plain strings/lists and locale maps such as
 * {@code {zh_CN: "...", en_US: "..."}}. Maps use the player's selected locale,
 * then English, then the first configured fallback.</p>
 */
public final class LocalizedCategoryText {
    private static final String FALLBACK = "*";
    private final boolean multiline;
    private final String fallbackText;
    private final List<String> fallbackLines;
    private final Map<String, List<String>> locales;

    private LocalizedCategoryText(boolean multiline, String fallbackText, List<String> fallbackLines,
                                  Map<String, List<String>> locales) {
        this.multiline = multiline;
        this.fallbackText = fallbackText == null ? "" : fallbackText;
        this.fallbackLines = immutableLines(fallbackLines == null ? List.of() : fallbackLines);
        Map<String, List<String>> copied = new LinkedHashMap<>();
        locales.forEach((locale, lines) -> copied.put(locale, immutableLines(lines)));
        this.locales = Collections.unmodifiableMap(copied);
    }

    public static LocalizedCategoryText text(Object value) {
        if (value instanceof Map<?, ?> map) {
            return new LocalizedCategoryText(false, "", List.of(), localeMap(map));
        }
        return new LocalizedCategoryText(false, textValue(value), List.of(), Map.of());
    }

    public static LocalizedCategoryText lines(Object value) {
        if (value instanceof Map<?, ?> map) {
            return new LocalizedCategoryText(true, "", List.of(), localeMap(map));
        }
        return new LocalizedCategoryText(true, "", lineValues(value), Map.of());
    }

    public static LocalizedCategoryText emptyText() {
        return text("");
    }

    public static LocalizedCategoryText emptyLines() {
        return lines(List.of());
    }

    public String resolve(LanguageManager.Localized language) {
        List<String> resolved = this.resolveLines(language);
        return resolved.isEmpty() ? "" : resolved.getFirst();
    }

    public List<String> resolveLines(LanguageManager.Localized language) {
        String requested = language == null ? "en_US" : language.locale();
        List<String> selected = this.localized(requested);
        if (selected != null) return selected;
        String languageOnly = requested.split("_", 2)[0];
        selected = this.localized(languageOnly);
        if (selected != null) return selected;
        if (!"en".equalsIgnoreCase(languageOnly)) {
            selected = this.localized("en_US");
            if (selected != null) return selected;
        }
        selected = this.locales.get(FALLBACK);
        if (selected != null) return selected;
        if (!this.locales.isEmpty()) return this.locales.values().iterator().next();
        return this.multiline ? this.fallbackLines : List.of(this.fallbackText);
    }

    public boolean isBlank() {
        return this.locales.isEmpty()
                ? this.multiline ? this.fallbackLines.isEmpty() : this.fallbackText.isBlank()
                : this.locales.values().stream().allMatch(List::isEmpty);
    }

    public Object yamlValue() {
        if (this.locales.isEmpty()) {
            return this.multiline ? new ArrayList<>(this.fallbackLines) : this.fallbackText;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        this.locales.forEach((locale, lines) -> {
            if (FALLBACK.equals(locale)) {
                values.put("default", this.multiline ? new ArrayList<>(lines)
                        : lines.isEmpty() ? "" : lines.getFirst());
            } else {
                values.put(locale, this.multiline ? new ArrayList<>(lines)
                        : lines.isEmpty() ? "" : lines.getFirst());
            }
        });
        return values;
    }

    private List<String> localized(String locale) {
        String normalized = normalizeLocale(locale);
        if (normalized == null) return null;
        List<String> exact = this.locales.get(normalized);
        if (exact != null) return exact;
        String language = normalized.split("_", 2)[0];
        return this.locales.get(language);
    }

    private static Map<String, List<String>> localeMap(Map<?, ?> raw) {
        Map<String, List<String>> values = new LinkedHashMap<>();
        raw.forEach((key, value) -> {
            String locale = normalizeLocale(string(key));
            if (locale != null) values.put(locale, lineValues(value));
        });
        return values;
    }

    private static String normalizeLocale(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().replace('-', '_').toLowerCase(Locale.ROOT);
        if (normalized.equals("default") || normalized.equals("fallback") || normalized.equals("*")) {
            return FALLBACK;
        }
        if (normalized.equals("zh") || normalized.equals("zh_cn")) return "zh_CN";
        if (normalized.equals("en") || normalized.equals("en_us")) return "en_US";
        return normalized;
    }

    private static List<String> lineValues(Object value) {
        if (value instanceof List<?> list) {
            List<String> lines = new ArrayList<>(list.size());
            for (Object line : list) {
                if (line != null) lines.add(String.valueOf(line));
            }
            return List.copyOf(lines);
        }
        if (value == null) return List.of();
        String text = String.valueOf(value);
        return text.isEmpty() ? List.of() : List.of(text);
    }

    private static String textValue(Object value) {
        if (value instanceof List<?> list) {
            return list.isEmpty() || list.getFirst() == null ? "" : String.valueOf(list.getFirst());
        }
        return string(value);
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static List<String> immutableLines(List<String> values) {
        return Collections.unmodifiableList(new ArrayList<>(values));
    }
}
