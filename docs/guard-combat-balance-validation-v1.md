# Guard Combat Balance Validation v1

状态：第一轮测量完成；未进行 HP、伤害、治疗阈值、冷却、撤退阈值或姿态参数调整。

最终机器可读结果位于 `build/reports/gametest/guard-combat-balance.json`，GameTest JUnit 报告位于 `build/reports/gametest/report.xml`。

## 1. 当前代码真实参数

以下结论来自运行代码，不采用设计文档中的预期值。

### 等级生命与职业攻击

| Guard 等级 | Max HP | 职业额外基础近战伤害 | 实体基础攻击伤害（空手） |
|---:|---:|---:|---:|
| 1 | 20 | 0 | 2.0 |
| 2 | 20 | 0 | 2.0 |
| 3 | 22 | 0 | 2.0 |
| 4 | 22 | 0 | 2.0 |
| 5 | 24 | +0.5 | 2.5 |
| 6 | 24 | +0.5 | 2.5 |
| 7 | 26 | +0.5 | 2.5 |
| 8 | 26 | +1.0 | 3.0 |
| 9 | 28 | +1.0 | 3.0 |
| 10 | 30 | +1.5 | 3.5 |

职业伤害是设置在实体基础攻击 attribute 上的固定加值，不是武器百分比乘区。剑及 Sharpness 仍走 vanilla `tryAttack` 计算。

### 移动、追踪与攻击

| 参数 | 当前真实值 |
|---|---|
| 基础 movement speed | 0.35 |
| Swift trait movement speed | 0.4025（0.35 × 1.15） |
| follow range attribute | 48 blocks |
| 静止主人跟随启动/停止 | 6 / 3 blocks |
| 移动主人跟随启动/停止 | 2.5 / 2.25 blocks |
| 跟随导航速度 | 1.15 |
| 战斗/追击导航速度 | 1.20 |
| 近战触达距离 | 3 blocks |
| 近战重新寻路间隔 | 8 ticks |
| 普通 Guard policy radius | 默认 16，可配置 4–32 |
| 测试普通场景 policy radius | 12 |
| 基础 chase slack | Lv.1–2 为 1.5；Lv.3+ 由策略/姿态决定 |
| 瞬移跟随距离 | 默认 12，可配置 6–64 |
| 近战 attack cooldown | 没有独立 Guard cooldown，也没有设置 `GENERIC_ATTACK_SPEED`；每 tick 尝试 `tryAttack`，同目标实际受 vanilla hurt-time/无敌帧约束 |

“没有独立 cooldown”值得后续单独验证目标切换时的攻击节奏，但第一轮没有改动。

### 弓、盾、治疗与紧急行为

| 参数 | 当前真实值 |
|---|---|
| Bow 解锁 | Lv.4 |
| Bow range | 20 blocks |
| 拉弓/射击周期/恢复 | 20 / 30 / 10 ticks |
| 箭速度/散布 | 3.0 / 1.0 |
| Shield 解锁 | Lv.6 |
| Shield hold | 20 ticks |
| 盾使用条件 | 远程威胁距离 >3，且当前没有可工作的弓 |
| Food threshold | HP ≤84% |
| Healing potion threshold | Lv.6+ 且 HP ≤35% |
| 通用 consumable cooldown | 40 ticks |
| Guard Lv.6+ consumable cooldown | 60 ticks |
| 消耗失败重试/上限 | 5 ticks / 最多 3 次 |
| Emergency retreat threshold | 默认 HP ≤20% |
| Owner Emergency | Lv.7+；主人 HP ≤40%，或至少 3 个敌人正在攻击主人 |
| Owner Emergency 追击边界 | 仍被 Guard policy radius 截断 |

食物不是直接瞬间回血。普通食物被转换成 Regeneration I，目标治疗量为 `max(1, hunger / 2)` HP，持续 `目标治疗量 × 50 ticks`。例如 steak 的 hunger=8，对应最多 4 HP、持续 200 ticks；战斗结束前只统计已经实际落地的治疗。食物候选优先于药水，系统选择“足够且代价最低”的候选，否则选择最强候选。

普通 Healing I 按 vanilla 恢复 4 HP，Strong Healing II 恢复 8 HP。第一轮 Endgame 使用 Strong Healing II，其余带药 Loadout 使用 Healing I。

额外路径确实存在：食物主动施加 Regeneration；金苹果等物品如果进入背包，还可按 vanilla 提供 Regeneration/Absorption。本轮固定 Loadout 没有金苹果，最终所有场景 remaining absorption 均为 0。没有发现额外职业吸收盾或隐藏瞬间回血。

### 三种 Combat Stance

| Stance | chase slack | retreat HP | pulls aggro | 行为方向 |
|---|---:|---:|---|---|
| Defensive | 0.75 | 30% | false | 贴近主人，只处理迫近或已威胁主人/Guard 的目标 |
| Balanced | 1.50 | 20% | true | 中间值 |
| Aggressive | 2.50 | 12% | true | 更长追击、更晚撤退 |

另有旧 trait 退避值：默认 25%、Cautious 40%、Reckless 15%；当前职业姿态路径使用上表配置。

### Vanilla 装备与附魔审计

- 护甲、armor toughness、Protection 通过 `super.damage` 的 vanilla 流程结算，没有 Squire 职业护甲乘区。
- Sturdy trait 是唯一额外减伤：进入 vanilla 护甲结算前把原始伤害乘 0.9。本轮清空 trait，因此数据中没有该加成。
- 剑伤、Sharpness 通过 vanilla `tryAttack` 生效；职业只增加上表实体基础攻击伤害，没有百分比武器倍率。
- Bow 真实创建 projectile；Power、Punch、Flame 通过 vanilla projectile enchantment 路径生效一次。审计发现并移除了旧的 Power 重复加伤路径，因此本报告最终数据不含双算。
- Protection IV、Sharpness V、Power V、Unbreaking III 按 vanilla 生效。Mending 只在获得 XP 时按 vanilla 修复，没有自定义免费修复。
- 背包中的 attack/armor 数值只用于选装评分，不会再次加入实际伤害/减伤。

资源语义有一个重要异常：Avatar 是 `MobEntity`，最终测量中近战剑、身上护甲和盾的耐久均未下降；Bow 因自定义发射路径显式损耗而正常下降。该问题会抬高长期续航，但修复会改变战斗经济，按“先测不调”原则留给第二轮决策。

## 2. 固定 Loadout

| Loadout | 装备 | 有限背包资源 |
|---|---|---|
| Naked | 无甲、Iron Sword | Bread ×4 |
| Iron Survival | 全套铁甲、Iron Sword、Bow、Shield | Arrow ×32、Bread ×8、Healing I ×3 |
| Diamond | 全套钻石甲、Diamond Sword、Bow、Shield | Arrow ×48、Steak ×12、Healing I ×4 |
| Netherite | 全套下界合金甲、Netherite Sword、Bow、Shield；无附魔 | Arrow ×48、Steak ×12、Healing I ×4 |
| Endgame Max | 全套 Netherite Protection IV/Unbreaking III/Mending；Netherite Sword Sharpness V/Unbreaking III/Mending；Bow Power V/Unbreaking III/Mending；Shield | Arrow ×64、Steak ×16、Strong Healing II ×6 |

Endgame Bow 选择 Mending 而不是 Infinity，确保普通箭真实消耗。所有食物、药水和箭都从真实 Avatar 背包取用，没有自动补充；20-potion stress 是专门的有限 20 瓶例外场景。

## 3. 测量方法与断言边界

新增 opt-in telemetry，仅在测试 session 开启时计数；战斗 AI 不读取测量结果。结构化结果记录：场景、等级、Loadout、姿态、结果、ticks、伤害、主人伤害、所有消耗、实际治疗、药水浪费、冷却期尝试、箭、盾挡、切换、接敌、撤退、主人距离、耐久和剩余资源。

第一轮只对明确能力做硬断言：Lv.4 弓、Lv.6 盾、Lv.9/10 避免 Warden、真实物品消耗、冷却生效、有限物品不复制。TTK、剩余 HP 和胜负均作为 measurement，不设凭空平衡门槛。

`damageDealt` 是固定测试区域内敌对实体的生命损失采样，不是攻击来源追踪。正常死亡的单体/波次值可靠；爆炸、召唤物、环境伤害及实体移除可能使其包含间接伤害或漏掉实体最后一段生命。因此 Mixed Pack、Evoker 等场景不能把该值等同于 Guard 精确个人 DPS。

## 4. 最终战斗数据

缩写：`Dmg=D/G/O` 为敌方生命损失 / Guard 实际承伤 / Owner 实际承伤；`Use=F/P/A` 为食物 / 药水 / 箭；`B/S/E/R` 为盾挡 / 武器切换 / 不同目标接敌 / 撤退；`Dist=avg/max` 为 Guard 到 Owner 距离；`WD` 为当前主武器耐久损失（实测主要是 Bow）。完整精度和每类剩余资源见 JSON。

### 等级与普通怪基准

| Scenario | Lv / Loadout / Stance | Result | ticks | Dmg D/G/O | Use F/P/A | Heal | B/S/E/R | Dist avg/max | HP | WD |
|---|---|---|---:|---|---|---:|---|---|---:|---:|
| 1 Zombie | 1 / Naked / B | WIN | 30 | 20/0/0 | 0/0/0 | 0 | 0/1/1/0 | 3.299/5.859 | 20 | 0 |
| 1 Zombie | 4 / Iron / B | WIN | 64 | 20/0/0 | 0/0/1 | 0 | 0/6/1/0 | 1.397/2.993 | 22 | 1 |
| 1 Zombie | 6 / Diamond / B | WIN | 30 | 20/0/0 | 0/0/0 | 0 | 0/1/1/0 | 3.299/5.859 | 24 | 0 |
| 1 Zombie | 8 / Netherite / B | WIN | 30 | 20/0/0 | 0/0/0 | 0 | 0/1/1/0 | 3.299/5.859 | 26 | 0 |
| 1 Zombie | 10 / Endgame / B | WIN | 21 | 20/0/0 | 0/0/0 | 0 | 0/1/1/0 | 2.299/3.855 | 30 | 0 |
| 5 Zombies | 1 / Naked / B | WIN | 127 | 100/12/0 | 1/0/0 | 1 | 0/1/5/0 | 1.663/3.734 | 9 | 0 |
| 5 Zombies | 6 / Iron / B | WIN | 138 | 100/5.52/0 | 1/0/0 | 1 | 0/1/5/0 | 1.924/3.627 | 19.48 | 0 |
| 10 Zombies | 10 / Endgame / B | WIN | 157 | 200/1.935/0 | 0/0/0 | 0 | 0/1/10/0 | 1.685/3.907 | 28.065 | 0 |
| 10 Zombies, 20-potion stress | 10 / Endgame / B | WIN | 177 | 200/2.177/0 | 0/0/0 | 0 | 0/1/10/0 | 1.703/3.124 | 27.823 | 0 |
| 1 Skeleton | 4 / Iron / B | WIN | 55 | 20/0/0 | 0/0/1 | 0 | 0/6/1/0 | 1.357/2.525 | 22 | 1 |
| 3 Skeletons | 6 / Iron / B | WIN | 322 | 60/6.16/0 | 2/0/10 | 2 | 0/1/3/0 | 5.831/10.527 | 19.84 | 11 |
| Shield probe: Skeleton, no ammo | 6 / Iron / B | WIN | 52 | 20/0/0 | 0/0/0 | 0 | 1/1/1/0 | 3.268/6.102 | 24 | 0 |
| 1 Creeper | 6 / Diamond / B | WIN | 82 | 20/0/0 | 0/0/3 | 0 | 0/1/1/0 | 1/1 | 24 | 3 |
| Mixed 3Z/2S/1C | 8 / Netherite / B | WIN | 81 | 100/6.144/0 | 1/0/0 | 1 | 0/2/4/0 | 1.111/2.374 | 20.856 | 2 |

### 高危普通敌人

| Scenario | Lv / Loadout / Stance | Result | ticks | Dmg D/G/O | Use F/P/A | Heal | CD attempts | B/S/E/R | Dist avg/max | HP | WD |
|---|---|---|---:|---|---|---:|---:|---|---|---:|---:|
| Blaze ×3 | 8 / Netherite / B | WIN | 394 | 60/12.8/0 | 5/0/13 | 9 | 69 | 0/1/3/0 | 1.034/1.563 | 22.2 | 13 |
| Wither Skeleton ×3 | 6 / Diamond / B | WIN | 86 | 60/0.44/0 | 0/0/0 | 0 | 0 | 0/1/3/0 | 1.953/4.399 | 23.56 | 0 |
| Piglin Brute ×2 | 8 / Netherite / B | LOSS | 464 | 94/39/0 | 7/0/11 | 13 | 186 | 0/1/2/2 | 6.408/10.563 | 0 | 11 |
| Vindicator ×3 | 10 / Diamond / B | WIN | 352 | 72/10/0 | 2/0/12 | 6 | 17 | 0/1/3/0 | 6.956/10.283 | 26 | 12 |
| Evoker + Vindicator | 10 / Netherite / B | LOSS | 435 | 28/41/0 | 6/0/3 | 11 | 147 | 0/3/6/1 | 2.462/5.926 | 0 | 3 |
| Ravager | 10 / Endgame / B | WIN | 143 | 100/3.836/0 | 0/0/5 | 0 | 0 | 0/1/1/0 | 4.662/8.839 | 26.164 | 1 |

### Raid、Boss 与飞行目标

| Scenario | Lv / Loadout / Stance | Result | ticks | Dmg D/G/O | Use F/P/A | Heal | CD attempts | B/S/E/R | Dist avg/max | HP | WD |
|---|---|---|---:|---|---|---:|---:|---|---|---:|---:|
| Fixed Raid Waves | 10 / Diamond / B | LOSS | 2148 | 346/65/0 | 12/2/48 | 35 | 351 | 20/4/20/1 | 3.89/14.189 | 0 | 48 |
| Fixed Raid Waves | 10 / Endgame / B | WIN | 1859 | 436/22.568/0 | 9/0/47 | 18 | 237 | 0/5/21/0 | 2.357/14.425 | 25.432 | 16 |
| Wither | 10 / Endgame / B | TIMEOUT | 3600 | 0/57.108/0 | 16/1/0 | 44 | 142 | 0/0/0/0 | 2.864/10.964 | 16.892 | 0 |
| Warden avoidance | 9 / Diamond / B | AVOIDED | 104 | 0/0/0 | 0/0/0 | 0 | 0 | 0/0/0/0 | 3.816/14.036 | 28 | 0 |
| Warden after Owner attack | 10 / Endgame / B | AVOIDED | 104 | 0/0/0 | 0/0/0 | 0 | 0 | 0/0/0/0 | 3.816/14.036 | 30 | 0 |
| Dragon flying-target proxy (Phantom) | 8 / Endgame / B | WIN | 23 | 20/0/0 | 0/0/1 | 0 | 0 | 0/1/1/0 | 1/1 | 26 | 0 |

完整 Ender Dragon encounter 未自动化。本轮只验证可控飞行目标选择和 Bow 路径。代码审计显示 Warden、Wither、Ender Dragon 都在 Lv.9+ high-threat 排除表内，因此 Lv.10 也会完全拒绝攻击 Dragon；这与“协助而非单刷”的目标不一致，是 AI 语义问题而非 DPS 问题。

### Owner protection

| Scenario | Lv / Loadout / Stance | Result | ticks | Dmg D/G/O | Use F/P/A | B/S/E/R | Dist avg/max | Guard HP |
|---|---|---|---:|---|---|---|---|---:|
| A: 5 Zombies | 8 / Diamond / Defensive | WIN | 167 | 100/4.14/0 | 0/0/0 | 0/1/5/0 | 1.777/3.274 | 21.86 |
| B: 3 Skeletons | 8 / Diamond / Balanced | WIN | 292 | 60/2.2/0 | 0/0/10 | 0/1/3/0 | 6.778/9.959 | 23.8 |
| C: Mixed pack | 10 / Endgame / Aggressive | WIN | 85 | 100/1.296/0 | 0/0/0 | 0/2/5/0 | 1.227/2.787 | 28.704 |
| D: Owner 25% HP + Mixed | 10 / Endgame / Aggressive | WIN | 82 | 100/1.889/0 | 0/0/2 | 0/2/4/0 | 0.924/1.375 | 28.111 |

四个场景 Owner 实际承伤均为 0。低血 Owner 场景的最大距离从可比 Mixed 场景的 2.787 降到 1.375，且 82 ticks 完成，说明 Owner Emergency 确实改变了贴身/优先行为。当前只是单批样本，不据此调整参数。

### Stance 定向追击探针

同一 Lv.8 Netherite Guard，Owner 与 Guard 固定在一起，目标被置于 policy radius 边缘之外的定向路径上；因此距离列保持 1，而差异体现在是否接敌及继续射击。

| Stance | Result | ticks | Damage dealt | Arrows | Engagements | Weapon switches | Avg/max owner distance |
|---|---|---:|---:|---:|---:|---:|---|
| Defensive | NOT_ENGAGED | 260 | 0 | 0 | 0 | 0 | 1/1 |
| Balanced | ENGAGED | 260 | 11 | 1 | 1 | 1 | 1/1 |
| Aggressive | ENGAGED | 260 | 62 | 8 | 1 | 1 | 1/1 |

三种姿态已有肉眼可见的目标保持差异：Defensive 不主动接敌，Balanced 射击一次后放弃越界目标，Aggressive 持续接战。该探针主要测 chase/retention，不代表开放地形中的实际跑离距离。

## 5. 治疗经济结论

全套 33 条结果合计：食物 62、药水 3、实际治疗 141 HP、药水浪费 0、冷却期消费尝试 1202、盾挡 21、撤退 4。

- 治疗强度当前主要来自食物 Regeneration，而不是药水。药水只出现在 Diamond Raid（2）和 Wither（1）。
- 20 瓶药压力场景打完 10 Zombies 消耗 0 瓶，剩余 20；轻微掉血不会把“20 瓶”变成 20 条额外生命。
- 所有药水浪费为 0，1202 次 cooldown 期间轮询未造成重复消费，60-tick Guard cooldown 确实生效。
- Diamond Raid 同时出现 20 次盾挡和 2 次药水，但最终仍死亡，没有形成 Shield + Potion 永久免伤循环。
- Wither 场景 16 食物 + 1 Strong Healing 共落地 44 HP 治疗，3600 ticks 后仍剩 16.892 HP；这说明持续时间很长时食物路径可提供显著续航。
- `stillHealing` 只在“当前待落地 regeneration 足以覆盖缺血”时阻止继续吃。高压战斗中可连续消耗多份食物，值得第二轮用重复样本重点验证。

## 6. 强弱与 AI 异常判断

明显偏强或需要复测：

- Lv.10 Endgame 对 10 Zombies 在 157 ticks 内获胜，只承受 1.935 HP，无任何资源消耗。
- Lv.10 Endgame 对 Ravager 获胜后剩 26.164/30 HP，只消耗 5 箭。
- Lv.10 Endgame 能自动完成固定高强度 Raid，剩 25.432/30 HP；但消耗 47 箭和 9 食物，并承受 22.568 累积伤害，不是无伤或无限续航。这里显示装备与附魔贡献非常大。
- Lv.1 Naked 在本批次以 9/20 HP 打赢 5 Zombies，接近边界；诊断批次有较明显随机波动，应先增加重复样本，不能以一次胜利直接削弱。

明显偏弱或失效：

- Lv.8 Netherite 输给 2 Piglin Brutes；消耗 7 食物、触发 2 次撤退仍死亡。
- Lv.10 Netherite 输给 Evoker + Vindicator；消耗 6 食物、触发 1 次撤退仍死亡。
- Lv.10 Diamond 固定 Raid 耗尽 48 箭和 12 食物、使用 2 药后死亡；与 Endgame 的反差说明目前强度更多来自附魔/装备，而非单纯 30 HP。
- 撤退在高压近战中会触发，但没有可靠脱离或创造安全治疗窗口。

AI 异常：

- Wither 被 high-threat 过滤：Lv.10 Endgame 3600 ticks 内接敌 0、输出 0、武器切换 0。它既不能单刷，也不能作为主人辅助；这不是“不过强”，而是目标语义失效。
- Warden 行为符合目标：Lv.9/Lv.10 都不主动攻击；即使 Owner 先攻击，Guard 仍保持回撤跟随且输出 0。
- Dragon 与 Wither 共用完全排除规则。Lv.8 飞行代理能正常使用 Bow，但 Lv.10 实际会拒绝 Dragon，需把“聪明规避 Warden”和“协助打 Boss”拆开设计。
- Lv.4 对单 Zombie/Skeleton 都出现 6 次武器切换、仅射 1 箭，明显高于其他单体场景，疑似弓/近战切换 hysteresis 不足。先重复采样再决定是否修。
- 盾在无箭 Skeleton 探针中真实挡住 1 箭。固定 Diamond Raid 有 20 次挡箭；Endgame Raid 为 0，因为有可用 Bow 时当前 AI 优先射击而不会举盾。

## 7. 测试暴露并已修复的真实漏洞

这些修复不改变任何平衡数值：

1. Power 曾在 projectile vanilla 附魔后又由 Squire 加一次，属于明确重复计算；已删除重复路径。
2. 敌对扫描只判断 `HostileEntity`，漏掉实现 vanilla `Monster` 标记但继承其他基类的敌人（测试中的 Phantom）；已改为按 `Monster` 判断。
3. Shield AI 会举盾但不保证朝向远程目标，真实箭可能绕过 vanilla 正面格挡；已在举盾时面向威胁。

ToolGate、§23、§23.1、§23.2 未修改。

## 8. 第二轮推荐项（未实施）

按优先级建议：

1. 先增加有固定种子/固定装备的重复样本，至少对 Naked 5 Zombies、Endgame 10 Zombies、两套 Raid、2 Brutes、Evoker 组合各跑多次，报告胜率和分位数。
2. 把 high-threat 策略拆分：Warden 保持规避；Wither/Dragon 允许在 Owner 已接战时提供受限辅助，不主动开战。
3. 决定 MobEntity 装备耐久语义。若修复剑/甲/盾损耗，应先重新跑整套，因为它直接改变长期 Raid 经济。
4. 重点检查 Lv.4 Bow/近战切换 hysteresis，避免频繁切换导致反而降低中期战力。
5. 为撤退增加“是否成功拉开距离/是否获得安全治疗窗口”的指标，再判断调整 retreat threshold 还是路径策略。
6. 治疗若需要收紧，优先评估 safe-to-heal 条件、食物连续消费和 cooldown；现有数据不支持先砍 HP、装备或药水恢复量。
7. 暂不增加职业额外 percentage damage；当前已有固定基础伤害加值，且 Endgame 普通怪/Raid 数据没有显示输出不足。

## 9. Regression

- Unit：706 tests，0 failures。新增 `GuardCombatBalanceContractTest` 4 条；当前构建实际可发现的改动前基线是 702，不是任务说明中的 712。没有为了凑数添加空测试。
- Required GameTests：184/184 passed，0 failed；相对要求基线 174 增加 10 个注册 GameTest 方法。
- M19、M20、M21、M22、§23、§23.1、§23.2 继续通过。

第一轮在此停止，不开始 Balance Patch。
