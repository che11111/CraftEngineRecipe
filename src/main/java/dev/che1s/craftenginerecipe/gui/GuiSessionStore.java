package dev.che1s.craftenginerecipe.gui;

import dev.che1s.craftenginerecipe.CraftEngineRecipePlugin;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 按玩家保存最后一次主动关闭时的浏览器视图。 */
public final class GuiSessionStore {
    private final CraftEngineRecipePlugin plugin;
    private final Map<UUID, Remembered> sessions = new HashMap<>();
    private final Path file;

    public GuiSessionStore(CraftEngineRecipePlugin plugin) {
        this.plugin = plugin;
        this.file = plugin.getDataFolder().toPath().resolve("sessions.yml");
        this.load();
    }

    GuiSession.Snapshot get(UUID player, long lifetimeMillis) {
        Remembered remembered = this.sessions.get(player);
        if (remembered == null) return null;
        long now = System.currentTimeMillis();
        if (expired(remembered.savedAt(), lifetimeMillis, now)) {
            this.sessions.remove(player);
            this.save();
            return null;
        }
        return remembered.snapshot();
    }

    static boolean expired(long savedAt, long lifetimeMillis, long now) {
        return lifetimeMillis > 0 && (savedAt <= 0 || now >= savedAt && now - savedAt > lifetimeMillis);
    }

    void remember(UUID player, GuiSession.Snapshot snapshot) {
        if (snapshot == null) return;
        this.sessions.put(player, new Remembered(snapshot, System.currentTimeMillis()));
        this.save();
    }

    public void clear(UUID player) {
        if (this.sessions.remove(player) != null) this.save();
    }

    private void load() {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(this.file.toFile());
        for (String raw : yaml.getKeys(false)) {
            try {
                UUID player = UUID.fromString(raw);
                String path = raw + ".";
                String category = yaml.getString(path + "category");
                String parent = yaml.getString(path + "parent-category");
                String workstation = yaml.getString(path + "workstation-id");
                String item = yaml.getString(path + "recipe-item");
                List<GuiSession.HistoryPage> history = new ArrayList<>();
                for (Map<?, ?> page : yaml.getMapList(path + "history")) {
                    Object historyItem = page.get("item");
                    if (!(historyItem instanceof String value) || history.size() >= 128) continue;
                    history.add(new GuiSession.HistoryPage(value,
                            Boolean.TRUE.equals(page.get("usage")),
                            page.get("index") instanceof Number number ? Math.max(0, number.intValue()) : 0,
                            Boolean.TRUE.equals(page.get("workstation-selection"))));
                }
                GuiSession.Snapshot snapshot = new GuiSession.Snapshot(category,
                        yaml.getInt(path + "category-page", 0), parent,
                        yaml.getBoolean(path + "unapplied", false), workstation,
                        yaml.getInt(path + "workstation-page", 0), yaml.getString(path + "workstation-keyword"),
                        yaml.getBoolean(path + "external-catalog", false), item,
                        yaml.getInt(path + "recipe-index", 0), yaml.getBoolean(path + "recipe-usage", false),
                        yaml.getBoolean(path + "recipe-workstation-selection", false),
                        history, yaml.getInt(path + "content-page", 0));
                this.sessions.put(player, new Remembered(snapshot, yaml.getLong(path + "saved-at", 0L)));
            } catch (IllegalArgumentException ignored) {
                // 忽略格式错误的条目，同时保留文件中的其他记录。
            }
        }
    }

    private void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        this.sessions.forEach((player, remembered) -> {
            GuiSession.Snapshot snapshot = remembered.snapshot();
            String path = player + ".";
            yaml.set(path + "saved-at", remembered.savedAt());
            yaml.set(path + "category", snapshot.category());
            yaml.set(path + "category-page", snapshot.categoryPage());
            yaml.set(path + "content-page", snapshot.contentPage());
            yaml.set(path + "parent-category", snapshot.parentCategory());
            yaml.set(path + "unapplied", snapshot.unapplied());
            yaml.set(path + "workstation-id", snapshot.workstationId());
            yaml.set(path + "workstation-page", snapshot.workstationPage());
            yaml.set(path + "workstation-keyword", snapshot.workstationKeyword());
            yaml.set(path + "external-catalog", snapshot.externalCatalog());
            yaml.set(path + "recipe-item", snapshot.recipeItem());
            yaml.set(path + "recipe-index", snapshot.recipeIndex());
            yaml.set(path + "recipe-usage", snapshot.recipeUsageMode());
            yaml.set(path + "recipe-workstation-selection", snapshot.recipeWorkstationSelection());
            List<Map<String, Object>> history = new ArrayList<>();
            for (GuiSession.HistoryPage page : snapshot.recipeHistory()) {
                history.add(Map.of(
                        "item", page.item(),
                        "usage", page.usageMode(),
                        "index", page.index(),
                        "workstation-selection", page.workstationSelection()));
            }
            yaml.set(path + "history", history);
        });
        Path temporary = this.file.resolveSibling(this.file.getFileName() + ".tmp");
        try {
            yaml.save(temporary.toFile());
            try {
                Files.move(temporary, this.file, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, this.file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException error) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
            }
            this.plugin.getLogger().warning(this.plugin.lang().text("logs.session-save-failed",
                    "error", error.getMessage()));
        }
    }

    private record Remembered(GuiSession.Snapshot snapshot, long savedAt) {
    }
}
