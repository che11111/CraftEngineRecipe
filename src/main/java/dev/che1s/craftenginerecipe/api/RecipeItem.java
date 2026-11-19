package dev.che1s.craftenginerecipe.api;

import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 物品身份及可选的显示覆盖信息。显示覆盖和图标均不会改变物品身份。 */
public record RecipeItem(String provider, String id, ItemStack icon, String name, List<String> lore,
                         Map<String, String> localizedNames) {
    public RecipeItem(String provider, String id, ItemStack icon, String name, List<String> lore) {
        this(provider, id, icon, name, lore, Map.of());
    }

    public RecipeItem {
        provider = RecipeValidation.provider(provider);
        if (id == null || id.isBlank() || id.chars().anyMatch(character -> Character.isWhitespace(character)
                || Character.isSpaceChar(character) || Character.isISOControl(character))) {
            throw new IllegalArgumentException("item id must be nonempty and contain no whitespace");
        }
        if (provider.equals("minecraft") || provider.equals("craftengine")) {
            RecipeValidation.identifier(id, "item id");
            if (provider.equals("minecraft") && !id.startsWith("minecraft:")) {
                throw new IllegalArgumentException("minecraft provider requires a minecraft: item id");
            }
        }
        icon = icon == null ? null : icon.clone();
        // null 会保留来源 lore；空列表会显式清除 lore。
        lore = lore == null ? null : List.copyOf(lore);
        localizedNames = localizedNames == null ? Map.of() : Map.copyOf(localizedNames);
    }

    @Override
    public ItemStack icon() { return this.icon == null ? null : this.icon.clone(); }

    /** Resolves a language-specific display-name override with English and plain-name fallbacks. */
    public String localizedName(String locale) {
        if (this.localizedNames.isEmpty()) return this.name;
        if (locale != null && !locale.isBlank()) {
            String normalized = locale.replace('-', '_');
            for (var entry : this.localizedNames.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(normalized)) return entry.getValue();
            }
            String language = normalized.toLowerCase(Locale.ROOT).split("_", 2)[0];
            for (var entry : this.localizedNames.entrySet()) {
                if (entry.getKey().toLowerCase(Locale.ROOT).startsWith(language + "_")) {
                    return entry.getValue();
                }
            }
        }
        for (var entry : this.localizedNames.entrySet()) {
            if (entry.getKey().equalsIgnoreCase("en_US")) return entry.getValue();
        }
        return this.localizedNames.values().stream().findFirst().orElse(this.name);
    }

    public RecipeItem withName(String name) {
        return new RecipeItem(this.provider, this.id, this.icon, name, this.lore, this.localizedNames);
    }

    public RecipeItem withLore(List<String> lore) {
        return new RecipeItem(this.provider, this.id, this.icon, this.name, lore, this.localizedNames);
    }

    public static RecipeItem of(String id) {
        return of(id != null && id.startsWith("minecraft:") ? "minecraft" : "craftengine", id);
    }

    public static RecipeItem of(String provider, String id) {
        return new RecipeItem(provider, id, null, null, null);
    }

    public static RecipeItem stack(String provider, String id, ItemStack icon) {
        if (icon == null) throw new IllegalArgumentException("icon must not be null");
        return new RecipeItem(provider, id, icon, null, null);
    }
}
