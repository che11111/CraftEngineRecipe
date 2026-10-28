package dev.che1s.craftenginerecipe.api.internal;

import dev.che1s.craftenginerecipe.api.ApiItem;
import dev.che1s.craftenginerecipe.api.ApiNote;
import dev.che1s.craftenginerecipe.api.ApiRecipe;
import dev.che1s.craftenginerecipe.api.DisplayRecipe;
import dev.che1s.craftenginerecipe.api.LocalizedText;
import dev.che1s.craftenginerecipe.api.RecipeIngredient;
import dev.che1s.craftenginerecipe.api.RecipeItem;
import dev.che1s.craftenginerecipe.api.RecipeLayout;
import dev.che1s.craftenginerecipe.api.RecipeResult;
import dev.che1s.craftenginerecipe.api.RecipeWorkstation;

import java.util.ArrayList;
import java.util.List;

/** 把公开的简易 API 数据编译为 CER 的展示模型。 */
public final class ApiRecipeCompiler {
    public static final String RECIPE_NAMESPACE = "cer_api";
    public static final String NOTE_PROVIDER = "cer_api";
    public static final String NOTE_ID = "note";
    public static final int WORKSTATION_SLOT = 4;
    public static final int NOTE_SLOT = 31;
    public static final int PREVIOUS_SLOT = 45;
    public static final int BACK_SLOT = 48;
    public static final int NEXT_SLOT = 53;
    /**
     * API 固定页面图案：◀ 只放原料，▶ 只放产物，# 是视觉留白。
     * 图形渲染器会重复这些类型槽位来分页，避免原料较少时产物被塞到左侧。
     */
    public static final List<String> PATTERN = List.of(
            "####T####",
            "◀◀◀◀#▶▶▶▶",
            "◀◀◀◀#▶▶▶▶",
            "◀◀◀◀#▶▶▶▶",
            "◀◀◀◀#▶▶▶▶",
            "P##R####N");

    private ApiRecipeCompiler() {
    }

    public static CompiledRecipe compile(String namespace, ApiRecipe source, String locale) {
        ApiItem station = source.workstation();
        RecipeItem stationIcon = item(station, locale);
        String stationName = resolve(station.name(), locale);
        RecipeWorkstation workstation = new RecipeWorkstation(station.id(), stationIcon,
                stationName == null || stationName.isBlank() ? station.id() : stationName);

        List<RecipeIngredient> ingredients = new ArrayList<>();
        for (ApiItem ingredient : source.ingredients()) {
            // -1 表示交给图案中的 ◀ 槽位动态分配，从而支持超过 16 项时自动分页。
            ingredients.add(new RecipeIngredient(List.of(item(ingredient, locale)), ingredient.amount(), -1,
                    RecipeIngredient.Role.INGREDIENT));
        }
        if (source.note() != null && !source.note().isEmpty()) {
            // 备注必须携带可本地化的名称和 Lore，因此建成固定槽位的 INFO 物品；
            // 普通 informationSlot 只支持公共 Lore，无法完整表达 ApiNote。
            ingredients.add(new RecipeIngredient(List.of(note(source.note(), locale)), 1, NOTE_SLOT,
                    RecipeIngredient.Role.INFO));
        }

        List<RecipeResult> results = source.results().stream()
                // 与原料相同，-1 让产物按 ▶ 区域排布并可独立分页。
                .map(item -> new RecipeResult(item(item, locale), item.amount(), -1, null))
                .toList();
        RecipeLayout layout = new RecipeLayout(6, null, WORKSTATION_SLOT, -1,
                PREVIOUS_SLOT, BACK_SLOT, NEXT_SLOT, PATTERN, false);
        String internalId = RECIPE_NAMESPACE + ":" + namespace + "/" + source.id();
        DisplayRecipe display = new DisplayRecipe(internalId, workstation, layout,
                ingredients, results, List.of(), null, null);
        return new CompiledRecipe(namespace, display);
    }

    private static RecipeItem item(ApiItem source, String locale) {
        RecipeItem identity = RecipeItem.of(source.id());
        String name = resolve(source.name(), locale);
        List<String> lore = resolve(source.lore(), locale);
        return new RecipeItem(identity.provider(), identity.id(), null,
                name == null || name.isBlank() ? null : name,
                lore == null || lore.isEmpty() ? null : lore);
    }

    private static RecipeItem note(ApiNote note, String locale) {
        String name = resolve(note.name(), locale);
        List<String> lore = resolve(note.lore(), locale);
        // 仅提供 Lore 时显式使用空名称，避免透明 PAPER 载体在悬浮提示中显示“纸”。
        return new RecipeItem(NOTE_PROVIDER, NOTE_ID, null,
                name == null || name.isBlank() ? "" : name,
                lore == null || lore.isEmpty() ? null : lore);
    }

    private static String resolve(LocalizedText text, String locale) {
        return text == null || text.isEmpty() ? null : text.resolve(locale);
    }

    private static List<String> resolve(List<LocalizedText> lines, String locale) {
        if (lines == null || lines.isEmpty()) return null;
        List<String> resolved = lines.stream()
                .map(line -> resolve(line, locale))
                .filter(line -> line != null && !line.isBlank())
                .toList();
        return resolved.isEmpty() ? null : resolved;
    }

    public record CompiledRecipe(String source, DisplayRecipe display) {
    }
}
