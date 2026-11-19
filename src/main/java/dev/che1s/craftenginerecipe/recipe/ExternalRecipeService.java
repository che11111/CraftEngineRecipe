package dev.che1s.craftenginerecipe.recipe;

import dev.che1s.craftenginerecipe.CraftEngineRecipePlugin;
import dev.che1s.craftenginerecipe.api.DisplayRecipe;
import dev.che1s.craftenginerecipe.api.internal.ApiRecipeCompiler;
import dev.che1s.craftenginerecipe.config.IntegrationSettings;
import dev.che1s.craftenginerecipe.recipe.io.CustomRecipeLoader;
import dev.che1s.craftenginerecipe.recipe.io.RecipeFileExporter;
import dev.che1s.craftenginerecipe.debug.Diagnostics;
import dev.che1s.craftenginerecipe.api.extension.RecipeSource;
import dev.che1s.craftenginerecipe.api.extension.RecipeSourceProvider;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 发布 recipes/ 中的配方。受支持的集成交互只在生成缺失来源目录时读取一次；
 * 生成后绝不会继续把来源插件的实时管理器作为运行时配方来源。
 *
 * <p>文件监视在异步定时器上比较轻量指纹；YAML 读取和解析也留在异步线程，
 * Bukkit 物品转换、索引编译及快照替换切回全局线程。连续两次轮询看到相同
 * 指纹后才重载，以减少编辑器保存时产生的重复刷新。</p>
 */
public final class ExternalRecipeService implements Listener {
    private final CraftEngineRecipePlugin plugin;
    private final Map<String, Adapter> adapters = new ConcurrentHashMap<>();
    private final Map<String, String> failures = new ConcurrentHashMap<>();
    private volatile ExternalRecipeSnapshot snapshot = ExternalRecipeSnapshot.EMPTY;
    private volatile long generation;
    private volatile boolean stopped;
    private volatile Map<String, String> lastFileState = Map.of();
    private volatile CustomRecipeLoader.PreparedDocuments parsedDocuments =
            CustomRecipeLoader.PreparedDocuments.EMPTY;
    private volatile Map<String, String> sourceDirectories = Map.of();
    private volatile Set<String> enabledSourceDirectories = Set.of();
    private Map<String, String> pendingFileState;
    private boolean pendingChanged;
    private ScheduledTask pollTask;
    private final AtomicBoolean sourceCheckScheduled = new AtomicBoolean();
    private final AtomicBoolean sourcesSettled = new AtomicBoolean();
    private final AtomicInteger sourceCheckTicks = new AtomicInteger();
    private final AtomicBoolean reloadScheduled = new AtomicBoolean();
    private final AtomicBoolean loadInProgress = new AtomicBoolean();
    private final AtomicLong loadRevision = new AtomicLong();

    public ExternalRecipeService(CraftEngineRecipePlugin plugin) {
        this.plugin = plugin;
        this.sourceDirectories = this.readSourceDirectories();
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
        this.sourcesSettled.set(false);
        this.sourceCheckTicks.set(0);
        this.discover();
        this.exportMissingSources();
        this.reloadFiles(true);
    }

    /** API 注册集合变化时的轻量刷新；文件加载和快照发布仍受同一套校验保护。 */
    public void reloadApi() {
        if (!this.stopped) this.plugin.tasks().global(() -> this.reloadFiles(false));
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
        this.plugin.tasks().global(() -> {
            this.discover();
            this.reloadFiles(false);
        });
    }

    /** 指令入口：先采集数据，再仅替换调用方请求的来源目录。 */
    public void reexport(Collection<String> requested, Consumer<ExportReport> completion) {
        this.plugin.tasks().global(() -> {
            this.discover();
            ExportReport report = this.forceExport(requested);
            this.reloadFiles(false, List.of(), () -> completion.accept(report));
        });
    }

    private List<String> reexportSources(boolean onPluginReload) {
        LinkedHashSet<String> requested = new LinkedHashSet<>();
        for (String source : this.plugin.extensions().ids()) {
            if (!this.sourceEnabled(source)) continue;
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
            RecipeSource recipeSource = this.recipeSource(sourceId);
            if (recipeSource == null) {
                failures.put(sourceId, "source plugin is missing or disabled");
                continue;
            }
            try {
                if (!recipeSource.ready()) {
                    failures.put(sourceId, "source plugin is not ready");
                    continue;
                }
                List<DisplayRecipe> captured = List.copyOf(recipeSource.capture());
                String outputDirectory = safeOutputDirectory(recipeSource);
                RecipeFileExporter.Result result = RecipeFileExporter.exportReplacing(
                        this.recipesDirectory(), sourceId, outputDirectory, captured);
                if (!result.installed()) throw new IOException("exporter did not install the refreshed files");
                files += result.files();
                recipes += result.recipes();
                sources++;
                bySource.put(sourceId, new SourceReport(result.files(), result.recipes(),
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
        return this.adapters.get(sourceId);
    }

    private RecipeSource recipeSource(String sourceId) {
        Adapter adapter = this.adapter(sourceId);
        if (adapter != null && this.sourceEnabled(sourceId)) return adapter.source();
        return null;
    }

    private String safeOutputDirectory(RecipeSource source) throws IOException {
        String output = source.outputDirectory();
        if (output == null || output.isBlank() || output.startsWith(".")
                || output.contains("..") || output.contains("/")
                || output.contains("\\")) {
            throw new IOException("invalid source output directory: " + output);
        }
        Path target = this.recipesDirectory().resolve(output).normalize();
        if (!target.startsWith(this.recipesDirectory().normalize())) {
            throw new IOException("invalid source output directory: " + output);
        }
        String owner = this.sourceDirectories.get(output);
        if (owner != null && !owner.equals(source.id())) {
            throw new IOException("source output directory " + output + " is owned by " + owner);
        }
        return output;
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
        if (!this.sourcesSettled.get() && this.plugin.data().isReady()
                && Math.floorMod(this.sourceCheckTicks.getAndIncrement(), 20) == 0
                && this.sourceCheckScheduled.compareAndSet(false, true)) {
            this.plugin.tasks().global(() -> {
                try {
                    this.exportMissingSources();
                } finally {
                    this.sourceCheckScheduled.set(false);
                }
            });
        }
        if (!this.loadInProgress.get()
                && this.plugin.getConfig().getBoolean("recipes.auto-reload", true)) {
            Map<String, String> next = this.fileState();
            if (!next.equals(this.lastFileState)) {
                // 等待连续两次状态一致，避免编辑器一次保存产生多次重载。
                if (this.pendingChanged && next.equals(this.pendingFileState)) {
                    if (this.reloadScheduled.compareAndSet(false, true)) {
                        List<String> changed = changedFiles(this.lastFileState, next);
                        this.plugin.tasks().global(() -> {
                            this.reloadFiles(false, changed, () -> this.reloadScheduled.set(false));
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
        boolean awaitingSource = false;
        for (var entry : List.copyOf(this.adapters.entrySet())) {
            String sourceId = entry.getKey();
            Adapter adapter = entry.getValue();
            if (!this.sourceEnabled(sourceId)) {
                this.adapters.remove(entry.getKey(), adapter);
                continue;
            }
            if (!this.exportMissing(sourceId)) continue;
            String outputDirectory;
            try {
                outputDirectory = this.safeOutputDirectory(adapter.source());
            } catch (IOException error) {
                this.warn(sourceId, error);
                continue;
            }
            Path target = this.recipesDirectory().resolve(outputDirectory);
            boolean exported;
            try {
                exported = hasExportedRecipeFiles(target);
            } catch (IOException error) {
                this.warn(sourceId, error);
                continue;
            }
            if (exported) {
                this.adapters.remove(entry.getKey(), adapter);
                continue;
            }
            try {
                if (!adapter.source().ready()) {
                    awaitingSource = true;
                    continue;
                }
                List<DisplayRecipe> captured = adapter.source().capture();
                RecipeFileExporter.Result result = RecipeFileExporter.exportReplacing(
                        this.recipesDirectory(), sourceId, outputDirectory, captured);
                this.adapters.remove(entry.getKey(), adapter);
                this.failures.remove(sourceId);
                if (result.installed()) {
                    this.plugin.getLogger().info(this.plugin.lang().text("logs.recipe-source-exported",
                            "source", this.plugin.extensions().displayName(sourceId),
                            "files", String.valueOf(result.files()),
                            "count", String.valueOf(result.recipes())));
                }
            } catch (IOException | RuntimeException | LinkageError error) {
                this.warn(sourceId, error);
            }
        }
        this.sourcesSettled.set(!awaitingSource);
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
        this.reloadFiles(initial, List.of(), null);
    }

    private void reloadFiles(boolean initial, List<String> changedFiles) {
        this.reloadFiles(initial, changedFiles, null);
    }

    /**
     * 异步读取 YAML，回到全局线程创建 Bukkit 物品并发布完整快照。
     * 调用入口只需完成轻量的配置/文件准备；completion 在本次加载结束或失效后执行。
     */
    private void reloadFiles(boolean initial, List<String> changedFiles, Runnable completion) {
        if (this.stopped) {
            if (completion != null) completion.run();
            return;
        }
        Set<String> enabledDirectories = this.enabledSourceDirectories;
        CustomRecipeLoader loader = new CustomRecipeLoader(this.plugin.getDataFolder().toPath(),
                this.managedSourceDirectories(), enabledDirectories::contains);
        long revision = this.loadRevision.incrementAndGet();
        this.loadInProgress.set(true);
        AtomicBoolean completed = new AtomicBoolean();
        Runnable finish = () -> {
            if (this.loadRevision.get() == revision) this.loadInProgress.set(false);
            if (completion != null && completed.compareAndSet(false, true)) completion.run();
        };
        try {
            this.plugin.tasks().async(() -> {
                if (!this.isCurrentLoad(revision)) {
                    finish.run();
                    return;
                }
                Map<String, String> nextFileState = this.fileState();
                Set<String> actualChanges = new LinkedHashSet<>(
                        changedFiles(this.lastFileState, nextFileState));
                actualChanges.addAll(changedFiles);
                CustomRecipeLoader.PreparedDocuments nextDocuments;
                try {
                    nextDocuments = loader.readDocuments(actualChanges,
                            initial ? null : this.parsedDocuments);
                } catch (IOException | RuntimeException | LinkageError error) {
                    this.finishFailedLoad(revision, nextFileState, error, finish);
                    return;
                }
                if (!this.isCurrentLoad(revision)) {
                    finish.run();
                    return;
                }
                try {
                    this.plugin.tasks().global(() -> {
                        if (!this.isCurrentLoad(revision)) {
                            finish.run();
                            return;
                        }
                        this.applyPreparedFiles(initial, List.copyOf(actualChanges), nextFileState,
                                loader, nextDocuments);
                        finish.run();
                    });
                } catch (IllegalPluginAccessException ignored) {
                    finish.run();
                }
            });
        } catch (IllegalPluginAccessException ignored) {
            finish.run();
        }
    }

    private void finishFailedLoad(long revision, Map<String, String> fileState,
                                  Throwable error, Runnable completion) {
        if (!this.isCurrentLoad(revision)) {
            completion.run();
            return;
        }
        try {
            this.plugin.tasks().global(() -> {
                if (this.isCurrentLoad(revision)) {
                    this.lastFileState = fileState;
                    this.warn("recipes", error);
                }
                completion.run();
            });
        } catch (IllegalPluginAccessException ignored) {
            completion.run();
        }
    }

    private void applyPreparedFiles(boolean initial, List<String> changedFiles,
                                    Map<String, String> fileState, CustomRecipeLoader loader,
                                    CustomRecipeLoader.PreparedDocuments parsed) {
        try (var timing = this.plugin.diagnostics().measure(Diagnostics.Operation.SOURCE_REFRESH)) {
            List<DisplayRecipe> recipes = new ArrayList<>(loader.build(parsed));
            List<RecipeEntry> entries = new ArrayList<>(recipes.stream().map(recipe ->
                    new RecipeEntry(recipe.id().startsWith("minecraft_data:") ? "minecraft" : "recipes",
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
            this.parsedDocuments = parsed;
            ++this.generation;
            if (this.plugin.recipeIndex() != null) this.plugin.recipeIndex().syncWorkstations();
            this.failures.remove("recipes");
            String files = changedFiles.isEmpty() ? "-" : summarizeFiles(changedFiles);
            this.plugin.getLogger().info(this.plugin.lang().text(
                    initial ? "logs.recipe-source-ready" : "logs.recipe-files-reloaded",
                    "source", "recipes", "count", String.valueOf(recipes.size()), "files", files));
        } catch (IOException | RuntimeException | LinkageError error) {
            this.lastFileState = fileState;
            this.warn("recipes", error);
        }
    }

    private boolean isCurrentLoad(long revision) {
        return !this.stopped && this.loadRevision.get() == revision;
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
        if (!name.endsWith(".yml") && !name.endsWith(".yaml")) return false;
        Path relative = this.recipesDirectory().relativize(path);
        if (relative.getNameCount() > 1) {
            String source = relative.getName(0).toString().toLowerCase(java.util.Locale.ROOT);
            if (this.sourceDirectories.containsKey(source)
                    && !this.enabledSourceDirectories.contains(source)) {
                return false;
            }
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
        try {
            this.registerSourceDirectories();
        } catch (RuntimeException error) {
            this.warn("source-directories", error);
        }
        this.enabledSourceDirectories = Set.copyOf(this.sourceDirectories.keySet().stream()
                .filter(this::sourceDirectoryEnabled).toList());
        this.adapters.entrySet().removeIf(entry -> !this.sourceEnabled(entry.getKey()));
        this.attachProviders();
    }

    private void attachProviders() {
        for (RecipeSourceProvider provider : this.plugin.extensions().providers()) {
            String sourceId = provider.id();
            if (this.adapters.containsKey(sourceId) || !this.sourceEnabled(sourceId)) continue;
            Plugin source = this.plugin.extensions().requiredPlugin(provider);
            if (source == null && !provider.requiredPluginName().isBlank()) continue;
            try {
                RecipeSource adapter = provider.create(this.plugin.extensions().context(sourceId, source));
                if (adapter == null) {
                    throw new IllegalStateException("provider returned no source");
                }
                if (!sourceId.equals(adapter.id())) {
                    throw new IllegalStateException("provider id '" + sourceId
                            + "' returned source id '" + adapter.id() + "'");
                }
                String declaredDirectory = provider.outputDirectory();
                if (!declaredDirectory.equals(adapter.outputDirectory())) {
                    this.registerAdapterDirectory(sourceId, adapter.outputDirectory());
                }
                this.adapters.put(sourceId, new Adapter(source, adapter));
            } catch (RuntimeException | LinkageError error) {
                this.warn(sourceId, error);
            }
        }
    }

    private boolean sourceEnabled(String sourceId) {
        return this.plugin.extensions().sourceEnabled(sourceId);
    }

    private void registerSourceDirectories() {
        Map<String, String> discovered = sourceDirectoryIndex(this.plugin.extensions().providers());
        LinkedHashMap<String, String> merged = new LinkedHashMap<>(this.sourceDirectories);
        for (var entry : discovered.entrySet()) {
            String previous = merged.putIfAbsent(entry.getKey(), entry.getValue());
            if (previous != null && !previous.equals(entry.getValue())) {
                throw new IllegalStateException("recipe output directory '" + entry.getKey()
                        + "' is owned by both " + previous + " and " + entry.getValue());
            }
        }
        Map<String, String> next = Map.copyOf(merged);
        if (!next.equals(this.sourceDirectories)) {
            this.sourceDirectories = next;
            this.persistSourceDirectories(next);
        }
    }

    private void registerAdapterDirectory(String sourceId, String outputDirectory) {
        if (outputDirectory == null || outputDirectory.isBlank()
                || outputDirectory.startsWith(".") || outputDirectory.contains("..")
                || outputDirectory.contains("/") || outputDirectory.contains("\\")) {
            throw new IllegalArgumentException("invalid source output directory: "
                    + outputDirectory + " for " + sourceId);
        }
        String owner = this.sourceDirectories.get(outputDirectory);
        if (owner != null && !owner.equals(sourceId)) {
            throw new IllegalStateException("recipe output directory '" + outputDirectory
                    + "' is owned by both " + owner + " and " + sourceId);
        }
        if (owner != null) return;
        LinkedHashMap<String, String> merged = new LinkedHashMap<>(this.sourceDirectories);
        merged.put(outputDirectory, sourceId);
        this.sourceDirectories = Map.copyOf(merged);
        LinkedHashSet<String> enabled = new LinkedHashSet<>(this.enabledSourceDirectories);
        enabled.add(outputDirectory);
        this.enabledSourceDirectories = Set.copyOf(enabled);
        this.persistSourceDirectories(this.sourceDirectories);
    }

    static Map<String, String> sourceDirectoryIndex(
            Collection<RecipeSourceProvider> providers) {
        LinkedHashMap<String, String> directories = new LinkedHashMap<>();
        for (RecipeSourceProvider provider : providers) {
            String sourceId = provider.id();
            String outputDirectory = provider.outputDirectory();
            if (outputDirectory == null || outputDirectory.isBlank()
                    || outputDirectory.startsWith(".") || outputDirectory.contains("..")
                    || outputDirectory.contains("/") || outputDirectory.contains("\\")) {
                throw new IllegalArgumentException("invalid source output directory: "
                        + outputDirectory + " for " + sourceId);
            }
            String previous = directories.putIfAbsent(outputDirectory, sourceId);
            if (previous != null && !previous.equals(sourceId)) {
                throw new IllegalArgumentException("source output directory '" + outputDirectory
                        + "' is declared by both " + previous + " and " + sourceId);
            }
        }
        return Map.copyOf(directories);
    }

    private Map<String, String> readSourceDirectories() {
        Path file = this.sourceDirectoryRegistry();
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return Map.of();
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(file.toFile());
            LinkedHashMap<String, String> directories = new LinkedHashMap<>();
            var section = yaml.getConfigurationSection("directories");
            if (section != null) {
                for (String source : section.getKeys(false)) {
                    String directory = section.getString(source, "").trim();
                    if (directory.isBlank() || directory.startsWith(".")
                            || directory.contains("..") || directory.contains("/")
                            || directory.contains("\\")) continue;
                    directories.put(directory, source);
                }
            }
            return Map.copyOf(directories);
        } catch (IOException | InvalidConfigurationException | RuntimeException error) {
            this.plugin.getLogger().warning("Cannot read source directory registry " + file
                    + ": " + error.getMessage());
            return Map.of();
        }
    }

    private void persistSourceDirectories(Map<String, String> directories) {
        Path file = this.sourceDirectoryRegistry();
        try {
            Files.createDirectories(file.getParent());
            YamlConfiguration yaml = new YamlConfiguration();
            directories.entrySet().stream()
                    .sorted(Map.Entry.comparingByValue())
                    .forEach(entry -> yaml.set("directories." + entry.getValue(), entry.getKey()));
            Path temporary = Files.createTempFile(file.getParent(), ".source-directories-", ".tmp");
            try {
                Files.writeString(temporary, yaml.saveToString(), StandardCharsets.UTF_8);
                try {
                    Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException error) {
            this.warn("source-directories", error);
        }
    }

    private Path sourceDirectoryRegistry() {
        return this.plugin.getDataFolder().toPath()
                .resolve("extensions-data").resolve("source-directories.yml");
    }

    private Set<String> managedSourceDirectories() {
        return this.sourceDirectories.keySet();
    }

    private boolean sourceDirectoryEnabled(String directory) {
        String source = this.sourceDirectories.get(directory);
        return source != null && this.sourceEnabled(source);
    }

    private boolean exportMissing(String sourceId) {
        return this.plugin.integrations().exportMissing(sourceId);
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
            this.attachProviders();
            this.sourcesSettled.set(false);
            this.sourceCheckTicks.set(0);
            this.exportMissingSources();
        });
    }

    @EventHandler
    public void onDisable(PluginDisableEvent event) {
        this.adapters.entrySet().removeIf(entry -> entry.getValue().plugin() == event.getPlugin());
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
        this.loadRevision.incrementAndGet();
        this.loadInProgress.set(false);
        if (this.pollTask != null) this.pollTask.cancel();
        HandlerList.unregisterAll(this);
        this.adapters.clear();
        this.parsedDocuments = CustomRecipeLoader.PreparedDocuments.EMPTY;
        this.snapshot = ExternalRecipeSnapshot.EMPTY;
        ++this.generation;
    }

    private record Adapter(Plugin plugin, RecipeSource source) {
    }

    public record SourceReport(int files, int recipes, int workstations) {}

    public record ExportReport(int sources, int files, int recipes,
                               Map<String, SourceReport> bySource,
                               Map<String, String> failures) {}
}
