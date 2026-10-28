package dev.che1s.craftenginerecipe.integration.kaleidoscope;

import dev.che1s.craftenginerecipe.api.DisplayRecipe;
import dev.che1s.craftenginerecipe.api.RecipeIngredient;
import dev.che1s.craftenginerecipe.api.RecipeItem;
import dev.che1s.craftenginerecipe.api.RecipeLayout;
import dev.che1s.craftenginerecipe.api.RecipeResult;
import dev.che1s.craftenginerecipe.api.RecipeWorkstation;
import dev.che1s.craftenginerecipe.integration.common.RecipeSource;
import dev.che1s.craftenginerecipe.i18n.IntegrationText;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** 可选的 KC 公共 API 桥接器。CER 不直接链接任何 KC 类。 */
public final class KaleidoscopeRecipeSource implements RecipeSource {
    public static final String PLUGIN_NAME = "KaleidoscopeCookeryPlugin";
    public static final String INFORMATION_PROVIDER = "cer";
    public static final String INFORMATION_ITEM_MODEL = "cer_kc_gui:note";
    public static final int INFORMATION_SLOT_LEFT = 33;
    public static final int INFORMATION_SLOT_RIGHT = 34;
    private static final String API = "net.kaleidoscope.cookery.api.KaleidoscopeCookeryAPI";
    private static final String APPLIANCE = "net.kaleidoscope.cookery.recipe.ApplianceType";
    private static final String PERMISSION = "kaleidoscopecookery.recipe.browse";
    private static final List<String> DEFAULT_LAYOUT = List.of(
            "     AAAA",
            "     AAAA",
            "     AAAA",
            "         ",
            "     BBBB",
            "     BBBB");
    private final Object registry;
    private final List<?> appliances;
    private final Object style;
    private final Object tags;
    private final Object knives;
    private final BooleanSupplier enabled;
    private final RecipeLayout layout;
    private final IntegrationText text;

    public KaleidoscopeRecipeSource(Plugin source) {
        this(source, IntegrationText.empty());
    }

    public KaleidoscopeRecipeSource(Plugin source, IntegrationText text) {
        this(load(source), source::isEnabled, RecipeLayout.patterned(DEFAULT_LAYOUT, null), text);
    }

    private KaleidoscopeRecipeSource(Bindings bindings, BooleanSupplier enabled, RecipeLayout layout) {
        this(bindings.registry(), bindings.appliances(), bindings.style(), bindings.tags(), bindings.knives(),
                enabled, layout, IntegrationText.empty());
    }

    private KaleidoscopeRecipeSource(Bindings bindings, BooleanSupplier enabled, RecipeLayout layout,
                                     IntegrationText text) {
        this(bindings.registry(), bindings.appliances(), bindings.style(), bindings.tags(), bindings.knives(),
                enabled, layout, text);
    }

    // 测试注册表夹具无需启动服务器即可验证真实反射边界。
    KaleidoscopeRecipeSource(Object registry, List<?> appliances, Object style, Object tags, Object knives,
                             BooleanSupplier enabled) {
        this(registry, appliances, style, tags, knives, enabled,
                RecipeLayout.patterned(DEFAULT_LAYOUT, null), IntegrationText.empty());
    }

    KaleidoscopeRecipeSource(Object registry, List<?> appliances, Object style, Object tags, Object knives,
                             BooleanSupplier enabled, RecipeLayout layout) {
        this(registry, appliances, style, tags, knives, enabled, layout, IntegrationText.empty());
    }

    KaleidoscopeRecipeSource(Object registry, List<?> appliances, Object style, Object tags, Object knives,
                             BooleanSupplier enabled, RecipeLayout layout, IntegrationText text) {
        this.registry = registry;
        this.appliances = List.copyOf(appliances);
        this.style = style;
        this.tags = tags;
        this.knives = knives;
        this.enabled = enabled;
        this.layout = java.util.Objects.requireNonNull(layout, "layout");
        this.text = text == null ? IntegrationText.empty() : text;
        requireMethod(registry, "readSnapshot", (Supplier<?>) List::of);
        requireMethod(registry, "generation");
        requireMethod(registry, "choppingRecipes");
        requireMethod(registry, "teapotRecipes");
        for (Object appliance : appliances) {
            requireMethod(registry, "accurateRecipes", appliance);
            requireMethod(registry, "flexRecipes", appliance);
        }
    }

    @Override public String id() { return "kaleidoscope"; }

    @Override public boolean ready() {
        return enabled.getAsBoolean() && !Boolean.TRUE.equals(optional(registry, "isConfigurationLoading", false));
    }

    @Override public long revision() { return ((Number) call(registry, "generation")).longValue(); }

    @Override public List<DisplayRecipe> capture() {
        if (!ready()) throw new IllegalStateException("Kaleidoscope Cookery recipes are not ready");
        @SuppressWarnings("unchecked")
        // KC 在 readSnapshot 内复用其缓存；直接遍历实时集合可能读到重载中的混合状态。
        List<DisplayRecipe> snapshot = (List<DisplayRecipe>) call(registry, "readSnapshot",
                (Supplier<List<DisplayRecipe>>) this::captureSnapshot);
        return List.copyOf(snapshot);
    }

    private List<DisplayRecipe> captureSnapshot() {
        List<DisplayRecipe> out = new ArrayList<>();
        for (Object appliance : appliances) {
            for (Object recipe : collection(call(registry, "accurateRecipes", appliance))) {
                out.add(accurate(recipe, appliance));
            }
            for (Object recipe : collection(call(registry, "flexRecipes", appliance))) {
                out.add(flex(recipe, appliance));
            }
        }
        for (Object recipe : collection(call(registry, "choppingRecipes"))) out.add(chopping(recipe));
        for (Object recipe : collection(call(registry, "teapotRecipes"))) out.add(teapot(recipe));
        return List.copyOf(out);
    }

    private DisplayRecipe accurate(Object recipe, Object appliance) {
        List<String> info = new ArrayList<>(List.of(
                t("accurate.title", "森罗物语·精准加工"),
                t("accurate.weight_note", "候选成品按相对权重随机选择一个；权重不是百分比。")));
        String type = applianceName(appliance);
        int cooking = number(optional(recipe, "cookingTime", 0));
        if (type.equals("steamer") || type.equals("shawarma")) {
            info.add(processing(t("label.processing_time", "加工时间"), cooking, t("unit.tick", " tick")));
        }
        if (type.equals("millstone")) {
            info.add(processing(t("label.rotations", "研磨圈数"),
                    number(optional(recipe, "rotations", 0)), t("unit.round", " 圈")));
        }
        List<String> resultLore = strings(optional(recipe, "lore", List.of()));
        List<RecipeResult> outputs = new ArrayList<>();
        int amount = Math.max(1, number(call(recipe, "resultCount")));
        for (Object result : collection(call(recipe, "results"))) {
            RecipeItem output = item(call(result, "key"));
            if (!resultLore.isEmpty()) output = new RecipeItem(output.provider(), output.id(), null, null, resultLore);
            outputs.add(new RecipeResult(output, amount, -1,
                    t("note.relative_weight", "相对权重：{weight}",
                            "weight", number(call(result, "weight")))));
        }
        return display("accurate", recipe, appliance,
                List.of(ingredient(call(recipe, "input"), 1, RecipeIngredient.Role.INGREDIENT)), outputs, info);
    }

    private DisplayRecipe flex(Object recipe, Object appliance) {
        List<RecipeIngredient> inputs = new ArrayList<>();
        Object raw = call(recipe, "perfect");
        if (!(raw instanceof Map<?, ?> perfect)) throw new IllegalStateException("KC perfect is not a map");
        perfect.forEach((key, amount) -> inputs.add(ingredient(key, number(amount), RecipeIngredient.Role.INGREDIENT)));
        Object carrier = call(recipe, "carrier");
        if (carrier != null && !key(carrier).equals("minecraft:air")) {
            inputs.add(ingredient(carrier, 1, RecipeIngredient.Role.CONTAINER));
        }
        List<String> info = new ArrayList<>(List.of(
                t("flex.title", "森罗物语·动态烹饪"),
                t("flex.ratio_note", "原料数量表示理想配比；实际投料可改变成品份数及品质。"),
                t("flex.output_note", "展示基础成品；品质名称、Lore、食物属性由实际烹饪决定。")));
        if (carrier == null || key(carrier).equals("minecraft:air")) {
            info.add(t("flex.no_container", "空手盛出，无需容器。"));
        }
        if (Boolean.TRUE.equals(optional(recipe, "useEquivalentFoods", true))) {
            info.add(t("flex.equivalent_foods", "允许等效食材替代，以森罗当前食材分组为准。"));
        }
        if (Boolean.TRUE.equals(optional(recipe, "useSeasonings", true))) {
            info.add(t("flex.seasonings", "允许调味品；调味品不参与理想配比的品质计算。"));
        }
        info.add(t("flex.quality_ratio", "投料越接近理想配比，品质越高。"));
        info.add(t("flex.extra_penalty", "多放杂料会拉低品质。"));
        Collection<?> liquids = collection(call(recipe, "liquids"));
        if (liquids.isEmpty() && applianceName(appliance).equals("stockpot")) {
            info.add(t("flex.any_liquid", "不限汤底。"));
        }
        for (Object liquid : liquids) {
            inputs.add(liquid(liquid));
        }
        if (applianceName(appliance).equals("pot")) {
            info.add(processing(t("label.stir_fry_count", "翻炒次数"),
                    number(optional(recipe, "stirFryCount", 0)), t("unit.times", " 次")));
            info.add(t("flex.heat_oil", "默认需要热源与上油；其他插件可动态修改烹饪条件。"));
        } else {
            info.add(processing(t("label.cooking_time", "烹饪时间"),
                    number(optional(recipe, "cookingTime", 0)), t("unit.tick", " tick")));
        }
        return display("flex", recipe, appliance, inputs,
                List.of(new RecipeResult(item(call(recipe, "result")), 1, -1,
                        t("flex.result_note", "示例：1 份，实际份数随投料变化"))), info);
    }

    private DisplayRecipe chopping(Object recipe) {
        Object appliance = appliance("chopping_board");
        String mode = key(call(recipe, "mode")).toUpperCase(Locale.ROOT);
        List<RecipeIngredient> inputs = new ArrayList<>(List.of(ingredient(call(recipe, "input"), 1, RecipeIngredient.Role.INGREDIENT)));
        List<RecipeItem> knifeChoices = knifeChoices();
        if (!knifeChoices.isEmpty()) inputs.add(new RecipeIngredient(knifeChoices, 1, -1, RecipeIngredient.Role.TOOL));
        List<String> info = new ArrayList<>(List.of(
                t("chopping.title", "森罗物语·砧板"),
                t("chopping.stage", "切配刀数：{stage}", "stage", number(call(recipe, "stage")))));
        if (knifeChoices.isEmpty()) {
            info.add(t("chopping.knife_note", "需要菜刀；可用工具以实际砧板配置和森罗标签为准。"));
        }
        List<RecipeResult> outputs = new ArrayList<>();
        boolean independent = mode.equals("MULTI_RANDOM");
        for (Object result : collection(call(recipe, "results"))) outputs.add(choppingResult(result, independent, false));
        switch (mode) {
            case "SINGLE_EXTRA" -> {
                info.add(t("chopping.single_extra", "主产物按相对权重选一个；副产物分别独立判定。"));
                for (Object result : collection(call(recipe, "extras"))) outputs.add(choppingResult(result, true, true));
            }
            case "MULTI_RANDOM" -> info.add(t("chopping.multi_random",
                    "各产物独立判定；全部未命中时按权重保底抽一个。所示百分比不是含保底的最终掉率。"));
            default -> info.add(t("chopping.weighted", "按相对权重随机选择一个产物；权重不是百分比。"));
        }
        return display("chopping", recipe, appliance, inputs, outputs, info);
    }

    private RecipeResult choppingResult(Object result, boolean independent, boolean extra) {
        int weight = number(call(result, "weight"));
        String note = extra ? t("note.byproduct_prefix", "副产物 · ") : "";
        note += independent
                ? t("note.independent", "独立判定：{percent}%（配置值 {weight}）",
                "percent", Math.max(0, Math.min(100, weight)), "weight", weight)
                : t("note.relative_weight", "相对权重：{weight}", "weight", weight);
        return new RecipeResult(item(call(result, "key")), Math.max(1, number(call(result, "count"))), -1, note);
    }

    private DisplayRecipe teapot(Object recipe) {
        Object fluid = call(recipe, "fluid");
        List<String> info = List.of(
                t("teapot.title", "森罗物语·茶壶"),
                processing(t("label.brew_time", "煮制时间"), number(call(recipe, "time")), t("unit.tick", " tick")));
        return display("teapot", recipe, appliance("teapot"),
                List.of(ingredient(call(recipe, "input"), number(call(recipe, "ingredientCount")), RecipeIngredient.Role.INGREDIENT), liquid(fluid)),
                List.of(new RecipeResult(item(call(recipe, "result")), Math.max(1, number(call(recipe, "resultCount"))), -1, "")), info);
    }

    private DisplayRecipe display(String family, Object recipe, Object appliance, List<RecipeIngredient> inputs,
                                  List<RecipeResult> outputs, List<String> info) {
        inputs = withInformationPanels(inputs);
        inputs = placeIngredients(inputs);
        outputs = placeResults(outputs);
        String type = applianceName(appliance);
        Object configuredIcon = optional(style, "applianceIcon", null, appliance);
        String icon = configuredIcon == null ? "kaleidoscopecookery:" + (type.equals("shawarma") ? "shawarma_spit" : type) : key(configuredIcon);
        String name = String.valueOf(optional(style, "applianceName", defaultName(type), appliance));
        if (!name.startsWith("森罗物语·")) {
            name = t("station.prefix", "森罗物语·") + name;
        }
        RecipeWorkstation station = new RecipeWorkstation("kc:" + type, item(icon), name);
        return new DisplayRecipe("kc:" + family + "/" + type + "/" + key(call(recipe, "id")).replace(':', '/'),
                station, this.layout, inputs, outputs, info, PERMISSION, player -> true);
    }

    private List<RecipeIngredient> withInformationPanels(List<RecipeIngredient> inputs) {
        if (this.layout.rows() < 4
                || this.layout.symbolAt(INFORMATION_SLOT_LEFT) == 'A'
                || this.layout.symbolAt(INFORMATION_SLOT_LEFT) == 'B'
                || this.layout.symbolAt(INFORMATION_SLOT_RIGHT) == 'A'
                || this.layout.symbolAt(INFORMATION_SLOT_RIGHT) == 'B') {
            return inputs;
        }
        List<RecipeIngredient> values = new ArrayList<>(inputs);
        for (int slot : List.of(INFORMATION_SLOT_LEFT, INFORMATION_SLOT_RIGHT)) {
            RecipeItem marker = RecipeItem.of(INFORMATION_PROVIDER, "minecraft:paper");
            values.add(new RecipeIngredient(List.of(marker), 1, slot, RecipeIngredient.Role.INFO));
        }
        return List.copyOf(values);
    }

    /** 两个固定信息槽由渲染器替换为同一份通用配方说明。 */
    public static boolean isInformationIngredient(String recipeId, RecipeIngredient ingredient) {
        if (recipeId == null || !recipeId.startsWith("kc:")
                || ingredient == null || ingredient.role() != RecipeIngredient.Role.INFO
                || ingredient.slot() != INFORMATION_SLOT_LEFT
                && ingredient.slot() != INFORMATION_SLOT_RIGHT
                || ingredient.choices().size() != 1) return false;
        RecipeItem item = ingredient.choices().getFirst();
        return item.provider().equals(INFORMATION_PROVIDER) && item.id().equals("minecraft:paper");
    }

    /** 返回只应显示在对应原料/产物 Lore 中的 KC 本地化备注。 */
    public static String itemNoteKey(String recipeId, RecipeIngredient.Role role) {
        if (recipeId == null || !recipeId.startsWith("kc:")) return null;
        return switch (role) {
            case CONTAINER -> "flex.container";
            case TOOL -> recipeId.startsWith("kc:chopping/") ? "chopping.knife_note" : null;
            case INFO -> {
                if (recipeId.startsWith("kc:flex/")) yield "flex.liquid_item";
                if (recipeId.startsWith("kc:teapot/")) yield "teapot.liquid";
                yield null;
            }
            default -> null;
        };
    }

    private List<RecipeIngredient> placeIngredients(List<RecipeIngredient> inputs) {
        List<Integer> slots = contentSlots('A');
        if (slots.isEmpty()) return inputs;
        Set<Integer> used = new HashSet<>();
        for (RecipeIngredient input : inputs) if (input.slot() >= 0) used.add(input.slot());
        List<RecipeIngredient> placed = new ArrayList<>(inputs.size());
        int next = 0;
        for (RecipeIngredient input : inputs) {
            int slot = input.slot();
            if (slot < 0) {
                while (next < slots.size() && used.contains(slots.get(next))) next++;
                if (next < slots.size()) {
                    slot = slots.get(next++);
                    used.add(slot);
                }
            }
            placed.add(new RecipeIngredient(input.choices(), input.amount(), slot, input.role()));
        }
        return List.copyOf(placed);
    }

    private List<RecipeResult> placeResults(List<RecipeResult> outputs) {
        List<Integer> slots = contentSlots('B');
        if (slots.isEmpty()) return outputs;
        Set<Integer> used = new HashSet<>();
        for (RecipeResult result : outputs) if (result.slot() >= 0) used.add(result.slot());
        List<RecipeResult> placed = new ArrayList<>(outputs.size());
        int next = 0;
        for (RecipeResult result : outputs) {
            int slot = result.slot();
            if (slot < 0) {
                while (next < slots.size() && used.contains(slots.get(next))) next++;
                if (next < slots.size()) {
                    slot = slots.get(next++);
                    used.add(slot);
                }
            }
            placed.add(new RecipeResult(result.item(), result.amount(), slot, result.note(), result.viewerLore()));
        }
        return List.copyOf(placed);
    }

    private List<Integer> contentSlots(char symbol) {
        List<Integer> slots = new ArrayList<>();
        List<String> pattern = this.layout.pattern();
        for (int row = 0; row < pattern.size(); row++) {
            String line = pattern.get(row);
            for (int column = 0; column < line.length(); column++) {
                if (line.charAt(column) == symbol) slots.add(row * 9 + column);
            }
        }
        return slots;
    }

    /** 为已提供 KC GUI 贴图的六种厨具返回背景字体图片 ID。 */
    public static String backgroundImage(String workstationId) {
        if (workstationId == null) return null;
        return switch (workstationId) {
            case "kc:chopping_board" -> "cer_kc_gui:chopping_board_gui";
            case "kc:millstone" -> "cer_kc_gui:millstone_gui";
            case "kc:pot" -> "cer_kc_gui:pot_gui";
            case "kc:shawarma" -> "cer_kc_gui:shawarma_spit_gui";
            case "kc:stockpot" -> "cer_kc_gui:stockpot_gui";
            case "kc:steamer" -> "cer_kc_gui:steamer_full_lid";
            case "kc:teapot" -> "cer_kc_gui:teapot_gui";
            default -> null;
        };
    }

    private RecipeIngredient liquid(Object fluid) {
        Object configuredIcon = optional(style, "liquidIcon", null, fluid);
        String id = configuredIcon == null ? key(fluid) : key(configuredIcon);
        if (id.equals("minecraft:water")) id = "minecraft:water_bucket";
        if (id.equals("minecraft:lava")) id = "minecraft:lava_bucket";
        // 液体属于条件，并不表示每条配方都会消耗一桶。
        return ingredient(id, 1, RecipeIngredient.Role.INFO);
    }

    private List<RecipeItem> knifeChoices() {
        Set<String> ids = new LinkedHashSet<>();
        for (Object id : collection(optional(knives, "registeredIds", List.of()))) ids.add(key(id));
        if (tags != null) {
            Map<String, Object> tagKeys = new LinkedHashMap<>();
            for (Object tag : collection(call(tags, "keys"))) tagKeys.put(key(tag), tag);
            collectTag("kaleidoscopecookery:kitchen_knife", tagKeys, new LinkedHashSet<>(), ids);
        }
        return ids.stream().sorted().map(KaleidoscopeRecipeSource::item).toList();
    }

    private void collectTag(String id, Map<String, Object> keys, Set<String> seen, Set<String> items) {
        // 标签允许引用自身或其他标签；visited 集合同时防止循环，并限制异常深度的数据。
        if (!seen.add(id) || seen.size() > 64) return;
        Object tag = keys.get(id);
        if (tag == null) return;
        for (Object member : collection(call(tags, "members", tag))) {
            String value = key(member);
            if (value.startsWith("#")) collectTag(value.substring(1), keys, seen, items);
            else items.add(value);
        }
    }

    private Object appliance(String name) {
        return appliances.stream().filter(value -> applianceName(value).equals(name)).findFirst()
                .orElseThrow(() -> new IllegalStateException("KC does not expose appliance " + name));
    }

    private static String applianceName(Object appliance) { return key(appliance).toLowerCase(Locale.ROOT); }

    private String defaultName(String type) {
        return switch (type) {
            case "pot" -> t("appliance.pot", "炒锅");
            case "stockpot" -> t("appliance.stockpot", "高汤锅");
            case "steamer" -> t("appliance.steamer", "蒸笼");
            case "shawarma" -> t("appliance.shawarma", "沙威玛烤架");
            case "millstone" -> t("appliance.millstone", "石磨");
            case "chopping_board" -> t("appliance.chopping_board", "砧板");
            case "teapot" -> t("appliance.teapot", "茶壶");
            default -> type;
        };
    }

    private String processing(String label, int value, String unit) {
        return value > 0
                ? t("processing.value", "{label}：{value}{unit}", "label", label, "value", value, "unit", unit)
                : t("processing.inherited", "{label}：继承厨具默认值（0 不表示立即完成）", "label", label);
    }

    private String t(String key, String fallback, Object... arguments) {
        return this.text.text(key, fallback, arguments);
    }

    private static RecipeIngredient ingredient(Object key, int amount, RecipeIngredient.Role role) {
        return new RecipeIngredient(List.of(item(key)), Math.max(1, amount), -1, role);
    }

    private static RecipeItem item(Object value) {
        String id = key(value);
        if (id.startsWith("craftengine:")) return RecipeItem.of("craftengine", id.substring("craftengine:".length()));
        return RecipeItem.of(id);
    }

    private static String key(Object value) {
        if (value == null) throw new IllegalStateException("KC supplied a null identifier");
        return value instanceof String || value instanceof Enum<?> ? value.toString()
                : String.valueOf(optional(value, "asString", value.toString()));
    }

    private static int number(Object value) {
        if (value instanceof Number number) return number.intValue();
        throw new IllegalStateException("KC supplied a non-numeric field: " + value);
    }

    private static Collection<?> collection(Object value) {
        if (value instanceof Collection<?> collection) return collection;
        throw new IllegalStateException("KC supplied a non-collection field");
    }

    private static List<String> strings(Object value) { return collection(value).stream().map(String::valueOf).toList(); }

    private record Bindings(Object registry, List<?> appliances, Object style, Object tags, Object knives) {}

    private static Bindings load(Plugin source) {
        try {
            ClassLoader loader = source.getClass().getClassLoader();
            Class<?> api = Class.forName(API, true, loader);
            Class<?> appliance = Class.forName(APPLIANCE, true, loader);
            Object registry = api.getMethod("foodRecipes").invoke(null);
            if (!appliance.isEnum()) throw new IllegalStateException("KC appliance API is not an enum");
            return new Bindings(registry, List.of(appliance.getEnumConstants()), apiOptional(api, "recipeMenuStyle"),
                    apiOptional(api, "itemTags"), apiOptional(api, "choppingBoardKnives"));
        } catch (ReflectiveOperationException | LinkageError error) {
            throw new IllegalStateException("Unsupported Kaleidoscope Cookery recipe API", error);
        }
    }

    private static Object apiOptional(Class<?> api, String name) throws ReflectiveOperationException {
        try { return api.getMethod(name).invoke(null); }
        catch (NoSuchMethodException absent) { return null; }
    }

    private static Object optional(Object target, String name, Object fallback, Object... args) {
        if (target == null) return fallback;
        Method method = method(target, name, args);
        return method == null ? fallback : invoke(target, method, args);
    }

    private static Object call(Object target, String name, Object... args) {
        return invoke(target, requireMethod(target, name, args), args);
    }

    private static Method requireMethod(Object target, String name, Object... args) {
        Method method = method(target, name, args);
        if (method == null) throw new IllegalStateException("Unsupported KC API method: " + name);
        return method;
    }

    private static Method method(Object target, String name, Object[] args) {
        if (target == null) return null;
        // KC 会通过不同实现暴露同名重载；按参数类型选择与传入值兼容的第一个方法。
        for (Method method : target.getClass().getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != args.length) continue;
            boolean matches = true;
            for (int index = 0; index < args.length; index++) {
                if (args[index] != null && !method.getParameterTypes()[index].isInstance(args[index])) { matches = false; break; }
            }
            if (matches) return method;
        }
        return null;
    }

    private static Object invoke(Object target, Method method, Object[] args) {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException failure) {
            // 保留 KC 业务异常的原始类型，调用方仍可按 RuntimeException 的正常路径处理。
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("KC API method failed: " + method.getName(), cause);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("KC API method is inaccessible: " + method.getName(), failure);
        }
    }
}
