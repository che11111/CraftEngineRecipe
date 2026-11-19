# CraftEngineRecipe 配方来源扩展

CER 核心负责配方索引、搜索、分类、GUI 和资源包；需要访问其他插件或私有数据模型时，可通过扩展 JAR 提供来源适配器。扩展与核心分开发布，来源插件 API 变化时不需要重新发布整个 CER。

## 安装结构

```text
plugins/CraftEngineRecipe/
├── extensions/
│   ├── cer-ext-craftengine_v<版本>.jar        # 首次运行时由主插件释放
│   ├── cer-ext-craftengine-cultivation_v<版本>.jar
│   ├── cer-ext-minecraft-cultivation_v<版本>.jar
│   ├── cer-ext-farmersdelight_v<版本>.jar
│   ├── cer-ext-trailtalesdelight_v<版本>.jar
│   ├── cer-ext-kaleidoscope_v<版本>.jar
│   ├── cer-ext-customcrops_v<版本>.jar
│   └── cer-ext-private-alchemy_v<版本>.jar
├── extensions-data/
│   ├── craftengine-cultivation/
│   ├── minecraft/
│   ├── minecraft_cultivation/
│   └── source-directories.yml         # provider output-directory ownership
└── recipes/
    ├── farmersdelight/
    ├── kaleidoscope/
    ├── customcrops/
    └── private_alchemy/
```

`cer-ext-craftengine_v<版本>.jar` 和 `cer-ext-craftengine-cultivation_v<版本>.jar` 打包在主插件 JAR 的
`META-INF/cer-embedded-extensions/` 下。插件仅在首次运行、即创建
`plugins/CraftEngineRecipe/extensions/` 时释放它们。目录已经存在时，即使为空或缺少
其中某个文件，也不会自动补齐或覆盖。`cer-ext-minecraft-cultivation_v<版本>.jar`
不内嵌，仍需手动放入扩展目录。

发布文件名包含版本号，格式为 `cer-ext-<来源>_v<版本>.jar`，扩展版本同时写入 JAR manifest 的 `Implementation-Version`。`/cer reload` 会重新扫描扩展目录；新增或删除 JAR 后会重建 Provider 及其依赖状态。升级时先移除旧版本文件，只保留当前版本，再执行重载或重启；同时存在两个提供相同 Provider ID 的版本会被加载器拒绝。替换扩展二进制后仍建议完整重启，因为 Java 不保证旧类立即卸载。

从旧版无前缀文件名升级时，请移除 `craftengine.jar`、`kaleidoscope.jar` 等旧文件，只保留新的 `cer-ext-*.jar`。加载器会扫描目录中的全部 JAR，新旧文件同时存在会触发重复来源 ID 错误。

每个扩展 JAR 根目录都应包含格式统一的 `extension.yml`，作用类似 Bukkit 的 `plugin.yml`。它声明扩展 ID、多语言名称与描述、扩展作者、支持语言、来源插件依赖和 `integrations.yml` 管理页按钮图标：

```yaml
schema: 1
id: farmersdelight
names:
  en_US: Farmer's Delight
  zh_CN: 农夫乐事
  zh_TW: 農夫樂事
  ja_JP: Farmer's Delight
  ko_KR: Farmer's Delight
  de_DE: Farmer's Delight
  fr_FR: Farmer's Delight
  es_ES: Farmer's Delight
  ru_RU: Farmer's Delight
descriptions:
  en_US: Imports Farmer's Delight cooking, cutting board, skillet and addon recipes.
  zh_CN: 导入农夫乐事的烹饪、砧板、煎锅和附属配方。
  zh_TW: 匯入農夫樂事的烹飪、砧板、煎鍋與附屬配方。
  ja_JP: Farmer's Delight の調理鍋、まな板、スキレット、アドオンレシピをインポートします。
  ko_KR: Farmer's Delight의 조리, 도마, 프라이팬 및 애드온 레시피를 가져옵니다.
  de_DE: Importiert Kochtopf-, Schneidebrett-, Pfannen- und Addon-Rezepte von Farmer's Delight.
  fr_FR: Importe les recettes de marmite, planche à découper, poêle et modules complémentaires de Farmer's Delight.
  es_ES: Importa recetas de olla, tabla de cortar, sartén y complementos de Farmer's Delight.
  ru_RU: Импортирует рецепты котла, разделочной доски, сковороды и дополнений Farmer's Delight.
authors:
  - che1s
supported-languages:
  - en_US
  - zh_CN
  - zh_TW
  - ja_JP
  - ko_KR
  - de_DE
  - fr_FR
  - es_ES
  - ru_RU
language-source: farmersdelight
resource-packs:
  - cer_fd_gui
dependencies:
  plugins:
    - name: FarmersDelight
      main-classes:
        - com.huidu.farmersdelight.FarmersDelightPlugin
      version: ">=1.0.3"
      primary: true
integration-button:
  icons:
    - farmersdelight:cooking_pot
    - minecraft:cauldron
```

内置扩展统一提供 `en_US`、`zh_CN`、`zh_TW`、`ja_JP`、`ko_KR`、`de_DE`、`fr_FR`、`es_ES`、`ru_RU`。`names` 和 `descriptions` 必须包含 `supported-languages` 中的每种语言。`language-source` 指定该 JAR 的 `lang/<locale>.yml` 属于哪个集成的文案；核心会把它们复制到服务器的 `lang/integrations/<source>/`，之后服主可直接编辑该本地文件。

`dependencies.plugins` 可校验插件名、主类、包名前缀、作者和版本；版本约束支持空格分隔的 `>=`、`>`、`<=`、`<`、`=` 组合，例如 `>=26.9.2 <27.0.0`。`primary` 标记的依赖是传给扩展 API 的来源插件。内置扩展只使用精确的 `main-classes`；`package-prefixes` 仍受核心支持，供第三方扩展在需要覆盖多个主类时作为较宽松的包路径校验。

`resource-packs` 列出 JAR 内 `resourcepack/<packId>/` 的资源包。核心会在启动时从中补齐本地资源副本，再合并进 CraftEngineRecipe 的统一资源包。每个扩展维护 `.cer-resource-state.yml` 版本与文件哈希状态：普通重启不会覆盖本地编辑；扩展版本变化时只更新上一版未被服主修改的文件。pack ID 在所有已加载扩展之间必须唯一。扩展 JAR 采用以下结构：

```text
cer-ext-farmersdelight_v<版本>.jar
├── extension.yml
├── workstation-presets.yml
├── category-presets.yml
├── lang/
│   ├── en_US.yml
│   ├── zh_CN.yml
│   ├── zh_TW.yml
│   ├── ja_JP.yml
│   ├── ko_KR.yml
│   ├── de_DE.yml
│   ├── fr_FR.yml
│   ├── es_ES.yml
│   └── ru_RU.yml
├── com/che1s/.../*.class
└── resourcepack/
    └── cer_fd_gui/
        ├── pack.yml
        ├── configuration/
        └── resourcepack/assets/
```

保留 `cer_fd_gui` 等现有 pack ID，是因为生成的配方和菜单背景已经引用这些命名空间。JAR 内的 `resourcepack/` 不会自动发送给客户端，必须由核心提取并合并；直接塞进扩展 JAR 并不会让客户端加载贴图。

物品 ID 使用标准 `namespace:path` 格式，优先推荐扩展自己资源包中的代表性物品，再列出原版备用物品。上面的农夫乐事示例会优先显示烹饪锅；扩展物品不存在或无法构建时，自动回退到原版炼药锅。依赖不满足的已加载扩展不会创建来源，并会显示在 `/cer manage` 的右键不支持列表中。

三个基础来源分别是：

```text
craftengine              -> 当前服务器生效的 CraftEngine 与原版配方
craftengine_cultivation  -> CraftEngine 命名空间耕地数据
minecraft_cultivation    -> 当前 MC 版本的原版耕地数据
```

三个基础扩展默认启用，不进入适配询问。带版本号的 Minecraft Cultivation 扩展内含 `1.21.7`、`1.21.8`、`1.21.9`、`1.21.10`、`1.21.11`、`26.1`、`26.2`、`26.3` 八套耕地数据，只释放与当前服务端完全匹配的一套；找不到精确版本时扩展禁用，不降级使用其他版本。这八套数据由 `scripts/fetch-vanilla-client.sh` 下载并校验对应官方客户端 JAR 后分别提取，不能从一个版本推断另一个版本。原版配方由 CraftEngine 运行时提供。

缺少扩展 JAR 或缺少它要求的来源插件时，核心和其他扩展继续正常工作。扩展加载失败、API 版本不兼容、重复来源 ID 或主类不匹配都会写入控制台日志。

只包含静态展示数据时不需要编写扩展，直接把 YAML 放入 `recipes/<namespace>/` 更简单。

## 扩展 API

扩展编译时依赖 CER 主 JAR，但不要把 CER API 类、Paper 或 CraftEngine 重新打包进扩展。

```text
dev.che1s.craftenginerecipe.api.extension.RecipeSourceProvider
dev.che1s.craftenginerecipe.api.extension.RecipeSource
dev.che1s.craftenginerecipe.api.extension.SourceContext
dev.che1s.craftenginerecipe.api.extension.SourceText
```

最小实现：

```java
public final class MyRecipeSourceProvider implements RecipeSourceProvider {
    @Override
    public String id() {
        return "my_private_source";
    }

    @Override
    public String requiredPluginName() {
        return "MyPrivatePlugin";
    }

    @Override
    public String requiredPluginMainClass() {
        return "com.example.privateplugin.PrivatePlugin";
    }

    @Override
    public String outputDirectory() {
        return "my_private_source";
    }

    @Override
    public RecipeSource create(SourceContext context) {
        return new MyRecipeSource(context);
    }
}
```

`outputDirectory()` 声明扩展独占的 `recipes/<目录>/`。它必须在插件启动前就能确定，默认等于 provider ID；声明为带版本目录时，源码仍应返回相同值。核心会持久化目录所有权，因此即使扩展 JAR 被移除或来源被禁用，也不会把旧的导出文件误当成普通手写配方继续加载。两个 provider 不能声明同一输出目录。

`RecipeSource` 只在 `ready()` 返回 `true` 后采集快照：

```java
public final class MyRecipeSource implements RecipeSource {
    @Override
    public String id() {
        return "my_private_source";
    }

    @Override
    public boolean ready() {
        return true;
    }

    @Override
    public long revision() {
        return 1L;
    }

    @Override
    public List<DisplayRecipe> capture() {
        return List.of(/* normalized CER recipe models */);
    }
}
```

核心通过 `SourceContext` 提供来源插件、当前 Minecraft 版本、自身 JAR、`extensions-data/<provider-id>/`、语言文本、物品名称、CraftEngine 资源包翻译、配置只读选项和受支持的配方文档加载器。扩展不应引用 `CraftEngineRecipePlugin` 等核心内部类，也不应修改 CER 内部索引、自行打开菜单或注册实际合成逻辑。

CraftEngine 物品的显示名优先使用 `ItemDefinition.translationKey()`，再从当前玩家的 CraftEngine 资源包和 `configuration/lang/<locale>.yml` 合并翻译中查找。只有翻译键缺失时才回退到 `ItemNameProcessor`、`CustomNameProcessor` 或物品 ID。

扩展自己声明工作台导航顺序和标题表现，核心不包含 FarmersDelight、Kaleidoscope、CustomCrops 或某一语言专用的空格、粗体规则。两个文件职责分离：

- `workstation-presets.yml`：`navigation/workstations.yml` 的工作台顺序、动态工作台前缀归属和标题表现。
- `category-presets.yml`：`navigation/categories.yml` 的完整分类预设。

`workstation-presets.yml` 示例：

```yaml
schema: 1
navigation:
  - id: "farmersdelight:cooking_pot"
  - id: "farmersdelight:cutting_board"
  - id: "farmersdelight:skillet"
  - id: "farmersdelight:special"
presentation:
  station-prefixes:
    - "farmersdelight:"
  styles:
    default:
      remove-spaces: false
      bold: false
    en_US:
      remove-spaces: true
      bold: false
    zh_CN:
      bold: true
    zh_TW:
      bold: true
```

`navigation` 中缺失的工作台会按扩展声明顺序补入 `navigation/workstations.yml`，不会移动服主已经调整的条目。核心会选择匹配工作站 ID 的最长 `station-prefixes`，再按查看者 locale 选择 `styles`。标题文字仍来自扩展 JAR 的 `lang/<locale>.yml`，因此扩展完整拥有名称、空格和粗体策略。缺少 `workstation-presets.yml` 时使用不压缩、不加粗且不接管动态工作台的安全默认值。

`category-presets.yml` 示例：

```yaml
schema: 1
presets:
  - id: farmersdelight
    authors:
      - HuiDu_OwO
    visibility: player
    hidden-categories:
      - farmersdelight:gui
      - farmersdelight:item_display
```

扩展预设应用到服务器文件后会写入稳定的所有权标记。实际发布 JAR 带版本后缀，但
`cer-managed-by` 会去掉 `_v<版本>`，避免升级后误删分类和工作站配置：

```yaml
# navigation/categories.yml
- id: farmersdelight
  visibility: player
  cer-managed-by: cer-ext-farmersdelight.jar

# navigation/workstations.yml
- id: farmersdelight:cooking_pot
  cer-managed-by: cer-ext-farmersdelight.jar
```

当对应 JAR 被移除、无法加载，或来源在 `integrations.yml` 中被禁用时，核心会从两个导航文件中删除该 JAR 写入的条目。没有 `cer-managed-by` 的本地条目、按钮和服主手工配置永不自动删除。动态发现的工作台会按最长 `station-prefixes` 认领；显式 `navigation` 条目优先。旧版没有标记、但 ID 能对应到当前已启用扩展预设的条目会补上所有权标记，不改写服主已经编辑的其他字段。

扩展定义是默认值，本地 `integrations.yml` 中同 ID 的 `category-presets` 项可以覆盖它。只包含 `id` 和 `mode` 的本地项视为运行时状态，不会覆盖 JAR 内的完整定义。

## JAR Manifest

```text
Implementation-Version: 1.0.0
CraftEngineRecipe-Provider-Class: com.example.MyRecipeSourceProvider
CraftEngineRecipe-Api-Version: 1
CraftEngineRecipe-Minecraft: 1.21.7,1.21.8,1.21.9,1.21.10,1.21.11,26.1,26.2,26.3
```

`CraftEngineRecipe-Minecraft` 可以是 `*` 或逗号分隔的兼容版本列表，例如 `1.21.7,1.21.8,1.21.9,1.21.10,1.21.11,26.1,26.2,26.3`。

## 启用与权限

可选扩展发现后显示在 `/cer integrations`，默认模式为 `ask`。四个基础扩展默认 `enabled`，不显示询问。启用后首次导出到来源声明的输出目录；之后的启动、查询和文件自动重载只读取导出文件；执行 `/cer export <provider-id>` 可重新采集来源。

私有集成仍使用同一套管理员权限 `craftenginerecipe.admin`。扩展代码是在服务器进程内运行的受信任代码；不要从不可信来源下载 JAR。

## 构建内建扩展

```bash
./build.sh
```

完整构建会生成：

```text
dist/CraftEngineRecipe-1.1.0-mc1.21.7-1.21.11.jar
dist/CraftEngineRecipe-1.1.0-mc26.1.jar
dist/CraftEngineRecipe-1.1.0-mc26.2.jar
dist/CraftEngineRecipe-1.1.0-mc26.3.jar
dist/extensions/cer-ext-craftengine_v<版本>.jar
dist/extensions/cer-ext-craftengine-cultivation_v<版本>.jar
dist/extensions/cer-ext-minecraft-cultivation_v<版本>.jar
dist/extensions/cer-ext-farmersdelight_v<版本>.jar
dist/extensions/cer-ext-trailtalesdelight_v<版本>.jar
dist/extensions/cer-ext-kaleidoscope_v<版本>.jar
dist/extensions/cer-ext-customcrops_v<版本>.jar
dist/extensions/cer-ext-private-alchemy_v<版本>.jar
```

单独开发扩展时，先安装核心 API，再构建扩展 reactor：

```bash
mvn install
mvn -f extensions-build/pom.xml clean package
```

每个内建扩展都在自己的 Maven 模块中拥有完整源码和资源，不再从核心的 `target/classes` 或 `src/main/resources` 复制文件：

```text
extensions-build/farmersdelight/
├── pom.xml
└── src/
    ├── main/
    │   ├── java/dev/che1s/craftenginerecipe/integration/farmersdelight/
    │   │   ├── FarmersDelightRecipeSource.java
    │   │   ├── FarmersDelightRecipeSourceProvider.java
    │   │   └── FDReflection.java
    │   └── resources/
    │       ├── extension.yml
    │       ├── workstation-presets.yml
    │       ├── category-presets.yml
    │       ├── lang/
    │       │   └── <nine locale files>
    │       └── resourcepack/
    │           └── cer_fd_gui/
    └── test/java/...
```

原版耕地数据直接位于 `extensions-build/minecraft-cultivation/`。提取脚本只写入这个模块；原版配方和名称由 CraftEngine 运行时及生成资源包提供，不再发布独立的 Minecraft 配方扩展。

内置九语言文件可由 `scripts/generate-language-files.py` 重新生成。该脚本会保护 MiniMessage 标签、命名参数、颜色代码和首尾空格；提交前应运行 `LanguageBundleCoverageTest` 检查所有语言的键集合与占位符是否一致。

`cer-ext-private-alchemy_v<版本>.jar` 是私有来源模板，默认要求插件名 `PrivateAlchemy` 和主类 `com.example.privatealchemy.PrivateAlchemyPlugin`，在真实集成完成前不会产生配方。
