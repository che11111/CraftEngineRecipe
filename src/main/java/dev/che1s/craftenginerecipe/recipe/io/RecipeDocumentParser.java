package dev.che1s.craftenginerecipe.recipe.io;

import dev.che1s.craftenginerecipe.api.DisplayRecipe;
import dev.che1s.craftenginerecipe.api.RecipeIngredient;
import dev.che1s.craftenginerecipe.api.RecipeItem;
import dev.che1s.craftenginerecipe.api.RecipeLayout;
import dev.che1s.craftenginerecipe.api.RecipeResult;
import dev.che1s.craftenginerecipe.api.RecipeWorkstation;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 解析单个配方 YAML 文档。 */
final class RecipeDocumentParser {
    private static final List<Integer> KALEIDOSCOPE_INGREDIENT_SLOTS =
            List.of(5, 6, 7, 8, 14, 15, 16, 17, 23, 24, 25, 26);
    private static final List<Integer> KALEIDOSCOPE_RESULT_SLOTS =
            List.of(41, 42, 43, 44, 50, 51, 52, 53);
    private static final Set<String> LAYOUT_KEYS = Set.of("template", "type", "rows", "title", "workstation-slot",
            "information-slot", "previous-slot", "back-slot", "next-slot");
    private static final Set<String> ITEM_KEYS = Set.of("provider", "id", "name", "lore");
    private static final Set<String> INGREDIENT_KEYS = Set.of("provider", "id", "name", "lore", "item", "choices",
            "amount", "slot", "role");
    private static final Set<String> RESULT_KEYS = Set.of("provider", "id", "name", "lore", "item", "amount", "slot",
            "note");
    private final Path file;

    RecipeDocumentParser(Path file) { this.file = file; }

    Map<String, Object> read() throws IOException {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        try (Reader reader = Files.newBufferedReader(this.file, StandardCharsets.UTF_8)) {
            Object root = new Yaml(new SafeConstructor(options)).load(reader);
            return root == null ? Map.of() : this.map(root, "root");
        } catch (RuntimeException error) {
            throw this.error("root", "invalid YAML: " + error.getMessage(), error);
        }
    }

    boolean isMenuDocument(Map<String, Object> root) {
        Map<String, Object> values = lowerKeys(root);
        return values.containsKey("icons") || values.containsKey("layout")
                && (values.containsKey("title") || values.containsKey("recipes"));
    }

    /** 读取生成的配方文件所使用的 TrMenu 风格 Title/Layout/Icons 文档。 */
    List<DisplayRecipe> menuRecipes(Map<String, Object> root) throws IOException {
        Map<String, Object> document = lowerKeys(root);
        this.keys(document, "", Set.of("enabled", "title", "title-data", "layout", "icons", "recipes",
                "options", "workstation"));
        if (!this.bool(document, "enabled", true, "enabled")) return List.of();
        Object configured = document.get("recipes");
        if (configured == null) {
            throw this.error("recipes", "expected at least one recipe menu");
        }
        Map<String, Object> menus = this.preserveKeyMap(configured, "recipes");
        if (menus.isEmpty()) throw this.error("recipes", "expected at least one recipe menu");
        Map<String, Object> commonIcons = this.iconMap(document.get("icons"), "icons");
        List<DisplayRecipe> recipes = new ArrayList<>();
        for (var entry : menus.entrySet()) {
            String path = "recipes." + entry.getKey();
            Map<String, Object> definition = this.lowerKeys(this.map(entry.getValue(), path));
            this.keys(definition, path, Set.of("enabled", "title", "layout", "icons", "recipes",
                    "title-data", "options", "workstation", "permission", "description", "results"));
            if (!this.bool(definition, "enabled", true, path + ".enabled")) continue;
            Map<String, Object> merged = new LinkedHashMap<>(document);
            if (definition.containsKey("title") || definition.containsKey("title-data")) {
                merged.remove("title");
                merged.remove("title-data");
            }
            // 顶层 Workstation 配置段由当前文件中的所有页面共享。
            definition.forEach((key, value) -> {
                if (!key.equals("icons")) merged.put(key, value);
            });
            Map<String, Object> icons = new LinkedHashMap<>(commonIcons);
            icons.putAll(this.iconMap(definition.get("icons"), path + ".icons"));
            recipes.add(this.menuRecipe(entry.getKey(), merged, icons, path));
        }
        return recipes;
    }

    private DisplayRecipe menuRecipe(String id, Map<String, Object> definition,
                                     Map<String, Object> icons, String path) throws IOException {
        List<String> rows = new ArrayList<>(this.layoutRows(definition.get("layout"), icons, path + ".layout"));
        boolean roleSchema = this.usesRoleSchema(icons);
        if (!roleSchema) icons = this.escapeReservedContentSymbols(rows, icons);
        Map<String, Integer> positions = new HashMap<>();
        for (int slot = 0; slot < rows.size() * 9; slot++) {
            char symbol = rows.get(slot / 9).charAt(slot % 9);
            if (symbol != ' ' && (roleSchema || symbol != '#')
                    && positions.putIfAbsent(String.valueOf(symbol), slot) != null
                    && (roleSchema || !"TIPRN".contains(String.valueOf(symbol)))) {
                throw this.error(path + ".layout", "content symbol '" + symbol + "' may appear only once");
            }
        }

        String title = this.title(definition.get("title"), path + ".title");
        if (definition.get("title-data") instanceof String encoded) {
            try {
                title = RecipeLayout.JSON_TITLE_PREFIX + new String(
                        java.util.Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException error) {
                throw this.error(path + ".title-data", "invalid base64 title component", error);
            }
        }
        int stationSlot = roleSchema ? -1 : positions.getOrDefault("T", -1);
        int informationSlot = roleSchema ? -1 : positions.getOrDefault("I", -1);
        int previousSlot = roleSchema ? -1 : positions.getOrDefault("P", -1);
        int backSlot = roleSchema ? -1 : positions.getOrDefault("R", -1);
        int nextSlot = roleSchema ? -1 : positions.getOrDefault("N", -1);
        boolean nativeUi = this.nativeUi(definition.get("options"), path + ".options");
        String page = this.page(definition.get("options"), path + ".options");
        if (page != null) nativeUi = true;

        RecipeWorkstation workstation = this.workstation(definition.get("workstation"), icons,
                path + ".workstation");
        if ("farmersdelight:special".equals(workstation.id())) {
            workstation = new RecipeWorkstation(workstation.id(),
                    RecipeItem.of("farmersdelight:rich_soil"), workstation.name());
        }
        List<RecipeIngredient> ingredients = new ArrayList<>();
        List<RecipeResult> results = new ArrayList<>();
        Set<String> descriptions = new LinkedHashSet<>(this.strings(definition.get("description"),
                path + ".description"));
        for (var iconEntry : icons.entrySet()) {
            String symbol = iconEntry.getKey();
            if (symbol.length() != 1 || !roleSchema && symbol.equals("#")) continue;
            Map<String, Object> icon = this.lowerKeys(this.map(iconEntry.getValue(), path + ".icons." + symbol));
            String iconPath = path + ".icons." + symbol;
            if (icon.containsKey("information")) {
                descriptions.addAll(this.textLines(icon.get("information"), iconPath + ".information"));
            }
            if (!roleSchema && "TIPRN".contains(symbol)) continue;
            int slot = positions.getOrDefault(symbol, this.iconSlot(icon, iconPath));
            if (roleSchema && (icon.containsKey("usage") || icon.containsKey("用途") || icon.containsKey("use"))) {
                IconRole role = this.iconRole(icon, iconPath);
                switch (role.kind()) {
                    case INGREDIENT -> ingredients.add(this.roleIngredient(icon, slot, iconPath));
                    case RESULT -> results.add(this.roleResult(icon, slot, iconPath));
                    case DECORATION -> {
                        ingredients.add(this.roleDecoration(icon, slot, iconPath));
                        descriptions.addAll(this.textLines(this.roleInformation(icon),
                                iconPath + ".information"));
                    }
                    case BUTTON -> {
                        Object configuredButton = icon.containsKey("button") ? icon.get("button") : icon.get("按钮");
                        String button = this.string(configuredButton, iconPath + ".button", true)
                                .trim().toLowerCase(Locale.ROOT);
                        switch (button) {
                            case "合成器", "工作站", "workstation" -> stationSlot = slot;
                            case "说明", "information", "info" -> {
                                informationSlot = slot;
                                descriptions.addAll(this.textLines(this.roleInformation(icon),
                                        iconPath + ".information"));
                            }
                            case "上一页", "上一配方", "previous", "prev" -> previousSlot = slot;
                            case "返回", "back" -> backSlot = slot;
                            case "下一页", "下一配方", "next" -> nextSlot = slot;
                            default -> throw this.error(iconPath + ".button",
                                    "expected workstation, information, previous, back or next");
                        }
                    }
                }
            } else if (icon.containsKey("ingredient")) {
                ingredients.add(this.menuIngredient(icon, slot, iconPath));
            } else if (icon.containsKey("result")) {
                results.add(this.menuResult(icon, slot, iconPath));
            } else if (icon.containsKey("decoration")) {
                ingredients.add(new RecipeIngredient(List.of(this.displayItem(icon, iconPath)), 1, slot,
                        RecipeIngredient.Role.INFO));
                descriptions.addAll(this.textLines(icon.get("decoration"), iconPath + ".decoration"));
            }
        }
        if (definition.containsKey("results")) {
            List<?> declared = this.list(definition.get("results"), path + ".results");
            results.clear();
            for (int index = 0; index < declared.size(); index++) {
                results.add(this.declaredResult(declared.get(index), path + ".results[" + index + "]"));
            }
        }
        if (results.isEmpty()) throw this.error(path + ".icons", "at least one icon must contain result");

        boolean displayOnlySpecial = id.startsWith("farmersdelight:special/")
                && descriptions.stream().anyMatch(line -> line.contains("说明条目"));
        if (id.startsWith("farmersdelight:special/")) {
            if (displayOnlySpecial && ingredients.stream().noneMatch(ingredient -> ingredient.slot() == 4)) {
                for (int index = 0; index < ingredients.size(); index++) {
                    RecipeIngredient ingredient = ingredients.get(index);
                    if (ingredient.role() == RecipeIngredient.Role.INFO && ingredient.slot() < 0) {
                        ingredients.set(index, new RecipeIngredient(ingredient.choices(), ingredient.amount(),
                                4, ingredient.role()));
                        break;
                    }
                }
            }
            for (int index = 0; index < ingredients.size(); index++) {
                RecipeIngredient ingredient = ingredients.get(index);
                if (ingredient.role() != RecipeIngredient.Role.INFO || ingredient.slot() != 4) continue;
                List<RecipeItem> transparent = ingredient.choices().stream()
                        .map(this::transparentDescriptionItem).toList();
                ingredients.set(index, new RecipeIngredient(transparent, ingredient.amount(),
                        ingredient.slot(), ingredient.role()));
            }
        }
        if (displayOnlySpecial) {
            if (!results.isEmpty() && results.getFirst().slot() < 0) {
                RecipeResult result = results.getFirst();
                results.set(0, new RecipeResult(result.item(), result.amount(), 16, result.note(),
                        result.viewerLore()));
            }
        }

        if (id.startsWith("kc:")) {
            ingredients = this.positionKaleidoscopeIngredients(ingredients);
            results = this.positionKaleidoscopeResults(results);
            if (id.startsWith("kc:flex/")) {
                descriptions.add("投料越接近理想配比，品质越高。");
                descriptions.add("多放杂料会拉低品质。");
                boolean hasCarrier = ingredients.stream()
                        .anyMatch(ingredient -> ingredient.role() == RecipeIngredient.Role.CONTAINER);
                descriptions.add(hasCarrier ? "手持盛装容器右键锅盛出。" : "空手盛出，无需容器。");
            }
        }
        if ("farmersdelight:skillet".equals(workstation.id())) {
            ingredients = this.normalizeSkilletInformation(ingredients, descriptions);
        }
        if (workstation.id().startsWith("farmersdelight:addon/brewinandchewin")) {
            ingredients = new ArrayList<>(ingredients.stream()
                    .filter(ingredient -> !this.unsupportedFillDecoration(ingredient))
                    .toList());
            boolean hasProgress = ingredients.stream().anyMatch(ingredient ->
                    ingredient.choices().stream().anyMatch(item -> item.id().equals("farmersdelight:animated")));
            boolean progressSlotOccupied = ingredients.stream().anyMatch(ingredient -> ingredient.slot() == 23)
                    || results.stream().anyMatch(result -> result.slot() == 23);
            if (!hasProgress && !progressSlotOccupied && rows.size() * 9 > 23) {
                ingredients.add(new RecipeIngredient(
                        List.of(RecipeItem.of("craftengine", "farmersdelight:animated")),
                        1, 23, RecipeIngredient.Role.INFO));
            }
        }

        RecipeLayout layout = new RecipeLayout(rows.size(), title, stationSlot, informationSlot,
                previousSlot, backSlot, nextSlot, rows, nativeUi, page);
        this.validateSlots(layout, ingredients, ingredients.size(), results, path);
        try {
            return new DisplayRecipe(id, workstation, layout, ingredients, results, List.copyOf(descriptions),
                    this.string(definition.get("permission"), path + ".permission", false), null);
        } catch (IllegalArgumentException | NullPointerException error) {
            throw this.error(path, error.getMessage(), error);
        }
    }

    private List<RecipeIngredient> positionKaleidoscopeIngredients(List<RecipeIngredient> ingredients) {
        Set<Integer> used = new LinkedHashSet<>();
        for (RecipeIngredient ingredient : ingredients) {
            if (ingredient.slot() >= 0) used.add(ingredient.slot());
        }
        int next = 0;
        List<RecipeIngredient> placed = new ArrayList<>(ingredients.size());
        for (RecipeIngredient ingredient : ingredients) {
            int slot = ingredient.slot();
            if (slot < 0) {
                while (next < KALEIDOSCOPE_INGREDIENT_SLOTS.size()
                        && used.contains(KALEIDOSCOPE_INGREDIENT_SLOTS.get(next))) next++;
                if (next < KALEIDOSCOPE_INGREDIENT_SLOTS.size()) {
                    slot = KALEIDOSCOPE_INGREDIENT_SLOTS.get(next++);
                    used.add(slot);
                }
            }
            placed.add(new RecipeIngredient(ingredient.choices(), ingredient.amount(), slot, ingredient.role()));
        }
        return placed;
    }

    private List<RecipeIngredient> normalizeSkilletInformation(List<RecipeIngredient> ingredients,
                                                               Set<String> descriptions) {
        int time = 600;
        String experience = "0";
        for (String line : descriptions) {
            java.util.regex.Matcher timeMatcher = java.util.regex.Pattern
                    .compile("烹饪时间：(\\d+) tick").matcher(line);
            if (timeMatcher.find()) time = Integer.parseInt(timeMatcher.group(1));
            java.util.regex.Matcher experienceMatcher = java.util.regex.Pattern
                    .compile("经验：([0-9]+(?:\\.[0-9]+)?)").matcher(line);
            if (experienceMatcher.find()) experience = experienceMatcher.group(1);
        }
        List<String> lore = List.of("<gray>时间：" + time + " 刻",
                "<gray>经验：" + new java.math.BigDecimal(experience)
                        .stripTrailingZeros().toPlainString());
        List<RecipeIngredient> normalized = new ArrayList<>(ingredients.size());
        for (RecipeIngredient ingredient : ingredients) {
            boolean skilletInfo = ingredient.role() == RecipeIngredient.Role.INFO
                    && ingredient.choices().stream().anyMatch(item -> item.id().equals("internal:cooking_info"));
            if (!skilletInfo) {
                normalized.add(ingredient);
                continue;
            }
            List<RecipeItem> choices = ingredient.choices().stream().map(item ->
                    new RecipeItem(item.provider(), item.id(), item.icon(), "<gold>配方信息", lore)).toList();
            normalized.add(new RecipeIngredient(choices, ingredient.amount(), ingredient.slot(), ingredient.role()));
        }
        return List.copyOf(normalized);
    }

    private boolean unsupportedFillDecoration(RecipeIngredient ingredient) {
        return ingredient.role() == RecipeIngredient.Role.INFO
                && ingredient.choices().stream().allMatch(item ->
                item.id().equals("minecraft:hopper") || item.id().equals("minecraft:barrier"));
    }

    private List<RecipeResult> positionKaleidoscopeResults(List<RecipeResult> results) {
        Set<Integer> used = new LinkedHashSet<>();
        for (RecipeResult result : results) {
            if (result.slot() >= 0) used.add(result.slot());
        }
        int next = 0;
        List<RecipeResult> placed = new ArrayList<>(results.size());
        for (RecipeResult result : results) {
            int slot = result.slot();
            if (slot < 0) {
                while (next < KALEIDOSCOPE_RESULT_SLOTS.size()
                        && used.contains(KALEIDOSCOPE_RESULT_SLOTS.get(next))) next++;
                if (next < KALEIDOSCOPE_RESULT_SLOTS.size()) {
                    slot = KALEIDOSCOPE_RESULT_SLOTS.get(next++);
                    used.add(slot);
                }
            }
            placed.add(new RecipeResult(result.item(), result.amount(), slot, result.note(), result.viewerLore()));
        }
        return placed;
    }

    private boolean usesRoleSchema(Map<String, Object> icons) {
        for (Object value : icons.values()) {
            if (!(value instanceof Map<?, ?> map)) continue;
            for (Object key : map.keySet()) {
                String field = String.valueOf(key);
                if (field.equals("用途") || field.equalsIgnoreCase("use")
                        || field.equalsIgnoreCase("usage")) return true;
            }
        }
        return false;
    }

    private IconRole iconRole(Map<String, Object> icon, String path) throws IOException {
        Object configured = icon.containsKey("usage") ? icon.get("usage")
                : icon.containsKey("用途") ? icon.get("用途") : icon.get("use");
        String role = this.string(configured, path + ".usage", true).trim().toLowerCase(Locale.ROOT);
        return switch (role) {
            case "原料", "材料", "ingredient" -> new IconRole(RoleKind.INGREDIENT);
            case "产物", "结果", "result" -> new IconRole(RoleKind.RESULT);
            case "装饰", "decoration", "decoration-only" -> new IconRole(RoleKind.DECORATION);
            case "按钮", "button" -> new IconRole(RoleKind.BUTTON);
            default -> throw this.error(path + ".usage",
                    "expected ingredient, result, decoration or button");
        };
    }

    private RecipeIngredient roleIngredient(Map<String, Object> icon, int slot, String path) throws IOException {
        Map<String, Object> marker = new LinkedHashMap<>();
        marker.put("amount", this.roleAmount(icon, path));
        Object role = icon.containsKey("role") ? icon.get("role") : icon.get("角色");
        if (role != null) marker.put("role", this.recipeRole(role, path + ".role").name());
        Object choices = icon.containsKey("choices") ? icon.get("choices")
                : icon.containsKey("候选") ? icon.get("候选") : null;
        if (choices != null) marker.put("choices", choices);
        Map<String, Object> compatible = new LinkedHashMap<>(icon);
        compatible.put("ingredient", marker);
        return this.menuIngredient(compatible, slot, path);
    }

    private RecipeIngredient.Role recipeRole(Object configured, String path) throws IOException {
        String role = this.string(configured, path, true).trim().toUpperCase(Locale.ROOT);
        role = switch (role) {
            case "工具" -> "TOOL";
            case "容器" -> "CONTAINER";
            case "燃料" -> "FUEL";
            case "信息", "装饰" -> "INFO";
            case "原料" -> "INGREDIENT";
            default -> role;
        };
        try {
            return RecipeIngredient.Role.valueOf(role);
        } catch (IllegalArgumentException error) {
            throw this.error(path, "expected INGREDIENT, TOOL, CONTAINER, FUEL or INFO", error);
        }
    }

    private int roleAmount(Map<String, Object> icon, String path) throws IOException {
        if (icon.containsKey("amount")) return this.integer(icon.get("amount"), path + ".amount");
        if (icon.containsKey("数量")) return this.integer(icon.get("数量"), path + ".数量");
        return this.displayAmount(icon, path);
    }

    private Object roleInformation(Map<String, Object> icon) {
        if (icon.containsKey("information")) return icon.get("information");
        if (icon.containsKey("description")) return icon.get("description");
        return icon.get("说明");
    }

    private RecipeResult roleResult(Map<String, Object> icon, int slot, String path) throws IOException {
        Map<String, Object> marker = new LinkedHashMap<>();
        marker.put("amount", icon.containsKey("amount") ? icon.get("amount")
                : icon.containsKey("数量") ? icon.get("数量") : this.displayAmount(icon, path));
        Object note = icon.containsKey("note") ? icon.get("note") : icon.get("备注");
        if (note != null) marker.put("note", note);
        Map<String, Object> compatible = new LinkedHashMap<>(icon);
        compatible.put("result", marker);
        return this.menuResult(compatible, slot, path);
    }

    private RecipeIngredient roleDecoration(Map<String, Object> icon, int slot, String path) throws IOException {
        Map<String, Object> marker = new LinkedHashMap<>();
        marker.put("amount", this.roleAmount(icon, path));
        marker.put("role", RecipeIngredient.Role.INFO.name());
        Object choices = icon.containsKey("choices") ? icon.get("choices")
                : icon.containsKey("候选") ? icon.get("候选") : null;
        if (choices != null) marker.put("choices", choices);
        Map<String, Object> compatible = new LinkedHashMap<>(icon);
        compatible.put("ingredient", marker);
        return this.menuIngredient(compatible, slot, path);
    }

    private enum RoleKind { INGREDIENT, RESULT, DECORATION, BUTTON }
    private record IconRole(RoleKind kind) {}

    private Map<String, Object> escapeReservedContentSymbols(List<String> rows,
                                                              Map<String, Object> icons) throws IOException {
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (var entry : icons.entrySet()) {
            String symbol = entry.getKey();
            if (!"TIPRN".contains(symbol) || !this.isContentIcon(entry.getValue())) {
                normalized.put(symbol, entry.getValue());
                continue;
            }
            char replacement = this.freeContentSymbol(rows, normalized);
            for (int row = 0; row < rows.size(); row++) {
                rows.set(row, rows.get(row).replace(symbol.charAt(0), replacement));
            }
            normalized.put(String.valueOf(replacement), entry.getValue());
        }
        return java.util.Collections.unmodifiableMap(normalized);
    }

    private boolean isContentIcon(Object configured) {
        if (!(configured instanceof Map<?, ?> map)) return false;
        for (Object key : map.keySet()) {
            String field = String.valueOf(key);
            if (field.equalsIgnoreCase("ingredient") || field.equalsIgnoreCase("result")
                    || field.equalsIgnoreCase("decoration")) return true;
        }
        return false;
    }

    private char freeContentSymbol(List<String> rows, Map<String, Object> icons) throws IOException {
        String candidates = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        for (char candidate : candidates.toCharArray()) {
            if ("TIPRN#".indexOf(candidate) >= 0 || icons.containsKey(String.valueOf(candidate))
                    || rows.stream().anyMatch(row -> row.indexOf(candidate) >= 0)) continue;
            return candidate;
        }
        throw this.error("icons", "no free symbol remains after escaping a reserved content character");
    }

    private List<String> layoutRows(Object configured, Map<String, Object> icons, String path) throws IOException {
        List<String> rows = new ArrayList<>();
        if (configured != null) {
            List<?> layout = this.list(configured, path);
            if (!layout.isEmpty() && layout.getFirst() instanceof List<?>) {
                layout = this.list(layout.getFirst(), path + "[0]");
            }
            for (int i = 0; i < layout.size(); i++) {
                if (!(layout.get(i) instanceof String row) || row.length() != 9) {
                    throw this.error(path + "[" + i + "]", "each row must contain exactly 9 characters");
                }
                rows.add(row);
            }
            if (rows.isEmpty()) throw this.error(path, "expected at least one row");
        } else {
            String empty = this.usesRoleSchema(icons) ? "         " : "#########";
            for (int i = 0; i < 6; i++) rows.add(empty);
        }
        if (rows.size() > 6) throw this.error(path, "at most 6 rows are supported");

        // TrMenu 通过布局放置图标。省略布局时，图标上的 slot 可作为简写；
        // 仍保留为 '#' 的占位符只用于装饰。
        for (var entry : icons.entrySet()) {
            String symbol = entry.getKey();
            Map<String, Object> icon = symbol.length() == 1
                    ? this.lowerKeys(this.map(entry.getValue(), path + ".icons." + symbol)) : Map.of();
            boolean roleIcon = icon.containsKey("用途") || icon.containsKey("use")
                    || icon.containsKey("usage");
            if (symbol.length() != 1 || !roleIcon && " ITIPRN#".contains(symbol) && !symbol.equals("T")
                    && !symbol.equals("I") && !symbol.equals("P") && !symbol.equals("R")
                    && !symbol.equals("N")) {
                continue;
            }
            int slot = this.iconSlot(icon, path + ".icons." + symbol);
            if (slot < 0 || this.containsSymbol(rows, symbol.charAt(0))) continue;
            int row = slot / 9;
            while (rows.size() <= row) rows.add("#########");
            char[] chars = rows.get(row).toCharArray();
            chars[slot % 9] = symbol.charAt(0);
            rows.set(row, new String(chars));
        }
        return List.copyOf(rows);
    }

    private boolean containsSymbol(List<String> rows, char symbol) {
        return rows.stream().anyMatch(row -> row.indexOf(symbol) >= 0);
    }

    private Map<String, Object> iconMap(Object configured, String path) throws IOException {
        if (configured == null) return Map.of();
        Map<String, Object> input = this.map(configured, path);
        Map<String, Object> icons = new LinkedHashMap<>();
        for (var entry : input.entrySet()) {
            if (entry.getKey().length() != 1) {
                throw this.error(path + "." + entry.getKey(), "icon keys must be one character");
            }
            icons.put(entry.getKey(), entry.getValue());
        }
        return icons;
    }

    private boolean nativeUi(Object configured, String path) throws IOException {
        if (configured == null) return false;
        Map<String, Object> options = this.lowerKeys(this.map(configured, path));
        this.keys(options, path, Set.of("native-ui", "nativeui", "hide-controls", "page", "type", "ui"));
        return Boolean.TRUE.equals(options.get("native-ui")) || Boolean.TRUE.equals(options.get("nativeui"))
                || Boolean.TRUE.equals(options.get("hide-controls"));
    }

    private String page(Object configured, String path) throws IOException {
        if (configured == null) return null;
        Map<String, Object> options = this.lowerKeys(this.map(configured, path));
        Object raw = options.containsKey("page") ? options.get("page")
                : options.containsKey("type") ? options.get("type") : options.get("ui");
        if (raw == null) return null;
        String value = this.string(raw, path + ".page", true).toLowerCase(Locale.ROOT);
        value = switch (value) {
            case "workbench", "workbench_table" -> "crafting";
            case "furnace" -> "smelting";
            case "blast_furnace" -> "blasting";
            case "smoker" -> "smoking";
            case "campfire" -> "campfire";
            case "stonecutter" -> "stonecutting";
            case "smithing_table" -> "smithing";
            case "brewing_stand" -> "brewing";
            default -> value;
        };
        if (value.equals("chest")) return null;
        if (!Set.of("crafting", "smelting", "blasting", "smoking", "campfire",
                "stonecutting", "smithing", "brewing").contains(value)) {
            throw this.error(path + ".page", "expected chest, crafting, smelting, blasting, smoking, campfire, stonecutting, smithing or brewing");
        }
        return value;
    }

    private RecipeWorkstation workstation(Object configured, Map<String, Object> icons, String path) throws IOException {
        Map<String, Object> station = configured == null ? Map.of()
                : this.lowerKeys(this.map(configured, path));
        Object stationIcon = station.getOrDefault("icon", station.get("display"));
        if (stationIcon == null) {
            Object icon = icons.get("T");
            if (icon != null) {
                Map<String, Object> values = this.lowerKeys(this.map(icon, path + ".icon"));
                if (values.containsKey("workstation")) {
                    Object marker = values.get("workstation");
                    if (marker instanceof Map<?, ?>) station = this.lowerKeys(this.map(marker, path));
                }
                stationIcon = values.getOrDefault("display", values);
            }
        }
        if (stationIcon == null) stationIcon = "minecraft:crafting_table";
        String id = this.string(station.get("id"), path + ".id", true);
        RecipeItem icon = this.parseItem(stationIcon, path + ".icon");
        return new RecipeWorkstation(id, icon, this.string(station.get("name"), path + ".name", false));
    }

    private RecipeIngredient menuIngredient(Map<String, Object> icon, int slot, String path) throws IOException {
        Map<String, Object> marker = this.marker(icon.get("ingredient"), path + ".ingredient");
        List<RecipeItem> choices = new ArrayList<>();
        Object configuredChoices = marker.containsKey("choices") ? marker.get("choices") : icon.get("choices");
        if (configuredChoices != null) {
            List<?> values = this.list(configuredChoices, path + ".ingredient.choices");
            for (int i = 0; i < values.size(); i++) {
                choices.add(this.choiceItem(values.get(i), path + ".ingredient.choices[" + i + "]"));
            }
        }
        if (choices.isEmpty()) choices.add(this.displayItem(icon, path));
        RecipeIngredient.Role role = RecipeIngredient.Role.INGREDIENT;
        if (marker.containsKey("role")) {
            try {
                role = RecipeIngredient.Role.valueOf(this.string(marker.get("role"), path + ".ingredient.role", true)
                        .toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException error) {
                throw this.error(path + ".ingredient.role", "expected INGREDIENT, TOOL, CONTAINER, FUEL or INFO", error);
            }
        }
        int amount = this.intValue(marker, "amount", this.displayAmount(icon, path), path + ".ingredient");
        if (amount < 1) throw this.error(path + ".ingredient.amount", "must be positive");
        return new RecipeIngredient(choices, amount, slot, role);
    }

    private RecipeResult menuResult(Map<String, Object> icon, int slot, String path) throws IOException {
        Map<String, Object> marker = this.marker(icon.get("result"), path + ".result");
        int amount = this.intValue(marker, "amount", this.displayAmount(icon, path), path + ".result");
        if (amount < 1) throw this.error(path + ".result.amount", "must be positive");
        return new RecipeResult(this.displayItem(icon, path), amount, slot,
                this.string(marker.get("note"), path + ".result.note", false));
    }

    private RecipeResult declaredResult(Object configured, String path) throws IOException {
        Map<String, Object> result = this.lowerKeys(this.map(configured, path));
        this.keys(result, path, Set.of("display", "amount", "slot", "note"));
        Object display = result.get("display");
        if (display == null) throw this.error(path + ".display", "a result display is required");
        int slot = result.containsKey("slot") ? this.integer(result.get("slot"), path + ".slot") : -1;
        int amount = this.intValue(result, "amount", 1, path);
        if (amount < 1) throw this.error(path + ".amount", "must be positive");
        return new RecipeResult(this.parseItem(display, path + ".display"), amount, slot,
                this.string(result.get("note"), path + ".note", false));
    }

    private RecipeItem choiceItem(Object value, String path) throws IOException {
        Map<String, Object> choice = this.lowerKeys(this.map(value, path));
        return this.parseItem(choice.getOrDefault("display", choice), path);
    }

    private RecipeItem displayItem(Map<String, Object> icon, String path) throws IOException {
        Object display = icon.get("display");
        return this.parseItem(display == null ? icon : display, path + ".display");
    }

    private RecipeItem parseItem(Object configured, String path) throws IOException {
        if (configured instanceof String value) {
            String id = this.materialId(value, path);
            return RecipeItem.of(id);
        }
        Map<String, Object> item = this.lowerKeys(this.map(configured, path));
        this.keys(item, path, Set.of("material", "id", "provider", "name", "lore", "amount", "data",
                "item-model", "hide-tooltip"));
        String raw = this.string(item.getOrDefault("id", item.get("material")), path + ".material", true);
        String id = this.materialId(raw, path);
        String provider = item.containsKey("provider")
                ? this.string(item.get("provider"), path + ".provider", true)
                : id.startsWith("minecraft:") ? "minecraft" : "craftengine";
        ItemStack icon = null;
        if (item.get("data") instanceof byte[] bytes) {
            try {
                icon = ItemStack.deserializeBytes(bytes);
            } catch (RuntimeException error) {
                throw this.error(path + ".data", "invalid serialized item", error);
            }
        } else if (item.get("data") instanceof String base64 && !base64.isBlank()) {
            try {
                icon = ItemStack.deserializeBytes(java.util.Base64.getDecoder().decode(base64));
            } catch (IllegalArgumentException error) {
                throw this.error(path + ".data", "invalid base64 item data", error);
            }
        }
        Object configuredModel = item.get("item-model");
        boolean airItem = id.equals("minecraft:air");
        boolean customModel = configuredModel instanceof String model && !model.isBlank()
                && !model.equalsIgnoreCase("minecraft:air");
        boolean airModel = configuredModel instanceof String model && model.equalsIgnoreCase("minecraft:air");
        if (airItem || airModel) {
            ItemStack carrier = icon == null ? new ItemStack(Material.PAPER) : icon;
            ItemMeta meta = carrier.getItemMeta();
            if (meta != null) {
                NamespacedKey model = airItem && customModel
                        ? NamespacedKey.fromString((String) configuredModel)
                        : NamespacedKey.minecraft("air");
                if (model == null) {
                    throw this.error(path + ".item-model", "invalid item model key");
                }
                meta.setItemModel(model);
                if (Boolean.TRUE.equals(item.get("hide-tooltip"))) {
                    meta.setHideTooltip(true);
                }
                carrier.setItemMeta(meta);
                icon = carrier;
            }
        }
        String name = this.string(item.get("name"), path + ".name", false);
        List<String> lore = !item.containsKey("lore") || item.get("lore") == null
                ? null : this.strings(item.get("lore"), path + ".lore");
        try {
            return new RecipeItem(provider, id, icon, name, lore);
        } catch (IllegalArgumentException error) {
            throw this.error(path, error.getMessage(), error);
        }
    }

    private String materialId(String raw, String path) throws IOException {
        if (raw.contains(":")) return raw;
        Material material = Material.matchMaterial(raw);
        if (material == null) {
            String normalized = raw.trim().replace(' ', '_').replace('-', '_');
            material = Material.matchMaterial(normalized);
        }
        if (material == null) {
            material = Material.matchMaterial(raw.trim().toUpperCase(Locale.ROOT).replace(' ', '_'));
        }
        if (material == null) throw this.error(path, "unknown material or item id: " + raw);
        return material.getKey().toString();
    }

    private RecipeItem transparentDescriptionItem(RecipeItem item) {
        try {
            ItemStack carrier = item.icon();
            if (carrier == null || carrier.getType().isAir()) carrier = new ItemStack(Material.PAPER);
            ItemMeta meta = carrier.getItemMeta();
            if (meta != null) {
                meta.setItemModel(NamespacedKey.minecraft("air"));
                carrier.setItemMeta(meta);
            }
            return new RecipeItem("minecraft", "minecraft:air", carrier, item.name(), item.lore());
        } catch (RuntimeException | LinkageError unavailableRegistry) {
            // 单元测试中的 Bukkit 桩没有注册表；实际服务器路径会提供空气模型。
            return new RecipeItem("minecraft", "minecraft:air", item.icon(), item.name(), item.lore());
        }
    }

    private Map<String, Object> marker(Object configured, String path) throws IOException {
        if (configured == null || Boolean.TRUE.equals(configured)) return Map.of();
        if (configured instanceof String text) return Map.of("note", text);
        return this.lowerKeys(this.map(configured, path));
    }

    private Object markerValue(Object configured) {
        if (configured instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                if (String.valueOf(entry.getKey()).equalsIgnoreCase("lore")
                        || String.valueOf(entry.getKey()).equalsIgnoreCase("text")
                        || String.valueOf(entry.getKey()).equalsIgnoreCase("lines")) return entry.getValue();
            }
        }
        return configured;
    }

    private List<String> textLines(Object configured, String path) throws IOException {
        Object value = this.markerValue(configured);
        if (value == null || Boolean.TRUE.equals(value)) return List.of();
        if (value instanceof String text) return text.isBlank() ? List.of() : List.of(text);
        return this.strings(value, path);
    }

    private int iconSlot(Map<String, Object> icon, String path) throws IOException {
        Object configured = icon.containsKey("slot") ? icon.get("slot") : icon.get("slots");
        if (configured == null) return -1;
        if (configured instanceof List<?> list) {
            if (list.isEmpty()) return -1;
            configured = list.getFirst();
            if (configured instanceof List<?> nested) {
                if (nested.isEmpty()) return -1;
                configured = nested.getFirst();
            }
        }
        int slot = this.integer(configured, path + ".slot");
        if (slot < -1 || slot >= 54) throw this.error(path + ".slot", "must be -1 or between 0 and 53");
        return slot;
    }

    private int displayAmount(Map<String, Object> icon, String path) throws IOException {
        Object display = icon.get("display");
        if (display instanceof Map<?, ?>) {
            Map<String, Object> values = this.lowerKeys(this.map(display, path + ".display"));
            return this.intValue(values, "amount", 1, path + ".display");
        }
        return this.intValue(icon, "amount", 1, path);
    }

    private String title(Object configured, String path) throws IOException {
        if (configured == null) return null;
        if (configured instanceof String text) return text;
        List<?> values = this.list(configured, path);
        if (values.isEmpty() || !(values.getFirst() instanceof String text)) {
            throw this.error(path, "expected a string or a nonempty list of strings");
        }
        return text;
    }

    private Map<String, Object> lowerKeys(Map<String, Object> input) {
        Map<String, Object> result = new LinkedHashMap<>();
        input.forEach((key, value) -> result.put(key.toLowerCase(Locale.ROOT), value));
        return result;
    }

    private Map<String, Object> preserveKeyMap(Object value, String path) throws IOException {
        return this.map(value, path);
    }

    DisplayRecipe recipe(String id, Map<String, Object> values, Map<String, Object> templates, String path) throws IOException {
        this.keys(values, path, Set.of("enabled", "workstation", "layout", "ingredients", "results", "description", "permission", "decorations"));
        Map<String, Object> station = this.map(values.get("workstation"), path + ".workstation");
        this.keys(station, path + ".workstation", Set.of("id", "icon", "name", "slot"));
        RecipeWorkstation workstation;
        try {
            workstation = new RecipeWorkstation(this.string(station.get("id"), path + ".workstation.id", true),
                    this.item(station.get("icon"), path + ".workstation.icon"),
                    this.string(station.get("name"), path + ".workstation.name", false));
        } catch (IllegalArgumentException | NullPointerException error) {
            throw this.error(path + ".workstation", error.getMessage(), error);
        }
        Object configuredLayout = values.get("layout");
        Map<String, Object> layoutValues;
        String templateId = null;
        if (configuredLayout instanceof String text) {
            templateId = text;
            layoutValues = Map.of();
        } else {
            layoutValues = configuredLayout == null ? Map.of() : this.map(configuredLayout, path + ".layout");
            if (layoutValues.containsKey("template")) templateId = this.string(layoutValues.get("template"), path + ".layout.template", true);
        }
        this.keys(layoutValues, path + ".layout", LAYOUT_KEYS);
        Map<String, Object> merged = new LinkedHashMap<>();
        if (templateId != null) {
            if (!templates.containsKey(templateId)) throw this.error(path + ".layout.template", "unknown template " + templateId);
            merged.putAll(this.map(templates.get(templateId), "layouts." + templateId));
        }
        merged.putAll(layoutValues);
        Integer stationSlot = station.containsKey("slot") ? this.integer(station.get("slot"), path + ".workstation.slot") : null;
        RecipeLayout layout = this.layout(merged, stationSlot, path + ".layout");
        List<RecipeIngredient> ingredients = new ArrayList<>();
        this.ingredients(values.get("ingredients"), ingredients, path + ".ingredients", false, layout.rows());
        int decorationStart = ingredients.size();
        this.ingredients(values.get("decorations"), ingredients, path + ".decorations", true, layout.rows());
        List<RecipeResult> results = new ArrayList<>();
        List<?> configuredResults = this.list(values.get("results"), path + ".results");
        if (configuredResults.isEmpty()) throw this.error(path + ".results", "at least one result is required");
        for (int i = 0; i < configuredResults.size(); i++) {
            String resultPath = path + ".results[" + i + "]";
            Map<String, Object> result = this.map(configuredResults.get(i), resultPath);
            this.keys(result, resultPath, RESULT_KEYS);
            results.add(new RecipeResult(this.entryItem(result, resultPath), this.amount(result, resultPath),
                    this.contentSlot(result, resultPath, layout.rows()), this.string(result.get("note"), resultPath + ".note", false)));
        }
        this.validateSlots(layout, ingredients, decorationStart, results, path);
        try {
            return new DisplayRecipe(id, workstation, layout, ingredients, results,
                    this.strings(values.get("description"), path + ".description"),
                    this.string(values.get("permission"), path + ".permission", false), null);
        } catch (IllegalArgumentException | NullPointerException error) {
            throw this.error(path, error.getMessage(), error);
        }
    }

    private void ingredients(Object value, List<RecipeIngredient> output, String path, boolean decoration, int rows) throws IOException {
        List<?> entries = this.list(value, path);
        for (int i = 0; i < entries.size(); i++) {
            String entryPath = path + "[" + i + "]";
            Map<String, Object> entry = this.map(entries.get(i), entryPath);
            this.keys(entry, entryPath, INGREDIENT_KEYS);
            RecipeIngredient.Role role = decoration ? RecipeIngredient.Role.INFO : RecipeIngredient.Role.INGREDIENT;
            if (entry.containsKey("role")) {
                try { role = RecipeIngredient.Role.valueOf(this.string(entry.get("role"), entryPath + ".role", true).toUpperCase(Locale.ROOT)); }
                catch (IllegalArgumentException error) { throw this.error(entryPath + ".role", "expected INGREDIENT, TOOL, CONTAINER, FUEL or INFO", error); }
                if (decoration && role != RecipeIngredient.Role.INFO) throw this.error(entryPath + ".role", "decorations must use INFO");
            }
            List<RecipeItem> choices = new ArrayList<>();
            if (entry.containsKey("choices")) {
                if (entry.containsKey("item") || entry.containsKey("id") || entry.containsKey("provider")) {
                    throw this.error(entryPath, "use choices or a single item, not both; put provider inside each choice");
                }
                List<?> configured = this.list(entry.get("choices"), entryPath + ".choices");
                if (configured.isEmpty()) throw this.error(entryPath + ".choices", "at least one item is required");
                for (int choice = 0; choice < configured.size(); choice++) {
                    RecipeItem item = this.item(configured.get(choice), entryPath + ".choices[" + choice + "]");
                    choices.add(new RecipeItem(item.provider(), item.id(), item.icon(),
                            entry.containsKey("name") ? this.string(entry.get("name"), entryPath + ".name", false) : item.name(),
                            entry.containsKey("lore") ? this.optionalLore(entry, entryPath) : item.lore()));
                }
            } else choices.add(this.entryItem(entry, entryPath));
            output.add(new RecipeIngredient(choices, this.amount(entry, entryPath), this.contentSlot(entry, entryPath, rows), role));
        }
    }

    private RecipeItem entryItem(Map<String, Object> entry, String path) throws IOException {
        if (entry.containsKey("item")) {
            if (entry.containsKey("id") || entry.containsKey("provider")) throw this.error(path, "use item or provider/id, not both");
            RecipeItem item = this.item(entry.get("item"), path + ".item");
            return new RecipeItem(item.provider(), item.id(), item.icon(),
                    entry.containsKey("name") ? this.string(entry.get("name"), path + ".name", false) : item.name(),
                    entry.containsKey("lore") ? this.optionalLore(entry, path) : item.lore());
        }
        Map<String, Object> item = new LinkedHashMap<>();
        for (String key : ITEM_KEYS) if (entry.containsKey(key)) item.put(key, entry.get(key));
        return this.item(item, path);
    }

    private RecipeItem item(Object value, String path) throws IOException {
        if (value instanceof String id) {
            try { return RecipeItem.of(id); }
            catch (IllegalArgumentException error) { throw this.error(path, error.getMessage(), error); }
        }
        Map<String, Object> item = this.map(value, path);
        this.keys(item, path, ITEM_KEYS);
        String id = this.string(item.get("id"), path + ".id", true);
        String provider = item.containsKey("provider") ? this.string(item.get("provider"), path + ".provider", true)
                : id.startsWith("minecraft:") ? "minecraft" : "craftengine";
        try {
            return new RecipeItem(provider, id, null, this.string(item.get("name"), path + ".name", false), this.optionalLore(item, path));
        } catch (IllegalArgumentException error) { throw this.error(path, error.getMessage(), error); }
    }

    private List<String> optionalLore(Map<String, Object> item, String path) throws IOException {
        return !item.containsKey("lore") || item.get("lore") == null ? null : this.strings(item.get("lore"), path + ".lore");
    }

    RecipeLayout layout(Map<String, Object> values, Integer stationSlot, String path) throws IOException {
        String type = values.containsKey("type") ? this.string(values.get("type"), path + ".type", true) : "chest";
        if (!type.equalsIgnoreCase("chest")) throw this.error(path + ".type", "only chest is supported");
        int rows = this.intValue(values, "rows", 6, path);
        if (rows < 1 || rows > 6) throw this.error(path + ".rows", "must be between 1 and 6");
        int bottom = (rows - 1) * 9;
        int station = stationSlot == null ? this.intValue(values, "workstation-slot", rows == 1 ? -1 : 4, path) : stationSlot;
        int information = this.intValue(values, "information-slot", bottom + 4, path);
        int previous = this.intValue(values, "previous-slot", bottom, path);
        int back = this.intValue(values, "back-slot", bottom + 3, path);
        int next = this.intValue(values, "next-slot", bottom + 8, path);
        try { return new RecipeLayout(rows, this.string(values.get("title"), path + ".title", false), station, information, previous, back, next); }
        catch (IllegalArgumentException error) { throw this.error(path, error.getMessage(), error); }
    }

    private void validateSlots(RecipeLayout layout, List<RecipeIngredient> ingredients, int decorationStart, List<RecipeResult> results, String path) throws IOException {
        Map<Integer, String> occupied = new HashMap<>();
        occupied.put(layout.workstationSlot(), path + ".workstation.slot");
        occupied.put(layout.informationSlot(), path + ".layout.information-slot");
        occupied.put(layout.previousSlot(), path + ".layout.previous-slot");
        occupied.put(layout.backSlot(), path + ".layout.back-slot");
        occupied.put(layout.nextSlot(), path + ".layout.next-slot");
        for (int i = 0; i < ingredients.size(); i++) {
            String entryPath = i < decorationStart ? path + ".ingredients[" + i + "]" : path + ".decorations[" + (i - decorationStart) + "]";
            this.reserve(occupied, ingredients.get(i).slot(), entryPath + ".slot");
        }
        for (int i = 0; i < results.size(); i++) this.reserve(occupied, results.get(i).slot(), path + ".results[" + i + "].slot");
    }

    private void reserve(Map<Integer, String> occupied, int slot, String path) throws IOException {
        if (slot < 0) return;
        String previous = occupied.putIfAbsent(slot, path);
        if (previous != null) throw this.error(path, "slot " + slot + " overlaps " + previous);
    }

    private int amount(Map<String, Object> value, String path) throws IOException {
        int amount = this.intValue(value, "amount", 1, path);
        if (amount < 1) throw this.error(path + ".amount", "must be positive");
        return amount;
    }

    private int contentSlot(Map<String, Object> value, String path, int rows) throws IOException {
        int slot = this.intValue(value, "slot", -1, path);
        if (slot < -1 || slot >= rows * 9) throw this.error(path + ".slot", "must be -1 or between 0 and " + (rows * 9 - 1));
        return slot;
    }

    private int intValue(Map<String, Object> values, String key, int fallback, String path) throws IOException {
        return values.containsKey(key) ? this.integer(values.get(key), path + "." + key) : fallback;
    }

    private int integer(Object value, String path) throws IOException {
        if (!(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)) throw this.error(path, "expected an integer");
        long number = ((Number) value).longValue();
        if (number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) throw this.error(path, "integer is out of range");
        return (int) number;
    }

    boolean bool(Map<String, Object> values, String key, boolean fallback, String path) throws IOException {
        if (!values.containsKey(key)) return fallback;
        if (!(values.get(key) instanceof Boolean value)) throw this.error(path, "expected true or false");
        return value;
    }

    private String string(Object value, String path, boolean required) throws IOException {
        if (value == null && !required) return null;
        if (!(value instanceof String text) || required && text.isBlank()) throw this.error(path, "expected " + (required ? "a nonempty string" : "a string"));
        return text;
    }

    private List<?> list(Object value, String path) throws IOException {
        if (value == null) return List.of();
        if (!(value instanceof List<?> list)) throw this.error(path, "expected a list");
        return list;
    }

    private List<String> strings(Object value, String path) throws IOException {
        List<?> configured = this.list(value, path);
        List<String> strings = new ArrayList<>(configured.size());
        for (int i = 0; i < configured.size(); i++) {
            if (!(configured.get(i) instanceof String text)) throw this.error(path + "[" + i + "]", "expected a string");
            strings.add(text);
        }
        return List.copyOf(strings);
    }

    Map<String, Object> map(Object value, String path) throws IOException {
        if (!(value instanceof Map<?, ?> map)) throw this.error(path, "expected a mapping");
        Map<String, Object> result = new LinkedHashMap<>();
        for (var entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) throw this.error(path, "mapping keys must be strings");
            result.put(key, entry.getValue());
        }
        return result;
    }

    void keys(Map<String, Object> values, String path, Set<String> allowed) throws IOException {
        for (String key : values.keySet()) if (!allowed.contains(key)) throw this.error(path.isEmpty() ? key : path + "." + key, "unknown setting");
    }

    IOException error(String path, String message) { return new IOException(this.file + ": " + path + ": " + message); }
    IOException error(String path, String message, Throwable cause) { return new IOException(this.file + ": " + path + ": " + message, cause); }
}
