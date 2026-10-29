package dev.che1s.craftenginerecipe.gui;

import dev.che1s.craftenginerecipe.CraftEngineRecipePlugin;
import dev.che1s.craftenginerecipe.preferences.PlayerPreferences;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 按玩家保存最后一次主动关闭时的浏览器视图，数据存放在玩家统一 SQLite 数据库。 */
public final class GuiSessionStore {
    private final CraftEngineRecipePlugin plugin;
    private final PlayerPreferences database;
    private final Map<UUID, Remembered> sessions = new ConcurrentHashMap<>();
    private final Path legacyFile;

    public GuiSessionStore(CraftEngineRecipePlugin plugin) {
        this.plugin = plugin;
        this.database = plugin.playerPreferences();
        this.legacyFile = plugin.getDataFolder().toPath().resolve("sessions.yml");
        this.loadDatabase();
        this.migrateLegacyYaml();
    }

    GuiSession.Snapshot get(UUID player, long lifetimeMillis) {
        Remembered remembered = this.sessions.get(player);
        if (remembered == null) return null;
        long now = System.currentTimeMillis();
        if (expired(remembered.savedAt(), lifetimeMillis, now)) {
            this.sessions.remove(player);
            this.database.deleteSession(player);
            return null;
        }
        return remembered.snapshot();
    }

    static boolean expired(long savedAt, long lifetimeMillis, long now) {
        return lifetimeMillis > 0 && (savedAt <= 0 || now >= savedAt && now - savedAt > lifetimeMillis);
    }

    void remember(UUID player, GuiSession.Snapshot snapshot) {
        if (snapshot == null) return;
        long savedAt = System.currentTimeMillis();
        this.sessions.put(player, new Remembered(snapshot, savedAt));
        this.database.saveSession(player, snapshotYaml(snapshot), savedAt);
    }

    public void clear(UUID player) {
        if (this.sessions.remove(player) != null) this.database.deleteSession(player);
    }

    private void loadDatabase() {
        this.database.loadSessions().forEach((player, stored) -> {
            GuiSession.Snapshot snapshot = parseSnapshot(stored.snapshot());
            if (snapshot != null) this.sessions.put(player, new Remembered(snapshot, stored.savedAt()));
        });
    }

    /**
     * 旧版 sessions.yml 只导入一次。数据库已有同玩家记录时数据库优先，
     * 成功后保留 sessions.yml.migrated 作为可恢复备份。
     */
    private void migrateLegacyYaml() {
        if (!Files.isRegularFile(this.legacyFile)) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(this.legacyFile.toFile());
        Map<UUID, PlayerPreferences.StoredSession> imported = new LinkedHashMap<>();
        for (String raw : yaml.getKeys(false)) {
            try {
                UUID player = UUID.fromString(raw);
                if (this.sessions.containsKey(player)) continue;
                GuiSession.Snapshot snapshot = snapshotFromYaml(yaml, raw + ".");
                if (snapshot == null) continue;
                long savedAt = yaml.getLong(raw + ".saved-at", 0L);
                this.sessions.put(player, new Remembered(snapshot, savedAt));
                imported.put(player, new PlayerPreferences.StoredSession(snapshotYaml(snapshot), savedAt));
            } catch (IllegalArgumentException ignored) {
                // 忽略格式错误的旧记录，其他玩家仍可继续迁移。
            }
        }
        if (!this.database.saveSessions(imported)) return;
        Path migrated = this.legacyFile.resolveSibling("sessions.yml.migrated");
        try {
            Files.move(this.legacyFile, migrated, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException error) {
            this.plugin.getLogger().warning(this.plugin.lang().text(
                    "logs.session-migrate-failed", "error", error.getMessage()));
        }
    }

    static GuiSession.Snapshot parseSnapshot(String text) {
        if (text == null || text.isBlank()) return null;
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(text);
        } catch (InvalidConfigurationException error) {
            return null;
        }
        return snapshotFromYaml(yaml, "");
    }

    private static GuiSession.Snapshot snapshotFromYaml(YamlConfiguration yaml, String path) {
        List<GuiSession.HistoryPage> history = new ArrayList<>();
        for (Map<?, ?> page : yaml.getMapList(path + "history")) {
            Object historyItem = page.get("item");
            if (!(historyItem instanceof String value) || history.size() >= 128) continue;
            history.add(new GuiSession.HistoryPage(value,
                    Boolean.TRUE.equals(page.get("usage")),
                    page.get("index") instanceof Number number ? Math.max(0, number.intValue()) : 0,
                    Boolean.TRUE.equals(page.get("workstation-selection"))));
        }
        return new GuiSession.Snapshot(
                yaml.getString(path + "category"),
                yaml.getInt(path + "category-page", 0),
                yaml.getString(path + "parent-category"),
                yaml.getBoolean(path + "unapplied", false),
                yaml.getString(path + "workstation-id"),
                yaml.getInt(path + "workstation-page", 0),
                yaml.getString(path + "workstation-keyword"),
                yaml.getString(path + "recipe-item"),
                yaml.getInt(path + "recipe-index", 0),
                yaml.getBoolean(path + "recipe-usage", false),
                yaml.getBoolean(path + "recipe-workstation-selection", false),
                history,
                yaml.getInt(path + "content-page", 0),
                yaml.getString(path + "item-search-keyword"));
    }

    static String snapshotYaml(GuiSession.Snapshot snapshot) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("category", snapshot.category());
        yaml.set("category-page", snapshot.categoryPage());
        yaml.set("content-page", snapshot.contentPage());
        yaml.set("parent-category", snapshot.parentCategory());
        yaml.set("unapplied", snapshot.unapplied());
        yaml.set("workstation-id", snapshot.workstationId());
        yaml.set("workstation-page", snapshot.workstationPage());
        yaml.set("workstation-keyword", snapshot.workstationKeyword());
        yaml.set("item-search-keyword", snapshot.itemSearchKeyword());
        yaml.set("recipe-item", snapshot.recipeItem());
        yaml.set("recipe-index", snapshot.recipeIndex());
        yaml.set("recipe-usage", snapshot.recipeUsageMode());
        yaml.set("recipe-workstation-selection", snapshot.recipeWorkstationSelection());
        List<Map<String, Object>> history = new ArrayList<>();
        for (GuiSession.HistoryPage page : snapshot.recipeHistory()) {
            history.add(Map.of(
                    "item", page.item(),
                    "usage", page.usageMode(),
                    "index", page.index(),
                    "workstation-selection", page.workstationSelection()));
        }
        yaml.set("history", history);
        return yaml.saveToString();
    }

    private record Remembered(GuiSession.Snapshot snapshot, long savedAt) {
    }
}
