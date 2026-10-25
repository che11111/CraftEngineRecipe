package dev.che1s.craftenginerecipe.api;

import org.bukkit.inventory.ItemStack;

import java.util.List;

/** 物品身份及可选的显示覆盖信息。显示覆盖和图标均不会改变物品身份。 */
public record RecipeItem(String provider, String id, ItemStack icon, String name, List<String> lore) {
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
    }

    @Override
    public ItemStack icon() { return this.icon == null ? null : this.icon.clone(); }

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
