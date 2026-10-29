package dev.che1s.craftenginerecipe.category;

import dev.che1s.craftenginerecipe.CraftEngineRecipePlugin;
import dev.che1s.craftenginerecipe.config.IntegrationSettings;
import dev.che1s.craftenginerecipe.data.PackMetadataIndex;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 管理 integrations.yml 中的分类预设、作者匹配和自动应用。
 *
 * <p>交互统一由 IntegrationConfigDialogService 提供，本类只提供待处理项、
 * 保存选择以及自动应用能力。</p>
 */
public final class CategoryApplyPromptService implements Listener {
    private final CraftEngineRecipePlugin plugin;
    private final Map<String, Preset> presets = new LinkedHashMap<>();

    public CategoryApplyPromptService(CraftEngineRecipePlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        this.reloadPresets();
        Bukkit.getPluginManager().registerEvents(this, this.plugin);
    }

    /** 数据就绪后自动处理 enabled 预设，并把剩余的待处理项交给统一 Dialog。 */
    public void requestPrompt() {
        if (!this.plugin.isEnabled() || !this.plugin.data().isReady()) return;
        this.plugin.tasks().global(() -> {
            this.applyEnabledPresets();
            this.applyLinkedEnabledPresets();
            if (this.plugin.getConfig().getBoolean("indexing.notify-admins-on-startup", true)) {
                this.plugin.integrationPrompts().requestPrompt();
            }
        });
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        this.requestPrompt();
    }

    public void reloadPresets() {
        this.presets.clear();
        for (Map<?, ?> map : this.plugin.integrations().categoryPresets()) {
            CategoryNamespaces.NamespaceEntry entry = CategoryNamespaces.parseNamespace(map);
            if (entry == null) continue;
            this.presets.putIfAbsent(entry.id(), new Preset(entry,
                    PackMetadataIndex.configuredAuthors(map.get("authors"))));
        }
    }

    public List<PendingPreset> pendingPresets() {
        Set<String> detected = this.plugin.data().detectedNamespaces();
        List<PendingPreset> pending = new ArrayList<>();
        for (Preset preset : this.presets.values()) {
            if (!this.isDialogCandidate(preset, detected)) continue;
            Set<String> actual = this.plugin.data().packAuthors(preset.entry().id());
            pending.add(new PendingPreset(
                    preset.entry().id(),
                    this.plugin.integrations().categoryPresetMode(preset.entry().id()),
                    !this.authorsMatch(preset),
                    List.copyOf(actual),
                    preset.authors()));
        }
        return List.copyOf(pending);
    }

    /** Dialog 展示全部预设，并标记 CE 检测、应用状态和作者状态。 */
    public List<DialogPreset> dialogPresets() {
        Set<String> detected = this.plugin.data().detectedNamespaces();
        List<DialogPreset> values = new ArrayList<>();
        for (Preset preset : this.presets.values()) {
            String namespace = preset.entry().id();
            Set<String> actual = this.plugin.data().packAuthors(namespace);
            values.add(new DialogPreset(
                    namespace,
                    this.plugin.integrations().categoryPresetMode(namespace),
                    detected.contains(namespace),
                    this.plugin.data().namespaces().appliedEntry(namespace) != null,
                    !this.authorsMatch(preset),
                    List.copyOf(actual),
                    preset.authors()));
        }
        return List.copyOf(values);
    }

    /** 保存 Dialog 中对分类预设的逐项选择，返回本次实际应用到 categories.yml 的 ID。 */
    public List<String> applyDialogModes(Map<String, IntegrationSettings.Mode> choices) {
        if (!this.plugin.data().isReady() || choices == null) return List.of();
        Set<String> detected = this.plugin.data().detectedNamespaces();
        List<String> applied = new ArrayList<>();
        for (var choice : choices.entrySet()) {
            Preset preset = this.presets.get(choice.getKey());
            if (preset == null) continue;
            IntegrationSettings.Mode mode = choice.getValue();
            this.plugin.integrations().setCategoryPresetMode(choice.getKey(), mode);
            if (mode != IntegrationSettings.Mode.ENABLED
                    || !detected.contains(choice.getKey())
                    || this.plugin.data().namespaces().appliedEntry(choice.getKey()) != null) continue;
            if (this.applyPreset(preset)) applied.add(choice.getKey());
        }
        return List.copyOf(applied);
    }

    /**
     * 交互式启用适配插件或启动时发现 mode=enabled，都会应用其绑定的预设。
     * 作者不匹配的预设不会自动应用，会留在统一 Dialog 中供管理员核对。
     */
    public List<String> applyLinkedPresets(java.util.Collection<String> sourceIds) {
        if (!this.plugin.data().isReady() || sourceIds == null) return List.of();
        Set<String> detected = this.plugin.data().detectedNamespaces();
        List<String> applied = new ArrayList<>();
        for (String source : sourceIds) {
            for (String namespace : this.plugin.integrations().categoryPresets(source)) {
                Preset preset = this.presets.get(namespace);
                if (preset == null || !detected.contains(namespace) || !this.authorsMatch(preset)
                        || this.plugin.integrations().categoryPresetMode(namespace)
                                == IntegrationSettings.Mode.DISABLED
                        || this.plugin.data().namespaces().appliedEntry(namespace) != null) continue;
                if (this.applyPreset(preset)) applied.add(namespace);
            }
        }
        return List.copyOf(applied);
    }

    private void applyEnabledPresets() {
        Set<String> detected = this.plugin.data().detectedNamespaces();
        List<String> applied = new ArrayList<>();
        for (Preset preset : this.presets.values()) {
            String namespace = preset.entry().id();
            if (this.plugin.integrations().categoryPresetMode(namespace)
                    != IntegrationSettings.Mode.ENABLED
                    || !detected.contains(namespace) || !this.authorsMatch(preset)
                    || this.plugin.data().namespaces().appliedEntry(namespace) != null) continue;
            if (this.applyPreset(preset)) applied.add(namespace);
        }
        if (!applied.isEmpty()) {
            this.plugin.getLogger().info(this.plugin.lang().text(
                    "logs.category-preset-linked-applied",
                    "namespaces", String.join(", ", applied)));
        }
    }

    private void applyLinkedEnabledPresets() {
        List<String> applied = this.applyLinkedPresets(this.plugin.integrations().enabledSources());
        if (applied.isEmpty()) return;
        this.plugin.getLogger().info(this.plugin.lang().text(
                "logs.category-preset-linked-applied",
                "namespaces", String.join(", ", applied)));
    }

    private boolean isDialogCandidate(Preset preset, Set<String> detected) {
        CategoryNamespaces namespaces = this.plugin.data().namespaces();
        String namespace = preset.entry().id();
        if (!detected.contains(namespace) || namespaces.appliedEntry(namespace) != null) return false;
        IntegrationSettings.Mode mode = this.plugin.integrations().categoryPresetMode(namespace);
        if (mode == IntegrationSettings.Mode.DISABLED) return false;
        if (!this.authorsMatch(preset)) return true;
        if (mode == IntegrationSettings.Mode.ENABLED) return false;
        return !this.plugin.integrations().linkedCategoryPresets().contains(namespace);
    }

    private boolean authorsMatch(Preset preset) {
        return PackMetadataIndex.matches(
                this.plugin.data().packAuthors(preset.entry().id()), preset.authors());
    }

    private boolean applyPreset(Preset preset) {
        if (!this.plugin.data().namespaces().applyPreset(preset.entry())) return false;
        this.plugin.integrations().setCategoryPresetMode(
                preset.entry().id(), IntegrationSettings.Mode.ENABLED);
        return true;
    }

    public record PendingPreset(String namespace, IntegrationSettings.Mode mode,
                                boolean authorMismatch, List<String> actualAuthors,
                                List<String> expectedAuthors) {
        public PendingPreset {
            actualAuthors = List.copyOf(actualAuthors);
            expectedAuthors = List.copyOf(expectedAuthors);
        }
    }

    public record DialogPreset(String namespace, IntegrationSettings.Mode mode,
                               boolean detected, boolean applied, boolean authorMismatch,
                               List<String> actualAuthors, List<String> expectedAuthors) {
        public DialogPreset {
            actualAuthors = List.copyOf(actualAuthors);
            expectedAuthors = List.copyOf(expectedAuthors);
        }
    }

    private record Preset(CategoryNamespaces.NamespaceEntry entry, List<String> authors) {
    }
}
