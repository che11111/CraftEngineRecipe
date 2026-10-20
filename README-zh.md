<p align="center">
  <img src="img/CraftEngineRecipe.png" alt="CraftEngineRecipe Logo" width="128">
</p>

<h1 align="center">CraftEngineRecipe</h1>

<p align="center">基于 CraftEngine 的物品与配方浏览插件，支持分类浏览、拼音搜索和配方用途查询。CER不仅仅是作为配方书，更像是一本CE物品图鉴。</p>


<p align="center">
  <img src="https://img.shields.io/badge/Minecraft-26.1.2%20%2F%2026.2%20%2F%2026.3-3fb950" alt="Minecraft 26.1.2 / 26.2 / 26.3">
  <a href="https://che11111.github.io/CraftEngineRecipe-wiki/docs/guide-zh/requirements/"><img src="https://img.shields.io/badge/CraftEngine-26.8.1_API+-5865F2" alt="CraftEngine 26.8.1 编译 API，运行要求见 Wiki"></a>
  <img src="https://img.shields.io/badge/Java-25-orange" alt="Java 25">
  <img src="https://img.shields.io/badge/Paper-supported-blue" alt="Paper supported">
  <a href="https://che11111.github.io/CraftEngineRecipe-wiki/docs/guide-zh/requirements/#folia-适配"><img src="https://img.shields.io/badge/Folia-experimental-blueviolet" alt="Folia 实验性支持，验证范围见 Wiki"></a>
</p>

<p align="center">
  <a href="README.md">English</a> · <a href="https://che11111.github.io/CraftEngineRecipe-wiki/docs/intro-zh/">使用 Wiki</a> · <a href="#快速上手">快速上手</a> · <a href="https://che11111.github.io/CraftEngineRecipe-wiki/docs/guide-zh/troubleshooting/">常见问题</a>
</p>

## 使用演示

<table>
  <tr>
    <td align="center"><strong>菜单展示</strong><br><small>按命名空间分类布局与各种合成器分类布局</small></td>
    <td align="center"><strong>功能展示</strong><br><small>查询手持物品配方、准星目标配方与使用搜索框</small></td>
  </tr>
  <tr>
    <td align="center">
      <img src="img/Menu_Display.gif" alt="菜单展示：按命名空间分类布局与各种合成器分类布局" width="100%">
    </td>
    <td align="center">
      <img src="img/Feature_Display.gif" alt="功能展示：查询手持物品配方、准星目标配方与使用搜索框" width="100%">
    </td>
  </tr>
</table>

## 功能与特色

- 自带清晰好用的UI画面，**自动上传**至CE资源包目录下。
- 两种分类逻辑页面：**按CE分类** 与 **按合成器分类**。
- 菜单中的物品，左键查看其**配方**、右键查看可**参与配方**。
- **自定义**分类顺序、可见权限、隐藏规则，自定义菜单**按钮**。
- 自定义按钮可执行任意指令，以前往其他页面。
- 按物品 ID、名称、翻译键、中文拼音或首字母搜索。
- 查看 `主手` / `准星` 物品配方，有指令也有菜单快捷按钮。
- `Shift+F`快捷键，快速打开配方菜单，可设置启用与否。

## 运行要求

Java **25**、Minecraft **26.1.2 / 26.2 / 26.3** 对应的 **Paper / Folia**，以及兼容该服务端的 **CraftEngine**。


## 快速上手

1. 安装兼容当前服务端的 CraftEngine 并加载物品、分类和配方，安装对应版本的 CraftEngineRecipe。

2. 首次运行时，插件会自动将背景与按钮资源安装到 `plugins/CraftEngine/resources/cer_resource/`，（无需手动复制，后续重启或重载不会再次安装）。再通过 CraftEngine 生成并分发资源包（如 `/ce reload all`，以所用版本为准），让客户端接受资源包。详见 [资源包说明](https://che11111.github.io/CraftEngineRecipe-wiki/docs/guide-zh/getting-started/#资源包的应用)。

3. 等待数据就绪，管理员执行 `/cer unapplied` 预览待应用分类。

4. 将 `plugins/CraftEngineRecipe/categories-unapplied.yml` 中需要的命名空间条目复制到 `categories.yml` 的 `entries` 下，例如：

   ```yaml
   entries:
     - id: farmersdelight
       visibility: player
       permission: ''
   ```

   将 `farmersdelight` 换成实际存在的命名空间；已有 `entries` 时追加条目即可。

5. 执行 `/cer reload`，完成后用 `/cer` 或 Shift+F 打开菜单。左键物品查看配方，右键查看可参与配方。


首次正式菜单为空时，请先完成分类应用。详细操作见 [使用 Wiki](https://che11111.github.io/CraftEngineRecipe-wiki/docs/intro-zh/)。

## 常用命令

| 命令 | 用途 |
| --- | --- |
| `/cer` | 打开正式菜单，别名 `/cg`、`/browser`、`Shift+F` |
| `/cer search` | 搜索物品 |
| `/cer hand` | 查看主手原版或 CE 物品的合成配方，遵循已配置的分类限制 |
| `/cer target` | 查看准星所指原版或 CE 方块的合成配方，遵循已配置的分类限制 |
| `/cer unapplied` | 预览待应用分类（管理员） |
| `/cer reload` | 重载配置与索引（管理员） |

`Shift+F`快捷键默认开启，但可在`config.yml`中关闭

管理权限为 `craftenginerecipe.admin`，OP 默认拥有。

## 配置文档

[分类与权限](https://che11111.github.io/CraftEngineRecipe-wiki/docs/guide-zh/categories/) · [隐藏物品](https://che11111.github.io/CraftEngineRecipe-wiki/docs/guide-zh/categories/#只隐藏物品保留分类按钮) · [隐藏分类按钮](https://che11111.github.io/CraftEngineRecipe-wiki/docs/guide-zh/categories/#隐藏分类按钮及内容) · [自定义按钮](https://che11111.github.io/CraftEngineRecipe-wiki/docs/guide-zh/custom-buttons/) · [音效](https://che11111.github.io/CraftEngineRecipe-wiki/docs/guide-zh/menu-sounds/) · [排障](https://che11111.github.io/CraftEngineRecipe-wiki/docs/guide-zh/troubleshooting/) · [更新插件](https://che11111.github.io/CraftEngineRecipe-wiki/docs/guide-zh/updating/) · [更新日志](https://che11111.github.io/CraftEngineRecipe-wiki/docs/guide-zh/changelog/)

## 使用统计

[查看 bStats 使用统计](https://bstats.org/plugin/bukkit/CraftEngineRecipe/34772)。

## 鸣谢

本项目是对于nsoer的CEGUI-1.0.0.jar项目的二次开发，在此基础上增加了很多功能，感谢nsoer对于部分代码以及部分贴图文件的授权。

感谢ChatGPT Astra的帮助，尤其感谢DeepSeek在10月15日的崩溃使我收到了来自他的160元赠金。
