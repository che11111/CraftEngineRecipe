# CraftEngineRecipe User Guide

[Back to README](README.md) · [简体中文](WIKI-zh.md)

This guide covers installation, player controls, configuration, and upgrades.

## Contents

- [Requirements](#requirements)
- [Folia Support](#folia-support)
- [First Installation and Applying Categories](#first-installation-and-applying-categories)
- [Commands, Permissions, and Controls](#commands-permissions-and-controls)
- [Applying the Resource Pack](#applying-the-resource-pack)
- [Player Controls and Search](#player-controls-and-search)
  - [Look Up Main-Hand Recipes](#look-up-main-hand-recipes)
  - [Search](#search)
- [Categories, Permissions, and Hiding Rules](#categories-permissions-and-hiding-rules)
  - [Order, Display Names, and Tooltips](#order-display-names-and-tooltips)
  - [Hiding Items in a Category](#hiding-items-in-a-category)
  - [Hiding Both Category Entries and Their Items](#hiding-both-category-entries-and-their-items)
- [Custom Buttons](#custom-buttons)
- [Menu Sounds](#menu-sounds)
- [Common Settings and Troubleshooting](#common-settings-and-troubleshooting)
- [Building from Source and Upgrading](#building-from-source-and-upgrading)

Common settings: [Commands, Permissions, and Controls](#commands-permissions-and-controls) · [Hide Items](#hiding-items-in-a-category) · [Hide Category Entries](#hiding-both-category-entries-and-their-items) · [Custom Buttons](#custom-buttons)

## Requirements

| Component | Requirement |
| --- | --- |
| Java | Java 25 (current build target) |
| Server | Paper / Folia for Minecraft `26.1.2 / 26.2 / 26.3`, using the matching plugin JAR |
| Required plugin | CraftEngine; the current compile-time dependency is `26.8.1` |
| Bundled dependencies | The matching InvUI version for each build, pinyin4j 2.5.1, and bStats 3.0.2; no separate installation needed |
| Folia | Experimental support since 1.0.10; target: `26.1.2 build 8` + CraftEngine `26.9.2`; see below |

## Folia Support

Folia support is experimental. Targets include Java 25, Folia `26.1.2 build 8`, and CraftEngine `26.9.2`. For Folia, also choose the JAR matching its Minecraft version and use a compatible CraftEngine release.

Menus, search callbacks, sounds, and recipe rotation use the player's entity scheduler, which follows the player across regions. Global configuration and index publication run on the global scheduler, while plain-text search indexes are computed asynchronously. A reload first pauses access and asynchronously waits for each player's windows to close, then loads configuration and publishes new category/search snapshots. Region threads do not block while waiting for other regions. Old search callbacks and delayed button actions become invalid after a reload.

`console:` actions run on the global scheduler. Once they finish, the action chain continues on the player's thread. `command:` / `player:` actions run on the player's thread. Commands from other plugins must also support Folia. **On Folia, `op:` actions are not executed and stop the action chain.** Use an explicit `console:` command or grant the player the required permissions to avoid temporarily changing OP status across threads. Paper retains the original `op:` behavior.

## First Installation and Applying Categories

1. Install and configure CraftEngine, and make sure it has loaded your item packs, item categories, and recipes.
2. Install the CraftEngineRecipe version matching your server. On the first run, the bundled resource pack is automatically installed into CraftEngine's resource directory. See [Applying the Resource Pack](#applying-the-resource-pack).
3. Wait for the console message indicating that CraftEngineRecipe data is ready (`CraftEngineRecipe 数据就绪` in Chinese). Indexes are built in batches and asynchronously, so large item collections may take a little longer.
4. As an administrator, run `/cer unapplied` to preview categories that have not yet been applied.
5. Open `plugins/CraftEngineRecipe/categories-unapplied.yml` and find the namespaces you want to make available, such as `farmersdelight`.
6. Copy those entries into `entries:` in `plugins/CraftEngineRecipe/categories.yml`. Set `visibility: player` as needed.
7. Run `/cer reload`, wait for completion, then open the main menu with `/cer`.

A minimal `categories.yml`:

```yaml
entries:
  - id: default
    visibility: player
    permission: ''
```

Replace `default` with an actual namespace from the unapplied file. Here, `id` is a namespace, not a full item ID or a category ID such as `farmersdelight:food`.

The main category list may initially be empty; this is expected. Newly discovered namespaces default to `admin` and appear in the unapplied list. They are not automatically added to the main `/cer` menu. After copying the entries and reloading, the corresponding entries are removed from the unapplied file.

This plugin reads categories and recipes already defined in CraftEngine. Editing `categories.yml` changes their presentation; it cannot create CraftEngine items, recipes, or categories.

## Commands, Permissions, and Controls

| Command / Control | Permission | Purpose |
| --- | --- | --- |
| `/cer unapplied` | `craftenginerecipe.admin` | Open the unapplied menu |
| `/cer`, `/cg`, `/browser` | None | Open the main menu |
| `/cer search` | None | Open the search dialog |
| `/cer hand` | None | View recipes that produce the main-hand item, within accessible main-menu categories |
| `/cer reload` | `craftenginerecipe.admin` | Reload plugin configuration, language, category data, search indexes, and the bStats setting |
| Press the swap-offhand key while sneaking (Shift+F by default; can be disabled in settings) | — | Open the main menu; actual keys depend on the client's key bindings |
| Click outside the recipe window | — | Close the recipe page and return to the main menu |
| Esc | — | Close the window |

OPs have administrator permissions by default. Administrators can view restricted namespaces. The console can run `/cer reload`; the other commands require a player.

After changing CraftEngine items or recipes, reload them through CraftEngine first. This plugin automatically rebuilds its data when it receives the reload event. `/cer reload` does not reload CraftEngine itself. During a rebuild, existing menus close; the command first reports that a reload is in progress, then reports the result when it finishes.

## Applying the Resource Pack

Only on its first startup, the plugin automatically copies the `cer_resource` pack into `plugins/CraftEngine/resources/`.

`plugins/CraftEngineRecipe/cer_resource/` serves only as a backup copy.

Run `/ce reload all` to reload CraftEngine and apply the resource pack.

## Player Controls and Search

Clicking an item with no matching recipe or usage does not open a recipe page. Multiple recipes rotate automatically, every 30 ticks by default (about 1.5 seconds at normal server speed). Recipe pages currently use native crafting table, furnace, smithing, and other inventory windows. They do not show buttons for manually switching recipes or modes.

Supported recipes include shaped/shapeless crafting, smelting, blasting, smoking, campfire cooking, stonecutting, smithing, and brewing. Campfire recipes use a furnace-style window. Brewing pages show the input container at the bottom left and the output at the bottom right. Dynamic recipes primarily preview ingredients and fixed outputs; smithing trim results display a message that the result depends on the actual input items. When an ingredient has multiple alternatives, only the first is currently shown. The interface is for reference and does not provide one-click crafting.

### Look Up Main-Hand Recipes

Hold an item in your main hand and run `/cer hand` to view recipes that produce it. This shows how to craft the item and does not consume, replace, or modify it. Multiple recipes use the existing automatic rotation.

This supports CraftEngine custom items and vanilla items exposed by accessible CraftEngine categories. It only queries recipes loaded and indexed from CraftEngine; it does not collect private recipes from other plugins. The command follows the main menu's applied categories, visibility, permissions, and hiding rules. Administrators cannot use it to bypass unapplied or hidden content. If CraftEngine has no categories at all, only loaded custom items in applied namespaces that the player has permission to access can be queried.

An empty hand, an inaccessible item, or an item with no indexed output recipe produces a message.

### Search

Run `/cer search` or click the search button, enter a keyword in the dialog, and confirm.

Search supports full item IDs, partial IDs, display names, full or partial translation keys, pinyin, and pinyin initials. For example, an item named “茄子” can be found with `茄子`, `qie`, `zi`, `qiez`, or `qz`. For characters with multiple pronunciations, pinyin4j's first pronunciation is used; some items may require searching by name or ID instead.

For item names that use translation keys, the plugin reads translations from `assets/*/lang/*.json` in CraftEngine's generated resource pack and includes the resolved names and their pinyin in the search index. For example, if `item.example.eggplant` maps to “茄子” in `zh_cn.json`, you can search by its Chinese name, pinyin, or translation key.

Set the preferred search-name languages in `config.yml`:

```yaml
search:
  languages:
    - zh_cn
    - en_us
```

The first language with a matching translation wins; later entries provide fallbacks. This does not index names in every listed language. It is independent of `language`, which controls plugin menus and messages, and does not automatically follow each player's client language.

The plugin uses the resource pack path supplied by CraftEngine, falling back to `plugins/CraftEngine/generated/resource_pack.zip` when that path is unavailable. After editing language files, generate the updated ZIP with CraftEngine, then run `/cer reload`; CraftEngine reload events also refresh the index. ZIP reading, JSON parsing, and text indexing run asynchronously. A missing or broken resource pack is logged, and existing item ID/name search remains available.

By default, search input is limited to 32 characters and results to 200 entries. Searches include categories and accessible subcategories that the player can access in the current main/unapplied menu. Hidden category members are excluded. If the same item also appears in another visible category, it may still be found. The browser and search only fall back to all loaded items when CraftEngine has no categories at all.

## Categories, Permissions, and Hiding Rules

### Order, Display Names, and Tooltips

`entries:` in `categories.yml` is an ordered list. Namespace entries and custom buttons can be mixed. Within each namespace, categories follow CraftEngine's category order.

```yaml
entries:
  - id: farmersdelight
    visibility: player
    icon-name: '<white><name></white>'
    icon-lore:
      - '<gray>Click to open this category</gray>'
      - '<dark_gray>Items: <count></dark_gray>'
    sub-icon-lore:
      - '<gray>Click to open this subcategory</gray>'
    item-lore:
      - '<gray>Left-click for recipes, right-click for uses</gray>'

  - id: vipitems
    visibility: permission
    permission: 'cer.vip'

  - id: internal
    visibility: admin
```

Keep only namespaces that actually exist. Available `visibility` values:

- `player`: visible to all players.
- `admin`: visible only to OPs or players with `craftenginerecipe.admin`. This is the default for namespaces.
- `permission`: players also need the permission specified by `permission`; an empty permission does not grant access to regular players.

Category permissions control browsing and search access. They do not change item usage or crafting permissions, or hide ingredients in recipes the player is already allowed to open.

A namespace's `icon-name`, `item-lore`, `icon-lore`, and `sub-icon-lore` override the global defaults in the language file. These fields support MiniMessage, such as `<gold>Text</gold>`. `<name>` is the category name. `<count>` is the number of direct member entries, including subcategory references; it is not a recursive count of unique items.

### Hiding Items in a Category

Place `hidden-items` at the same level as the entry's `visibility` and `permission`. This example hides all items directly inside Farmer's Delight's GUI and display-model categories while keeping items in other categories, such as food, blocks, and tools:

```yaml
entries:
  - id: farmersdelight
    visibility: player
    permission: ''
    hidden-items:
      - category: farmersdelight:gui
        items:
          - '*'
      - category: farmersdelight:item_display
        items:
          - '*'
```

If `entries` already contains `- id: farmersdelight`, add `hidden-items` to that entry. Do not add a duplicate namespace entry or a second `entries` key. Edit the actual `plugins/CraftEngineRecipe/categories.yml`, then run `/cer reload`. The repository's `示例配置.yml` uses the category-entry hiding syntax described below; the plugin does not automatically read that example file.

`category` must be a full CraftEngine category ID. `'*'` must be quoted and hides all direct items in that category. Prefix patterns such as `farmersdelight:*` are not supported. To hide individual items, replace `'*'` with specific IDs:

```yaml
    hidden-items:
      - category: farmersdelight:gui
        items:
          - farmersdelight:recipe_book
          - farmersdelight:slot
```

Both browsing lists and search results follow these rules, for administrators and regular players alike. Category icons and subcategory entries remain visible. Add a separate rule for a subcategory to hide its items. An item may still appear if it also belongs to another visible category. These settings do not delete actual items, prevent crafting, or remove the item from other recipes' ingredient slots.

In CraftEngine's category configuration, `hidden: true` controls top-level category visibility. A category referenced by `#categoryID` in its parent's `list` can still be accessed as a subcategory. This differs from hiding items here. For GUI and display-model categories referenced by a root category, the two rules above hide their direct items.

### Hiding Both Category Entries and Their Items

To also remove the GUI and display-model category buttons, use `hidden-categories` under the corresponding namespace entry:

```yaml
entries:
  - id: farmersdelight
    visibility: player
    permission: ''
    hidden-categories:
      - farmersdelight:gui
      - farmersdelight:item_display
```

Place `hidden-categories` at the same level as the entry's `visibility` and `permission`. Use full category IDs with exact matching; `'*'` is not supported. This removes the corresponding entries from the top category bar and parent category content, skips their search paths, and prevents opening them directly. The rules apply to administrators and regular players, with no placeholder buttons. Descendant categories are not searched through a hidden path. If an item or subcategory has another visible entry point, that path remains accessible. Category relationships and item definitions are retained, and CraftEngine's actual crafting behavior is unchanged.

For Farmer's Delight, the root category and other subcategories remain visible, while the GUI and item_display entries and their search paths are hidden.

| Setting | Category Entry | Category Items | Use Case |
| --- | --- | --- | --- |
| `hidden-items` + `items: ['*']` | Kept | Direct items hidden | Keep the category entry and filter its items |
| `hidden-categories` | Hidden | Entire category browsing/search path skipped | Remove both the category button and its contents |

## Custom Buttons

Insert the following entry into `entries:` in `categories.yml`:

```yaml
  - display:
      material: minecraft:compass
      name: '&eReturn to Spawn'
      lore:
        - '&7Left-click to teleport to spawn'
    visibility: player
    actions:
      left:
        - 'sound: UI_BUTTON_CLICK'
        - close
        - 'delay: 0.1'
        - 'command: spawn'
```

Button `display.name` and `display.lore` use `&` color codes. CraftEngine icons can be specified as `source:CraftEngine:namespace:itemID`. Buttons also support `visibility` and `permission`, and are visible to all players by default.

Supported click types are `all`, `left`, `right`, `shift_left`, `shift_right`, and `middle`. If a matching type is not configured, the button falls back to `all`. Buttons with only sound actions and no commands can serve as decorations. `delay` uses a scheduler and does not put the main thread to sleep.

`console` and `op` are privileged actions and should only be configured by trusted administrators. For more examples, see `categories-example.yml`, generated on the first startup.

## Menu Sounds

In `config.yml`, `sounds` accepts `sound`, `sound-volume`, and `sound-volume-pitch`. For example:

```yaml
sounds:
  change-category: 'UI_BUTTON_CLICK-1-2'
  change-page: minecraft:item.book.page_turn
  click: minecraft:ui.button.click
  back: minecraft:item.book.page_turn
  search: minecraft:ui.toast.in
  open-menu: 'BLOCK_IRON_DOOR_OPEN-1-2'
  close-menu: 'BLOCK_IRON_DOOR_CLOSE-1-2'
```

`UI_BUTTON_CLICK-1-2` can also be written as `minecraft:ui.button.click-1-2`. `1` is the volume and `2` is the pitch, **not the number of times to play the sound**. Decimal values such as `0.5` and `0.8` are also supported. Without explicit parameters, all `sounds.*` entries default to volume 0.7 and pitch 1; button actions default to volume 1 and pitch 1. Custom buttons in `categories.yml` can still configure their own sounds through `actions`, for example:

```yaml
actions:
  left:
    - 'sound: UI_BUTTON_CLICK-1-2'
```

## Common Settings and Troubleshooting

| Issue / Goal | What to Do |
| --- | --- |
| `/cer` is empty on the first run | As an administrator, check `/cer unapplied`; copy namespaces into the main category configuration and reload |
| A player cannot see a category | Check whether it has been applied, namespace visibility, permissions, and whether CraftEngine has a corresponding top-level category |
| An item cannot be found in search | Check whether it belongs to an accessible category and whether it is hidden; try its full ID; reload CraftEngine first after changing items |
| Data is not ready | Wait for indexing; run `/cer reload`; if it keeps failing, check the full console exception and YAML indentation |
| Garbled background or missing textures | Check the resource pack installation directory, CraftEngine pack-loading logs, generation/distribution steps, and whether players have downloaded the new pack |
| Clicking an item does nothing | CraftEngine may have no recipe for the selected direction; left-click for outputs, right-click for uses; private recipes from other plugins are not guaranteed to be included |
| Recipes rotate too quickly | Increase `recipe.rotate-interval`; valid range: 5–200 ticks; default: 30 |
| Disable the shortcut | Set `hotkey.enabled: false` and reload |
| Open without sneaking | Set `hotkey.require-sneak: false`; this also takes over the regular swap-offhand key |
| Wrong Java version | The current JAR requires Java 25; check which Java executable starts the server |

Search limits: `search.max-results` accepts 1–1000, and `search.max-length` accepts 1–64. For large item collections, keeping the default limit of 200 results is recommended.

The menu layout has 6 rows of 9 slots. By default, `C` represents categories, `X` represents content, and `#` is a reserved empty slot. Each button's character is determined by its `char` setting. Use a different single character for every button, without conflicting with category/content characters or `#`. Spaces and tabs are ignored, unknown characters become empty slots, short rows are padded, and long rows are truncated. Missing rows are padded to 6; extra rows are ignored. To disable a button, set its `char` to `''` and replace its character in the layout with `#`.

`category-bar.source: ce` follows CraftEngine's top-level categories. Full category IDs in `category-bar.list`, such as `farmersdelight:categories`, only take effect when the source is `custom`. Their namespaces must still be applied in this plugin's `categories.yml`. `content.empty` only uses `material` and the name/lore from the language file; it does not support `char` or `item-model`.

## Building from Source and Upgrading

Install JDK 25 and Maven 3.9+, then run the following in the project directory:

```bash
mvn clean verify
```

The default build targets Minecraft 26.2. Use a Maven profile to select a target version:

```bash
mvn clean verify -Pmc-26.1
mvn clean verify -Pmc-26.2
mvn clean verify -Pmc-26.3
```

A single build writes its JAR to `target/` with the matching `mc26.1`, `mc26.2`, or `mc26.3` filename suffix. To build all variants at once, run:

```bash
./build-all.sh
```

