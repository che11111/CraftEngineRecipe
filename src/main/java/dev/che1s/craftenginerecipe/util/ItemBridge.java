package dev.che1s.craftenginerecipe.util;

import dev.che1s.craftenginerecipe.CraftEngineRecipePlugin;
import dev.che1s.craftenginerecipe.api.RecipeItem;
import dev.che1s.craftenginerecipe.book.RecipeBookService;

import io.papermc.paper.datacomponent.DataComponentTypes;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
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
import java.util.Map;
import java.util.Optional;

public final class ItemBridge {

    private ItemBridge() {
    }

    public static ItemStack build(Key id, Player player, int count) {
        var plugin = CraftEngineRecipePlugin.get();
        if (id != null && "cer_external".equals(id.namespace()) && plugin != null) {
            RecipeItem external = plugin.externalRecipes().snapshot().items().get(id);
            if (external != null && !external.provider().equals("craftengine") && !external.provider().equals("minecraft")) {
                return build(external, player, count);
            }
        }
        return buildNative(id, player, count);
    }

    private static ItemStack buildNative(Key id, Player player, int count) {
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
            if (vanilla == null || !vanilla.isItem()) return barrier(Component.text(id.toString()));
            stack = new ItemStack(vanilla);
        } else {
            stack = stack.clone();
        }
        stack = localizeNativeName(id, stack, player);
        if (count <= 1) return stack;
        stack.setAmount(Math.min(count, stack.getMaxStackSize()));
        return stack;
    }

    private static ItemStack localizeNativeName(Key id, ItemStack stack, Player player) {
        if (id == null || stack == null || player == null) return stack;
        var plugin = CraftEngineRecipePlugin.get();
        if (plugin == null || plugin.data() == null) return stack;
        String name = RecipeBookService.isRecipeBookItem(id) && plugin.recipeBook() != null
                ? plugin.recipeBook().nameTemplate(player)
                : plugin.data().itemDisplayName(player, id.toString());
        if ((name == null || name.isBlank()) && "minecraft".equals(id.namespace())) {
            name = plugin.data().vanillaItemName(player, id.toString());
        }
        if (name == null || name.isBlank()) {
            Material material = stack.getType();
            String path = id.value();
            Map<String, String> translations = plugin.data().resourcePackTranslations(player);
            name = translations.get("item.minecraft." + path);
            if (name == null || name.isBlank()) {
                name = translations.get("block.minecraft." + path);
            }
            if ((name == null || name.isBlank()) && material.isItem()) {
                name = translations.get(material.getTranslationKey());
            }
        }
        if (name == null || name.isBlank()) return stack;
        ItemStack copy = stack.clone();
        ItemMeta meta = copy.getItemMeta();
        if (meta != null) {
            var browser = plugin.browser(player);
            var config = browser == null ? plugin.config() : browser.configFor(player);
            meta.displayName(config.mm(name));
            copy.setItemMeta(meta);
        }
        return copy;
    }

    public static ItemStack build(Key id, Player player) {
        return build(id, player, 1);
    }

    public static ItemStack build(RecipeItem item, Player player, int count) {
        ItemStack stack = item.icon();
        var plugin = CraftEngineRecipePlugin.get();
        if (stack == null && !item.provider().equals("craftengine") && !item.provider().equals("minecraft") && plugin != null) {
            RecipeItem known = plugin.externalRecipes().snapshot().items().get(dev.che1s.craftenginerecipe.recipe.ExternalRecipeSnapshot.key(item));
            if (known != null) stack = known.icon();
        }
        if (stack == null && item.provider().equals("cer_api") && item.id().equals("note")) {
            // API 备注没有来源图标；保留可悬浮的 PAPER 载体，但将模型替换为空气。
            stack = icon("minecraft:air", Material.PAPER, null, null);
        }
        if (stack == null) {
            stack = item.provider().equals("craftengine") || item.provider().equals("minecraft")
                    ? buildNative(Key.of(item.id()), player, count) : barrier(Component.text(item.id()));
        }
        stack.setAmount(Math.max(1, Math.min(count, stack.getMaxStackSize())));
        ItemMeta meta = stack.getItemMeta();
        if (meta != null && plugin != null) {
            Map<String, String> translations = plugin.data() == null
                    ? Map.of() : plugin.data().resourcePackTranslations(player);
            Component existingName = meta.displayName();
            String existingPlain = existingName == null ? null
                    : PlainTextComponentSerializer.plainText().serialize(existingName);
            if ((item.name() == null || item.name().isBlank())
                    && DisplayNames.looksLikeTranslationKey(existingPlain)) {
                String fallback = translations.get(existingPlain);
                if (fallback != null && !fallback.isBlank()) {
                    meta.displayName(plugin.config().mm(fallback));
                }
            }
            if (item.name() != null) {
                String localized = item.localizedName(player == null ? "en_US"
                        : plugin.lang(player).locale());
                if ((localized == null || localized.isBlank()) && plugin.data() != null) {
                    localized = plugin.data().itemDisplayName(player, item.id());
                }
                if ((localized == null || localized.isBlank()) && "minecraft".equals(item.provider())
                        && plugin.data() != null) {
                    localized = plugin.data().vanillaItemName(player, item.id());
                }
                String displayName = localized == null || localized.isBlank()
                        ? DisplayNames.expandTranslations(item.name(), translations, player) : localized;
                meta.displayName(plugin.config().mm(displayName));
            }
            if (item.lore() != null) {
                meta.lore(item.lore().stream()
                        .map(line -> plugin.config().mm(
                                DisplayNames.expandTranslations(line, translations, player)))
                        .toList());
            }
            stack.setItemMeta(meta);
        }
        return stack;
    }

    /** 追加浏览提示，同时保留物品原有的显示 lore。 */
    public static ItemStack appendLore(ItemStack stack, List<Component> lines) {
        ItemStack copy = stack.clone();
        ItemMeta meta = copy.getItemMeta();
        if (meta != null) {
            List<Component> lore = new ArrayList<>();
            if (meta.lore() != null) lore.addAll(meta.lore());
            lore.addAll(lines);
            meta.lore(lore);
            copy.setItemMeta(meta);
        }
        return copy;
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
