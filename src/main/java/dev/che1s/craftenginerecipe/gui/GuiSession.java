package dev.che1s.craftenginerecipe.gui;

import dev.che1s.craftenginerecipe.data.WorkstationRecipes;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.item.recipe.RecipeType;
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
    private RecipeType workstation;
    private int workstationPage;
    private int workstationCategoryOffset;
    private String workstationKeyword;
    private WorkstationRecipes.Output recipeSelection;
    private long recipeGeneration;

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
        this.leaveWorkstations();
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
        this.recipeSelection = null;
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

    public void workstation(RecipeType type, int page, String keyword) {
        this.workstation = type;
        this.workstationPage = page;
        this.workstationKeyword = keyword;
        this.recipeSelection = null;
    }

    public RecipeType workstation() { return this.workstation; }
    public int workstationPage() { return this.workstationPage; }
    public void workstationPage(int page) { this.workstationPage = page; }
    public int workstationCategoryOffset() { return this.workstationCategoryOffset; }
    public void workstationCategoryOffset(int offset) { this.workstationCategoryOffset = Math.max(0, offset); }
    public String workstationKeyword() { return this.workstationKeyword; }
    public WorkstationRecipes.Output recipeSelection() { return this.recipeSelection; }
    public long recipeGeneration() { return this.recipeGeneration; }
    public void recipeSelection(WorkstationRecipes.Output selection, long generation) {
        this.recipeSelection = selection;
        this.recipeGeneration = generation;
    }
    public void leaveWorkstations() {
        this.workstation = null;
        this.workstationPage = 0;
        this.workstationCategoryOffset = 0;
        this.workstationKeyword = null;
        this.recipeSelection = null;
    }
}
