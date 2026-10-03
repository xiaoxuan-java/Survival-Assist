# 生存助手 SurvivalAssist

> 纯客户端 · Minecraft 1.20.1 · Fabric / Forge
>
> Client-side · Minecraft 1.20.1 · Fabric / Forge

**选择语言 / Choose your language:**
[中文](#中文) · [English](#english)

---

<details open>
<summary><h2 id="中文">🇨🇳 中文</h2></summary>

<br>

一个纯客户端的《我的世界》生存辅助模组，把生存过程中最琐碎、最重复的操作收进快捷键里：一键连锁挖矿、自动挖矿、矿透、自动补种、自动钓鱼、生物血条、最近玩家距离、超亮调节、自动刷附魔书、一键整理背包，以及血量/工具耐久过低时自动退出服务器。

> **不改变服务端任何逻辑，不需要服务端安装。** 单人存档、原版服务器、以及允许客户端模组的服务器都可直接使用。

## 支持版本

| 加载器 | Minecraft | 源码目录 | 状态 |
|---|---|---|---|
| Fabric | 1.20.1 | [`fabric/`](fabric/) | ✅ 已支持 |
| Forge | 1.20.1 | [`forge/`](forge/) | ✅ 已支持 |

两个版本功能基本一致（Fabric 版额外包含自动挖矿与 HUD 世界坐标投影）。

## 功能特性

### 挖掘与采集

- **连锁挖掘**（`·` 键，即键盘左上角 ESC 下方那个键）
  按一下就把相连的同种方块整片挖掉，一次最多连锁的方块数可在设置里调整，方块充足时挖满设定数量。适合砍树、清矿脉、整理建筑废墟。

- **自动挖矿**（`G` 键，Fabric 版）
  自动在设定范围内扫描矿石并逐个挖掉，自动切换快捷栏里的镐子。扫描范围可配置。

- **矿透 X-Ray**（`X` 键）
  在设定范围内高亮矿石方块，非矿石方块不渲染。支持原版全部矿石（煤、铁、铜、金、红石、青金石、钻石、绿宝石、下界石英、下界金、远古残骸），并支持**自定义方块 ID**（逗号分隔，可填模组方块），扫描范围可调。

- **自动补种**
  挖掉成熟作物后自动把种子种回去，补种延迟（tick）可配置，0 为立即补种。

- **只采集成熟作物**
  开启后未成熟的作物无法被左键挖掉，避免手滑把没长好的田铲了。

### 信息显示

- **生物血量显示**
  在生物头顶显示血条，可同时显示具体数值；血条长度、显示距离（超出距离不显示）均可配置。

- **最近玩家距离**
  显示离你最近的玩家的距离，可同时显示玩家名称，方便判断周围是否有人。

### 自动化

- **自动钓鱼**
  自动抛竿、自动收竿，挂机钓鱼用。

- **鸡蛋连点投掷**
  按一次把背包里的鸡蛋连续投完，投掷间隔（tick）可配置，0 为最快。刷鸡、刷成就用。

- **自动刷附魔书**（`V` 键）
  自动循环刷取指定附魔书：可选择目标附魔（中文名显示）、目标等级（0 表示任意等级都算命中）、等级上限、刷取轮数上限（0 表示永不停止）。

- **自动疾跑**
  按住前进时自动进入疾跑状态。

### 便利与保护

- **一键整理背包**（`R` 键）
  一键把背包物品排序整理。

- **伽马值 / 亮度调节**
  突破原版亮度上限：0~100% 为原版亮度范围（100% = 原版最亮），100% 以上通过光照贴图额外提亮，**最高可到 1500% 的超亮**，适合地下探索。

- **自动退出保护**
  血量低于设定百分比、或主手镐子剩余耐久低于设定百分比时，**自动断开与服务器的连接**，防止死亡掉装备或镐子爆掉。

## 安装方法

1. 确认已安装对应版本的**模组加载器**：
   - Fabric：需 [Fabric Loader](https://fabricmc.net/use/) 0.14.24 或更高
   - Forge：需 Forge 1.20.1
2. 把 jar 放进 `.minecraft/mods/` 文件夹
3. Fabric 版还需安装以下**前置模组**（Forge 版无需额外依赖）：
   - [Fabric API](https://modrinth.com/mod/fabric-api)
   - [Mod Menu](https://modrinth.com/mod/modmenu) ≥ 7.2.0
   - [Cloth Config API](https://modrinth.com/mod/cloth-config) ≥ 11.0.0
4. 启动游戏，在**「模组」→「生存助手」**里配置各项功能，或直接使用下表默认快捷键

## 默认快捷键

| 按键 | 功能 |
|---|---|
| `·`（ESC 下方） | 连锁挖掘 |
| `V` | 自动刷附魔书 |
| `G` | 自动挖矿（Fabric 版） |
| `X` | 矿透 X-Ray |
| `R` | 一键整理背包 |

所有按键均可在游戏的「选项 → 控制」中修改。

## 从源码构建

两个加载器各自独立构建，进入对应目录执行：

```bash
# Fabric 版
cd fabric
./gradlew build          # 产物：build/libs/survivalassist-<version>.jar

# Forge 版
cd forge
./gradlew build          # 产物：build/libs/survivalassist-<version>.jar
```

Fabric 版要求 Gradle 8.8+（`fabric-loom` 与 Gradle 8.14 存在兼容问题）；Forge 版使用 Gradle 8.14 亦可。

## 注意事项

- 本模组为**纯客户端**模组，请勿在禁止客户端辅助模组的服务器上使用，否则可能违反服务器规则导致封禁。是否允许使用请以你所游玩服务器的规则为准。
- 矿透、自动挖矿、自动钓鱼等功能在多数公共服务器属于**违规行为**，建议仅在单人存档或私人服务器使用。
- 「自动退出保护」触发后角色会立刻下线，请留意自己的血量与工具耐久。

## 开源协议

GNU Lesser General Public License v3.0（LGPL-3.0）

你可以自由使用、修改、分发本模组，也可以把它集成进整合包。若你修改了本模组的源码并对外分发，则修改后的代码同样需要以 LGPL-3.0 开源。

**作者：** xiaoxuan_java

</details>

<details>
<summary><h2 id="english">🇬🇧 English</h2></summary>

<br>

A client-side survival helper mod for Minecraft. It folds the most repetitive, fiddly survival chores into hotkeys: one-key vein mining, auto mining, X-Ray, auto replant, auto fishing, mob health bars, nearest-player distance, extra-bright gamma, auto enchant-book farming, one-key inventory sorting, and auto-disconnect when your health or pickaxe durability gets too low.

> **It changes no server-side logic and needs no server installation.** Works in singleplayer, on vanilla servers, and on any server that allows client-side mods.

## Supported Versions

| Loader | Minecraft | Source | Status |
|---|---|---|---|
| Fabric | 1.20.1 | [`fabric/`](fabric/) | ✅ Supported |
| Forge | 1.20.1 | [`forge/`](forge/) | ✅ Supported |

Both versions are functionally equivalent (the Fabric build additionally includes auto mining and HUD world-space projection).

## Features

### Mining & Gathering

- **Vein Mining** (`·` key, the key just below ESC)
  Break an entire connected cluster of the same block with one press. The maximum number of blocks per chain is configurable; if enough blocks are available it will mine up to that limit. Great for chopping trees, clearing ore veins, and tearing down builds.

- **Auto Mining** (`G` key, Fabric build)
  Automatically scans for ores within a configurable range and mines them one by one, auto-switching pickaxes in the hotbar.

- **X-Ray** (`X` key)
  Highlights ore blocks within a configurable range and stops rendering non-ore blocks. Covers every vanilla ore (coal, iron, copper, gold, redstone, lapis, diamond, emerald, nether quartz, nether gold, ancient debris) and supports a **custom block-ID list** (comma-separated, modded blocks welcome).

- **Auto Replant**
  Automatically replants seeds after you harvest a mature crop. Replant delay (in ticks) is configurable; 0 means instant.

- **Mature-Only Harvest**
  When enabled, immature crops cannot be broken with left-click — no more accidentally shredding a field that hasn't grown yet.

### Information

- **Mob Health Bars**
  Shows a health bar above mobs, optionally with numeric values. Bar width and display range (mobs beyond it show no bar) are configurable.

- **Nearest Player Distance**
  Shows the distance to the closest player, optionally with their name, so you know whether anyone is around.

### Automation

- **Auto Fishing**
  Casts and reels automatically — for AFK fishing.

- **Egg Machine-Gun**
  One press throws every egg in your inventory in quick succession. Throw interval (in ticks) is configurable; 0 is fastest. Handy for chicken and advancement farming.

- **Auto Enchant Book** (`V` key)
  Repeatedly rerolls for a chosen enchanted book. You can pick the target enchantment, the target level (0 = any level counts), the level cap, and the round limit (0 = never stop).

- **Auto Sprint**
  Automatically sprints while you hold forward.

### Utility & Protection

- **Sort Inventory** (`R` key)
  Sorts your inventory with a single key press.

- **Gamma / Brightness Control**
  Goes beyond the vanilla brightness ceiling: 0–100% maps to vanilla gamma (100% = vanilla max), while anything above 100% applies an extra brightness pass over the lightmap — **up to 1500% for extreme visibility**, perfect for cave exploration.

- **Auto-Exit Protection**
  Automatically **disconnects from the server** when your health drops below a configured percentage, or when your main-hand pickaxe durability drops below a configured percentage — so you don't lose your gear or break your pickaxe.

## Installation

1. Install the matching **mod loader**:
   - Fabric: [Fabric Loader](https://fabricmc.net/use/) 0.14.24 or newer
   - Forge: Forge 1.20.1
2. Drop the jar into your `.minecraft/mods/` folder.
3. The Fabric build additionally requires these **dependencies** (the Forge build needs none):
   - [Fabric API](https://modrinth.com/mod/fabric-api)
   - [Mod Menu](https://modrinth.com/mod/modmenu) ≥ 7.2.0
   - [Cloth Config API](https://modrinth.com/mod/cloth-config) ≥ 11.0.0
4. Launch the game and configure everything under **Mods → Survival Assist**, or just use the default keybinds below.

## Default Keybinds

| Key | Action |
|---|---|
| `·` (below ESC) | Vein Mining |
| `V` | Auto Enchant Book |
| `G` | Auto Mining (Fabric) |
| `X` | X-Ray |
| `R` | Sort Inventory |

All keybinds can be changed in **Options → Controls**.

## Building from Source

Each loader builds independently — enter the matching directory:

```bash
# Fabric
cd fabric
./gradlew build          # Output: build/libs/survivalassist-<version>.jar

# Forge
cd forge
./gradlew build          # Output: build/libs/survivalassist-<version>.jar
```

The Fabric build requires Gradle 8.8+ (`fabric-loom` is incompatible with Gradle 8.14); the Forge build also works with Gradle 8.14.

## Notes

- This is a **client-side only** mod. Do not use it on servers that forbid client-side assistance mods, or you may break server rules and get banned. Whether it's allowed is up to the rules of the server you play on.
- X-Ray, auto mining and auto fishing count as **cheating on most public servers**. Please only use them in singleplayer or on private servers.
- When **Auto-Exit Protection** triggers, your character disconnects immediately — keep an eye on your health and tool durability.

## License

GNU Lesser General Public License v3.0 (LGPL-3.0)

You are free to use, modify and redistribute this mod, and to include it in modpacks. If you modify the mod's source code and distribute it, your modified code must also be released under LGPL-3.0.

**Author:** xiaoxuan_java

</details>

---

*SurvivalAssist · by xiaoxuan_java*
