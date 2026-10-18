package dev.leon.cer.gui;

import net.momirealms.craftengine.core.util.Key;
import org.bukkit.entity.Player;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.UUID;

public final class GuiSession {

    private static final Map<UUID, GuiSession> SESSIONS = new ConcurrentHashMap<>();

    private Key currentCategory;
    private int categoryPage;
    private Key parentCategory;
    private int recipeIndex;
    private Key recipeItem;
    private boolean recipeUsageMode;
    private boolean unapplied;

    private GuiSession() {
    }

    public static GuiSession of(Player player) {
        return SESSIONS.computeIfAbsent(player.getUniqueId(), k -> new GuiSession());
    }

    public static void clear(Player player) {
        SESSIONS.remove(player.getUniqueId());
    }

    public static void clearAll() {
        SESSIONS.clear();
    }

    public void enter(Key category, int categoryPage, Key parent) {
        this.currentCategory = category;
        this.categoryPage = categoryPage;
        this.parentCategory = parent;
    }

    public Key exit() {
        Key parent = this.parentCategory;
        this.currentCategory = parent;
        this.parentCategory = null;
        return parent;
    }

    public Key currentCategory() {
        return this.currentCategory;
    }

    public int categoryPage() {
        return this.categoryPage;
    }

    public void categoryPage(int page) {
        this.categoryPage = page;
    }

    public boolean unapplied() {
        return this.unapplied;
    }

    public void unapplied(boolean unapplied) {
        this.unapplied = unapplied;
    }

    public Key parentCategory() {
        return this.parentCategory;
    }

    public void recipeContext(Key item, int index, boolean usageMode) {
        this.recipeItem = item;
        this.recipeIndex = index;
        this.recipeUsageMode = usageMode;
    }

    public Key recipeItem() {
        return this.recipeItem;
    }

    public int recipeIndex() {
        return this.recipeIndex;
    }

    public boolean recipeUsageMode() {
        return this.recipeUsageMode;
    }
}
