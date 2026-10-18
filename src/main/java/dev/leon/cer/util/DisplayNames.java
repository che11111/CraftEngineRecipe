package dev.leon.cer.util;

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

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Map;

public final class DisplayNames {

    private static final Pattern TAG = Pattern.compile("<[^>]*>");
    private static final Pattern L10N = Pattern.compile("<l10n:([^>]+)>");

    private DisplayNames() {
    }

    public static String of(ItemDefinition definition) {
        return of(definition, Map.of());
    }

    public static String of(ItemDefinition definition, Map<String, String> translations) {
        if (definition == null) {
            return null;
        }
        String custom = findProcessor(definition, true, translations);
        if (notBlank(custom)) {
            return custom;
        }
        String itemName = findProcessor(definition, false, translations);
        if (notBlank(itemName)) {
            return itemName;
        }
        String key = translationKeyOf(definition);
        return key == null ? null : translations.get(key);
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
                    return strip(cnp.customName(), translations);
                }
                if (!(processor instanceof ItemNameProcessor)) continue;
                ItemNameProcessor inp = (ItemNameProcessor) processor;
                return strip(inp.itemName(), translations);
            } catch (RuntimeException | LinkageError error) {
                CompatibilityWarnings.warn("DisplayNames.nameProcessor", error);
            }
        }
        return null;
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
        String expanded = expandL10n(miniMessage, translations);
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
        return expandL10n(raw, Map.of());
    }

    private static String expandL10n(String raw, Map<String, String> translations) {
        if (raw == null || raw.indexOf("<l10n:") < 0) {
            return raw;
        }
        Matcher m = L10N.matcher(raw);
        StringBuilder sb = new StringBuilder(raw.length());
        int last = 0;
        while (m.find()) {
            sb.append(raw, last, m.start());
            String key = m.group(1);
            try {
                String text = TranslationManager.instance().miniMessageTranslation(key);
                sb.append(text == null || text.equals(key) ? translations.getOrDefault(key, key) : text);
            } catch (RuntimeException | LinkageError error) {
                CompatibilityWarnings.warn("DisplayNames.localizedText", error);
                sb.append(translations.getOrDefault(key, key));
            }
            last = m.end();
        }
        sb.append(raw.substring(last));
        return sb.toString();
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
                sb.append(notBlank(rendered) ? rendered : translatable.key());
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
