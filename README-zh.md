<p align="center">
  <img src="img/CraftEngineRecipe.png" alt="CraftEngineRecipe Logo" width="128">
</p>

<h1 align="center">CraftEngineRecipe</h1>

<p align="center">基于 CraftEngine 的物品与配方浏览插件，支持分类浏览、拼音搜索和配方用途查询。CER 不仅是配方书，也可以作为完整的 CE 物品图鉴。</p>

> 本 README 对应尚未发布的 1.1.0 版本；如果你正在使用 1.0.17，可先参考 Wiki 中的说明。


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

- 自带清晰易用的 UI 画面，资源会**自动安装**到 CraftEngine 资源包目录。
- 两种分类逻辑页面：**按命名空间分类** 与 **按合成器分类**。
- 菜单中的物品，左键查看其**配方**、右键查看可**参与配方**。
- **自定义**分类顺序、可见权限、隐藏规则，自定义菜单**按钮**。
- 自定义按钮可执行任意指令，以前往其他页面。
- 按物品 ID、名称、翻译键、中文拼音或首字母搜索。
- 内置原版中文名称索引，可直接搜索“小麦”“木剑”等；配方文件监听不会持续占用主线程。
- 支持 TrMenu 风格的自定义配方文件；农夫乐事、农夫乐事煎锅、森罗物语和 CustomCrops 种植盆配方首次自动导出到 `recipes/`，之后统一从文件加载。
- CE 固定产物配方首次导出到 `recipes/craftengine/`，之后只读取文件；原版配方、原版栽培、CE 栽培及各集成栽培数据均有独立开关。
- `recipes/` 文件修改后自动重载；玩家按 ESC 离开时保留当前页面与配方返回层级，默认记忆 3 分钟（可配置），在菜单外右键会返回进入来源对应的主页面。
- 右键“按合成器浏览”可打开个性化页面；玩家可单独选择主菜单样式和是否恢复上次浏览位置，偏好保存在 SQLite 数据库 `player-preferences.db` 中。
- 启动构建索引时会向在线管理员提示检测到的配方来源；CraftEngine 重载配置和资源包后会自动重新导出 CE 配方并刷新索引。
- 查看 `主手` / `准星` 物品配方，有指令也有菜单快捷按钮。
- `Shift+F`快捷键，快速打开配方菜单，可设置启用与否。

## 运行要求

Java **25**、Minecraft **26.1.2 / 26.2 / 26.3** 对应的 **Paper / Folia**，以及兼容该服务端的 **CraftEngine**。


## 快速上手

1. 安装兼容当前服务端的 CraftEngine 并加载物品、分类和配方，安装对应版本的 CraftEngineRecipe。

2. 首次运行时会把已启用菜单包及 `cer_kc_gui`、`cer_fd_gui` 统一放在 `CraftEngine/resources/CraftEngineRecipe/subpacks/` 下。默认启用可爱版、粗版、原版材质版和地图版。玩家用 `/cer open <包名>` 打开对应界面。

3. 等待数据就绪，管理员执行 `/cer unapplied` 预览待应用分类。

4. 将 `plugins/CraftEngineRecipe/categories-unapplied.yml` 中需要的命名空间条目复制到 `categories.yml` 的 `entries` 下，例如：

   ```yaml
   entries:
     - id: farmersdelight
       visibility: player
       permission: ''
   ```

   将 `farmersdelight` 换成实际存在的命名空间；已有 `entries` 时追加条目即可。

   只有一个物品、只提供配方的附属包可以设置 `category-entry: false`。这样不会在按命名空间分类页面添加按钮，但物品仍会进入搜索、直接查询和合成器产物页面。

5. 执行 `/cer reload`，完成后用 `/cer` 或 Shift+F 打开菜单。左键物品查看配方，右键查看可参与配方。

6. 右键任意菜单中的“按合成器浏览”按钮可打开个性化页面。玩家可以选择自己的菜单样式，以及关闭后再次打开时是否恢复上次浏览位置；设置保存在 `player-preferences.db`。

7. 默认情况下，插件启动构建索引时会向在线管理员提示检测到的配方来源插件；CraftEngine 重载配置与资源包后会自动重新导出 CE 配方并刷新索引。

首次正式菜单为空时，请先完成分类应用。详细操作见 [使用 Wiki](https://che11111.github.io/CraftEngineRecipe-wiki/docs/intro-zh/)。

## 常用命令

| 命令 | 用途 |
| --- | --- |
| `/cer` | 打开正式菜单，别名 `/cg`、`/browser`、`Shift+F` |
| `/cer search` | 搜索物品 |
| `/cer hand` | 查看主手原版或 CE 物品的合成配方，遵循已配置的分类限制 |
| `/cer target` | 查看准星所指原版或 CE 方块的合成配方，遵循已配置的分类限制 |
| `/cer export <craftengine\|farmersdelight\|kaleidoscope\|customcrops\|all>` | 重新获取对应来源并替换 `recipes/` 下的数据（管理员） |
| `/cer unapplied` | 预览待应用分类（管理员） |
| `/cer reload` | 手动重载配置与索引（管理员；配方文件修改会自动加载） |
| `/cer open <包名>` | 使用指定且已启用的 UI 样式打开菜单 |
| `/cer menu` | 查看默认、已启用和全部可用样式（管理员） |
| `/cer menu enable <包名\|all>` | 启用一个样式或全部 `cer_menu_*`（管理员） |
| `/cer menu disable <包名>` | 停用非默认样式（管理员） |
| `/cer menu default <包名>` | 设置 `/cer` 与 Shift+F 使用的默认样式（管理员） |
| `/cer debug recipes` | 将服务器当前注册的全部配方保存为本地 JSON 调试文件（管理员） |
| `/cer debug performance` | 查看插件操作耗时、菜单会话/点击计数及服务器 TPS/MSPT（管理员） |
| `/cer debug performance reset` | 重置插件计时窗口，再复现问题进行观察（管理员） |

调试指令也可在控制台执行，统一使用 `craftenginerecipe.admin` 权限。配方导出保存在 `plugins/CraftEngineRecipe/debug/recipes-*.json`，分别收录 Bukkit、CraftEngine 以及 `recipes/` 中的展示配方。采集分短批次执行，文件异步写入；同一时间仅允许一次导出，结束后冷却 30 秒，不会上传数据。文件是诊断快照，不是可导入的备份。Bukkit/CE 重叠注册保留在各自章节，采集期间请避免重载配方。

排查卡顿时，先执行 `/cer debug performance reset`，复现菜单操作后再执行 `/cer debug performance`。计时只覆盖已埋点的插件操作，可能嵌套，不能相加为服务器 CPU 占比；完整调用栈请配合 [spark](https://spark.lucko.me/)。Folia 的全服 TPS/MSPT 可能不可用。字段说明见 [调试文档](docs/DEBUG-zh.md)。

`Shift+F` 快捷打开菜单默认开启，且默认要求潜行；可在 `config.yml` 中关闭快捷键或取消潜行要求。

将 `sources.reexport-on-reload` 设为 `true` 后，每次插件重载成功都会删除并重新生成所有已启用来源的配方目录。

`sources.reexport-craftengine-on-ce-reload` 默认为 `true`。CraftEngine 完成配置与资源包重载后，CER 会重新导出 `recipes/craftengine/` 并刷新索引；不需要该行为时可设为 `false`。

`indexing.notify-admins-on-startup` 默认为 `true`，用于控制启动时是否向在线管理员发送索引构建提示。

管理权限为 `craftenginerecipe.admin`，OP 默认拥有。

## 配置文档

[分类与权限](https://che11111.github.io/CraftEngineRecipe-wiki/docs/guide-zh/categories/) · [隐藏物品](https://che11111.github.io/CraftEngineRecipe-wiki/docs/guide-zh/categories/#只隐藏物品保留分类按钮) · [隐藏分类按钮](https://che11111.github.io/CraftEngineRecipe-wiki/docs/guide-zh/categories/#隐藏分类按钮及内容) · [自定义按钮](https://che11111.github.io/CraftEngineRecipe-wiki/docs/guide-zh/custom-buttons/) · [音效](https://che11111.github.io/CraftEngineRecipe-wiki/docs/guide-zh/menu-sounds/) · [排障](https://che11111.github.io/CraftEngineRecipe-wiki/docs/guide-zh/troubleshooting/) · [更新插件](https://che11111.github.io/CraftEngineRecipe-wiki/docs/guide-zh/updating/) · [更新日志](https://che11111.github.io/CraftEngineRecipe-wiki/docs/guide-zh/changelog/)



## 使用统计

[查看 bStats 使用统计](https://bstats.org/plugin/bukkit/CraftEngineRecipe/34772)。

[![bStats](https://bstats.org/signatures/bukkit/CraftEngineRecipe.svg)](https://bstats.org/plugin/bukkit/CraftEngineRecipe/34772)

## 许可证

本项目的源代码采用 **GNU General Public License v3.0（GPL-3.0）** 开源。

本项目中的贴图文件采用单独授权：**仅授权给 CraftEngineRecipe（CER）插件使用**。未经明确许可，其他插件不得复制、修改、分发或复用本项目的贴图文件。

## 鸣谢

感谢 HuiDu_OwO 无偿为本项目绘制了 cer_menu_cute 布局的GUI贴图。

感谢 SnowCutieOwO 为本项目绘制了 cer_menu_map 布局的GUI贴图。

感谢 nsoer 提供的 CEGUI-1.0.0.jar 以及 cer_menu_rough 布局的部分贴图文件的授权。

感谢 ChatGPT Astra 的帮助，尤其感谢 DeepSeek 在10月15日的崩溃使我收到了来自他的160元赠金。
