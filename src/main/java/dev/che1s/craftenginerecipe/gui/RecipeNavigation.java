package dev.che1s.craftenginerecipe.gui;

import org.bukkit.event.inventory.ClickType;

/** 供 CraftEngine 原生、来源插件自有以及 CER 箱子配方页面共用。 */
enum RecipeNavigation {
    BACK, HOME;

    static RecipeNavigation from(ClickType click) {
        return switch (click) {
            case LEFT, SHIFT_LEFT, WINDOW_BORDER_LEFT -> BACK;
            case RIGHT, SHIFT_RIGHT, WINDOW_BORDER_RIGHT -> HOME;
            default -> null;
        };
    }
}
