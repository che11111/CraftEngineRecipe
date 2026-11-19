package dev.che1s.craftenginerecipe.integration.farmersdelight;

import dev.che1s.craftenginerecipe.api.DisplayRecipe;
import dev.che1s.craftenginerecipe.api.RecipeIngredient;
import dev.che1s.craftenginerecipe.api.RecipeLayout;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class FarmersDelightRecipeSourceTest {
    private static final String ROOT = "com.huidu.farmersdelight.";

    @BeforeEach void reset() { Api.loaded = true; Api.specials = List.of(); Api.types = List.of(); Discovery.unlocked = true; Icons.resolutions = 0; Text.translations = Map.of(); }

    @Test void importsDefaultAndGroupedPotRecipesWithoutCollidingAndRetainsAlternativesAndContainer() {
        RawIngredient options = new RawIngredient(List.of(new Stack("farmersdelight:beef", 1), new Stack("minecraft:porkchop", 1)));
        Pot defaultPot = new Pot("stew", List.of(options), new Stack("minecraft:bowl", 1), true, new Stack("farmersdelight:stew", 3), 0.5f, 100);
        Pot customPot = new Pot("stew", List.of(options), null, false, new Stack("farmersdelight:stew", 6), 1, 200);
        Fixture fixture = new Fixture(new Snapshot(Map.of("stew", defaultPot), Map.of("large", Map.of("stew", customPot))), Map.of());

        List<DisplayRecipe> recipes = source(fixture).capture();

        assertEquals(2, recipes.size());
        assertNotEquals(recipes.get(0).id(), recipes.get(1).id());
        assertNotEquals(recipes.get(0).workstation().id(), recipes.get(1).workstation().id());
        assertEquals(2, recipes.getFirst().ingredients().getFirst().choices().size());
        RecipeIngredient container = recipes.getFirst().ingredients().stream()
                .filter(i -> i.role() == RecipeIngredient.Role.CONTAINER).findFirst().orElseThrow();
        assertEquals("minecraft", container.choices().getFirst().provider());
        assertEquals(3, recipes.getFirst().results().getFirst().amount());
        assertEquals(6, recipes.getLast().results().getFirst().amount());
        assertEquals(List.of(19, 41), realIngredients(recipes.getFirst()).stream()
                .map(RecipeIngredient::slot).toList());
        assertEquals(43, recipes.getFirst().results().getFirst().slot());
        assertEquals(List.of(19), realIngredients(recipes.getLast()).stream()
                .map(RecipeIngredient::slot).toList());
        assertEquals(43, recipes.getLast().results().getFirst().slot());
        assertUsesFarmersDelightUi(recipes.getFirst());
        assertEquals(1, realIngredients(recipes.getLast()).size());
        assertFakeButton(recipes.getFirst(), 0);
        assertFakeButton(recipes.getLast(), 0);
        assertEquals("craftengine", recipes.getFirst().results().getFirst().item().provider());
    }

    @Test void importsIndependentByproductChancesAndToolsAsOneAlternativeRequirement() {
        Tool tools = new Tool(new RawIngredient(List.of(new Stack("farmersdelight:iron_knife", 1), new Stack("farmersdelight:gold_knife", 1))), Set.of("farmersdelight:gold_knife"));
        Board board = new Board("wild_rice", new RawIngredient(List.of(new Stack("farmersdelight:wild_rice", 1))), List.of(tools),
                List.of(new Output(new Stack("farmersdelight:rice", 2), 1), new Output(new Stack("farmersdelight:straw", 1), 0.125)));

        DisplayRecipe recipe = source(new Fixture(new Snapshot(Map.of(), Map.of()), Map.of("wild_rice", board))).capture().getFirst();

        assertEquals(2, recipe.results().size());
        assertNull(recipe.results().getFirst().note());
        assertEquals("独立概率：12.5%", recipe.results().getLast().note());
        assertEquals(2, recipe.results().getFirst().amount());
        assertEquals(28, recipe.ingredients().getFirst().slot());
        RecipeIngredient tool = recipe.ingredients().stream()
                .filter(i -> i.role() == RecipeIngredient.Role.TOOL).findFirst().orElseThrow();
        assertEquals(30, tool.slot());
        assertEquals(List.of(32, 33), recipe.results().stream().map(result -> result.slot()).toList());
        assertEquals(List.of("farmersdelight:iron_knife"), tool.choices().stream().map(i -> i.id()).toList());
        assertFakeButton(recipe, 0);
        assertUsesFarmersDelightUi(recipe);
    }

    @Test void customCookingPotAndBoardSlotsFollowFarmersDelightGuiConfig() {
        DetailConfig pot = new DetailConfig(List.of(10, 11), 16, 14, -1, -1, List.of());
        DetailConfig customPot = new DetailConfig(List.of(20, 21, 22), 25, 24, -1, -1, List.of());
        DetailConfig board = new DetailConfig(List.of(), -1, -1, 28, 30, List.of(32, 33, 41, 42));
        Fixture fixture = new Fixture(new Snapshot(
                Map.of("stew", new Pot("stew", List.of(new RawIngredient(List.of(new Stack("minecraft:beef", 1)))),
                                new Stack("minecraft:bowl", 1), true, new Stack("farmersdelight:stew", 1), 0, 20)),
                Map.of("large", Map.of("stew", new Pot("stew", List.of(new RawIngredient(List.of(new Stack("minecraft:beef", 1))),
                                new RawIngredient(List.of(new Stack("minecraft:carrot", 1)))), null, false,
                        new Stack("farmersdelight:stew", 1), 0, 20)))),
                Map.of("rice", new Board("rice", new RawIngredient(List.of(new Stack("minecraft:rice", 1))), List.of(),
                        List.of(new Output(new Stack("farmersdelight:rice", 1), 1)))),
                new GuiSection(pot, Map.of("large", customPot), board));

        List<DisplayRecipe> recipes = source(fixture).capture();
        DisplayRecipe defaultPotRecipe = recipes.stream().filter(recipe -> recipe.id().contains("default")).findFirst().orElseThrow();
        DisplayRecipe customPotRecipe = recipes.stream().filter(recipe -> recipe.id().contains("group/large")).findFirst().orElseThrow();
        DisplayRecipe boardRecipe = recipes.stream().filter(recipe -> recipe.workstation().id().contains("cutting_board")).findFirst().orElseThrow();

        assertEquals(List.of(10, 14), realIngredients(defaultPotRecipe).stream()
                .map(RecipeIngredient::slot).toList());
        assertEquals(16, defaultPotRecipe.results().getFirst().slot());
        assertEquals(List.of(20, 21), realIngredients(customPotRecipe).stream()
                .map(RecipeIngredient::slot).toList());
        assertEquals(25, customPotRecipe.results().getFirst().slot());
        assertEquals(28, realIngredients(boardRecipe).getFirst().slot());
        assertFakeButton(defaultPotRecipe, 0);
        assertFakeButton(customPotRecipe, 0);
        assertFakeButton(boardRecipe, 0);
        assertEquals(List.of(32), boardRecipe.results().stream().map(result -> result.slot()).toList());
    }

    @Test void respectsSourcePermissionAndEvaluatesDiscoveryForEachViewerAfterCapture() {
        Pot pot = new Pot("stew", List.of(), null, false, new Stack("farmersdelight:stew", 1), 0, 40);
        DisplayRecipe recipe = source(new Fixture(new Snapshot(Map.of("stew", pot), Map.of()), Map.of())).capture().getFirst();

        assertFalse(recipe.canView(player(false)));
        assertTrue(recipe.canView(player(true)));
        Discovery.unlocked = false;
        assertFalse(recipe.canView(player(true)));
        Discovery.unlocked = true;
        assertTrue(recipe.canView(player(true)));
    }

    @Test void missingOrUnreadyPluginProducesNoRecipes() {
        FarmersDelightRecipeSource absent = new FarmersDelightRecipeSource((Plugin) null);
        assertFalse(absent.ready());
        assertTrue(absent.capture().isEmpty());
        Api.loaded = false;
        FarmersDelightRecipeSource source = source(new Fixture(new Snapshot(Map.of(), Map.of()), Map.of()));
        assertFalse(source.ready());
        assertTrue(source.capture().isEmpty());
    }

    @Test void skilletUsesCampfireInputAndCuttingBoardSlotPositions() {
        FarmersDelightRecipeSource source = source(new Fixture(new Snapshot(Map.of(), Map.of()), Map.of()));

        DisplayRecipe recipe = source.skilletRecipe(
                new Stack("farmersdelight:beef", 1),
                new Stack("farmersdelight:steak", 2), 600, 0.35f);

        assertEquals("farmersdelight:skillet", recipe.workstation().id());
        assertEquals("farmersdelight:skillet", recipe.workstation().icon().id());
        assertEquals("农夫乐事·煎锅", recipe.workstation().name());
        assertEquals(19, recipe.ingredients().getFirst().slot());
        assertEquals("farmersdelight:beef", recipe.ingredients().getFirst().choices().getFirst().id());
        RecipeIngredient info = recipe.ingredients().stream()
                .filter(i -> i.choices().getFirst().id().equals("internal:cooking_info"))
                .findFirst().orElseThrow();
        assertEquals(21, info.slot());
        assertEquals(RecipeIngredient.Role.INFO, info.role());
        assertEquals("craftengine", info.choices().getFirst().provider());
        assertEquals("<gold>配方信息", info.choices().getFirst().name());
        assertTrue(info.choices().getFirst().lore().stream()
                .anyMatch(line -> line.contains("时间：600 刻")));
        assertTrue(info.choices().getFirst().lore().stream()
                .noneMatch(line -> line.contains("<arg:")));
        assertEquals(2, recipe.ingredients().size());
        assertEquals(23, recipe.results().getFirst().slot());
        assertEquals("farmersdelight:steak", recipe.results().getFirst().item().id());
        assertEquals(2, recipe.results().getFirst().amount());
        assertEquals("cer_fd_gui:fd_pot_menu", FarmersDelightRecipeSource.backgroundImage("farmersdelight:skillet"));
    }

    @Test void skilletIdentityIncludesTheResultSoOneInputCanProduceMultipleOutputs() {
        FarmersDelightRecipeSource source = source(new Fixture(new Snapshot(Map.of(), Map.of()), Map.of()));

        DisplayRecipe first = source.skilletRecipe(
                new Stack("farmersdelight:beef", 1), new Stack("farmersdelight:steak", 1), 600, 0.35f);
        DisplayRecipe second = source.skilletRecipe(
                new Stack("farmersdelight:beef", 1), new Stack("farmersdelight:beef_patty", 1), 300, 0.2f);

        assertNotEquals(first.id(), second.id());
    }

    @Test void specialPagesUseTheirOwnDetailGridAndConditionSlots() {
        Api.specials = List.of(new Special("compost", "farmersdelight:compost", "special.compost",
                List.of("compost.description", "compost.line"), List.of(new Slot("minecraft:dirt", null, null)),
                List.of(new Slot("minecraft:bone_meal", null, null)),
                List.of(new Slot(null, "farmersdelight:compost", "activators")), true, true, true));
        Object detail = new Cfg(Map.of(
                "title", "<white><icon><lang:gui.recipe.special_recipes>",
                "rows", 4,
                "layout", List.of("XXXXHXXXX", "XIXXXXXOX", "XXXDEFXXX", "BXXXXGXXX"),
                "legend", new Cfg(Map.of("H", "description", "I", "input", "O", "output",
                        "D", "sunlight", "E", "water", "F", "catalyst_info", "G", "catalyst_item",
                        "B", "back", "X", "background")),
                "title-layout", new Cfg(Map.of("craftengine", new Cfg(Map.of(
                        "offset", "", "icon", "<image:farmersdelight:decomposition>",
                        "sunlight", "<image:farmersdelight:sun>", "water", "<image:farmersdelight:water>",
                        "catalyst_info", "<image:farmersdelight:mushroom>"))))));
        Fixture fixture = new Fixture(new Snapshot(Map.of(), Map.of()), Map.of(),
                new Cfg(Map.of("special-recipe-detail", detail)));

        DisplayRecipe recipe = source(fixture).capture().getFirst();

        assertEquals(4, recipe.layout().rows());
        assertTrue(recipe.layout().title().contains("<bold>农夫乐事·特殊配方</bold>"));
        assertEquals("farmersdelight:rich_soil", recipe.workstation().icon().id());
        assertEquals(4, slotOf(recipe, "minecraft:paper"));
        assertEquals(10, slotOf(recipe, "minecraft:dirt"));
        assertEquals(16, recipe.results().getFirst().slot());
        assertEquals(21, slotOfName(recipe, "gui.condition.sunlight"));
        assertEquals(22, slotOfName(recipe, "gui.condition.water"));
        assertEquals(23, slotOfName(recipe, "gui.condition.catalyst_info"));
        assertEquals(32, recipe.ingredients().stream()
                .filter(i -> i.choices().size() == 2).findFirst().orElseThrow().slot());
        assertFakeButton(recipe, 27);
    }

    @Test void itemDescriptionSpecialsUseTheCompactSpecialLayoutAndFixedDescriptionSlot() {
        Api.specials = List.of(new Special("wild_cabbages", "farmersdelight:wild_cabbages",
                "special.wild", List.of("wild.description"), List.of(), List.of(), List.of(),
                false, false, false, "item_description"));
        Object detail = new Cfg(Map.of(
                "title", "<white><icon><lang:gui.recipe.special_recipes>",
                "rows", 3,
                "layout", List.of("XXXXCXXXX", "XIXXXXXOX", "BXXXXXXXX"),
                "legend", new Cfg(Map.of("C", "description", "I", "input", "O", "output",
                        "B", "back", "X", "background")),
                "title-layout", new Cfg(Map.of("craftengine", new Cfg(Map.of(
                        "offset", "", "icon", "<image:farmersdelight:decomposition_none>"))))));
        Fixture fixture = new Fixture(new Snapshot(Map.of(), Map.of()), Map.of(),
                new Cfg(Map.of("special-recipe-detail-basic", detail)));

        DisplayRecipe recipe = source(fixture).capture().getFirst();

        assertEquals(3, recipe.layout().rows());
        assertEquals(4, recipe.ingredients().getFirst().slot());
        assertEquals(16, recipe.results().getFirst().slot());
        assertEquals("farmersdelight:rich_soil", recipe.workstation().icon().id());
        assertFakeButton(recipe, 18);
    }

    private static int slotOfName(DisplayRecipe recipe, String name) {
        return recipe.ingredients().stream().filter(i -> i.choices().getFirst().name() != null
                && i.choices().getFirst().name().contains(name)).findFirst().orElseThrow().slot();
    }

    @Test void specialRecipesRetainBehaviorCandidatesCatalystsAndConditions() {
        Api.specials = List.of(new Special("compost", "farmersdelight:compost", "special.compost", List.of("compost.description"),
                List.of(new Slot("minecraft:dirt", null, null)), List.of(new Slot("minecraft:bone_meal", null, null)),
                List.of(new Slot(null, "farmersdelight:compost", "activators")), true, true));

        DisplayRecipe recipe = source(new Fixture(new Snapshot(Map.of(), Map.of()), Map.of())).capture().getFirst();

        assertEquals("minecraft:bone_meal", recipe.results().getFirst().item().id());
        RecipeIngredient description = recipe.ingredients().stream()
                .filter(i -> i.choices().getFirst().id().equals("minecraft:paper")).findFirst().orElseThrow();
        assertEquals("compost.description", description.choices().getFirst().name());
        assertTrue(recipe.ingredients().stream().anyMatch(i -> i.choices().getFirst().name() != null
                && i.choices().getFirst().name().contains("gui.condition.sunlight")));
        assertTrue(recipe.ingredients().stream().anyMatch(i -> i.choices().getFirst().name() != null
                && i.choices().getFirst().name().contains("gui.condition.water")));
        RecipeIngredient catalysts = recipe.ingredients().stream()
                .filter(i -> i.choices().size() == 2 && i.role() == RecipeIngredient.Role.INFO).findFirst().orElseThrow();
        assertEquals(2, catalysts.choices().size());
        assertUsesFarmersDelightUi(recipe);
    }

    @Test void addonDisplayRoleListsRemainSeparateSimultaneousSlots() {
        Api.types = List.of(new AddonType("barbequesdelight:grilling", Component.text("Grill"), new Stack("addon:grill", 1),
                List.of(new AddonRecipe("roast", List.of(new Stack("minecraft:beef", 2)), new Stack("addon:roast", 3),
                        Map.of("container", List.of(new Stack("minecraft:bowl", 1), new Stack("minecraft:glass_bottle", 1)))))));

        DisplayRecipe recipe = source(new Fixture(new Snapshot(Map.of(), Map.of()), Map.of())).capture().getFirst();

        assertEquals(3, realIngredients(recipe).size());
        assertEquals(2, recipe.ingredients().getFirst().amount());
        assertEquals(2, recipe.ingredients().stream().filter(i -> i.role() == RecipeIngredient.Role.CONTAINER).count());
        assertTrue(recipe.ingredients().stream().allMatch(i -> i.choices().size() == 1));
        assertEquals(3, recipe.results().getFirst().amount());
        assertFakeButton(recipe, 0);
        assertUsesFarmersDelightUi(recipe);
    }

    @Test void trailTalesDedicatedProviderIsExcludedFromGenericAddonImport() {
        Api.types = List.of(new AddonType("trailandtales_delight:trail_recipes",
                Component.text("Trail & Tales Delight"),
                new Stack("trailandtales_delight:pottery_cooking_pot", 1),
                List.of(new AddonRecipe("ancient_coffee",
                        List.of(new Stack("minecraft:sugar", 1)),
                        new Stack("trailandtales_delight:ancient_coffee", 1), Map.of()))));

        assertTrue(source(new Fixture(new Snapshot(Map.of(), Map.of()), Map.of()))
                .capture().isEmpty());
    }

    private static void assertUsesFarmersDelightUi(DisplayRecipe recipe) {
        assertEquals(-1, recipe.layout().workstationSlot());
        assertEquals(-1, recipe.layout().informationSlot());
        assertEquals(-1, recipe.layout().previousSlot());
        assertEquals(-1, recipe.layout().backSlot());
        assertEquals(-1, recipe.layout().nextSlot());
        assertTrue(recipe.layout().nativeUi());
    }


    @Test void addonStationTitlesResolveResourcePackTranslationKeysThroughFarmersDelight() {
        Text.translations = Map.of("brewinandchewin.gui.keg.category_title", "酒桶发酵",
                "barbequesdelight.jei.grilling", "烧烤", "barbequesdelight.jei.skewering", "串串");
        List<AddonRecipe> recipes = List.of(new AddonRecipe("roast", List.of(), new Stack("addon:roast", 1), Map.of()));
        Api.types = List.of(
                new AddonType("brewinandchewin:keg", Component.translatable("brewinandchewin.gui.keg.category_title"), null, recipes),
                new AddonType("barbequesdelight:grilling", Component.translatable("barbequesdelight.jei.grilling"), null, recipes),
                new AddonType("barbequesdelight:skewering", Component.translatable("barbequesdelight.jei.skewering"), null, recipes));

        assertEquals(List.of("农夫乐事·发酵桶", "农夫乐事·烧烤架", "农夫乐事·配料盆"),
                source(new Fixture(new Snapshot(Map.of(), Map.of()), Map.of()))
                .capture().stream().map(recipe -> recipe.workstation().name()).toList());
    }

    @Test void addonStationTitlesResolveLiteralAndLegacyMisspelledTranslationKeys() {
        Text.translations = Map.of("brewinandchewin.gui.keg.category_title", "酒桶发酵",
                "barbequesdelight.jei.grilling", "烧烤", "barbequesdelight.jei.skewering", "串串");
        List<AddonRecipe> recipes = List.of(new AddonRecipe("roast", List.of(), new Stack("addon:roast", 1), Map.of()));
        Api.types = List.of(
                new AddonType("brewinandchewin:keg", Component.text("breuinandeheuin.guikeg.category_title"), null, recipes),
                new AddonType("barbequesdelight:grilling", Component.text("barbequesdelight.jei.grilling"), null, recipes),
                new AddonType("barbequesdelight:skewering", Component.text("barbequesdelight.jei.skeuering"), null, recipes));

        assertEquals(List.of("农夫乐事·发酵桶", "农夫乐事·烧烤架", "农夫乐事·配料盆"),
                source(new Fixture(new Snapshot(Map.of(), Map.of()), Map.of()))
                .capture().stream().map(recipe -> recipe.workstation().name()).toList());
    }

    @Test void addonStationTitlesPreferCerResourcePackTranslationsOverFarmersDelightServerLanguage() {
        // FD 自身的 serverText 使用服务端语言（此处为英语）；附属标题位于生成的资源包中，
        // 对 zh_CN 查看者必须优先使用 CER 配置的语言顺序。
        Text.translations = Map.of("brewinandchewin.gui.keg.category_title", "Keg Fermenting",
                "barbequesdelight.jei.grilling", "Grilling", "barbequesdelight.jei.skewering", "Skewering");
        Map<String, String> pack = Map.of("brewinandchewin.gui.keg.category_title", "酒桶发酵",
                "barbequesdelight.jei.grilling", "烧烤", "barbequesdelight.jei.skewering", "串串");
        List<AddonRecipe> recipes = List.of(new AddonRecipe("roast", List.of(), new Stack("addon:roast", 1), Map.of()));
        Api.types = List.of(
                new AddonType("brewinandchewin:keg", Component.text("breuinandeheuin.guikeg.category_title"), null, recipes),
                new AddonType("barbequesdelight:grilling", Component.text("barbequesdelight.jei.grilling"), null, recipes),
                new AddonType("barbequesdelight:skewering", Component.text("barbequesdelight.jei.skeuering"), null, recipes));

        assertEquals(List.of("农夫乐事·发酵桶", "农夫乐事·烧烤架", "农夫乐事·配料盆"),
                source(new Fixture(new Snapshot(Map.of(), Map.of()), Map.of()), pack::get)
                .capture().stream().map(recipe -> recipe.workstation().name()).toList());
    }

    @Test void addonPagesUseTheirOwnGridTitleImageAndSlotPositions() {
        Layout layout = new Layout(Component.translatable("barbequesdelight.jei.grilling"), 3,
                List.of("BXXXXXXXX", "XXIXXXRXX", "XXXXXXXXX"),
                Map.of('I', "ingredient", 'R', "result", 'B', "back", 'X', "background"));
        Api.types = List.of(new AddonTypeLayout("barbequesdelight:grilling", Component.text("Grilling"),
                new Stack("barbequesdelight:grill", 1),
                List.of(new AddonRecipe("steak", List.of(new Stack("minecraft:beef", 1)),
                        new Stack("barbequesdelight:steak", 1), Map.of())), layout));

        DisplayRecipe recipe = source(new Fixture(new Snapshot(Map.of(), Map.of()), Map.of())).capture().getFirst();

        assertEquals(3, recipe.layout().rows());
        assertTrue(recipe.layout().nativeUi());
        assertTrue(recipe.layout().pattern().stream().noneMatch(row -> row.contains("#")));
        assertEquals(-1, recipe.layout().informationSlot());
        assertEquals(11, recipe.ingredients().getFirst().slot());
        assertEquals(15, recipe.results().getFirst().slot());
        assertTrue(recipe.layout().title().startsWith(RecipeLayout.JSON_TITLE_PREFIX));
        Component title = net.kyori.adventure.text.serializer.gson.GsonComponentSerializer.gson()
                .deserialize(recipe.layout().title().substring(RecipeLayout.JSON_TITLE_PREFIX.length()));
        assertEquals("农夫乐事·烧烤架", net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                .plainText().serialize(title));
        assertEquals(net.kyori.adventure.text.format.TextColor.color(0x292D35), title.color());
        assertEquals(net.kyori.adventure.text.format.TextDecoration.State.TRUE,
                title.decoration(net.kyori.adventure.text.format.TextDecoration.BOLD));
    }

    @Test void addonDisplayRolesUseTheirOwnGridSlots() {
        Layout layout = new Layout(Component.text("Keg"), 6, List.of(
                "BXXXUXXFX", "XXXXXTXXX", "CXIIOAXRD", "XXIIXXXXX", "XXMMXEXXX", "XXXXXXXXX"),
                Map.ofEntries(Map.entry('I', "ingredient"), Map.entry('U', "fluid_level"),
                        Map.entry('C', "fluid_icon"), Map.entry('O', "output_fluid"),
                        Map.entry('T', "ferment_info"), Map.entry('D', "bubble"),
                        Map.entry('M', "temperature"), Map.entry('R', "result"),
                        Map.entry('E', "return"), Map.entry('F', "fill"), Map.entry('B', "back"),
                        Map.entry('X', "background"), Map.entry('A', "progress")),
                Map.of(
                        "background", new Stack("minecraft:gray_stained_glass_pane", 1),
                        "bubble", new Stack("addon:bubble", 1),
                        "fill", new Stack("minecraft:hopper", 1),
                        "back", new Stack("addon:back", 1)));
        Map<String, List<ItemStack>> display = new java.util.LinkedHashMap<>();
        display.put("fluid", List.of(new Stack("addon:water", 1)));
        display.put("fluid_icon", List.of(new Stack("addon:water", 1)));
        display.put("fluid_level", List.of(new Stack("addon:water_level", 1)));
        display.put("output_fluid", List.of(new Stack("addon:beer_fluid", 1)));
        display.put("return", List.of(new Stack("addon:bottle", 1)));
        display.put("temperature", List.of(new Stack("addon:temperature", 1)));
        display.put("ferment_info", List.of(new Stack("addon:clock", 1)));
        Api.types = List.of(new AddonTypeLayout("brewinandchewin:keg", Component.text("Keg"),
                new Stack("brewinandchewin:keg", 1),
                List.of(new AddonRecipe("beer", List.of(new Stack("addon:malt", 1)),
                        new Stack("addon:beer", 1), display)), layout));

        DisplayRecipe recipe = source(new Fixture(new Snapshot(Map.of(), Map.of()), Map.of())).capture().getFirst();

        assertEquals(20, recipe.ingredients().stream()
                .filter(i -> i.choices().getFirst().id().equals("addon:malt"))
                .findFirst().orElseThrow().slot());
        assertEquals(25, recipe.results().getFirst().slot());
        assertEquals(18, slotOf(recipe, "addon:water"));
        assertEquals(4, slotOf(recipe, "addon:water_level"));
        assertEquals(22, slotOf(recipe, "addon:beer_fluid"));
        assertEquals(41, slotOf(recipe, "addon:bottle"));
        assertEquals(38, slotOf(recipe, "addon:temperature"));
        assertEquals(14, slotOf(recipe, "addon:clock"));
        assertEquals(26, slotOf(recipe, "addon:bubble"));
        assertTrue(recipe.ingredients().stream()
                .noneMatch(i -> i.choices().getFirst().id().equals("addon:back")));
        assertFakeButton(recipe, 0);
        assertEquals(23, slotOf(recipe, "farmersdelight:animated"));
        assertEquals("brewinandchewin_display", recipe.ingredients().stream()
                .filter(i -> i.choices().getFirst().id().equals("addon:water_level"))
                .findFirst().orElseThrow().choices().getFirst().provider());
        assertTrue(recipe.ingredients().stream()
                .noneMatch(i -> i.choices().getFirst().id().equals("minecraft:hopper")));
    }

    @Test void addonStaticDecorationsSkipUnsupportedFillButtons() {
        Layout layout = new Layout(Component.text("Keg"), 3,
                List.of("XXXXXXXXX", "XIXFXXRXX", "XXXXXXXXB"),
                Map.of('I', "ingredient", 'R', "result", 'F', "fill",
                        'B', "back", 'X', "background"),
                Map.of(
                        "background", new Stack("minecraft:gray_stained_glass_pane", 1),
                        "fill", new Stack("minecraft:hopper", 1),
                        "back", new Stack("minecraft:arrow", 1)));
        Api.types = List.of(new AddonTypeLayout("brewinandchewin:keg", Component.text("Keg"),
                new Stack("brewinandchewin:keg", 1),
                List.of(new AddonRecipe("beer", List.of(new Stack("addon:malt", 1)),
                        new Stack("addon:beer", 1), Map.of())), layout));

        DisplayRecipe recipe = source(new Fixture(new Snapshot(Map.of(), Map.of()), Map.of())).capture().getFirst();

        assertEquals(26, slotOf(recipe, "minecraft:arrow"));
        assertTrue(recipe.ingredients().stream()
                .noneMatch(i -> i.choices().getFirst().id().equals("minecraft:hopper")));
        assertTrue(recipe.ingredients().stream()
                .noneMatch(i -> i.choices().getFirst().id().equals("minecraft:gray_stained_glass_pane")));
    }

    private static int slotOf(DisplayRecipe recipe, String id) {
        return recipe.ingredients().stream()
                .filter(i -> i.choices().getFirst().id().equals(id))
                .findFirst().orElseThrow().slot();
    }

    private static List<RecipeIngredient> realIngredients(DisplayRecipe recipe) {
        return recipe.ingredients().stream()
                .filter(i -> !isFakeButton(i)).toList();
    }

    private static boolean isFakeButton(RecipeIngredient ingredient) {
        return ingredient.role() == RecipeIngredient.Role.INFO
                && ingredient.choices().size() == 1
                && ingredient.choices().getFirst().provider().equals("cer_fd_gui")
                && ingredient.choices().getFirst().id().equals("minecraft:air");
    }

    private static void assertFakeButton(DisplayRecipe recipe, int slot) {
        RecipeIngredient fake = recipe.ingredients().stream()
                .filter(FarmersDelightRecipeSourceTest::isFakeButton)
                .findFirst().orElseThrow();
        assertEquals(slot, fake.slot());
        assertEquals(RecipeIngredient.Role.INFO, fake.role());
        assertNull(fake.choices().getFirst().name());
        assertNull(fake.choices().getFirst().lore());
        assertNull(fake.choices().getFirst().icon());
    }

    @Test void addonRecipesWithMissingResultsStillUseTheTypeIcon() {
        Api.types = List.of(new AddonType("brewinandchewin:keg", Component.text("Keg"),
                new Stack("brewinandchewin:keg", 1),
                List.of(new AddonRecipe("beer", List.of(), null, Map.of()))));

        DisplayRecipe recipe = source(new Fixture(new Snapshot(Map.of(), Map.of()), Map.of())).capture().getFirst();

        assertEquals("brewinandchewin:keg", recipe.results().getFirst().item().id());
        assertUsesFarmersDelightUi(recipe);
    }

    @Test void addonRecipesIgnoreFarmersDelightDiscoveryButStillRequirePermission() {
        Discovery.unlocked = false;
        try {
            Api.types = List.of(new AddonType("brewinandchewin:keg", Component.text("Keg"), null,
                    List.of(new AddonRecipe("roast", List.of(), new Stack("addon:roast", 1), Map.of()))));

            DisplayRecipe recipe = source(new Fixture(new Snapshot(Map.of(), Map.of()), Map.of())).capture().getFirst();

            assertTrue(recipe.canView(player(true)));
            assertFalse(recipe.canView(player(false)));
        } finally {
            Discovery.unlocked = true;
        }
    }

    @Test void addonStationTitlesRetainSiblingsTranslateArgumentsAndUsePublishedFallback() {
        Text.translations = Map.of("addon.title", "%2$s · %1$s", "addon.grill", "烧烤");
        Component title = Component.text("[").append(Component.translatable("addon.title",
                        Component.translatable("addon.grill"), Component.text("大号")))
                .append(Component.text("] ")).append(Component.translatable("addon.missing").fallback("附属工作站"));
        Api.types = List.of(new AddonType("addon:grill", title, null,
                List.of(new AddonRecipe("roast", List.of(), new Stack("addon:roast", 1), Map.of()))));

        assertEquals("农夫乐事·[大号 · 烧烤] 附属工作站", source(new Fixture(new Snapshot(Map.of(), Map.of()), Map.of()))
                .capture().getFirst().workstation().name());
    }

    @Test void revisionIgnoresFreshEquivalentRecipesTypesAndItemStacks() throws Exception {
        Map<String, Object> pots = new LinkedHashMap<>();
        Map<String, Object> boards = new LinkedHashMap<>();
        Fixture fixture = new Fixture(new Snapshot(pots, Map.of("large", pots)), boards);
        FarmersDelightRecipeSource source = source(fixture);
        replaceEquivalentRecipes(pots, boards);
        long initial = poll(source);

        for (int i = 0; i < 10; i++) {
            // 每次轮询都会产生新的模型、包装列表和图标，因为 FD 附属插件可能如此提供数据。
            replaceEquivalentRecipes(pots, boards);
            assertEquals(initial, poll(source));
        }
    }

    @Test void revisionDetectsAddonChangesWithinTheSameWrapperAndRecipeAdditionsAndRemovals() throws Exception {
        List<ItemStack> inputs = new ArrayList<>(List.of(new Stack("minecraft:beef", 2)));
        Stack result = new Stack("addon:roast", 3);
        Map<String, List<ItemStack>> slots = new LinkedHashMap<>();
        List<AddonRecipe> recipes = new ArrayList<>(List.of(new AddonRecipe("roast", inputs, result, slots)));
        Api.types = List.of(new AddonType("addon:grilling", Component.text("Grill"), new Stack("addon:grill", 1), recipes));
        FarmersDelightRecipeSource source = source(new Fixture(new Snapshot(Map.of(), Map.of()), Map.of()));
        long revision = poll(source);

        inputs.set(0, new Stack("minecraft:porkchop", 2));
        assertEquals(++revision, poll(source));
        result.amount = 5;
        assertEquals(++revision, poll(source));
        slots.put("fuel", List.of(new Stack("minecraft:coal", 1)));
        assertEquals(++revision, poll(source));
        recipes.add(new AddonRecipe("steak", List.of(), new Stack("minecraft:cooked_beef", 1), Map.of()));
        assertEquals(++revision, poll(source));
        recipes.removeFirst();
        assertEquals(++revision, poll(source));
        assertEquals(revision, poll(source));
    }

    @Test void revisionDetectsPotAndBoardContentChangesAndRecipeRemoval() throws Exception {
        Map<String, Object> pots = new LinkedHashMap<>();
        Map<String, Object> boards = new LinkedHashMap<>();
        replaceEquivalentRecipes(pots, boards);
        FarmersDelightRecipeSource source = source(new Fixture(new Snapshot(pots, Map.of()), boards));
        long revision = poll(source);

        Pot pot = (Pot) pots.get("stew");
        ((Stack) pot.getResult()).amount = 9;
        assertEquals(++revision, poll(source));
        pots.put("stew", new Pot("stew", pot.getIngredients(), pot.getContainer(), true, pot.getResult(), 0.5f, 200));
        assertEquals(++revision, poll(source));
        Board board = (Board) boards.get("rice");
        board.getTools().getFirst().getExcludedItems().add("farmersdelight:gold_knife");
        assertEquals(++revision, poll(source));
        boards.put("rice", new Board(board.getId(), board.getInput(), board.getTools(),
                List.of(new Output(new Stack("farmersdelight:rice", 1), 0.5))));
        assertEquals(++revision, poll(source));
        pots.clear();
        assertEquals(++revision, poll(source));
        boards.clear();
        assertEquals(++revision, poll(source));
    }

    @Test void revisionDetectsSpecialConditionsSlotsAndDescriptions() throws Exception {
        FarmersDelightRecipeSource source = source(new Fixture(new Snapshot(Map.of(), Map.of()), Map.of()));
        Api.specials = List.of(new Special("compost", "farmersdelight:compost", "title", List.of("description"),
                List.of(new Slot("minecraft:dirt", null, null)), List.of(new Slot("minecraft:bone_meal", null, null)), List.of(), false, false));
        long revision = poll(source);
        Api.specials = List.of(new Special("compost", "farmersdelight:compost", "title", List.of("description"),
                List.of(new Slot("minecraft:dirt", null, null)), List.of(new Slot("minecraft:bone_meal", null, null)), List.of(), true, false));
        assertEquals(++revision, poll(source));
        Api.specials = List.of(new Special("compost", "farmersdelight:compost", "title", List.of("changed"),
                List.of(new Slot("minecraft:dirt", null, null)), List.of(new Slot("minecraft:bone_meal", null, null)), List.of(), true, false));
        assertEquals(++revision, poll(source));
        Api.specials = List.of();
        assertEquals(++revision, poll(source));
    }

    @Test void sharedIngredientsResolveOncePerPassButChangedTagContentsRemainObservable() throws Exception {
        List<ItemStack> tagContents = new ArrayList<>(List.of(new Stack("minecraft:beef", 1)));
        Map<String, Object> pots = new LinkedHashMap<>();
        for (int i = 0; i < 100; i++) {
            // 在大型配方包中，同一个已发布标签常见有多个不同的包装器。
            pots.put("stew_" + i, new Pot("stew_" + i,
                    List.of(new RawIngredient(tagContents, "tag:test:meat")), null, false,
                    new Stack("farmersdelight:stew", 1), 0, 40));
        }
        FarmersDelightRecipeSource source = source(new Fixture(new Snapshot(pots, Map.of()), Map.of()));
        long revision = poll(source);
        assertEquals(1, Icons.resolutions);
        assertEquals(100, source.capture().size());
        assertEquals(2, Icons.resolutions);

        tagContents.set(0, new Stack("minecraft:porkchop", 1));
        assertEquals(revision + 1, poll(source));
        assertEquals(3, Icons.resolutions);
        assertTrue(source.capture().stream().allMatch(recipe -> recipe.ingredients().getFirst()
                .choices().getFirst().id().equals("minecraft:porkchop")));
        assertEquals(4, Icons.resolutions);
    }

    @Test void recipeIdsPreserveNamespacesGroupsAndEscapedCharacters() {
        String defaultId = FarmersDelightRecipeSource.recipeId("cooking_pot", null, "addon:stew");
        String groupId = FarmersDelightRecipeSource.recipeId("cooking_pot", "default", "addon:stew");
        assertNotEquals(defaultId, groupId);
        assertNotEquals(FarmersDelightRecipeSource.recipeId("x", "a/b", "c"), FarmersDelightRecipeSource.recipeId("x", "a", "b/c"));
        assertNotEquals(FarmersDelightRecipeSource.recipeId("x", null, "a:b"), FarmersDelightRecipeSource.recipeId("x", null, "a_3ab"));
        assertTrue(defaultId.matches("[a-z0-9_.-]+:[a-z0-9/._-]+"));
    }

    private static FarmersDelightRecipeSource source(Fixture fixture) {
        return source(fixture, key -> null);
    }

    private static FarmersDelightRecipeSource source(Fixture fixture, java.util.function.Function<String, String> packTranslations) {
        FDReflection reflection = new FDReflection(FarmersDelightRecipeSourceTest.class.getClassLoader(), Map.of(
                ROOT + "api.FarmersDelightApi", Api.class,
                ROOT + "gui.RecipeIngredientIcons", Icons.class,
                ROOT + "api.item.FarmersDelightItems", Items.class,
                ROOT + "api.text.FarmersDelightText", Text.class,
                ROOT + "util.ItemUtils", Utilities.class,
                ROOT + "gui.RecipeViewGuiConfig", ViewGuiConfig.class,
                ROOT + "api.recipe.FarmersDelightRecipeDiscovery", Discovery.class));
        return new FarmersDelightRecipeSource(fixture, reflection, () -> true, packTranslations);
    }

    private static long poll(FarmersDelightRecipeSource source) throws Exception {
        var checkedAt = FarmersDelightRecipeSource.class.getDeclaredField("checkedAt");
        checkedAt.setAccessible(true);
        checkedAt.setLong(source, 0);
        return source.revision();
    }

    private static void replaceEquivalentRecipes(Map<String, Object> pots, Map<String, Object> boards) {
        pots.put("stew", new Pot("stew", List.of(new RawIngredient(List.of(new Stack("minecraft:beef", 1)))),
                new Stack("minecraft:bowl", 1), true, new Stack("farmersdelight:stew", 3), 0.5f, 100));
        boards.put("rice", new Board("rice", new RawIngredient(List.of(new Stack("farmersdelight:wild_rice", 1))),
                List.of(new Tool(new RawIngredient(List.of(new Stack("farmersdelight:iron_knife", 1))), new LinkedHashSet<>())),
                List.of(new Output(new Stack("farmersdelight:rice", 1), 1))));
        Api.specials = List.of(new Special("compost", "farmersdelight:compost", "title", List.of("description"),
                List.of(new Slot("minecraft:dirt", null, null)), List.of(new Slot("minecraft:bone_meal", null, null)), List.of(), true, false));
        Api.types = List.of(new AddonType("addon:grilling", Component.text("Grill"), new Stack("addon:grill", 1),
                List.of(new AddonRecipe("roast", List.of(new Stack("minecraft:beef", 2)), new Stack("addon:roast", 3),
                        Map.of("fuel", List.of(new Stack("minecraft:coal", 1)))))));
    }

    private static Player player(boolean permitted) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class}, (proxy, method, args) -> {
            if (method.getName().equals("hasPermission")) return permitted;
            if (method.getName().equals("toString")) return "viewer";
            return null;
        });
    }

    public static final class Cfg {
        private final Map<String, Object> values;
        Cfg(Map<String, Object> values) { this.values = values; }
        public Object getConfigurationSection(String path) {
            Object current = this;
            for (String part : path.split("\\.")) {
                if (!(current instanceof Cfg cfg)) return null;
                current = cfg.values.get(part);
            }
            return current instanceof Cfg ? current : null;
        }
        public String getString(String key) { return values.get(key) instanceof String text ? text : null; }
        public int getInt(String key) { return values.get(key) instanceof Number number ? number.intValue() : 0; }
        public List<String> getStringList(String key) {
            return values.get(key) instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of();
        }
        public Set<String> getKeys(boolean deep) { return values.keySet(); }
    }

    public record Snapshot(Map<String, Object> recipes, Map<String, Object> customRecipes) {}
    public record Pots(Snapshot snapshot) { public Snapshot capturePublished() { return snapshot; } }
    public record Boards(Map<String, Object> recipes) { public Map<String, Object> getRecipes() { return recipes; } }
    public record Fixture(Snapshot pot, Map<String, Object> board, Object viewGuiSection) {
        public Fixture(Snapshot pot, Map<String, Object> board) { this(pot, board, null); }
        public Pots getCookingPotRecipes() { return new Pots(pot); }
        public Boards getCuttingBoardRecipes() { return new Boards(board); }
        public Object getRecipeViewGuiSection() { return viewGuiSection; }
    }
    public record DetailConfig(List<Integer> ingredientSlots, int resultSlot, int containerSlot,
                               int inputSlot, int toolSlot, List<Integer> resultSlots) {
        public List<Integer> getIngredientSlots() { return ingredientSlots; }
        public int getResultSlot() { return resultSlot; }
        public int getContainerSlot() { return containerSlot; }
        public int getInputSlot() { return inputSlot; }
        public int getToolSlot() { return toolSlot; }
        public List<Integer> getResultSlots() { return resultSlots; }
    }
    public record GuiSection(DetailConfig pot, Map<String, DetailConfig> customPots, DetailConfig board) {}
    public static final class ViewGuiConfig {
        private final GuiSection section;
        private ViewGuiConfig(GuiSection section) { this.section = section; }
        public static ViewGuiConfig fromConfig(Object section) {
            if (section instanceof Cfg) {
                DetailConfig none = new DetailConfig(List.of(), -1, -1, -1, -1, List.of());
                return new ViewGuiConfig(new GuiSection(none, Map.of(), none));
            }
            return new ViewGuiConfig((GuiSection) section);
        }
        public DetailConfig getCookingPotDetail() { return section.pot(); }
        public DetailConfig getCookingPotDetail(String customId) { return section.customPots().getOrDefault(customId, section.pot()); }
        public DetailConfig getCuttingBoardDetail() { return section.board(); }
    }
    public record RawIngredient(List<ItemStack> options, String stableKey) {
        public RawIngredient(List<ItemStack> options) { this(options, null); }
    }
    public record Pot(String getId, List<RawIngredient> getIngredients, ItemStack getContainer, boolean getNeedsContainer,
                      ItemStack getResult, float getExperience, int getCookTime) {}
    public record Tool(RawIngredient asIngredient, Set<String> getExcludedItems) {
        public Set<String> getExcludedTags() { return Set.of(); }
        public String getKey() { return "farmersdelight:tools/knives"; }
    }
    public record Board(String getId, RawIngredient getInput, List<Tool> getTools, List<Output> getResults) {}
    public record Output(ItemStack getItem, double getChance) {}
    public record Special(String id, String iconItemId, String titleKey, List<String> descriptionKeys,
                          List<Slot> inputSlots, List<Slot> outputSlots, List<Slot> catalystSlots,
                          boolean hasSunlight, boolean hasWater, boolean hasCatalystInfo, String displayType) {
        public Special(String id, String iconItemId, String titleKey, List<String> descriptionKeys,
                       List<Slot> inputSlots, List<Slot> outputSlots, List<Slot> catalystSlots,
                       boolean hasSunlight, boolean hasWater, boolean hasCatalystInfo) {
            this(id, iconItemId, titleKey, descriptionKeys, inputSlots, outputSlots, catalystSlots,
                    hasSunlight, hasWater, hasCatalystInfo, "recipe");
        }
        public Special(String id, String iconItemId, String titleKey, List<String> descriptionKeys,
                       List<Slot> inputSlots, List<Slot> outputSlots, List<Slot> catalystSlots,
                       boolean hasSunlight, boolean hasWater) {
            this(id, iconItemId, titleKey, descriptionKeys, inputSlots, outputSlots, catalystSlots,
                    hasSunlight, hasWater, false, "recipe");
        }
    }
    public record Slot(String itemId, String behaviorBlockId, String behaviorListKey) {}
    public record AddonType(String id, Component title, ItemStack icon, List<AddonRecipe> recipes) {}
    public record AddonTypeLayout(String id, Component title, ItemStack icon, List<AddonRecipe> recipes,
                                  Layout detail) {
        public Layout detailLayout() { return detail; }
    }
    public record Layout(Component title, int rows, List<String> layout, Map<Character, String> legend,
                         Map<String, ItemStack> decorations) {
        public Layout(Component title, int rows, List<String> layout, Map<Character, String> legend) {
            this(title, rows, layout, legend, Map.of());
        }
    }
    public record AddonRecipe(String id, List<ItemStack> inputs, ItemStack result, Map<String, List<ItemStack>> displaySlots) {}
    public static final class Icons {
        static int resolutions;
        public static List<ItemStack> resolveIngredientOptions(RawIngredient ingredient) { ++resolutions; return ingredient.options(); }
    }
    public static final class Items {
        public static String idOf(ItemStack item) { return ((Stack) item).id; }
        public static ItemStack create(String id) { return null; }
        public static boolean matchesId(ItemStack item, String id) { return ((Stack) item).id.equals(id); }
    }
    public static final class Utilities {
        public static List<ItemStack> createSlotItems(String itemId, String behavior, String list) {
            return itemId == null ? List.of(new Stack("minecraft:brown_mushroom", 1), new Stack("minecraft:red_mushroom", 1))
                    : List.of(new Stack(itemId, 1));
        }
    }
    public static final class Api {
        static boolean loaded;
        static List<?> specials;
        static List<?> types;
        public static Api get() { return new Api(); }
        public boolean isContentLoaded() { return loaded; }
        public List<?> specialRecipes() { return specials; }
        public List<?> recipeTypes() { return types; }
    }
    public static final class Discovery {
        static boolean unlocked;
        public static boolean isUnlocked(Player viewer, String type, String id) { return unlocked; }
    }
    public static final class Text {
        static Map<String, String> translations = Map.of();
        public static String serverText(String key) { return translations.getOrDefault(key, key); }
    }
    private static final class Stack extends ItemStack {
        final String id;
        int amount;
        Stack(String id, int amount) { super(); this.id = id; this.amount = amount; }
        @Override public Material getType() { return Material.STONE; }
        @Override public int getAmount() { return amount; }
        @Override public boolean hasItemMeta() { return false; }
        @Override public ItemStack clone() { return new Stack(id, amount); }
        @Override public int hashCode() { return id.hashCode() * 31 + amount; }
    }
}
