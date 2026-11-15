<p align="center">
  <img src="img/CraftEngineRecipe.png" alt="CraftEngineRecipe Logo" width="128">
</p>

<h1 align="center">CraftEngineRecipe</h1>

<p align="center">基于 CraftEngine 的物品与配方浏览插件，支持分类浏览、拼音搜索和配方用途查询。CER 不仅是配方书，也可以作为完整的 CE 物品图鉴。</p>

> 目前 1.2.0-alpha.1 版本可能不太稳定。请先使用 Release 中发布的版本。


<p align="center">
  <img src="https://img.shields.io/badge/Minecraft-1.21.7--1.21.11%20%2F%2026.1--26.3-3fb950" alt="Minecraft 1.21.7-1.21.11 / 26.1-26.3">
  <a href="https://che11111.github.io/CraftEngineRecipe-wiki/docs/guide-zh/requirements/"><img src="https://img.shields.io/badge/CraftEngine-API_26.8.1%20%2F%20Extensions_26.9.2+-5865F2" alt="CraftEngine 26.8.1 编译 API，内置来源扩展要求 26.9.2+"></a>
  <img src="https://img.shields.io/badge/Java-21%20%2F%2025-orange" alt="Java 21 / 25">
  <img src="https://img.shields.io/badge/Paper-supported-blue" alt="Paper supported">
  <a href="https://che11111.github.io/CraftEngineRecipe-wiki/docs/guide-zh/requirements/#folia-适配"><img src="https://img.shields.io/badge/Folia-experimental-blueviolet" alt="Folia 实验性支持，验证范围见 Wiki"></a>
</p>

<p align="center">
  <a href="README.md">English</a> · <a href="https://che11111.github.io/CraftEngineRecipe-wiki/docs/intro-zh/">使用 Wiki</a> · <a href="#快速上手">快速上手</a> · <a href="https://che11111.github.io/CraftEngineRecipe-wiki/docs/guide-zh/troubleshooting/">常见问题</a>
</p>

## 使用演示

<table>
  <tr>
    <td align="center"><strong>菜单展示</strong><br><small>按物品种类分类布局与各种合成器分类布局</small></td>
    <td align="center"><strong>功能展示</strong><br><small>查询手持物品配方、准星目标配方与使用搜索框</small></td>
  </tr>
  <tr>
    <td align="center">
      <img src="img/Menu_Display.gif" alt="菜单展示：按物品种类分类布局与各种合成器分类布局" width="100%">
    </td>
    <td align="center">
      <img src="img/Feature_Display.gif" alt="功能展示：查询手持物品配方、准星目标配方与使用搜索框" width="100%">
    </td>
  </tr>
</table>

## 主菜单界面风格

CER 内置五种主菜单贴图风格，玩家可在服务端已启用的样式之间切换。

<table>
  <thead>
    <tr>
      <td width="20%" align="center">
        <img src="img/menu-styles/menu-style-cute.png" alt="CER 可爱风格界面" width="100%">
        <br><small>CER 可爱风格界面</small>
      </td>
      <td width="20%" align="center">
        <img src="img/menu-styles/menu-style-rough.png" alt="CER 粗糙风格界面" width="100%">
        <br><small>CER 粗糙风格界面</small>
      </td>
      <td width="20%" align="center">
        <img src="img/menu-styles/menu-style-vanilla.png" alt="CER 原版风格界面" width="100%">
        <br><small>CER 原版风格界面</small>
      </td>
      <td width="20%" align="center">
        <img src="img/menu-styles/menu-style-map.png" alt="CER 地图风格界面" width="100%">
        <br><small>CER 地图风格界面</small>
      </td>
      <td width="20%" align="center">
        <img src="img/menu-styles/menu-style-wood.png" alt="CER 木板风格界面" width="100%">
        <br><small>CER 木板风格界面</small>
      </td>
    </tr>
  </thead>
</table>

其中，`CER 可爱风格界面`为默认页面

## 特殊适配贴图

这里仅展示部分贴图，更多 CER 贴图请前往[界面图库](https://che11111.github.io/CraftEngineRecipe-wiki/docs/gallery-zh/)查看。

<table>
  <thead>
    <tr>
      <th width="20%" align="center">Farmer'sDelight · 煎锅</th>
      <th width="20%" align="center">CustomCrops · 种植盆</th>
      <th width="20%" align="center">KaleidoscopeCookery · 沙威玛烤架</th>
      <th width="20%" align="center">KaleidoscopeCookery · 石磨</th>
      <th width="20%" align="center">KaleidoscopeCookery · 茶壶</th>
    </tr>
  </thead>
  <tbody>
    <tr>
      <td width="20%" align="center">
        <img src="img/integration-textures/farmers-delight-skillet.png" alt="Farmer's Delight 煎锅配方界面" width="100%">
      </td>
      <td width="20%" align="center">
        <img src="img/integration-textures/custom-crops-planter.png" alt="CustomCrops 种植盆配方界面" width="100%">
      </td>
      <td width="20%" align="center">
        <img src="img/integration-textures/kaleidoscope-shawarma-grill.png" alt="森罗物语沙威玛烤架配方界面" width="100%">
      </td>
      <td width="20%" align="center">
        <img src="img/integration-textures/kaleidoscope-millstone.png" alt="森罗物语石磨配方界面" width="100%">
      </td>
      <td width="20%" align="center">
        <img src="img/integration-textures/kaleidoscope-teapot.png" alt="森罗物语茶壶配方界面" width="100%">
      </td>
    </tr>
  </tbody>
</table>

## 功能与特色

- **开箱即用**：自动安装 CraftEngine 资源与多套菜单界面，支持按物品种类、按合成器两种浏览方式。
- **双向配方查询**：左键查看物品如何合成，右键查看物品参与哪些配方；支持主手、准星、搜索和背包可制作查询。
- **快速搜索**：支持物品 ID、名称、翻译键、中文拼音及首字母搜索。
- **游戏内管理**：通过 `/cer manage` 编辑分类、可见权限、隐藏规则、排序、合成器顺序和自定义按钮，并支持正式配置与未应用配置互相移动。
- **统一配方索引**：原版、CraftEngine 和各适配插件的配方可导出到 `recipes/` 后统一浏览，文件修改自动重载；其他插件也可通过 CER Recipe API 接入。
- **可选配方扩展**：农夫乐事、森罗物语、CustomCrops 和私有来源适配器以独立 JAR 放在 `plugins/CraftEngineRecipe/extensions/`，无需重新构建主插件；通过 `/cer integrations` 管理启用状态。
- **主配置管理**：在 `/cer manage` 中打开 `config.yml`，可直接修改数据来源、搜索上限、快捷键与重载行为。
- **自动与玩家级语言**：插件提供英语、简体中文、繁体中文、日语、韩语、德语、法语、西班牙语和俄语。服务端语言会自动识别，玩家也可在“个性化”中选择这 9 种语言。
- **个性化与续接**：玩家可选择菜单风格、默认页面和关闭恢复行为；个性化与浏览会话统一保存在 `player-preferences.db`。

## 特殊适配插件或内容

| Logo | 插件或内容 | CER 适配内容 |
| :---: | --- | --- |
| <a href="https://github.com/Xiao-MoMi/craft-engine"><img src="img/compatible-plugin-logos/craftengine.png" alt="CraftEngine" width="64"></a> | [CraftEngine](https://github.com/Xiao-MoMi/craft-engine) | CER 的核心物品与配方来源，读取 CE 可用配方类型并提供物品、结果与用途索引；必需前置。 |
| <a href="https://github.com/IOVEYOUMC0/Farmersdelight-Plugin"><img src="img/compatible-plugin-logos/farmersdelight.png" alt="Farmer's Delight" width="72"></a> | [Farmer's Delight](https://github.com/IOVEYOUMC0/Farmersdelight-Plugin) | 导入烹饪锅、砧板、煎锅与特殊配方，并支持自动导出为 CER 配方文件；煎锅页面使用 `cer_fd_gui` 专用贴图。 |
| <a href="https://github.com/Nicoppara/KaleidoscopeCookery_Plugin"><img src="img/compatible-plugin-logos/kaleidoscope-cookery.png" alt="Kaleidoscope Cookery" width="64"></a> | [Kaleidoscope Cookery](https://github.com/Nicoppara/KaleidoscopeCookery_Plugin) | 导入炒锅、砧板、磨盘、沙威玛烤架、高汤锅、蒸笼和茶壶等设备配方；各设备页面使用对应的 `cer_kc_gui` 专用贴图。 |
| <a href="https://github.com/Xiao-MoMi/Custom-Crops"><img src="img/compatible-plugin-logos/customcrops.png" alt="CustomCrops" width="64"></a> | [CustomCrops](https://github.com/Xiao-MoMi/Custom-Crops) | 生成种植盆作物页面，包含种子、产物、季节、用水、肥料、洒水器、温室和稻草人等信息；种植盆页面使用 `cer_ccrops_gui` 专用贴图。 |
| <a href="https://github.com/che11111/WoodworkingSaw"><img src="img/compatible-plugin-logos/woodworkingsaw.png" alt="WoodworkingSaw" width="64"></a> | [WoodworkingSaw](https://github.com/che11111/WoodworkingSaw) | 通过 CER Recipe API 直接将 `transformations.yml` 转化规则发布到配方页面；CER 为 API 配方准备了统一的 `cer_api_gui` UI 贴图。 |


> CER 为煎锅、Kaleidoscope Cookery 设备、CustomCrops 种植盆页和 API 配方分别绘制了多套专用配方 UI 贴图。

## 运行要求

Paper **1.21.7 / 1.21.8 / 1.21.9 / 1.21.10 / 1.21.11** 使用 Java **21**；Paper / Folia **26.1.2 / 26.2 / 26.3** 使用 Java **25**。还需安装兼容服务端版本的 **CraftEngine**。

核心使用 CraftEngine **26.8.1** 编译 API；随包发布的 `craftengine` 与 `craftengine-cultivation` 来源扩展要求 CraftEngine **26.9.2 或更高版本**。如果只使用核心和手动配置的配方，可以继续使用兼容的 26.8.1 API；需要内置 CE 来源导出时请升级到 26.9.2+。

## 常用命令

### 玩家指令

| 命令 | 用途 |
| --- | --- |
| `/cer` | 打开正式菜单；快捷键 `Shift+F` |
| `/cer craft` | 直接打开“按合成器分类”页面 |
| `/cer items` | 直接打开“按物品种类分类”页面 |
| `/cer search` | 搜索物品 |
| `/cer hand` | 查看主手原版或 CE 物品的合成配方，遵循已配置的分类限制 |
| `/cer target` | 查看准星所指原版或 CE 方块的合成配方，遵循已配置的分类限制 |
| `/cer open <UI包名>` | 使用指定且已启用的 UI 样式打开菜单 |

### 管理员指令

| 命令 | 用途 |
| --- | --- |
| `/cer manage` | 打开配置管理中心：在游戏内编辑分类、隐藏规则、显示顺序与合成器配置 |
| `/cer integrations` | 打开CER管理页中的适配插件列表，并选择是否启用 |
| `/cer unapplied` | 按 `categories-unapplied.yml` 的分类条目打开配方浏览页面 |
| `/cer reload` | 手动重载配置与索引；配方文件修改会自动加载 |
| `/cer menu` | 查看默认、已启用和全部可用样式 |
| `/cer menu enable <包名\|all>` | 启用一个样式或全部 `cer_menu_*` |
| `/cer menu disable <包名>` | 停用非默认样式 |
| `/cer menu default <包名>` | 设置 `/cer` 与 Shift+F 使用的默认样式 |
| `/cer export <source-id\|all>` | 手动重新获取指定扩展来源并替换 `recipes/` 下的数据，例如 `minecraft_cultivation` 或 `all` |
| `/cer debug recipes` | 将服务器当前注册的全部配方保存为本地 JSON 调试文件 |
| `/cer debug performance` | 查看插件操作耗时、菜单会话/点击计数及服务器 TPS/MSPT |
| `/cer debug performance reset` | 重置插件计时窗口，再复现问题进行观察 |

调试指令也可在控制台执行，管理权限为 `craftenginerecipe.admin`，OP 默认拥有。

## 配置文档

[分类管理](https://che11111.github.io/CraftEngineRecipe-wiki/docs/guide-zh/categories/) · [指令与操作](https://che11111.github.io/CraftEngineRecipe-wiki/docs/guide-zh/commands/) · [配方浏览与搜索](https://che11111.github.io/CraftEngineRecipe-wiki/docs/guide-zh/search/) · [菜单自定义](https://che11111.github.io/CraftEngineRecipe-wiki/docs/guide-zh/menus/) · [插件适配](https://che11111.github.io/CraftEngineRecipe-wiki/docs/guide-zh/integrations/) · [扩展开发](docs/extensions.md) · [Recipe API](https://che11111.github.io/CraftEngineRecipe-wiki/docs/guide-zh/api/) · [常见问题](https://che11111.github.io/CraftEngineRecipe-wiki/docs/guide-zh/troubleshooting/) · [更新插件](https://che11111.github.io/CraftEngineRecipe-wiki/docs/guide-zh/updating/) · [更新日志](https://che11111.github.io/CraftEngineRecipe-wiki/docs/guide-zh/changelog/)


## 构建

运行：

```bash
./build.sh
```

脚本会构建全部四个 Minecraft 目标及独立扩展，并输出：

```text
dist/CraftEngineRecipe-<version>-mc<target>.jar
dist/extensions/cer-ext-*.jar
```

单独构建时，Maven profile 可选 `mc-1.21.7-1.21.11`、`mc-26.1`、`mc-26.2` 或 `mc-26.3`。

## 扩展安装

- **基础来源扩展**：`cer-ext-craftengine.jar`、`cer-ext-craftengine-cultivation.jar`、`cer-ext-minecraft.jar`、`cer-ext-minecraft-cultivation.jar`。建议全部安装，以获得 CE、原版和耕地来源的完整导出与索引。
- **第三方来源扩展**：`cer-ext-farmersdelight.jar`、`cer-ext-kaleidoscope.jar`、`cer-ext-customcrops.jar`。仅在服务器安装对应前置插件时启用。
- **私有来源模板**：`cer-ext-private-alchemy.jar` 只是扩展开发模板，在替换为真实 API 和采集逻辑前不会产生有效配方。

## 快速上手

1. 安装兼容版本的 CraftEngine 和 CraftEngineRecipe。核心 JAR 放入 `plugins/`，所需扩展以稳定文件名放入 `plugins/CraftEngineRecipe/extensions/`，然后完整停止并启动服务器。不要使用 `/reload` 或插件管理器的热重载。

2. 管理员执行 `/cer integrations`，开启 CER 对其他插件或资源包的特殊适配（例如农夫乐事、森罗物语等，可见本文适配列表）。

3. 管理员使用 `/cer manage` 在游戏内调整分类、排序和隐藏规则。（CER采用白名单模式，只有管理员在此页面调整后，对应的配方才可显示）

4. 玩家使用 `/cer` 或 `Shift+F` 打开物品分类；也可以使用 `/cer items`、`/cer craft` 和 `/cer search` 直接进入对应页面。

## 配置目录

```text
plugins/CraftEngineRecipe/
├── extensions/                        # 基础与可选配方来源扩展（独立升级）
│   ├── cer-ext-craftengine.jar
│   ├── cer-ext-craftengine-cultivation.jar
│   ├── cer-ext-minecraft.jar
│   ├── cer-ext-minecraft-cultivation.jar
│   ├── cer-ext-farmersdelight.jar
│   ├── cer-ext-kaleidoscope.jar
│   ├── cer-ext-customcrops.jar
│   └── cer-ext-private-alchemy.jar
├── extensions-data/                   # 扩展数据、来源目录所有权与版本标记
├── config.yml                         # 主配置：菜单、搜索、快捷键、更新及浏览默认值
├── resources/cer_menu_*/lang/         # 各菜单包独立的 9 语言覆盖文案
├── integrations.yml                   # CER 特殊适配开关、导出策略和分类预设
├── player-preferences.db              # SQLite：玩家个性化与浏览会话
├── navigation/                        # 正式菜单配置
│   ├── categories.yml                 # 已应用的分类、权限、隐藏规则和按钮
│   ├── categories-unapplied.yml       # 检测到但尚未应用的分类和按钮
│   └── workstations.yml               # 合成器顺序与工作站页自定义按钮
├── example/                           # 示例模板，不会被正式加载
│   ├── navigation/
│   │   └── categories-example.yml     # categories.yml 语法示例
│   └── recipes/
│       └── recipes-example.yml        # 自定义配方文件格式示例
├── lang/                              # 语言文件
│   └── integrations/
│       ├── craftengine/{<locale>}.yml           # CraftEngine 合成器名称
│       ├── farmersdelight/{<locale>}.yml        # 农夫乐事标题、条件和配方说明
│       ├── kaleidoscope/{<locale>}.yml          # 森罗物语设备名称、加工和品质说明
│       ├── customcrops/{<locale>}.yml           # 星露谷种植盆、季节、环境和肥料文本
│       ├── cultivation/{<locale>}.yml           # 耕地、种子、基质和种植条件说明
│       ├── minecraft/{<locale>}.yml             # 原版合成器显示名称
│       └── private_alchemy/{<locale>}.yml       # 私有来源模板文案
├── recipes/                           # CER 实际加载的自定义与导出配方
└── resources/                         # 核心与扩展资源本地副本，用于合并到 CE 资源包
```

每个受管资源目录包含 `.cer-resource-state.yml`，记录来源 JAR 版本和文件哈希。普通重启保留本地编辑；升级时只覆盖上一版仍与插件内容一致的文件，并清理确认未被修改的旧文件。最终 `CraftEngine/resources/CraftEngineRecipe/subpacks/` 会同时包含核心菜单包和扩展声明的专用贴图包。

扩展通过 `language-source` 声明的语言文件会释放到 `lang/integrations/<language-source>/<locale>.yml`；多个扩展共用同一来源目录时共用这份本地文案。运行时始终从磁盘副本读取。升级只补齐缺失键，不覆盖玩家或服主已经修改的文字；扩展生成的标题、备注、条件和参数文本会在玩家打开配方时按玩家语言解析。

每个 `cer_menu_*` 菜单包通过 `menu/` 下的完整布局文件声明它负责的页面。文件名任意，代码只读取文件中的 `pages` 声明，不使用继承或合并：

```text
cer_menu_cute/
├── pack.yml                           # CraftEngine 资源包元数据
├── menu/
│   ├── browse.yml                     # 物品分类与按合成器浏览页面布局
│   └── utility.yml                    # 管理、未应用与背包可制作页面布局
├── configuration/gui.yml              # 标题图片的字体高度、基线和贴图引用
└── resourcepack/
    └── assets/cer_cute/textures/font/gui/cer_main.png  # 菜单标题贴图
```

支持的语言 ID 为 `en_US`、`zh_CN`、`zh_TW`、`ja_JP`、`ko_KR`、`de_DE`、`fr_FR`、`es_ES`、`ru_RU`。插件界面、Dialog、状态提示、菜单文案、原版物品名称及资源包名称跟随玩家选择的语言。CraftEngine 物品名称优先按物品配置中的翻译键，从对应语言的 CraftEngine 翻译数据解析。内置原版配方文件保持语言中立：工作站标题在运行时根据稳定 ID 本地化，插件同时打包 9 种官方客户端语言数据。

物品名称严格按“玩家当前语言 → 英文 → 由物品 ID 生成的可读名称”回退，不会把 `item.*` 或 `block.*` 翻译键直接显示给玩家。原版物品和原版耕地扩展各自内置 9 种语言；原版翻译按当前服务端精确版本从 `cer-ext-minecraft.jar` 读取，不使用其他 Minecraft 版本的数据。



## 使用统计

[查看 bStats 使用统计](https://bstats.org/plugin/bukkit/CraftEngineRecipe/34772)。

[![bStats](https://bstats.org/signatures/bukkit/CraftEngineRecipe.svg)](https://bstats.org/plugin/bukkit/CraftEngineRecipe/34772)

## 许可证

本项目的源代码采用 **GNU General Public License v3.0（GPL-3.0）** 开源。

本项目中的贴图文件采用单独授权：**仅 CraftEngineRecipe（CER）插件使用**。未经明确许可，其他插件不得复制、修改、分发或复用本项目的贴图文件。

## 赞助与支持

如果本项目对你有帮助，可以通过 Ko-fi 或 爱发电 赞助开发。赞助后可提供插件安装、配置和故障排查问答服务。

<p align="center">
  <a href='https://ko-fi.com/J5C529ZWWL' target='_blank'><img height='36' style='border:0px;height:36px;' src='https://storage.ko-fi.com/cdn/kofi6.png?v=6' border='0' alt='Buy Me a Coffee at ko-fi.com' /></a>
  <a href='https://afdian.com/a/sss000' target='_blank'><img height='36' style='border:0px;height:36px;' src='https://img.shields.io/badge/Afdian-Support-946CE6?style=for-the-badge&logo=afdian&logoColor=white' border='0' alt='在爱发电赞助' /></a>
</p>

## 鸣谢

感谢 HuiDu_OwO 无偿为本项目绘制了 cer_menu_cute 布局的GUI贴图。

感谢 SnowCutieOwO 为本项目绘制了 cer_menu_map 和  cer_menu_wood 布局的GUI贴图。

感谢 nsoer 提供的 CEGUI-1.0.0.jar 以及 cer_menu_rough 布局的部分贴图文件的授权。

感谢 ChatGPT Astra 的帮助，尤其感谢 DeepSeek 在10月15日的崩溃使我收到了来自他的160元赠金。
