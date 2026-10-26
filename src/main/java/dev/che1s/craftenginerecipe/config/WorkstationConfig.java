package dev.che1s.craftenginerecipe.config;

import dev.che1s.craftenginerecipe.api.RecipeWorkstation;
import dev.che1s.craftenginerecipe.category.CategoryNamespaces;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * 管理“按合成器浏览”的有序条目，包括合成器与自定义按钮。
 * 新发现的合成器会自动追加，已有顺序和按钮不会被覆盖。
 */
public final class WorkstationConfig {
    private static final String HEADER = """
            CraftEngineRecipe 合成器按钮配置 / Workstation button configuration
            合成器条目使用 id；自定义按钮使用 display + actions。
            Workstation entries use id; custom buttons use display + actions.
            按钮支持 visibility、permission、hide-inaccessible 与 actions。
            Buttons support visibility, permission, hide-inaccessible and actions.
            未列出的新合成器会自动追加到末尾，已有条目顺序不会被覆盖。
            Newly discovered workstations are appended automatically without replacing existing entries.
            """;

    private final Path targetFile;
    private final Path legacyFile;
    private Path file;
    private final Logger logger;
    private List<Entry> entries = List.of();

    public WorkstationConfig(Path dataFolder, Logger logger) {
        this.targetFile = dataFolder.resolve("navigation/workstations.yml");
        this.legacyFile = dataFolder.resolve("workstations.yml");
        this.file = this.resolveSource();
        this.logger = logger;
    }

    public void load() {
        this.file = this.resolveSource();
        if (!Files.isRegularFile(this.file)) {
            this.entries = List.of();
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            boolean legacySource = !this.file.equals(this.targetFile);
            yaml.load(this.file.toFile());
            List<Entry> parsed = new ArrayList<>();
            List<?> configured = yaml.getList("entries");
            boolean legacyOrder = configured == null;
            if (configured != null) {
                for (Object value : configured) {
                    if (!(value instanceof Map<?, ?> map)) continue;
                    if (map.get("display") instanceof Map<?, ?>) {
                        parsed.add(new ButtonEntry(CategoryNamespaces.parseButtonEntry(map)));
                        continue;
                    }
                    String id = text(map.get("id"));
                    if (!id.isBlank()) parsed.add(new StationEntry(id));
                }
            } else {
                // 兼容最初版本的 order: 列表。
                for (String id : yaml.getStringList("order")) {
                    if (id != null && !id.isBlank()) parsed.add(new StationEntry(id.trim()));
                }
            }
            this.entries = deduplicateStations(parsed);
            if (legacyOrder || legacySource) this.save();
        } catch (IOException | InvalidConfigurationException error) {
            this.logger.warning("Cannot load workstations.yml: " + error.getMessage());
        }
    }

    private Path resolveSource() {
        return Files.isRegularFile(this.targetFile) || !Files.isRegularFile(this.legacyFile)
                ? this.targetFile : this.legacyFile;
    }

    /** 将已发现但尚未写入配置的合成器追加到末尾。 */
    public synchronized int sync(Collection<String> discovered) {
        LinkedHashSet<String> stations = new LinkedHashSet<>();
        for (Entry entry : this.entries) {
            if (entry instanceof StationEntry station) stations.add(station.id());
        }
        List<Entry> next = new ArrayList<>(this.entries);
        int added = 0;
        for (String id : discovered) {
            if (id == null || id.isBlank() || !stations.add(id.trim())) continue;
            next.add(new StationEntry(id.trim()));
            added++;
        }
        if (added > 0) {
            this.entries = List.copyOf(next);
            this.save();
        }
        return added;
    }

    public List<RecipeWorkstation> sort(Collection<RecipeWorkstation> stations) {
        LinkedHashMap<String, RecipeWorkstation> remaining = new LinkedHashMap<>();
        for (RecipeWorkstation station : stations) {
            remaining.putIfAbsent(station.id(), station);
        }
        List<RecipeWorkstation> result = new ArrayList<>(remaining.size());
        for (Entry entry : this.entries) {
            if (!(entry instanceof StationEntry station)) continue;
            RecipeWorkstation definition = remaining.remove(station.id());
            if (definition != null) result.add(definition);
        }
        result.addAll(remaining.values());
        return List.copyOf(result);
    }

    public List<Entry> entries() {
        return this.entries;
    }

    private void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().header(HEADER);
        List<Map<String, Object>> list = new ArrayList<>();
        for (Entry entry : this.entries) {
            if (entry instanceof StationEntry station) {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("id", station.id());
                list.add(map);
            } else if (entry instanceof ButtonEntry button) {
                list.add(CategoryNamespaces.buttonMap(button.button()));
            }
        }
        yaml.set("entries", list);
        try {
            Files.createDirectories(this.targetFile.getParent());
            yaml.save(this.targetFile.toFile());
        } catch (IOException error) {
            this.logger.warning("Cannot save workstations.yml: " + error.getMessage());
        }
    }

    private static List<Entry> deduplicateStations(List<Entry> input) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<Entry> result = new ArrayList<>(input.size());
        for (Entry entry : input) {
            if (entry instanceof StationEntry station) {
                if (!seen.add(station.id())) continue;
            }
            result.add(entry);
        }
        return List.copyOf(result);
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    public sealed interface Entry permits StationEntry, ButtonEntry {
    }

    public record StationEntry(String id) implements Entry {
    }

    public record ButtonEntry(CategoryNamespaces.ButtonEntry button) implements Entry {
    }
}
