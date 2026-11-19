package dev.che1s.craftenginerecipe.integration.trailtalesdelight;

import dev.che1s.craftenginerecipe.api.DisplayRecipe;
import dev.che1s.craftenginerecipe.api.RecipeIngredient;
import dev.che1s.craftenginerecipe.api.RecipeItem;
import dev.che1s.craftenginerecipe.api.RecipeLayout;
import dev.che1s.craftenginerecipe.api.RecipeResult;
import dev.che1s.craftenginerecipe.api.RecipeWorkstation;
import dev.che1s.craftenginerecipe.api.extension.RecipeSource;
import dev.che1s.craftenginerecipe.api.extension.SourceContext;
import dev.che1s.craftenginerecipe.api.extension.SourceText;
import net.kyori.adventure.text.Component;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bridges the public FarmersDelight recipe API registered by TrailTalesDelight.
 *
 * <p>The extension intentionally loads all source classes reflectively so CER does not compile
 * against either downstream plugin. TrailTalesDelight recipes can then use their own source
 * directory and workstation identity instead of the generic Farmer's Delight addon namespace.</p>
 */
public final class TrailTalesDelightRecipeSource implements RecipeSource {
    public static final String PLUGIN_NAME = "TrailTalesDelight";
    public static final String PLUGIN_MAIN = "dev.che1s.trailtalesdelight.TrailTalesDelightPlugin";

    static final String RECIPE_TYPE_ID = "trailandtales_delight:trail_recipes";
    private static final String FARMERS_DELIGHT = "FarmersDelight";
    private static final String API_CLASS = "com.huidu.farmersdelight.api.FarmersDelightApi";
    private static final String ITEMS_CLASS = "com.huidu.farmersdelight.api.item.FarmersDelightItems";
    private static final String PERMISSION = "farmersdelight.command.recipe";
    private static final String STATION_ID = "trailandtalesdelight:pottery_cooking_pot";
    private static final long POLL_NANOS = 5_000_000_000L;
    private static final List<Integer> INPUT_SLOTS = List.of(19, 20, 21, 28, 29, 30);
    private static final int TOOL_SLOT = 38;
    private static final int CONTAINER_SLOT = 41;
    private static final int RESULT_SLOT = 43;
    // Trail & Tales Delight's own detail layout from gui.yml.
    private static final List<String> PATTERN = List.of(
            "XXXXXXXXX",
            "XXXXXXXXX",
            "XIIIXXXXX",
            "XIIIXXXXX",
            "XXTXXCXRX",
            "XXXXXXXXX");

    private final Plugin source;
    private final Plugin farmersDelight;
    private final ClassLoader sourceLoader;
    private final SourceText text;
    private long checkedAt;
    private long revision;
    private long fingerprint = Long.MIN_VALUE;

    public TrailTalesDelightRecipeSource(SourceContext context) {
        this.source = context.sourcePlugin();
        this.farmersDelight = Bukkit.getPluginManager().getPlugin(FARMERS_DELIGHT);
        this.sourceLoader = this.source == null ? getClass().getClassLoader()
                : this.source.getClass().getClassLoader();
        this.text = context.text("trailandtalesdelight");
    }

    @Override
    public String id() {
        return "trailandtalesdelight";
    }

    @Override
    public boolean ready() {
        if (this.source == null || !this.source.isEnabled()
                || this.farmersDelight == null || !this.farmersDelight.isEnabled()) {
            return false;
        }
        try {
            Object api = api();
            return api != null
                    && Boolean.TRUE.equals(call(api, "isAvailable"))
                    && Boolean.TRUE.equals(call(api, "isContentLoaded"))
                    && recipeType(api) != null;
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    @Override
    public synchronized long revision() {
        long now = System.nanoTime();
        if (this.checkedAt != 0 && now - this.checkedAt < POLL_NANOS) return this.revision;
        this.checkedAt = now;
        long next = ready() ? fingerprint() : 0L;
        if (next != this.fingerprint) {
            this.fingerprint = next;
            this.revision++;
        }
        return this.revision;
    }

    @Override
    public List<DisplayRecipe> capture() {
        Object type = recipeType(api());
        if (type == null) return List.of();
        List<DisplayRecipe> recipes = new ArrayList<>();
        for (Object view : collection(call(type, "recipes"))) {
            recipes.add(recipe(type, view));
        }
        return List.copyOf(recipes);
    }

    private DisplayRecipe recipe(Object type, Object view) {
        String id = string(call(view, "id"));
        List<RecipeIngredient> ingredients = new ArrayList<>();
        int inputIndex = 0;
        for (ItemStack stack : stacks(call(view, "inputs"))) {
            int slot = inputIndex < INPUT_SLOTS.size() ? INPUT_SLOTS.get(inputIndex) : -1;
            ingredients.add(new RecipeIngredient(List.of(item(stack)),
                    Math.max(1, stack.getAmount()), slot, RecipeIngredient.Role.INGREDIENT));
            inputIndex++;
        }

        Map<String, List<ItemStack>> display = displaySlots(call(view, "displaySlots"));
        addFirst(ingredients, display.get("tool"), TOOL_SLOT, RecipeIngredient.Role.TOOL);
        addFirst(ingredients, display.get("return"), CONTAINER_SLOT, RecipeIngredient.Role.CONTAINER);
        addFirst(ingredients, display.get("container"), CONTAINER_SLOT, RecipeIngredient.Role.CONTAINER);
        // Match Farmer's Delight native detail pages: the source GUI keeps its own background and
        // navigation, while a transparent carrier covers the leftover return-button slot.
        ingredients.removeIf(ingredient -> ingredient.slot() == 0);
        ingredients.add(new RecipeIngredient(List.of(fakeButton()), 1, 0, RecipeIngredient.Role.INFO));

        ItemStack result = stack(call(view, "result"));
        if (!usable(result)) result = stack(call(type, "icon"));
        if (!usable(result)) result = new ItemStack(Material.PAPER);

        Object detail = call(type, "detailLayout");
        Component title = detail == null ? null : component(call(detail, "title"));
        String layoutTitle = title == null ? null : RecipeLayout.jsonTitle(title);
        RecipeLayout layout = nativeUiLayout(layoutTitle);

        String stationTitle = this.text.deferred("station.title", "Trail & Tales Delight·陶锅");
        RecipeWorkstation station = new RecipeWorkstation(STATION_ID,
                usable(stack(call(type, "icon"))) ? item(stack(call(type, "icon")))
                        : RecipeItem.of("trailandtales_delight:pottery_cooking_pot"),
                stationTitle);
        return new DisplayRecipe(displayId(id), station, layout,
                ingredients,
                List.of(new RecipeResult(item(result), Math.max(1, result.getAmount()), RESULT_SLOT,
                        null, player -> detailInfoLines(view, player))),
                List.of(), PERMISSION, player -> this.source != null && this.source.isEnabled());
    }

    static String displayId(String sourceId) {
        return "trailandtalesdelight:" + normalizedId(sourceId);
    }

    static RecipeLayout nativeUiLayout(String title) {
        return new RecipeLayout(6, title, -1, -1, -1, -1, -1, PATTERN, true);
    }

    private static RecipeItem fakeButton() {
        return new RecipeItem("cer_fd_gui", "minecraft:air", null, null, null);
    }

    private void addFirst(List<RecipeIngredient> ingredients, List<ItemStack> stacks,
                          int slot, RecipeIngredient.Role role) {
        if (stacks == null || stacks.isEmpty()) return;
        ItemStack stack = stacks.getFirst();
        if (!usable(stack)) return;
        if (ingredients.stream().anyMatch(value -> value.slot() == slot)) return;
        ingredients.add(new RecipeIngredient(List.of(item(stack)),
                Math.max(1, stack.getAmount()), slot, role));
    }

    /**
     * FarmersDelight exposes these lines per viewer, so keep the callback lazy instead of invoking
     * the player-required API while the source snapshot is being captured.
     */
    static List<Component> detailInfoLines(Object view, Player player) {
        Object raw = call(view, "detailInfoLines", player);
        if (!(raw instanceof Iterable<?> iterable)) return List.of();
        List<Component> lines = new ArrayList<>();
        for (Object value : iterable) {
            Component line = component(value);
            if (line != null && !lines.contains(line)) lines.add(line);
        }
        return List.copyOf(lines);
    }

    private long fingerprint() {
        try {
            Object type = recipeType(api());
            if (type == null) return 0L;
            long hash = 1125899906842597L;
            for (Object view : collection(call(type, "recipes"))) {
                hash = 31 * hash + string(call(view, "id")).hashCode();
                ItemStack result = stack(call(view, "result"));
                hash = 31 * hash + itemId(result).hashCode();
            }
            return hash;
        } catch (RuntimeException | LinkageError ignored) {
            return 0L;
        }
    }

    private Object api() {
        return invokeStatic(API_CLASS, "get");
    }

    private Object recipeType(Object api) {
        return api == null ? null : call(api, "recipeType", RECIPE_TYPE_ID);
    }

    private RecipeItem item(ItemStack stack) {
        String id = itemId(stack);
        String provider = id.startsWith("minecraft:") ? "minecraft" : "craftengine";
        return RecipeItem.stack(provider, id, stack);
    }

    private String itemId(ItemStack stack) {
        if (!usable(stack)) return "minecraft:air";
        try {
            String id = string(invokeStatic(ITEMS_CLASS, "idOf", stack));
            if (!id.isBlank()) return id;
        } catch (RuntimeException | LinkageError ignored) {
            // Paper's material identity remains a safe fallback.
        }
        try {
            var custom = CraftEngineItems.getCustomItemId(stack);
            if (custom != null) return custom.toString();
        } catch (RuntimeException | LinkageError ignored) {
            // CraftEngine may not be ready while an extension is probed.
        }
        return stack.getType().getKey().toString();
    }

    private static String normalizedId(String id) {
        return id == null || id.isBlank() ? "unknown"
                : id.replace(':', '/').replaceAll("[^a-zA-Z0-9_./-]", "_");
    }

    private Map<String, List<ItemStack>> displaySlots(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) return Map.of();
        Map<String, List<ItemStack>> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            result.put(String.valueOf(entry.getKey()), stacks(entry.getValue()));
        }
        return result;
    }

    private List<ItemStack> stacks(Object raw) {
        if (!(raw instanceof Iterable<?> iterable)) return List.of();
        List<ItemStack> result = new ArrayList<>();
        for (Object value : iterable) {
            ItemStack stack = stack(value);
            if (usable(stack)) result.add(stack);
        }
        return List.copyOf(result);
    }

    private Object invokeStatic(String className, String method, Object... arguments) {
        try {
            ClassLoader loader = this.farmersDelight == null
                    ? this.sourceLoader : this.farmersDelight.getClass().getClassLoader();
            Class<?> type = Class.forName(className, true, loader);
            Method target = compatibleMethod(type.getMethods(), method, arguments);
            if (target == null) throw new IllegalStateException("Missing static method " + className + "#" + method);
            if (!target.canAccess(null)) target.trySetAccessible();
            return target.invoke(null, arguments);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException(cause);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Cannot invoke " + className + "#" + method, error);
        }
    }

    static Object call(Object target, String method, Object... arguments) {
        if (target == null) return null;
        try {
            Method selected = compatibleMethod(target.getClass().getMethods(), method, arguments);
            if (selected == null) throw new IllegalStateException("Missing method " + method);
            // ViewableRecipe implementations are commonly private nested records.
            // Their public interface methods are still inaccessible without opening the method.
            if (!selected.canAccess(target)) selected.trySetAccessible();
            return selected.invoke(target, arguments);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException(cause);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Cannot invoke " + method + ": " + error.getMessage(), error);
        }
    }

    private static Method compatibleMethod(Method[] candidates, String name, Object[] arguments) {
        for (Method candidate : candidates) {
            if (!candidate.getName().equals(name)
                    || candidate.getParameterCount() != arguments.length) continue;
            if (accepts(candidate.getParameterTypes(), arguments)) return candidate;
        }
        return null;
    }

    private static boolean accepts(Class<?>[] parameters, Object[] arguments) {
        for (int index = 0; index < parameters.length; index++) {
            Object argument = arguments[index];
            if (argument == null) {
                if (parameters[index].isPrimitive()) return false;
                continue;
            }
            Class<?> parameter = wrap(parameters[index]);
            if (!parameter.isInstance(argument)) return false;
        }
        return true;
    }

    private static Class<?> wrap(Class<?> type) {
        if (!type.isPrimitive()) return type;
        if (type == boolean.class) return Boolean.class;
        if (type == byte.class) return Byte.class;
        if (type == short.class) return Short.class;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == float.class) return Float.class;
        if (type == double.class) return Double.class;
        if (type == char.class) return Character.class;
        return Void.class;
    }

    private static Component component(Object value) {
        return value instanceof Component component ? component : null;
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static List<?> collection(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    private static ItemStack stack(Object value) {
        return value instanceof ItemStack stack ? stack.clone() : null;
    }

    private static boolean usable(ItemStack stack) {
        return stack != null && stack.getType().isItem() && !stack.getType().isAir();
    }
}
