package dev.che1s.craftenginerecipe.gui;

import dev.che1s.craftenginerecipe.CraftEngineRecipePlugin;
import dev.che1s.craftenginerecipe.api.DisplayRecipe;
import dev.che1s.craftenginerecipe.api.RecipeIngredient;
import dev.che1s.craftenginerecipe.api.RecipeItem;
import dev.che1s.craftenginerecipe.api.RecipeLayout;
import dev.che1s.craftenginerecipe.config.CerConfig;
import dev.che1s.craftenginerecipe.integration.kaleidoscope.KaleidoscopeRecipeSource;
import dev.che1s.craftenginerecipe.recipe.ExternalRecipeSnapshot;
import dev.che1s.craftenginerecipe.recipe.RecipeEntry;
import dev.che1s.craftenginerecipe.recipe.access.RecipeAccessPolicy;
import dev.che1s.craftenginerecipe.recipe.presentation.RecipePresentation;
import dev.che1s.craftenginerecipe.util.ItemBridge;
import dev.che1s.craftenginerecipe.util.DisplayNames;
import dev.che1s.craftenginerecipe.util.CompatibilityWarnings;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import xyz.xenondevs.invui.gui.Gui;
import xyz.xenondevs.invui.gui.SlotElement;
import xyz.xenondevs.invui.item.Item;
import xyz.xenondevs.invui.window.Window;
import xyz.xenondevs.invui.window.WindowManager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.IntConsumer;

/** 只读箱子展示。所有更新都在查看者所属的实体调度器上执行。 */
final class ChestRecipeRenderer {
    private final CraftEngineRecipePlugin plugin;
    private final BrowserService browser;
    private final CerConfig config;
    private final Map<UUID, View> views = new ConcurrentHashMap<>();

    ChestRecipeRenderer(CraftEngineRecipePlugin plugin, BrowserService browser, CerConfig config) {
        this.plugin = plugin;
        this.browser = browser;
        this.config = config;
    }

    void open(Player player, RecipeEntry entry, int recipeIndex, int recipeCount, Key usageItem,
              IntConsumer navigate, BiFunction<Key, ItemStack, Item> clickable) {
        this.cancel(player);
        if (!this.browser.canViewRecipe(entry, player)) {
            this.browser.returnToBrowser(player);
            return;
        }
        DisplayRecipe recipe = entry.display();
        String[] structure = new String[recipe.layout().rows()];
        java.util.Arrays.fill(structure, "#########");
        Gui gui = Gui.builder().setStructure(structure).addIngredient('#', Item.EMPTY).build();
        View view = new View(player, entry, recipeIndex, recipeCount, usageItem, navigate, clickable, gui,
                plan(recipe), this.plugin.recipeIndex().generation());
        Component title = this.title(recipe);
        Window window = Window.builder().setViewer(player).setUpperGui(gui).setTitle(title)
                .addOutsideClickHandler(click -> {
                    click.setCancelled(true);
                    this.returnClick(view, click.getClickType());
                })
                .addCloseHandler(reason -> this.cancel(player, view))
                .build();
        view.window = window;
        this.views.put(player.getUniqueId(), view);
        this.render(view);
        this.browser.openWindow(window);
        if (WindowManager.getInstance().getOpenWindow(player) == window) this.schedule(view);
        else this.cancel(player, view);
    }

    void cancel(Player player) {
        View view = this.views.remove(player.getUniqueId());
        if (view != null) view.cancelTask();
    }

    private void cancel(Player player, View expected) {
        if (this.views.remove(player.getUniqueId(), expected)) expected.cancelTask();
    }

    void shutdown() {
        this.views.values().forEach(View::cancelTask);
        this.views.clear();
    }

    private boolean current(View view) {
        return this.views.get(view.player.getUniqueId()) == view
                && WindowManager.getInstance().getOpenWindow(view.player) == view.window;
    }

    private boolean validate(View view, RecipeAccessPolicy visibility) {
        if (!this.current(view)) return false;
        if (this.plugin.recipeIndex().generation() != view.generation) {
            this.cancel(view.player, view);
            view.navigate.accept(view.recipeIndex);
            if (WindowManager.getInstance().getOpenWindow(view.player) == view.window) this.browser.returnToBrowser(view.player);
            return false;
        }
        if (!visibility.canView(view.entry)) {
            this.cancel(view.player, view);
            this.browser.returnToBrowser(view.player);
            return false;
        }
        return true;
    }

    private void schedule(View view) {
        if (!this.current(view)) return;
        int token = view.token;
        view.task = this.plugin.tasks().playerLater(view.player, () -> {
            if (view.token != token) return;
            var visibility = this.browser.visibility(view.player);
            if (!this.validate(view, visibility)) return;
            view.cycle = view.cycle == Integer.MAX_VALUE ? 0 : view.cycle + 1;
            // 固定材料和产物已经发送。只有未包含用途查询物品的候选槽会随时间变化；
            // 查询物品所在的槽位保持固定，确保它从轮播开始到结束始终可见。
            for (Placement placement : view.plan.pages().get(view.page).animated()) {
                RecipeIngredient ingredient = view.entry.display().ingredients().get(placement.index());
                if (shouldAnimateCandidates(ingredient.choices(), view.usageItem)) {
                    this.content(view, placement, visibility);
                }
            }
            this.schedule(view);
        }, 20);
        if (this.views.get(view.player.getUniqueId()) != view || view.token != token) view.cancelTask();
    }

    private Component title(DisplayRecipe recipe) {
        String raw = recipe.layout().title();
        String fixedTitle = RecipePresentation.farmerFixedTitle(recipe.workstation().id());
        raw = RecipePresentation.emphasizedFarmerTitle(recipe.workstation().id(), raw);
        if (raw != null && raw.startsWith(RecipeLayout.JSON_TITLE_PREFIX)) {
            try {
                Component parsed = GsonComponentSerializer.gson()
                        .deserialize(raw.substring(RecipeLayout.JSON_TITLE_PREFIX.length()));
                Component content = fixedTitle == null ? parsed : boldText(parsed, fixedTitle);
                return this.config.applyNameStyle(this.config.recipeTitleNamePrefix(), content,
                        this.titleName(recipe, fixedTitle));
            } catch (RuntimeException | LinkageError error) {
                CompatibilityWarnings.warn("ChestRecipeRenderer.title", error);
                raw = null;
            }
        }
        String background = RecipePresentation.backgroundImage(recipe.id(), recipe.workstation().id());
        if (RecipePresentation.shouldApplyGuiBackground(recipe.id(), recipe.workstation().id(), raw)) {
            String label = raw != null && !raw.isBlank()
                    ? PlainTextComponentSerializer.plainText().serialize(this.config.mm(raw))
                    : this.plain(recipe.workstation().name());
            return this.config.mm(RecipePresentation.title(background, label,
                    this.config.recipeTitleNamePrefix()));
        }
        if (raw != null && !raw.isBlank()) {
            Component parsed = this.config.mm(raw);
            return raw.contains("<image:")
                    ? this.config.applyNameStyle(this.config.recipeTitleNamePrefix(), parsed,
                    this.titleName(recipe, fixedTitle))
                    : this.config.styledName(this.config.recipeTitleNamePrefix(), parsed);
        }
        if (fixedTitle != null) {
            return this.config.styledName(this.config.recipeTitleNamePrefix(), fixedTitle);
        }
        return this.config.styledName(this.config.recipeTitleNamePrefix(),
                this.plain(recipe.workstation().name()));
    }

    private String titleName(DisplayRecipe recipe, String fixedTitle) {
        if (fixedTitle != null && !fixedTitle.isBlank()) return fixedTitle;
        String name = this.plain(recipe.workstation().name());
        return name == null || name.isBlank() ? null : name;
    }

    static String emphasizeFarmersDelightTitle(String workstationId, String title) {
        return RecipePresentation.emphasizedFarmerTitle(workstationId, title);
    }

    private static Component boldText(Component component, String text) {
        Component styled = component;
        if (component instanceof TextComponent value && value.content().equals(text)) {
            styled = component.decoration(TextDecoration.BOLD, TextDecoration.State.TRUE);
        }
        List<Component> children = styled.children();
        if (children.isEmpty()) return styled;
        Component result = styled.children(List.of());
        for (Component child : children) result = result.append(boldText(child, text));
        return result;
    }

    static boolean shouldApplyGuiBackground(String workstationId, String rawTitle) {
        return RecipePresentation.shouldApplyGuiBackground(workstationId, rawTitle);
    }

    static String guiTitle(String background, String label) {
        return RecipePresentation.title(background, label);
    }

    static String guiTitle(String background, String label, String prefix) {
        return RecipePresentation.title(background, label, prefix);
    }

    static String farmersDelightGuiTitle(String background, String label) {
        return RecipePresentation.title(background, label);
    }

    static String farmersDelightGuiTitle(String background, String label, String prefix) {
        return RecipePresentation.title(background, label, prefix);
    }

    static boolean usesSourceLoreOnly(String recipeId) {
        return RecipePresentation.usesSourceLoreOnly(recipeId);
    }

    private void render(View view) {
        DisplayRecipe recipe = view.entry.display();
        // 空槽仅作为视觉背景。导航刻意交给下方 Window 的外部点击回调，
        // 即点击箱子菜单之外的区域，而不是箱子内部的空槽。
        Item blank = Item.simple(ItemBridge.emptySlot());
        for (int slot = 0; slot < recipe.layout().rows() * 9; slot++) {
            this.put(view.gui, slot, blank);
        }
        var visibility = this.browser.visibility(view.player);
        for (Placement placement : view.plan.pages().get(view.page).placements()) this.content(view, placement, visibility);

        RecipeLayout layout = recipe.layout();
        if (layout.workstationSlot() >= 0) {
            ItemStack workstation = ItemBridge.build(recipe.workstation().icon(), view.player, 1);
            if (recipe.workstation().name() != null && !recipe.workstation().name().isBlank()) {
                workstation = ItemBridge.decorate(workstation, this.config.mm(DisplayNames.expandTranslations(
                        recipe.workstation().name(), this.plugin.data().resourcePackTranslations())), null);
            }
            this.put(view.gui, layout.workstationSlot(), Item.simple(workstation));
        }
        List<Component> information = recipe.description().stream()
                .map(this.config::mm)
                .map(ChestRecipeRenderer::prepareLoreLine)
                .filter(line -> !hiddenSourceLore(line))
                .toList();
        if (!information.isEmpty()) {
            this.put(view.gui, layout.informationSlot(), Item.simple(ItemBridge.icon(
                    this.config.info().itemModel(), Material.PAPER, Component.empty(), information)));
        }
        this.put(view.gui, layout.previousSlot(), this.navigation(view, -1));
        this.put(view.gui, layout.nextSlot(), this.navigation(view, 1));
        ItemStack back = ItemBridge.icon(this.config.back().itemModel(), this.config.back().material(),
                this.config.mm(this.config.back().name()), List.of());
        this.put(view.gui, layout.backSlot(), Item.builder().setItemProvider(back)
                .addClickHandler(click -> this.returnClick(view, click.clickType())).build());
    }

    private void content(View view, Placement placement, RecipeAccessPolicy visibility) {
        DisplayRecipe recipe = view.entry.display();
        RecipeItem definition;
        int amount;
        boolean nativeUi = recipe.layout().nativeUi();
        boolean sourceLoreOnly = usesSourceLoreOnly(recipe.id());
        boolean kaleidoscope = recipe.id() != null && recipe.id().startsWith("kc:");
        boolean information = !placement.output()
                && recipe.ingredients().get(placement.index()).role() == RecipeIngredient.Role.INFO;
        List<Component> lore = new ArrayList<>();
        if (placement.output()) {
            var result = recipe.results().get(placement.index());
            definition = result.item();
            amount = result.amount();
            if ((!sourceLoreOnly || kaleidoscope) && result.note() != null && !result.note().isBlank()) {
                lore.add(this.config.mm(result.note()));
            }
            if (!sourceLoreOnly && result.viewerLore() != null) {
                try {
                    List<Component> extra = result.viewerLore().apply(view.player);
                    if (extra != null) lore.addAll(extra);
                } catch (RuntimeException | LinkageError error) {
                    CompatibilityWarnings.warn("ChestRecipeRenderer.viewerLore", error);
                }
            }
            // FD 原生详情页将说明放在产物物品上，不显示 CER 的信息按钮。
            if (!sourceLoreOnly && nativeUi && !recipe.description().isEmpty()) {
                for (String line : recipe.description()) lore.add(this.config.mm(line));
            }
        } else {
            RecipeIngredient ingredient = recipe.ingredients().get(placement.index());
            if (KaleidoscopeRecipeSource.isInformationIngredient(recipe.id(), ingredient)) {
                List<Component> notes = recipe.description().stream()
                        .map(this.config::mm)
                        .map(ChestRecipeRenderer::prepareLoreLine)
                        .filter(line -> !hiddenSourceLore(line))
                        .toList();
                this.put(view.gui, placement.slot(), Item.simple(ItemBridge.icon(
                        KaleidoscopeRecipeSource.INFORMATION_ITEM_MODEL, Material.PAPER,
                        Component.empty(), notes)));
                return;
            }
            List<RecipeItem> choices = ingredient.choices();
            if (choices.isEmpty()) {
                this.put(view.gui, placement.slot(), Item.EMPTY);
                return;
            }
            int choice = choiceIndex(choices, view.cycle, view.usageItem);
            definition = choices.get(choice);
            amount = ingredient.amount();
            if (kaleidoscope) {
                for (String line : this.kaleidoscopeItemNotes(recipe, ingredient, definition)) {
                    lore.add(this.config.mm(line));
                }
            }
        }
        Key key = ExternalRecipeSnapshot.key(definition);
        ItemStack icon = ItemBridge.build(definition, view.player, amount);
        icon = hideUnwantedSourceLore(icon);
        if (!kaleidoscope && !sourceLoreOnly && !nativeUi && recipe.layout().informationSlot() < 0) {
            for (String line : recipe.description()) {
                Component component = this.config.mm(line);
                if (!hiddenSourceLore(component)) lore.add(component);
            }
        }
        List<Component> displayLore = lore.stream().map(ChestRecipeRenderer::prepareLoreLine).toList();
        ItemStack decorated = ItemBridge.appendLore(icon, displayLore);
        this.put(view.gui, placement.slot(), !information && visibility.canQuery(key)
                ? view.clickable.apply(key, decorated) : Item.simple(decorated));
    }

    private List<String> kaleidoscopeItemNotes(DisplayRecipe recipe, RecipeIngredient ingredient, RecipeItem item) {
        String key = KaleidoscopeRecipeSource.itemNoteKey(recipe.id(), ingredient.role());
        if (key == null) return List.of();
        return List.of(this.plugin.lang().integrationText("kaleidoscope", key,
                "container", item.id(), "liquid", item.id()));
    }

    private static ItemStack hideUnwantedSourceLore(ItemStack stack) {
        ItemStack copy = stack.clone();
        var meta = copy.getItemMeta();
        if (meta == null) return copy;
        if (meta.lore() == null) {
            copy.setItemMeta(meta);
            return copy;
        }
        List<Component> lore = meta.lore().stream()
                .filter(line -> !hiddenSourceLore(line))
                .map(ChestRecipeRenderer::prepareLoreLine)
                .toList();
        meta.lore(lore);
        copy.setItemMeta(meta);
        return copy;
    }

    static boolean hiddenSourceLore(Component line) {
        return PlainTextComponentSerializer.plainText().serialize(line)
                .contains("获取方式见内容包菜谱");
    }

    static Component prepareLoreLine(Component component) {
        if (component == null) return null;
        return component.color() == null ? component.color(TextColor.color(0xB8C0CC)) : component;
    }

    /**
     * 用途查询时，包含被查询物品的材料槽固定展示该物品，避免候选轮播切到
     * 同一材料标签中的其他物品后，页面看起来与刚查询的物品无关。
     */
    static int choiceIndex(List<RecipeItem> choices, int cycle, net.momirealms.craftengine.core.util.Key usageItem) {
        if (usageItem != null) {
            for (int index = 0; index < choices.size(); index++) {
                if (usageItem.equals(ExternalRecipeSnapshot.key(choices.get(index)))) return index;
            }
        }
        return Math.floorMod(cycle, choices.size());
    }

    static boolean shouldAnimateCandidates(List<RecipeItem> choices, Key usageItem) {
        if (usageItem == null) return true;
        for (RecipeItem choice : choices) {
            if (usageItem.equals(ExternalRecipeSnapshot.key(choice))) return false;
        }
        return true;
    }

    private Item navigation(View view, int direction) {
        return this.button(direction < 0 ? this.config.prevPage() : this.config.nextPage(),
                () -> this.navigate(view, direction));
    }

    private Item button(CerConfig.ButtonDef definition, Runnable action) {
        ItemStack icon = ItemBridge.icon(definition.itemModel(), definition.material(),
                this.config.mm(definition.name()), List.of());
        return Item.builder().setItemProvider(icon).addClickHandler(click -> this.browser.handleClick(click.player(), action)).build();
    }

    private void navigate(View view, int direction) {
        if (!this.validate(view, this.browser.visibility(view.player))) return;
        int target = view.page + direction;
        if (target >= 0 && target < view.plan.pages().size()) {
            // 保持使用同一窗口也能保留主配方轮播的窗口校验状态。
            view.cancelTask();
            view.page = target;
            view.cycle = 0;
            this.render(view);
            this.schedule(view);
        } else if (view.recipeCount > 0) {
            view.navigate.accept(Math.floorMod(view.recipeIndex + direction, view.recipeCount));
        }
        Sounds.play(view.player, this.config.sounds().changePage());
    }

    private void returnClick(View view, ClickType click) {
        RecipeNavigation navigation = RecipeNavigation.from(click);
        if (navigation == null) return;
        this.browser.handleClick(view.player, () -> {
            if (!this.current(view)) return;
            this.browser.recipes().navigateBack(view.player, navigation == RecipeNavigation.HOME);
        });
    }

    private String plain(String value) {
        return value == null ? "" : PlainTextComponentSerializer.plainText().serialize(this.config.mm(value));
    }

    private void put(Gui gui, int slot, Item item) {
        if (slot >= 0) gui.setSlotElement(slot, new SlotElement.Item(item));
    }

    /** 纯槽位规划：显式内容放在第一页，溢出内容也绝不会被丢弃。 */
    static Plan plan(DisplayRecipe recipe) {
        RecipeLayout layout = recipe.layout();
        if (!layout.pattern().isEmpty()) return patternedPlan(recipe);
        Set<Integer> controls = new HashSet<>();
        for (int slot : new int[]{layout.workstationSlot(), layout.informationSlot(), layout.previousSlot(), layout.backSlot(), layout.nextSlot()}) {
            if (slot >= 0) controls.add(slot);
        }
        List<Placement> fixed = new ArrayList<>();
        List<Integer> inputs = new ArrayList<>();
        List<Integer> outputs = new ArrayList<>();
        for (int index = 0; index < recipe.ingredients().size(); index++) {
            int slot = recipe.ingredients().get(index).slot();
            if (slot >= 0) fixed.add(new Placement(false, index, slot)); else inputs.add(index);
        }
        for (int index = 0; index < recipe.results().size(); index++) {
            int slot = recipe.results().get(index).slot();
            if (slot >= 0) fixed.add(new Placement(true, index, slot)); else outputs.add(index);
        }
        List<Page> pages = new ArrayList<>();
        int input = 0;
        int output = 0;
        do {
            Set<Integer> free = new TreeSet<>();
            for (int slot = 0; slot < layout.rows() * 9; slot++) if (!controls.contains(slot)) free.add(slot);
            List<Placement> placements = new ArrayList<>();
            if (pages.isEmpty()) {
                placements.addAll(fixed);
                fixed.forEach(placement -> free.remove(placement.slot()));
            }
            while (!free.isEmpty() && (input < inputs.size() || output < outputs.size())) {
                if (input < inputs.size()) {
                    int slot = preferred(free, false, layout.rows());
                    placements.add(new Placement(false, inputs.get(input++), slot));
                    free.remove(slot);
                }
                if (!free.isEmpty() && output < outputs.size()) {
                    int slot = preferred(free, true, layout.rows());
                    placements.add(new Placement(true, outputs.get(output++), slot));
                    free.remove(slot);
                }
            }
            List<Placement> animated = placements.stream().filter(placement -> !placement.output()
                    && recipe.ingredients().get(placement.index()).choices().size() > 1).toList();
            pages.add(new Page(List.copyOf(placements), animated));
        } while (input < inputs.size() || output < outputs.size());
        return new Plan(List.copyOf(pages));
    }

    private static Plan patternedPlan(DisplayRecipe recipe) {
        List<Placement> fixed = new ArrayList<>();
        List<Integer> inputs = new ArrayList<>();
        List<Integer> outputs = new ArrayList<>();
        for (int index = 0; index < recipe.ingredients().size(); index++) {
            int slot = recipe.ingredients().get(index).slot();
            if (slot >= 0) fixed.add(new Placement(false, index, slot)); else inputs.add(index);
        }
        for (int index = 0; index < recipe.results().size(); index++) {
            int slot = recipe.results().get(index).slot();
            if (slot >= 0) fixed.add(new Placement(true, index, slot)); else outputs.add(index);
        }
        List<Page> pages = new ArrayList<>();
        int input = 0;
        int output = 0;
        boolean hasContentSlots = false;
        // API 图案区分原料/产物槽位；这类图案允许固定备注等内容出现在每一页。
        boolean hasTypedContentSlots = false;
        Set<Integer> controls = new HashSet<>();
        for (int slot : new int[]{recipe.layout().workstationSlot(), recipe.layout().informationSlot(),
                recipe.layout().previousSlot(), recipe.layout().backSlot(), recipe.layout().nextSlot()}) {
            if (slot >= 0) controls.add(slot);
        }
        for (int slot = 0; slot < recipe.layout().rows() * 9; slot++) {
            char symbol = recipe.layout().symbolAt(slot);
            if (symbol == RecipeLayout.INGREDIENT_MARKER || symbol == RecipeLayout.RESULT_MARKER) {
                hasTypedContentSlots = true;
            }
            if (!controls.contains(slot) && symbol != '#' && symbol != ' ') {
                hasContentSlots = true;
                break;
            }
        }
        int totalContent = inputs.size() + outputs.size();
        do {
            List<Placement> placements = new ArrayList<>();
            Set<Integer> used = new HashSet<>();
            // 普通图案只在第一页放固定物品；类型化图案则重复固定备注，翻页后上下文不丢失。
            if (pages.isEmpty() || hasTypedContentSlots) {
                placements.addAll(fixed);
                fixed.forEach(placement -> used.add(placement.slot()));
            }
            int pendingBefore = inputs.size() + outputs.size() - input - output;
            for (int slot = 0; slot < recipe.layout().rows() * 9; slot++) {
                if (used.contains(slot) || controls.contains(slot)) continue;
                char symbol = recipe.layout().symbolAt(slot);
                if (symbol == RecipeLayout.INGREDIENT_MARKER) {
                    // 类型化槽位只消费对应类型，原料不足时保留空位给产物区域。
                    if (input < inputs.size()) {
                        placements.add(new Placement(false, inputs.get(input++), slot));
                        used.add(slot);
                    }
                    continue;
                }
                if (symbol == RecipeLayout.RESULT_MARKER) {
                    // 产物数量也可以单独超过 16 项，下一页继续从 ▶ 区域开始。
                    if (output < outputs.size()) {
                        placements.add(new Placement(true, outputs.get(output++), slot));
                        used.add(slot);
                    }
                    continue;
                }
                if (hasContentSlots && (symbol == '#' || symbol == ' ')) continue;
                if (input < inputs.size()) {
                    placements.add(new Placement(false, inputs.get(input++), slot));
                    used.add(slot);
                } else if (output < outputs.size()) {
                    placements.add(new Placement(true, outputs.get(output++), slot));
                    used.add(slot);
                }
            }
            if ((input < inputs.size() || output < outputs.size()) && pages.isEmpty()
                    && !fixed.isEmpty() && !hasTypedContentSlots) {
                // 生成的特色配方页面可能包含固定装饰或说明物品，但省略了自动产物的符号。
                // 此时使用剩余空槽，避免创建没有可见导航控件的第二页。
                for (int slot = 0; slot < recipe.layout().rows() * 9
                        && (input < inputs.size() || output < outputs.size()); slot++) {
                    if (used.contains(slot) || controls.contains(slot)) continue;
                    if (input < inputs.size()) {
                        placements.add(new Placement(false, inputs.get(input++), slot));
                    } else {
                        placements.add(new Placement(true, outputs.get(output++), slot));
                    }
                    used.add(slot);
                }
            }
            List<Placement> animated = placements.stream().filter(placement -> !placement.output()
                    && recipe.ingredients().get(placement.index()).choices().size() > 1).toList();
            pages.add(new Page(List.copyOf(placements), animated));
            if (pages.size() > totalContent + 1) {
                throw new IllegalStateException("Pattern layout cannot place all recipe content");
            }
            int pendingAfter = inputs.size() + outputs.size() - input - output;
            if (pendingAfter > 0 && pendingAfter == pendingBefore
                    && (pages.size() > 1 || fixed.isEmpty())) {
                throw new IllegalStateException("Pattern layout has no available content slot");
            }
        } while (input < inputs.size() || output < outputs.size());
        if (input < inputs.size() || output < outputs.size()) {
            throw new IllegalStateException("Pattern layout has no available content slot");
        }
        return new Plan(List.copyOf(pages));
    }

    private static int preferred(Set<Integer> slots, boolean output, int rows) {
        int[] columns = output ? new int[]{7, 6, 5, 8, 4, 3, 2, 1, 0} : new int[]{1, 2, 3, 0, 4, 5, 6, 7, 8};
        int[] columnRank = new int[9];
        for (int index = 0; index < columns.length; index++) columnRank[columns[index]] = index;
        return slots.stream().min(Comparator.<Integer>comparingInt(slot -> {
            int row = slot / 9;
            return row == 0 ? Math.max(0, rows - 2) : row == rows - 1 ? rows - 1 : row - 1;
        }).thenComparingInt(slot -> columnRank[slot % 9])).orElseThrow();
    }

    record Placement(boolean output, int index, int slot) {}
    record Page(List<Placement> placements, List<Placement> animated) {}
    record Plan(List<Page> pages) {}

    private static final class View {
        final Player player;
        final RecipeEntry entry;
        final int recipeIndex;
        final int recipeCount;
        final Key usageItem;
        final IntConsumer navigate;
        final BiFunction<Key, ItemStack, Item> clickable;
        final Gui gui;
        final Plan plan;
        final long generation;
        volatile Window window;
        volatile ScheduledTask task;
        volatile int token;
        int page;
        int cycle;

        View(Player player, RecipeEntry entry, int recipeIndex, int recipeCount, Key usageItem,
             IntConsumer navigate, BiFunction<Key, ItemStack, Item> clickable, Gui gui, Plan plan,
             long generation) {
            this.player = player;
            this.entry = entry;
            this.recipeIndex = recipeIndex;
            this.recipeCount = recipeCount;
            this.usageItem = usageItem;
            this.navigate = navigate;
            this.clickable = clickable;
            this.gui = gui;
            this.plan = plan;
            this.generation = generation;
        }

        void cancelTask() {
            this.token++;
            ScheduledTask previous = this.task;
            this.task = null;
            if (previous != null) previous.cancel();
        }
    }
}
