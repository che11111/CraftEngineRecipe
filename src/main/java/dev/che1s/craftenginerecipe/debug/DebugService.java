package dev.che1s.craftenginerecipe.debug;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.che1s.craftenginerecipe.CraftEngineRecipePlugin;
import dev.che1s.craftenginerecipe.recipe.RecipeEntry;
import net.momirealms.craftengine.core.item.recipe.Recipe;
import net.momirealms.craftengine.core.item.recipe.RecipeType;
import net.momirealms.craftengine.core.plugin.CraftEngine;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.IllegalPluginAccessException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import java.util.logging.Level;

/** 管理员诊断功能。配方访问在全局线程进行，只有普通 JSON 值会进入 I/O 工作线程。 */
public final class DebugService {
    private static final long EXPORT_COOLDOWN = TimeUnit.SECONDS.toNanos(30);
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC);
    private final CraftEngineRecipePlugin plugin;
    private final AtomicBoolean exporting = new AtomicBoolean();
    private final Map<String, LongSupplier> gauges = new LinkedHashMap<>();
    private long lastExport;
    private boolean hasExported;
    private volatile boolean stopped;

    public DebugService(CraftEngineRecipePlugin plugin) { this.plugin = plugin; }

    /** 在启动期间注册固定且线程安全的计数器。取值函数不得访问玩家背包。 */
    public synchronized void registerGauge(String name, LongSupplier gauge) {
        if (!this.gauges.containsKey(name) && this.gauges.size() >= 32) throw new IllegalStateException("Too many diagnostic gauges");
        this.gauges.put(java.util.Objects.requireNonNull(name), java.util.Objects.requireNonNull(gauge));
    }

    public void execute(CommandSender sender, String[] arguments) {
        if (!sender.hasPermission("craftenginerecipe.admin")) {
            this.reply(sender, "messages.no-permission");
            return;
        }
        if (arguments.length == 1 && arguments[0].equalsIgnoreCase("recipes")) {
            this.exportRecipes(sender);
        } else if (arguments.length >= 1 && arguments[0].equalsIgnoreCase("performance")
                && (arguments.length == 1 || arguments.length == 2 && arguments[1].equalsIgnoreCase("reset"))) {
            if (arguments.length == 2) {
                this.plugin.diagnostics().reset();
                this.reply(sender, "messages.debug-performance-reset");
            } else this.global(() -> this.performance(sender));
        } else this.reply(sender, "messages.debug-usage");
    }

    private void exportRecipes(CommandSender sender) {
        synchronized (this) {
            if (this.exporting.get()) {
                this.reply(sender, "messages.debug-export-busy");
                return;
            }
            long now = System.nanoTime();
            long elapsed = now - this.lastExport;
            if (this.hasExported && elapsed < EXPORT_COOLDOWN) {
                this.reply(sender, "messages.debug-export-cooldown", "seconds",
                        Long.toString(Math.max(1, TimeUnit.NANOSECONDS.toSeconds(EXPORT_COOLDOWN - elapsed) + 1)));
                return;
            }
            this.exporting.set(true);
            this.lastExport = now;
            this.hasExported = true;
        }
        this.reply(sender, "messages.debug-export-start");
        this.global(() -> {
            try { new Capture(sender).run(); }
            catch (RuntimeException | LinkageError error) { this.fail(sender, error); }
        });
    }

    private void performance(CommandSender sender) {
        Diagnostics.Snapshot report = this.plugin.diagnostics().snapshot();
        this.reply(sender, "messages.debug-performance-header", "seconds", number(report.elapsedNanos() / 1_000_000_000.0));
        String tps = "N/A", mspt = "N/A";
        try {
            var values = Bukkit.getTPS();
            if (values.length >= 3) tps = number(values[0]) + "/" + number(values[1]) + "/" + number(values[2]);
            mspt = number(Bukkit.getAverageTickTime());
        } catch (RuntimeException | LinkageError ignored) {
            // 并非所有平台的 Folia 都提供有意义的全服统一 tick 时间。
        }
        this.reply(sender, "messages.debug-performance-server", "tps", tps, "mspt", mspt,
                "folia", Boolean.toString(dev.che1s.craftenginerecipe.scheduler.TaskDispatcher.isFolia()));
        int nativeRecipes = 0;
        for (RecipeType type : RecipeType.values()) nativeRecipes += this.plugin.data().recipeSnapshot().byType(type).size();
        var external = this.plugin.externalRecipes().snapshot();
        this.reply(sender, "messages.debug-performance-data", "ready", Boolean.toString(this.plugin.data().isReady()),
                "generation", Long.toString(this.plugin.recipeIndex().generation()),
                "items", Integer.toString(this.plugin.data().items().size()),
                "categories", Integer.toString(this.plugin.data().categoriesById().size()),
                "native", Integer.toString(nativeRecipes), "external", Integer.toString(external.entries().size()),
                "externalItems", Integer.toString(external.items().size()));
        synchronized (this) {
            this.gauges.forEach((name, supplier) -> {
                try { this.reply(sender, "messages.debug-performance-gauge", "name", name, "value", Long.toString(supplier.getAsLong())); }
                catch (RuntimeException | LinkageError ignored) { this.reply(sender, "messages.debug-performance-gauge", "name", name, "value", "N/A"); }
            });
        }
        report.counters().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
                this.reply(sender, "messages.debug-performance-gauge", "name", entry.getKey().name(), "value", entry.getValue().toString()));
        if (report.timings().isEmpty()) this.reply(sender, "messages.debug-performance-empty");
        report.timings().stream().sorted(Comparator.comparingLong(Diagnostics.Timing::totalNanos).reversed()).forEach(timing ->
                this.reply(sender, "messages.debug-performance-operation", "name", timing.operation().name(),
                        "count", Long.toString(timing.count()), "total", number(timing.totalMillis()),
                        "average", number(timing.averageMillis()), "max", number(timing.maximumMillis())));
        this.reply(sender, "messages.debug-performance-note");
    }

    private final class Capture implements Runnable {
        private final CommandSender sender;
        private final long generation = plugin.data().generation();
        private final long externalGeneration = plugin.externalRecipes().generation();
        private final JsonObject document = new JsonObject();
        private final JsonArray bukkit = new JsonArray();
        private final JsonArray nativeRecipes = new JsonArray();
        private final JsonArray external = new JsonArray();
        private final Iterator<org.bukkit.inventory.Recipe> bukkitIterator;
        private final Iterator<Recipe> nativeIterator;
        private final Iterator<RecipeEntry> externalIterator;
        private long captureNanos;
        private int errors;

        private Capture(CommandSender sender) {
            this.sender = sender;
            if (CraftEngine.instance().isReloading()) throw new IllegalStateException("CraftEngine is reloading; retry when loading has completed");
            this.bukkitIterator = Bukkit.recipeIterator();
            List<Recipe> nativeCopy = new ArrayList<>();
            for (RecipeType type : RecipeType.values()) {
                var recipes = CraftEngine.instance().recipeManager().recipesByType(type);
                if (recipes != null) nativeCopy.addAll(recipes);
            }
            this.nativeIterator = nativeCopy.iterator();
            this.externalIterator = plugin.externalRecipes().snapshot().entries().iterator();
            this.document.addProperty("schemaVersion", 1);
            this.document.addProperty("captureStartedUtc", Instant.now().toString());
            this.document.addProperty("pluginVersion", plugin.getPluginMeta().getVersion());
            this.document.addProperty("server", Bukkit.getVersion());
            this.document.addProperty("minecraftVersion", Bukkit.getMinecraftVersion());
            this.document.addProperty("craftEngineVersion", CraftEngine.instance().pluginVersion());
            this.document.addProperty("cerDataReady", plugin.data().isReady());
            this.document.addProperty("cerGeneration", this.generation);
            this.document.addProperty("externalGeneration", this.externalGeneration);
            JsonArray notes = new JsonArray();
            notes.add("Bukkit and CraftEngine sections may represent overlapping registrations; counts must not be added to infer unique recipes.");
            notes.add("The export is captured in short batches across ticks. Avoid recipe reloads during capture; detected changes abort the export.");
            notes.add("Includes every registered Bukkit recipe, CraftEngine recipe by all API recipe types, and published CER provider/custom/integration entries, regardless of viewer permissions.");
            notes.add("Diagnostic snapshot, not an importable backup. Dynamic recipes, predicates, post-processors and runtime functions may expose only class names, not executable behavior or configuration.");
            notes.add("Item nbtBase64 contains Bukkit serializeAsBytes output including exact item components; no items are built or granted by this command.");
            this.document.add("notes", notes);
            this.document.add("bukkit", this.bukkit);
            this.document.add("craftEngine", this.nativeRecipes);
            this.document.add("cerProviders", this.external);
        }

        @Override public void run() {
            if (stopped) return;
            long started = System.nanoTime();
            try {
                if (plugin.data().generation() != this.generation || plugin.externalRecipes().generation() != this.externalGeneration
                        || CraftEngine.instance().isReloading()) throw new IllegalStateException("Recipes changed while capturing; retry after the reload");
                int remaining = 32;
                do {
                    if (this.bukkitIterator.hasNext()) {
                        var recipe = this.bukkitIterator.next();
                        try { this.bukkit.add(RecipeDump.bukkit(recipe)); }
                        catch (RuntimeException | LinkageError error) { this.bukkit.add(this.failedEntry(recipe.getClass().getName(), error)); }
                    } else if (this.nativeIterator.hasNext()) {
                        var recipe = this.nativeIterator.next();
                        try { this.nativeRecipes.add(RecipeDump.nativeRecipe(recipe)); }
                        catch (RuntimeException | LinkageError error) { this.nativeRecipes.add(this.failedEntry(recipe.id().toString(), error)); }
                    } else if (this.externalIterator.hasNext()) {
                        var entry = this.externalIterator.next();
                        try { this.external.add(RecipeDump.external(entry)); }
                        catch (RuntimeException | LinkageError error) { this.external.add(this.failedEntry(entry.display().id(), error)); }
                    } else {
                        this.captureNanos += System.nanoTime() - started;
                        plugin.diagnostics().record(Diagnostics.Operation.EXPORT_SNAPSHOT, this.captureNanos);
                        this.finish();
                        return;
                    }
                } while (--remaining > 0 && System.nanoTime() - started < 2_000_000L);
                this.captureNanos += System.nanoTime() - started;
                plugin.tasks().globalLater(this, 1);
            } catch (RuntimeException | LinkageError error) { fail(this.sender, error); }
        }

        private JsonObject failedEntry(String id, Throwable error) {
            ++this.errors;
            JsonObject failed = new JsonObject();
            failed.addProperty("idOrClass", id);
            failed.addProperty("captureError", RecipeDump.error(error));
            return failed;
        }

        private void finish() {
            this.document.addProperty("captureCompletedUtc", Instant.now().toString());
            this.document.addProperty("captureWorkMillis", this.captureNanos / 1_000_000.0);
            JsonObject counts = new JsonObject();
            counts.addProperty("bukkit", this.bukkit.size());
            counts.addProperty("craftEngine", this.nativeRecipes.size());
            counts.addProperty("cerProviders", this.external.size());
            counts.addProperty("entryErrors", this.errors);
            this.document.add("counts", counts);
            Path directory = plugin.getDataFolder().toPath().resolve("debug");
            plugin.tasks().async(() -> {
                try (var ignored = plugin.diagnostics().measure(Diagnostics.Operation.EXPORT_WRITE)) {
                    Path file = write(directory, this.document);
                    reply(this.sender, "messages.debug-export-success", "path", file.toAbsolutePath().toString(),
                            "bukkit", Integer.toString(this.bukkit.size()), "native", Integer.toString(this.nativeRecipes.size()),
                            "external", Integer.toString(this.external.size()), "errors", Integer.toString(this.errors));
                } catch (IOException | RuntimeException | LinkageError error) { fail(this.sender, error); }
                finally { releaseExport(); }
            });
        }
    }

    static Path write(Path directory, JsonObject document) throws IOException {
        Files.createDirectories(directory);
        String name = "recipes-" + FILE_TIME.format(Instant.now()) + "-" + UUID.randomUUID().toString().substring(0, 8) + ".json";
        Path target = directory.resolve(name);
        Path temporary = Files.createTempFile(directory, "recipes-", ".tmp");
        try {
            try (var writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) { RecipeDump.JSON.toJson(document, writer); }
            try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, target); }
            return target;
        } finally { Files.deleteIfExists(temporary); }
    }

    private void fail(CommandSender sender, Throwable error) {
        this.releaseExport();
        if (this.stopped) return;
        this.plugin.getLogger().log(Level.WARNING, this.plugin.lang().text("logs.debug-export-failed"), error);
        this.reply(sender, "messages.debug-export-failed", "error", RecipeDump.error(error));
    }

    private void global(Runnable action) {
        if (this.stopped) return;
        try { this.plugin.tasks().global(action); }
        catch (IllegalPluginAccessException ignored) { this.releaseExport(); }
    }

    private void reply(CommandSender sender, String key, String... arguments) {
        if (this.stopped) return;
        Runnable action = () -> sender.sendMessage(this.plugin.lang().message(key, arguments));
        if (sender instanceof Player player) this.plugin.tasks().player(player, action);
        else action.run();
    }

    public void shutdown() { this.stopped = true; this.exporting.set(false); }

    private synchronized void releaseExport() {
        this.lastExport = System.nanoTime();
        this.hasExported = true;
        this.exporting.set(false);
    }

    private static String number(double value) { return Double.isFinite(value) ? String.format(Locale.ROOT, "%.3f", value) : "N/A"; }
}
