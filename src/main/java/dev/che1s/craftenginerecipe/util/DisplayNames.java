package dev.che1s.craftenginerecipe.util;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.che1s.craftenginerecipe.CraftEngineRecipePlugin;
import net.momirealms.craftengine.core.item.ItemDefinition;
import net.momirealms.craftengine.core.item.processor.CustomNameProcessor;
import net.momirealms.craftengine.core.item.processor.ItemNameProcessor;
import net.momirealms.craftengine.core.item.processor.ItemProcessor;
import net.momirealms.craftengine.core.plugin.gui.category.Category;
import net.momirealms.craftengine.core.plugin.locale.TranslationManager;
import net.momirealms.craftengine.core.util.AdventureHelper;
import net.momirealms.craftengine.libraries.adventure.text.Component;
import net.momirealms.craftengine.libraries.adventure.text.TextComponent;
import net.momirealms.craftengine.libraries.adventure.text.TranslatableComponent;
import org.bukkit.entity.Player;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

public final class DisplayNames {

    private static final Pattern TAG = Pattern.compile("<[^>]*>");
    private static final Pattern LOCALIZED = Pattern.compile("<(?:l10n|lang):([^>]+)>");
    private static final Pattern PLUGIN_LOCALIZED =
            Pattern.compile("<cer-lang:([^>:]+):([^>:]+)(?::([^>]+))?>");

    private DisplayNames() {
    }

    public static String of(ItemDefinition definition) {
        return of(definition, Map.of());
    }

    public static String of(ItemDefinition definition, Map<String, String> translations) {
        if (definition == null) {
            return null;
        }
        String key = translationKeyOf(definition);
        String translated = key == null ? null : translations.get(key);
        if (notBlank(translated)) {
            return translated;
        }
        String custom = findProcessor(definition, true, translations);
        if (notBlank(custom)) {
            return custom;
        }
        String itemName = findProcessor(definition, false, translations);
        if (notBlank(itemName)) {
            return itemName;
        }
        return key == null ? null : translations.get(key);
    }

    /**
     * Resolves only translations supplied by the selected language map. Missing keys return null
     * so callers can preserve the original client-side translatable component instead of forcing
     * the server's English fallback.
     */
    public static String localizedOf(ItemDefinition definition, Map<String, String> translations) {
        if (definition == null) return null;
        String key = translationKeyOf(definition);
        if (key != null && notBlank(translations.get(key))) return translations.get(key);
        String raw = findProcessorRaw(definition, true);
        if (!notBlank(raw)) raw = findProcessorRaw(definition, false);
        return localizeConfigured(raw, translations);
    }

    public static String translationKeyOf(ItemDefinition definition) {
        if (definition == null) return null;
        try {
            String key = definition.translationKey();
            return notBlank(key) ? key : null;
        } catch (RuntimeException | LinkageError error) {
            CompatibilityWarnings.warn("DisplayNames.translationKey", error);
            return null;
        }
    }

    private static String findProcessor(ItemDefinition definition, boolean custom, Map<String, String> translations) {
        String raw = findProcessorRaw(definition, custom);
        return raw == null ? null : strip(raw, translations);
    }

    private static String findProcessorRaw(ItemDefinition definition, boolean custom) {
        ItemProcessor[] processors;
        try {
            processors = definition.processors();
        } catch (RuntimeException | LinkageError error) {
            CompatibilityWarnings.warn("DisplayNames.processors", error);
            return null;
        }
        if (processors == null) {
            return null;
        }
        for (ItemProcessor processor : processors) {
            try {
                if (custom) {
                    if (!(processor instanceof CustomNameProcessor)) continue;
                    CustomNameProcessor cnp = (CustomNameProcessor) processor;
                    return cnp.customName();
                }
                if (!(processor instanceof ItemNameProcessor)) continue;
                ItemNameProcessor inp = (ItemNameProcessor) processor;
                return inp.itemName();
            } catch (RuntimeException | LinkageError error) {
                CompatibilityWarnings.warn("DisplayNames.nameProcessor", error);
            }
        }
        return null;
    }

    private static String localizeConfigured(String raw, Map<String, String> translations) {
        if (!notBlank(raw)) return null;
        String plain = translatePlainKey(raw.trim(), translations);
        if (plain != null) return plain;
        Matcher matcher = LOCALIZED.matcher(raw);
        StringBuilder expanded = new StringBuilder(raw.length());
        int last = 0;
        while (matcher.find()) {
            String translated = translations.get(matcher.group(1));
            if (!notBlank(translated)) return null;
            expanded.append(raw, last, matcher.start()).append(translated);
            last = matcher.end();
        }
        expanded.append(raw.substring(last));
        String text = TAG.matcher(expanded).replaceAll("").trim();
        return text.isBlank() ? null : text;
    }

    public static String of(Category category) {
        return of(category, Map.of());
    }

    public static String of(Category category, Map<String, String> translations) {
        if (category == null) {
            return null;
        }
        String raw;
        try {
            raw = category.displayName();
        } catch (RuntimeException | LinkageError error) {
            CompatibilityWarnings.warn("DisplayNames.categoryName", error);
            return null;
        }
        String plain = strip(raw, translations);
        if (notBlank(plain)) {
            return plain;
        }
        try {
            return category.id().value();
        } catch (RuntimeException | LinkageError error) {
            CompatibilityWarnings.warn("DisplayNames.categoryId", error);
            return null;
        }
    }

    private static String strip(String miniMessage, Map<String, String> translations) {
        if (miniMessage == null || miniMessage.isBlank()) {
            return null;
        }
        String expanded = expandTranslations(miniMessage, translations);
        String text = null;
        try {
            text = plain(AdventureHelper.miniMessage().deserialize(expanded), translations, 0);
        } catch (RuntimeException | LinkageError error) {
            CompatibilityWarnings.warn("DisplayNames.miniMessage", error);
        }
        if (text == null || text.isBlank() || TAG.matcher(text).find()) {
            text = TAG.matcher(expanded).replaceAll("");
        }
        return text.isBlank() ? null : text;
    }

    public static String expandL10n(String raw) {
        return expandTranslations(raw, Map.of());
    }

    public static String resolveText(String raw, Map<String, String> translations) {
        return resolveText(raw, translations, null);
    }

    public static String resolveText(String raw, Map<String, String> translations, Player player) {
        if (raw == null) return null;
        raw = expandPluginTranslations(raw, player);
        String translated = translatePlainKey(raw.trim(), translations);
        if (translated != null) return translated;
        return looksLikeTranslationKey(raw) ? humanizeTranslationKey(raw) : raw;
    }

    public static String expandTranslations(String raw, Map<String, String> translations) {
        return expandTranslations(raw, translations, null);
    }

    public static String expandTranslations(String raw, Map<String, String> translations, Player player) {
        if (raw == null) return null;
        raw = expandPluginTranslations(raw, player);
        String plain = translatePlainKey(raw.trim(), translations);
        if (plain != null) return plain;
        if (raw.indexOf("<l10n:") < 0 && raw.indexOf("<lang:") < 0) {
            return looksLikeTranslationKey(raw) ? humanizeTranslationKey(raw) : raw;
        }
        Matcher m = LOCALIZED.matcher(raw);
        StringBuilder sb = new StringBuilder(raw.length());
        int last = 0;
        while (m.find()) {
            sb.append(raw, last, m.start());
            String key = m.group(1);
            String text = translation(key, translations);
            sb.append(text == null ? humanizeTranslationKey(key) : text);
            last = m.end();
        }
        sb.append(raw.substring(last));
        return sb.toString();
    }

    private static String translatePlainKey(String raw, Map<String, String> translations) {
        if (!looksLikeTranslationKey(raw)) return null;
        return translation(raw, translations);
    }

    public static boolean looksLikeTranslationKey(String raw) {
        return raw != null && !raw.isEmpty() && raw.indexOf(' ') < 0 && raw.indexOf('<') < 0
                && raw.matches("[A-Za-z0-9_.-]+") && raw.indexOf('.') >= 0;
    }

    private static String expandPluginTranslations(String raw, Player player) {
        return expandPluginTranslations(raw, player, 0);
    }

    private static String expandPluginTranslations(String raw, Player player, int depth) {
        if (raw == null || raw.indexOf("<cer-lang:") < 0) return raw;
        if (depth > 8) return raw;
        CraftEngineRecipePlugin plugin = CraftEngineRecipePlugin.get();
        if (plugin == null || plugin.lang() == null) return raw;
        Matcher matcher = PLUGIN_LOCALIZED.matcher(raw);
        StringBuilder result = new StringBuilder(raw.length());
        int last = 0;
        while (matcher.find()) {
            result.append(raw, last, matcher.start());
            String source = matcher.group(1);
            String key = matcher.group(2);
            Object[] arguments = deferredArguments(matcher.group(3));
            String value;
            try {
                value = player == null ? plugin.lang().integrationText(source, key, arguments)
                        : plugin.lang(player).integrationText(source, key, arguments);
            } catch (RuntimeException | LinkageError error) {
                CompatibilityWarnings.warn("DisplayNames.pluginLocalizedText", error);
                value = null;
            }
            result.append(value == null || value.isBlank() || value.equals(key)
                    ? humanizeTranslationKey(key) : value);
            last = matcher.end();
        }
        result.append(raw.substring(last));
        String expanded = result.toString();
        return expanded.indexOf("<cer-lang:") < 0
                ? expanded : expandPluginTranslations(expanded, player, depth + 1);
    }

    private static Object[] deferredArguments(String payload) {
        if (payload == null || payload.isBlank()) return new Object[0];
        try {
            JsonObject values = JsonParser.parseString(new String(
                    Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8)).getAsJsonObject();
            List<Object> arguments = new ArrayList<>(values.size() * 2);
            values.entrySet().forEach(entry -> {
                arguments.add(entry.getKey());
                arguments.add(entry.getValue().getAsString());
            });
            return arguments.toArray();
        } catch (RuntimeException ignored) {
            return new Object[0];
        }
    }

    public static String humanizeTranslationKey(String key) {
        if (key == null || key.isBlank()) return "";
        String[] parts = key.split("\\.");
        String value = parts.length == 0 ? key : parts[parts.length - 1];
        if (value.equalsIgnoreCase("category_title") && parts.length > 1) {
            value = parts[parts.length - 2];
        }
        return humanize(value);
    }

    public static String humanizeItemId(String id) {
        if (id == null || id.isBlank()) return "";
        String value = id;
        int namespace = value.indexOf(':');
        if (namespace >= 0 && namespace + 1 < value.length()) value = value.substring(namespace + 1);
        int slash = value.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < value.length()) value = value.substring(slash + 1);
        return humanize(value);
    }

    private static String humanize(String value) {
        String normalized = value.replace('_', ' ').replace('-', ' ').trim();
        StringBuilder result = new StringBuilder(normalized.length());
        boolean upper = true;
        for (int index = 0; index < normalized.length(); index++) {
            char character = normalized.charAt(index);
            if (Character.isWhitespace(character)) {
                result.append(character);
                upper = true;
            } else {
                result.append(upper ? Character.toUpperCase(character) : character);
                upper = false;
            }
        }
        return result.toString();
    }

    private static String translation(String key, Map<String, String> translations) {
        String bundled = translations.get(key);
        if (notBlank(bundled)) return bundled;
        try {
            String text = TranslationManager.instance().miniMessageTranslation(key);
            if (notBlank(text) && !text.equals(key)) return text;
        } catch (RuntimeException | LinkageError error) {
            CompatibilityWarnings.warn("DisplayNames.localizedText", error);
        }
        return null;
    }

    static String plain(Component component, Map<String, String> translations, int depth) {
        if (component == null || depth > 8) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (component instanceof TextComponent) {
            TextComponent text = (TextComponent) component;
            sb.append(text.content());
        } else if (component instanceof TranslatableComponent) {
            TranslatableComponent translatable = (TranslatableComponent) component;
            String translated = translations.get(translatable.key());
            if (notBlank(translated)) {
                sb.append(translated);
            } else {
                String rendered = null;
                try {
                    Component resolved = TranslationManager.instance().render(translatable);
                    if (!resolved.equals(translatable)) rendered = plain(resolved, translations, depth + 1);
                } catch (RuntimeException | LinkageError error) {
                    CompatibilityWarnings.warn("DisplayNames.translatableComponent", error);
                }
                sb.append(notBlank(rendered) ? rendered : humanizeTranslationKey(translatable.key()));
            }
        }
        for (Component child : component.children()) {
            sb.append(plain(child, translations, depth + 1));
        }
        return sb.toString();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
