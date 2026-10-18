package dev.leon.cer.util;

import io.papermc.paper.datacomponent.DataComponentTypes;
import net.kyori.adventure.text.Component;
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.bukkit.item.BukkitItem;
import net.momirealms.craftengine.bukkit.item.BukkitItemDefinition;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.item.recipe.Ingredient;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.util.UniqueKey;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import xyz.xenondevs.invui.item.ItemBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class ItemBridge {

    private ItemBridge() {
    }

    public static ItemStack build(Key id, Player player, int count) {
        ItemStack stack = null;
        try {
            BukkitItemDefinition def = CraftEngineItems.byId(id);
            if (def != null) {
                stack = player != null ? def.buildBukkitItem(player) : def.buildBukkitItem();
            }
        } catch (RuntimeException | LinkageError error) {
            CompatibilityWarnings.warn("ItemBridge.build", error);
        }
        if (stack == null || stack.getType() == Material.AIR) {
            Material vanilla = matchVanilla(id);
            if (vanilla == null) return barrier(Component.text(id.toString()));
            stack = new ItemStack(vanilla);
        } else {
            stack = stack.clone();
        }
        if (count <= 1) return stack;
        stack.setAmount(Math.min(count, stack.getMaxStackSize()));
        return stack;
    }

    public static ItemStack build(Key id, Player player) {
        return build(id, player, 1);
    }

    public static ItemStack buildIngredient(Ingredient ingredient, Player player, int count) {
        if (ingredient == null) {
            return emptySlot();
        }
        List<UniqueKey> items = ingredient.items();
        if (items == null || items.isEmpty()) {
            items = ingredient.minecraftItems();
        }
        if (items == null || items.isEmpty()) {
            return emptySlot();
        }
        Key first = items.get(0).key();
        int c = count > 0 ? count : Math.max(1, ingredient.count());
        return build(first, player, c);
    }

    public static Optional<Key> firstIngredientKey(Ingredient ingredient) {
        if (ingredient == null) {
            return Optional.empty();
        }
        List<UniqueKey> items = ingredient.items();
        if (items == null || items.isEmpty()) {
            items = ingredient.minecraftItems();
        }
        if (items == null || items.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(items.get(0).key());
    }

    public static ItemStack fromCeItem(Item item, Player player) {
        if (item == null) {
            return emptySlot();
        }
        try {
            if (item instanceof BukkitItem) {
                BukkitItem bukkitItem = (BukkitItem) item;
                return bukkitItem.getBukkitItem().clone();
            }
            Object raw = item.platformItem();
            if (raw instanceof ItemStack) {
                ItemStack stack = (ItemStack) raw;
                return stack.clone();
            }
        } catch (RuntimeException | LinkageError error) {
            CompatibilityWarnings.warn("ItemBridge.platformItem", error);
        }
        return emptySlot();
    }

    public static ItemStack button(Material material, Component name, List<Component> lore) {
        ItemStack stack = new ItemStack(material == null ? Material.STONE : material);
        applyMeta(stack, name, lore);
        return stack;
    }

    public static ItemStack icon(String itemModel, Material fallback, Component name, List<Component> lore) {
        ItemBuilder builder = new ItemBuilder(fallback == null ? Material.STONE : fallback);
        if (itemModel != null && !itemModel.isBlank()) {
            builder.set(DataComponentTypes.ITEM_MODEL, net.kyori.adventure.key.Key.key(itemModel));
        }
        if (name != null) {
            builder.setName(name);
        }
        if (lore != null && !lore.isEmpty()) {
            builder.setLore(lore);
        }
        return builder.build();
    }

    public static ItemStack decorate(ItemStack stack, Component name, List<Component> lore) {
        if (stack == null) {
            return emptySlot();
        }
        ItemStack copy = stack.clone();
        applyMeta(copy, name, lore);
        return copy;
    }

    private static void applyMeta(ItemStack stack, Component name, List<Component> lore) {
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return;
        }
        if (name != null) {
            meta.displayName(name);
        }
        if (lore != null && !lore.isEmpty()) {
            List<Component> filtered = new ArrayList<>(lore.size());
            for (Component c : lore) {
                if (c == null) continue;
                filtered.add(c);
            }
            meta.lore(filtered);
        }
        stack.setItemMeta(meta);
    }

    public static ItemStack emptySlot() {
        return new ItemStack(Material.AIR);
    }

    public static ItemStack barrier(Component name) {
        return button(Material.BARRIER, name, List.of());
    }

    private static Material matchVanilla(Key id) {
        if (id == null) {
            return null;
        }
        if (!"minecraft".equals(id.namespace())) return null;
        String path = id.value();
        Material m = Material.matchMaterial(path);
        if (m != null) {
            return m;
        }
        m = Material.matchMaterial(path.toUpperCase(Locale.ROOT));
        return m;
    }

    public static net.momirealms.craftengine.core.entity.player.Player toCePlayer(Player player) {
        return BukkitAdaptor.adapt(player);
    }
}
