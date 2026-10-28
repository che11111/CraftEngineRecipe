package dev.che1s.craftenginerecipe.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 一段可按语言切换的 MiniMessage 文本。
 *
 * <p>语言键使用 Minecraft 常见写法，例如 {@code zh_CN}、{@code en_US}。
 * 未找到当前语言时依次尝试同语言的地区变体、{@code en_US}、通配键 {@code *}
 * 和提交顺序中的第一个非空文本。</p>
 */
public record LocalizedText(Map<String, String> translations) {
    public LocalizedText {
        Objects.requireNonNull(translations, "translations");
        // 使用 LinkedHashMap 保留提交顺序：所有按语言匹配都失败时，第一个非空文本兜底。
        Map<String, String> copy = new LinkedHashMap<>();
        translations.forEach((locale, text) -> {
            if (locale == null || locale.isBlank()) {
                throw new IllegalArgumentException("translation locale must not be blank");
            }
            if (text != null) copy.put(normalizeKey(locale), text);
        });
        translations = Collections.unmodifiableMap(copy);
    }

    public static LocalizedText plain(String text) {
        return new LocalizedText(Map.of("*", Objects.requireNonNull(text, "text")));
    }

    public static LocalizedText of(String locale, String text) {
        return new LocalizedText(Map.of(locale, Objects.requireNonNull(text, "text")));
    }

    public static LocalizedText of(Map<String, String> translations) {
        return new LocalizedText(translations);
    }

    public boolean isEmpty() {
        return this.translations.values().stream().allMatch(String::isBlank);
    }

    /** 使用 CER 当前界面语言解析；文本允许 MiniMessage。 */
    public String resolve(String locale) {
        if (this.translations.isEmpty()) return null;
        String requested = normalizeKey(locale);
        // 先要求精确地区匹配，例如 zh_CN 不应被另一个中文地区抢先覆盖。
        String exact = find(requested);
        if (exact != null) return exact;

        // 再接受同语言族文本，使 zh_CN 可以回退到 zh 或 zh_TW。
        int separator = requested.indexOf('_');
        String language = separator < 0 ? requested : requested.substring(0, separator);
        String sameLanguage = this.translations.entrySet().stream()
                .filter(entry -> entry.getKey().equals(language)
                        || entry.getKey().startsWith(language + "_"))
                .map(Map.Entry::getValue)
                .filter(text -> !text.isBlank())
                .findFirst()
                .orElse(null);
        if (sameLanguage != null) return sameLanguage;

        // 最后依次使用英文和通配文本；仍无匹配时由首个非空提交项兜底。
        for (String fallback : new String[]{"en_US", "en", "*"}) {
            String value = find(fallback);
            if (value != null) return value;
        }
        return this.translations.values().stream()
                .filter(text -> !text.isBlank())
                .findFirst()
                .orElse(null);
    }

    private String find(String normalizedLocale) {
        for (Map.Entry<String, String> entry : this.translations.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(normalizedLocale) && !entry.getValue().isBlank()) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static String normalizeKey(String locale) {
        // API 接受 zh-CN 等常见写法，内部统一成与 Minecraft 配置一致的下划线形式。
        return locale == null ? "" : locale.trim().replace('-', '_').toLowerCase(Locale.ROOT);
    }
}
