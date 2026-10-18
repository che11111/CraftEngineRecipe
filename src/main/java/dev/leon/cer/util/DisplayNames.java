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

public final class DisplayNames {

    private static final Pattern TAG = Pattern.compile("<[^>]*>");
    private static final Pattern L10N = Pattern.compile("<l10n:([^>]+)>");

    private DisplayNames() {
    }

    public static String of(ItemDefinition definition) {
        if (definition == null) {
            return null;
        }
        String custom = findProcessor(definition, true);
        if (notBlank(custom)) {
            return custom;
        }
        String itemName = findProcessor(definition, false);
        if (notBlank(itemName)) {
            return itemName;
        }
        return null;
    }

    private static String findProcessor(ItemDefinition definition, boolean custom) {
        ItemProcessor[] processors;
        try {
            processors = definition.processors();
        } catch (Throwable ignored) {
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
                    return strip(cnp.customName());
                }
                if (!(processor instanceof ItemNameProcessor)) continue;
                ItemNameProcessor inp = (ItemNameProcessor) processor;
                return strip(inp.itemName());
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    public static String of(Category category) {
        if (category == null) {
            return null;
        }
        String raw;
        try {
            raw = category.displayName();
        } catch (Throwable ignored) {
            return null;
        }
        String plain = strip(raw);
        if (notBlank(plain)) {
            return plain;
        }
        try {
            return category.id().value();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String strip(String miniMessage) {
        if (miniMessage == null || miniMessage.isBlank()) {
            return null;
        }
        String expanded = expandL10n(miniMessage);
        String text = null;
        try {
            text = plain(AdventureHelper.miniMessage().deserialize(expanded), 0);
        } catch (Throwable ignored) {
        }
        if (text == null || text.isBlank() || TAG.matcher(text).find()) {
            text = TAG.matcher(expanded).replaceAll("");
        }
        return text.isBlank() ? null : text;
    }

    public static String expandL10n(String raw) {
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
                sb.append(text == null ? key : text);
            } catch (Throwable ignored) {
                sb.append(key);
            }
            last = m.end();
        }
        sb.append(raw.substring(last));
        return sb.toString();
    }

    private static String plain(Component component, int depth) {
        if (component == null || depth > 8) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (component instanceof TextComponent) {
            TextComponent text = (TextComponent) component;
            sb.append(text.content());
        } else if (component instanceof TranslatableComponent) {
            TranslatableComponent translatable = (TranslatableComponent) component;
            try {
                sb.append(plain(TranslationManager.instance().render(translatable), depth + 1));
            } catch (Throwable ignored) {
                sb.append(translatable.key());
            }
        }
        for (Component child : component.children()) {
            sb.append(plain(child, depth + 1));
        }
        return sb.toString();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
