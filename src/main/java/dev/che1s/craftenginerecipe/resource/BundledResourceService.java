package dev.che1s.craftenginerecipe.resource;

import dev.che1s.craftenginerecipe.i18n.LanguageManager;
import dev.che1s.craftenginerecipe.integration.extension.ExtensionManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Installs and replenishes bundled CraftEngine resource packs. */
public final class BundledResourceService {
    private static final String RECIPE_BOOK_RECIPE_FILE = "configuration/recipes.yml";
    private final JavaPlugin plugin;
    private final Path jarPath;
    private final MenuPackManager menuPacks;
    private final LanguageManager language;
    private boolean recipeBookCraftingChanged;

    public BundledResourceService(JavaPlugin plugin, Path jarPath,
                                  MenuPackManager menuPacks, LanguageManager language) {
        this.plugin = plugin;
        this.jarPath = jarPath;
        this.menuPacks = menuPacks;
        this.language = language;
    }

    public void install() {
        Path localRoot = this.localRoot();
        try {
            for (String pack : ResourcePackInstaller.REQUIRED_CORE_PACKS) {
                this.populate(localRoot, pack);
            }
            for (String menuPack : ResourcePackInstaller.bundledMenuPacks(this.jarPath)) {
                this.populate(localRoot, menuPack);
            }
            this.syncRecipeBookCraftingFile();
            this.recipeBookCraftingChanged = false;
            this.menuPacks.installEnabled();
        } catch (IOException error) {
            this.plugin.getLogger().severe(this.language.text(
                    "logs.pack-install-failed", "error", error.getMessage()));
        }
    }

    public void replenish() {
        Path localRoot = this.localRoot();
        List<String> packs = new ArrayList<>(ResourcePackInstaller.REQUIRED_CORE_PACKS);
        Path recipe = this.recipeBookRecipe(localRoot);
        boolean recipeWasPresent = Files.isRegularFile(recipe, LinkOption.NOFOLLOW_LINKS);
        try {
            packs.addAll(ResourcePackInstaller.bundledMenuPacks(this.jarPath));
        } catch (IOException error) {
            this.plugin.getLogger().warning("Cannot list bundled menu packs: " + error.getMessage());
        }
        for (String pack : packs) {
            Path local = localRoot.resolve(pack);
            try {
                int count = ResourcePackInstaller.replenishBundled(this.jarPath, local, pack,
                        this.plugin.getName(), this.plugin.getDescription().getVersion());
                this.logCopy(local, count);
            } catch (IOException error) {
                this.plugin.getLogger().severe(this.language.text(
                        "logs.pack-install-failed", "error", error.getMessage()));
            }
        }
        try {
            boolean recipeIsPresent = this.syncRecipeBookCraftingFile();
            this.recipeBookCraftingChanged = recipeWasPresent != recipeIsPresent;
        } catch (IOException error) {
            this.plugin.getLogger().severe(this.language.text(
                    "logs.pack-install-failed", "error", error.getMessage()));
        }
    }

    public boolean consumeRecipeBookCraftingChanged() {
        boolean changed = this.recipeBookCraftingChanged;
        this.recipeBookCraftingChanged = false;
        return changed;
    }

    public void installExtensionPacks(ExtensionManager extensions) {
        if (extensions == null) return;
        Path localRoot = this.localRoot();
        for (var extension : extensions.providers()) {
            String id = extension.id();
            ExtensionManager.LoadedExtension loaded = extensions.extension(id);
            if (loaded == null) continue;
            for (String pack : extensions.resourcePacks(id)) {
                Path local = localRoot.resolve(pack);
                try {
                    int count = ResourcePackInstaller.replenishPrefix(
                            loaded.jar(), local, "resourcepack/" + pack,
                            loaded.jar().getFileName().toString(), loaded.version());
                    this.logCopy(local, count);
                } catch (IOException error) {
                    this.plugin.getLogger().warning("Cannot install resource pack " + pack
                            + " from extension " + loaded.jar().getFileName() + ": "
                            + error.getMessage());
                }
            }
        }
    }

    private void populate(Path localRoot, String pack) throws IOException {
        Path local = localRoot.resolve(pack);
        int count = ResourcePackInstaller.replenishBundled(this.jarPath, local, pack,
                this.plugin.getName(), this.plugin.getDescription().getVersion());
        this.logCopy(local, count);
    }

    private boolean syncRecipeBookCraftingFile() throws IOException {
        Path root = this.localRoot();
        Path recipe = this.recipeBookRecipe(root);
        boolean enabled = this.plugin.getConfig().getBoolean("recipe-book.enabled", true)
                && this.plugin.getConfig().getBoolean("recipe-book.crafting-enabled", true);
        boolean present = Files.isRegularFile(recipe, LinkOption.NOFOLLOW_LINKS);
        if (enabled && !present) {
            ResourcePackInstaller.replenishBundled(this.jarPath,
                    root.resolve(ResourcePackInstaller.RECIPE_BOOK_PACK_DIRECTORY),
                    ResourcePackInstaller.RECIPE_BOOK_PACK_DIRECTORY,
                    this.plugin.getName(), this.plugin.getDescription().getVersion());
        } else if (!enabled) {
            Files.deleteIfExists(recipe);
        }
        return Files.isRegularFile(recipe, LinkOption.NOFOLLOW_LINKS);
    }

    private Path recipeBookRecipe(Path localRoot) {
        return localRoot.resolve(ResourcePackInstaller.RECIPE_BOOK_PACK_DIRECTORY)
                .resolve(RECIPE_BOOK_RECIPE_FILE);
    }

    private void logCopy(Path local, int count) {
        this.plugin.getLogger().info(this.language.text(
                "logs.pack-copy-ready", "path", local.toString(), "count", String.valueOf(count)));
    }

    private Path localRoot() {
        return this.plugin.getDataFolder().toPath().resolve(ResourcePackInstaller.RESOURCES_DIRECTORY);
    }

}
