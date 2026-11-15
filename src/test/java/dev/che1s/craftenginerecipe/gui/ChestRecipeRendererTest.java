package dev.che1s.craftenginerecipe.gui;

import dev.che1s.craftenginerecipe.api.DisplayRecipe;
import dev.che1s.craftenginerecipe.api.RecipeIngredient;
import dev.che1s.craftenginerecipe.api.RecipeItem;
import dev.che1s.craftenginerecipe.api.RecipeLayout;
import dev.che1s.craftenginerecipe.api.RecipeResult;
import dev.che1s.craftenginerecipe.api.RecipeWorkstation;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ChestRecipeRendererTest {
    @Test
    void applianceGuiTitleUsesTheCerResourceImageOriginAndWhiteOverlayText() {
        assertEquals("<#FFFFFF><shift:-8><image:cer_kc_gui:pot_gui><shift:-168>"
                        + "<#000000><bold>森罗物语·炒锅</bold></#000000>",
                ChestRecipeRenderer.guiTitle("cer_kc_gui:pot_gui", "森罗物语·炒锅"));
        assertEquals("<#FFFFFF><shift:-2><image:cer_fd_gui:fd_pot_menu><shift:-163>"
                        + "<#000000><bold>农夫乐事·煎锅</bold></#000000>",
                ChestRecipeRenderer.farmersDelightGuiTitle("cer_fd_gui:fd_pot_menu", "农夫乐事·煎锅"));
        assertEquals("<#FFFFFF><shift:-8><image:cer_kc_gui:pot_gui><shift:-168>"
                        + "<red><bold>森罗物语·炒锅",
                ChestRecipeRenderer.guiTitle("cer_kc_gui:pot_gui", "森罗物语·炒锅", "<red><bold>"));
    }

    @Test
    void kaleidoscopePlainTitlesStillReceiveTheirCerGuiBackgroundAfterYamlReload() {
        assertTrue(ChestRecipeRenderer.shouldApplyGuiBackground("kc:pot", "森罗物语·炒锅"));
        assertFalse(ChestRecipeRenderer.shouldApplyGuiBackground("kc:pot", "<image:custom:background>森罗物语·炒锅"));
        assertFalse(ChestRecipeRenderer.shouldApplyGuiBackground("example:pot", "森罗物语·炒锅"));
        assertTrue(ChestRecipeRenderer.shouldApplyGuiBackground("farmersdelight:skillet", "农夫乐事·煎锅"));
        assertTrue(ChestRecipeRenderer.shouldApplyGuiBackground("customcrops:pot/default", "种植盆 · 卷心菜"));
        assertTrue(ChestRecipeRenderer.shouldApplyGuiBackground("minecraft:cultivation", "耕地"));
        assertEquals("cer_api_gui:default_recipe_gui",
                dev.che1s.craftenginerecipe.recipe.presentation.RecipePresentation
                        .backgroundImage("cer_api:addon/recipe", "minecraft:crafting_table"));
    }

    @Test
    void customCropsAndCultivationUseTheirOwnRecipeBackgrounds() {
        assertEquals("cer_ccrops_gui:customcrops_gui",
                dev.che1s.craftenginerecipe.recipe.presentation.RecipePresentation
                        .backgroundImage("customcrops:pot/aquatic_crops"));
        assertEquals("cer_cultivation_gui:cultivation_gui",
                dev.che1s.craftenginerecipe.recipe.presentation.RecipePresentation
                        .backgroundImage("minecraft:cultivation"));
        assertNull(dev.che1s.craftenginerecipe.recipe.presentation.RecipePresentation
                .backgroundImage("example:unknown"));
    }

    @Test
    void integrationRecipeItemsKeepOnlyTheirSourceLore() {
        assertTrue(ChestRecipeRenderer.usesSourceLoreOnly("kc:accurate/pot:_example"));
        assertTrue(ChestRecipeRenderer.usesSourceLoreOnly("farmersdelight:cooking_pot/default/example"));
        assertFalse(ChestRecipeRenderer.usesSourceLoreOnly("example:custom_recipe"));
        assertFalse(ChestRecipeRenderer.usesSourceLoreOnly(null));
        assertTrue(ChestRecipeRenderer.hiddenSourceLore(
                Component.text("获取方式见内容包菜谱")));
        assertFalse(ChestRecipeRenderer.hiddenSourceLore(Component.text("理想配比")));
    }

    @Test
    void farmersDelightNativeTitlesEmphasizeTheFixedHeadingWithoutDoubleWrapping() {
        String pot = "<white><shift:-8><image:farmersdelight:pot><shift:-168>农夫乐事·烹饪锅";
        String board = "<white><shift:-8><image:farmersdelight:board><shift:-168>农夫乐事·砧板";
        String special = "<white><shift:-8><image:farmersdelight:special><shift:-168>农夫乐事·特殊配方";

        assertEquals("<white><shift:-8><image:farmersdelight:pot><shift:-168><bold>农夫乐事·烹饪锅</bold>",
                ChestRecipeRenderer.emphasizeFarmersDelightTitle("farmersdelight:cooking_pot/large", pot));
        assertEquals("<white><shift:-8><image:farmersdelight:board><shift:-168><bold>农夫乐事·砧板</bold>",
                ChestRecipeRenderer.emphasizeFarmersDelightTitle("farmersdelight:cutting_board", board));
        assertEquals("<white><shift:-8><image:farmersdelight:special><shift:-168><bold>农夫乐事·特殊配方</bold>",
                ChestRecipeRenderer.emphasizeFarmersDelightTitle("farmersdelight:special", special));
        assertEquals(special, ChestRecipeRenderer.emphasizeFarmersDelightTitle("farmersdelight:unknown", special));
    }

    @Test
    void localizedNativeTitlesKeepTheOriginalImageAndShiftTemplate() {
        String title = "<white><shift:-2><image:farmersdelight:cooking_pot_menu>"
                + "<shift:-163><black><bold>农夫乐事·烹饪锅</bold>";

        assertEquals("<white><shift:-2><image:farmersdelight:cooking_pot_menu>"
                        + "<shift:-163><black><bold>Farmer's Delight·Cooking Pot</bold>",
                ChestRecipeRenderer.localizeImageTitle(
                        "farmersdelight:cooking_pot", title,
                        "Farmer's Delight·Cooking Pot"));
    }

    @Test
    void explicitLoreColorsArePreservedWithoutChangingOtherRuns() {
        Component source = Component.text("紫色 ", NamedTextColor.LIGHT_PURPLE)
                .append(Component.text("蓝色", NamedTextColor.BLUE))
                .append(Component.text(" 嵌套", NamedTextColor.LIGHT_PURPLE)
                        .append(Component.text("金色", NamedTextColor.GOLD)));

        Component normalized = ChestRecipeRenderer.prepareLoreLine(source);

        assertEquals(NamedTextColor.LIGHT_PURPLE, normalized.color());
        assertEquals(NamedTextColor.BLUE, normalized.children().getFirst().color());
        assertEquals(NamedTextColor.LIGHT_PURPLE, normalized.children().getLast().color());
        assertEquals(NamedTextColor.GOLD,
                normalized.children().getLast().children().getFirst().color());
    }

    @Test
    void uncoloredLoreRootGetsAnIndependentGrayBase() {
        Component source = Component.text()
                .append(Component.text("说明", NamedTextColor.LIGHT_PURPLE))
                .append(Component.text(" 后续普通文字"))
                .build();

        Component normalized = ChestRecipeRenderer.prepareLoreLine(source);

        assertEquals(TextColor.color(0xB8C0CC), normalized.color());
        assertEquals(NamedTextColor.LIGHT_PURPLE, normalized.children().getFirst().color());
        assertNull(normalized.children().getLast().color());
    }

    @Test
    void automaticallyPlacesIngredientsLeftAndResultsRightWithoutUsingControlSlots() {
        DisplayRecipe recipe = recipe(RecipeLayout.defaults(), List.of(input(-1), input(-1)), List.of(output(-1)));

        var page = ChestRecipeRenderer.plan(recipe).pages().getFirst();

        assertEquals(10, placement(page, false, 0).slot());
        assertEquals(11, placement(page, false, 1).slot());
        assertEquals(16, placement(page, true, 0).slot());
        assertNoOverlaps(ChestRecipeRenderer.plan(recipe), recipe.layout());
    }

    @Test
    void preservesExactConfiguredChestSlots() {
        DisplayRecipe recipe = recipe(RecipeLayout.defaults(), List.of(input(10), input(11)), List.of(output(16)));

        var page = ChestRecipeRenderer.plan(recipe).pages().getFirst();

        assertEquals(10, placement(page, false, 0).slot());
        assertEquals(11, placement(page, false, 1).slot());
        assertEquals(16, placement(page, true, 0).slot());
    }

    @Test
    void paginatesEveryInputAndOutputExactlyOnce() {
        List<RecipeIngredient> inputs = new ArrayList<>();
        List<RecipeResult> outputs = new ArrayList<>();
        for (int i = 0; i < 120; i++) inputs.add(input(-1));
        for (int i = 0; i < 75; i++) outputs.add(output(-1));
        DisplayRecipe recipe = recipe(RecipeLayout.defaults(), inputs, outputs);

        var plan = ChestRecipeRenderer.plan(recipe);
        List<ChestRecipeRenderer.Placement> placements = plan.pages().stream().flatMap(p -> p.placements().stream()).toList();

        assertEquals(4, plan.pages().size());
        assertEquals(195, placements.size());
        assertEquals(120, placements.stream().filter(p -> !p.output()).map(ChestRecipeRenderer.Placement::index).distinct().count());
        assertEquals(75, placements.stream().filter(ChestRecipeRenderer.Placement::output).map(ChestRecipeRenderer.Placement::index).distinct().count());
        assertNoOverlaps(plan, recipe.layout());
    }

    @Test
    void fixedItemsOnAFullFirstPageDoNotStrandAutomaticOverflow() {
        RecipeLayout layout = new RecipeLayout(1, null, -1, -1, -1, -1, -1);
        List<RecipeIngredient> inputs = new ArrayList<>();
        for (int slot = 0; slot < 9; slot++) inputs.add(input(slot));
        DisplayRecipe recipe = recipe(layout, inputs, List.of(output(-1)));

        var plan = ChestRecipeRenderer.plan(recipe);

        assertEquals(2, plan.pages().size());
        assertEquals(9, plan.pages().getFirst().placements().size());
        assertEquals(1, plan.pages().getLast().placements().size());
        assertTrue(plan.pages().getLast().placements().getFirst().output());
        assertNoOverlaps(plan, layout);
    }

    @Test
    void smallLayoutsAndDisabledControlsStillPaginateAllContent() {
        RecipeLayout layout = new RecipeLayout(1, null, 0, 1, 2, 3, 4);
        DisplayRecipe recipe = recipe(layout, java.util.Collections.nCopies(13, input(-1)), List.of(output(-1)));

        var plan = ChestRecipeRenderer.plan(recipe);

        assertEquals(4, plan.pages().size());
        assertEquals(14, plan.pages().stream().mapToInt(p -> p.placements().size()).sum());
        assertTrue(plan.pages().getFirst().placements().stream().anyMatch(ChestRecipeRenderer.Placement::output));
        assertNoOverlaps(plan, layout);
    }

    @Test
    void nativeUiLayoutPlacesContentWithoutAnyCerControls() {
        RecipeLayout layout = RecipeLayout.nativeUi(null);
        DisplayRecipe recipe = recipe(layout, List.of(input(-1)), List.of(output(-1)));

        var page = ChestRecipeRenderer.plan(recipe).pages().getFirst();

        assertEquals(2, page.placements().size());
        assertEquals(-1, layout.workstationSlot());
        assertEquals(-1, layout.informationSlot());
        assertEquals(-1, layout.previousSlot());
        assertEquals(-1, layout.backSlot());
        assertEquals(-1, layout.nextSlot());
        assertNoOverlaps(ChestRecipeRenderer.plan(recipe), layout);
    }

    @Test
    void nativePatternKeepsContentSlotsWithoutAnyCerControls() {
        RecipeLayout layout = new RecipeLayout(3, null, -1, -1, -1, -1, -1,
                List.of("#########", "##A###B##", "#########"), true);
        DisplayRecipe recipe = recipe(layout, List.of(input(11)), List.of(output(15)));

        var page = ChestRecipeRenderer.plan(recipe).pages().getFirst();

        assertEquals(2, page.placements().size());
        assertNoOverlaps(ChestRecipeRenderer.plan(recipe), layout);
    }

    @Test
    void patternedMenuPlacesAllContentSymbolsInsteadOfLoopingOnUnknownOnes() {
        RecipeLayout layout = new RecipeLayout(2, null, -1, -1, -1, -1, -1,
                List.of("#ACD#####", "#EFB#####"), false);
        DisplayRecipe recipe = recipe(layout,
                List.of(input(-1), input(-1), input(-1)),
                List.of(output(-1), output(-1)));

        var pages = ChestRecipeRenderer.plan(recipe).pages();

        assertEquals(1, pages.size());
        assertEquals(5, pages.getFirst().placements().size());
        assertNoOverlaps(ChestRecipeRenderer.plan(recipe), layout);
    }

    @Test
    void patternedMenuWithoutContentSymbolsUsesFallbackSlotsAndTerminates() {
        RecipeLayout layout = new RecipeLayout(1, null, -1, -1, -1, -1, -1,
                List.of("#########"), false);
        List<RecipeIngredient> inputs = new ArrayList<>();
        for (int i = 0; i < 15; i++) inputs.add(input(-1));
        DisplayRecipe recipe = recipe(layout, inputs, List.of());

        var plan = ChestRecipeRenderer.plan(recipe);

        assertEquals(2, plan.pages().size());
        assertEquals(15, plan.pages().stream().mapToInt(page -> page.placements().size()).sum());
        assertNoOverlaps(plan, layout);
    }

    @Test
    void fixedDecorationPagesUseFreeSlotsForAutomaticResults() {
        RecipeLayout layout = new RecipeLayout(1, null, -1, -1, -1, -1, -1,
                List.of("A########"), false);
        DisplayRecipe recipe = recipe(layout, List.of(input(0)), List.of(output(-1)));

        var plan = ChestRecipeRenderer.plan(recipe);

        assertEquals(1, plan.pages().size());
        var output = plan.pages().getFirst().placements().stream()
                .filter(ChestRecipeRenderer.Placement::output).findFirst().orElseThrow();
        assertEquals(1, output.slot());
    }

    @Test
    void emptyRecipeStillHasOneContentPage() {
        var plan = ChestRecipeRenderer.plan(recipe(RecipeLayout.defaults(), List.of(), List.of()));
        assertEquals(1, plan.pages().size());
        assertTrue(plan.pages().getFirst().placements().isEmpty());
    }

    @Test
    void animationUpdatesOnlyAlternativesOnTheVisiblePage() {
        var rotating = new RecipeIngredient(List.of(RecipeItem.of("minecraft:stone"), RecipeItem.of("minecraft:dirt")),
                1, -1, RecipeIngredient.Role.INGREDIENT);
        List<RecipeIngredient> inputs = new ArrayList<>(java.util.Collections.nCopies(60, input(-1)));
        inputs.set(0, rotating);
        inputs.set(59, rotating);
        var plan = ChestRecipeRenderer.plan(recipe(RecipeLayout.defaults(), inputs, List.of(output(-1))));

        assertEquals(2, plan.pages().size());
        assertEquals(List.of(placement(plan.pages().getFirst(), false, 0)), plan.pages().getFirst().animated());
        assertEquals(List.of(placement(plan.pages().getLast(), false, 59)), plan.pages().getLast().animated());
    }

    @Test
    void fixedRecipesRequireNoContentUpdatesBetweenRenders() {
        var page = ChestRecipeRenderer.plan(recipe(RecipeLayout.defaults(), List.of(input(-1)), List.of(output(-1))))
                .pages().getFirst();
        assertEquals(2, page.placements().size());
        assertTrue(page.animated().isEmpty());
    }

    @Test
    void usageLookupPinsTheQueriedItemInsideASharedCandidateList() {
        List<RecipeItem> choices = List.of(
                RecipeItem.of("minecraft:cod"),
                RecipeItem.of("minecraft:salmon"),
                RecipeItem.of("minecraft:tropical_fish"));

        assertEquals(1, ChestRecipeRenderer.choiceIndex(choices, 0,
                net.momirealms.craftengine.core.util.Key.of("minecraft:salmon")));
        assertEquals(2, ChestRecipeRenderer.choiceIndex(choices, 2, null));
        assertFalse(ChestRecipeRenderer.shouldAnimateCandidates(choices,
                net.momirealms.craftengine.core.util.Key.of("minecraft:salmon")));
        assertTrue(ChestRecipeRenderer.shouldAnimateCandidates(
                List.of(RecipeItem.of("minecraft:beef"), RecipeItem.of("minecraft:porkchop")),
                net.momirealms.craftengine.core.util.Key.of("minecraft:salmon")));
        assertTrue(ChestRecipeRenderer.shouldAnimateCandidates(choices, null));
    }

    private static ChestRecipeRenderer.Placement placement(ChestRecipeRenderer.Page page, boolean output, int index) {
        return page.placements().stream().filter(p -> p.output() == output && p.index() == index).findFirst().orElseThrow();
    }

    private static void assertNoOverlaps(ChestRecipeRenderer.Plan plan, RecipeLayout layout) {
        for (var page : plan.pages()) {
            Set<Integer> used = new HashSet<>();
            for (int slot : new int[]{layout.workstationSlot(), layout.informationSlot(), layout.previousSlot(), layout.backSlot(), layout.nextSlot()}) {
                if (slot >= 0) used.add(slot);
            }
            for (var placement : page.placements()) {
                assertTrue(placement.slot() >= 0 && placement.slot() < layout.rows() * 9);
                assertTrue(used.add(placement.slot()), "overlapping slot " + placement.slot());
            }
        }
    }

    private static RecipeIngredient input(int slot) {
        return new RecipeIngredient(List.of(RecipeItem.of("minecraft:stone")), 1, slot, RecipeIngredient.Role.INGREDIENT);
    }

    private static RecipeResult output(int slot) {
        return new RecipeResult(RecipeItem.of("test:result"), 3, slot, "");
    }

    private static DisplayRecipe recipe(RecipeLayout layout, List<RecipeIngredient> inputs, List<RecipeResult> outputs) {
        return new DisplayRecipe("test:recipe", new RecipeWorkstation("test:station", RecipeItem.of("minecraft:crafting_table"), "Station"),
                layout, inputs, outputs, List.of(), null, null);
    }
}
