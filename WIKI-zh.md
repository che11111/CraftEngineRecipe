# CraftEngineRecipe 使用指南

[返回 README](README-zh.md)

本手册包含安装、玩家操作、完整配置与升级说明。

## 目录

- [环境要求](#环境要求)
- [Folia 适配](#folia-适配)
- [首次安装与分类应用](#首次安装与分类应用)
- [命令权限与操作](#命令权限与操作)
- [资源包的应用](#资源包的应用)
- [玩家操作与搜索](#玩家操作与搜索)
- [分类、权限与隐藏规则](#分类权限与隐藏规则)
  - [顺序、显示名与提示](#顺序显示名与提示)
  - [隐藏某个分类中的物品](#隐藏某个分类中的物品)
  - [同时隐藏分类入口和分类中的物品](#同时隐藏分类入口和分类中的物品)
- [自定义按钮](#自定义按钮)
- [菜单音效](#菜单音效)
- [常用调整与故障排查](#常用调整与故障排查)
- [源码构建与升级](#源码构建与升级)

常用配置：[命令权限与操作](#命令权限与操作) · [隐藏物品](#隐藏某个分类中的物品) · [隐藏分类入口](#同时隐藏分类入口和分类中的物品) · [自定义按钮](#自定义按钮)


## 环境要求

| 项目 | 要求 |
| --- | --- |
| Java | Java 25（当前构建目标） |
| 服务端 | Paper；当前编译依赖为 `26.2.build.132-stable` |
| 必装插件 | CraftEngine；当前编译依赖为 `26.8.1` |
| 已打包依赖 | InvUI 2.3.2、pinyin4j 2.5.1、bStats 3.0.2，无需单独安装 |
| Folia | 1.0.10 起实验性支持；目标 `26.1.2 build 8` + CraftEngine `26.9.2`，见下文 |



## Folia 适配

使用 Java 25、Folia `26.1.2 build 8` 和 CraftEngine `26.9.2`。Paper 与 Folia 使用同一个插件 JAR。

菜单、搜索回调、声音和配方轮播使用玩家实体调度器，会跟随玩家跨区域移动。全局配置与索引发布在全局调度器执行，纯文本索引计算放到异步线程。重载会先暂停访问，异步等待各玩家完成窗口清理，再加载配置和发布新的分类/搜索快照；不会在区域线程等待其他区域完成任务。旧搜索回调和延迟按钮动作在重载后失效。

`console:` 动作在全局调度器执行，完成后才回到玩家线程继续动作链；`command:` / `player:` 在玩家线程执行。被调用的其他插件命令也需要支持 Folia。**Folia 下不执行 `op:`，并停止该动作链**；请改用明确的 `console:` 命令或授予玩家对应权限，避免跨线程临时修改 OP 状态。Paper 下保留原 `op:` 行为。


## 首次安装与分类应用

1. 安装并配置 CraftEngine，确保它已经加载你的物品包、物品分类与配方。
2. 将 `CraftEngineRecipe-1.0.12.jar` 放入服务器的 `plugins/`，启动服务器。内置资源首次运行时会自动安装到 CraftEngine 的资源目录，详见[资源包说明](#资源包的应用)。
3. 等待控制台出现 `CraftEngineRecipe 数据就绪`。索引会分批、异步构建，物品多时需要稍候。
4. 管理员执行 `/cer unapplied`，预览尚未应用的分类。
5. 打开 `plugins/CraftEngineRecipe/categories-unapplied.yml`，找到需要开放的命名空间，例如 `farmersdelight`。
6. 把对应条目复制进 `plugins/CraftEngineRecipe/categories.yml` 的 `entries:` 下，按需设置 `visibility: player`。
7. 执行 `/cer reload`，等待提示完成，再用 `/cer` 打开正式菜单。

最小可用的 `categories.yml`：

```yaml
entries:
  - id: default
    visibility: player
    permission: ''
```

请把 `default` 换成待应用文件中实际存在的命名空间。这里的 `id` 是命名空间，不是完整的物品 ID，也不是 `farmersdelight:food` 这样的分类 ID。

首次生成的正式分类列表可能为空，这是正常现象。新发现的命名空间默认 `admin`，位于待应用列表；它们不会自动加入正式 `/cer` 菜单。完成复制并重载后，对应条目会从待应用文件中移除。

本插件读取 CraftEngine 中已有的分类和配方。修改 `categories.yml` 只会调整展示，不能创建 CraftEngine 物品、配方或分类。

## 命令权限与操作

| 命令 | 权限 | 用途 |
| --- | --- | --- |
| `/cer unapplied` | `craftenginerecipe.admin` | 打开待应用菜单 |
| `/cer`、`/cg`、`/browser` | 无 | 打开正式菜单 |
| `/cer search` | 无 | 打开搜索对话框 |
| `/cer reload` | `craftenginerecipe.admin` | 重载本插件配置、语言、分类数据、搜索索引和 bStats 开关 |
| 潜行时按交换副手键（默认 Shift+F）（本项可在设置中关闭） |-| 打开正式菜单；键位以客户端设置为准 |
| 点击配方窗口外的空白处 | - |关闭配方页面，返回主菜单 |
| Esc | - |关闭窗口|

OP 默认拥有管理员权限。管理员可以查看受限制的命名空间。控制台可以执行 `/cer reload`，其他命令需要玩家执行。

修改 CraftEngine 物品或配方后，先通过 CraftEngine 自身流程重载；本插件监听到重载事件后会自动重建数据。`/cer reload` 不负责重载 CraftEngine 本身。重建期间旧菜单会关闭，命令先提示正在重载，完成后再通知结果。





## 资源包的应用

插件仅首次启动时，会将资源包`cer_resource`自动复制在`plugins/CraftEngine/resources/`目录下。

`plugins/CraftEngineRecipe/cer_resource/`仅作为副本备份。


你需要执行`/ce reload all`指令重载CraftEngine插件，以应用资源包。



## 玩家操作与搜索

没有对应配方或用途时，点击不会打开配方页。多条配方会自动轮播，默认每 30 tick 切换一次（服务端正常运行时约 1.5 秒）。目前配方页使用原生工作台、熔炉、锻造台等窗口，不显示手动翻配方或切换模式按钮。

支持工作台有序/无序合成、熔炼、高炉、烟熏、营火、切石、锻造和酿造。营火使用熔炉式窗口展示；酿造页左下展示输入容器、右下展示产物。动态配方以材料和固定产物预览为主；锻造纹饰结果显示“结果取决于实际投入物品”。材料有多种候选时，目前仅展示第一个候选。界面用于查阅，不提供一键合成。



### 搜索

执行 `/cer search` 或点击搜索按钮，在弹出的输入框输入关键字并确认。

支持完整物品 ID、ID 片段、显示名称、拼音和拼音首字母。例如物品“茄子”可以尝试 `茄子`、`qie`、`zi`、`qiez` 或 `qz`。多音字采用 pinyin4j 的首个读音，个别物品可能需要改用名称或 ID 搜索。

默认最多输入 32 个字符、返回 200 条结果。搜索范围是当前正式/待应用菜单中有权访问的分类及其可访问子分类；被隐藏的分类成员会被排除。如果同一物品在另一个可见分类中仍存在，它仍可被搜到。完全没有 CraftEngine 分类时，浏览器和搜索才回退到全部已加载物品。




## 分类、权限与隐藏规则

### 顺序、显示名与提示

`categories.yml` 的 `entries:` 是有序列表。命名空间条目与自定义按钮可以混排；同一命名空间内部的分类遵循 CraftEngine 分类顺序。

```yaml
entries:
  - id: farmersdelight
    visibility: player
    icon-name: '<white><name></white>'
    icon-lore:
      - '<gray>点击进入分类</gray>'
      - '<dark_gray>物品数: <count></dark_gray>'
    sub-icon-lore:
      - '<gray>点击进入子分类</gray>'
    item-lore:
      - '<gray>左键查看配方，右键查看用途</gray>'

  - id: vipitems
    visibility: permission
    permission: 'cer.vip'

  - id: internal
    visibility: admin
```

只保留实际存在的命名空间。`visibility` 可选：

- `player`：所有玩家可查看。
- `admin`：仅 OP 或有 `craftenginerecipe.admin` 的玩家可查看，也是命名空间默认值。
- `permission`：玩家还需要 `permission` 指定的权限；空权限不会向普通玩家放行。

分类权限控制浏览与搜索入口，不会改变实际物品使用、合成权限，也不会隐藏玩家已获准打开的配方中所需的材料。

命名空间的 `icon-name`、`item-lore`、`icon-lore`、`sub-icon-lore` 可覆盖语言文件中的全局默认值。这些字段支持 MiniMessage，如 `<gold>文字</gold>`；`<name>` 是分类名，`<count>` 是直接成员条目数，含子分类引用，并非递归统计的去重物品数。


### 隐藏某个分类中的物品

与该条目的 `visibility`、`permission` 同级。下面示例是：只隐藏 Farmer's Delight 的 “GUI 和展示模型” 分类中的全部物品，保留食物、方块、工具等其他分类的物品：

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

如果 `entries` 中已有 `- id: farmersdelight`，只在原条目下补上 `hidden-items`，不要新增重复命名空间条目或第二个 `entries`。修改实际的 `plugins/CraftEngineRecipe/categories.yml` 后执行 `/cer reload`；仓库的 `示例配置.yml` 使用下文隐藏分类入口的写法；插件不会自动读取这个示例文件。

`category` 必须是完整的 CraftEngine 分类 ID。`'*'` 必须加引号，表示隐藏该分类的全部直接物品，不支持 `farmersdelight:*` 这类前缀写法。如果只想隐藏个别物品，将 `'*'` 换为具体 ID：

```yaml
    hidden-items:
      - category: farmersdelight:gui
        items:
          - farmersdelight:recipe_book
          - farmersdelight:slot
```

浏览列表与搜索结果都遵循规则，管理员和普通玩家均受影响。分类图标与子分类入口仍保留；隐藏子分类中的物品需要为该子分类另加规则。同一物品如果还出现在另一个可见分类中，仍可能显示。这些设置不会删除实际物品、禁止合成，或从其他配方材料栏移除该物品。

CraftEngine 分类配置中的 `hidden: true` 控制顶层分类展示；被父分类 `list` 中的 `#分类ID` 引用时，仍可以作为子分类访问。因此它与这里隐藏物品的功能不同。对于被根分类引用的 GUI 与展示模型分类，使用上面的两条规则可以隐藏其直接物品。




### 同时隐藏分类入口和分类中的物品

如果希望 GUI、展示模型这两个分类按钮也消失，在对应命名空间条目下使用 `hidden-categories`：

```yaml
entries:
  - id: farmersdelight
    visibility: player
    permission: ''
    hidden-categories:
      - farmersdelight:gui
      - farmersdelight:item_display
```

`hidden-categories` 与该条目的 `visibility`、`permission` 同级，填写完整分类 ID，使用精确匹配，不支持 `'*'`。它会移除顶层分类栏及父分类内容区中的对应入口，跳过这些分类的搜索路径，也阻止直接打开它们。规则适用于管理员和普通玩家，不显示占位按钮。隐藏分类中的下级分类不会沿这条路径进入搜索；若某物品或下级分类还存在其他可见入口，该路径仍可访问。分类图和物品定义保留，不修改 CraftEngine 实际合成行为。

以 Farmer's Delight 为例，根分类及其他子分类仍保留，GUI 和 item_display 入口及其搜索路径被隐藏。

| 配置 | 分类入口 | 分类物品 | 适用情况 |
| --- | --- | --- | --- |
| `hidden-items` + `items: ['*']` | 保留 | 隐藏直接物品 | 保留分类入口，只过滤物品 |
| `hidden-categories` | 隐藏 | 跳过整个分类浏览与搜索路径 | 同时移除分类按钮及其内容 |



## 自定义按钮

把以下条目插入 `categories.yml` 的 `entries:` 中：

```yaml
  - display:
      material: minecraft:compass
      name: '&e返回主城'
      lore:
        - '&7左键传送到主城'
    visibility: player
    actions:
      left:
        - 'sound: UI_BUTTON_CLICK'
        - close
        - 'delay: 0.1'
        - 'command: spawn'
```

按钮的 `display.name`、`display.lore` 使用 `&` 颜色码。CraftEngine 图标可写成 `source:CraftEngine:命名空间:物品ID`。按钮也支持 `visibility` 与 `permission`，默认向所有玩家显示。

点击类型支持 `all`、`left`、`right`、`shift_left`、`shift_right`、`middle`；没有对应类型时回退到 `all`。只有声音、没有命令的按钮可用作装饰。`delay` 使用调度器，不会让主线程睡眠。

`console` 和 `op` 属于高权限动作，只应由可信管理员填写。更多示例见首次启动生成的 `categories-example.yml`。



## 菜单音效

`config.yml` 的 `sounds` 支持 `声音`、`声音-音量`、`声音-音量-音调`。例如：

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

`UI_BUTTON_CLICK-1-2` 也可写为 `minecraft:ui.button.click-1-2`。`1` 是音量，`2` 是音调，**不是播放次数**；小数如 `0.5`、`0.8` 也支持。不写参数时，所有 `sounds.*` 默认音量 0.7、音调 1；按钮动作默认音量 1、音调 1。`categories.yml` 中的自定义按钮仍通过自身 `actions` 单独设置音效，例如：

```yaml
actions:
  left:
    - 'sound: UI_BUTTON_CLICK-1-2'
```



## 常用调整与故障排查

| 情况 | 处理方法 |
| --- | --- |
| 首次 `/cer` 空空如也 | 管理员用 `/cer unapplied` 检查；将命名空间复制到正式分类配置后重载 |
| 玩家看不到某分类 | 检查是否已应用、命名空间可见性、权限，以及 CraftEngine 是否有对应顶层分类 |
| 搜索不到物品 | 检查它是否属于当前菜单可访问分类、是否被隐藏；尝试完整 ID；修改物品后先重载 CraftEngine |
| 提示数据未就绪 | 等待索引构建；执行 `/cer reload`；持续失败则检查控制台完整异常和 YAML 缩进 |
| 背景乱码或贴图丢失 | 检查资源包导入目录、CraftEngine 包加载日志、生成与分发流程，确认玩家已下载新包 |
| 点击物品没有反应 | 该方向可能没有 CraftEngine 可查询配方；左键查产出、右键查用途；不保证收录其他插件的私有配方 |
| 多配方切换太快 | 增大 `recipe.rotate-interval`；有效范围 5～200 tick，默认 30 |
| 想关闭快捷键 | 设置 `hotkey.enabled: false` 后重载 |
| 想不潜行也能打开 | 设置 `hotkey.require-sneak: false`，此时普通交换副手键也会被占用 |
| Java 版本错误 | 当前 JAR 需要 Java 25；确认启动服务器的 Java 路径 |

搜索可调范围：`search.max-results` 为 1～1000，`search.max-length` 为 1～64。物品很多时建议保留 200 条的默认上限。

菜单布局是 6 行、每行 9 格。默认 `C` 为分类、`X` 为内容，`#` 为保留空槽；按钮字符由相应配置的 `char` 决定。每个按钮使用不同的单字符，不要与分类、内容区或 `#` 冲突。空格和制表符会忽略，未知字符显示为空槽，短行补齐、长行截断；少于 6 行时补空行，多余行忽略。禁用按钮时，将其 `char` 设为 `''`，并将布局里的对应字符改为 `#`。

`category-bar.source: ce` 跟随 CraftEngine 顶层分类；只有设为 `custom` 时，`category-bar.list` 中的完整分类 ID 才生效，例如 `farmersdelight:categories`。其命名空间仍需应用到本插件的 `categories.yml`。`content.empty` 只使用 `material` 和语言文件中的名称、lore，不支持 `char` 或 `item-model`。


## 源码构建与升级

安装 JDK 25 与 Maven 3.9+，在项目目录运行：

```bash
mvn clean verify
```

最终插件为 `target/CraftEngineRecipe-x.x.x.jar`。