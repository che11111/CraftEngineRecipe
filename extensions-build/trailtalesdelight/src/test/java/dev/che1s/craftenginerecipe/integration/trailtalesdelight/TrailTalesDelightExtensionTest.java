package dev.che1s.craftenginerecipe.integration.trailtalesdelight;

import dev.che1s.craftenginerecipe.i18n.LanguageManager;
import dev.che1s.craftenginerecipe.api.RecipeLayout;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrailTalesDelightExtensionTest {
    @Test
    void providerUsesDedicatedIdentityAndSourcePlugin() {
        TrailTalesDelightRecipeSourceProvider provider = new TrailTalesDelightRecipeSourceProvider();

        assertEquals("trailandtalesdelight", provider.id());
        assertEquals("TrailTalesDelight", provider.requiredPluginName());
        assertEquals("dev.che1s.trailtalesdelight.TrailTalesDelightPlugin",
                provider.requiredPluginMainClass());
        assertEquals("enabled", provider.defaultMode());
    }

    @Test
    void allSupportedLanguagesProvideTheDedicatedStationTitle() throws Exception {
        for (String locale : LanguageManager.supportedLocales()) {
            String resource = "lang/" + locale + ".yml";
            try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
                assertNotNull(input, resource);
                YamlConfiguration yaml = YamlConfiguration.loadConfiguration(
                        new InputStreamReader(input, StandardCharsets.UTF_8));
                String title = yaml.getString("station.title");
                assertNotNull(title, resource);
                assertTrue(title.startsWith("Trail & Tales Delight·"), title);
            }
        }
    }

    @Test
    void viewerInfoUsesThePlayerAwareDetailMethod() {
        RecordingView view = new RecordingView();
        Player player = (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(), new Class<?>[]{Player.class}, (proxy, method, args) -> null);

        List<Component> lines = TrailTalesDelightRecipeSource.detailInfoLines(view, player);

        assertEquals(List.of(Component.text("烹饪时间：2.0 秒")), lines);
        assertEquals(1, view.detailCalls);
        assertEquals(0, view.zeroArgumentCalls);
    }

    @Test
    void reflectionCanReadPrivateViewableRecipeImplementations() {
        assertEquals("ancient_coffee",
                TrailTalesDelightRecipeSource.call(new PrivateView(), "id"));
    }

    @Test
    void sourceRecipeIdsAreNamespacedCerIdentifiers() {
        assertEquals("trailandtalesdelight:trailandtales_delight/ancient_coffee",
                TrailTalesDelightRecipeSource.displayId("trailandtales_delight:ancient_coffee"));
    }

    @Test
    void nativeRecipeLayoutUsesSourceNavigationWithoutCerBackControl() {
        RecipeLayout layout = TrailTalesDelightRecipeSource.nativeUiLayout("title");

        assertTrue(layout.nativeUi());
        assertEquals(-1, layout.backSlot());
        assertEquals(-1, layout.previousSlot());
        assertEquals(-1, layout.nextSlot());
    }

    @Test
    void bundlesCategoryAndWorkstationNavigationPresets() throws Exception {
        try (InputStream categories = getClass().getClassLoader()
                .getResourceAsStream("category-presets.yml");
             InputStream workstations = getClass().getClassLoader()
                     .getResourceAsStream("workstation-presets.yml")) {
            assertNotNull(categories);
            assertNotNull(workstations);
            YamlConfiguration categoryYaml = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(categories, StandardCharsets.UTF_8));
            YamlConfiguration workstationYaml = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(workstations, StandardCharsets.UTF_8));

            assertEquals("trailandtales_delight",
                    categoryYaml.getMapList("presets").getFirst().get("id"));
            assertEquals("player",
                    categoryYaml.getMapList("presets").getFirst().get("visibility"));
            assertEquals("trailandtalesdelight:pottery_cooking_pot",
                    workstationYaml.getMapList("navigation").getFirst().get("id"));
        }
    }

    interface PublicView {
        String id();
    }

    private static final class PrivateView implements PublicView {
        @Override
        public String id() {
            return "ancient_coffee";
        }
    }

    static final class RecordingView {
        int detailCalls;
        int zeroArgumentCalls;

        public List<Component> detailInfoLines(Player player) {
            detailCalls++;
            return List.of(Component.text("烹饪时间：2.0 秒"));
        }

        public List<Component> detailInfoLines() {
            zeroArgumentCalls++;
            throw new AssertionError("player-less overload must not be selected");
        }
    }
}
