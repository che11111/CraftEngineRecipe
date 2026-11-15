package dev.che1s.craftenginerecipe.gui;

import dev.che1s.craftenginerecipe.i18n.LanguageManager;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Resolves explicit lang: references and known legacy defaults without rewriting user files. */
final class LocalizedConfigText {
    private static final Map<String, String> LEGACY_DEFAULTS;

    static {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("<white>原版物品</white>", "ui.default-categories.vanilla-name");
        values.put("<gray>点击查看所有原版物品</gray>", "ui.default-categories.vanilla-click");
        values.put("<dark_gray>物品数: <count></dark_gray>", "ui.default-categories.vanilla-count");
        values.put("&#B8C0CC一个分隔玻璃板", "ui.default-categories.divider-lore");
        values.put("一个分隔玻璃板", "ui.default-categories.divider-lore");
        values.put("&#D8B278打开「管理页面」", "ui.default-categories.manage-name");
        values.put("打开「管理页面」", "ui.default-categories.manage-name");
        values.put("&#B8C0CC在页面中会显示出当前服务器中CE的内容",
                "ui.default-categories.manage-lore-1");
        values.put("在页面中会显示出当前服务器中CE的内容", "ui.default-categories.manage-lore-1");
        values.put("&#B8C0CC管理员需要手动应用这些内容，并可以选择屏蔽，或者排序",
                "ui.default-categories.manage-lore-2");
        values.put("管理员需要手动应用这些内容，并可以选择屏蔽，或者排序",
                "ui.default-categories.manage-lore-2");
        values.put("&#B8C0CC通过/cer manage指令也可以进入", "ui.default-categories.manage-lore-3");
        values.put("通过/cer manage指令也可以进入", "ui.default-categories.manage-lore-3");
        values.put("&#B8C0CC此按钮仅管理员可见", "ui.default-categories.manage-lore-4");
        values.put("此按钮仅管理员可见", "ui.default-categories.manage-lore-4");
        values.put("&#B8C0CC可以试试将这个按钮隐藏/删掉？",
                "ui.default-categories.manage-lore-5");
        values.put("可以试试将这个按钮隐藏/删掉？", "ui.default-categories.manage-lore-5");
        values.put("&#D8B278打开「CER的适配插件页面」",
                "ui.default-categories.integrations-name");
        values.put("打开「CER的适配插件页面」", "ui.default-categories.integrations-name");
        values.put("&#B8C0CC在页面中会显示当前服务器中有哪些CER特殊适配内容",
                "ui.default-categories.integrations-lore-1");
        values.put("在页面中会显示当前服务器中有哪些CER特殊适配内容",
                "ui.default-categories.integrations-lore-1");
        values.put("&#B8C0CC需要管理员手动开启，适配内容方可工作",
                "ui.default-categories.integrations-lore-2");
        values.put("需要管理员手动开启，适配内容方可工作",
                "ui.default-categories.integrations-lore-2");
        values.put("&#B8C0CC通过/cer integrations指令也可以进入",
                "ui.default-categories.integrations-lore-3");
        values.put("通过/cer integrations指令也可以进入",
                "ui.default-categories.integrations-lore-3");
        values.put("&#B8C0CC可以试试将这个按钮隐藏/删掉？",
                "ui.default-categories.integrations-lore-5");
        LEGACY_DEFAULTS = Map.copyOf(values);
    }

    private LocalizedConfigText() {
    }

    static String resolve(LanguageManager.Localized language, String raw) {
        if (raw == null || raw.isBlank()) return raw;
        String trimmed = raw.trim();
        if (trimmed.startsWith("lang:")) {
            return language.text(trimmed.substring("lang:".length()));
        }
        String key = LEGACY_DEFAULTS.get(raw);
        return key == null ? raw : language.text(key, "count", "<count>");
    }

    static List<String> resolve(LanguageManager.Localized language, List<String> raw) {
        return raw == null ? List.of() : raw.stream()
                .map(value -> resolve(language, value)).toList();
    }
}
