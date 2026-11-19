package dev.che1s.craftenginerecipe.integration.extension;

import dev.che1s.craftenginerecipe.CraftEngineRecipePlugin;
import dev.che1s.craftenginerecipe.api.extension.RecipeSourceProvider;
import dev.che1s.craftenginerecipe.api.extension.SourceContext;
import dev.che1s.craftenginerecipe.api.extension.SourceText;
import dev.che1s.craftenginerecipe.api.DisplayRecipe;
import dev.che1s.craftenginerecipe.i18n.IntegrationText;
import dev.che1s.craftenginerecipe.recipe.io.CustomRecipeLoader;
import dev.che1s.craftenginerecipe.util.PackagedNameOverrides;
import org.bukkit.Bukkit;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.io.FilterInputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * Discovers optional recipe-source extensions from {@code plugins/CraftEngineRecipe/extensions}.
 *
 * <p>Each JAR is loaded with its own class loader whose parent is the core plugin loader. The
 * provider class is selected by manifest metadata, validated before instantiation, and retained
 * until plugin shutdown. A replacement JAR requires a server restart; closing a URL class loader
 * does not hot-unload classes already referenced by providers.</p>
 */
public final class ExtensionManager {
    public static final String DIRECTORY_NAME = "extensions";
    public static final String MANIFEST_PROVIDER_CLASS = "CraftEngineRecipe-Provider-Class";
    public static final String MANIFEST_API_VERSION = "CraftEngineRecipe-Api-Version";
    public static final String MANIFEST_MINECRAFT = "CraftEngineRecipe-Minecraft";
    public static final String DEFINITION_RESOURCE = "extension.yml";
    public static final String WORKSTATION_PRESETS_RESOURCE = "workstation-presets.yml";
    public static final String CATEGORY_PRESETS_RESOURCE = "category-presets.yml";

    private final CraftEngineRecipePlugin plugin;
    private final Path directory;
    private final Map<String, LoadedExtension> extensions = new LinkedHashMap<>();
    private final List<UnavailableExtension> unavailable = new ArrayList<>();
    private final Map<String, String> resourcePackOwners = new LinkedHashMap<>();
    private List<String> jarFingerprint = List.of();

    public ExtensionManager(CraftEngineRecipePlugin plugin) {
        this.plugin = plugin;
        this.directory = plugin.getDataFolder().toPath().resolve(DIRECTORY_NAME);
    }

    public void load() {
        try {
            Files.createDirectories(this.directory);
            this.load(this.scanJars());
        } catch (IOException error) {
            this.plugin.getLogger().warning("Cannot create extension directory " + this.directory + ": "
                    + error.getMessage());
        }
    }

    /**
     * Reloads providers only when the extension directory's JAR set or contents changed.
     */
    public boolean refreshIfChanged() {
        try {
            Files.createDirectories(this.directory);
            List<Path> jars = this.scanJars();
            List<String> nextFingerprint = fingerprint(jars);
            if (nextFingerprint.equals(this.jarFingerprint)) return false;
            this.load(jars);
            return true;
        } catch (IOException error) {
            this.plugin.getLogger().warning("Cannot scan extension directory " + this.directory + ": "
                    + error.getMessage());
            return false;
        }
    }

    private List<Path> scanJars() throws IOException {
        try (var paths = Files.list(this.directory)) {
            return paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }
    }

    private void load(List<Path> jars) throws IOException {
        this.close();
        this.unavailable.clear();
        this.resourcePackOwners.clear();
        this.jarFingerprint = fingerprint(jars);
        for (Path jar : jars) {
            this.loadJar(jar);
        }
        this.plugin.getLogger().info("Loaded " + this.extensions.size()
                + " CraftEngineRecipe extension(s) from " + this.directory);
    }

    static List<String> fingerprint(List<Path> jars) throws IOException {
        List<String> values = new ArrayList<>(jars.size());
        for (Path jar : jars) {
            values.add(jar.getFileName() + ":" + Files.size(jar) + ":"
                    + Files.getLastModifiedTime(jar).toMillis());
        }
        return List.copyOf(values);
    }

    private void loadJar(Path jar) {
        URLClassLoader loader = null;
        RecipeSourceProvider provider = null;
        String providerClass = null;
        String id = canonicalJarName(jar.getFileName().toString()).replaceFirst("(?i)\\.jar$", "");
        String displayName = id;
        String version = "unknown";
        try (JarFile file = new JarFile(jar.toFile(), false)) {
            Manifest manifest = file.getManifest();
            if (manifest == null) {
                throw new IllegalArgumentException("missing manifest");
            }
            Attributes attributes = manifest.getMainAttributes();
            providerClass = required(attributes, MANIFEST_PROVIDER_CLASS);
            int apiVersion = integer(attributes, MANIFEST_API_VERSION, -1);
            version = attributes.getValue(Attributes.Name.IMPLEMENTATION_VERSION);
            if (version == null || version.isBlank()) version = "unknown";
            if (apiVersion != RecipeSourceProvider.API_VERSION) {
                throw new IllegalArgumentException("extension API " + apiVersion
                        + " is incompatible with core API " + RecipeSourceProvider.API_VERSION);
            }
            this.checkMinecraftCompatibility(attributes.getValue(MANIFEST_MINECRAFT));

            loader = new URLClassLoader(new URL[]{jar.toUri().toURL()}, this.plugin.getClass().getClassLoader());
            Class<?> loaded = Class.forName(providerClass, true, loader);
            if (!RecipeSourceProvider.class.isAssignableFrom(loaded)) {
                throw new IllegalArgumentException(providerClass + " does not implement RecipeSourceProvider");
            }
            provider = (RecipeSourceProvider) loaded.getDeclaredConstructor().newInstance();
            if (provider.requiredApiVersion() != RecipeSourceProvider.API_VERSION) {
                throw new IllegalArgumentException("provider requires API " + provider.requiredApiVersion());
            }
            id = provider.id();
            displayName = provider.displayName();
            if (id == null || !id.matches("[a-z0-9_.-]+")) {
                throw new IllegalArgumentException("invalid provider id: " + id);
            }
            if (this.extensions.containsKey(id)) {
                throw new IllegalArgumentException("duplicate provider id '" + id + "' in "
                        + this.extensions.get(id).jar().getFileName());
            }
            ExtensionDefinition definition = this.loadDefinition(loader, jar, id);
            this.registerDefinition(id, jar, definition);
            this.extensions.put(id, new LoadedExtension(
                    id, jar, loader, provider, version, definition));
        } catch (ReflectiveOperationException | LinkageError | IOException | RuntimeException error) {
            if (loader != null) {
                try {
                    loader.close();
                } catch (IOException ignored) {
                }
            }
            String reason = error.getMessage() == null ? error.toString() : error.getMessage();
            this.unavailable.add(new UnavailableExtension(id, displayName, jar, version, reason));
            this.plugin.getLogger().warning("Cannot load extension " + jar.getFileName() + ": "
                    + reason);
        }
    }

    private void checkMinecraftCompatibility(String declaration) {
        if (declaration == null || declaration.isBlank() || declaration.trim().equals("*")) return;
        String current = Bukkit.getBukkitVersion();
        String normalized = current == null ? "" : current.toLowerCase(Locale.ROOT);
        boolean compatible = List.of(declaration.split("\\s*,\\s*")).stream()
                .map(value -> value.toLowerCase(Locale.ROOT))
                .anyMatch(value -> normalized.equals(value) || normalized.startsWith(value + "-")
                        || normalized.startsWith(value + "."));
        if (!compatible) {
            throw new IllegalArgumentException("Minecraft " + current
                    + " is not listed as compatible by " + declaration);
        }
    }

    private static String required(Attributes attributes, String key) {
        String value = attributes.getValue(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("manifest is missing " + key);
        }
        return value.trim();
    }

    private static int integer(Attributes attributes, String key, int fallback) {
        String value = attributes.getValue(key);
        if (value == null || value.isBlank()) return fallback;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    public List<RecipeSourceProvider> providers() {
        return this.extensions.values().stream().map(LoadedExtension::provider).toList();
    }

    public List<String> ids() {
        return List.copyOf(this.extensions.keySet());
    }

    public RecipeSourceProvider provider(String id) {
        LoadedExtension extension = this.extensions.get(id);
        return extension == null ? null : extension.provider();
    }

    public String displayName(String id) {
        RecipeSourceProvider provider = this.provider(id);
        return provider == null ? id : provider.displayName();
    }

    public String displayName(String id, String locale) {
        LoadedExtension extension = this.extensions.get(id);
        if (extension == null) return id;
        return extension.definition().localizedName(locale, extension.provider().displayName());
    }

    public String description(String id, String locale) {
        LoadedExtension extension = this.extensions.get(id);
        return extension == null ? ""
                : extension.definition().localizedDescription(locale, "");
    }

    public List<String> supportedLanguages(String id) {
        LoadedExtension extension = this.extensions.get(id);
        return extension == null ? List.of() : extension.definition().supportedLanguages();
    }

    public List<String> integrationButtonIcons(String id) {
        LoadedExtension extension = this.extensions.get(id);
        return extension == null ? List.of() : extension.definition().buttonIcons();
    }

    public List<String> resourcePacks(String id) {
        LoadedExtension extension = this.extensions.get(id);
        return extension == null ? List.of() : extension.definition().resourcePacks();
    }

    public List<String> resourcePackIds() {
        LinkedHashSet<String> packs = new LinkedHashSet<>();
        this.extensions.values().forEach(extension ->
                packs.addAll(extension.definition().resourcePacks()));
        return List.copyOf(packs);
    }

    public List<String> languageSources() {
        LinkedHashSet<String> sources = new LinkedHashSet<>();
        this.extensions.values().stream()
                .map(extension -> extension.definition().languageSource())
                .filter(value -> value != null && !value.isBlank())
                .forEach(sources::add);
        return List.copyOf(sources);
    }

    public java.io.InputStream languageResource(String source, String locale) {
        LoadedExtension extension = this.extensions.values().stream()
                .filter(loaded -> source.equals(loaded.definition().languageSource()))
                .findFirst().orElse(null);
        if (extension == null) return null;
        try {
            return jarResource(extension.jar(), "lang/" + locale + ".yml");
        } catch (IOException error) {
            this.plugin.getLogger().warning("Cannot read language resource lang/" + locale + ".yml from "
                    + extension.jar().getFileName() + ": " + error.getMessage());
            return null;
        }
    }

    static java.io.InputStream jarResource(Path jarPath, String entryName) throws IOException {
        JarFile jar = new JarFile(jarPath.toFile());
        var entry = jar.getJarEntry(entryName);
        if (entry == null || entry.isDirectory()) {
            jar.close();
            return null;
        }
        return new FilterInputStream(jar.getInputStream(entry)) {
            @Override
            public void close() throws IOException {
                try {
                    super.close();
                } finally {
                    jar.close();
                }
            }
        };
    }

    private ExtensionDefinition loadDefinition(URLClassLoader loader, Path jar, String providerId) {
        try (var stream = loader.getResourceAsStream(DEFINITION_RESOURCE)) {
            if (stream == null) {
                this.plugin.getLogger().warning("Extension " + jar.getFileName()
                        + " has no " + DEFINITION_RESOURCE + "; using core icon fallback");
                return ExtensionDefinition.EMPTY;
            }
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
            int schema = yaml.getInt("schema", 1);
            if (schema != 1) {
                throw new IllegalArgumentException("unsupported extension.yml schema " + schema);
            }
            String declaredId = yaml.getString("id", "").trim();
            if (!declaredId.isBlank() && !declaredId.equals(providerId)) {
                throw new IllegalArgumentException("extension.yml id '" + declaredId
                        + "' does not match provider id '" + providerId + "'");
            }
            Map<String, String> names = localizedText(yaml, "names");
            Map<String, String> descriptions = localizedText(yaml, "descriptions");
            List<String> authors = stringList(yaml, "authors");
            List<String> supportedLanguages = stringList(yaml, "supported-languages").stream()
                    .map(String::toLowerCase)
                    .distinct()
                    .toList();
            String languageSource = yaml.getString("language-source", "").trim();
            if (!languageSource.isBlank() && !languageSource.matches("[a-z0-9_.-]+")) {
                throw new IllegalArgumentException("invalid language-source: " + languageSource);
            }
            List<String> resourcePacks = stringList(yaml, "resource-packs");
            for (String pack : resourcePacks) {
                if (!pack.matches("[a-z0-9_-]+")) {
                    throw new IllegalArgumentException("invalid resource pack id: " + pack);
                }
            }
            List<PluginDependency> dependencies = new ArrayList<>();
            for (Map<?, ?> raw : yaml.getMapList("dependencies.plugins")) {
                String name = string(raw.get("name"));
                if (name.isBlank()) continue;
                dependencies.add(new PluginDependency(
                        name,
                        stringList(raw.get("main-classes")),
                        stringList(raw.get("package-prefixes")),
                        stringList(raw.get("authors")),
                        string(raw.get("version")),
                        bool(raw.get("primary"))));
            }
            List<String> icons = yaml.getStringList("integration-button.icons").stream()
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .filter(value -> value.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))
                    .distinct()
                    .toList();
            IntegrationDefaults integrationDefaults = this.loadIntegrationDefaults(yaml);
            WorkstationPresentation presentation = loadWorkstationPresets(loader, jar);
            List<Map<String, Object>> categoryPresets = loadCategoryPresets(loader, jar);
            return new ExtensionDefinition(declaredId, names, descriptions, authors,
                    supportedLanguages, languageSource, resourcePacks, dependencies, icons,
                    integrationDefaults, presentation, categoryPresets);
        } catch (IOException | InvalidConfigurationException | RuntimeException error) {
            this.plugin.getLogger().warning("Cannot read " + DEFINITION_RESOURCE + " from "
                    + jar.getFileName() + ": " + error.getMessage());
            return ExtensionDefinition.EMPTY;
        }
    }

    private IntegrationDefaults loadIntegrationDefaults(YamlConfiguration yaml) {
        var section = yaml.getConfigurationSection("integration-defaults");
        if (section == null) return IntegrationDefaults.EMPTY;
        String mode = section.getString("mode", "").trim().toLowerCase(Locale.ROOT);
        if (!mode.isBlank() && !List.of("ask", "enabled", "disabled").contains(mode)) {
            throw new IllegalArgumentException("invalid integration-defaults.mode: " + mode);
        }
        Map<String, Boolean> options = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            if (key.equals("mode") || section.isConfigurationSection(key)) continue;
            options.put(key, bool(section.get(key)));
        }
        return new IntegrationDefaults(mode, options);
    }

    public WorkstationTitleStyle workstationTitleStyle(String stationId, String locale) {
        if (stationId == null || stationId.isBlank()) return WorkstationTitleStyle.DEFAULT;
        WorkstationPresentation best = null;
        int bestLength = -1;
        for (LoadedExtension extension : this.extensions.values()) {
            WorkstationPresentation presentation = extension.definition().presentation();
            for (String prefix : presentation.stationPrefixes()) {
                if (stationId.startsWith(prefix) && prefix.length() > bestLength) {
                    best = presentation;
                    bestLength = prefix.length();
                }
            }
        }
        return best == null ? WorkstationTitleStyle.DEFAULT : best.style(locale);
    }

    private WorkstationPresentation loadWorkstationPresets(URLClassLoader loader, Path jar) {
        try (var stream = loader.getResourceAsStream(WORKSTATION_PRESETS_RESOURCE)) {
            if (stream == null) return WorkstationPresentation.EMPTY;
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
            int schema = yaml.getInt("schema", 1);
            if (schema != 1) {
                throw new IllegalArgumentException("unsupported workstation-presets.yml schema " + schema);
            }
            List<String> navigation = yaml.getMapList("navigation").stream()
                    .map(value -> string(value.get("id")))
                    .filter(value -> !value.isBlank())
                    .distinct()
                    .toList();
            List<String> prefixes = yaml.getStringList("presentation.station-prefixes").stream()
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .distinct()
                    .toList();
            Map<String, WorkstationTitleStyle> styles = new LinkedHashMap<>();
            var section = yaml.getConfigurationSection("presentation.styles");
            if (section != null) {
                for (String key : section.getKeys(false)) {
                    String path = key + ".";
                    styles.put(key.toLowerCase(Locale.ROOT), new WorkstationTitleStyle(
                            section.getBoolean(path + "remove-spaces", false),
                            section.getBoolean(path + "bold", false)));
                }
            }
            return new WorkstationPresentation(navigation, prefixes, styles);
        } catch (IOException | InvalidConfigurationException | RuntimeException error) {
            this.plugin.getLogger().warning("Cannot read " + WORKSTATION_PRESETS_RESOURCE + " from "
                    + jar.getFileName() + ": " + error.getMessage());
            return WorkstationPresentation.EMPTY;
        }
    }

    private List<Map<String, Object>> loadCategoryPresets(URLClassLoader loader, Path jar) {
        try (var stream = loader.getResourceAsStream(CATEGORY_PRESETS_RESOURCE)) {
            if (stream == null) return List.of();
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
            int schema = yaml.getInt("schema", 1);
            if (schema != 1) {
                throw new IllegalArgumentException("unsupported category-presets.yml schema " + schema);
            }
            List<Map<String, Object>> presets = new ArrayList<>();
            for (Map<?, ?> raw : yaml.getMapList("presets")) {
                Map<String, Object> copied = new LinkedHashMap<>();
                raw.forEach((key, value) -> copied.put(String.valueOf(key), value));
                if (!string(copied.get("id")).isBlank()) presets.add(Map.copyOf(copied));
            }
            return List.copyOf(presets);
        } catch (IOException | InvalidConfigurationException | RuntimeException error) {
            this.plugin.getLogger().warning("Cannot read " + CATEGORY_PRESETS_RESOURCE + " from "
                    + jar.getFileName() + ": " + error.getMessage());
            return List.of();
        }
    }

    public List<BundledCategoryPreset> bundledCategoryPresets() {
        List<BundledCategoryPreset> presets = new ArrayList<>();
        for (LoadedExtension extension : this.extensions.values()) {
            for (Map<String, Object> definition : extension.definition().categoryPresets()) {
                presets.add(new BundledCategoryPreset(extension.id(), canonicalJarName(
                        extension.jar().getFileName().toString()),
                        definition));
            }
        }
        return List.copyOf(presets);
    }

    public List<ManagedWorkstationPreset> workstationPresets() {
        List<ManagedWorkstationPreset> presets = new ArrayList<>();
        for (LoadedExtension extension : this.extensions.values()) {
            if (!this.isActive(extension)) continue;
            for (String id : extension.definition().presentation().navigation()) {
                presets.add(new ManagedWorkstationPreset(id, canonicalJarName(
                        extension.jar().getFileName().toString())));
            }
        }
        return List.copyOf(presets);
    }

    public java.util.Set<String> activeManagedJarNames() {
        java.util.LinkedHashSet<String> jars = new java.util.LinkedHashSet<>();
        for (LoadedExtension extension : this.extensions.values()) {
            if (this.isActive(extension)) {
                jars.add(canonicalJarName(extension.jar().getFileName().toString()));
            }
        }
        return java.util.Set.copyOf(jars);
    }

    /**
     * Finds the active extension owning a workstation. Explicit navigation entries win over
     * presentation prefixes, and the longest matching prefix wins between extensions.
     */
    public String managedByForWorkstation(String stationId) {
        return this.workstationOwner(stationId, true);
    }

    /** Ownership including disabled extensions, used to avoid re-adding their discovered stations. */
    public String configuredOwnerForWorkstation(String stationId) {
        return this.workstationOwner(stationId, false);
    }

    private String workstationOwner(String stationId, boolean activeOnly) {
        if (stationId == null || stationId.isBlank()) return "";
        String explicit = "";
        Map<String, List<String>> prefixes = new LinkedHashMap<>();
        for (LoadedExtension extension : this.extensions.values()) {
            if (activeOnly && !this.isActive(extension)) continue;
            if (extension.definition().presentation().navigation().contains(stationId)) {
                if (explicit.isBlank()) {
                    explicit = canonicalJarName(extension.jar().getFileName().toString());
                }
            }
            if (!extension.definition().presentation().stationPrefixes().isEmpty()) {
                prefixes.put(canonicalJarName(extension.jar().getFileName().toString()),
                        extension.definition().presentation().stationPrefixes());
            }
        }
        return !explicit.isBlank() ? explicit : longestMatchingPrefixOwner(stationId, prefixes);
    }

    static String longestMatchingPrefixOwner(String stationId,
                                             Map<String, ? extends Collection<String>> prefixesByJar) {
        if (stationId == null || prefixesByJar == null) return "";
        String owner = "";
        int longest = -1;
        for (var entry : prefixesByJar.entrySet()) {
            for (String prefix : entry.getValue()) {
                if (prefix != null && !prefix.isBlank() && stationId.startsWith(prefix)
                        && prefix.length() > longest) {
                    owner = entry.getKey();
                    longest = prefix.length();
                }
            }
        }
        return owner;
    }

    /**
     * Removes the release version suffix while retaining the stable extension filename used by
     * managed category and workstation ownership markers.
     */
    static String canonicalJarName(String fileName) {
        if (fileName == null || fileName.isBlank()) return "";
        return fileName.trim().replaceFirst(
                "(?i)(?:_v\\d[0-9A-Za-z._+-]*)?\\.jar$", ".jar");
    }

    public String bundledCategoryOwner(String namespace) {
        for (LoadedExtension extension : this.extensions.values()) {
            for (Map<String, Object> definition : extension.definition().categoryPresets()) {
                if (namespace != null && namespace.equals(string(definition.get("id")))) {
                    return canonicalJarName(extension.jar().getFileName().toString());
                }
            }
        }
        return "";
    }

    private boolean isActive(LoadedExtension extension) {
        return effectiveMode(extension.id())
                == dev.che1s.craftenginerecipe.config.IntegrationSettings.Mode.ENABLED
                && this.installationProblem(extension.provider()) == null;
    }

    private static Map<String, String> localizedText(YamlConfiguration yaml, String path) {
        var section = yaml.getConfigurationSection(path);
        if (section == null) return Map.of();
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        for (String locale : section.getKeys(false)) {
            String text = section.getString(locale);
            if (text != null && !text.isBlank()) values.put(locale, text.trim());
        }
        return Map.copyOf(values);
    }

    private static List<String> stringList(YamlConfiguration yaml, String path) {
        return yaml.getStringList(path).stream()
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
    }

    private static List<String> stringList(Object value) {
        if (value instanceof Collection<?> collection) {
            return collection.stream().map(ExtensionManager::string)
                    .filter(text -> !text.isBlank()).distinct().toList();
        }
        String text = string(value);
        return text.isBlank() ? List.of() : List.of(text);
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static boolean bool(Object value) {
        return value instanceof Boolean bool ? bool : Boolean.parseBoolean(string(value));
    }

    public LoadedExtension extension(String id) {
        return this.extensions.get(id);
    }

    /**
     * Collects language fallbacks bundled by installed source extensions. Extensions should only
     * provide keys missing from the source pack; core applies them after the source translations.
     */
    public Map<String, Map<String, String>> itemNameOverrides() {
        Map<String, Map<String, String>> merged = new LinkedHashMap<>();
        for (LoadedExtension extension : List.copyOf(this.extensions.values())) {
            try {
                Map<String, Map<String, String>> values = PackagedNameOverrides.readJar(
                        extension.jar(), "item-name-overrides/default.json");
                for (var locale : values.entrySet()) {
                    merged.computeIfAbsent(locale.getKey(), ignored -> new LinkedHashMap<>())
                            .putAll(locale.getValue());
                }
            } catch (IOException | RuntimeException error) {
                this.plugin.getLogger().warning("Cannot read item name overrides from "
                        + extension.jar().getFileName() + ": " + error.getMessage());
            }
        }
        Map<String, Map<String, String>> immutable = new LinkedHashMap<>();
        merged.forEach((locale, values) -> immutable.put(locale, Map.copyOf(values)));
        return Map.copyOf(immutable);
    }

    public Plugin requiredPlugin(RecipeSourceProvider provider) {
        if (provider == null || this.installationProblem(provider.id()) != null) return null;
        LoadedExtension extension = this.extensions.get(provider.id());
        if (extension != null) {
            PluginDependency primary = extension.definition().pluginDependencies().stream()
                    .filter(PluginDependency::primary).findFirst().orElse(null);
            if (primary != null) return Bukkit.getPluginManager().getPlugin(primary.name());
        }
        String name = provider.requiredPluginName();
        if (name == null || name.isBlank()) return null;
        Plugin source = Bukkit.getPluginManager().getPlugin(name);
        if (source == null || !source.isEnabled() || source.getDescription() == null) return null;
        String expectedMain = provider.requiredPluginMainClass();
        if (expectedMain != null && !expectedMain.isBlank()
                && List.of(expectedMain.split("\\s*,\\s*")).stream()
                .noneMatch(value -> value.equals(source.getDescription().getMain()))) {
            return null;
        }
        return source;
    }

    public boolean isInstalled(RecipeSourceProvider provider) {
        return this.installationProblem(provider) == null;
    }

    public String installationProblem(RecipeSourceProvider provider) {
        return provider == null ? "Provider is unavailable" : this.installationProblem(provider.id());
    }

    public String installationProblem(String id) {
        LoadedExtension extension = this.extensions.get(id);
        if (extension == null) return "Extension is not loaded";
        for (PluginDependency dependency : extension.definition().pluginDependencies()) {
            String problem = this.dependencyProblem(dependency);
            if (problem != null) return problem;
        }
        RecipeSourceProvider provider = extension.provider();
        String name = provider.requiredPluginName();
        if (name == null || name.isBlank()) return null;
        Plugin source = Bukkit.getPluginManager().getPlugin(name);
        if (source == null || !source.isEnabled() || source.getDescription() == null) {
            return "Required plugin " + name + " is missing or disabled";
        }
        String expectedMain = provider.requiredPluginMainClass();
        if (expectedMain != null && !expectedMain.isBlank()
                && List.of(expectedMain.split("\\s*,\\s*")).stream()
                .noneMatch(value -> value.equals(source.getDescription().getMain()))) {
            return "Plugin " + name + " has main class " + source.getDescription().getMain()
                    + " instead of " + expectedMain;
        }
        return null;
    }

    private String dependencyProblem(PluginDependency dependency) {
        Plugin source = Bukkit.getPluginManager().getPlugin(dependency.name());
        if (source == null || !source.isEnabled() || source.getDescription() == null) {
            return "Required plugin " + dependency.name() + " is missing or disabled";
        }
        String main = source.getDescription().getMain();
        if (!dependency.mainClasses().isEmpty()
                && dependency.mainClasses().stream().noneMatch(main::equals)) {
            return "Plugin " + dependency.name() + " has main class " + main
                    + " instead of " + dependency.mainClasses();
        }
        if (!dependency.packagePrefixes().isEmpty()
                && dependency.packagePrefixes().stream().noneMatch(main::startsWith)) {
            return "Plugin " + dependency.name() + " main class " + main
                    + " is outside " + dependency.packagePrefixes();
        }
        if (!dependency.authors().isEmpty()
                && dependency.authors().stream().noneMatch(expected ->
                source.getDescription().getAuthors().stream().anyMatch(expected::equalsIgnoreCase))) {
            return "Plugin " + dependency.name() + " authors "
                    + source.getDescription().getAuthors() + " do not match "
                    + dependency.authors();
        }
        String actualVersion = source.getDescription().getVersion();
        if (!versionMatches(actualVersion, dependency.version())) {
            return "Plugin " + dependency.name() + " version " + actualVersion
                    + " does not satisfy " + dependency.version();
        }
        return null;
    }

    static boolean versionMatches(String actual, String constraint) {
        if (constraint == null || constraint.isBlank() || constraint.trim().equals("*")) return true;
        if (actual == null || actual.isBlank()) return false;
        for (String part : constraint.trim().split("\\s+")) {
            String operator = "=";
            String expected = part;
            for (String candidate : List.of(">=", "<=", ">", "<", "=")) {
                if (part.startsWith(candidate)) {
                    operator = candidate;
                    expected = part.substring(candidate.length());
                    break;
                }
            }
            int comparison = compareVersions(actual, expected);
            boolean matches = switch (operator) {
                case ">=" -> comparison >= 0;
                case "<=" -> comparison <= 0;
                case ">" -> comparison > 0;
                case "<" -> comparison < 0;
                default -> comparison == 0;
            };
            if (!matches) return false;
        }
        return true;
    }

    private static int compareVersions(String left, String right) {
        String[] leftParts = normalizedVersion(left).split("\\.");
        String[] rightParts = normalizedVersion(right).split("\\.");
        int length = Math.max(leftParts.length, rightParts.length);
        for (int index = 0; index < length; index++) {
            int leftValue = index < leftParts.length ? leadingInteger(leftParts[index]) : 0;
            int rightValue = index < rightParts.length ? leadingInteger(rightParts[index]) : 0;
            int result = Integer.compare(leftValue, rightValue);
            if (result != 0) return result;
        }
        return 0;
    }

    private static String normalizedVersion(String version) {
        String value = version == null ? "" : version.trim().toLowerCase(Locale.ROOT);
        int start = 0;
        while (start < value.length() && !Character.isDigit(value.charAt(start))) start++;
        return start == value.length() ? "0" : value.substring(start);
    }

    private static int leadingInteger(String part) {
        int end = 0;
        while (end < part.length() && Character.isDigit(part.charAt(end))) end++;
        if (end == 0) return 0;
        try {
            return Integer.parseInt(part.substring(0, end));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    public List<UnavailableExtension> unavailableExtensions() {
        List<UnavailableExtension> values = new ArrayList<>(this.unavailable);
        for (LoadedExtension extension : this.extensions.values()) {
            String problem = this.installationProblem(extension.provider());
            if (problem == null) continue;
            values.add(new UnavailableExtension(extension.id(), extension.provider().displayName(),
                    extension.jar(), extension.version(), problem));
        }
        return List.copyOf(values);
    }

    public SourceContext context(Plugin source) {
        return this.context(null, source);
    }

    public SourceContext context(String id, Plugin source) {
        LoadedExtension extension = this.extensions.get(id);
        if (extension == null) throw new IllegalArgumentException("Unknown extension: " + id);
        return new ExtensionSourceContext(this, this.plugin, extension, source);
    }

    public boolean isPromptable(String id) {
        LoadedExtension extension = this.extensions.get(id);
        if (extension == null) return false;
        String configured = extension.definition().integrationDefaults().mode();
        if (!configured.isBlank()) return configured.equals("ask");
        return extension.provider().promptable();
    }

    public boolean sourceEnabled(String sourceId) {
        RecipeSourceProvider provider = this.provider(sourceId);
        if (provider == null) return false;
        return this.effectiveMode(sourceId) == dev.che1s.craftenginerecipe.config.IntegrationSettings.Mode.ENABLED;
    }

    public dev.che1s.craftenginerecipe.config.IntegrationSettings.Mode effectiveMode(String sourceId) {
        RecipeSourceProvider provider = this.provider(sourceId);
        if (provider == null) return dev.che1s.craftenginerecipe.config.IntegrationSettings.Mode.DISABLED;
        if (this.plugin.integrations().isConfigured(sourceId)) {
            return this.plugin.integrations().mode(sourceId);
        }
        String legacy = switch (sourceId) {
            case "craftengine" -> "sources.craftengine";
            case "craftengine_cultivation" -> "sources.craftengine-cultivation";
            case "minecraft_cultivation" -> "sources.minecraft-cultivation";
            default -> null;
        };
        if (legacy != null && this.integrationDefaults(sourceId).mode().isBlank()
                && this.plugin.getConfig().isSet(legacy)) {
            return this.plugin.getConfig().getBoolean(legacy, true)
                    ? dev.che1s.craftenginerecipe.config.IntegrationSettings.Mode.ENABLED
                    : dev.che1s.craftenginerecipe.config.IntegrationSettings.Mode.DISABLED;
        }
        return defaultMode(sourceId);
    }

    public dev.che1s.craftenginerecipe.config.IntegrationSettings.Mode defaultMode(String sourceId) {
        LoadedExtension extension = this.extensions.get(sourceId);
        if (extension == null) {
            return dev.che1s.craftenginerecipe.config.IntegrationSettings.Mode.ASK;
        }
        String declared = extension.definition().integrationDefaults().mode();
        String mode = declared.isBlank() ? extension.provider().defaultMode() : declared;
        return switch (mode.toLowerCase(Locale.ROOT)) {
            case "enabled" -> dev.che1s.craftenginerecipe.config.IntegrationSettings.Mode.ENABLED;
            case "disabled" -> dev.che1s.craftenginerecipe.config.IntegrationSettings.Mode.DISABLED;
            default -> dev.che1s.craftenginerecipe.config.IntegrationSettings.Mode.ASK;
        };
    }

    public IntegrationDefaults integrationDefaults(String sourceId) {
        LoadedExtension extension = this.extensions.get(sourceId);
        return extension == null ? IntegrationDefaults.EMPTY
                : extension.definition().integrationDefaults();
    }

    public boolean integrationOption(String sourceId, String option, boolean fallback) {
        return this.integrationDefaults(sourceId).option(option, fallback);
    }

    public static String minecraftVersion() {
        String version = Bukkit.getMinecraftVersion();
        if (version != null && !version.isBlank()) return version.trim();
        String bukkit = Bukkit.getBukkitVersion();
        if (bukkit == null || bukkit.isBlank()) return "unknown";
        int separator = bukkit.indexOf('-');
        return separator < 0 ? bukkit : bukkit.substring(0, separator);
    }

    public void close() {
        List<LoadedExtension> loaded = new ArrayList<>(this.extensions.values());
        this.extensions.clear();
        this.resourcePackOwners.clear();
        for (LoadedExtension extension : loaded) {
            try {
                extension.loader().close();
            } catch (IOException error) {
                this.plugin.getLogger().warning("Cannot close extension loader for "
                        + extension.jar().getFileName() + ": " + error.getMessage());
            }
        }
    }

    private void registerDefinition(String id, Path jar, ExtensionDefinition definition) {
        String jarName = jar.getFileName().toString();
        for (String pack : definition.resourcePacks()) {
            String previous = this.resourcePackOwners.get(pack);
            if (previous != null && !previous.equals(jarName)) {
                throw new IllegalArgumentException("resource pack '" + pack + "' from extension " + id
                        + " is already owned by " + previous);
            }
        }
        definition.resourcePacks().forEach(pack -> this.resourcePackOwners.put(pack, jarName));
    }

    public record LoadedExtension(String id, Path jar, URLClassLoader loader,
                                  RecipeSourceProvider provider, String version,
                                  ExtensionDefinition definition) {
    }

    public record ExtensionDefinition(String id, Map<String, String> names,
                                      Map<String, String> descriptions, List<String> authors,
                                      List<String> supportedLanguages,
                                      String languageSource, List<String> resourcePacks,
                                      List<PluginDependency> pluginDependencies,
                                      List<String> buttonIcons,
                                      IntegrationDefaults integrationDefaults,
                                      WorkstationPresentation presentation,
                                      List<Map<String, Object>> categoryPresets) {
        public static final ExtensionDefinition EMPTY = new ExtensionDefinition(
                "", Map.of(), Map.of(), List.of(), List.of(), "",
                List.of(), List.of(), List.of(), IntegrationDefaults.EMPTY,
                WorkstationPresentation.EMPTY, List.of());

        public ExtensionDefinition {
            names = Map.copyOf(names);
            descriptions = Map.copyOf(descriptions);
            authors = List.copyOf(authors);
            supportedLanguages = List.copyOf(supportedLanguages);
            languageSource = languageSource == null ? "" : languageSource.trim();
            resourcePacks = List.copyOf(resourcePacks);
            pluginDependencies = List.copyOf(pluginDependencies);
            buttonIcons = List.copyOf(buttonIcons);
            integrationDefaults = integrationDefaults == null
                    ? IntegrationDefaults.EMPTY : integrationDefaults;
            presentation = presentation == null ? WorkstationPresentation.EMPTY : presentation;
            categoryPresets = List.copyOf(categoryPresets);
        }

        public String localizedName(String locale, String fallback) {
            return localized(names, locale, fallback);
        }

        public String localizedDescription(String locale, String fallback) {
            return localized(descriptions, locale, fallback);
        }

        private static String localized(Map<String, String> values, String locale, String fallback) {
            if (values.isEmpty()) return fallback;
            if (locale == null || locale.isBlank()) {
                return values.getOrDefault("en_US", fallback);
            }
            String exact = values.get(locale);
            if (exact != null) return exact;
            String language = locale.split("_", 2)[0].toLowerCase(Locale.ROOT);
            String matched = values.entrySet().stream()
                    .filter(entry -> entry.getKey().toLowerCase(Locale.ROOT).startsWith(language + "_"))
                    .map(Map.Entry::getValue)
                    .findFirst().orElse(null);
            return matched == null ? values.getOrDefault("en_US", fallback) : matched;
        }
    }

    public record IntegrationDefaults(String mode, Map<String, Boolean> options) {
        public static final IntegrationDefaults EMPTY = new IntegrationDefaults("", Map.of());

        public IntegrationDefaults {
            mode = mode == null ? "" : mode.trim().toLowerCase(Locale.ROOT);
            options = Map.copyOf(options);
        }

        public boolean option(String name, boolean fallback) {
            return name == null ? fallback : this.options.getOrDefault(name, fallback);
        }
    }

    public record WorkstationPresentation(List<String> navigation, List<String> stationPrefixes,
                                          Map<String, WorkstationTitleStyle> styles) {
        public static final WorkstationPresentation EMPTY =
                new WorkstationPresentation(List.of(), List.of(), Map.of());

        public WorkstationPresentation {
            navigation = List.copyOf(navigation);
            stationPrefixes = List.copyOf(stationPrefixes);
            styles = Map.copyOf(styles);
        }

        public WorkstationTitleStyle style(String locale) {
            String normalized = locale == null ? "" : locale.toLowerCase(Locale.ROOT);
            WorkstationTitleStyle exact = styles.get(normalized);
            if (exact != null) return exact;
            int separator = normalized.indexOf('_');
            String language = separator < 0 ? normalized : normalized.substring(0, separator);
            WorkstationTitleStyle sameLanguage = styles.entrySet().stream()
                    .filter(entry -> entry.getKey().startsWith(language + "_"))
                    .map(Map.Entry::getValue)
                    .findFirst().orElse(null);
            return sameLanguage == null
                    ? styles.getOrDefault("default", WorkstationTitleStyle.DEFAULT) : sameLanguage;
        }
    }

    public record WorkstationTitleStyle(boolean removeSpaces, boolean bold) {
        public static final WorkstationTitleStyle DEFAULT =
                new WorkstationTitleStyle(false, false);

        public String apply(String title) {
            return !this.removeSpaces || title == null
                    ? title : title.replaceAll("[\\s\\u00A0]+", "");
        }
    }

    public record BundledCategoryPreset(String sourceId, String jarName,
                                        Map<String, Object> definition) {
    }

    public record ManagedWorkstationPreset(String stationId, String jarName) {
    }

    public record PluginDependency(String name, List<String> mainClasses,
                                   List<String> packagePrefixes, List<String> authors,
                                   String version, boolean primary) {
        public PluginDependency {
            mainClasses = List.copyOf(mainClasses);
            packagePrefixes = List.copyOf(packagePrefixes);
            authors = List.copyOf(authors);
            version = version == null ? "" : version.trim();
        }
    }

    public record UnavailableExtension(String id, String displayName, Path jar, String version,
                                       String reason) {
    }

    private record ExtensionSourceContext(ExtensionManager manager, CraftEngineRecipePlugin core,
                                          LoadedExtension extension, Plugin source)
            implements SourceContext {
        @Override
        public Plugin sourcePlugin() {
            return this.source;
        }

        @Override
        public Path coreDataFolder() {
            return this.core.getDataFolder().toPath();
        }

        @Override
        public Path extensionJar() {
            return this.extension.jar();
        }

        @Override
        public Path extensionDataFolder() {
            Path folder = this.core.getDataFolder().toPath()
                    .resolve("extensions-data").resolve(this.extension.id());
            try {
                Files.createDirectories(folder);
            } catch (IOException error) {
                throw new IllegalStateException("Cannot create extension data folder " + folder, error);
            }
            return folder;
        }

        @Override
        public String extensionVersion() {
            return this.extension.version();
        }

        @Override
        public String minecraftVersion() {
            return ExtensionManager.minecraftVersion();
        }

        @Override
        public java.util.logging.Logger logger() {
            return this.core.getLogger();
        }

        @Override
        public SourceText text(String integrationId) {
            return IntegrationText.of(integrationId, this.core.lang());
        }

        @Override
        public Function<String, String> resourcePackTranslation() {
            return this.core.data()::resourcePackTranslation;
        }

        @Override
        public Function<String, String> itemDisplayName() {
            return this.core.data()::itemDisplayName;
        }

        @Override
        public java.util.Map<String, String> localizedItemNames(String id) {
            return this.core.data().localizedItemNames(id);
        }

        @Override
        public long translationRevision() {
            return this.core.data().translationRevision();
        }

        @Override
        public boolean chinese() {
            return this.core.lang().locale().toLowerCase(Locale.ROOT).startsWith("zh");
        }

        @Override
        public boolean sourceEnabled(String sourceId) {
            return this.manager.sourceEnabled(sourceId);
        }

        @Override
        public boolean sourceOption(String sourceId, String option, boolean fallback) {
            return this.core.integrations().option(sourceId, option, fallback);
        }

        @Override
        public boolean coreOption(String path, boolean fallback) {
            return this.core.getConfig().getBoolean(path, fallback);
        }

        @Override
        public boolean craftEngineDataReady() {
            return this.core.data().isReady();
        }

        @Override
        public long craftEngineDataGeneration() {
            return this.core.data().generation();
        }

        @Override
        public List<DisplayRecipe> loadRecipes(Path dataFolder) throws IOException {
            return CustomRecipeLoader.allSources(dataFolder).load();
        }

        @Override
        public Function<String, String> coreText() {
            return this.core.lang()::text;
        }
    }
}
