package dev.che1s.craftenginerecipe.integration.farmersdelight;

import dev.che1s.craftenginerecipe.CraftEngineRecipePlugin;
import dev.che1s.craftenginerecipe.api.DisplayRecipe;
import dev.che1s.craftenginerecipe.api.RecipeIngredient;
import dev.che1s.craftenginerecipe.api.RecipeItem;
import dev.che1s.craftenginerecipe.api.RecipeLayout;
import dev.che1s.craftenginerecipe.api.RecipeResult;
import dev.che1s.craftenginerecipe.api.RecipeWorkstation;
import dev.che1s.craftenginerecipe.integration.common.RecipeSource;
import dev.che1s.craftenginerecipe.i18n.IntegrationText;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.CampfireRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import net.momirealms.craftengine.core.plugin.locale.TranslationManager;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.ToLongFunction;
import java.util.function.Predicate;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/** 导入 FD 已发布的配方，不重新解析其文件，也不拦截其菜单。 */
public final class FarmersDelightRecipeSource implements RecipeSource {
    private static final String COOKING_POT_TITLE = "农夫乐事·烹饪锅";
    private static final String CUTTING_BOARD_TITLE = "农夫乐事·砧板";
    private static final String SPECIAL_RECIPE_TITLE = "农夫乐事·特殊配方";
    private static final int FAKE_BUTTON_SLOT = 0;
    public static final String PLUGIN_NAME = "FarmersDelight";
    private static final String ROOT = "com.huidu.farmersdelight.";
    private static final String PERMISSION = "farmersdelight.command.recipe";
    private static final long POLL_NANOS = 5_000_000_000L;
    private final Object source;
    private final BooleanSupplier enabled;
    private final JavaPlugin cer;
    private final FDReflection reflection;
    private final Function<String, String> resourcePackTranslations;
    private final IntegrationText text;
    private final Set<String> warned = new LinkedHashSet<>();
    private long checkedAt;
    private long fingerprint = Long.MIN_VALUE;
    private long translationFingerprint = Long.MIN_VALUE;
    private long revision;

    public FarmersDelightRecipeSource(JavaPlugin cer, Plugin source) {
        this.cer = cer;
        this.source = source;
        this.enabled = () -> source != null && source.isEnabled();
        this.reflection = new FDReflection(source == null ? getClass().getClassLoader() : source.getClass().getClassLoader());
        this.resourcePackTranslations = key -> cer instanceof CraftEngineRecipePlugin plugin
                ? plugin.data().resourcePackTranslation(key) : null;
        this.text = cer instanceof CraftEngineRecipePlugin plugin
                ? IntegrationText.of("farmersdelight", plugin.lang()) : IntegrationText.empty();
    }

    FarmersDelightRecipeSource(Object source, FDReflection reflection, BooleanSupplier enabled) {
        this(source, reflection, enabled, key -> null);
    }

    FarmersDelightRecipeSource(Object source, FDReflection reflection, BooleanSupplier enabled,
                               Function<String, String> resourcePackTranslations) {
        this.cer = null;
        this.source = source;
        this.reflection = reflection;
        this.enabled = enabled;
        this.resourcePackTranslations = resourcePackTranslations == null ? key -> null : resourcePackTranslations;
        this.text = IntegrationText.empty();
    }

    @Override public String id() { return "farmersdelight"; }

    @Override public boolean ready() {
        if (source == null || !enabled.getAsBoolean()) return false;
        try {
            Object api = api();
            return api != null && Boolean.TRUE.equals(reflection.call(api, "isContentLoaded"));
        } catch (RuntimeException | LinkageError error) {
            warn("readiness", error);
            return false;
        }
    }

    /** 有界轮询还能观察不会触发重载事件的运行时注册。 */
    @Override public synchronized long revision() {
        long now = System.nanoTime();
        if (checkedAt != 0 && now - checkedAt < POLL_NANOS) return revision;
        checkedAt = now;
        long next = ready() ? stateFingerprint(readState()) : 0;
        long translations = this.cer instanceof CraftEngineRecipePlugin plugin ? plugin.data().translationRevision() : 0L;
        if (next != fingerprint || translations != translationFingerprint) {
            fingerprint = next;
            translationFingerprint = translations;
            revision++;
        }
        return revision;
    }

    @Override public List<DisplayRecipe> capture() {
        if (!ready()) return List.of();
        State state = readState();
        IngredientOptions options = new IngredientOptions();
        Object guiSection = reflection.call(source, "getRecipeViewGuiSection");
        TitleLayouts titles = new TitleLayouts(guiSection);
        RecipeSlotLayouts slots = new RecipeSlotLayouts(guiSection);
        List<DisplayRecipe> recipes = new ArrayList<>();
        state.pots.forEach((key, value) -> safely(recipes, "cooking_pot/" + key, () -> pot(value, null, options, titles, slots)));
        state.groups.forEach((group, entries) -> map(entries).forEach((key, value) ->
                safely(recipes, "cooking_pot/" + group + "/" + key, () -> pot(value, group, options, titles, slots))));
        state.boards.forEach((key, value) -> safely(recipes, "cutting_board/" + key, () -> board(value, options, titles, slots)));
        for (Object special : state.specials) safely(recipes, "special/" + text(special, "id"), () -> special(special, titles));
        for (Object type : state.types) {
            try {
                for (Object recipe : list(reflection.call(type, "recipes"))) {
                    safely(recipes, text(type, "id") + "/" + text(recipe, "id"), () -> addon(type, recipe));
                }
            } catch (RuntimeException | LinkageError error) { warn("recipe type " + text(type, "id"), error); }
        }
        for (DisplayRecipe skillet : skilletRecipes()) {
            recipes.add(skillet);
        }
        return List.copyOf(recipes);
    }

    /** 煎锅烹饪完全对应 FarmersDelight 自身的 CampfireRecipeCache 规则。 */
    private List<DisplayRecipe> skilletRecipes() {
        if (this.cer == null) return List.of();
        Map<String, DisplayRecipe> unique = new LinkedHashMap<>();
        var iterator = Bukkit.recipeIterator();
        while (iterator.hasNext()) {
            Recipe recipe;
            try {
                recipe = iterator.next();
            } catch (RuntimeException ignored) {
                continue;
            }
            if (!(recipe instanceof CampfireRecipe campfire)) continue;
            try {
                ItemStack result = campfire.getResult();
                if (!usable(result)) continue;
                RecipeChoice choice = campfire.getInputChoice();
                if (choice instanceof RecipeChoice.MaterialChoice materials) {
                    for (Material material : materials.getChoices()) {
                        if (material == null || material.isLegacy() || !material.isItem()) continue;
                        ItemStack input = new ItemStack(material);
                        DisplayRecipe display = skilletRecipe(input, result, campfire.getCookingTime(),
                                campfire.getExperience());
                        unique.putIfAbsent(display.id(), display);
                    }
                } else if (choice instanceof RecipeChoice.ExactChoice exact) {
                    for (ItemStack input : exact.getChoices()) {
                        if (!usable(input)) continue;
                        DisplayRecipe display = skilletRecipe(input, result, campfire.getCookingTime(),
                                campfire.getExperience());
                        unique.putIfAbsent(display.id(), display);
                    }
                }
            } catch (RuntimeException | LinkageError error) {
                warn("skillet " + campfire.getKey(), error);
            }
        }
        return List.copyOf(unique.values());
    }

    DisplayRecipe skilletRecipe(ItemStack inputStack, ItemStack resultStack, int cookingTime, float experience) {
        RecipeItem input = item(inputStack);
        RecipeItem result = item(resultStack);
        String inputIdentity = input.provider() + ":" + input.id();
        String resultIdentity = result.provider() + ":" + result.id();
        String notes = t("skillet.note",
                "营火可烹饪：是；烹饪时间：{ticks} tick；经验：{experience}",
                "ticks", Math.max(1, cookingTime), "experience", Math.max(0, experience));
        RecipeLayout layout = new RecipeLayout(6, null, -1, -1, -1, -1, -1,
                List.of(), true);
        return new DisplayRecipe(recipeId("skillet", null, inputIdentity + "=>" + resultIdentity),
                new RecipeWorkstation("farmersdelight:skillet", fromId("farmersdelight:skillet"),
                        t("title.skillet", COOKING_POT_TITLE.replace("烹饪锅", "煎锅"))),
                layout,
                List.of(new RecipeIngredient(List.of(input), 1, 19, RecipeIngredient.Role.INGREDIENT),
                        new RecipeIngredient(List.of(flameDecoration(cookingTime, experience)), 1, 21,
                                RecipeIngredient.Role.INFO)),
                List.of(new RecipeResult(result, Math.max(1, resultStack.getAmount()), 23, null)),
                List.of(notes), PERMISSION, player -> enabled.getAsBoolean());
    }

    private RecipeItem flameDecoration(int cookingTime, float experience) {
        return new RecipeItem("craftengine", "internal:cooking_info", null,
                t("flame.name", "<gold>配方信息"),
                List.of(
                        t("flame.time", "<gray>时间：{ticks} 刻", "ticks", Math.max(1, cookingTime)),
                        t("flame.experience", "<gray>经验：{experience}", "experience", decimal(experience))));
    }

    private static String decimal(float value) {
        return java.math.BigDecimal.valueOf(Math.max(0, value)).stripTrailingZeros().toPlainString();
    }

    private Object api() { return reflection.call(reflection.type(ROOT + "api.FarmersDelightApi"), "get"); }

    private State readState() {
        Object potManager = reflection.call(source, "getCookingPotRecipes");
        Object published = reflection.call(potManager, "capturePublished");
        Map<String, Object> pots = map(published == null ? reflection.call(potManager, "getRecipes") : reflection.call(published, "recipes"));
        Map<String, Object> groups = map(reflection.call(published, "customRecipes"));
        Object boards = reflection.call(source, "getCuttingBoardRecipes");
        return new State(pots, groups, map(reflection.call(boards, "getRecipes")),
                list(reflection.call(api(), "specialRecipes")), list(reflection.call(api(), "recipeTypes")));
    }

    private DisplayRecipe pot(Object raw, String group, IngredientOptions options, TitleLayouts titles, RecipeSlotLayouts slots) {
        String key = text(raw, "getId");
        List<RecipeIngredient> ingredients = new ArrayList<>();
        List<Integer> ingredientSlots = slots.potIngredientSlots(group);
        int ingredientIndex = 0;
        for (Object ingredient : list(reflection.call(raw, "getIngredients"))) {
            ingredients.add(ingredient(ingredient, RecipeIngredient.Role.INGREDIENT, options,
                    slotAt(ingredientSlots, ingredientIndex++)));
        }
        ItemStack container = stack(reflection.call(raw, "getContainer"));
        if (Boolean.TRUE.equals(reflection.call(raw, "getNeedsContainer")) && usable(container)) {
            ingredients.add(new RecipeIngredient(List.of(item(container)), Math.max(1, container.getAmount()),
                    slots.potContainerSlot(group), RecipeIngredient.Role.CONTAINER));
        }
        addFakeButton(ingredients, FAKE_BUTTON_SLOT);
        ItemStack result = stack(reflection.call(raw, "getResult"));
        if (!usable(result)) throw new IllegalStateException("Missing cooking pot result");
        List<String> notes = new ArrayList<>();
        notes.add(t("pot.cooking_time", "烹饪时间：{seconds} 秒",
                "seconds", number(reflection.call(raw, "getCookTime"), 0).doubleValue() / 20.0));
        notes.add(t("pot.experience", "经验：{experience}",
                "experience", number(reflection.call(raw, "getExperience"), 0)));
        if (group != null) notes.add(t("pot.group", "专用烹饪锅：{group}", "group", group));
        String stationId = group == null ? "farmersdelight:cooking_pot" : "farmersdelight:cooking_pot/" + encoded(group);
        return new DisplayRecipe(recipeId("cooking_pot", group, key),
                station(stationId, "farmersdelight:cooking_pot",
                        group == null ? t("title.cooking_pot", COOKING_POT_TITLE)
                                : t("title.cooking_pot_group", COOKING_POT_TITLE + " · {group}",
                                "group", group)),
                titles.layout("recipe-detail-cooking-pot", group, key), ingredients,
                List.of(new RecipeResult(item(result), Math.max(1, result.getAmount()), slots.potResultSlot(group), null)), notes, PERMISSION,
                unlocked("farmersdelight:cooking_pot", key));
    }

    private DisplayRecipe board(Object raw, IngredientOptions options, TitleLayouts titles, RecipeSlotLayouts slots) {
        String key = text(raw, "getId");
        List<RecipeIngredient> ingredients = new ArrayList<>();
        ingredients.add(ingredient(reflection.call(raw, "getInput"), RecipeIngredient.Role.INGREDIENT, options,
                slots.boardInputSlot()));
        LinkedHashMap<String, RecipeItem> tools = new LinkedHashMap<>();
        for (Object requirement : list(reflection.call(raw, "getTools"))) {
            Object asIngredient = reflection.call(requirement, "asIngredient");
            List<ItemStack> candidates = options.resolve(asIngredient);
            if (candidates.isEmpty()) candidates = actionTools(text(requirement, "getKey"));
            for (ItemStack candidate : candidates) {
                if (excludedTool(candidate, requirement)) continue;
                RecipeItem choice = options.item(candidate);
                tools.putIfAbsent(choice.provider() + ":" + choice.id(), choice);
            }
        }
        if (!tools.isEmpty()) ingredients.add(new RecipeIngredient(List.copyOf(tools.values()), 1,
                slots.boardToolSlot(), RecipeIngredient.Role.TOOL));
        else if (!list(reflection.call(raw, "getTools")).isEmpty()) ingredients.add(new RecipeIngredient(List.of(
                new RecipeItem("minecraft", "minecraft:barrier", null,
                        t("item.required_tool", "所需工具"),
                        List.of(t("item.required_tool_lore", "工具要求无法展开，请查看 /fd recipe")))),
                1, slots.boardToolSlot(), RecipeIngredient.Role.TOOL));
        addFakeButton(ingredients, FAKE_BUTTON_SLOT);
        List<RecipeResult> results = new ArrayList<>();
        List<Integer> resultSlots = slots.boardResultSlots();
        for (Object entry : list(reflection.call(raw, "getResults"))) {
            ItemStack output = stack(reflection.call(entry, "getItem"));
            if (usable(output)) results.add(new RecipeResult(item(output), Math.max(1, output.getAmount()),
                    slotAt(resultSlots, results.size()), chanceNote(number(reflection.call(entry, "getChance"), 1).doubleValue())));
        }
        return new DisplayRecipe(recipeId("cutting_board", null, key),
                station("farmersdelight:cutting_board", "farmersdelight:cutting_board",
                        t("title.cutting_board", CUTTING_BOARD_TITLE)),
                titles.layout("recipe-detail-cutting-board", null, key), ingredients, results, List.of(), PERMISSION, unlocked("farmersdelight:cutting_board", key));
    }

    private RecipeIngredient ingredient(Object ingredient, RecipeIngredient.Role role, IngredientOptions options) {
        return ingredient(ingredient, role, options, -1);
    }

    private RecipeIngredient ingredient(Object ingredient, RecipeIngredient.Role role, IngredientOptions options, int slot) {
        List<RecipeItem> choices = new ArrayList<>();
        for (ItemStack candidate : options.resolve(ingredient)) choices.add(options.item(candidate));
        if (choices.isEmpty()) {
            String identity = String.valueOf(reflection.call(ingredient, "stableKey"));
            choices.add(new RecipeItem("minecraft", "minecraft:barrier", null,
                    t("item.unresolved", "未解析的原料"), List.of(identity)));
        }
        return new RecipeIngredient(choices, 1, slot, role);
    }

    private static int slotAt(List<Integer> slots, int index) {
        return slots != null && index >= 0 && index < slots.size() ? slots.get(index) : -1;
    }

    /** 使用 Farmer's Delight 发布的详情 GUI 槽位配置，并以内置默认值作为回退。 */
    private final class RecipeSlotLayouts {
        private final Object config;

        RecipeSlotLayouts(Object section) {
            Object configType = reflection.type(ROOT + "gui.RecipeViewGuiConfig");
            Object parsed = section == null || configType == null ? null : reflection.call(configType, "fromConfig", section);
            this.config = parsed;
        }

        List<Integer> potIngredientSlots(String group) {
            return integerList(detail("getCookingPotDetail", group), "getIngredientSlots",
                    List.of(19, 20, 21, 28, 29, 30));
        }

        int potContainerSlot(String group) {
            return intValue(detail("getCookingPotDetail", group), "getContainerSlot", 41);
        }

        int potResultSlot(String group) {
            return intValue(detail("getCookingPotDetail", group), "getResultSlot", 43);
        }

        int boardInputSlot() {
            return intValue(detail("getCuttingBoardDetail", null), "getInputSlot", 28);
        }

        int boardToolSlot() {
            return intValue(detail("getCuttingBoardDetail", null), "getToolSlot", 30);
        }

        List<Integer> boardResultSlots() {
            return integerList(detail("getCuttingBoardDetail", null), "getResultSlots", List.of(32, 33, 41, 42));
        }

        private Object detail(String method, String group) {
            if (this.config == null) return null;
            return group == null ? reflection.call(this.config, method) : reflection.call(this.config, method, group);
        }

        private List<Integer> integerList(Object receiver, String method, List<Integer> fallback) {
            Object value = reflection.call(receiver, method);
            if (!(value instanceof Collection<?> collection)) return fallback;
            List<Integer> result = collection.stream().filter(Number.class::isInstance).map(Number.class::cast)
                    .map(Number::intValue).toList();
            return result.isEmpty() ? fallback : result;
        }

        private int intValue(Object receiver, String method, int fallback) {
            Object value = reflection.call(receiver, method);
            return value instanceof Number number ? number.intValue() : fallback;
        }
    }

    private DisplayRecipe special(Object raw, TitleLayouts titles) {
        String key = text(raw, "id");
        AddonLayout layout = titles.specialLayout(raw, key);
        if (layout == null) layout = new AddonLayout(nativeUiLayout(boldTitle(specialTitle())), Map.of(), Map.of());
        Map<String, List<Integer>> slots = layout.slots();
        Set<Integer> used = new HashSet<>();
        List<RecipeIngredient> ingredients = new ArrayList<>();
        addSpecialInputs(ingredients, list(reflection.call(raw, "inputSlots")),
                RecipeIngredient.Role.INGREDIENT, slots.getOrDefault("input", List.of()), used);
        addSpecialDescription(ingredients, raw, slots.getOrDefault("description", List.of()), used);
        addSpecialCondition(ingredients, Boolean.TRUE.equals(reflection.call(raw, "hasSunlight")),
                "minecraft:torch", "gui.condition.sunlight", "gui.condition.sunlight_lore", "yellow",
                slots.getOrDefault("sunlight", List.of()), used);
        addSpecialCondition(ingredients, Boolean.TRUE.equals(reflection.call(raw, "hasWater")),
                "minecraft:water_bucket", "gui.condition.water", "gui.condition.water_lore", "blue",
                slots.getOrDefault("water", List.of()), used);
        addSpecialCondition(ingredients, Boolean.TRUE.equals(reflection.call(raw, "hasCatalystInfo")),
                "minecraft:paper", "gui.condition.catalyst_info", "gui.condition.catalyst_info_lore", "gold",
                slots.getOrDefault("catalyst_info", List.of()), used);
        addSpecialCatalysts(ingredients, list(reflection.call(raw, "catalystSlots")),
                slots.getOrDefault("catalyst_item", List.of()), used);
        List<RecipeResult> results = new ArrayList<>();
        Set<Integer> usedResults = new HashSet<>();
        for (Object entry : list(reflection.call(raw, "outputSlots"))) {
            List<ItemStack> choices = specialChoices(entry);
            int slot = takeSlot(slots.getOrDefault("output", List.of()), usedResults);
            for (int index = 0; index < choices.size(); index++) {
                ItemStack choice = choices.get(index);
                RecipeItem identity = decorated(item(choice), entry);
                results.add(new RecipeResult(identity, Math.max(1, choice.getAmount()), index == 0 ? slot : -1,
                        choices.size() > 1 ? t("note.candidate_result", "候选产物") : null));
            }
        }
        if (results.isEmpty()) {
            RecipeItem icon = fromId(text(raw, "iconItemId"));
            boolean descriptionOnly = "item_description".equalsIgnoreCase(text(raw, "displayType"));
            results.add(new RecipeResult(icon, 1, descriptionOnly ? 16 : -1,
                    t("note.description_display", "说明展示")));
        }
        List<String> notes = new ArrayList<>();
        if ("item_description".equalsIgnoreCase(text(raw, "displayType"))) {
            notes.add(t("note.description_entry", "说明条目；不代表可执行的合成配方"));
        }
        addFakeButton(ingredients, Math.max(0, layout.layout().rows() - 1) * 9);
        RecipeItem icon = fromId(text(raw, "iconItemId"));
        return new DisplayRecipe(recipeId("special", null, key),
                new RecipeWorkstation("farmersdelight:special",
                        RecipeItem.of("farmersdelight:rich_soil"), specialTitle()),
                layout.layout(), ingredients, results, notes, PERMISSION, player -> enabled.getAsBoolean());
    }

    private void addSpecialInputs(List<RecipeIngredient> target, List<?> entries, RecipeIngredient.Role role,
                                  List<Integer> slots, Set<Integer> used) {
        for (Object entry : entries) {
            List<RecipeItem> choices = new ArrayList<>();
            for (ItemStack candidate : specialChoices(entry)) choices.add(decorated(item(candidate), entry));
            if (!choices.isEmpty()) target.add(new RecipeIngredient(choices, 1, takeSlot(slots, used), role));
        }
    }

    private void addSpecialDescription(List<RecipeIngredient> target, Object raw, List<Integer> slots, Set<Integer> used) {
        List<String> keys = list(reflection.call(raw, "descriptionKeys")).stream().map(String::valueOf).toList();
        if (keys.isEmpty()) return;
        List<String> lore = keys.size() > 1
                ? keys.subList(1, keys.size()).stream().map(this::translation).toList() : List.of();
        RecipeItem description = new RecipeItem("minecraft", "minecraft:paper",
                invisibleCarrier(Material.PAPER), translation(keys.getFirst()), lore);
        target.add(new RecipeIngredient(List.of(description), 1, takeSlot(slots, used), RecipeIngredient.Role.INFO));
    }

    private void addSpecialCondition(List<RecipeIngredient> target, boolean active, String itemId,
                                     String nameKey, String loreKey, String color,
                                     List<Integer> slots, Set<Integer> used) {
        if (!active) return;
        Material material = itemId.equals("minecraft:torch") ? Material.TORCH
                : itemId.equals("minecraft:water_bucket") ? Material.WATER_BUCKET : Material.PAPER;
        RecipeItem condition = new RecipeItem("minecraft", itemId, invisibleCarrier(material),
                "<" + color + ">" + translation(nameKey) + "</" + color + ">",
                List.of("<gray>" + translation(loreKey) + "</gray>"));
        target.add(new RecipeIngredient(List.of(condition), 1, takeSlot(slots, used), RecipeIngredient.Role.INFO));
    }

    /** FD 使用隐藏载体的物品模型绘制这些槽位，仅保留悬浮文本。 */
    private static ItemStack invisibleCarrier(Material material) {
        try {
            ItemStack icon = new ItemStack(material);
            ItemMeta meta = icon.getItemMeta();
            if (meta != null) {
                meta.setItemModel(NamespacedKey.minecraft("air"));
                icon.setItemMeta(meta);
            }
            return icon;
        } catch (RuntimeException | LinkageError ignored) {
            // 单元测试环境没有 Paper 注册表；展示层会解析为普通图标。
            return null;
        }
    }

    /**
     * 透明遮罩按钮：空气身份、无名称与 Lore、不可点击，只负责遮住 FD 背景瑕疵。
     */
    static RecipeIngredient fakeButton(int slot) {
        return new RecipeIngredient(List.of(fakeButtonItem()), 1, slot, RecipeIngredient.Role.INFO);
    }

    private static void addFakeButton(List<RecipeIngredient> ingredients, int slot) {
        // 假按钮覆盖目标槽位原有的装饰，避免 DisplayRecipe 出现同槽冲突。
        ingredients.removeIf(ingredient -> ingredient.slot() == slot);
        ingredients.add(fakeButton(slot));
    }

    static RecipeItem fakeButtonItem() {
        // 导出器会将这个内部身份写成空气物品 + cer_fd_gui 物品模型。
        return new RecipeItem("cer_fd_gui", "minecraft:air", null, null, null);
    }

    private void addSpecialCatalysts(List<RecipeIngredient> target, List<?> entries, List<Integer> slots, Set<Integer> used) {
        List<RecipeItem> choices = new ArrayList<>();
        for (Object entry : entries) {
            for (ItemStack candidate : specialChoices(entry)) choices.add(decorated(item(candidate), entry));
        }
        if (choices.isEmpty()) return;
        // FD 会在槽位上列出全部催化选项；CER 会轮播这些选项，因此 lore 中保留完整列表。
        List<String> names = choices.stream().map(choice -> choice.name() == null ? choice.id() : choice.name()).toList();
        List<RecipeItem> annotated = new ArrayList<>(choices.size());
        for (RecipeItem choice : choices) {
            List<String> lore = new ArrayList<>(choice.lore() == null ? List.of() : choice.lore());
            for (String name : names) lore.add("<gray>- " + name + "</gray>");
            lore.add(t("note.auto_cycle", "<gray>自动循环显示</gray>"));
            annotated.add(new RecipeItem(choice.provider(), choice.id(), choice.icon(), choice.name(), lore));
        }
        target.add(new RecipeIngredient(annotated, 1, takeSlot(slots, used), RecipeIngredient.Role.INFO));
    }

    private List<ItemStack> specialChoices(Object entry) {
        return stacks(reflection.call(reflection.type(ROOT + "util.ItemUtils"), "createSlotItems",
                reflection.call(entry, "itemId"), reflection.call(entry, "behaviorBlockId"), reflection.call(entry, "behaviorListKey")));
    }

    private RecipeItem decorated(RecipeItem item, Object entry) {
        String nameKey = text(entry, "nameKey");
        String itemId = text(entry, "itemId");
        boolean referenced = itemId != null && itemId.startsWith("#") || text(entry, "behaviorBlockId") != null;
        List<String> lore = new ArrayList<>();
        for (Object key : list(reflection.call(entry, "loreKeys"))) lore.add(translation(String.valueOf(key)));
        return new RecipeItem(item.provider(), item.id(), item.icon(),
                nameKey == null || referenced ? item.name() : translation(nameKey),
                lore.isEmpty() ? null : lore);
    }

    private DisplayRecipe addon(Object type, Object raw) {
        String typeId = text(type, "id");
        String key = text(raw, "id");
        String title = plain(reflection.call(type, "title"));
        String stationName = addonName(typeId, title);
        AddonLayout layout = addonLayout(type, stationName);
        if (layout == null) layout = new AddonLayout(nativeUiLayout(null), Map.of(), Map.of());
        List<RecipeIngredient> ingredients = addonIngredients(raw, layout);
        if (usesFakeButton(typeId)) {
            addFakeButton(ingredients, FAKE_BUTTON_SLOT);
        }
        ItemStack icon = stack(reflection.call(type, "icon"));
        ItemStack result = stack(reflection.call(raw, "result"));
        // 部分附属配方只提供图标；当 result() 为 AIR 时保留条目，
        // 不要丢弃整个类型，FD 自身配方书在同样情况下会回退到 PAPER。
        if (!usable(result)) result = icon;
        if (!usable(result)) result = new ItemStack(Material.PAPER);
        RecipeItem stationIcon = usable(icon) ? item(icon) : RecipeItem.of("minecraft:barrier");
        List<String> notes = new ArrayList<>();
        // 与查看者相关的信息刻意交由 FD 在打开 /fd recipe 时自行判断。
        notes.add(t("addon.type", "附属配方类型：{type}", "type", typeId));
        notes.add(t("addon.conditions", "额外条件及玩家专属说明请查看 /fd recipe"));
        RecipeResult addonResult = new RecipeResult(item(result), Math.max(1, result.getAmount()),
                takeSlot(layout.slots().getOrDefault("result", List.of()), new HashSet<>()), null);
        return new DisplayRecipe(recipeId("addon", typeId, key),
                new RecipeWorkstation("farmersdelight:addon/" + encoded(typeId), stationIcon, stationName),
                layout.layout(), ingredients, List.of(addonResult), notes, PERMISSION, player -> enabled.getAsBoolean());
    }

    private List<RecipeIngredient> addonIngredients(Object raw, AddonLayout layout) {
        List<RecipeIngredient> ingredients = new ArrayList<>();
        Map<String, List<Integer>> slots = layout.slots();
        Set<Integer> used = new HashSet<>();
        Map<String, Object> display = map(reflection.call(raw, "displaySlots"));
        List<ItemStack> inputs = stacks(display.get("ingredient"));
        if (inputs.isEmpty()) inputs = stacks(reflection.call(raw, "inputs"));
        for (ItemStack input : inputs) {
            ingredients.add(new RecipeIngredient(List.of(item(input)), Math.max(1, input.getAmount()),
                    takeSlot(slots.getOrDefault("ingredient", List.of()), used), RecipeIngredient.Role.INGREDIENT));
        }
        // FD 会把自定义角色列表中的每个成员放入独立槽位；
        // 这些内容同时显示，与 RecipeIngredient 的候选选择列表不同。
        for (Map.Entry<String, Object> entry : display.entrySet()) {
            String key = entry.getKey();
            if (key.equals("result") || key.equals("ingredient")) continue;
            if (key.equals("fluid_icon") && display.containsKey("fluid")) continue;
            List<ItemStack> displayed = stacks(entry.getValue());
            if (displayed.isEmpty()) continue;
            String roleKey = switch (key) {
                case "input" -> "ingredient";
                case "fluid" -> slots.containsKey("fluid_icon") ? "fluid_icon" : "fluid";
                default -> key;
            };
            if (nonDisplayableActionRole(roleKey)) continue;
            RecipeIngredient.Role role = switch (roleKey) {
                case "ingredient" -> RecipeIngredient.Role.INGREDIENT;
                case "tool" -> RecipeIngredient.Role.TOOL;
                case "container" -> RecipeIngredient.Role.CONTAINER;
                case "fuel" -> RecipeIngredient.Role.FUEL;
                default -> RecipeIngredient.Role.INFO;
            };
            List<Integer> roleSlots = slots.getOrDefault(roleKey, List.of());
            for (ItemStack displayedItem : displayed) {
                int slot = takeSlot(roleSlots, used);
                RecipeItem definition = role == RecipeIngredient.Role.INFO
                        ? exactInfoItem(displayedItem, roleKey, slot)
                        : item(displayedItem);
                ingredients.add(new RecipeIngredient(List.of(definition),
                        Math.max(1, displayedItem.getAmount()), slot, role));
            }
        }
        for (Map.Entry<String, Object> entry : layout.decorations().entrySet()) {
            if (entry.getKey().equals("background") || nonDisplayableActionRole(entry.getKey())) continue;
            ItemStack decoration = stack(entry.getValue());
            if (!usable(decoration)) continue;
            for (int slot : layout.slots().getOrDefault(entry.getKey(), List.of())) {
                if (!used.add(slot)) continue;
                ingredients.add(new RecipeIngredient(List.of(item(decoration)),
                        Math.max(1, decoration.getAmount()), slot, RecipeIngredient.Role.INFO));
            }
        }
        for (int slot : layout.slots().getOrDefault("progress", List.of())) {
            if (!used.add(slot)) continue;
            ingredients.add(new RecipeIngredient(List.of(fromId("farmersdelight:animated")),
                    1, slot, RecipeIngredient.Role.INFO));
        }
        return ingredients;
    }

    private RecipeItem exactInfoItem(ItemStack stack, String role, int slot) {
        RecipeItem base = item(stack);
        return new RecipeItem("brewinandchewin_display", base.id(), stack, base.name(), base.lore());
    }

    /** 交互辅助动作无法在 CER 的只读配方页面中生效。 */
    private static boolean nonDisplayableActionRole(String role) {
        if (role == null) return false;
        return switch (role) {
            case "fill", "fill-success", "fill-missing", "fill-inventory-full", "switch" -> true;
            default -> false;
        };
    }

    /** 对应附属插件自身的详情网格、标题图和槽位角色，以保留其贴图。 */
    private AddonLayout addonLayout(Object type, String friendlyTitle) {
        Object detail = reflection.call(type, "detailLayout");
        if (detail == null) return null;
        int rows = number(reflection.call(detail, "rows"), 0).intValue();
        List<String> grid = list(reflection.call(detail, "layout")).stream().map(String::valueOf).toList();
        if (rows < 1 || rows > 6 || grid.size() != rows) return null;
        Map<Character, String> legend = roleLegend(reflection.call(detail, "legend"));
        Object configuredDecorations = reflection.call(detail, "decorations");
        Map<String, Object> decorations = configuredDecorations instanceof Map<?, ?>
                ? map(configuredDecorations) : Map.of();
        List<String> pattern = new ArrayList<>(rows);
        Map<String, List<Integer>> slots = new LinkedHashMap<>();
        boolean hasIngredient = false;
        boolean hasResult = false;
        for (int row = 0; row < rows; row++) {
            String line = grid.get(row);
            if (line == null || line.length() != 9) return null;
            StringBuilder symbols = new StringBuilder(9);
            for (int column = 0; column < 9; column++) {
                String role = legend.get(line.charAt(column));
                char symbol;
                if ("result".equals(role)) {
                    symbol = 'B';
                    hasResult = true;
                } else if (addonInputRole(role)) {
                    symbol = 'A';
                    hasIngredient = true;
                } else if ("progress".equals(role)) {
                    symbol = ' ';
                } else {
                    // 附属页面保留标题贴图并让空槽透明；CER 的玻璃板会遮挡用户希望看到的资源包背景。
                    symbol = ' ';
                }
                symbols.append(symbol);
                if (symbol == 'A' || symbol == 'B') {
                    slots.computeIfAbsent(role, ignored -> new ArrayList<>()).add(row * 9 + column);
                } else if ("progress".equals(role)) {
                    slots.computeIfAbsent(role, ignored -> new ArrayList<>()).add(row * 9 + column);
                    } else if (role != null && !"background".equals(role)
                            && decorations.containsKey(role)) {
                        slots.computeIfAbsent(role, ignored -> new ArrayList<>())
                                .add(row * 9 + column);
                }
            }
            pattern.add(symbols.toString());
        }
        if (!hasIngredient || !hasResult) return null;
        Object rawTitle = reflection.call(detail, "title");
        String title = rawTitle instanceof Component component
                ? RecipeLayout.jsonTitle(replaceTranslatables(component, friendlyTitle)) : friendlyTitle;
        return new AddonLayout(new RecipeLayout(rows, title, -1, -1, -1, -1, -1, pattern, true),
                slots, decorations);
    }

    private static boolean specialInputRole(String role) {
        return role != null && switch (role) {
            case "input", "description", "sunlight", "water", "catalyst_info", "catalyst_item" -> true;
            default -> false;
        };
    }

    private String specialTitle() {
        return t("title.special", SPECIAL_RECIPE_TITLE);
    }

    private static String boldTitle(String title) {
        return title == null || title.isBlank() ? title : "<bold>" + title + "</bold>";
    }

    private static boolean addonInputRole(String role) {
        return role != null && switch (role) {
            case "ingredient", "input", "tool", "container", "fuel", "fluid", "fluid_icon",
                    "fluid_level", "output_fluid", "temperature", "ferment_info", "return" -> true;
            default -> false;
        };
    }

    private static Map<Character, String> roleLegend(Object value) {
        if (!(value instanceof Map<?, ?> map)) return Map.of();
        Map<Character, String> roles = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() instanceof Character key && entry.getValue() != null) {
                roles.put(key, String.valueOf(entry.getValue()));
            }
        }
        return roles;
    }

    private static int takeSlot(List<Integer> slots, Set<Integer> used) {
        for (int slot : slots) if (used.add(slot)) return slot;
        return -1;
    }

    /** 保留附属插件的背景和图片字形，同时替换其中的本地化标题文字。 */
    private static Component replaceTranslatables(Component component, String text) {
        if (component instanceof TranslatableComponent translatable) {
            Style style = translatable.style()
                    .color(TextColor.color(0x292D35))
                    .decoration(TextDecoration.BOLD, TextDecoration.State.TRUE)
                    .decoration(TextDecoration.ITALIC, TextDecoration.State.FALSE);
            return Component.text(text).style(style);
        }
        List<Component> children = component.children();
        if (children.isEmpty()) return component;
        Component copy = component.children(List.of());
        for (Component child : children) copy = copy.append(replaceTranslatables(child, text));
        return copy;
    }

    /** FD 附属配方书会显示所有已注册类型；配方发现仅负责锅和砧板配方。 */
    private String addonName(String typeId, String title) {
        if (typeId == null || typeId.isBlank()) return t("title.unknown_addon", "农夫乐事·附加配方");
        String name;
        if (typeId.startsWith("brewinandchewin:") && typeId.contains("keg")) {
            name = t("addon.keg", "发酵桶");
        } else if (typeId.startsWith("barbequesdelight:") && typeId.contains("grill")) {
            name = t("addon.grill", "烧烤架");
        } else if (typeId.startsWith("barbequesdelight:") && typeId.contains("skewer")) {
            name = t("addon.skewer", "配料盆");
        }
        else name = title;
        if (name == null || name.isBlank()) name = typeId.substring(typeId.indexOf(':') + 1);
        String prefix = t("title.addon_prefix", "农夫乐事·");
        return name.startsWith(prefix) ? name : prefix + name;
    }

    private static boolean usesFakeButton(String typeId) {
        if (typeId == null) return false;
        return typeId.startsWith("brewinandchewin:") && typeId.contains("keg")
                || typeId.startsWith("barbequesdelight:") && (typeId.contains("grilling")
                || typeId.contains("skewering"));
    }

    public static String backgroundImage(String workstationId) {
        return "farmersdelight:skillet".equals(workstationId) ? "cer_fd_gui:fd_pot_menu" : null;
    }

    private Predicate<Player> unlocked(String type, String recipe) {
        return player -> {
            if (source == null || !enabled.getAsBoolean()) return false;
            try {
                Object value = reflection.call(reflection.type(ROOT + "api.recipe.FarmersDelightRecipeDiscovery"), "isUnlocked", player, type, recipe);
                return !Boolean.FALSE.equals(value);
            } catch (RuntimeException | LinkageError error) { warn("recipe discovery", error); return false; }
        };
    }

    private RecipeWorkstation station(String id, String itemId, String name) { return new RecipeWorkstation(id, fromId(itemId), name); }

    /** 读取 FD 标题模板，并保留其中的资源包图片和位移标签供 CER 使用。 */
    private final class TitleLayouts {
        private final Object root;
        TitleLayouts(Object root) { this.root = root; }

        RecipeLayout layout(String path, String customGroup, String recipeId) {
            String actual = path;
            if (customGroup != null && !customGroup.isBlank() && "recipe-detail-cooking-pot".equals(path)
                    && section(root, "recipe-detail-cooking-pot-guis." + customGroup) != null) {
                actual = "recipe-detail-cooking-pot-guis." + customGroup;
            }
            return withTitle(actual, recipeId, Map.of());
        }

        AddonLayout specialLayout(Object raw, String recipeId) {
            boolean sunlight = Boolean.TRUE.equals(reflection.call(raw, "hasSunlight"));
            boolean water = Boolean.TRUE.equals(reflection.call(raw, "hasWater"));
            boolean catalystInfo = Boolean.TRUE.equals(reflection.call(raw, "hasCatalystInfo"));
            boolean conditions = sunlight || water || catalystInfo || !list(reflection.call(raw, "catalystSlots")).isEmpty();
            String path = conditions ? "special-recipe-detail" : "special-recipe-detail-basic";
            Map<String, String> placeholders = Map.of(
                    "sunlight", condition(path, "sunlight", sunlight),
                    "water", condition(path, "water", water),
                    "catalyst_info", condition(path, "catalyst_info", catalystInfo));
            Object config = section(root, path);
            if (config == null) return null;
            List<String> grid = list(reflection.call(config, "getStringList", "layout")).stream().map(String::valueOf).toList();
            int rows = number(reflection.call(config, "getInt", "rows"), grid.size()).intValue();
            if (rows < 1 || rows > 6 || grid.size() != rows) return null;
            Map<Character, String> legend = new LinkedHashMap<>();
            Object legendSection = section(config, "legend");
            if (legendSection != null) {
                for (Object key : list(reflection.call(legendSection, "getKeys", false))) {
                    String name = String.valueOf(key);
                    if (name.length() == 1) legend.put(name.charAt(0), value(legendSection, name));
                }
            }
            List<String> pattern = new ArrayList<>(rows);
            Map<String, List<Integer>> slots = new LinkedHashMap<>();
            boolean hasInput = false;
            boolean hasOutput = false;
            boolean hasDescription = false;
            for (int row = 0; row < rows; row++) {
                String line = grid.get(row);
                if (line == null || line.length() != 9) return null;
                StringBuilder symbols = new StringBuilder(9);
                for (int column = 0; column < 9; column++) {
                    String role = legend.get(line.charAt(column));
                    char symbol;
                    if ("output".equals(role)) {
                        symbol = 'B';
                        hasOutput = true;
                    } else if ("description".equals(role)) {
                        symbol = 'A';
                        hasDescription = true;
                    } else if (specialInputRole(role)) {
                        symbol = 'A';
                        hasInput = true;
                    } else {
                        symbol = ' ';
                    }
                    symbols.append(symbol);
                    if (symbol == 'A' || symbol == 'B') {
                        slots.computeIfAbsent(role, ignored -> new ArrayList<>()).add(row * 9 + column);
                    }
                }
                pattern.add(symbols.toString());
            }
            if ((!hasInput || !hasOutput) && !hasDescription) return null;
            String title = title(path, recipeId, placeholders, t("title.special", SPECIAL_RECIPE_TITLE));
            if (title == null) title = boldTitle(specialTitle());
            return new AddonLayout(new RecipeLayout(rows, title, -1, -1, -1, -1, -1, pattern, true),
                    slots, Map.of());
        }

        private RecipeLayout withTitle(String path, String recipeId, Map<String, String> placeholders) {
            String fixedTitle = path.contains("cooking-pot")
                    ? t("title.cooking_pot", COOKING_POT_TITLE)
                    : path.contains("cutting-board")
                    ? t("title.cutting_board", CUTTING_BOARD_TITLE) : null;
            String title = title(path, recipeId, placeholders, fixedTitle);
            return title == null ? nativeUiLayout(null) : nativeUiLayout(title);
        }

        private String title(String path, String recipeId, Map<String, String> placeholders, String fixedTitle) {
            Object config = section(root, path);
            if (config == null) return null;
            String title = string(reflection.call(config, "getString", "title"));
            if (title == null || title.isBlank()) return null;
            title = title.replace("{recipe_id}", recipeId == null ? "" : recipeId);
            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                title = title.replace("<" + entry.getKey() + ">", entry.getValue());
            }
            Object layout = section(config, "title-layout.craftengine");
            if (layout != null) {
                title = title.replace("<offset>", value(layout, "offset"));
                title = title.replace("<icon>", value(layout, "icon"));
                for (String key : List.of("sunlight", "water", "catalyst_info")) {
                    if (!placeholders.containsKey(key)) title = title.replace("<" + key + ">", value(layout, key));
                }
            }
            if (fixedTitle != null) {
                String emphasized = boldTitle(fixedTitle);
                title = title.replace("<lang:gui.recipe.detail_title>", emphasized)
                        .replace("<lang:gui.recipe.special_recipes>", emphasized);
                if (!title.contains(emphasized)) title = title.replace(fixedTitle, emphasized);
            }
            return title;
        }

        private String condition(String path, String key, boolean active) {
            Object config = section(root, path);
            Object layout = section(config, "title-layout.craftengine");
            return layout == null ? "" : value(layout, active ? key : key + "-off");
        }

        private String value(Object section, String key) {
            String value = string(reflection.call(section, "getString", key));
            return value == null ? "" : value;
        }

        private Object section(Object parent, String path) {
            return parent == null || path == null ? null : reflection.call(parent, "getConfigurationSection", path);
        }
    }

    /** FD 原生详情页自行负责导航和展示，不显示 CER 控件。 */
    private static RecipeLayout nativeUiLayout(String title) {
        return RecipeLayout.nativeUi(title);
    }


    private Class<?> icons() { return reflection.type(ROOT + "gui.RecipeIngredientIcons"); }
    private Class<?> items() { return reflection.type(ROOT + "api.item.FarmersDelightItems"); }

    private RecipeItem fromId(String id) {
        if (id == null || id.isBlank()) return RecipeItem.of("minecraft:barrier");
        ItemStack stack = stack(reflection.call(items(), "create", id));
        return usable(stack) ? item(stack) : RecipeItem.of(id);
    }

    private RecipeItem item(ItemStack stack) {
        String id = string(reflection.call(items(), "idOf", stack));
        if (id == null) id = stack.getType().getKey().toString();
        String provider = id.startsWith("minecraft:") ? "minecraft" : id.startsWith("mmoitems:") ? "mmoitems" : "craftengine";
        // FD 可能携带原版物品的精确 NBT 快照。不要将其索引为该材质的普通物品，
        // 而应保留稳定身份和完整克隆图标。
        if (provider.equals("minecraft") && stack.hasItemMeta()) {
            String snapshot;
            try { snapshot = java.util.Base64.getEncoder().encodeToString(stack.asOne().serializeAsBytes()); }
            catch (RuntimeException | LinkageError unavailable) { snapshot = stack.asOne().serialize().toString(); }
            id = id + "/" + java.util.UUID.nameUUIDFromBytes(snapshot.getBytes(StandardCharsets.UTF_8));
            provider = "farmersdelight";
        }
        return RecipeItem.stack(provider, id, stack);
    }

    private static RecipeResult result(ItemStack stack, String note, RecipeItem item) { return new RecipeResult(item, Math.max(1, stack.getAmount()), -1, note); }
    private RecipeResult result(ItemStack stack, String note) { return result(stack, note, item(stack)); }

    String chanceNote(double chance) {
        if (!Double.isFinite(chance)) return t("note.probability_unknown", "概率未知");
        if (chance >= 1) return null;
        String percent = java.math.BigDecimal.valueOf(Math.max(0, chance)).multiply(java.math.BigDecimal.valueOf(100)).stripTrailingZeros().toPlainString();
        return t("note.independent_chance", "独立概率：{percent}%", "percent", percent);
    }

    private String t(String key, String fallback, Object... arguments) {
        return this.text.text(key, fallback, arguments);
    }

    private List<ItemStack> actionTools(String key) {
        if (key == null) return List.of();
        if (key.equals("farmersdelight:tools/knives")) {
            List<ItemStack> knives = new ArrayList<>();
            Object config = reflection.call(source, "getConfig");
            for (Object id : list(reflection.call(config, "getStringList", "knife-items.items"))) {
                ItemStack knife = stack(reflection.call(items(), "create", String.valueOf(id)));
                if (usable(knife)) knives.add(knife);
            }
            return knives;
        }
        String suffix = switch (key) {
            case "farmersdelight:axe_dig", "farmersdelight:axe_strip", "minecraft:axes" -> "_AXE";
            case "farmersdelight:pickaxe_dig", "minecraft:pickaxes" -> "_PICKAXE";
            case "farmersdelight:shovel_dig", "minecraft:shovels" -> "_SHOVEL";
            default -> null;
        };
        List<ItemStack> result = new ArrayList<>();
        if (suffix != null) for (Material material : Material.values()) {
            if (!material.isLegacy() && material.isItem() && material.name().endsWith(suffix)) result.add(new ItemStack(material));
        }
        if (key.equals("minecraft:shears")) result.add(new ItemStack(Material.SHEARS));
        return result;
    }

    private boolean excludedTool(ItemStack candidate, Object requirement) {
        for (Object excluded : list(reflection.call(requirement, "getExcludedItems"))) {
            if (Boolean.TRUE.equals(reflection.call(items(), "matchesId", candidate, excluded.toString()))) return true;
        }
        for (Object excluded : list(reflection.call(requirement, "getExcludedTags"))) {
            if (Boolean.TRUE.equals(reflection.call(items(), "matchesTag", candidate, excluded.toString()))) return true;
        }
        return false;
    }

    private String translation(String key) {
        if (key == null || key.isBlank()) return "";
        String canonical = canonicalTranslationKey(key);
        // 附属标题位于生成的资源包中。优先使用其配置的语言顺序，
        // 避免 FD 服务端语言查询返回与 CER 界面不同的语言。
        String packed = this.resourcePackTranslations.apply(canonical);
        if (packed != null && !packed.isBlank()) return packed;
        Object translated = reflection.call(reflection.type(ROOT + "api.text.FarmersDelightText"), "serverText", canonical);
        String result = translated == null ? canonical : translated.toString();
        if (result.equals(canonical) || result.equals(key)) {
            try {
                String craftEngine = TranslationManager.instance().plainTranslation(canonical);
                if (craftEngine != null && !craftEngine.isBlank() && !craftEngine.equals(canonical)) result = craftEngine;
            } catch (RuntimeException | LinkageError ignored) {
                // CraftEngine 可能尚未加载可选附属插件的语言。
            }
        }
        return result;
    }

    private String titleTranslation(String key) {
        String canonical = canonicalTranslationKey(key);
        String result = translation(key);
        return result.equals(canonical) || result.equals(key) ? humanizeTranslationKey(canonical) : result;
    }

    /** 附属 GUI 标题有时会直接输出键名，旧版本还包含两个已知错字。 */
    private static String canonicalTranslationKey(String key) {
        return key.replace("breuinandeheuin", "brewinandchewin")
                .replace(".guikeg.", ".gui.keg.")
                .replace(".skeuering", ".skewering");
    }

    private static String humanizeTranslationKey(String key) {
        String[] parts = key.split("\\.");
        String value = parts.length == 0 ? key : parts[parts.length - 1];
        if (value.equalsIgnoreCase("category_title") && parts.length > 1) value = parts[parts.length - 2];
        value = value.replace('_', ' ').replace('-', ' ');
        StringBuilder result = new StringBuilder(value.length());
        boolean upper = true;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isWhitespace(c)) {
                result.append(c);
                upper = true;
            } else {
                result.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
        }
        return result.toString();
    }

    private String text(Object receiver, String method) { return string(reflection.call(receiver, method)); }
    private static String string(Object value) { return value == null ? null : value.toString(); }
    private String plain(Object value) {
        return value instanceof net.kyori.adventure.text.Component component
                ? plain(component, 0) : value == null ? "" : value.toString();
    }

    private String plain(net.kyori.adventure.text.Component component, int depth) {
        if (depth > 16) return "";
        StringBuilder name = new StringBuilder();
        if (component instanceof net.kyori.adventure.text.TranslatableComponent translatable) {
            // 附属插件会发布客户端可翻译标题。直接序列化只会保留键名，
            // 因此展平前先通过 FD 的资源包语言服务解析。
            String canonical = canonicalTranslationKey(translatable.key());
            String template = translation(translatable.key());
            if (template.equals(canonical) || template.equals(translatable.key())) {
                if (translatable.fallback() != null) template = translatable.fallback();
                else template = humanizeTranslationKey(canonical);
            }
            Object[] arguments = translatable.arguments().stream().map(argument ->
                    argument.value() instanceof net.kyori.adventure.text.Component nested
                            ? plain(nested, depth + 1) : argument.value()).toArray();
            if (arguments.length > 0) {
                try { template = String.format(Locale.ROOT, template, arguments); }
                catch (java.util.IllegalFormatException ignored) { /* 保持不完整的附属模板可读。 */ }
            }
            name.append(template);
        } else {
            String content = component instanceof net.kyori.adventure.text.TextComponent text
                    ? text.content() : net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                    .serialize(component.children(List.of()));
            if (content.matches("[a-z0-9_]+(?:\\.[a-z0-9_]+)+")) content = titleTranslation(content);
            name.append(content);
        }
        for (var child : component.children()) name.append(plain(child, depth + 1));
        return name.toString();
    }
    private static Number number(Object value, Number fallback) { return value instanceof Number n ? n : fallback; }
    private static ItemStack stack(Object value) { return value instanceof ItemStack item ? item : null; }
    private static boolean usable(ItemStack item) {
        return item != null && item.getType() != Material.AIR && item.getType() != Material.CAVE_AIR && item.getType() != Material.VOID_AIR;
    }
    private static List<?> list(Object value) { return value instanceof Collection<?> collection ? new ArrayList<>(collection) : List.of(); }
    private static List<ItemStack> stacks(Object value) { return list(value).stream().filter(ItemStack.class::isInstance).map(ItemStack.class::cast).filter(FarmersDelightRecipeSource::usable).toList(); }
    @SuppressWarnings("unchecked") private static Map<String, Object> map(Object value) { return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of(); }

    static String recipeId(String station, String group, String recipe) {
        return "farmersdelight:" + encoded(station) + "/" + (group == null ? "default" : "group/" + encoded(group)) + "/" + encoded(recipe);
    }

    /** UTF-8 十六进制转义具有单射性，包括斜杠和转义标记本身。 */
    private static String encoded(String value) {
        if (value == null || value.isBlank()) return "empty";
        StringBuilder result = new StringBuilder();
        for (byte unit : value.getBytes(StandardCharsets.UTF_8)) {
            int b = Byte.toUnsignedInt(unit);
            if (b >= 'a' && b <= 'z' || b >= '0' && b <= '9' || b == '-' || b == '.') result.append((char) b);
            else result.append('_').append(String.format(Locale.ROOT, "%02x", b));
        }
        return result.toString();
    }

    private long stateFingerprint(State state) {
        IngredientOptions options = new IngredientOptions();
        return contentHash(
                entriesFingerprint(state.pots, recipe -> potFingerprint(recipe, options)),
                entriesFingerprint(state.groups, group -> entriesFingerprint(map(group), recipe -> potFingerprint(recipe, options))),
                entriesFingerprint(state.boards, recipe -> boardFingerprint(recipe, options)),
                listFingerprint(state.specials, this::specialFingerprint),
                listFingerprint(state.types, this::typeFingerprint));
    }

    // FD 附属类型允许在每次 recipes() 调用时创建新的 ViewableRecipe 包装器。
    // 数据版本只记录其发布值；包装器身份变化不算内容变化。
    private long potFingerprint(Object recipe, IngredientOptions options) {
        return contentHash(fields(recipe, "getId", "getContainer", "getNeedsContainer", "getResult",
                        "getExperience", "getCookTime", "getCategory", "getPriority"),
                listFingerprint(list(reflection.call(recipe, "getIngredients")), options::fingerprint));
    }

    private long boardFingerprint(Object recipe, IngredientOptions options) {
        return contentHash(fields(recipe, "getId", "getInputDisplay", "getSound", "getPriority"),
                options.fingerprint(reflection.call(recipe, "getInput")),
                listFingerprint(list(reflection.call(recipe, "getTools")), tool -> contentHash(
                        text(tool, "getKey"), reflection.call(tool, "isTag"),
                        keySetFingerprint(reflection.call(tool, "getExcludedItems")),
                        keySetFingerprint(reflection.call(tool, "getExcludedTags")),
                        options.fingerprint(reflection.call(tool, "asIngredient")))),
                listFingerprint(list(reflection.call(recipe, "getResults")), output -> fields(output, "getItem", "getChance")));
    }

    /** 每次采集或轮询后丢弃：标签内容和来源物品堆可能在两次扫描间变化。 */
    private final class IngredientOptions {
        private final Map<String, List<ItemStack>> byKey = new HashMap<>();
        private final Map<Object, List<ItemStack>> byIdentity = new IdentityHashMap<>();
        private final Map<String, Long> fingerprints = new HashMap<>();
        private final Map<ItemStack, RecipeItem> items = new IdentityHashMap<>();

        private RecipeItem item(ItemStack stack) {
            // RecipeItem 持有防御性图标副本，可以安全地在配方间共享。
            // 这样也能避免对共享原版材料重复执行精确 NBT 序列化。
            return this.items.computeIfAbsent(stack, FarmersDelightRecipeSource.this::item);
        }

        private List<ItemStack> resolve(Object ingredient) {
            if (ingredient == null) return List.of();
            String key = text(ingredient, "stableKey");
            if (key != null) return this.byKey.computeIfAbsent(key, ignored -> this.read(ingredient));
            // 缺少稳定键的旧实现绝不能把不同材料错误合并为同一项。
            return this.byIdentity.computeIfAbsent(ingredient, this::read);
        }

        private List<ItemStack> read(Object ingredient) {
            return stacks(reflection.call(icons(), "resolveIngredientOptions", ingredient));
        }

        private long fingerprint(Object ingredient) {
            if (ingredient == null) return 0;
            String key = text(ingredient, "stableKey");
            // stableKey 覆盖 NBT、候选项和排除项。解析值还能检测标签内容变化；
            // 共享标签只哈希一次，不必为每条配方重复克隆。
            if (key != null) return this.fingerprints.computeIfAbsent(key,
                    ignored -> contentHash(key, this.resolve(ingredient)));
            return contentHash(null, this.resolve(ingredient));
        }
    }

    private long specialFingerprint(Object recipe) {
        return contentHash(fields(recipe, "id", "iconItemId", "titleKey", "descriptionKeys", "hasSunlight",
                        "hasWater", "hasCatalystInfo", "displayType"),
                listFingerprint(list(reflection.call(recipe, "inputSlots")), this::specialSlotFingerprint),
                listFingerprint(list(reflection.call(recipe, "outputSlots")), this::specialSlotFingerprint),
                listFingerprint(list(reflection.call(recipe, "catalystSlots")), this::specialSlotFingerprint));
    }

    private long specialSlotFingerprint(Object slot) {
        return contentHash(fields(slot, "itemId", "behaviorBlockId", "behaviorListKey", "nameKey", "loreKeys"),
                specialChoices(slot));
    }

    private long typeFingerprint(Object type) {
        return contentHash(fields(type, "id", "title", "icon"),
                listFingerprint(list(reflection.call(type, "recipes")), recipe -> fields(recipe,
                        "id", "inputs", "listInputs", "result", "icon", "detailTitle", "displaySlots")));
    }

    private long fields(Object receiver, String... methods) {
        long result = 1;
        for (String method : methods) result = mix(result, valueFingerprint(reflection.call(receiver, method)));
        return result;
    }

    private static long entriesFingerprint(Map<String, Object> entries, ToLongFunction<Object> fingerprint) {
        long result = entries.size();
        // 映射包装器和迭代顺序不会影响最终发布的配方集合。
        for (Map.Entry<String, Object> entry : entries.entrySet()) result += contentHash(entry.getKey(), fingerprint.applyAsLong(entry.getValue()));
        return result;
    }

    private static long listFingerprint(List<?> values, ToLongFunction<Object> fingerprint) {
        long result = 1;
        for (Object value : values) result = mix(result, fingerprint.applyAsLong(value));
        return result;
    }

    private static long keySetFingerprint(Object values) {
        long result = 0;
        for (Object key : list(values)) result += contentHash(String.valueOf(key));
        return result;
    }

    private static long contentHash(Object... values) {
        long result = 1;
        for (Object value : values) result = mix(result, valueFingerprint(value));
        return result;
    }

    /** 只有已知值类型会进入此方法；FD 配方和类型对象已在上层完成投影。 */
    private static long valueFingerprint(Object value) {
        if (value instanceof Map<?, ?> values) {
            long result = values.size();
            for (var entry : values.entrySet()) result += contentHash(entry.getKey(), entry.getValue());
            return result;
        }
        if (value instanceof Set<?> values) {
            long result = values.size();
            for (Object entry : values) result += valueFingerprint(entry);
            return result;
        }
        if (value instanceof Collection<?> values) return listFingerprint(new ArrayList<>(values), FarmersDelightRecipeSource::valueFingerprint);
        if (value instanceof Long number) return number;
        // Bukkit ItemStack 和 Adventure Component 实现了值哈希，包括元数据与格式。
        // 现在保存哈希，以便检测同一物品堆之后的修改。
        return Objects.hashCode(value);
    }

    private static long mix(long left, long right) { return (left ^ right) * 0x100000001b3L; }

    private void safely(List<DisplayRecipe> target, String key, java.util.function.Supplier<DisplayRecipe> read) {
        try { target.add(read.get()); }
        catch (RuntimeException | LinkageError error) { warn(key, error); }
    }

    private synchronized void warn(String key, Throwable error) {
        if (cer != null && warned.size() < 100 && warned.add(key)) cer.getLogger().warning("FarmersDelight recipe import (" + key + "): " + error.getMessage());
    }

    private record AddonLayout(RecipeLayout layout, Map<String, List<Integer>> slots,
                               Map<String, Object> decorations) {}
    private record State(Map<String, Object> pots, Map<String, Object> groups, Map<String, Object> boards, List<?> specials, List<?> types) {}
}
