package dev.che1s.craftenginerecipe.recipe;

import dev.che1s.craftenginerecipe.CraftEngineRecipePlugin;
import dev.che1s.craftenginerecipe.api.DisplayRecipe;
import dev.che1s.craftenginerecipe.api.internal.ApiRecipeCompiler;
import dev.che1s.craftenginerecipe.config.IntegrationSettings;
import dev.che1s.craftenginerecipe.recipe.io.CustomRecipeLoader;
import dev.che1s.craftenginerecipe.recipe.io.RecipeFileExporter;
import dev.che1s.craftenginerecipe.debug.Diagnostics;
import dev.che1s.craftenginerecipe.integration.minecraft.BundledMinecraftRecipeSource;
import dev.che1s.craftenginerecipe.integration.craftengine.CraftEngineRecipeSource;
import dev.che1s.craftenginerecipe.integration.common.CultivationDataExporter;
import dev.che1s.craftenginerecipe.integration.customcrops.CustomCropsRecipeSource;
import dev.che1s.craftenginerecipe.integration.farmersdelight.FarmersDelightRecipeSource;
import dev.che1s.craftenginerecipe.integration.kaleidoscope.KaleidoscopeRecipeSource;
import dev.che1s.craftenginerecipe.integration.common.PluginFingerprints;
import dev.che1s.craftenginerecipe.integration.common.RecipeSource;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 发布 recipes/ 中的配方。受支持的集成交互只在生成缺失来源目录时读取一次；
 * 生成后绝不会继续把来源插件的实时管理器作为运行时配方来源。
 *
 * <p>文件监视在异步定时器上比较轻量指纹；真正加载和替换快照始终切回全局线程。
 * 连续两次轮询看到相同指纹后才重载，以减少编辑器保存时产生的重复刷新。</p>
 */
public final class ExternalRecipeService implements Listener {
    private static final List<String> BUILT_IN_SOURCES =
            List.of("craftengine", "farmersdelight", "kaleidoscope", CustomCropsRecipeSource.SOURCE_ID);
    private final CraftEngineRecipePlugin plugin;
    private final Map<Plugin, Adapter> adapters = new ConcurrentHashMap<>();
    private final Map<String, String> failures = new ConcurrentHashMap<>();
    private volatile ExternalRecipeSnapshot snapshot = ExternalRecipeSnapshot.EMPTY;
    private volatile long generation;
    private volatile boolean stopped;
    private volatile Map<String, String> lastFileState = Map.of();
    private Map<String, String> pendingFileState;
    private boolean pendingChanged;
    private ScheduledTask pollTask;
    private int sourceCheckTicks;
    private boolean sourcesSettled;
    private final AtomicBoolean sourceCheckScheduled = new AtomicBoolean();
    private final AtomicBoolean reloadScheduled = new AtomicBoolean();

    public ExternalRecipeService(CraftEngineRecipePlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        Bukkit.getPluginManager().registerEvents(this, this.plugin);
        this.discover();
        this.exportMissingSources();
        this.reloadFiles(true);
        this.startPolling();
    }

    /** 手动执行 /cer reload 时重载文件，并只重试仍然缺失的来源导出。 */
    public void reload() {
        if (this.stopped) return;
        this.discover();
        this.exportMissingSources();
        this.reloadFiles(true);
    }

    /** API 注册集合变化时的轻量刷新；文件加载和快照发布仍受同一套校验保护。 */
    public void reloadApi() {
        if (!this.stopped) this.reloadFiles(false);
    }

    public boolean reexportOnReload() {
        return !this.reexportSources(true).isEmpty();
    }

    public void reexportConfigured(Consumer<ExportReport> completion) {
        this.reexport(this.reexportSources(true), completion);
    }

    /** CraftEngine 重载完成后，仅刷新配置允许自动重建的来源。 */
    public void reexportAfterCraftEngineReload() {
        List<String> requested = this.reexportSources(false);
        if (!requested.isEmpty()) this.reexport(requested, ignored -> {});
    }

    /** 配置中的启用状态变化后，重新读取现有 recipes/，不强制执行来源导出。 */
    public void reloadIndex() {
        this.plugin.tasks().global(() -> this.reloadFiles(false));
    }

    /** 指令入口：先采集数据，再仅替换调用方请求的来源目录。 */
    public void reexport(Collection<String> requested, Consumer<ExportReport> completion) {
        this.plugin.tasks().global(() -> {
            this.discover();
            ExportReport report = this.forceExport(requested);
            this.reloadFiles(false);
            completion.accept(report);
        });
    }

    private List<String> reexportSources(boolean onPluginReload) {
        LinkedHashSet<String> requested = new LinkedHashSet<>();
        String configKey = onPluginReload
                ? "sources.reexport-on-reload"
                : "sources.reexport-craftengine-on-ce-reload";
        if (this.sourceEnabled(CraftEngineRecipeSource.SOURCE_ID)
                && this.plugin.getConfig().getBoolean(configKey, false)) {
            requested.add(CraftEngineRecipeSource.SOURCE_ID);
        }
        for (String source : IntegrationSettings.PROMPTABLE_SOURCES) {
            if (!this.plugin.integrations().enabled(source)) continue;
            boolean enabled = onPluginReload
                    ? this.plugin.integrations().reexportOnReload(source)
                    : this.plugin.integrations().reexportOnCeReload(source);
            if (enabled) requested.add(source);
        }
        return List.copyOf(requested);
    }

    private ExportReport forceExport(Collection<String> requested) {
        Map<String, String> failures = new LinkedHashMap<>();
        Map<String, SourceReport> bySource = new LinkedHashMap<>();
        int files = 0;
        int recipes = 0;
        int sources = 0;
        if (!this.plugin.data().isReady()) {
            failures.put("sources", "CraftEngineRecipe data is not ready");
            return new ExportReport(sources, files, recipes, Map.copyOf(bySource), Map.copyOf(failures));
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        requested.stream().map(id -> id == null ? "" : id.trim().toLowerCase(java.util.Locale.ROOT))
                .filter(id -> !id.isBlank()).forEach(normalized::add);
        for (String sourceId : normalized) {
            if (!this.sourceEnabled(sourceId)) {
                failures.put(sourceId, "source plugin is missing or disabled");
                continue;
            }
            int sourceFiles = 0;
            boolean cultivationExported = false;
            try {
                int cultivation = CultivationDataExporter.export(this.plugin, sourceId);
                if (cultivation > 0) {
                    sourceFiles++;
                    cultivationExported = true;
                }
            } catch (IOException | RuntimeException error) {
                failures.put(sourceId + "-cultivation", error.getMessage());
            }
            RecipeSource recipeSource = this.recipeSource(sourceId);
            if (recipeSource == null) {
                if (cultivationExported) {
                    files += sourceFiles;
                    sources++;
                    bySource.put(sourceId, new SourceReport(sourceFiles, 0, 0));
                } else {
                    failures.put(sourceId, "source plugin is missing or disabled");
                }
                continue;
            }
            try {
                if (!recipeSource.ready()) {
                    failures.put(sourceId, "source plugin is not ready");
                    continue;
                }
                List<DisplayRecipe> captured = List.copyOf(recipeSource.capture());
                Path target = this.recipesDirectory().resolve(sourceId).normalize();
                if (!target.startsWith(this.recipesDirectory().normalize())) {
                    throw new IOException("invalid source directory");
                }
                deleteRecursively(target);
                RecipeFileExporter.Result result = RecipeFileExporter.export(
                        this.recipesDirectory(), sourceId, captured);
                if (!result.installed()) throw new IOException("exporter did not install the refreshed files");
                sourceFiles += result.files();
                files += sourceFiles;
                recipes += result.recipes();
                sources++;
                bySource.put(sourceId, new SourceReport(sourceFiles, result.recipes(),
                        workstationCount(captured)));
                this.failures.remove(sourceId);
            } catch (IOException | RuntimeException | LinkageError error) {
                String message = error.getMessage() == null ? error.toString() : error.getMessage();
                failures.put(sourceId, message);
                this.warn(sourceId, error);
            }
        }
        return new ExportReport(sources, files, recipes, Map.copyOf(bySource), Map.copyOf(failures));
    }

    static int workstationCount(Collection<DisplayRecipe> recipes) {
        return (int) recipes.stream().map(recipe -> recipe.workstation().id()).distinct().count();
    }

    private Adapter adapter(String sourceId) {
        for (Adapter adapter : this.adapters.values()) {
            if (adapter.source.id().equals(sourceId)) return adapter;
        }
        return null;
    }

    private RecipeSource recipeSource(String sourceId) {
        Adapter adapter = this.adapter(sourceId);
        if (adapter != null && this.sourceEnabled(sourceId)) return adapter.source;
        // CraftEngine 是硬依赖，始终从其实时 API 读取，不依赖插件名扫描是否成功。
        if ("craftengine".equals(sourceId)
                && this.sourceEnabled(sourceId)
                && this.plugin.data().isReady()) {
            return new CraftEngineRecipeSource(this.plugin);
        }
        return null;
    }

    private static void deleteRecursively(Path directory) throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return;
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    private void startPolling() {
        long interval = Math.max(10, Math.min(200,
                this.plugin.getConfig().getLong("recipes.poll-interval", 20)));
        this.pollTask = this.plugin.tasks().asyncTimer(this::pollAsync,
                interval * 50L, interval * 50L, TimeUnit.MILLISECONDS);
    }

    private void pollAsync() {
        if (this.stopped || !this.plugin.isEnabled()) return;
        // 来源插件可能晚于 CER 启用；等待 CE 数据就绪后低频重试导出，直到来源集合稳定。
        if (!this.sourcesSettled && this.plugin.data().isReady() && this.sourceCheckTicks++ % 20 == 0
                && this.sourceCheckScheduled.compareAndSet(false, true)) {
            this.plugin.tasks().global(() -> {
                try {
                    this.exportMissingSources();
                } finally {
                    this.sourceCheckScheduled.set(false);
                }
            });
        }
        if (this.plugin.getConfig().getBoolean("recipes.auto-reload", true)) {
            Map<String, String> next = this.fileState();
            if (!next.equals(this.lastFileState)) {
                // 等待连续两次状态一致，避免编辑器一次保存产生多次重载。
                if (this.pendingChanged && next.equals(this.pendingFileState)) {
                    if (this.reloadScheduled.compareAndSet(false, true)) {
                        List<String> changed = changedFiles(this.lastFileState, next);
                        this.plugin.tasks().global(() -> {
                            try {
                                this.reloadFiles(false, changed);
                            } finally {
                                this.reloadScheduled.set(false);
                            }
                        });
                    }
                    this.pendingChanged = false;
                } else {
                    this.pendingChanged = true;
                    this.pendingFileState = next;
                }
            } else {
                this.pendingChanged = false;
                this.pendingFileState = null;
            }
        }
    }

    private void exportMissingSources() {
        if (this.stopped) return;
        // 只有在 CER 捕获 CraftEngine 物品与资源包数据后，才导出依赖界面和翻译标题的来源。
        if (!this.plugin.data().isReady()) return;
        CultivationDataExporter.exportMissing(this.plugin);
        for (var entry : List.copyOf(this.adapters.entrySet())) {
            Adapter adapter = entry.getValue();
            if (!this.sourceEnabled(adapter.source.id())) {
                this.adapters.remove(entry.getKey(), adapter);
                continue;
            }
            if (!this.exportMissing(adapter.source.id())) continue;
            Path target = this.recipesDirectory().resolve(adapter.source.id());
            boolean exported;
            try {
                exported = hasExportedRecipeFiles(target);
            } catch (IOException error) {
                this.warn(adapter.source.id(), error);
                continue;
            }
            if (exported) {
                this.adapters.remove(entry.getKey(), adapter);
                continue;
            }
            try {
                if (!adapter.source.ready()) continue;
                if (Files.exists(target)) deleteRecursively(target);
                List<DisplayRecipe> captured = adapter.source.capture();
                RecipeFileExporter.Result result = RecipeFileExporter.export(
                        this.recipesDirectory(), adapter.source.id(), captured);
                this.adapters.remove(entry.getKey(), adapter);
                this.failures.remove(entry.getKey().getName());
                if (result.installed()) {
                    this.plugin.getLogger().info(this.plugin.lang().text("logs.recipe-source-exported",
                            "source", entry.getKey().getName(), "files", String.valueOf(result.files()),
                            "count", String.valueOf(result.recipes())));
                }
            } catch (IOException | RuntimeException | LinkageError error) {
                this.warn(entry.getKey().getName(), error);
            }
        }
        this.exportMissingCraftEngine();
        this.sourcesSettled = true;
    }

    private void exportMissingCraftEngine() {
        if (!this.plugin.getConfig().getBoolean("sources.craftengine", true)
                || !this.plugin.data().isReady()) return;
        Path target = this.recipesDirectory().resolve(CraftEngineRecipeSource.SOURCE_ID);
        try {
            if (hasExportedRecipeFiles(target)) return;
            if (Files.exists(target)) deleteRecursively(target);
            RecipeSource source = new CraftEngineRecipeSource(this.plugin);
            if (!source.ready()) return;
            List<DisplayRecipe> captured = source.capture();
            RecipeFileExporter.Result result = RecipeFileExporter.export(
                    this.recipesDirectory(), source.id(), captured);
            this.adapters.entrySet().removeIf(entry ->
                    entry.getValue().source.id().equals(CraftEngineRecipeSource.SOURCE_ID));
            if (result.installed()) {
                this.plugin.getLogger().info(this.plugin.lang().text("logs.recipe-source-exported",
                        "source", "CraftEngine", "files", String.valueOf(result.files()),
                        "count", String.valueOf(result.recipes())));
            }
        } catch (IOException | RuntimeException | LinkageError error) {
            this.warn("CraftEngine", error);
        }
    }

    static boolean hasExportedRecipeFiles(Path directory) throws IOException {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return false;
        try (var paths = Files.walk(directory)) {
            return paths.anyMatch(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    && (path.getFileName().toString().endsWith(".yml")
                    || path.getFileName().toString().endsWith(".yaml")));
        }
    }

    private void reloadFiles(boolean initial) {
        this.reloadFiles(initial, List.of());
    }

    private void reloadFiles(boolean initial, List<String> changedFiles) {
        if (this.stopped) return;
        try (var timing = this.plugin.diagnostics().measure(Diagnostics.Operation.SOURCE_REFRESH)) {
            if (this.plugin.getConfig().getBoolean("sources.minecraft", true)) {
                try {
                    BundledMinecraftRecipeSource.installRecipeFiles(this.plugin);
                } catch (IOException | RuntimeException | LinkageError error) {
                    this.warn("minecraft", error);
                }
            }
            Map<String, String> fileState = this.fileState();
            List<DisplayRecipe> recipes = new ArrayList<>(new CustomRecipeLoader(this.plugin).load());
            boolean bundledEnabled = this.plugin.getConfig().getBoolean("sources.minecraft", true)
                    || this.plugin.getConfig().getBoolean("sources.minecraft-cultivation", true)
                    || this.plugin.integrations().cultivation("farmersdelight")
                    || this.plugin.integrations().cultivation("kaleidoscope")
                    || this.plugin.integrations().cultivation("customcrops")
                    || this.plugin.getConfig().getBoolean("sources.craftengine-cultivation", true);
            if (bundledEnabled) {
                try {
                    recipes.addAll(new BundledMinecraftRecipeSource(this.plugin).load());
                    this.failures.remove("minecraft");
                } catch (IOException | RuntimeException | LinkageError error) {
                    // 即使内置数据损坏，也保留可编辑配方可用。
                    this.warn("minecraft", error);
                }
            } else {
                this.failures.remove("minecraft");
            }
            List<RecipeEntry> entries = new ArrayList<>(recipes.stream().map(recipe ->
                    new RecipeEntry(recipe.id().startsWith("minecraft_data:")
                            || recipe.id().startsWith("minecraft_cultivation:") ? "minecraft" : "recipes",
                            null, recipe, () -> !this.stopped)).toList());
            if (this.plugin.recipeApi() != null) {
                // API 与文件配方先合并，再一次性编译成快照；玩家不会看到只更新了一半的索引。
                for (ApiRecipeCompiler.CompiledRecipe recipe
                        : this.plugin.recipeApi().compile(this.plugin.lang().locale())) {
                    entries.add(new RecipeEntry("api:" + recipe.source(), null, recipe.display(),
                            () -> !this.stopped));
                }
            }
            ExternalRecipeSnapshot next = ExternalRecipeSnapshot.compile(entries);
            // 先完整构建索引，再一次性发布快照，读者不会观察到半更新的索引集合。
            this.snapshot = next;
            this.lastFileState = fileState;
            ++this.generation;
            if (this.plugin.recipeIndex() != null) this.plugin.recipeIndex().syncWorkstations();
            this.failures.remove("recipes");
            String files = changedFiles.isEmpty() ? "-" : summarizeFiles(changedFiles);
            this.plugin.getLogger().info(this.plugin.lang().text(
                    initial ? "logs.recipe-source-ready" : "logs.recipe-files-reloaded",
                    "source", "recipes", "count", String.valueOf(recipes.size()), "files", files));
        } catch (IOException | RuntimeException | LinkageError error) {
            this.lastFileState = this.fileState();
            this.warn("recipes", error);
        }
    }

    private Map<String, String> fileState() {
        Path directory = this.recipesDirectory();
        if (!Files.isDirectory(directory)) return Map.of();
        Map<String, String> state = new LinkedHashMap<>();
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(this::isRecipeFile).sorted().toList()) {
                state.put(directory.relativize(path).toString().replace('\\', '/'),
                        // 修改时间与大小足以识别正常编辑；内容哈希会在每次轮询时造成不必要的 I/O。
                        Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis()
                                + ":" + Files.size(path));
            }
        } catch (IOException error) {
            this.warn("recipes-watch", error);
        }
        return Map.copyOf(state);
    }

    private static List<String> changedFiles(Map<String, String> before, Map<String, String> after) {
        LinkedHashSet<String> changed = new LinkedHashSet<>();
        before.forEach((path, state) -> {
            if (!state.equals(after.get(path))) changed.add(path);
        });
        after.keySet().forEach(path -> {
            if (!before.containsKey(path)) changed.add(path);
        });
        return List.copyOf(changed);
    }

    private static String summarizeFiles(List<String> files) {
        if (files.size() <= 20) return String.join(", ", files);
        return String.join(", ", files.subList(0, 20)) + " (+" + (files.size() - 20) + ")";
    }

    private boolean isRecipeFile(Path path) {
        String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        if (name.equals(CultivationDataExporter.FILE_NAME)) return true;
        if (!name.endsWith(".yml") && !name.endsWith(".yaml")) return false;
        Path relative = this.recipesDirectory().relativize(path);
        if (relative.getNameCount() > 1) {
            String source = relative.getName(0).toString().toLowerCase(java.util.Locale.ROOT);
            if (IntegrationSettings.PROMPTABLE_SOURCES.contains(source)
                    && !this.plugin.integrations().enabled(source)) return false;
        }
        for (Path part : relative) {
            String segment = part.toString().toLowerCase(java.util.Locale.ROOT);
            if (segment.equals("example") || segment.equals("examples") || segment.startsWith("example.")
                    || segment.startsWith("example-") || segment.endsWith("-example.yml")
                    || segment.endsWith("-example.yaml")) return false;
        }
        return true;
    }

    private Path recipesDirectory() {
        return this.plugin.getDataFolder().toPath().resolve("recipes");
    }

    private void discover() {
        if (this.stopped) return;
        this.adapters.entrySet().removeIf(entry -> !this.sourceEnabled(entry.getValue().source.id()));
        for (Plugin installed : Bukkit.getPluginManager().getPlugins()) this.attach(installed);
    }

    private void attach(Plugin source) {
        if (!source.isEnabled() || this.adapters.containsKey(source)) return;
        // 插件名可以伪造，先通过主类和实现特征确认来源，再选择对应的反射适配器。
        PluginFingerprints.Fingerprint fingerprint = PluginFingerprints.identify(source).orElse(null);
        if (fingerprint == null) {
            PluginFingerprints.byPluginName(source.getName()).ifPresent(expected -> this.warnMismatch(source, expected));
            return;
        }
        try {
            RecipeSource adapter = switch (fingerprint.sourceId()) {
                case "craftengine" -> this.sourceEnabled("craftengine")
                        ? new CraftEngineRecipeSource(this.plugin) : null;
                case "farmersdelight" -> this.sourceEnabled("farmersdelight")
                        ? new FarmersDelightRecipeSource(this.plugin, source) : null;
                case "kaleidoscope" -> this.sourceEnabled("kaleidoscope")
                        ? new KaleidoscopeRecipeSource(source,
                        dev.che1s.craftenginerecipe.i18n.IntegrationText.of(
                                "kaleidoscope", this.plugin.lang())) : null;
                case "customcrops" -> this.sourceEnabled("customcrops")
                        ? new CustomCropsRecipeSource(source, this.plugin.data()::resourcePackTranslation,
                        this.plugin.data()::itemDisplayName,
                        () -> this.plugin.lang().locale().toLowerCase(java.util.Locale.ROOT).startsWith("zh"),
                        dev.che1s.craftenginerecipe.i18n.IntegrationText.of("customcrops", this.plugin.lang())) : null;
                default -> null;
            };
            if (adapter != null) this.adapters.put(source, new Adapter(adapter));
        } catch (RuntimeException | LinkageError error) {
            this.warn(source.getName(), error);
        }
    }

    private boolean sourceEnabled(String sourceId) {
        if ("craftengine".equals(sourceId)) {
            return this.plugin.getConfig().getBoolean("sources.craftengine", true);
        }
        return this.plugin.integrations().enabled(sourceId);
    }

    private boolean exportMissing(String sourceId) {
        return this.plugin.integrations().exportMissing(sourceId);
    }

    private void warnMismatch(Plugin source, PluginFingerprints.Fingerprint expected) {
        String actual = source.getDescription() == null ? "<unknown>" : source.getDescription().getMain();
        String message = this.plugin.lang().text("logs.plugin-fingerprint-mismatch",
                "plugin", source.getName(), "actual", actual, "expected", expected.mainClass());
        String key = "identity:" + source.getName();
        if (!message.equals(this.failures.put(key, message))) {
            this.plugin.getLogger().warning(message);
        }
    }

    private void warn(String source, Throwable error) {
        String message = error.getMessage() == null ? error.toString() : error.getMessage();
        if (!message.equals(this.failures.put(source, message))) {
            this.plugin.getLogger().warning(this.plugin.lang().text("logs.recipe-source-failed",
                    "source", source, "error", message));
        }
    }

    @EventHandler
    public void onEnable(PluginEnableEvent event) {
        if (event.getPlugin() == this.plugin) return;
        this.plugin.tasks().global(() -> {
            this.attach(event.getPlugin());
            this.sourcesSettled = false;
            this.sourceCheckTicks = 0;
            this.exportMissingSources();
        });
    }

    @EventHandler
    public void onDisable(PluginDisableEvent event) {
        this.adapters.remove(event.getPlugin());
        if (this.plugin.recipeApi() != null) this.plugin.recipeApi().removeOwner(event.getPlugin());
    }

    public ExternalRecipeSnapshot snapshot() {
        return this.snapshot;
    }

    public long generation() {
        return this.generation;
    }

    public void shutdown() {
        this.stopped = true;
        if (this.pollTask != null) this.pollTask.cancel();
        HandlerList.unregisterAll(this);
        this.adapters.clear();
        this.snapshot = ExternalRecipeSnapshot.EMPTY;
        ++this.generation;
    }

    private record Adapter(RecipeSource source) {
    }

    public record SourceReport(int files, int recipes, int workstations) {}

    public record ExportReport(int sources, int files, int recipes,
                               Map<String, SourceReport> bySource,
                               Map<String, String> failures) {}
}
