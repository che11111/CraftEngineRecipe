<p align="center">
  <img src="https://raw.githubusercontent.com/che11111/CraftEngineRecipe/main/img/CraftEngineRecipe.png" alt="CraftEngineRecipe Logo" width="128">
</p>

<h1 align="center">CraftEngineRecipe</h1>

<p align="center">An item and recipe browser for CraftEngine, with category navigation, pinyin search, and recipe usage lookup.</p>

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
    <td align="center"><strong>Page Display</strong></td>
    <td align="center"><strong>Search Display</strong></td>
  </tr>
  <tr>
    <td align="center">
      <img src="https://github.com/che11111/CraftEngineRecipe/blob/main/img/202610291172005.gif?raw=true" alt="Page Display Demo" width="100%">
    </td>
    <td align="center">
      <img src="https://github.com/che11111/CraftEngineRecipe/blob/main/img/202610291192714.gif?raw=true" alt="Search Display Demo" width="100%">
    </td>
  </tr>
</table>

## Features

- A clear, ready-to-use UI, with assets **automatically installed** in CraftEngine's resource pack directory.
- Two browsing modes: **CraftEngine categories** and **workstation types**.
- **Left-click** an item to view its **recipes**; **right-click** to view recipes that **use it**.
- **Customize** category order, visibility permissions, hiding rules, and menu **buttons**.
- Custom buttons can run any command to open other menus or pages.
- Search by item ID, name, translation key, Chinese pinyin, or pinyin initials.
- Look up recipes for the item in your `main hand` or the block under your `crosshair`, using commands or menu shortcuts.
- Open the recipe menu quickly with the configurable `Shift+F` shortcut.

## Requirements

Java **25**, **Paper / Folia** for Minecraft **26.1.2 / 26.2 / 26.3**, and a compatible **CraftEngine** release.


## Quick Start

1. Install a version of CraftEngine compatible with your server, load the items, categories, and recipes, then install the corresponding version of CraftEngineRecipe.

2. On its first run, the plugin automatically installs the background and button assets into `plugins/CraftEngine/resources/cer_resource/`. No manual copying is required, and subsequent restarts or reloads will not install them again. Use CraftEngine to generate and distribute the resource pack (for example, `/ce reload all`, depending on your version), then have clients accept it. See [Applying the Resource Pack](https://che11111.github.io/CraftEngineRecipe-wiki/docs/guide/getting-started/#applying-the-resource-pack).

3. Wait for the data to be ready, then run `/cer unapplied` as an administrator to preview categories that have not yet been applied.

4. Copy the namespace entries you want from `plugins/CraftEngineRecipe/categories-unapplied.yml` into `entries` in `categories.yml`, for example:

   ```yaml
   entries:
     - id: farmersdelight
       visibility: player
       permission: ''
   ```

   Replace `farmersdelight` with an existing namespace. If `entries` already exists, append the entries to it.

5. Run `/cer reload`. Once it finishes, open the menu with `/cer` or Shift+F. Left-click an item to view its recipes; right-click to view recipes that use it.

If the main menu is empty on the first run, apply your categories first. See the [User Wiki](https://che11111.github.io/CraftEngineRecipe-wiki/docs/intro/) for detailed instructions.

## Common Commands

| Command | Purpose |
| --- | --- |
| `/cer` | Open the main menu; aliases: `/cg`, `/browser`; shortcut: `Shift+F` |
| `/cer search` | Search for items |
| `/cer hand` | View recipes producing your main-hand vanilla or CE item, respecting configured category restrictions |
| `/cer target` | View recipes producing the vanilla or CE block under your crosshair, respecting configured category restrictions |
| `/cer unapplied` | Preview unapplied categories (administrator) |
| `/cer reload` | Reload configuration and indexes (administrator) |

The `Shift+F` shortcut is enabled by default and can be disabled in `config.yml`.

The administrator permission is `craftenginerecipe.admin`. OPs have it by default.

## Configuration Guide

[Categories and Permissions](https://che11111.github.io/CraftEngineRecipe-wiki/docs/guide/categories/) · [Hide Items](https://che11111.github.io/CraftEngineRecipe-wiki/docs/guide/categories/#hide-items-and-keep-the-category-button) · [Hide Category Buttons](https://che11111.github.io/CraftEngineRecipe-wiki/docs/guide/categories/#hide-category-buttons-and-contents) · [Custom Buttons](https://che11111.github.io/CraftEngineRecipe-wiki/docs/guide/custom-buttons/) · [Sounds](https://che11111.github.io/CraftEngineRecipe-wiki/docs/guide/menu-sounds/) · [Troubleshooting](https://che11111.github.io/CraftEngineRecipe-wiki/docs/guide/troubleshooting/) · [Updating](https://che11111.github.io/CraftEngineRecipe-wiki/docs/guide/updating/) · [Changelog](https://che11111.github.io/CraftEngineRecipe-wiki/docs/guide/changelog/)

## Usage Statistics

[View usage statistics on bStats](https://bstats.org/plugin/bukkit/CraftEngineRecipe/34772). 

## Credits

This project is a derivative work of nsoer’s CEGUI-1.0.0.jar, with many additional features and improvements. Special thanks to nsoer for granting permission to use portions of the original code and texture assets.

