# Squire 职业与成长系统设计文档 v1.0

## 1. 文档目的

本文档定义 Squire 模组第一版职业与成长系统，作为后续 Claude Code 实现与重构的直接设计依据。

本版本不尝试把 Squire 扩展成完整的自主 Agent，而是在现有可靠能力之上增加：

- 多职业基础框架
- Guard（守卫）职业
- Engineer（工程师）职业
- 1–10 级成长系统
- 职业 XP
- 晋升材料
- 能力解锁
- 装备、背包、食物、药水与职业成长的联动
- 工程师蓝图能力的逐级扩展
- 防刷与能力边界

核心设计原则：

> **等级给予能力，玩家给予资源。**

> **Guard 的成长重点是“越来越会战斗”，而不是“越来越像 Boss”。**

> **Engineer 的成长重点是“越来越会使用蓝图系统”，而不是“等级越高越依赖 LLM Agent”。**

---

# 2. 当前已有能力前提

本设计必须建立在当前 Squire 已经实现的能力之上，不应破坏现有功能。

当前通用 Squire 已具备：

## 2.1 通用实体能力

- 跟随玩家
- 自动攻击敌对生物
- 基础寻路
- 与玩家通过自然语言交流
- 将部分自然语言任务转换为游戏内命令执行

## 2.2 装备系统

Squire 可以：

- 穿戴护甲
- 使用武器
- 拥有独立装备栏
- 由玩家直接编辑装备
- 使用装备上的原版附魔效果

必须保留原版装备强度，不应额外通过职业等级给予夸张百分比攻击或防御倍率。

## 2.3 背包系统

Squire 拥有自己的物品空间，并且：

- 玩家可以直接编辑其背包
- 玩家与 Squire 可以互相丢物品
- Squire 可以从自己的物品空间读取和使用物品

## 2.4 生存与治疗

当前 Squire 已能够：

- 自己吃食物回血
- 自己喝药水回血

职业系统需要在此基础上增加更合理的“使用时机”，而不是重复实现一套全新的治疗系统。

## 2.5 当前工程能力

当前 Squire 的建筑能力不是自由生成，而是：

1. 使用预设建筑模板
2. 通过指令生成结构
3. 在正式建造前使用粒子显示蓝图预览
4. 玩家可以在 UI 中：
   - 前移
   - 后移
   - 左移
   - 右移
   - 调整尺寸
   - 调整模板中的材料类型
5. 玩家确认后正式生成

当前限制：

- 建筑结构本身基本固定
- LLM 不可靠地负责自由建筑设计
- 当前不应要求 LLM 完成复杂多步 Agent 任务
- 不应在 v1 中加入：
  - 自动定位最近村庄后规划道路
  - 大规模地形理解
  - 自主扫描世界并规划工程
  - 失败后多轮自主反思和重规划
  - 自由设计任意建筑

---

# 3. v1 职业架构

第一版只实现两个正式职业：

```text
Squire
├── Guard
└── Engineer
```

保留未来增加职业的扩展空间，但不要在 v1 中实现 Miner、Farmer、Hunter 等额外职业。

每个 Squire 需要至少持久化：

```text
profession
professionLevel
professionXp
promotionReady
```

推荐：

```java
enum SquireProfession {
    GUARD,
    ENGINEER
}
```

等级范围：

```text
1 <= professionLevel <= 10
```

---

# 4. 通用升级规则

## 4.1 升级条件

每次升级必须同时满足：

1. 当前等级 XP 条达到上限
2. 玩家提供对应晋升材料
3. 玩家主动在 UI 中点击“晋升”

禁止自动升级。

示例：

```text
Guard Lv.4
550 / 550 XP

READY FOR PROMOTION

需要：
Diamond ×2

[晋升]
```

---

## 4.2 等级 XP 需求

采用“每级独立经验条”，升级后 XP 重新计算，不使用巨大的总累计 XP。

| 当前等级 | 升级到 | 所需 XP |
|---:|---:|---:|
| 1 | 2 | 100 |
| 2 | 3 | 200 |
| 3 | 4 | 350 |
| 4 | 5 | 550 |
| 5 | 6 | 800 |
| 6 | 7 | 1100 |
| 7 | 8 | 1500 |
| 8 | 9 | 2000 |
| 9 | 10 | 2800 |

从 Lv.1 培养到 Lv.10 总计约需要：

```text
9400 Profession XP
```

---

## 4.3 晋升材料

普通晋升材料两个职业可以共享。

| 晋升 | 所需材料 |
|---|---|
| Lv.1 → Lv.2 | 铁锭 ×8 |
| Lv.2 → Lv.3 | 金锭 ×8 |
| Lv.3 → Lv.4 | 青金石 ×16 |
| Lv.4 → Lv.5 | 钻石 ×2 |
| Lv.5 → Lv.6 | 绿宝石 ×12 |
| Lv.6 → Lv.7 | 钻石 ×4 |
| Lv.7 → Lv.8 | 下界合金碎片 ×4 |
| Lv.8 → Lv.9 | 钻石 ×8 |
| Lv.9 → Lv.10 | 职业专属材料 |

职业专属大师晋升材料：

```text
Guard:
Nether Star ×1

Engineer:
Beacon ×1
```

后续可配置化，不要硬编码在行为逻辑中。

---

# 5. XP 溢出规则

当当前等级 XP 已满，但玩家暂时没有晋升材料时：

- 允许继续积累少量溢出 XP
- 晋升后将溢出 XP 转入下一级
- 最大保留量 = 下一级 XP 需求的 25%

例：

```text
Lv.4 → Lv.5 需要 550 XP

当前：
520 / 550

一次任务获得：
150 XP

则：
550 / 550
Overflow = 120
```

晋升 Lv.5 后：

```text
Lv.5:
120 / 800
```

如果 Overflow 超过：

```text
800 × 25% = 200
```

则最多保留 200。

这样避免：

- 没晋升材料时所有经验全部浪费
- 玩家一次存够几级经验连续晋升

---

# 6. 死亡与 XP

职业 XP：

- 死亡不扣
- 倒地不扣
- 装备损失不影响职业等级

原因：

Squire 是长期培养对象，职业进度不应因为一次战斗被大量回滚。

---

# 7. Guard 职业设计

## 7.1 Guard 的核心定位

Guard 解决：

> 世界中的实体威胁。

Guard 的成长重点：

```text
基础战斗
→ 目标选择
→ 远近战切换
→ 盾牌
→ 背包补给管理
→ 主人保护
→ 高威胁判断
→ 完整 Guardian Protocol
```

Guard 不依赖 LLM 多轮 Agent 推理完成战斗。

---

# 8. Guard 数值平衡原则

由于 Squire 已经可以：

- 穿满配下界合金套
- 使用附魔武器
- 使用弓
- 使用盾
- 吃食物
- 喝治疗药水
- 携带大量补给

因此职业成长不能再叠加夸张 HP 和攻击倍率。

## 8.1 Guard HP

建议：

| Level | HP |
|---:|---:|
| 1 | 20 |
| 2 | 20 |
| 3 | 22 |
| 4 | 22 |
| 5 | 24 |
| 6 | 24 |
| 7 | 26 |
| 8 | 26 |
| 9 | 28 |
| 10 | 30 |

Lv.10 最终为 30 HP，即 15 颗心。

满配下界合金 + Protection + 食物 + 药水条件下已经足够强。

---

## 8.2 Guard 攻击力

优先规则：

> 武器决定实际战斗力。

v1 建议完全不提供百分比伤害倍率。

可选的小型职业补偿：

```text
Lv.1–4: +0
Lv.5–7: +0.5 damage
Lv.8–9: +1.0 damage
Lv.10:  +1.5 damage
```

如果当前装备系统和属性处理较复杂，v1 可以直接取消职业额外伤害。

---

# 9. Guard 1–10 级能力

## Lv.1 — Recruit / 新兵

已有：

- 跟随主人
- 主动攻击敌对生物
- 使用近战武器
- 穿戴玩家提供的装备
- 使用背包
- 吃食物回血
- 喝治疗药水回血
- 玩家受攻击时进行基础反击

基础战斗逻辑：

```text
发现敌人
→ 接近
→ 攻击
```

能力边界：

- 不会使用弓
- 不会主动武器切换
- 不会使用盾牌战术
- 威胁判断非常基础
- 不会高级保护主人

---

## Lv.2 — Armed Squire / 武装侍从

解锁：

### Equipment Awareness

能够识别：

- 剑
- 斧
- 护甲
- 食物
- 治疗药水
- 背包中的备用近战武器

新增：

### Basic Weapon Preference

多个近战武器存在时，优先选择：

1. 当前有效伤害更高
2. 可正常使用
3. 未接近损坏阈值

不要求实现复杂附魔评分。

---

## Lv.3 — Protector / 护卫

解锁：

### Threat Evaluation

不再单纯攻击最近目标。

推荐威胁分数考虑：

```text
正在攻击主人
距离主人
距离 Guard
敌对类型基础威胁值
当前是否已锁定主人
```

示意：

```text
ThreatScore =
ownerTargetBonus
+ ownerDistanceWeight
+ hostileTier
+ currentAggroBonus
```

解锁：

### Chase Limit

防止 Guard 为追一个低价值目标远离主人。

---

## Lv.4 — Marksman / 弓箭手

解锁：

### Bow Proficiency

前提：

- Guard 身上或背包中存在弓
- 背包中存在合法箭矢

行为：

```text
远距离目标
→ 尝试弓

飞行目标
→ 优先弓

近距离目标
→ 近战
```

重要：

- 不生成无限箭
- 正常消耗箭
- 弓正常消耗耐久

---

## Lv.5 — Veteran / 老兵

重大晋升节点。

需要：

```text
Diamond ×2
```

解锁：

### Automatic Weapon Switching

根据敌人和距离切换：

- 近战武器
- 弓

示例：

```text
Zombie nearby → sword
Creeper at distance → bow
Ghast → bow
Phantom → bow
Weapon nearly broken → try backup
```

解锁：

### Supply Awareness

能够检查：

- 食物数量
- 治疗药水
- 箭
- 备用武器

此等级只要求“意识到资源存在并合理使用”，不要加入复杂经济规划。

---

## Lv.6 — Defender / 防卫者

解锁：

### Shield Proficiency

如果拥有盾：

```text
远程攻击即将命中
→ 举盾

防御窗口结束
→ 恢复攻击

近战攻击窗口
→ 放盾攻击
```

升级治疗策略：

```text
HP < 65% 且当前安全
→ 优先食物

HP < 35% 且存在合适治疗药水
→ 使用治疗药水

HP < 20%
→ 尝试脱战 / 靠近主人
```

阈值需要配置化。

禁止：

- 少量掉血立即喝药
- 战斗中无冷却连续喝药

---

## Lv.7 — Guardian / 守护者

核心升级：

### Intercept

当敌人正在威胁主人时，尽量形成：

```text
Enemy → Guard → Player
```

即 Guard 尽量进入敌人与玩家之间。

解锁：

### Owner Emergency

当主人处于危险状态：

- 缩短追击距离
- 停止攻击远处无关敌人
- 更贴近玩家
- 提高正在攻击玩家目标的优先级

建议危险条件：

```text
Player HP < configurable threshold
OR
multiple hostile mobs targeting player
```

---

## Lv.8 — Elite Guard / 精英守卫

重大晋升节点。

需要：

```text
Netherite Scrap ×4
```

解锁：

### Combat Stance

新增 UI 设置：

```text
DEFENSIVE
BALANCED
AGGRESSIVE
```

#### Defensive

- 极短追击距离
- 主人保护优先
- 更容易回撤
- 更少主动拉怪

#### Balanced

- 默认模式

#### Aggressive

- 更长追击距离
- 更主动攻击
- 更低回撤倾向

以后性格系统可以修改这些参数，但：

> Personality 不得覆盖玩家明确选择的战斗姿态。

解锁：

### Advanced Inventory Use

更主动地使用：

- 备用武器
- 弓
- 盾
- 食物
- 治疗药水
- 箭

---

## Lv.9 — Knight / 骑士

解锁：

### High-Threat Awareness

增加特殊敌人危险识别。

至少支持：

- Warden
- Wither
- Ender Dragon
- Raid high-threat mobs
- 后续可扩展 modded boss tag

核心原则：

> 高等级不是“更无脑”，而是“更会判断打不过”。

例如：

```text
Warden detected
→ 不主动攻击
→ 回到主人附近
→ 保持距离
```

Boss 战中：

- 避免无意义贴脸
- 合理使用远程武器
- 主人濒危时优先保护主人

---

## Lv.10 — Royal Guard / 皇家守卫

大师晋升：

```text
Nether Star ×1
```

最终解锁：

# Guardian Protocol

统一战斗状态机。

正常状态：

```text
Observe
→ Select Threat
→ Choose Weapon
→ Fight
→ Manage Supplies
→ Maintain Owner Distance
```

主人危险状态：

```text
OWNER EMERGENCY

停止低价值目标
→ 返回主人
→ 重新计算最高威胁
→ Intercept
→ 自动切换剑 / 弓 / 盾
→ 必要时治疗自己
→ 保持主人保护状态
```

最终属性：

```text
HP = 30
```

不提供夸张攻击倍率。

---

# 10. Guard XP 规则

## 10.1 获得 XP 的有效战斗条件

Guard 必须真正参与战斗。

满足至少一个条件：

```text
Guard 完成最后一击
OR
Guard 对目标造成至少 20% Max HP 的有效伤害
```

避免：

- 玩家杀怪，Guard 站附近白拿 XP
- 刷怪塔纯挂机蹭经验

---

## 10.2 怪物基础经验

### Tier 1 — 普通敌人

```text
2 XP
```

例如：

- Zombie
- Spider
- Cave Spider
- Slime
- Drowned

### Tier 2 — 中等威胁

```text
4 XP
```

例如：

- Skeleton
- Stray
- Husk
- Witch
- Creeper
- Pillager

### Tier 3 — 强敌

```text
8 XP
```

例如：

- Piglin Brute
- Ghast
- Blaze
- Wither Skeleton
- Vindicator
- Evoker

### Tier 4 — 精英威胁

```text
20–40 XP
```

推荐：

```text
Raid Captain: 20
Ravager: 20
Elder Guardian: 25
Modded Elite: configurable 20–40
```

### Boss

推荐：

```text
Warden: 120 XP
Wither: 180 XP
Ender Dragon: 250 XP
```

---

## 10.3 Boss 重复击杀衰减

同一个 Guard 对同类 Boss：

```text
第 1 次：100%
第 2 次：50%
第 3 次及以后：25%
```

---

## 10.4 同类怪物重复击杀衰减

按短时间窗口统计。

建议默认窗口：

```text
5 minutes
```

同类型敌人：

| 5 分钟内数量 | XP |
|---|---:|
| 1–10 | 100% |
| 11–25 | 50% |
| 26–50 | 20% |
| 51+ | 5% |

该参数应配置化。

目的：

- 正常探索不受明显影响
- 刷怪塔不能快速挂机满级

---

## 10.5 保护主人奖励

若目标满足：

- 正在攻击主人
- 正在锁定主人
- 距离主人很近并构成有效威胁

则：

```text
XP × 1.25
```

最终 XP：

```text
FinalGuardXP =
BaseXP
× ProtectionBonus
× RepeatPenalty
× BossRepeatPenalty
```

最后统一取整。

---

# 11. Engineer 职业设计

## 11.1 Engineer 核心定位

Engineer 解决：

> 世界中的模板建筑和蓝图工程问题。

第一版 Engineer 的本质：

> **Natural Language → Blueprint Parameters → Deterministic Construction**

而不是：

> **Natural Language → Multi-step LLM Agent → Replan → World Search**

LLM 只负责理解玩家想调用什么模板、哪些参数。

真正建造由确定性代码完成。

---

# 12. Engineer v1 技术边界

Engineer v1 可以：

- 选择建筑模板
- 修改尺寸
- 修改位置
- 修改材料
- 蓝图粒子预览
- 旋转
- 镜像
- 结构 Variant
- 多层
- 附属模块
- 模块组合
- Compound Blueprint
- 保存自定义蓝图配置

Engineer v1 明确不能：

- 自动寻找最近村庄并修路
- 自动分析复杂世界地形
- 自动规划跨越河流/山脉道路
- 多轮调用 LLM 自主执行命令
- 让 LLM 自由设计任意建筑
- 失败后让 LLM 自主无限反思重规划

这些以后可以作为 Agentic / Prestige 能力单独设计。

---

# 13. Engineer 1–10 级能力

## Lv.1 — Apprentice / 学徒

等价于当前已有基础蓝图能力的职业化版本。

允许：

- Basic House
- 粒子蓝图预览
- UI 前后左右移动
- 调整基础尺寸
- 调整模板已有材料
- 玩家确认后生成

建议限制：

```text
Max Footprint: 9×9
Max Height: 6
Template Count: 1–2
```

---

## Lv.2 — Builder / 建筑工

解锁：

### Template Library I

新增简单模板，例如：

- Small House
- Shed
- Small Storage

扩大尺寸：

```text
Max Footprint: 13×13
```

增加常见材料族：

- Wood
- Stone
- Glass
- Slab
- Stair compatible palette

---

## Lv.3 — Draftsman / 制图师

解锁：

### Blueprint Rotation

```text
0°
90°
180°
270°
```

增强 UI：

```text
Move ±1
Move ±5
```

可选：

### Blueprint Anchor

支持：

- Center
- Entrance
- Corner

如果实现成本偏高，Anchor 可延期，但 Rotation 应优先。

---

## Lv.4 — Material Specialist / 材料专家

解锁：

### Material Regions

从简单材料替换升级为结构分区材料：

```text
Foundation
Wall
Floor
Roof
Window
Trim
```

示例：

```text
Foundation = Stone Bricks
Wall = Oak Planks
Roof = Spruce
Floor = Polished Andesite
```

建议：

```text
Max Footprint: 17×17
```

---

## Lv.5 — Engineer / 工程师

重大晋升节点。

需要：

```text
Diamond ×2
```

解锁：

### Structural Variants

结构不再完全固定，但仍然是预定义 Variant。

例如：

```text
Roof:
- Flat
- Gable
- Hip

Foundation:
- None
- Stone
- Raised

Window:
- Small
- Wide
- Tall

Entrance:
- Center
- Side
```

由模板代码组合，不让 LLM 自由生成方块布局。

建议：

```text
Max Footprint: 21×21
```

---

## Lv.6 — Architect / 建筑师

注意：

Architect 仍然是确定性蓝图能力，不是自由 LLM 建筑师。

解锁：

### Multi-floor

```text
1 Floor
2 Floors
3 Floors
```

模板代码负责：

- 楼板
- 楼梯
- 层高
- 门窗重复
- 层间结构

新增模板可包括：

- Large House
- Warehouse
- Watchtower

---

## Lv.7 — Structural Engineer / 结构工程师

解锁：

### Mirror

```text
Mirror X
Mirror Z
```

解锁：

### Optional Modules

例如：

```text
Porch
Chimney
Storage Wing
Tower
Balcony
```

模块必须具有：

```text
predefined connector
```

禁止让 LLM 自行决定任意拼接位置。

---

## Lv.8 — Master Builder / 建造大师

重大晋升节点。

需要：

```text
Netherite Scrap ×4
```

解锁：

### Modular Blueprint

建筑由多个预定义模块组成：

```text
Core
+
Wing
+
Tower
+
Entrance
+
Accessory
```

UI 可以提供：

```text
[+] Add Wing
[+] Add Tower
[+] Add Workshop
```

模块连接只能使用合法 Connector。

建议：

```text
Max Footprint: 32×32
```

LLM 仍然只做参数映射。

例：

玩家：

```text
“帮我建一个带塔楼和仓库的大房子”
```

解析：

```json
{
  "template": "house",
  "size": "large",
  "tower": true,
  "storage_wing": true
}
```

之后全部由代码执行。

---

## Lv.9 — Project Engineer / 项目工程师

解锁：

### Compound Blueprint

一个蓝图可以包含多个建筑：

```text
Outpost
├── Main House
├── Warehouse
├── Watchtower
├── Fence
└── Gate
```

整个布局仍然由规则或预设布局产生。

玩家可以调整：

- 整体位置
- 方向
- 建筑间距
- 大小
- 材料
- 模块选择

解锁：

### Save Blueprint Configuration

玩家可以保存自己调好的参数组合。

---

## Lv.10 — Grand Engineer / 大工程师

大师晋升：

```text
Beacon ×1
```

最终解锁：

# Advanced Blueprint Library

包含：

- 高级尺寸权限
- 多层
- Material Regions
- Structural Variants
- Rotation
- Mirror
- Optional Modules
- Modular Blueprint
- Compound Blueprint
- 保存蓝图配置
- 加载已有配置

建议：

```text
Max Footprint: 48×48
```

核心能力：

### Save Custom Blueprint Preset

例如保存：

```text
Name: My Survival House

Size: 17×13
Floors: 2
Foundation: Stone Brick
Wall: Oak
Roof: Spruce
Roof Type: Gable
Storage Wing: true
Chimney: true
```

以后玩家说：

```text
“再建一个我之前那个生存屋。”
```

LLM 只需要：

```text
LOAD_BLUEPRINT_PRESET("My Survival House")
```

然后打开正常蓝图预览。

---

# 14. Engineer XP 规则

## 14.1 不按方块给予 XP

禁止：

```text
Place 1 block = 1 XP
```

否则：

- 大型建筑经验失控
- 玩家可以通过反复铺地刷级
- 与职业“完成项目”的设计目标不符

Engineer XP 必须在：

```text
Blueprint Construction SUCCESS
```

之后一次性结算。

---

## 14.2 基础工程 XP

按模板复杂度定义。

### Tier 1 — 简单项目

```text
20 XP
```

例如：

- Shed
- Small Storage
- Small House

### Tier 2 — 普通项目

```text
40 XP
```

例如：

- Standard House
- Medium Warehouse
- Small Watchtower

### Tier 3 — 复杂项目

```text
70 XP
```

例如：

- Two-floor House
- Large Warehouse
- Tall Tower

### Tier 4 — Module Project

```text
110 XP
```

例如：

- House + Porch
- House + Storage Wing
- House + Tower

### Tier 5 — Compound Project

```text
180 XP
```

例如：

```text
Outpost
├── House
├── Warehouse
├── Tower
├── Fence
└── Gate
```

---

## 14.3 Size Multiplier

不能按实际方块数量线性计算。

建议：

| Size | Multiplier |
|---|---:|
| Small | ×1.00 |
| Medium | ×1.25 |
| Large | ×1.50 |
| Very Large | ×1.75 |

绝对上限：

```text
×2.0
```

---

## 14.4 Structural Complexity Modifier

### Structural Variant

使用高级结构 Variant：

```text
×1.10
```

### Multi-floor

```text
2 Floors: ×1.15
3 Floors: ×1.25
```

### Modules

每个附属模块：

```text
+10%
```

最大模块奖励：

```text
+40%
```

Compound Blueprint 建议直接使用更高 Base XP，不再无限叠模块倍率。

---

## 14.5 Engineer XP 公式

推荐：

```text
FinalEngineerXP =
BaseProjectXP
× SizeMultiplier
× StructuralModifier
× FloorModifier
× ModuleModifier
× RepeatPenalty
```

最后统一取整。

---

# 15. Engineer 防刷规则

重复项目需要衰减。

记录：

```text
Template ID
Size Class
Floor Count
Structural Variant
Module Configuration
```

不要将纯材料颜色纳入重复 Hash。

原因：

以下不应该被视为“新工程”：

```text
Oak House
Spruce House
Birch House
```

如果结构完全一样，只换颜色，不应该重置完整经验。

建议重复衰减：

| 短时间内同类项目次数 | XP |
|---|---:|
| 第 1 次 | 100% |
| 第 2 次 | 75% |
| 第 3 次 | 50% |
| 第 4 次 | 25% |
| 第 5 次及以后 | 10% |

建议时间窗口配置化。

---

# 16. Engineer XP 结算时机

完整流程：

```text
Blueprint Preview
↓
Player Adjust
↓
Player Confirm
↓
Construction
↓
Construction Success
↓
Award Profession XP
```

以下情况：

```text
Preview Cancelled
Construction Cancelled
Command Failure
Interrupted Project
Invalid Blueprint
```

全部：

```text
0 XP
```

---

# 17. 背包与 Guard 的联动

Guard 背包定位：

> 战斗补给空间。

高级 Guard 可以越来越有效地利用：

- Food
- Healing Potion
- Arrow
- Bow
- Shield
- Backup Weapon

等级解锁的是：

> 是否懂得合理使用。

不是：

> 升级自动生成补给。

所有资源必须由玩家实际提供。

---

# 18. 背包与 Engineer 的联动

Engineer 背包第一版保持普通储物作用。

v1 不强制：

> 建房必须从 Engineer 背包扣除全部材料。

原因：

当前工程系统是 Command-based，如果立即加入真实材料消耗，需要额外处理：

- 数百/数千材料统计
- 多容器读取
- 材料不足
- 施工过程中扣除
- 命令失败时回滚
- 原子性
- 建筑被取消时返还
- 中途断线

这些复杂度不属于职业系统 v1 的必要范围。

后续可以单独设计：

```text
Survival Construction Mode
```

---

# 19. 食物与药水规则

现有自动进食与喝药能力保留。

Guard 等级只增强“决策时机”。

推荐默认：

```text
HP < 65% 且安全
→ 吃食物

HP < 35% 且存在 Healing Potion
→ 喝药

HP < 20%
→ 尝试脱离危险
```

必须增加：

- 使用冷却
- 不重复浪费药水
- 不在低价值掉血时频繁喝药

配置项建议：

```text
foodHealThreshold
potionHealThreshold
emergencyRetreatThreshold
consumableUseCooldown
```

---

# 20. 装备与耐久

Guard 使用：

- Armor
- Sword
- Axe
- Bow
- Shield

必须正常消耗耐久。

附魔：

- 使用原版正常效果
- Mending 使用 Vanilla XP Orb
- Profession XP 与原版 XP 分离

禁止：

```text
Profession XP 自动修理装备
```

---

# 21. Guard 满配强度目标

Lv.10 Guard + 满配下界合金 + 满附魔 + 弓 + 盾 + 食物 + 药水：

应该：

### 普通怪群

明显碾压。

### Raid

是强力战斗伙伴，但玩家仍应参与。

### Wither

能辅助玩家，不能稳定无脑单刷。

### Warden

应识别为极高风险并避免主动攻击。

### Ender Dragon

可以：

- 用弓攻击
- 保护主人
- 清理部分周边威胁

但不能独立完成整个 Boss 战。

---

# 22. 职业能力边界

## Guard 可以

- 跟随
- 战斗
- 保护
- 使用装备
- 使用食物和药水
- 使用背包补给
- 远近战切换
- 战斗姿态
- 高威胁判断

## Guard 不应该

- 使用建筑命令完成工程任务
- 自动执行复杂世界编辑
- 因等级获得 Boss 级 HP
- 因等级获得夸张伤害倍率
- 凭空获得箭、药水、装备

---

## Engineer 可以

- 选择模板
- 调整参数
- 蓝图预览
- 调整位置
- 调整尺寸
- 调整材料
- 旋转
- 镜像
- 结构 Variant
- 多层
- 模块
- Compound Blueprint
- 保存/读取蓝图 Preset

## Engineer 不应该

- 自主搜索最近村庄
- 自主规划跨地图道路
- 自主进行复杂地形分析
- 多轮 LLM Agent 规划
- LLM 自由设计任意建筑
- 依赖高 token 消耗才能完成普通建筑任务

---

# 23. LLM 使用原则

职业等级不应该解锁“更强 LLM”。

错误设计：

```text
Lv.1 → simple prompt
Lv.10 → long autonomous agent prompt
```

正确设计：

```text
Profession Level
↓
Available Tools / Blueprint Features

LLM
↓
Select Intent + Parameters
```

例如：

Lv.2 Engineer：

```text
BUILD_SMALL_HOUSE
BUILD_SHED
```

Lv.8 Engineer：

```text
BUILD_HOUSE
BUILD_WAREHOUSE
ROTATE_BLUEPRINT
MIRROR_BLUEPRINT
ADD_MODULE
SET_STRUCTURAL_VARIANT
```

LLM 只负责一次结构化解析。

推荐：

```text
Player Natural Language
↓
Intent Parse
↓
Structured Parameters
↓
Deterministic Java Logic
↓
Preview
↓
Player Confirm
↓
Execute
```

---

# 24. 推荐的数据结构

示例：

```java
public class SquireProfessionData {
    private SquireProfession profession;
    private int level;
    private int xp;
    private int overflowXp;
    private boolean promotionReady;
}
```

建议能力判断不要散落大量：

```java
if (level >= 6)
```

而是封装：

```java
professionAbilities.canUseBow(level)
professionAbilities.canUseShield(level)
professionAbilities.canUseStructuralVariants(level)
```

或者使用：

```text
Ability Unlock Registry
```

---

# 25. 推荐能力 ID

Guard：

```text
guard.basic_melee
guard.equipment_awareness
guard.threat_evaluation
guard.bow_proficiency
guard.weapon_switching
guard.supply_awareness
guard.shield_proficiency
guard.intercept
guard.combat_stance
guard.high_threat_awareness
guard.guardian_protocol
```

Engineer：

```text
engineer.basic_blueprint
engineer.template_library_1
engineer.blueprint_rotation
engineer.material_regions
engineer.structural_variants
engineer.multi_floor
engineer.blueprint_mirror
engineer.optional_modules
engineer.modular_blueprint
engineer.compound_blueprint
engineer.blueprint_preset_library
```

---

# 26. 配置化要求

以下内容尽量不要硬编码：

## 通用

```text
xpRequiredPerLevel
promotionItems
overflowXpRatio
```

## Guard

```text
hpPerLevel
mobThreatTier
mobBaseXp
bossXp
repeatKillWindow
repeatKillMultipliers
protectionXpBonus
foodHealThreshold
potionHealThreshold
retreatThreshold
chaseDistanceByStance
```

## Engineer

```text
projectBaseXp
sizeMultiplier
floorMultiplier
moduleMultiplier
repeatProjectPenalty
maxBlueprintSizeByLevel
availableTemplatesByLevel
```

这样方便后续平衡，不需要重写代码。

---

# 27. UI 建议

Squire 主 UI 增加：

```text
Name
Profession
Level
XP Bar
Promotion Status
```

示例：

```text
Roger
Guard Lv.6

XP
████████░░
870 / 1100

Next Unlock:
Guardian Lv.7
- Intercept
- Owner Emergency

Promotion Material:
Diamond ×4
```

Engineer：

```text
Alice
Engineer Lv.5

Unlocked:
✓ Rotation
✓ Material Regions
✓ Structural Variants

Next:
Lv.6 Multi-floor
```

当 XP 满：

```text
READY FOR PROMOTION
```

显示所需材料与按钮。

---

# 28. 推荐实现优先级

## Phase 1 — 职业基础架构

实现：

- profession
- level
- xp
- promotion
- NBT / save persistence
- UI 显示
- 升级材料扣除

---

## Phase 2 — Guard 职业化

先将当前战斗 Squire 迁移为 Guard Lv.1。

逐步实现：

1. Equipment Awareness
2. Threat Evaluation
3. Bow Proficiency
4. Weapon Switching
5. Shield
6. Supply Logic
7. Intercept
8. Combat Stance
9. High-Threat Awareness
10. Guardian Protocol

同时实现 Guard XP。

---

## Phase 3 — Engineer 职业化

先将当前建筑蓝图能力迁移为 Engineer Lv.1。

优先顺序：

1. Template Library
2. Rotation
3. Material Regions
4. Structural Variants
5. Multi-floor
6. Mirror
7. Optional Modules
8. Modular Blueprint
9. Compound Blueprint
10. Blueprint Preset Library

同时实现 Engineer XP。

---

## Phase 4 — 平衡与防刷

实现：

- Guard repeat kill penalty
- Boss repeat penalty
- Engineer repeated project penalty
- XP overflow
- 晋升材料
- 配置文件

---

# 29. 暂不纳入 v1 的功能

明确禁止本次职业系统重构顺手扩张范围。

以下放入未来版本：

- 多侍从协作
- Personality
- 长期 Memory
- 主副职业
- 职业专精
- Ability Slot
- Guard Knight / Ranger 分支
- Engineer Architect / Mechanist / Foreman 分支
- 自主道路规划
- 自动寻找村庄
- Create 复杂机器自动设计
- 红石自主设计
- LLM 多轮 Agent Loop
- 自动失败反思
- 自动世界探索
- Survival Material Consumption
- Downed / Revive System

这些功能应在基础职业系统稳定后独立规划。

---

# 30. 最终职业对照表

| Lv | Guard | Engineer |
|---:|---|---|
| 1 | 基础近战、现有治疗/装备 | 基础模板蓝图 |
| 2 | 装备意识 | 更多模板、扩大尺寸 |
| 3 | 威胁判断、追击限制 | 蓝图旋转 |
| 4 | 弓箭使用 | 材料分区 |
| 5 | 自动武器切换、补给意识 | Structural Variants |
| 6 | 盾牌、智能治疗阈值 | 多层建筑 |
| 7 | Intercept、主人紧急保护 | Mirror、Optional Modules |
| 8 | Combat Stance、高级背包使用 | Modular Blueprint |
| 9 | 高威胁 / Boss Awareness | Compound Blueprint、保存配置 |
| 10 | Guardian Protocol | Advanced Blueprint Library |

---

# 31. 最终设计原则

整个 v1 必须坚持以下原则：

## 31.1 Guard

> 装备决定战斗上限，等级决定 Guard 是否会正确使用这些装备。

Guard 的满级价值主要来自：

- 更好的目标选择
- 更聪明的武器切换
- 更合理的盾牌使用
- 更合理的治疗
- 更好的主人保护
- 更强的危险判断

而不是：

```text
+500% HP
+100% Damage
```

---

## 31.2 Engineer

> 模板和蓝图系统决定建筑可靠性，等级决定 Engineer 能控制多复杂的蓝图。

Engineer 的满级价值来自：

- 更多模板
- 更多参数
- 结构变化
- 多层
- 模块化
- Compound Project
- 保存和复用蓝图

而不是：

```text
让 LLM 自己想办法盖任何东西
```

---

## 31.3 LLM

> LLM 负责理解玩家意图，不负责承担可以由确定性算法完成的工作。

目标：

```text
Natural Language
↓
Structured Intent
↓
Deterministic Tool
↓
Preview / Action
```

避免：

```text
LLM
↓
Command
↓
Observation
↓
LLM
↓
Command
↓
Observation
↓
...
```

除非未来明确进入 Agentic 功能版本。

---

## 31.4 成长

> XP 证明侍从真的做过这个职业的事情。

> 晋升材料要求玩家为培养付出世界资源。

> 等级应该优先解锁“以前不会做的行为”，而不是单纯增加数值。

这三点是整个 Squire 职业系统的核心。
