package dev.che1s.craftenginerecipe.gui;

import dev.che1s.craftenginerecipe.recipe.WorkstationRecipes;
import dev.che1s.craftenginerecipe.recipe.RecipeEntry;
import dev.che1s.craftenginerecipe.recipe.RecipeIndex;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.item.recipe.RecipeType;
import org.bukkit.entity.Player;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.function.Function;

public final class GuiSession {

    private static final Map<UUID, GuiSession> SESSIONS = new ConcurrentHashMap<>();

    private Key currentCategory;
    private int categoryPage;
    private int contentPage;
    private Key parentCategory;
    private int recipeIndex;
    private Key recipeItem;
    private boolean recipeUsageMode;
    private boolean unapplied;
    private boolean craftableMode;
    private boolean managerMode;
    private RecipeType workstation;
    private String workstationId;
    private int workstationPage;
    private int workstationCategoryOffset;
    private String workstationKeyword;
    private WorkstationRecipes.Output recipeSelection;
    private RecipeEntry.Output entrySelection;
    private long recipeGeneration;
    private final Deque<RecipePage> recipeHistory = new ArrayDeque<>();

    public record RecipePage(Key item, boolean usageMode, int index, RecipeEntry.Output selection, long generation) {}

    public record HistoryPage(String item, boolean usageMode, int index, boolean workstationSelection) {
    }

    public record Snapshot(String category, int categoryPage, String parentCategory, boolean unapplied,
                           String workstationId, int workstationPage, String workstationKeyword,
                           String recipeItem, int recipeIndex, boolean recipeUsageMode,
                           boolean recipeWorkstationSelection, List<HistoryPage> recipeHistory, int contentPage) {
        public Snapshot {
            recipeHistory = recipeHistory == null ? List.of() : List.copyOf(recipeHistory);
        }
    }

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
        this.enter(category, categoryPage, parent, 0);
    }

    public void enter(Key category, int categoryPage, Key parent, int contentPage) {
        this.leaveWorkstations();
        this.craftableMode = false;
        this.managerMode = false;
        this.currentCategory = category;
        this.categoryPage = categoryPage;
        this.contentPage = Math.max(0, contentPage);
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

    public int contentPage() {
        return this.contentPage;
    }

    public void contentPage(int page) {
        this.contentPage = Math.max(0, page);
    }

    public boolean unapplied() {
        return this.unapplied;
    }

    public void unapplied(boolean unapplied) {
        this.unapplied = unapplied;
    }

    public boolean craftableMode() {
        return this.craftableMode;
    }

    public void craftableMode(boolean craftableMode) {
        this.craftableMode = craftableMode;
    }

    public boolean managerMode() {
        return this.managerMode;
    }

    public void managerMode(boolean managerMode) {
        this.managerMode = managerMode;
    }

    public Key parentCategory() {
        return this.parentCategory;
    }

    public void recipeContext(Key item, int index, boolean usageMode) {
        this.recipeItem = item;
        this.recipeIndex = index;
        this.recipeUsageMode = usageMode;
        this.recipeSelection = null;
        this.entrySelection = null;
    }

    public void pushRecipePage() {
        this.pushRecipePage(this.recipePage());
    }

    public RecipePage recipePage() {
        return this.recipeItem == null ? null
                : new RecipePage(this.recipeItem, this.recipeUsageMode, this.recipeIndex, this.entrySelection, this.recipeGeneration);
    }

    public void pushRecipePage(RecipePage page) {
        if (page != null && !page.equals(this.recipeHistory.peek())) {
            // 限制长时间浏览时保留的产物选择数量。
            if (this.recipeHistory.size() >= 128) this.recipeHistory.removeLast();
            this.recipeHistory.push(page);
        }
    }

    public RecipePage popRecipePage() {
        return this.recipeHistory.poll();
    }

    public void clearRecipeHistory() {
        this.recipeHistory.clear();
    }

    /** 清除当前配方，同时保留物品浏览或合成器导航状态。 */
    public void clearRecipeContext() {
        this.recipeItem = null;
        this.recipeIndex = 0;
        this.recipeUsageMode = false;
        this.recipeSelection = null;
        this.entrySelection = null;
        this.clearRecipeHistory();
    }

    /** 快照是纯数据，可跨重启持久化；不可序列化的引用在 restore() 时重算。 */
    public Snapshot snapshot() {
        return new Snapshot(
                this.currentCategory == null ? null : this.currentCategory.toString(),
                this.categoryPage,
                this.parentCategory == null ? null : this.parentCategory.toString(),
                this.unapplied,
                this.workstationId,
                this.workstationPage,
                this.workstationKeyword,
                this.recipeItem == null ? null : this.recipeItem.toString(),
                this.recipeIndex,
                this.recipeUsageMode,
                this.entrySelection != null,
                this.recipeHistory.stream().map(page -> new HistoryPage(page.item().toString(), page.usageMode(),
                        page.index(), page.selection() != null)).toList(),
                this.contentPage);
    }

    public void restore(Snapshot snapshot) {
        this.restore(snapshot, item -> null, 0L);
    }

    public void restore(Snapshot snapshot, Function<String, RecipeEntry.Output> selectionResolver, long generation) {
        // 恢复只重建“位置”，不重建实时对象：合成器选择通过 selectionResolver
        // 重新解析，其余引用统一置空，等待页面重新打开时按需重建。
        this.craftableMode = false;
        this.managerMode = false;
        this.currentCategory = snapshot.category() == null ? null : Key.of(snapshot.category());
        this.categoryPage = Math.max(0, snapshot.categoryPage());
        this.contentPage = Math.max(0, snapshot.contentPage());
        this.parentCategory = snapshot.parentCategory() == null ? null : Key.of(snapshot.parentCategory());
        this.unapplied = snapshot.unapplied();
        this.workstationId = snapshot.workstationId();
        this.workstation = RecipeIndex.nativeType(this.workstationId);
        this.workstationPage = Math.max(0, snapshot.workstationPage());
        this.workstationKeyword = snapshot.workstationKeyword();
        this.recipeItem = snapshot.recipeItem() == null ? null : Key.of(snapshot.recipeItem());
        this.recipeIndex = Math.max(0, snapshot.recipeIndex());
        this.recipeUsageMode = snapshot.recipeUsageMode();
        this.workstationCategoryOffset = 0;
        this.recipeSelection = null;
        this.entrySelection = snapshot.recipeWorkstationSelection() && this.recipeItem != null
                ? selectionResolver.apply(this.recipeItem.toString()) : null;
        this.recipeGeneration = generation;
        this.clearRecipeHistory();
        for (HistoryPage page : snapshot.recipeHistory()) {
            try {
                RecipeEntry.Output selection = page.workstationSelection()
                        ? selectionResolver.apply(page.item()) : null;
                if (this.recipeHistory.size() >= 128) break;
                this.recipeHistory.addLast(new RecipePage(Key.of(page.item()), page.usageMode(),
                        Math.max(0, page.index()), selection, generation));
            } catch (RuntimeException ignored) {
                // 跳过单个损坏的旧页面，不丢弃其余层级记录。
            }
        }
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
        this.workstationId = RecipeIndex.stationId(type);
        this.workstationPage = page;
        this.workstationKeyword = keyword;
        this.recipeSelection = null;
        this.entrySelection = null;
    }

    public void workstation(String id, int page, String keyword) {
        this.workstationId = id;
        this.workstation = RecipeIndex.nativeType(id);
        this.workstationPage = page;
        this.workstationKeyword = keyword;
        this.recipeSelection = null;
        this.entrySelection = null;
    }

    public String workstationId() { return this.workstationId; }
    public RecipeEntry.Output entrySelection() { return this.entrySelection; }
    public void entrySelection(RecipeEntry.Output selection, long generation) {
        this.entrySelection = selection;
        this.recipeGeneration = generation;
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
        this.workstationId = null;
        this.workstationPage = 0;
        this.workstationCategoryOffset = 0;
        this.workstationKeyword = null;
        this.clearRecipeContext();
    }
}
