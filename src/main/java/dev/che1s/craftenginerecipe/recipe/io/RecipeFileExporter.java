package dev.che1s.craftenginerecipe.recipe.io;

import dev.che1s.craftenginerecipe.api.DisplayRecipe;
import dev.che1s.craftenginerecipe.api.RecipeIngredient;
import dev.che1s.craftenginerecipe.api.RecipeItem;
import dev.che1s.craftenginerecipe.api.RecipeLayout;
import dev.che1s.craftenginerecipe.api.RecipeResult;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** 生成可编辑的 TrMenu 风格配方文档，每个合成器一个文件。 */
public final class RecipeFileExporter {
    private static final String SYMBOLS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    private RecipeFileExporter() {
    }

    public static Result export(Path recipesDirectory, String source, List<DisplayRecipe> recipes) throws IOException {
        Path target = recipesDirectory.resolve(source);
        if (Files.exists(target)) return new Result(false, 0, 0);
        Files.createDirectories(recipesDirectory);

        Map<String, List<DisplayRecipe>> grouped = new LinkedHashMap<>();
        recipes.stream().sorted(Comparator.comparing(DisplayRecipe::id)).forEach(recipe ->
                grouped.computeIfAbsent(recipe.workstation().id(), ignored -> new ArrayList<>()).add(recipe));
        Map<String, String> files = new LinkedHashMap<>();
        for (var entry : grouped.entrySet()) {
            String fileName = uniqueFile(entry.getKey(), files);
            files.put(fileName, dump(source, entry.getKey(), entry.getValue()));
        }

        Path staging = recipesDirectory.resolve("." + source + "-export-" + UUID.randomUUID());
        Files.createDirectories(staging);
        try {
            for (var entry : files.entrySet()) {
                Files.writeString(staging.resolve(entry.getKey()), entry.getValue(), StandardCharsets.UTF_8);
            }
            try {
                Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(staging, target);
            }
        } catch (IOException error) {
            deleteRecursively(staging);
            throw error;
        }
        return new Result(true, files.size(), recipes.size());
    }

    public record Result(boolean installed, int files, int recipes) {
    }

    private static String uniqueFile(String workstation, Map<String, String> existing) {
        String base = sanitize(workstation);
        String name = base + ".yml";
        if (!existing.containsKey(name)) return name;
        return base + "-" + Integer.toHexString(workstation.hashCode()) + ".yml";
    }

    private static String sanitize(String workstation) {
        String path = workstation;
        int separator = path.indexOf(':');
        if (separator >= 0) path = path.substring(separator + 1);
        String sanitized = path.replaceAll("[^a-z0-9._-]+", "_").replaceAll("^_+|_+$", "");
        return sanitized.isBlank() ? "workstation" : sanitized;
    }

    private static String dump(String source, String workstation, List<DisplayRecipe> recipes) {
        Map<String, Object> root = new LinkedHashMap<>();
        putTitle(root, recipes.getFirst().layout().title() == null
                ? recipes.getFirst().workstation().name() : recipes.getFirst().layout().title());
        root.put("Layout", transparentLayout(recipes.stream().mapToInt(recipe -> recipe.layout().rows()).max().orElse(6)));

        Map<String, Object> station = new LinkedHashMap<>();
        station.put("id", workstation);
        if (recipes.getFirst().workstation().name() != null) {
            station.put("name", recipes.getFirst().workstation().name());
        }
        station.put("icon", item(recipes.getFirst().workstation().icon()));
        root.put("Workstation", station);

        Map<String, Object> menus = new LinkedHashMap<>();
        for (DisplayRecipe recipe : recipes) menus.put(recipe.id(), menu(recipe));
        root.put("Recipes", menus);
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setIndent(2);
        options.setIndicatorIndent(0);
        options.setPrettyFlow(true);
        options.setAllowUnicode(true);
        options.setSplitLines(false);
        String body = new Yaml(options).dump(root);
        return "# Generated once from " + source + ". After generation CER reads this file directly.\n"
                + "# Syntax follows TrMenu's Title/Layout/Icons model; Recipe markers define CER content.\n" + body;
    }

    private static List<String> transparentLayout(int rows) {
        List<String> result = new ArrayList<>();
        for (int row = 0; row < Math.max(1, Math.min(6, rows)); row++) result.add("         ");
        return result;
    }

    private static Map<String, Object> menu(DisplayRecipe recipe) {
        Map<String, Object> menu = new LinkedHashMap<>();
        if (recipe.layout().title() != null) putTitle(menu, recipe.layout().title());
        if (!recipe.description().isEmpty()) menu.put("Description", recipe.description());
        if (recipe.permission() != null) menu.put("Permission", recipe.permission());
        if (recipe.layout().page() != null) {
            menu.put("Options", Map.of("Page", recipe.layout().page()));
        } else if (recipe.layout().nativeUi()) {
            menu.put("Options", Map.of("Native-UI", true));
        }

        RecipeLayout layout = recipe.layout();
        List<String> rows = transparentLayout(layout.rows());
        Map<String, Object> icons = new LinkedHashMap<>();
        int nextSymbol = 0;
        if (layout.workstationSlot() >= 0) {
            String symbol = nextSymbol(nextSymbol++);
            putControl(rows, layout.workstationSlot(), symbol.charAt(0));
            Map<String, Object> icon = new LinkedHashMap<>(displayItem(recipe.workstation().icon()));
            icon.put("Usage", "button");
            icon.put("Button", "workstation");
            icons.put(symbol, icon);
        }
        if (layout.informationSlot() >= 0) {
            String symbol = nextSymbol(nextSymbol++);
            putControl(rows, layout.informationSlot(), symbol.charAt(0));
            Map<String, Object> icon = new LinkedHashMap<>(display("minecraft:paper", null, null, null));
            icon.put("Usage", "button");
            icon.put("Button", "information");
            icon.put("Information", recipe.description());
            icons.put(symbol, icon);
        }
        if (layout.previousSlot() >= 0) {
            String symbol = nextSymbol(nextSymbol++);
            putControl(rows, layout.previousSlot(), symbol.charAt(0));
            Map<String, Object> icon = new LinkedHashMap<>(display("minecraft:spectral_arrow", null, null, null));
            icon.put("Usage", "button");
            icon.put("Button", "previous");
            icons.put(symbol, icon);
        }
        if (layout.backSlot() >= 0) {
            String symbol = nextSymbol(nextSymbol++);
            putControl(rows, layout.backSlot(), symbol.charAt(0));
            Map<String, Object> icon = new LinkedHashMap<>(display("minecraft:barrier", null, null, null));
            icon.put("Usage", "button");
            icon.put("Button", "back");
            icons.put(symbol, icon);
        }
        if (layout.nextSlot() >= 0) {
            String symbol = nextSymbol(nextSymbol++);
            putControl(rows, layout.nextSlot(), symbol.charAt(0));
            Map<String, Object> icon = new LinkedHashMap<>(display("minecraft:spectral_arrow", null, null, null));
            icon.put("Usage", "button");
            icon.put("Button", "next");
            icons.put(symbol, icon);
        }

        for (int index = 0; index < recipe.ingredients().size(); index++) {
            RecipeIngredient ingredient = recipe.ingredients().get(index);
            String symbol = nextSymbol(nextSymbol++);
            if (ingredient.slot() >= 0) putControl(rows, ingredient.slot(), symbol.charAt(0));
            Map<String, Object> icon = new LinkedHashMap<>();
            icon.put("Usage", ingredient.role() == RecipeIngredient.Role.INFO ? "decoration" : "ingredient");
            icon.put("Amount", ingredient.amount());
            boolean transparentDescription = recipe.id().startsWith("farmersdelight:special/")
                    && ingredient.slot() == 4 && ingredient.role() == RecipeIngredient.Role.INFO;
            if (ingredient.role() != RecipeIngredient.Role.INGREDIENT
                    && ingredient.role() != RecipeIngredient.Role.INFO) {
                icon.put("Role", ingredient.role().name());
            }
            if (ingredient.choices().size() > 1) {
                List<Map<String, Object>> choices = new ArrayList<>();
                for (RecipeItem choice : ingredient.choices()) {
                    choices.add(Map.of("Display", transparentDescription ? transparentItem(choice) : item(choice)));
                }
                icon.put("Choices", choices);
            } else if (!ingredient.choices().isEmpty()) {
                icon.put("Display", transparentDescription
                        ? transparentItem(ingredient.choices().getFirst())
                        : item(ingredient.choices().getFirst()));
            }
            icons.put(symbol, icon);
        }
        for (int index = 0; index < recipe.results().size(); index++) {
            RecipeResult result = recipe.results().get(index);
            String symbol = nextSymbol(nextSymbol++);
            if (result.slot() >= 0) putControl(rows, result.slot(), symbol.charAt(0));
            Map<String, Object> icon = new LinkedHashMap<>();
            icon.put("Display", item(result.item()));
            icon.put("Usage", "result");
            icon.put("Amount", result.amount());
            if (result.note() != null && !result.note().isBlank()) icon.put("Note", result.note());
            icons.put(symbol, icon);
        }
        menu.put("Layout", rows);
        menu.put("Icons", icons);
        return menu;
    }

    private static String nextSymbol(int index) {
        if (index >= SYMBOLS.length()) throw new IllegalStateException("Recipe has too many icons");
        return String.valueOf(SYMBOLS.charAt(index));
    }

    private static void putControl(List<String> rows, int slot, char symbol) {
        if (slot < 0) return;
        int row = slot / 9;
        char[] chars = rows.get(row).toCharArray();
        chars[slot % 9] = symbol;
        rows.set(row, new String(chars));
    }

    private static String title(String raw) {
        if (raw == null || !raw.startsWith(RecipeLayout.JSON_TITLE_PREFIX)) return raw;
        try {
            return MiniMessage.miniMessage().serialize(GsonComponentSerializer.gson()
                    .deserialize(raw.substring(RecipeLayout.JSON_TITLE_PREFIX.length())));
        } catch (RuntimeException ignored) {
            return "配方";
        }
    }

    private static void putTitle(Map<String, Object> target, String raw) {
        if (raw != null && raw.startsWith(RecipeLayout.JSON_TITLE_PREFIX)) {
            String json = raw.substring(RecipeLayout.JSON_TITLE_PREFIX.length());
            target.put("Title-Data", java.util.Base64.getEncoder()
                    .encodeToString(json.getBytes(StandardCharsets.UTF_8)));
        } else {
            target.put("Title", title(raw));
        }
    }

    private static Map<String, Object> display(String material, String name, List<String> lore, byte[] data) {
        Map<String, Object> display = new LinkedHashMap<>();
        display.put("Material", material);
        if (name != null) display.put("Name", name);
        if (lore != null) display.put("Lore", lore);
        if (data != null) display.put("Data", data);
        return Map.of("Display", display);
    }

    private static Map<String, Object> displayItem(RecipeItem item) {
        return Map.of("Display", item(item));
    }

    private static Map<String, Object> item(RecipeItem item) {
        Map<String, Object> display = new LinkedHashMap<>();
        display.put("Material", item.id());
        String inferred = item.id().startsWith("minecraft:") ? "minecraft" : "craftengine";
        if (!item.provider().equals(inferred)) display.put("Provider", item.provider());
        if (item.name() != null) display.put("Name", item.name());
        if (item.lore() != null) display.put("Lore", item.lore());
        ItemStack icon = item.icon();
        if (icon != null) {
            ItemMeta meta = icon.getItemMeta();
            if (meta != null && meta.hasItemModel()) {
                display.put("Item-Model", meta.getItemModel().toString());
            }
        }
        if (icon != null && !item.provider().equals("craftengine") && !item.provider().equals("minecraft")) {
            try {
                display.put("Data", icon.asOne().serializeAsBytes());
            } catch (RuntimeException | LinkageError ignored) {
                // 当前服务器无法序列化图标时，仍可使用材质或来源插件重新构建物品。
            }
        }
        return display;
    }

    private static Map<String, Object> transparentItem(RecipeItem item) {
        Map<String, Object> display = new LinkedHashMap<>();
        display.put("Material", "minecraft:air");
        if (item.name() != null) display.put("Name", item.name());
        if (item.lore() != null) display.put("Lore", item.lore());
        return display;
    }

    private static void deleteRecursively(Path directory) {
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }
}
