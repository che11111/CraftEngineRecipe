package dev.che1s.craftenginerecipe.preferences;

import org.bukkit.configuration.file.FileConfiguration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/** 由小型 SQLite 数据库持久化、使用异步写入的玩家偏好缓存。 */
public final class PlayerPreferences implements AutoCloseable {
    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS player_preferences (
                player_uuid TEXT PRIMARY KEY,
                menu_pack TEXT,
                remember_last_page INTEGER,
                landing_page TEXT,
                updated_at INTEGER NOT NULL
            )
            """;
    private static final String UPSERT = """
            INSERT INTO player_preferences(player_uuid, menu_pack, remember_last_page, landing_page, updated_at)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT(player_uuid) DO UPDATE SET
                menu_pack = excluded.menu_pack,
                remember_last_page = excluded.remember_last_page,
                landing_page = excluded.landing_page,
                updated_at = excluded.updated_at
            """;
    private static final String CREATE_SESSION_TABLE = """
            CREATE TABLE IF NOT EXISTS gui_sessions (
                player_uuid TEXT PRIMARY KEY,
                snapshot TEXT NOT NULL,
                saved_at INTEGER NOT NULL
            )
            """;
    private static final String UPSERT_SESSION = """
            INSERT INTO gui_sessions(player_uuid, snapshot, saved_at)
            VALUES (?, ?, ?)
            ON CONFLICT(player_uuid) DO UPDATE SET
                snapshot = excluded.snapshot,
                saved_at = excluded.saved_at
            """;

    public enum LandingPage {
        NAMESPACES,
        WORKSTATIONS;

        static LandingPage parse(String value) {
            if (value == null) return null;
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "namespaces", "categories", "namespace" -> NAMESPACES;
                case "workstations", "workstation" -> WORKSTATIONS;
                default -> null;
            };
        }
    }

    private final Path database;
    private final Logger logger;
    private final Map<UUID, Stored> preferences = new ConcurrentHashMap<>();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "CraftEngineRecipe-Player-Data");
        thread.setDaemon(true);
        return thread;
    });
    private volatile Settings settings = Settings.disabled();

    public PlayerPreferences(Path dataFolder, Logger logger) {
        this.database = dataFolder.resolve("player-preferences.db");
        this.logger = logger;
        this.initialize();
        this.load();
    }

    public void reload(FileConfiguration config, Collection<String> enabledPacks) {
        LinkedHashSet<String> available = new LinkedHashSet<>();
        for (String pack : enabledPacks) {
            String normalized = normalize(pack);
            if (normalized != null) available.add(normalized);
        }
        List<String> choices = new ArrayList<>();
        for (String configured : config.getStringList("personalization.menu-packs")) {
            String pack = normalize(configured);
            if (pack != null && available.contains(pack) && !choices.contains(pack)) choices.add(pack);
        }
        if (choices.isEmpty()) choices.addAll(available);
        String configuredDefault = normalize(config.getString("personalization.default-menu-pack"));
        String serverDefault = normalize(config.getString("menu-packs.default"));
        String defaultPack = configuredDefault != null && choices.contains(configuredDefault)
                ? configuredDefault
                : serverDefault != null && choices.contains(serverDefault)
                        ? serverDefault : choices.isEmpty() ? "" : choices.getFirst();
        boolean defaultRemember = config.getBoolean("personalization.default-remember-last-page",
                config.getBoolean("browser.remember-last-page", true));
        LandingPage defaultLanding = LandingPage.parse(
                config.getString("browser.default-main-page", "namespaces"));
        if (defaultLanding == null) defaultLanding = LandingPage.NAMESPACES;
        this.settings = new Settings(config.getBoolean("personalization.enabled", true) && !choices.isEmpty(),
                List.copyOf(choices), defaultPack, defaultRemember, defaultLanding);
    }

    public boolean enabled() {
        return this.settings.enabled();
    }

    public List<String> menuPacks() {
        return this.settings.menuPacks();
    }

    public String defaultMenuPack() {
        return this.settings.defaultMenuPack();
    }

    public String menuPack(UUID player) {
        Stored stored = this.preferences.get(player);
        String selected = stored == null ? null : normalize(stored.menuPack());
        return selected != null && this.settings.menuPacks().contains(selected)
                ? selected : this.settings.defaultMenuPack();
    }

    public boolean rememberLastPage(UUID player) {
        Stored stored = this.preferences.get(player);
        return stored != null && stored.rememberLastPage() != null
                ? stored.rememberLastPage() : this.settings.defaultRememberLastPage();
    }

    public LandingPage landingPage(UUID player) {
        if (!this.settings.enabled()) return this.settings.defaultLandingPage();
        Stored stored = this.preferences.get(player);
        return stored != null && stored.landingPage() != null
                ? stored.landingPage() : this.settings.defaultLandingPage();
    }

    public boolean setMenuPack(UUID player, String menuPack) {
        String normalized = normalize(menuPack);
        if (normalized == null || !this.settings.menuPacks().contains(normalized)) return false;
        this.update(player, normalized, null, null);
        return true;
    }

    public void setRememberLastPage(UUID player, boolean remember) {
        this.update(player, null, remember, null);
    }

    public void setLandingPage(UUID player, LandingPage landingPage) {
        if (landingPage != null) this.update(player, null, null, landingPage);
    }

    private void update(UUID player, String menuPack, Boolean remember, LandingPage landingPage) {
        Stored stored = this.preferences.compute(player, (ignored, current) -> {
            Stored value = current == null ? Stored.EMPTY : current;
            return new Stored(menuPack == null ? value.menuPack() : menuPack,
                    remember == null ? value.rememberLastPage() : remember,
                    landingPage == null ? value.landingPage() : landingPage);
        });
        this.writer.execute(() -> this.persist(player, stored));
    }

    private void initialize() {
        try {
            Files.createDirectories(this.database.getParent());
            Class.forName("org.sqlite.JDBC");
            try (Connection connection = this.connection(); Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA synchronous=NORMAL");
                statement.execute(CREATE_TABLE);
                statement.execute(CREATE_SESSION_TABLE);
                ensureColumn(connection, "landing_page", "TEXT");
            }
        } catch (Exception error) {
            this.logger.warning("Cannot initialize player preferences database: " + error.getMessage());
        }
    }

    private void load() {
        try (Connection connection = this.connection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT player_uuid, menu_pack, remember_last_page, landing_page FROM player_preferences")) {
            while (result.next()) {
                try {
                    UUID player = UUID.fromString(result.getString("player_uuid"));
                    String menuPack = result.getString("menu_pack");
                    int remember = result.getInt("remember_last_page");
                    Boolean rememberLastPage = result.wasNull() ? null : remember != 0;
                    LandingPage landingPage = LandingPage.parse(result.getString("landing_page"));
                    this.preferences.put(player, new Stored(menuPack, rememberLastPage, landingPage));
                } catch (IllegalArgumentException ignored) {
                    // 忽略 UUID 格式错误的行，不影响数据库中的其他记录。
                }
            }
        } catch (SQLException error) {
            this.logger.warning("Cannot load player preferences database: " + error.getMessage());
        }
    }

    private void persist(UUID player, Stored stored) {
        try (Connection connection = this.connection(); PreparedStatement statement = connection.prepareStatement(UPSERT)) {
            statement.setString(1, player.toString());
            statement.setString(2, stored.menuPack());
            if (stored.rememberLastPage() == null) statement.setNull(3, java.sql.Types.INTEGER);
            else statement.setInt(3, stored.rememberLastPage() ? 1 : 0);
            if (stored.landingPage() == null) statement.setNull(4, java.sql.Types.VARCHAR);
            else statement.setString(4, stored.landingPage().name().toLowerCase(Locale.ROOT));
            statement.setLong(5, System.currentTimeMillis());
            statement.executeUpdate();
        } catch (SQLException error) {
            this.logger.warning("Cannot save player preferences: " + error.getMessage());
        }
    }

    /** 读取统一数据库中的菜单恢复快照。快照序列化格式由 GuiSessionStore 管理。 */
    public Map<UUID, StoredSession> loadSessions() {
        Map<UUID, StoredSession> sessions = new ConcurrentHashMap<>();
        try (Connection connection = this.connection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT player_uuid, snapshot, saved_at FROM gui_sessions")) {
            while (result.next()) {
                try {
                    UUID player = UUID.fromString(result.getString("player_uuid"));
                    sessions.put(player, new StoredSession(result.getString("snapshot"),
                            result.getLong("saved_at")));
                } catch (IllegalArgumentException ignored) {
                    // 忽略 UUID 格式错误的行，不影响其他玩家数据。
                }
            }
        } catch (SQLException error) {
            this.logger.warning("Cannot load GUI sessions database: " + error.getMessage());
        }
        return sessions;
    }

    public void saveSession(UUID player, String snapshot, long savedAt) {
        if (player == null || snapshot == null) return;
        this.writer.execute(() -> {
            try (Connection connection = this.connection();
                 PreparedStatement statement = connection.prepareStatement(UPSERT_SESSION)) {
                statement.setString(1, player.toString());
                statement.setString(2, snapshot);
                statement.setLong(3, savedAt);
                statement.executeUpdate();
            } catch (SQLException error) {
                this.logger.warning("Cannot save GUI session: " + error.getMessage());
            }
        });
    }

    /** 旧数据迁移使用单事务同步写入，成功后才允许归档旧文件。 */
    public boolean saveSessions(Map<UUID, StoredSession> sessions) {
        if (sessions == null || sessions.isEmpty()) return true;
        try (Connection connection = this.connection();
             PreparedStatement statement = connection.prepareStatement(UPSERT_SESSION)) {
            connection.setAutoCommit(false);
            for (var entry : sessions.entrySet()) {
                statement.setString(1, entry.getKey().toString());
                statement.setString(2, entry.getValue().snapshot());
                statement.setLong(3, entry.getValue().savedAt());
                statement.addBatch();
            }
            statement.executeBatch();
            connection.commit();
            return true;
        } catch (SQLException error) {
            this.logger.warning("Cannot import GUI sessions: " + error.getMessage());
            return false;
        }
    }

    public void deleteSession(UUID player) {
        if (player == null) return;
        this.writer.execute(() -> {
            try (Connection connection = this.connection();
                 PreparedStatement statement = connection.prepareStatement(
                         "DELETE FROM gui_sessions WHERE player_uuid = ?")) {
                statement.setString(1, player.toString());
                statement.executeUpdate();
            } catch (SQLException error) {
                this.logger.warning("Cannot delete GUI session: " + error.getMessage());
            }
        });
    }

    private Connection connection() throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + this.database.toAbsolutePath());
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout=5000");
        }
        return connection;
    }

    @Override
    public void close() {
        this.writer.shutdown();
        try {
            if (!this.writer.awaitTermination(3, TimeUnit.SECONDS)) this.writer.shutdownNow();
        } catch (InterruptedException error) {
            this.writer.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static String normalize(String pack) {
        if (pack == null || pack.isBlank()) return null;
        String value = pack.trim().toLowerCase(Locale.ROOT);
        return value.startsWith("cer_menu_") ? value : "cer_menu_" + value;
    }

    private static void ensureColumn(Connection connection, String name, String type) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet columns = statement.executeQuery("PRAGMA table_info(player_preferences)")) {
            while (columns.next()) {
                if (name.equalsIgnoreCase(columns.getString("name"))) return;
            }
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE player_preferences ADD COLUMN " + name + " " + type);
        }
    }

    private record Stored(String menuPack, Boolean rememberLastPage, LandingPage landingPage) {
        private static final Stored EMPTY = new Stored(null, null, null);
    }

    public record StoredSession(String snapshot, long savedAt) {
    }

    private record Settings(boolean enabled, List<String> menuPacks, String defaultMenuPack,
                            boolean defaultRememberLastPage, LandingPage defaultLandingPage) {
        private static Settings disabled() {
            return new Settings(false, List.of(), "", true, LandingPage.NAMESPACES);
        }
    }
}
