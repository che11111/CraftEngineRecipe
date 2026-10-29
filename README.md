<p align="center">
  <img src="img/CraftEngineRecipe.png" alt="CraftEngineRecipe Logo" width="128">
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
  <a href="README-zh.md">简体中文</a> · <a href="https://che11111.github.io/CraftEngineRecipe-wiki/docs/intro/">User Wiki</a> · <a href="#quick-start">Quick Start</a> · <a href="https://che11111.github.io/CraftEngineRecipe-wiki/docs/guide/troubleshooting/">FAQ</a>
</p>

## Demo

<table>
  <tr>
    <td align="center"><strong>Menu Showcase</strong><br><small>Namespace-based and workstation-based navigation</small></td>
    <td align="center"><strong>Feature Showcase</strong><br><small>Held-item lookup, targeted-block lookup, and search</small></td>
  </tr>
  <tr>
    <td align="center">
      <img src="img/Menu_Display.gif" alt="Menu Showcase: namespace and workstation layouts" width="100%">
    </td>
    <td align="center">
      <img src="img/Feature_Display.gif" alt="Feature Showcase: held item recipes, targeted block recipes, and search" width="100%">
    </td>
  </tr>
</table>

## Features

- **Ready to use**: automatically installs CraftEngine resources and multiple menu styles, with item-type and workstation browsing.
- **Two-way recipe lookup**: left-click for recipes and right-click for crafting uses, including main-hand, targeted-block, search, and inventory-craftable queries.
- **Fast search**: search by item ID, name, translation key, Chinese pinyin, or pinyin initials.
- **In-game management**: use `/cer manage` to edit categories, permissions, hiding rules, ordering, workstation order, and custom buttons, including moving entries between applied and unapplied configurations.
- **Unified recipe index**: vanilla, CraftEngine, and supported integration recipes are exported under `recipes/`, loaded together, and reloaded after edits; other plugins can publish through the CER Recipe API.
- **Integration management**: enable or disable CER's special integrations for other plugins and resource packs through `/cer integrations`.
- **Personalization and resume**: players can choose menu style, landing page, and reopen behavior; preferences and browsing sessions share `player-preferences.db`.

## Compatible Plugins or Content

| Logo | Plugin or content | CER integration |
| :---: | --- | --- |
| <a href="https://github.com/Xiao-MoMi/craft-engine"><img src="img/compatible-plugin-logos/craftengine.png" alt="CraftEngine" width="64"></a> | [CraftEngine](https://github.com/Xiao-MoMi/craft-engine) | Required core item and recipe source. CER reads available CE recipe types and indexes items, results, and usage data. |
| <a href="https://github.com/IOVEYOUMC0/Farmersdelight-Plugin"><img src="img/compatible-plugin-logos/farmersdelight.png" alt="Farmer's Delight" width="72"></a> | [Farmer's Delight](https://github.com/IOVEYOUMC0/Farmersdelight-Plugin) | Imports cooking pot, cutting board, skillet, and special recipes, with automatic export to CER recipe files. The skillet page uses the dedicated `cer_fd_gui` texture. |
| <a href="https://github.com/Nicoppara/KaleidoscopeCookery_Plugin"><img src="img/compatible-plugin-logos/kaleidoscope-cookery.png" alt="Kaleidoscope Cookery" width="64"></a> | [Kaleidoscope Cookery](https://github.com/Nicoppara/KaleidoscopeCookery_Plugin) | Imports pot, chopping board, millstone, shawarma spit, stockpot, steamer, and teapot recipes. Each appliance uses its corresponding dedicated `cer_kc_gui` texture. |
| <a href="https://github.com/Xiao-MoMi/Custom-Crops"><img src="img/compatible-plugin-logos/customcrops.png" alt="CustomCrops" width="64"></a> | [CustomCrops](https://github.com/Xiao-MoMi/Custom-Crops) | Builds crop pages for CustomCrops pots, covering seeds, products, seasons, water, fertilizer, sprinklers, greenhouses, and scarecrows. Pot pages use the dedicated `cer_ccrops_gui` texture. |
| <a href="https://github.com/che11111/WoodworkingSaw"><img src="img/compatible-plugin-logos/woodworkingsaw.png" alt="WoodworkingSaw" width="64"></a> | [WoodworkingSaw](https://github.com/che11111/WoodworkingSaw) | Publishes saw-table transformation rules from `transformations.yml` through the CER Recipe API. CER provides the shared `cer_api_gui` UI texture set for API recipes. |
| <a href="https://www.minebbs.com/resources/ultramarine-craftengine.17044/"><img src="img/compatible-plugin-logos/ultramarine.png" alt="Ultramarine 群青" width="64"></a> | [Ultramarine](https://www.minebbs.com/resources/ultramarine-craftengine.17044/) | Provides a namespace preset for Ultramarine content; hides items that neither have a result recipe nor participate in any crafting recipe. |

> CER provides dedicated recipe UI texture sets for the Farmer's Delight skillet, Kaleidoscope Cookery appliances, CustomCrops pot pages, and API recipes.

## Requirements

Java **25**, **Paper / Folia** for Minecraft **26.1.2 / 26.2 / 26.3**, and a compatible **CraftEngine** release.

## Quick Start

1. Install compatible versions of CraftEngine and CraftEngineRecipe, then wait for startup to finish.

2. As an administrator, run `/cer integrations` to enable CER's special integrations for other plugins and resource packs (for example, Farmer's Delight, Kaleidoscope Cookery, and others; see the compatibility list in this README).

3. As an administrator, run `/cer manage` to adjust categories, ordering, and hiding rules in game. (CER uses a whitelist model: recipes become visible only after an administrator configures them on this page.)

4. Players can run `/cer` or press `Shift+F` to open the item categories. They can also use `/cer items`, `/cer craft`, or `/cer search` to open a specific page directly.

## Configuration Layout

```text
plugins/CraftEngineRecipe/
├── config.yml                         # Main settings: language, sources, notices, browser defaults
├── integrations.yml                   # CER integration switches, export policies, category presets
├── player-preferences.db              # SQLite preferences and browsing sessions
├── navigation/                        # Active menu configuration
│   ├── categories.yml                 # Applied categories, permissions, hiding rules, buttons
│   ├── categories-unapplied.yml       # Detected categories and buttons not yet applied
│   └── workstations.yml               # Workstation order and page-specific custom buttons
├── example/                           # Example templates; not loaded as live configuration
│   ├── navigation/
│   │   └── categories-example.yml     # Syntax example for categories.yml
│   └── recipes/
│       └── recipes-example.yml        # Custom recipe file format example
├── lang/                              # Language files
│   └── integrations/
│       ├── common/{zh_CN,en_US}.yml             # Shared separators, yes/no text, and units
│       ├── craftengine/{zh_CN,en_US}.yml        # CraftEngine workstation names
│       ├── farmersdelight/{zh_CN,en_US}.yml     # Farmer's Delight titles, conditions, recipe notes
│       ├── kaleidoscope/{zh_CN,en_US}.yml       # Kaleidoscope appliance and processing text
│       ├── customcrops/{zh_CN,en_US}.yml        # CustomCrops pots, seasons, environments, fertilizer text
│       ├── cultivation/{zh_CN,en_US}.yml        # Cultivation station, seeds, substrates, planting notes
│       └── minecraft/{zh_CN,en_US}.yml          # Vanilla workstation display names
├── recipes/                           # Custom and exported recipes loaded by CER
└── resources/                         # Local menu resources installed into CraftEngine packs
```

Each `cer_menu_*` pack uses complete layout files under `menu/` and declares the pages each file serves. File names are arbitrary; CER reads only the `pages` declarations and performs no inheritance or merging:

```text
cer_menu_cute/
├── pack.yml                           # CraftEngine resource-pack metadata
├── menu/
│   ├── browse.yml                     # Item-category and workstation browser layouts
│   └── utility.yml                    # Management, unapplied, and craftable-page layouts
├── configuration/gui.yml              # Title-image font height, ascent, and texture reference
└── resourcepack/
    └── assets/cer_cute/textures/font/gui/cer_main.png  # Menu title texture
```

Exported titles, lore, and descriptions follow `config.yml > language`. Third-party item names remain controlled by the CraftEngine resource pack and the client's language.

## Common Commands

### Player Commands

| Command | Purpose |
| --- | --- |
| `/cer` | Open the main menu; shortcut `Shift+F` |
| `/cer craft` | Open the workstation browser directly |
| `/cer items` | Open the item-type browser directly |
| `/cer search` | Search for items |
| `/cer hand` | View recipes producing the vanilla or CraftEngine item in your main hand, respecting configured category restrictions |
| `/cer target` | View recipes producing the vanilla or CraftEngine block under your crosshair, respecting configured category restrictions |
| `/cer open <pack>` | Open the menu using an enabled UI pack |

### Administrator Commands

| Command | Purpose |
| --- | --- |
| `/cer export <craftengine\|farmersdelight\|kaleidoscope\|customcrops\|all>` | Re-read a source and replace its data under `recipes/` |
| `/cer integrations` | Open the unified integration and category-preset dialog |
| `/cer manage` | Open the Configuration Center to edit categories, hiding rules, ordering and workstation configuration in game |
| `/cer unapplied` | Browse recipes using category entries from `categories-unapplied.yml` |
| `/cer reload` | Reload configuration and indexes; recipe-file edits reload automatically |
| `/cer menu` | Show the default, enabled, and available UI packs |
| `/cer menu enable <pack\|all>` | Enable one style or every `cer_menu_*` style |
| `/cer menu disable <pack>` | Disable a non-default style |
| `/cer menu default <pack>` | Set the default style used by `/cer` and Shift+F |
| `/cer debug recipes` | Save all currently registered recipes as a local JSON diagnostic file |
| `/cer debug performance` | Show plugin operation timings, menu session/click counts, and server TPS/MSPT |
| `/cer debug performance reset` | Reset the plugin timing window before reproducing a problem |

Debug commands also work from the console and require `craftenginerecipe.admin`. Recipe exports are saved under `plugins/CraftEngineRecipe/debug/recipes-*.json` and contain separate Bukkit, CraftEngine, and `recipes/` display sections. Capture runs in short batches and file writing is asynchronous. Only one export may run at a time, followed by a 30-second cooldown. No data is uploaded. The file is a diagnostic snapshot, not an importable backup. Keep Bukkit/CE overlapping registrations in their respective sections and avoid reloading recipes during capture.

For lag diagnosis, run `/cer debug performance reset`, reproduce the slow menu action, then run `/cer debug performance`. Timings cover instrumented plugin operations only, may be nested, and must not be added together as a server CPU percentage. Use [spark](https://spark.lucko.me/) for full call stacks. Global TPS/MSPT may be unavailable on Folia. See [Diagnostics](docs/DEBUG.md) for field details.

`Shift+F` is enabled by default and requires sneaking by default. Both settings can be changed in `config.yml`.

Integration switches and namespace category presets are stored together in `integrations.yml`. `mode` accepts `ask`, `enabled`, or `disabled`; interactive enabling also sets `cultivation` to `true`:

```yaml
integrations:
  farmersdelight:
    mode: ask
    cultivation: true
    reexport-on-reload: true
    export-missing: true
    reexport-craftengine-on-ce-reload: true
    category-presets:
      - farmersdelight
```

`kaleidoscope` and `customcrops` use the same fields; Kaleidoscope links to `kaleidoscopecookery` through `category-presets: [kaleidoscopecookery]`. `disabled` stops export and indexing while preserving existing files under `recipes/<source>/`.

When an integration with `category-presets` is enabled, CER also applies those category presets. Clicking the integration button to enable it counts as approval for the linked presets. If `mode` is already `enabled`, linked presets are applied automatically on startup or reload.

Each category preset may define an optional `authors` list. CER reads `author` from the matching `pack.yml`, ignores case and extra whitespace, and supports comma-, semicolon-, slash-, ampersand-, and enumeration-comma-separated co-authors. Any single match is sufficient. Omit `authors` or leave it empty to match by namespace only.

Each category preset also has a `mode`: `ask` prompts when detected independently, `enabled` applies automatically after a matching detection, and `disabled` prevents automatic prompts and application. Clicking a resource-pack button writes `enabled`; clicking it again writes `disabled`. Disabling does not remove manually maintained entries already present in `categories.yml`, and no separate preset-state file is created.

`sources.reexport-on-reload` and `sources.reexport-craftengine-on-ce-reload` now apply only to CraftEngine. Each optional integration uses its own `integrations.<source>.*` settings in `integrations.yml`. The same file contains `category-presets`, used to ask administrators before applying complete namespace presets detected in CraftEngine.

`indexing.notify-admins-on-startup` defaults to `true` and controls the integration prompt shown after data is ready. If no administrator is online, the prompt appears when one joins.

Name colors are controlled centrally by `name-prefixes`. Values use MiniMessage and affect only the name text, not title images or shifts:

```yaml
name-prefixes:
  category-bar: '<white>'
  category-title: '<black><bold>'
  workstation-bar: '<white>'
  workstation-title: '<black><bold>'
  recipe-title: '<black><bold>'
```

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
