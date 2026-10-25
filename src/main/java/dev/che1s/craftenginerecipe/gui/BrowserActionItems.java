package dev.che1s.craftenginerecipe.gui;

import dev.che1s.craftenginerecipe.CraftEngineRecipePlugin;
import dev.che1s.craftenginerecipe.config.CerConfig;
import dev.che1s.craftenginerecipe.util.ItemBridge;
import net.kyori.adventure.text.Component;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import xyz.xenondevs.invui.item.BoundItem;
import xyz.xenondevs.invui.item.Item;
import xyz.xenondevs.invui.item.ItemWrapper;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** 构建各类菜单操作按钮，不持有页面或窗口状态。 */
final class BrowserActionItems {
    private final CraftEngineRecipePlugin plugin;
    private final CerConfig config;
    private final BrowserService browser;

    BrowserActionItems(CraftEngineRecipePlugin plugin, CerConfig config, BrowserService browser) {
        this.plugin = plugin;
        this.config = config;
        this.browser = browser;
    }

    Item pageNavItem(boolean next) {
        CerConfig.ButtonDef def = next ? this.config.nextPage() : this.config.prevPage();
        return BoundItem.pagedBuilder().setItemProvider((player, gui) -> {
            boolean available = next ? gui.getPage() + 1 < gui.getPageCount() : gui.getPage() > 0;
            Component name = available
                    ? this.config.mm(def.name())
                    : this.plugin.lang().component(next ? "ui.common.last-page" : "ui.common.first-page");
            String model = available ? def.itemModel() : this.config.disabledItemModel();
            return new ItemWrapper(ItemBridge.icon(model, def.material(), name, this.buttonLore(def)));
        }).addClickHandler((item, gui, click) -> this.browser.handleClick(click.player(), () -> {
            int target = gui.getPage() + (next ? 1 : -1);
            if (target < 0 || target >= gui.getPageCount()) {
                Sounds.play(click.player(), this.config.sounds().queryFailure());
                return;
            }
            gui.setPage(target);
            Sounds.play(click.player(), this.config.sounds().changePage());
        })).build();
    }

    Item searchItem() {
        return this.searchItem(player -> this.plugin.searchDialog().open(player));
    }

    Item searchItem(Consumer<Player> action) {
        return this.shortcutItem(this.config.search(), action);
    }

    Item shortcutItem(CerConfig.ButtonDef def, Consumer<Player> action) {
        ItemStack stack = this.icon(def);
        if (action == null) return Item.simple(stack);
        return Item.builder().setItemProvider(stack).addClickHandler(click ->
                this.browser.handleClick(click.player(), () -> action.accept(click.player()))).build();
    }

    Item queryShortcutItem(CerConfig.ButtonDef def) {
        return Item.builder().setItemProvider(this.icon(def)).addClickHandler(click -> {
            ClickType type = click.clickType();
            boolean target = type == ClickType.RIGHT || type == ClickType.SHIFT_RIGHT;
            this.browser.handleClick(click.player(), () -> {
                if (target) this.browser.openTargetBlockRecipe(click.player());
                else this.browser.openHeldRecipe(click.player());
            });
        }).build();
    }

    Item workstationsShortcutItem(CerConfig.ButtonDef def) {
        List<Component> lore = new ArrayList<>(this.buttonLore(def));
        boolean personalizationEnabled = this.plugin.playerPreferences() != null
                && this.plugin.playerPreferences().enabled()
                && this.plugin.personalizationDialog() != null;
        if (personalizationEnabled) {
            lore.add(this.plugin.lang().component("ui.content.workstations.personalization-lore"));
        }
        ItemStack stack = ItemBridge.icon(def.itemModel(), def.material(), this.config.mm(def.name()), lore);
        return Item.builder().setItemProvider(stack).addClickHandler(click -> {
            ClickType type = click.clickType();
            boolean personalize = personalizationEnabled
                    && (type == ClickType.RIGHT || type == ClickType.SHIFT_RIGHT);
            this.browser.handleClick(click.player(), () -> {
                Player player = click.player();
                Sounds.play(player, this.config.sounds().click());
                if (personalize && this.plugin.personalizationDialog().open(player)) return;
                this.browser.openOrToggleWorkstations(player);
            });
        }).build();
    }

    Item backItem() {
        CerConfig.ButtonDef def = this.config.back();
        return Item.builder().setItemProvider(this.icon(def)).addClickHandler(click ->
                this.browser.handleClick(click.player(), () -> {
                    Player player = click.player();
                    GuiSession session = GuiSession.of(player);
                    Key parent = session.exit();
                    Sounds.play(player, this.config.sounds().back());
                    if (parent != null) this.browser.openCategory(player, parent, session.categoryPage());
                    else this.browser.openDefault(player);
                })).build();
    }

    private ItemStack icon(CerConfig.ButtonDef def) {
        return ItemBridge.icon(def.itemModel(), def.material(), this.config.mm(def.name()), this.buttonLore(def));
    }

    private List<Component> buttonLore(CerConfig.ButtonDef def) {
        return def.lore().stream().map(this.config::mm).toList();
    }
}
