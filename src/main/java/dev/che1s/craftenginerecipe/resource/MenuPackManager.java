package dev.che1s.craftenginerecipe.resource;

import dev.che1s.craftenginerecipe.CraftEngineRecipePlugin;
import org.bukkit.Bukkit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 发现并安装 config.yml 中启用的菜单包。 */
public final class MenuPackManager {
    private static final String DEFAULT_PACK = ResourcePackInstaller.MENU_CUTE_DIRECTORY;
    private final CraftEngineRecipePlugin plugin;

    public MenuPackManager(CraftEngineRecipePlugin plugin) {
        this.plugin = plugin;
    }

    public List<String> available() {
        Path root = localRoot();
        if (!Files.isDirectory(root)) return List.of();
        try (var paths = Files.list(root)) {
            return paths.filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.matches("cer_menu_[a-z0-9_-]+"))
                    .filter(name -> this.hasMenuDefinitions(root.resolve(name).resolve("menu")))
                    .sorted().toList();
        } catch (IOException error) {
            plugin.getLogger().warning("Cannot list menu packs: " + error.getMessage());
            return List.of();
        }
    }

    private boolean hasMenuDefinitions(Path directory) {
        if (!Files.isDirectory(directory)) return false;
        try (var files = Files.list(directory)) {
            return files.anyMatch(path -> {
                String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                return Files.isRegularFile(path) && (name.endsWith(".yml") || name.endsWith(".yaml"));
            });
        } catch (IOException error) {
            return false;
        }
    }

    public String defaultPack() {
        List<String> packs = available();
        String configured = plugin.getConfig().getString("menu-packs.default", DEFAULT_PACK);
        if (configured != null && packs.contains(configured.toLowerCase(Locale.ROOT))) {
            return configured.toLowerCase(Locale.ROOT);
        }
        if (packs.contains(DEFAULT_PACK)) return DEFAULT_PACK;
        return packs.isEmpty() ? DEFAULT_PACK : packs.getFirst();
    }

    /** 默认菜单包始终启用，并在配置列表为空时排在首位。 */
    public List<String> enabled() {
        List<String> packs = available();
        Set<String> available = Set.copyOf(packs);
        LinkedHashSet<String> selected = new LinkedHashSet<>();
        for (String configured : plugin.getConfig().getStringList("menu-packs.enabled")) {
            if (configured == null) continue;
            String id = configured.trim().toLowerCase(Locale.ROOT);
            if (available.contains(id)) selected.add(id);
        }
        String defaultPack = defaultPack();
        if (available.isEmpty() || available.contains(defaultPack)) selected.add(defaultPack);
        if (selected.isEmpty() && !packs.isEmpty()) selected.add(packs.getFirst());
        return List.copyOf(selected);
    }

    /** 使用 FD、KC 及全部已启用菜单包重建受管子包集合。 */
    public void installEnabled() throws IOException {
        LinkedHashSet<String> selected = new LinkedHashSet<>();
        selected.addAll(ResourcePackInstaller.REQUIRED_GUI_PACKS);
        selected.addAll(enabled());
        for (String id : enabled()) {
            Path local = localRoot().resolve(id);
            ResourcePackInstaller.replenishBundled(plugin.jarPath(), local, id);
        }
        for (String id : ResourcePackInstaller.REQUIRED_GUI_PACKS) {
            Path local = localRoot().resolve(id);
            ResourcePackInstaller.replenishBundled(plugin.jarPath(), local, id);
        }
        for (String id : enabled()) {
            ResourcePackInstaller.removeLegacyMenuLayouts(localRoot().resolve(id));
        }
        ResourcePackInstaller.installBundle(localRoot(), targetResourcesRoot(), selected);
    }

    public boolean setDefault(String requested) {
        String id = normalize(requested);
        if (id == null || !available().contains(id)) return false;
        LinkedHashSet<String> enabled = new LinkedHashSet<>(enabled());
        enabled.add(id);
        plugin.getConfig().set("menu-packs.default", id);
        return applyEnabled(enabled);
    }

    public boolean enableAll() {
        return applyEnabled(new LinkedHashSet<>(available()));
    }

    public boolean enable(String requested) {
        String id = normalize(requested);
        if (id == null || !available().contains(id)) return false;
        LinkedHashSet<String> selected = new LinkedHashSet<>(enabled());
        selected.add(id);
        return applyEnabled(selected);
    }

    public boolean disable(String requested) {
        String id = normalize(requested);
        if (id == null || id.equals(defaultPack())) return false;
        LinkedHashSet<String> selected = new LinkedHashSet<>(enabled());
        if (!selected.remove(id)) return false;
        return applyEnabled(selected);
    }

    public boolean switchTo(String requested) {
        return setDefault(requested);
    }

    private boolean applyEnabled(Collection<String> requested) {
        Set<String> available = Set.copyOf(available());
        LinkedHashSet<String> selected = new LinkedHashSet<>();
        for (String raw : requested) {
            String id = normalize(raw);
            if (id != null && available.contains(id)) selected.add(id);
        }
        if (selected.isEmpty()) return false;
        plugin.getConfig().set("menu-packs.enabled", List.copyOf(selected));
        plugin.saveConfig();
        String reload = plugin.getConfig().getString("menu-packs.reload-command", "ce reload all");
        plugin.tasks().global(() -> {
            try {
                installEnabled();
            } catch (IOException error) {
                plugin.getLogger().warning("Cannot install selected menu packs: " + error.getMessage());
            }
            if (reload != null && !reload.isBlank()) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), reload);
            }
            plugin.reloadSelf(ok -> {});
        });
        return true;
    }

    private String normalize(String requested) {
        if (requested == null || requested.isBlank()) return null;
        String id = requested.trim().toLowerCase(Locale.ROOT);
        return id.startsWith("cer_menu_") ? id : "cer_menu_" + id;
    }

    private Path localRoot() {
        return plugin.getDataFolder().toPath().resolve(ResourcePackInstaller.RESOURCES_DIRECTORY);
    }

    private Path targetResourcesRoot() {
        return plugin.getDataFolder().toPath().resolveSibling("CraftEngine")
                .resolve(ResourcePackInstaller.RESOURCES_DIRECTORY);
    }
}
