package dev.che1s.craftenginerecipe.integration.kaleidoscope;

import dev.che1s.craftenginerecipe.api.DisplayRecipe;
import dev.che1s.craftenginerecipe.api.RecipeIngredient;
import dev.che1s.craftenginerecipe.api.RecipeItem;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class KaleidoscopeRecipeSourceTest {
    @Test
    void capturesEveryRuntimeFamilyInOneSnapshotAndKeepsTheirIdentitiesSeparate() {
        Registry registry = new Registry();
        registry.accurate.put(Appliance.STEAMER, List.of(accurate()));
        registry.flex.put(Appliance.POT, List.of(flex()));
        registry.chopping = List.of(chopping("SINGLE_EXTRA"));
        registry.teapot = List.of(teapot());

        List<DisplayRecipe> entries = source(registry).capture();

        assertEquals(1, registry.snapshots);
        assertFalse(registry.reading);
        assertEquals(4, entries.size());
        assertEquals(4, entries.stream().map(DisplayRecipe::id).distinct().count());
        assertTrue(entries.stream().allMatch(entry -> entry.id().startsWith("kc:")));
        assertTrue(entries.stream().allMatch(entry -> entry.permission().equals("kaleidoscopecookery.recipe.browse")));
        assertTrue(entries.stream().allMatch(entry -> entry.workstation().id().startsWith("kc:")));
        assertTrue(entries.stream().allMatch(entry -> entry.layout().pattern().equals(List.of(
                "     AAAA", "     AAAA", "     AAAA", "         ", "     BBBB", "     BBBB"))));
        assertEquals("KaleidoscopeCookeryPlugin", KaleidoscopeRecipeSource.PLUGIN_NAME);
    }

    @Test
    void mapsSixApplianceGuiTexturesWithoutInventingASteamerTexture() {
        assertEquals("cer_kc_gui:chopping_board_gui", KaleidoscopeRecipeSource.backgroundImage("kc:chopping_board"));
        assertEquals("cer_kc_gui:millstone_gui", KaleidoscopeRecipeSource.backgroundImage("kc:millstone"));
        assertEquals("cer_kc_gui:pot_gui", KaleidoscopeRecipeSource.backgroundImage("kc:pot"));
        assertEquals("cer_kc_gui:shawarma_spit_gui", KaleidoscopeRecipeSource.backgroundImage("kc:shawarma"));
        assertEquals("cer_kc_gui:stockpot_gui", KaleidoscopeRecipeSource.backgroundImage("kc:stockpot"));
        assertEquals("cer_kc_gui:steamer_full_lid", KaleidoscopeRecipeSource.backgroundImage("kc:steamer"));
        assertEquals("cer_kc_gui:teapot_gui", KaleidoscopeRecipeSource.backgroundImage("kc:teapot"));
        assertNull(KaleidoscopeRecipeSource.backgroundImage("kc:unknown"));
    }

    @Test
    void classifiesOnlyItemSpecificNotesForItemLore() {
        assertEquals("flex.container",
                KaleidoscopeRecipeSource.itemNoteKey("kc:flex/pot/test/recipe", RecipeIngredient.Role.CONTAINER));
        assertEquals("flex.liquid_item",
                KaleidoscopeRecipeSource.itemNoteKey("kc:flex/stockpot/test/recipe", RecipeIngredient.Role.INFO));
        assertEquals("chopping.knife_note",
                KaleidoscopeRecipeSource.itemNoteKey("kc:chopping/test/recipe", RecipeIngredient.Role.TOOL));
        assertEquals("teapot.liquid",
                KaleidoscopeRecipeSource.itemNoteKey("kc:teapot/test/recipe", RecipeIngredient.Role.INFO));
        assertNull(KaleidoscopeRecipeSource.itemNoteKey(
                "kc:accurate/pot/test/recipe", RecipeIngredient.Role.INGREDIENT));
    }

    @Test
    void keepsAllWeightedAccurateResultsCountsLoreAndInheritedProcessing() {
        Registry registry = new Registry();
        registry.accurate.put(Appliance.STEAMER, List.of(accurate()));

        DisplayRecipe entry = source(registry).capture().getFirst();

        assertEquals(2, entry.results().size());
        assertEquals(3, entry.results().getFirst().amount());
        assertEquals("craftengine", entry.results().getFirst().item().provider());
        assertEquals("kaleidoscopecookery:dish", entry.results().getFirst().item().id());
        assertEquals(List.of("<gold>熟食"), entry.results().getFirst().item().lore());
        assertTrue(entry.results().getFirst().note().contains("80"));
        assertTrue(entry.results().getLast().note().contains("20"));
        List<RecipeIngredient> infoPanels = entry.ingredients().stream()
                .filter(ingredient -> KaleidoscopeRecipeSource.isInformationIngredient(entry.id(), ingredient))
                .toList();
        assertEquals(List.of(33, 34), infoPanels.stream().map(RecipeIngredient::slot).toList());
        assertEquals(List.of("cer", "cer"),
                infoPanels.stream().map(panel -> panel.choices().getFirst().provider()).toList());
        assertTrue(entry.description().stream().anyMatch(text -> text.contains("不是百分比")));
        assertTrue(entry.description().stream().anyMatch(text -> text.contains("继承厨具默认值")));
        assertEquals("minecraft", entry.ingredients().getFirst().choices().getFirst().provider());
        assertEquals(5, entry.ingredients().getFirst().slot());
        assertEquals(List.of(41, 42), entry.results().stream().map(result -> result.slot()).toList());
    }

    @Test
    void showsFlexRatiosContainersAndLiquidConditionsWithoutClaimingFixedYield() {
        Registry registry = new Registry();
        registry.flex.put(Appliance.POT, List.of(flex()));

        DisplayRecipe entry = source(registry).capture().getFirst();

        assertEquals(2, entry.ingredients().stream().filter(i -> i.role() == RecipeIngredient.Role.INGREDIENT).count());
        assertEquals(2, entry.ingredients().getFirst().amount());
        assertTrue(entry.ingredients().stream().anyMatch(i -> i.role() == RecipeIngredient.Role.CONTAINER
                && i.choices().getFirst().id().equals("minecraft:bowl")));
        assertTrue(entry.ingredients().stream().anyMatch(i -> i.role() == RecipeIngredient.Role.INFO
                && i.choices().getFirst().id().equals("minecraft:water_bucket")));
        assertEquals(1, entry.results().getFirst().amount());
        assertTrue(entry.results().getFirst().note().contains("实际份数"));
        assertTrue(entry.description().stream().anyMatch(text -> text.contains("理想配比")));
        assertTrue(entry.description().stream().anyMatch(text -> text.contains("等效食材")));
        assertTrue(entry.description().stream().anyMatch(text -> text.contains("动态修改")));
        assertTrue(entry.description().stream().anyMatch(text -> text.contains("理想配比")));
        assertTrue(entry.description().stream().anyMatch(text -> text.contains("翻炒次数")));
        assertTrue(entry.description().stream().anyMatch(text -> text.contains("品质越高")));
        assertFalse(entry.description().stream().anyMatch(text -> text.contains("右键锅盛出")));
        assertEquals(List.of(5, 6, 7, 8, 33, 34),
                entry.ingredients().stream().map(RecipeIngredient::slot).toList());
        assertEquals(41, entry.results().getFirst().slot());
    }

    @Test
    void preservesChoppingExtrasAndResolvesCyclicKnifeTagsAndRuntimeKnives() {
        Registry registry = new Registry();
        registry.chopping = List.of(chopping("SINGLE_EXTRA"));
        Tags tags = new Tags();
        tags.members.put(k("kaleidoscopecookery:kitchen_knife"), Set.of("#test:nested", "craftengine:test:knife", "craftengine:minecraft:custom_knife"));
        tags.members.put(k("test:nested"), Set.of("#kaleidoscopecookery:kitchen_knife", "minecraft:iron_sword"));
        KaleidoscopeRecipeSource source = new KaleidoscopeRecipeSource(registry, List.of(Appliance.values()),
                null, tags, new Knives(), () -> true);

        DisplayRecipe entry = source.capture().getFirst();

        assertEquals(3, entry.results().size());
        assertEquals(2, entry.results().getFirst().amount());
        assertTrue(entry.results().getLast().note().contains("副产物"));
        assertTrue(entry.results().getLast().note().contains("25%"));
        List<RecipeItem> choices = entry.ingredients().stream().filter(i -> i.role() == RecipeIngredient.Role.TOOL)
                .findFirst().orElseThrow().choices();
        assertEquals(Set.of("test:knife", "test:runtime_knife", "minecraft:iron_sword", "minecraft:custom_knife"),
                choices.stream().map(RecipeItem::id).collect(java.util.stream.Collectors.toSet()));
        assertTrue(choices.stream().filter(i -> i.id().equals("test:knife")).allMatch(i -> i.provider().equals("craftengine")));
        assertTrue(choices.stream().filter(i -> i.id().equals("minecraft:custom_knife")).allMatch(i -> i.provider().equals("craftengine")));
        assertTrue(entry.description().stream().anyMatch(text -> text.contains("切配刀数：4")));
        assertFalse(entry.description().stream().anyMatch(text -> text.contains("需要菜刀")));
    }

    @Test
    void describesIndependentRollsAndFallbackInsteadOfInventingFinalDropRates() {
        Registry registry = new Registry();
        registry.chopping = List.of(chopping("MULTI_RANDOM"));

        DisplayRecipe entry = source(registry).capture().getFirst();

        assertEquals(2, entry.results().size(), "extras only apply to SINGLE_EXTRA");
        assertTrue(entry.results().getFirst().note().contains("独立判定"));
        assertTrue(entry.description().stream().anyMatch(text -> text.contains("保底") && text.contains("不是") && text.contains("最终掉率")));
    }

    @Test
    void retainsTeapotLiquidAmountsTimeAndSourceStyle() {
        Registry registry = new Registry();
        registry.teapot = List.of(teapot());
        KaleidoscopeRecipeSource source = new KaleidoscopeRecipeSource(registry, List.of(Appliance.values()),
                new Style(), null, null, () -> true);

        DisplayRecipe entry = source.capture().getFirst();

        assertEquals("森罗物语·定制茶壶", entry.workstation().name());
        assertEquals("test:teapot_icon", entry.workstation().icon().id());
        assertEquals(2, entry.ingredients().getFirst().amount());
        assertEquals(4, entry.results().getFirst().amount());
        assertTrue(entry.ingredients().stream().anyMatch(ingredient ->
                ingredient.role() == RecipeIngredient.Role.INFO
                        && ingredient.choices().getFirst().id().equals("minecraft:water_bucket")));
        assertEquals(2, entry.ingredients().stream()
                .filter(ingredient -> KaleidoscopeRecipeSource.isInformationIngredient(entry.id(), ingredient))
                .count());
        assertTrue(entry.description().stream().anyMatch(text -> text.contains("200 tick")));
        assertFalse(entry.description().stream().anyMatch(text -> text.contains("所需液体")));
    }

    @Test
    void exposesGenerationAndRejectsCapturingAnUnreadyRegistry() {
        Registry registry = new Registry();
        KaleidoscopeRecipeSource source = source(registry);
        assertTrue(source.ready());
        assertEquals(7L, source.revision());
        registry.generation = 8;
        assertEquals(8L, source.revision());
        registry.loading = true;
        assertFalse(source.ready());
        assertThrows(IllegalStateException.class, source::capture);
        assertEquals(0, registry.snapshots);
        registry.loading = false;
        assertFalse(new KaleidoscopeRecipeSource(registry, List.of(Appliance.values()), null, null, null, () -> false).ready());
    }

    @Test
    void propagatesSourceFailureInsteadOfPublishingAnIncompleteRecipeSet() {
        Registry registry = new Registry();
        registry.failure = new IllegalArgumentException("registry failure");

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> source(registry).capture());

        assertSame(registry.failure, thrown);
        assertFalse(registry.reading);
    }

    private static KaleidoscopeRecipeSource source(Registry registry) {
        return new KaleidoscopeRecipeSource(registry, List.of(Appliance.values()), null, null, null, () -> true);
    }

    private static Accurate accurate() {
        return new Accurate(k("test:shared"), k("minecraft:beef"),
                List.of(new Weighted(k("kaleidoscopecookery:dish"), 80), new Weighted(k("minecraft:charcoal"), 20)),
                0, 3, List.of("<gold>熟食"), 0);
    }

    private static Flex flex() {
        Map<K, Integer> perfect = new LinkedHashMap<>();
        perfect.put(k("minecraft:beef"), 2);
        perfect.put(k("test:vegetable"), 1);
        return new Flex(k("test:shared"), k("test:stew"), perfect, List.of(k("minecraft:water")),
                k("minecraft:bowl"), true, true, 0, 8);
    }

    private static Chopping chopping(String mode) {
        return new Chopping(k("test:shared"), k("minecraft:beef"), 4, mode,
                List.of(new Chopped(k("test:slice"), 2, 70), new Chopped(k("test:slice"), 1, 30)),
                List.of(new Chopped(k("test:extra"), 1, 25)));
    }

    private static Teapot teapot() {
        return new Teapot(k("test:shared"), k("minecraft:water"), k("test:tea_leaf"), 2, k("test:tea"), 4, 200);
    }

    private static K k(String id) { return new K(id); }

    public enum Appliance { POT, STOCKPOT, STEAMER, SHAWARMA, MILLSTONE, CHOPPING_BOARD, TEAPOT }
    public record K(String value) { public String asString() { return value; } }
    public record Weighted(K key, int weight) {}
    public record Chopped(K key, int count, int weight) {}
    public record Accurate(K id, K input, List<Weighted> results, int rotations, int resultCount, List<String> lore, int cookingTime) {}
    public record Flex(K id, K result, Map<K, Integer> perfect, List<K> liquids, K carrier,
                       boolean useEquivalentFoods, boolean useSeasonings, int cookingTime, int stirFryCount) {}
    public record Chopping(K id, K input, int stage, String mode, List<Chopped> results, List<Chopped> extras) {}
    public record Teapot(K id, K fluid, K input, int ingredientCount, K result, int resultCount, int time) {}

    public static class Registry {
        final Map<Appliance, List<?>> accurate = new EnumMap<>(Appliance.class);
        final Map<Appliance, List<?>> flex = new EnumMap<>(Appliance.class);
        List<?> chopping = List.of();
        List<?> teapot = List.of();
        boolean reading;
        boolean loading;
        int snapshots;
        long generation = 7;
        RuntimeException failure;

        public long generation() { return generation; }
        public boolean isConfigurationLoading() { return loading; }
        public <T> T readSnapshot(Supplier<T> action) {
            assertFalse(reading);
            reading = true;
            snapshots++;
            try { return action.get(); }
            finally { reading = false; }
        }
        public List<?> accurateRecipes(Appliance appliance) {
            assertTrue(reading, "all registry tables must be read within readSnapshot");
            if (failure != null) throw failure;
            return accurate.getOrDefault(appliance, List.of());
        }
        public List<?> flexRecipes(Appliance appliance) {
            assertTrue(reading);
            return flex.getOrDefault(appliance, List.of());
        }
        public List<?> choppingRecipes() { assertTrue(reading); return chopping; }
        public List<?> teapotRecipes() { assertTrue(reading); return teapot; }
        public List<?> menuAccurateRecipes(Appliance appliance) { fail("editor-only recipes must not be imported"); return List.of(); }
    }

    public static class Tags {
        final Map<K, Set<String>> members = new LinkedHashMap<>();
        public Set<K> keys() { return members.keySet(); }
        public Set<String> members(K tag) { return members.getOrDefault(tag, Set.of()); }
    }

    public static class Knives {
        public Set<String> registeredIds() { return Set.of("craftengine:test:runtime_knife"); }
    }

    public static class Style {
        public K applianceIcon(Appliance appliance) { return k("test:teapot_icon"); }
        public String applianceName(Appliance appliance) { return "定制茶壶"; }
        public K liquidIcon(K liquid) { return k("minecraft:water_bucket"); }
    }
}
