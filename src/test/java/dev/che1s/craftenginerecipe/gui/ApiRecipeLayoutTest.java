package dev.che1s.craftenginerecipe.gui;

import dev.che1s.craftenginerecipe.api.ApiItem;
import dev.che1s.craftenginerecipe.api.ApiNote;
import dev.che1s.craftenginerecipe.api.ApiRecipe;
import dev.che1s.craftenginerecipe.api.DisplayRecipe;
import dev.che1s.craftenginerecipe.api.LocalizedText;
import dev.che1s.craftenginerecipe.api.internal.ApiRecipeCompiler;
import dev.che1s.craftenginerecipe.recipe.presentation.RecipePresentation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ApiRecipeLayoutTest {
    @Test
    void compilesRequiredAndOptionalFieldsIntoTheCerApiLayout() {
        ApiRecipe recipe = new ApiRecipe("tomato_soup",
                new ApiItem("minecraft:cauldron",
                        LocalizedText.of(Map.of("zh_CN", "炖锅", "en_US", "Pot")),
                        List.of(LocalizedText.of(Map.of("zh_CN", "工作站说明", "en_US", "Station lore")))),
                List.of(new ApiItem("garden:tomato",
                        LocalizedText.of(Map.of("zh_CN", "番茄", "en_US", "Tomato")),
                        List.of(LocalizedText.plain("新鲜")), 2)),
                List.of(new ApiItem("garden:soup",
                        LocalizedText.of(Map.of("zh_CN", "番茄汤", "en_US", "Tomato soup")),
                        List.of(LocalizedText.plain("热饮")), 4)),
                new ApiNote(LocalizedText.of(Map.of("zh_CN", "备注", "en_US", "Note")),
                        List.of(LocalizedText.of(Map.of("zh_CN", "小火慢炖", "en_US", "Simmer gently")))));

        DisplayRecipe chinese = ApiRecipeCompiler.compile("test_plugin", recipe, "zh_CN").display();
        assertEquals("cer_api:test_plugin/tomato_soup", chinese.id());
        assertEquals(4, chinese.layout().workstationSlot());
        assertEquals(31, chinese.ingredients().getLast().slot());
        assertEquals(45, chinese.layout().previousSlot());
        assertEquals(48, chinese.layout().backSlot());
        assertEquals(53, chinese.layout().nextSlot());
        assertEquals(ApiRecipeCompiler.PATTERN, chinese.layout().pattern());
        assertEquals("炖锅", chinese.workstation().name());
        assertEquals("工作站说明", chinese.workstation().icon().lore().getFirst());
        assertEquals("番茄", chinese.ingredients().getFirst().choices().getFirst().name());
        assertEquals(2, chinese.ingredients().getFirst().amount());
        assertEquals("番茄汤", chinese.results().getFirst().item().name());
        assertEquals(4, chinese.results().getFirst().amount());
        assertEquals(List.of("番茄汤"), chinese.results().stream()
                .map(result -> result.item().name()).toList());
        assertEquals("备注", chinese.ingredients().getLast().choices().getFirst().name());
        assertEquals("小火慢炖", chinese.ingredients().getLast().choices().getFirst().lore().getFirst());
        assertEquals("cer_api", chinese.ingredients().getLast().choices().getFirst().provider());
        assertEquals("note", chinese.ingredients().getLast().choices().getFirst().id());

        DisplayRecipe english = ApiRecipeCompiler.compile("test_plugin", recipe, "en_US").display();
        assertEquals("Pot", english.workstation().name());
        assertEquals("Tomato", english.ingredients().getFirst().choices().getFirst().name());
        assertEquals("Simmer gently", english.ingredients().getLast().choices().getFirst().lore().getFirst());
        assertEquals("cer_api_gui:default_recipe_gui",
                RecipePresentation.backgroundImage(english.id(), english.workstation().id()));
    }

    @Test
    void fixedRegionsPaginateIndependentlyAndKeepTheNoteOnEveryPage() {
        List<ApiItem> ingredients = new ArrayList<>();
        for (int i = 0; i < 17; i++) ingredients.add(new ApiItem("minecraft:stone"));
        List<ApiItem> results = new ArrayList<>();
        for (int i = 0; i < 17; i++) results.add(new ApiItem("minecraft:crafting_table"));
        ApiRecipe recipe = new ApiRecipe("many",
                new ApiItem("minecraft:crafting_table"),
                ingredients,
                results,
                new ApiNote(LocalizedText.plain("备注"), LocalizedText.plain("说明")));
        DisplayRecipe display = ApiRecipeCompiler.compile("test_plugin", recipe, "zh_CN").display();

        var plan = ChestRecipeRenderer.plan(display);
        assertEquals(2, plan.pages().size());
        var first = plan.pages().getFirst();
        var second = plan.pages().getLast();
        assertEquals(9, placement(first, false, 0).slot());
        assertEquals(39, placement(first, false, 15).slot());
        assertEquals(14, placement(first, true, 0).slot());
        assertEquals(44, placement(first, true, 15).slot());
        assertEquals(31, placement(first, false, 17).slot());
        assertEquals(9, placement(second, false, 16).slot());
        assertEquals(14, placement(second, true, 16).slot());
        assertEquals(31, placement(second, false, 17).slot());
    }

    private static ChestRecipeRenderer.Placement placement(
            ChestRecipeRenderer.Page page, boolean output, int index) {
        return page.placements().stream()
                .filter(placement -> placement.output() == output && placement.index() == index)
                .findFirst()
                .orElseThrow();
    }
}
