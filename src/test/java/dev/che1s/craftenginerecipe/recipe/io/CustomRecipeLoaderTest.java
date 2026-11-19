package dev.che1s.craftenginerecipe.recipe.io;

import dev.che1s.craftenginerecipe.api.DisplayRecipe;
import dev.che1s.craftenginerecipe.api.RecipeIngredient;
import dev.che1s.craftenginerecipe.api.RecipeItem;
import dev.che1s.craftenginerecipe.api.RecipeLayout;
import dev.che1s.craftenginerecipe.api.RecipeResult;
import dev.che1s.craftenginerecipe.api.RecipeWorkstation;
import dev.che1s.craftenginerecipe.recipe.presentation.KaleidoscopePresentation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CustomRecipeLoaderTest {
    @TempDir Path dataFolder;

    @Test
    void absentDirectoryHasNoPhantomRecipes() throws Exception {
        assertEquals(List.of(), this.loader().load());
    }

    @Test
    void minecraftCultivationDirectoryIsLoaded() throws Exception {
        this.write("minecraft-cultivation/cultivation.yml",
                minimal("minecraft_cultivation:wheat"));

        List<DisplayRecipe> loaded = this.loader().load();

        assertEquals(List.of("minecraft_cultivation:wheat"),
                loaded.stream().map(DisplayRecipe::id).toList());
    }

    @Test
    void localizedItemNamesLoadAndResolveByPlayerLanguage() throws Exception {
        this.write("cultivation.yml", """
                recipes:
                  cultivation:wheat:
                    layout: {rows: 3}
                    workstation:
                      id: minecraft:cultivation
                      icon: minecraft:farmland
                    results:
                      - id: minecraft:wheat
                        slot: 16
                        name:
                          en_US: Wheat
                          zh_CN: 小麦
                          ja_JP: コムギ
                    ingredients:
                      - id: minecraft:wheat_seeds
                        name:
                          en_US: Wheat Seeds
                          zh_CN: 小麦种子
                        slot: 10
                """);

        DisplayRecipe recipe = this.loader().load().getFirst();
        RecipeItem result = recipe.results().getFirst().item();
        RecipeItem ingredient = recipe.ingredients().getFirst().choices().getFirst();

        assertEquals("小麦", result.localizedName("zh_CN"));
        assertEquals("コムギ", result.localizedName("ja-JP"));
        assertEquals("Wheat", result.localizedName("de_DE"));
        assertEquals("小麦种子", ingredient.localizedName("zh_CN"));
    }

    @Test
    void readsNestedFilesMultipleRecipesChoicesOutputsAndFileLocalTemplates() throws Exception {
        this.write("garden/stews.yaml", """
                layouts:
                  pot:
                    type: chest
                    rows: 3
                    title: '<gold>锅'
                recipes:
                  garden:stew_1:
                    layout: pot
                    workstation:
                      id: garden:pot
                      slot: 4
                      icon: minecraft:cauldron
                    results:
                      - id: garden:stew
                        amount: 3
                        slot: 16
                      - id: minecraft:bowl
                        slot: 15
                        note: '<gray>返回容器'
                    ingredients:
                      - choices:
                          - minecraft:carrot
                          - provider: craftengine
                            id: garden:cabbage
                        amount: 2
                        slot: 10
                      - item: minecraft:wooden_sword
                        role: tool
                        slot: 11
                    decorations:
                      - id: minecraft:clock
                        slot: 7
                    permission: garden.recipe
                    description: ['耗时 10 秒', '']
                  garden:stew_2:
                    layout:
                      template: pot
                      title: '<green>替代配方'
                    workstation:
                      id: garden:pot
                      icon: minecraft:cauldron
                    results:
                      - id: garden:stew
                    ingredients:
                      - id: minecraft:potato
                """);
        this.write("z.yml", minimal("garden:salad"));
        List<DisplayRecipe> loaded = this.loader().load();
        assertEquals(List.of("garden:stew_1", "garden:stew_2", "garden:salad"), loaded.stream().map(DisplayRecipe::id).toList());
        DisplayRecipe first = loaded.getFirst();
        assertEquals(2, first.results().size());
        assertEquals(3, first.results().getFirst().amount());
        assertEquals("garden:stew", first.results().getFirst().item().id());
        assertEquals(2, first.ingredients().getFirst().choices().size());
        assertEquals("craftengine", first.ingredients().getFirst().choices().getLast().provider());
        assertEquals(RecipeIngredient.Role.TOOL, first.ingredients().get(1).role());
        assertEquals(RecipeIngredient.Role.INFO, first.ingredients().get(2).role());
        assertEquals(22, first.layout().informationSlot());
        assertEquals("<green>替代配方", loaded.get(1).layout().title());
        assertEquals("garden.recipe", first.permission());
        assertEquals(List.of("耗时 10 秒", ""), first.description());
    }

    @Test
    void readsRecipesFromArbitrarilyDeepSubdirectories() throws Exception {
        this.write("111/222/333/abc.yml", minimal("deep:nested_recipe"));

        List<DisplayRecipe> loaded = this.loader().load();

        assertEquals(List.of("deep:nested_recipe"), loaded.stream().map(DisplayRecipe::id).toList());
    }

    @Test
    void readsTrMenuLikeTitleLayoutIconsDocumentsAndSharedWorkstationMetadata() throws Exception {
        this.write("farmersdelight/cooking_pot.yml", """
                Title: '<gold>农夫乐事 · 烹饪锅'
                Layout:
                  - '#########'
                  - '#A#####B#'
                  - '####T####'
                  - '####I####'
                Icons:
                  '#':
                    Display:
                      Material: gray stained glass pane
                  T:
                    Display:
                      Material: farmersdelight:cooking_pot
                  I:
                    Information: ['烹饪时间：10 秒']
                Workstation:
                  id: farmersdelight:cooking_pot
                  name: '<gold>烹饪锅'
                  icon:
                    Material: farmersdelight:cooking_pot
                Recipes:
                  farmersdelight:cooking_pot/soup:
                    Icons:
                      A:
                        Ingredient:
                          Amount: 2
                          Choices:
                            - Display:
                                Material: minecraft:carrot
                            - Display:
                                Material: minecraft:potato
                      B:
                        Display:
                          Material: farmersdelight:stew
                          Name: '<green>蔬菜炖汤'
                        Result:
                          Amount: 3
                          Note: '<gray>热食'
                """);

        DisplayRecipe recipe = this.loader().load().getFirst();

        assertEquals("farmersdelight:cooking_pot/soup", recipe.id());
        assertEquals("farmersdelight:cooking_pot", recipe.workstation().id());
        assertEquals(10, recipe.ingredients().getFirst().slot());
        assertEquals(2, recipe.ingredients().getFirst().choices().size());
        assertEquals(16, recipe.results().getFirst().slot());
        assertEquals(3, recipe.results().getFirst().amount());
        assertEquals(List.of("烹饪时间：10 秒"), recipe.description());
    }

    @Test
    void optionsPageSelectsAnExistingNativeVanillaWindow() throws Exception {
        this.write("native/workbench.yml", """
                Options:
                  Page: crafting
                Layout:
                  - '         '
                Workstation:
                  id: minecraft:crafting
                  name: '原版·工作台'
                  icon:
                    Material: minecraft:crafting_table
                Recipes:
                  sample:wooden_sword:
                    Layout:
                      - ' a       '
                    Icons:
                      a:
                        Usage: ingredient
                        Display:
                          Material: minecraft:oak_planks
                      r:
                        Usage: result
                        Display:
                          Material: minecraft:wooden_sword
                """);

        DisplayRecipe recipe = this.loader().load().getFirst();

        assertEquals("crafting", recipe.layout().page());
        assertTrue(recipe.layout().nativeUi());
    }

    @Test
    void recipeLevelResultsRemainAvailableWhenAResultIconIsMissing() throws Exception {
        this.write("farmersdelight/broken_icon.yml", """
                Title: 'Keg'
                Layout:
                  - ' a       '
                Workstation:
                  id: farmersdelight:addon/brewinandchewin_keg
                  icon: minecraft:cauldron
                Recipes:
                  farmersdelight:addon/group/keg/bloody_mary:
                    Icons:
                      a:
                        Ingredient:
                          Amount: 1
                        Display:
                          Material: minecraft:carrot
                    Results:
                      - Slot: 8
                        Amount: 2
                        Display:
                          Material: minecraft:potion
                """);

        DisplayRecipe recipe = this.loader().load().getFirst();

        assertEquals(1, recipe.results().size());
        assertEquals("minecraft:potion", recipe.results().getFirst().item().id());
        assertEquals(8, recipe.results().getFirst().slot());
        assertEquals(2, recipe.results().getFirst().amount());
    }

    @Test
    void contentIconsUsingReservedControlLettersAreEscapedInsteadOfBeingDropped() throws Exception {
        this.write("farmersdelight/reserved_result.yml", """
                Title: 'Keg'
                Layout:
                  - 'I   P    '
                Workstation:
                  id: farmersdelight:addon/brewinandchewin_keg
                  icon: minecraft:cauldron
                Recipes:
                  farmersdelight:addon/group/keg/bloody_mary:
                    Icons:
                      I:
                        Display:
                          Material: minecraft:carrot
                        Ingredient:
                          Amount: 1
                      P:
                        Display:
                          Material: brewinandchewin:bloody_mary
                        Result:
                          Amount: 1
                """);

        DisplayRecipe recipe = this.loader().load().getFirst();

        assertEquals(0, recipe.ingredients().getFirst().slot());
        assertEquals(4, recipe.results().getFirst().slot());
        assertEquals(-1, recipe.layout().previousSlot());
    }

    @Test
    void roleSchemaTreatsC_P_AndHashOnlyAsLayoutIdentifiers() throws Exception {
        this.write("farmersdelight/role_identifiers.yml", """
                Title: 'Identifiers'
                Layout:
                  - 'CP#      '
                Workstation:
                  id: farmersdelight:addon/example
                  icon: minecraft:cauldron
                Recipes:
                  farmersdelight:addon/example/recipe:
                    Icons:
                      C:
                        Usage: ingredient
                        Amount: 1
                        Display:
                          Material: minecraft:carrot
                      P:
                        Usage: result
                        Amount: 1
                        Display:
                          Material: farmersdelight:stew
                      '#':
                        Usage: decoration
                        Amount: 1
                        Choices:
                          - Display:
                              Material: minecraft:brown_mushroom
                          - Display:
                              Material: minecraft:red_mushroom
                """);

        DisplayRecipe recipe = this.loader().load().getFirst();

        assertEquals(0, recipe.ingredients().getFirst().slot());
        assertEquals(1, recipe.results().getFirst().slot());
        assertEquals(2, recipe.ingredients().getLast().slot());
        assertEquals(RecipeIngredient.Role.INFO, recipe.ingredients().getLast().role());
        assertEquals(2, recipe.ingredients().getLast().choices().size());
        assertEquals(-1, recipe.layout().previousSlot());
    }

    @Test
    void kaleidoscopeRecipesReceiveTheRequestedInputAndOutputRegions() throws Exception {
        this.write("kaleidoscope/layout.yml", """
                Title: 'KC'
                Layout:
                  - '         '
                  - '         '
                  - '         '
                  - '         '
                  - '         '
                  - '         '
                Workstation:
                  id: kc:pot
                  icon: minecraft:cauldron
                Recipes:
                  kc:flex/pot/example:
                    Icons:
                      A:
                        Usage: ingredient
                        Display:
                          Material: minecraft:carrot
                      B:
                        Usage: ingredient
                        Display:
                          Material: minecraft:potato
                      C:
                        Usage: result
                        Display:
                          Material: minecraft:baked_potato
                """);

        DisplayRecipe recipe = this.loader().load().getFirst();

        assertEquals(List.of(5, 6), recipe.ingredients().stream().map(RecipeIngredient::slot).toList());
        assertEquals(41, recipe.results().getFirst().slot());
        assertTrue(recipe.description().stream().anyMatch(line -> line.contains("品质越高")));
        assertTrue(recipe.description().stream().anyMatch(line -> line.contains("空手盛出")));
    }

    @Test
    void farmersDelightSpecialStationAlwaysUsesTheSourceRichSoilIcon() throws Exception {
        this.write("farmersdelight/special_icon.yml", """
                Title: 'Special'
                Layout:
                  - 'A       B'
                Workstation:
                  id: farmersdelight:special
                  name: 'Special Recipes'
                  icon: minecraft:apple
                Recipes:
                  farmersdelight:special/example:
                    Icons:
                      A:
                        Usage: decoration
                        Display:
                          Material: minecraft:paper
                      B:
                        Usage: result
                        Display:
                          Material: farmersdelight:tomato
                """);

        DisplayRecipe recipe = this.loader().load().getFirst();

        assertEquals("farmersdelight:rich_soil", recipe.workstation().icon().id());
        assertEquals("Special Recipes", recipe.workstation().name());
    }

    @Test
    void wildPlantDescriptionUsesSpecialDescriptionAndResultSlots() throws Exception {
        this.write("farmersdelight/wild_description.yml", """
                Title: 'Special'
                Layout:
                  - '    A    '
                  - '         '
                  - '         '
                Workstation:
                  id: farmersdelight:special
                  icon: farmersdelight:rich_soil
                Recipes:
                  farmersdelight:special/default/wild_cabbages:
                    Description:
                      - 说明条目；不代表可执行的合成配方
                    Icons:
                      A:
                        Usage: decoration
                        Display:
                          Material: minecraft:paper
                          Name: 野生卷心菜通常可以在海滩沿岸找到。
                      B:
                        Usage: result
                        Display:
                          Material: farmersdelight:wild_cabbages
                """);

        DisplayRecipe recipe = this.loader().load().getFirst();

        assertEquals(4, recipe.ingredients().getFirst().slot());
        assertEquals("minecraft:air", recipe.ingredients().getFirst().choices().getFirst().id());
        assertEquals(16, recipe.results().getFirst().slot());
    }

    @Test
    void oldKegExportsDoNotKeepTheUnsupportedFillButton() throws Exception {
        this.write("farmersdelight/old_keg.yml", """
                Title: 'Keg'
                Layout:
                  - 'A   B   C'
                  - '        D'
                  - '         '
                  - '         '
                  - '         '
                  - '         '
                Workstation:
                  id: farmersdelight:addon/brewinandchewin_keg
                  icon: brewinandchewin:keg
                Recipes:
                  farmersdelight:addon/group/keg/example:
                    Icons:
                      A:
                        Usage: ingredient
                        Display:
                          Material: minecraft:carrot
                      B:
                        Usage: decoration
                        Display:
                          Material: minecraft:hopper
                          Name: 填入材料
                      C:
                        Usage: decoration
                        Display:
                          Material: brewinandchewin:gui_bubble
                      D:
                        Usage: result
                        Display:
                          Material: brewinandchewin:beer
                """);

        DisplayRecipe recipe = this.loader().load().getFirst();

        assertTrue(recipe.ingredients().stream().noneMatch(ingredient ->
                ingredient.choices().stream().anyMatch(item -> item.id().equals("minecraft:hopper"))));
        assertTrue(recipe.ingredients().stream().anyMatch(ingredient ->
                ingredient.choices().stream().anyMatch(item -> item.id().equals("brewinandchewin:gui_bubble"))));
        assertTrue(recipe.ingredients().stream().anyMatch(ingredient -> ingredient.slot() == 23
                && ingredient.choices().stream().anyMatch(item -> item.id().equals("farmersdelight:animated"))));
    }

    @Test
    void skilletFlameLoreUsesConcreteCookingArguments() throws Exception {
        this.write("farmersdelight/skillet_lore.yml", """
                Title: 'Skillet'
                Layout:
                  - 'A       B'
                Workstation:
                  id: farmersdelight:skillet
                  icon: farmersdelight:skillet
                Recipes:
                  farmersdelight:skillet/example:
                    Description:
                      - '营火可烹饪：是；烹饪时间：432 tick；经验：0.35'
                    Icons:
                      A:
                        Usage: ingredient
                        Display:
                          Material: minecraft:beef
                      B:
                        Usage: decoration
                        Display:
                          Material: internal:cooking_info
                      C:
                        Usage: result
                        Display:
                          Material: minecraft:cooked_beef
                """);

        DisplayRecipe recipe = this.loader().load().getFirst();
        RecipeItem flame = recipe.ingredients().stream()
                .filter(ingredient -> ingredient.choices().stream()
                        .anyMatch(item -> item.id().equals("internal:cooking_info")))
                .findFirst().orElseThrow().choices().getFirst();

        assertEquals("<gold>配方信息", flame.name());
        assertEquals(List.of("<gray>时间：432 刻", "<gray>经验：0.35"), flame.lore());
    }

    @Test
    void exportedSpecialDescriptionSlotUsesAirMaterial() throws Exception {
        RecipeLayout layout = new RecipeLayout(3, "<white>Special", -1, -1, -1, -1, -1,
                List.of("    A    ", "         ", "         "), true);
        DisplayRecipe source = new DisplayRecipe("farmersdelight:special/default/wild_cabbages",
                new RecipeWorkstation("farmersdelight:special",
                        RecipeItem.of("farmersdelight:rich_soil"), "Special"),
                layout,
                List.of(new RecipeIngredient(List.of(new RecipeItem("minecraft", "minecraft:paper", null,
                        "Wild Cabbage", List.of("Beach"))), 1, 4, RecipeIngredient.Role.INFO)),
                List.of(new RecipeResult(RecipeItem.of("farmersdelight:wild_cabbages"), 1, 16, "Description")),
                List.of("说明条目；不代表可执行的合成配方"), null, null);

        RecipeFileExporter.export(this.dataFolder.resolve("recipes"), "farmersdelight", List.of(source));

        String yaml = Files.readString(this.dataFolder.resolve(
                "recipes/farmersdelight/special.yml"));
        assertTrue(yaml.contains("Material: minecraft:air"));
        assertFalse(yaml.contains("Material: minecraft:paper"));
        assertFalse(yaml.contains("Item-Model:"));
    }

    @Test
    void ignoresDefaultItemModelsWrittenByOlderExports() throws Exception {
        this.write("farmersdelight/item_model.yml", """
                Title: 'Test'
                Layout:
                  - 'A       B'
                Workstation:
                  id: farmersdelight:test
                  icon: minecraft:cauldron
                Recipes:
                  farmersdelight:test/example:
                    Icons:
                      A:
                        Usage: ingredient
                        Display:
                          Material: farmersdelight:tomato
                          Item-Model: minecraft:apple
                      B:
                        Usage: result
                        Display:
                          Material: farmersdelight:stew
                          Item-Model: minecraft:cooked_beef
                """);

        DisplayRecipe recipe = this.loader().load().getFirst();

        assertNull(recipe.ingredients().getFirst().choices().getFirst().icon());
        assertNull(recipe.results().getFirst().item().icon());
    }

    @Test
    void exportedJsonTitlesRoundTripThroughTitleData() throws Exception {
        String rawTitle = RecipeLayout.jsonTitle(net.kyori.adventure.text.Component.text("shifted title"));
        RecipeLayout layout = new RecipeLayout(1, rawTitle, -1, -1, -1, -1, -1, List.of(), true);
        DisplayRecipe source = new DisplayRecipe("farmersdelight:addon/example/recipe",
                new RecipeWorkstation("farmersdelight:addon/example",
                        RecipeItem.of("minecraft:cauldron"), "Example"),
                layout,
                List.of(new RecipeIngredient(List.of(RecipeItem.of("minecraft:carrot")), 1, 0,
                        RecipeIngredient.Role.INGREDIENT)),
                List.of(new RecipeResult(RecipeItem.of("minecraft:baked_potato"), 1, 1, null)),
                List.of(), null, null);

        RecipeFileExporter.export(this.dataFolder.resolve("recipes"), "farmersdelight", List.of(source));

        assertTrue(Files.readString(this.dataFolder.resolve("recipes/farmersdelight/addon_example.yml"))
                .contains("Title-Data:"));
        assertEquals(rawTitle, this.loader().load().getFirst().layout().title());
    }

    @Test
    void fakeFarmersDelightButtonExportsAsAirWithOnlyItsItemModel() throws Exception {
        RecipeLayout layout = RecipeLayout.nativeUi(null);
        DisplayRecipe source = new DisplayRecipe("farmersdelight:test/fake_button",
                new RecipeWorkstation("test:station", RecipeItem.of("minecraft:crafting_table"), "Station"),
                layout,
                List.of(new RecipeIngredient(List.of(
                        new RecipeItem("cer_fd_gui", "minecraft:air", null, null, null)),
                        1, 18, RecipeIngredient.Role.INFO)),
                List.of(new RecipeResult(RecipeItem.of("minecraft:stone"), 1, 26, null)),
                List.of(), null, null);

        RecipeFileExporter.export(this.dataFolder.resolve("recipes"), "farmersdelight", List.of(source));

        String yaml = Files.readString(this.dataFolder.resolve(
                "recipes/farmersdelight/station.yml"));
        assertTrue(yaml.contains("Material: minecraft:air"));
        assertTrue(yaml.contains("Item-Model: cer_fd_gui:fake_button"));
        assertTrue(yaml.contains("Hide-Tooltip: true"));
        assertFalse(yaml.contains("Name:"));
        assertFalse(yaml.contains("Lore:"));
    }

    @Test
    void kaleidoscopeInformationMarkersSurviveFileExportAndReload() throws Exception {
        RecipeLayout layout = RecipeLayout.patterned(List.of(
                "     AAAA",
                "     AAAA",
                "     AAAA",
                "         ",
                "     BBBB",
                "     BBBB"), null);
        RecipeItem marker = RecipeItem.of(KaleidoscopePresentation.INFORMATION_PROVIDER, "minecraft:paper");
        DisplayRecipe source = new DisplayRecipe("kc:flex/pot/test/information",
                new RecipeWorkstation("kc:pot", RecipeItem.of("kaleidoscopecookery:pot"), "森罗物语·炒锅"),
                layout,
                List.of(
                        new RecipeIngredient(List.of(RecipeItem.of("minecraft:beef")), 1, 5,
                                RecipeIngredient.Role.INGREDIENT),
                        new RecipeIngredient(List.of(marker), 1, KaleidoscopePresentation.INFORMATION_SLOT_LEFT,
                                RecipeIngredient.Role.INFO),
                        new RecipeIngredient(List.of(marker), 1, KaleidoscopePresentation.INFORMATION_SLOT_RIGHT,
                                RecipeIngredient.Role.INFO)),
                List.of(new RecipeResult(RecipeItem.of("test:dish"), 1, 41, null)),
                List.of("通用配方备注"), null, null);

        RecipeFileExporter.export(this.dataFolder.resolve("recipes"), "kaleidoscope", List.of(source));
        DisplayRecipe loaded = this.loader().load().getFirst();

        assertEquals(List.of(33, 34), loaded.ingredients().stream()
                .filter(ingredient -> KaleidoscopePresentation.isInformationIngredient(loaded.id(), ingredient))
                .map(RecipeIngredient::slot).toList());
        assertTrue(loaded.description().contains("通用配方备注"));
    }

    @Test
    void exportedWorkstationDocumentsRoundTripThroughTheLoader() throws Exception {
        RecipeLayout layout = new RecipeLayout(3, "<gold>锅", 22, 21, -1, 18, -1,
                List.of("#########", "#A#####B#", "R##IT####"), false);
        DisplayRecipe source = new DisplayRecipe("farmersdelight:cooking_pot/soup",
                new RecipeWorkstation("farmersdelight:cooking_pot",
                        RecipeItem.of("minecraft:cauldron"), "烹饪锅"),
                layout,
                List.of(new RecipeIngredient(List.of(RecipeItem.of("minecraft:carrot")), 2, 10,
                                RecipeIngredient.Role.INGREDIENT),
                        new RecipeIngredient(List.of(RecipeItem.of("minecraft:hopper")), 1, 11,
                                RecipeIngredient.Role.INFO),
                        new RecipeIngredient(List.of(RecipeItem.of("minecraft:stick")), 1, 12,
                                RecipeIngredient.Role.TOOL)),
                List.of(new RecipeResult(RecipeItem.of("farmersdelight:stew"), 3, 16, "<gray>热食")),
                List.of("烹饪时间：10 秒"), "recipes.view", null);
        DisplayRecipe second = new DisplayRecipe("farmersdelight:cooking_pot/porridge", source.workstation(), layout,
                List.of(new RecipeIngredient(List.of(RecipeItem.of("minecraft:wheat")), 1, 10,
                        RecipeIngredient.Role.INGREDIENT)),
                List.of(new RecipeResult(RecipeItem.of("farmersdelight:porridge"), 1, 16, null)),
                List.of(), null, null);

        RecipeFileExporter.Result result = RecipeFileExporter.export(
                this.dataFolder.resolve("recipes"), "farmersdelight", List.of(source, second));

        assertTrue(result.installed());
        assertEquals(1, result.files());
        assertEquals(2, result.recipes());
        Path exported = this.dataFolder.resolve("recipes/farmersdelight/cooking_pot.yml");
        assertTrue(Files.exists(exported));
        String exportedText = Files.readString(exported);
        assertFalse(exportedText.contains("gray_stained_glass_pane"));
        assertTrue(exportedText.contains("Usage: decoration"));
        assertTrue(exportedText.contains("Usage: ingredient"));
        assertTrue(exportedText.contains("Usage: result"));
        assertTrue(exportedText.contains("Usage: button"));
        assertTrue(exportedText.contains("Button: back"));
        assertFalse(exportedText.contains("Ingredient:"));
        assertFalse(exportedText.contains("Result:"));
        assertFalse(exportedText.contains("Item-Model:"));
        List<DisplayRecipe> documents = this.loader().load();
        assertEquals(2, documents.size());
        DisplayRecipe loaded = documents.stream().filter(recipe -> recipe.id().equals(source.id())).findFirst().orElseThrow();
        assertEquals(source.id(), loaded.id());
        assertEquals(10, loaded.ingredients().getFirst().slot());
        assertEquals(16, loaded.results().getFirst().slot());
        assertEquals(22, loaded.layout().workstationSlot());
        assertEquals(21, loaded.layout().informationSlot());
        assertEquals(18, loaded.layout().backSlot());
        assertTrue(loaded.ingredients().stream().anyMatch(ingredient ->
                ingredient.role() == RecipeIngredient.Role.INFO && ingredient.slot() == 11));
        assertTrue(loaded.ingredients().stream().anyMatch(ingredient ->
                ingredient.role() == RecipeIngredient.Role.TOOL && ingredient.slot() == 12));
        assertEquals("recipes.view", loaded.permission());
    }

    @Test
    void preservesUnspecifiedMetadataAndExplicitEmptyOverrides() throws Exception {
        this.write("metadata.yml", minimal("garden:stew").replace("- id: minecraft:carrot", """
                - choices:
                          - id: minecraft:carrot
                          - id: garden:cabbage
                            lore: ['source override']
                        name: ''
                        lore: []"""));
        DisplayRecipe recipe = this.loader().load().getFirst();
        assertNull(recipe.results().getFirst().item().name());
        assertNull(recipe.results().getFirst().item().lore());
        assertEquals("", recipe.ingredients().getFirst().choices().getFirst().name());
        assertEquals(List.of(), recipe.ingredients().getFirst().choices().getLast().lore());
    }

    @Test
    void ignoresExamplesAndDisabledDefinitionsEvenWithUnfinishedRecipeBodies() throws Exception {
        this.write("recipes-example.yml", "invalid: [ yaml");
        this.write("example.yml", "invalid: [ yaml");
        this.write("examples/broken.yaml", "invalid: [ yaml");
        this.write("off.yml", "enabled: false\nrecipes: not_finished\n");
        this.write("entries.yml", "recipes:\n  garden:later:\n    enabled: false\n");
        assertEquals(List.of(), this.loader().load());
    }

    @Test
    void rejectsDuplicateIdsAcrossFilesWithoutReturningAPartialSet() throws Exception {
        this.write("a.yml", minimal("garden:stew"));
        Path second = this.write("nested/b.yml", minimal("garden:stew"));
        IOException error = assertThrows(IOException.class, () -> this.loader().load());
        assertTrue(error.getMessage().contains(second.toString()));
        assertTrue(error.getMessage().contains("recipes.garden:stew"));
        assertTrue(error.getMessage().contains("duplicate recipe id"));
    }

    @Test
    void reportsFileAndPreciseQuantityPath() throws Exception {
        Path file = this.write("nested/bad.yml", minimal("garden:stew").replace("amount: 2", "amount: 0"));
        IOException error = assertThrows(IOException.class, () -> this.loader().load());
        assertTrue(error.getMessage().contains(file.toString()));
        assertTrue(error.getMessage().contains("recipes.garden:stew.ingredients[0].amount"));
    }

    @Test
    void rejectsFractionalCountsOutOfBoundsAndReservedSlots() throws Exception {
        Path file = this.write("bad.yml", minimal("garden:stew").replace("amount: 2", "amount: 1.5"));
        assertTrue(assertThrows(IOException.class, () -> this.loader().load()).getMessage().contains("expected an integer"));
        Files.writeString(file, minimal("garden:stew").replace("slot: 10", "slot: 27"));
        assertTrue(assertThrows(IOException.class, () -> this.loader().load()).getMessage().contains("ingredients[0].slot"));
        Files.writeString(file, minimal("garden:stew").replace("slot: 10", "slot: 21"));
        assertTrue(assertThrows(IOException.class, () -> this.loader().load()).getMessage().contains("overlaps"));
    }

    @Test
    void rejectsUnknownTemplatesTypesFieldsAndDuplicateYamlKeys() throws Exception {
        Path file = this.write("bad.yml", minimal("garden:stew").replace("layout: {rows: 3}", "layout: nonexistent"));
        assertTrue(assertThrows(IOException.class, () -> this.loader().load()).getMessage().contains("layout.template"));
        Files.writeString(file, minimal("garden:stew").replace("layout: {rows: 3}", "layout: {type: furnace}"));
        assertTrue(assertThrows(IOException.class, () -> this.loader().load()).getMessage().contains("layout.type"));
        Files.writeString(file, minimal("garden:stew").replace("amount: 2", "ammount: 2"));
        assertTrue(assertThrows(IOException.class, () -> this.loader().load()).getMessage().contains("ingredients[0].ammount"));
        Files.writeString(file, "recipes: {}\nrecipes: {}\n");
        assertTrue(assertThrows(IOException.class, () -> this.loader().load()).getMessage().contains("invalid YAML"));
    }

    @Test
    void reportsSyntaxErrorsAndRequiresAnOutput() throws Exception {
        Path file = this.write("bad.yml", "recipes: [broken");
        assertTrue(assertThrows(IOException.class, () -> this.loader().load()).getMessage().contains(file.toString()));
        Files.writeString(file, minimal("garden:stew").replace("results:\n      - id: garden:stew\n        slot: 16", "results: []"));
        assertTrue(assertThrows(IOException.class, () -> this.loader().load()).getMessage().contains("results: at least one result"));
    }

    @Test
    void bundledExampleIsDisabledAndValidWhenRenamed() throws Exception {
        try (var input = getClass().getResourceAsStream("/example/recipes/recipes-example.yml")) {
            assertNotNull(input);
            this.write("renamed.yml", new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
        assertEquals(List.of(), this.loader().load());
        Path file = this.dataFolder.resolve("recipes/renamed.yml");
        Files.writeString(file, Files.readString(file).replace("enabled: false", "enabled: true"));
        assertEquals(1, this.loader().load().size());
    }

    @Test
    void disabledIntegrationDirectoriesAreNotIndexed() throws Exception {
        this.write("farmersdelight/cooking.yml", minimal("farmersdelight:cooking"));
        this.write("kaleidoscope/cooking.yml", minimal("kaleidoscope:cooking"));
        this.write("customcrops/crops.yml", minimal("customcrops:pot"));

        List<DisplayRecipe> loaded = new CustomRecipeLoader(this.dataFolder,
                () -> true, source -> source.equals("customcrops")).load();

        assertEquals(List.of("customcrops:pot"),
                loaded.stream().map(DisplayRecipe::id).toList());
    }

    @Test
    void dynamicallyManagedThirdPartyDirectoriesAlsoRespectDisabledState() throws Exception {
        this.write("private-output/recipes.yml", minimal("private:recipe"));
        this.write("manual/recipes.yml", minimal("manual:recipe"));

        List<DisplayRecipe> loaded = new CustomRecipeLoader(this.dataFolder,
                Set.of("private-output"), source -> false).load();

        assertEquals(List.of("manual:recipe"),
                loaded.stream().map(DisplayRecipe::id).toList());
    }

    @Test
    void preparedDocumentsCanBeBuiltAfterAStandaloneYamlPass() throws Exception {
        this.write("first.yml", minimal("garden:first"));
        this.write("nested/second.yml", minimal("garden:second"));
        CustomRecipeLoader loader = this.loader();

        CustomRecipeLoader.PreparedDocuments prepared = loader.readDocuments();
        List<DisplayRecipe> loaded = loader.build(prepared);

        assertEquals(2, prepared.documents().size());
        assertEquals(List.of("garden:first", "garden:second"),
                loaded.stream().map(DisplayRecipe::id).toList());
    }

    @Test
    void incrementalReadReusesUnchangedDocumentsAndReplacesChangedOnes() throws Exception {
        this.write("first.yml", minimal("garden:first"));
        this.write("second.yml", minimal("garden:second"));
        CustomRecipeLoader loader = this.loader();
        CustomRecipeLoader.PreparedDocuments previous = loader.readDocuments();

        this.write("second.yml", minimal("garden:changed"));
        CustomRecipeLoader.PreparedDocuments current =
                loader.readDocuments(Set.of("second.yml"), previous);

        assertSame(previous.documents().getFirst(), current.documents().getFirst());
        assertNotSame(previous.documents().get(1), current.documents().get(1));
        assertEquals(List.of("garden:first", "garden:changed"),
                loader.build(current).stream().map(DisplayRecipe::id).toList());
    }

    private CustomRecipeLoader loader() { return new CustomRecipeLoader(this.dataFolder); }

    private Path write(String name, String contents) throws IOException {
        Path file = this.dataFolder.resolve("recipes").resolve(name);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, contents);
    }

    private static String minimal(String id) {
        return """
                recipes:
                  %s:
                    layout: {rows: 3}
                    workstation:
                      id: garden:pot
                      icon: minecraft:cauldron
                    results:
                      - id: garden:stew
                        slot: 16
                    ingredients:
                      - id: minecraft:carrot
                        amount: 2
                        slot: 10
                """.formatted(id);
    }
}
