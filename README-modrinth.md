<p align="center">
  <img src="https://raw.githubusercontent.com/che11111/CraftEngineRecipe/main/img/CraftEngineRecipe.png" alt="CraftEngineRecipe Logo" width="128">
</p>

<h1 align="center">CraftEngineRecipe</h1>

<p align="center">An item and recipe browser for CraftEngine with category navigation, pinyin search, and recipe-usage lookup. CER is more than a recipe book: it can also serve as a complete catalog of CraftEngine items.</p>

> This README describes the unreleased 1.1.0 version. If you are using version 1.0.17, you can refer to the Wiki for its documentation first.

<p align="center">
  <img src="https://img.shields.io/badge/Minecraft-26.1.2%20%2F%2026.2%20%2F%2026.3-3fb950" alt="Minecraft 26.1.2 / 26.2 / 26.3">
  <a href="https://che11111.github.io/CraftEngineRecipe-wiki/docs/guide/requirements/"><img src="https://img.shields.io/badge/CraftEngine-26.8.1_API+-5865F2" alt="CraftEngine 26.8.1 compile-time API; see the Wiki for requirements"></a>
  <img src="https://img.shields.io/badge/Java-25-orange" alt="Java 25">
  <img src="https://img.shields.io/badge/Paper-supported-blue" alt="Paper supported">
  <a href="https://che11111.github.io/CraftEngineRecipe-wiki/docs/guide/requirements/#folia-support"><img src="https://img.shields.io/badge/Folia-experimental-blueviolet" alt="Experimental Folia support; see the Wiki for details"></a>
</p>

<p align="center">
  <a href="https://github.com/che11111/CraftEngineRecipe/blob/main/README-zh.md">简体中文</a> · <a href="https://che11111.github.io/CraftEngineRecipe-wiki/docs/intro/">User Wiki</a> · <a href="#quick-start">Quick Start</a> · <a href="https://che11111.github.io/CraftEngineRecipe-wiki/docs/guide/troubleshooting/">FAQ</a>
</p>

## Demo

<table>
  <tr>
    <td align="center"><strong>Menu Showcase</strong><br><small>Namespace-based and workstation-based navigation</small></td>
    <td align="center"><strong>Feature Showcase</strong><br><small>Held-item lookup, targeted-block lookup, and search</small></td>
  </tr>
  <tr>
    <td align="center">
      <img src="https://github.com/che11111/CraftEngineRecipe/blob/main/img/Menu_Display.gif?raw=true" alt="Menu Showcase: namespace and workstation layouts" width="100%">
    </td>
    <td align="center">
      <img src="https://github.com/che11111/CraftEngineRecipe/blob/main/img/Feature_Display.gif?raw=true" alt="Feature Showcase: held item recipes, targeted block recipes, and search" width="100%">
    </td>
  </tr>
</table>

## Features

- A clear, ready-to-use UI whose resources are **installed automatically** into CraftEngine's resource-pack directory.
- Two browsing modes: **browse by namespace** and **browse by workstation**.
- Workstation order and custom buttons are configured in `navigation/workstations.yml`; newly discovered workstations are appended automatically.
- Left-click an item to view its **recipes**; right-click to view recipes that **use it**.
- Customize category order, visibility permissions, hiding rules, and menu **buttons**.
- Custom buttons can run commands to open other menus or pages.
- Search by item ID, name, translation key, Chinese pinyin, or pinyin initials.
- Right-click **Search items** to list recipe outputs that can currently be completed with the player's inventory. Right-click again or press ESC to return to normal browsing.
- Bundled vanilla Chinese names make searches such as `小麦` and `木剑` work; recipe-file polling does not continuously occupy the server thread.
- TrMenu-like custom recipe files are supported. FarmersDelight, the FarmersDelight skillet, Kaleidoscope Cookery, and CustomCrops pot recipes are exported to `recipes/` on first use and then loaded from files.
- Fixed-result CraftEngine recipes are exported to `recipes/craftengine/` on first use and then read only from files. Vanilla recipes, vanilla cultivation data, CraftEngine cultivation data, and integration-specific cultivation data have independent source switches.
- Edited files under `recipes/` reload automatically. Pressing ESC remembers the current page and recipe back-stack for three minutes by default; right-clicking outside a menu returns to the main view corresponding to where it was opened.
- Right-click **Browse by workstation** to open Personalization. Each player can choose a main menu style and whether the last browsing position should be restored. Preferences are stored in the SQLite database `player-preferences.db`.
- During startup indexing, online administrators are told which recipe sources were detected. After CraftEngine reloads its configuration and resource packs, CER re-exports CraftEngine recipes and refreshes its index automatically.
- Look up recipes for the item in your **main hand** or the block under your **crosshair**, using commands or menu shortcuts.
- The **Shift+F** shortcut opens the menu quickly. It is enabled by default, requires sneaking by default, and is configurable.

## Requirements

Java **25**, **Paper / Folia** for Minecraft **26.1.2 / 26.2 / 26.3**, and a compatible **CraftEngine** release.

## Quick Start

1. Install a compatible version of CraftEngine, load its items, categories, and recipes, then install the matching CraftEngineRecipe version.

2. On first run, enabled menu packs plus `cer_kc_gui` and `cer_fd_gui` are installed as subpacks under `CraftEngine/resources/CraftEngineRecipe/subpacks/`. Cute, rough, vanilla-material, and map styles are enabled by default. Players can open a style with `/cer open <pack>`.

3. Wait for data initialization to finish, then run `/cer unapplied` as an administrator to preview categories that have not yet been applied.

4. Copy the desired namespace entries from `plugins/CraftEngineRecipe/navigation/categories-unapplied.yml` into `entries` in `navigation/categories.yml`, for example:

   ```yaml
   entries:
     - id: farmersdelight
       visibility: player
       permission: ''
   ```

   Replace `farmersdelight` with an existing namespace. If `entries` already exists, append the entry.

   Addons containing only one item or only recipe data can use `category-entry: false`. This omits the namespace category button while keeping items in search, direct queries, and workstation output pages.

5. Run `/cer reload`, then open the menu with `/cer` or Shift+F. Left-click an item for its recipes; right-click to see recipes that use it.

6. Right-click the **Browse by workstation** button in any menu to open Personalization. Players can choose their menu style and whether reopening restores the previous position. Preferences are stored in `player-preferences.db`.

7. By default, online administrators receive the detected recipe-source list when startup indexing begins. After CraftEngine reloads its configuration and resource packs, CER automatically re-exports CE recipes and refreshes its index.

If the main menu is empty on first use, apply the desired categories first. See the [User Wiki](https://che11111.github.io/CraftEngineRecipe-wiki/docs/intro/) for details.

## Configuration Layout

```text
plugins/CraftEngineRecipe/
├── config.yml
├── navigation/
│   ├── categories.yml
│   ├── categories-unapplied.yml
│   ├── workstations.yml
│   └── examples/
│       └── categories.yml
├── lang/
├── recipes/
└── resources/
```

Each `cer_menu_*` pack uses complete layout files under `menu/` and declares the pages each file serves. File names are arbitrary; CER reads only the `pages` declarations and performs no inheritance or merging:

```text
cer_menu_cute/
├── menu/
│   ├── browse.yml
│   └── utility.yml
├── configuration/gui.yml
└── resourcepack/
```

## Common Commands

| Command | Purpose |
| --- | --- |
| `/cer` | Open the main menu; aliases `/cg` and `/browser`; shortcut `Shift+F` |
| `/cer search` | Search for items |
| `/cer hand` | View recipes producing the vanilla or CraftEngine item in your main hand, respecting configured category restrictions |
| `/cer target` | View recipes producing the vanilla or CraftEngine block under your crosshair, respecting configured category restrictions |
| `/cer export <craftengine\|farmersdelight\|kaleidoscope\|customcrops\|all>` | Re-read a source and replace its data under `recipes/` (administrator) |
| `/cer manage` | Open Category Management to view applied/unapplied namespaces and edit unapplied entries (administrator) |
| `/cer unapplied` | Browse recipes using category entries from `categories-unapplied.yml` (administrator) |
| `/cer reload` | Reload configuration and indexes (administrator; recipe-file edits reload automatically) |
| `/cer open <pack>` | Open the menu using an enabled UI pack |
| `/cer menu` | Show the default, enabled, and available UI packs (administrator) |
| `/cer menu enable <pack\|all>` | Enable one style or every `cer_menu_*` style (administrator) |
| `/cer menu disable <pack>` | Disable a non-default style (administrator) |
| `/cer menu default <pack>` | Set the default style used by `/cer` and Shift+F (administrator) |
| `/cer debug recipes` | Save all currently registered recipes as a local JSON diagnostic file (administrator) |
| `/cer debug performance` | Show plugin operation timings, menu session/click counts, and server TPS/MSPT (administrator) |
| `/cer debug performance reset` | Reset the plugin timing window before reproducing a problem (administrator) |

Debug commands also work from the console and require `craftenginerecipe.admin`. Recipe exports are saved under `plugins/CraftEngineRecipe/debug/recipes-*.json` and contain separate Bukkit, CraftEngine, and `recipes/` display sections. Capture runs in short batches and file writing is asynchronous. Only one export may run at a time, followed by a 30-second cooldown. No data is uploaded. The file is a diagnostic snapshot, not an importable backup. Keep Bukkit/CE overlapping registrations in their respective sections and avoid reloading recipes during capture.

For lag diagnosis, run `/cer debug performance reset`, reproduce the slow menu action, then run `/cer debug performance`. Timings cover instrumented plugin operations only, may be nested, and must not be added together as a server CPU percentage. Use [spark](https://spark.lucko.me/) for full call stacks. Global TPS/MSPT may be unavailable on Folia. See the [Diagnostics document](https://github.com/che11111/CraftEngineRecipe/blob/main/docs/DEBUG.md) for field details.

`Shift+F` is enabled by default and requires sneaking by default. Both settings can be changed in `config.yml`.

Setting `sources.reexport-on-reload` to `true` deletes and regenerates every enabled source folder after each successful plugin reload.

`sources.reexport-craftengine-on-ce-reload` defaults to `true`. After CraftEngine reloads its configuration and resource packs, CER re-exports `recipes/craftengine/` and refreshes its index. Set it to `false` to disable this behavior.

`indexing.notify-admins-on-startup` defaults to `true` and controls whether online administrators receive the startup index-build notice.

The administrator permission is `craftenginerecipe.admin`; OPs have it by default.

## Configuration Guide

[Categories and Permissions](https://che11111.github.io/CraftEngineRecipe-wiki/docs/guide/categories/) · [Hide Items](https://che11111.github.io/CraftEngineRecipe-wiki/docs/guide/categories/#hide-items-and-keep-the-category-button) · [Hide Category Buttons](https://che11111.github.io/CraftEngineRecipe-wiki/docs/guide/categories/#hide-category-buttons-and-contents) · [Custom Buttons](https://che11111.github.io/CraftEngineRecipe-wiki/docs/guide/custom-buttons/) · [Sounds](https://che11111.github.io/CraftEngineRecipe-wiki/docs/guide/menu-sounds/) · [Troubleshooting](https://che11111.github.io/CraftEngineRecipe-wiki/docs/guide/troubleshooting/) · [Updating](https://che11111.github.io/CraftEngineRecipe-wiki/docs/guide/updating/) · [Changelog](https://che11111.github.io/CraftEngineRecipe-wiki/docs/guide/changelog/)


## Usage Statistics

[View usage statistics on bStats](https://bstats.org/plugin/bukkit/CraftEngineRecipe/34772).

[![bStats](https://bstats.org/signatures/bukkit/CraftEngineRecipe.svg)](https://bstats.org/plugin/bukkit/CraftEngineRecipe/34772)

## License

The source code of this project is open source under the **GNU General Public License v3.0 (GPL-3.0)**. 

Texture files in this project are licensed separately: they are authorized **only for use by the CraftEngineRecipe (CER) plugin**. Without explicit permission, other plugins may not copy, modify, distribute, or reuse the texture files from this project.

## Credits

Thank you to HuiDu_OwO for drawing the GUI textures used by the `cer_menu_cute` layout for this project without charge.

Thank you to SnowCutieOwO for drawing the GUI textures used by the `cer_menu_map` layout for this project.

Thank you to nsoer for providing CEGUI-1.0.0.jar and for authorizing the use of portions of the `cer_menu_rough` texture assets.

Thank you to ChatGPT Astra for its help, and especially to DeepSeek, whose outage on October 15 resulted in a CNY 160 credit for me.
