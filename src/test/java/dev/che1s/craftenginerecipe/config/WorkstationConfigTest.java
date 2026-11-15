package dev.che1s.craftenginerecipe.config;

import dev.che1s.craftenginerecipe.api.RecipeItem;
import dev.che1s.craftenginerecipe.api.RecipeWorkstation;
import dev.che1s.craftenginerecipe.category.CategoryNamespaces;
import dev.che1s.craftenginerecipe.integration.extension.ExtensionManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkstationConfigTest {
    @TempDir Path directory;

    @Test
    void configuredStationsComeFirstAndUnlistedStationsKeepTheirOrder() throws Exception {
        Files.writeString(this.directory.resolve("workstations.yml"), """
                order:
                  - test:b
                  - test:missing
                  - test:a
                  - test:b
                """);
        WorkstationConfig config = new WorkstationConfig(this.directory, Logger.getAnonymousLogger());
        config.load();

        List<RecipeWorkstation> sorted = config.sort(List.of(
                station("test:a"), station("test:c"), station("test:b")));

        assertEquals(List.of("test:b", "test:a", "test:c"),
                sorted.stream().map(RecipeWorkstation::id).toList());
    }

    @Test
    void missingConfigurationKeepsAllStationsInSourceOrder() {
        WorkstationConfig config = new WorkstationConfig(this.directory, Logger.getAnonymousLogger());
        config.load();

        List<RecipeWorkstation> stations = List.of(station("test:b"), station("test:a"));

        assertEquals(stations, config.sort(stations));
    }

    @Test
    void newStationsAreAppendedWithoutMovingConfiguredButtons() throws Exception {
        Path file = this.directory.resolve("navigation/workstations.yml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                entries:
                - id: test:b
                - display:
                    material: minecraft:compass
                    name: 'Navigation'
                  visibility: admin
                  permission: ''
                  hide-inaccessible: true
                  actions:
                    all:
                      - 'command: cer manage'
                - id: test:a
                """);
        WorkstationConfig config = new WorkstationConfig(this.directory, Logger.getAnonymousLogger());
        config.load();

        assertEquals(1, config.sync(List.of("test:b", "test:c", "test:a")));
        assertEquals(List.of("test:b", "test:a", "test:c"),
                config.sort(List.of(station("test:a"), station("test:c"), station("test:b")))
                        .stream().map(RecipeWorkstation::id).toList());
        assertTrue(config.entries().get(1) instanceof WorkstationConfig.ButtonEntry);
        String saved = Files.readString(file);
        assertTrue(saved.contains("id: test:c"));
        assertTrue(saved.contains("hide-inaccessible: true"));
        assertTrue(saved.contains("command: cer manage"));
    }

    @Test
    void administratorsCanReorderEntriesAndEditButtonsWithoutLosingOtherEntries() throws Exception {
        Path file = this.directory.resolve("navigation/workstations.yml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                entries:
                - id: test:a
                - display:
                    material: minecraft:compass
                    name: Navigation
                  visibility: admin
                  actions:
                    all:
                      - 'command: cer manage'
                - id: test:b
                """);
        WorkstationConfig config = new WorkstationConfig(this.directory, Logger.getAnonymousLogger());
        config.load();

        assertTrue(config.moveEntry(2, -2));
        CategoryNamespaces.ButtonEntry updated = new CategoryNamespaces.ButtonEntry(
                new CategoryNamespaces.ButtonDisplay("minecraft:book", "Help",
                        List.of("Open help")),
                java.util.Map.of("all", List.of("command: cer help")),
                CategoryNamespaces.Visibility.PLAYER, "", false);
        assertTrue(config.updateButton(2, updated));

        assertTrue(config.entries().get(0) instanceof WorkstationConfig.StationEntry first
                && first.id().equals("test:b"));
        assertTrue(config.entries().get(2) instanceof WorkstationConfig.ButtonEntry button
                && button.button().display().name().yamlValue().equals("Help"));
        String saved = Files.readString(file);
        assertTrue(saved.contains("material: minecraft:book"));
        assertTrue(saved.contains("command: cer help"));
        assertTrue(Files.isRegularFile(file.resolveSibling("workstations.yml.bak")));
    }

    @Test
    void extensionPresetsAssignOwnershipAndInactiveJarsAreRemoved() throws Exception {
        Path file = this.directory.resolve("navigation/workstations.yml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                entries:
                - id: local:station
                - id: removed:station
                  cer-managed-by: cer-ext-old.jar
                """);
        WorkstationConfig config = new WorkstationConfig(this.directory, Logger.getAnonymousLogger());
        config.load();

        WorkstationConfig.Reconciliation reconciliation = config.reconcile(
                List.of("farmersdelight:cooking_pot", "local:station", "removed:station"),
                List.of(new ExtensionManager.ManagedWorkstationPreset(
                        "farmersdelight:cooking_pot", "cer-ext-farmersdelight.jar")),
                Set.of("cer-ext-farmersdelight.jar"),
                id -> id.startsWith("farmersdelight:")
                        ? "cer-ext-farmersdelight.jar" : "");

        assertEquals(1, reconciliation.added());
        assertEquals(1, reconciliation.removed());
        assertTrue(config.entries().stream().anyMatch(entry ->
                entry instanceof WorkstationConfig.StationEntry station
                        && station.id().equals("farmersdelight:cooking_pot")
                        && station.managedBy().equals("cer-ext-farmersdelight.jar")));
        assertTrue(config.entries().stream().noneMatch(entry ->
                entry instanceof WorkstationConfig.StationEntry station
                        && station.id().equals("removed:station")));
        String saved = Files.readString(file);
        assertTrue(saved.contains("cer-managed-by: cer-ext-farmersdelight.jar"));
        assertTrue(saved.contains("id: local:station"));
    }

    private static RecipeWorkstation station(String id) {
        return new RecipeWorkstation(id, RecipeItem.of("minecraft:paper"), id);
    }
}
