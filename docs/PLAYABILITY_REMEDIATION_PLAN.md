# Squire 玩家玩法闭环修复与优化实施方案

> 状态：工作包 A–I 全部已落地（阶段 1–6）。剩余：阶段 7 的一次真实服务器人工 B01–B12 验收。
>
> 已完成部分的证据：287 项单元测试 + 89 项 GameTest 全绿；
> 相关决策记录见 ADR-029~037，并已废止 ADR-021/022。
>
> 基线日期：2026-08-26
>
> 适用版本：Minecraft 1.20.1 / Fabric / Java 21
>
> 目的：把现有“底层组件可运行”推进到“玩家从聊天或命令入口触发后，最终游戏世界状态真实成立”。

## 1. 完成口径

本方案覆盖以下九类能力：

1. 具有长期身份和玩家外观的伙伴，以及 summon、follow、stay、stop、home、look、基础动作和状态保存。
2. 高频指令 FastPath、本地任务执行、复杂意图的 LLM 规划，以及失败后的有界重规划。
3. 真实背包、装备、采集、合成、熔炼、搬运、容器存取和整理。
4. 长期 Guard、本地 Combat Runtime，以及用真实物品帮助低血量玩家。
5. 基地、仓库、农场、矿洞等长期位置记忆和基地生活。
6. 普通建造及受 Permission、Capability、Preview、Confirmation、Undo 约束的 WorldEdit。
7. 可持久化、可暂停/恢复/删除的长期 AutomationGraph。
8. 仅在玩家明确要求时生成的真实命令方块工程（Materialized CBP）。
9. 第三方 Fabric Tool 和 MCP Tool 的调用、结果返回、后续回答及失败处理。

“完成”必须同时满足：

- 玩家入口可达，而不是只能由测试直接调用 Java API。
- 行为改变真实实体、背包、容器或世界状态，不用测试夹具代替结果。
- 重启恢复测试覆盖长期身份、记忆、任务/策略、自动化和项目注册表。
- 高风险操作具有真实可见的预览、一次性确认、原操作自动执行和可用撤销。
- B01–B12 每项都有从聊天/命令入口到最终世界状态的黑盒 GameTest。
- 组件测试、黑盒 GameTest 和一次人工交互验收全部通过。

## 2. 不可破坏的架构原则

### 2.1 LLM 的职责边界

- LLM 只负责复杂意图理解、高层计划、选择 Tool，以及收到结构化失败后的有界重规划。
- 寻路、战斗、采集、合成、熔炼、容器操作和逐 Tick 状态机全部由 Java Runtime 执行。
- 高频、确定性的中文/英文指令必须先走 FastPath；没有配置 LLM 时仍然可玩。
- LLM 不得直接提交 raw Minecraft command。结构化命令和 CBP 都必须经过各自的编译器及安全策略。

### 2.2 状态所有权

- `AgentRegistry` 只做已加载实体索引，不再充当长期身份的事实来源。
- 长期身份、位置记忆、长期策略和任务摘要使用世界存档内的 `PersistentState`。
- Avatar NBT 保存实体级状态；世界级 Store 保存“即使实体未加载或被 dismiss 也必须存在”的状态。
- 所有世界写入，无论来自普通采集、放置、WorldEdit、Automation 还是 CBP，都必须经过统一的 Protection/Permission 检查。

### 2.3 验收证据

- 测试名称不得使用“end-to-end”但在中途由测试代码发送第二条补救消息。
- 黑盒测试不得直接调用 `scheduler.submit`、`automation.create`、`gateway.dispatch` 或 CBP Builder 来代替玩家入口。
- Tool 返回 `SUCCESS` 只证明调用成功；最终目标仍必须由 Goal Verifier 检查真实世界状态。

## 3. 工作包 A：伙伴长期身份与控制语义（P0）

### A1. 建立长期身份 Store

新增 `SquireAgentStateStore extends PersistentState`，以 owner UUID 为索引保存 `AgentRecord`：

```text
AgentRecord
  ownerId
  agentId                 // 永久不变
  entityUuid              // 当前实体实例，可变化
  displayName
  skinProfileId / model
  activeBody              // 当前是否物化在世界中
  lastDimension + lastPos
  movementMode
  stayGlobalPos
  homeGlobalPos
  inventory + equipment
  health
  persistentPolicies      // guard 等长期策略引用
  schemaVersion
```

实现要求：

- 第一次 summon 创建 `agentId`；以后 summon、dismiss、死亡恢复、跨维度物化和服务器重启都沿用它。
- summon 已存在的伙伴时，恢复或移动同一身份，不丢背包、装备、名字、home、mode 和记忆。
- dismiss 只解除当前物化实体，不能删除 `AgentRecord`。
- 使用 `ServerEntityEvents.ENTITY_LOAD/ENTITY_UNLOAD` 修复索引并快照实体，防止未加载区块导致重复伙伴。
- 保存前检查相同 owner/agentId 的重复实体；保留 Store 指向的实体，其余安全移除并记录审计日志。
- Store 必须带 `schemaVersion`，未知未来版本 fail-closed，旧版缺省字段进行保守迁移。

主要文件：

- 新增 `server/agent/SquireAgentStateStore.java`
- 修改 `server/agent/AgentRegistry.java`
- 修改 `server/runtime/SquireRuntime.java`
- 修改 `SquireMod.java`
- 修改 `server/body/avatar/AvatarEntity.java`

### A2. 修复召唤、home 和外观

- 首次 summon 默认进入 FOLLOW；恢复既有伙伴时保持已保存模式，除非玩家明确说“跟着我”。
- 自定义名称由 `AgentRecord` 保存，不使用未持久化的 `summonCounter`。
- `/squire home set` 保存玩家当前位置的 `GlobalPos`，而不是 Avatar 所在方块。
- `home return` 使用保存的维度；跨维度时走受控传送/维度迁移策略，并在结果中明确说明。
- owner UUID 使用 `DataTracker` 同步到客户端。在线时渲染主人皮肤；离线时使用 UUID 对应的稳定默认皮肤或已缓存皮肤资料。
- Avatar 使用 36 格主背包，并将主手、副手和四个护甲槽作为真实装备状态保存。

### A3. 修复控制语义

- 为 `TaskScheduler` 增加 `cancelAgent(agentId, reason)`；`stop` 必须取消该伙伴所有 RUNNING/PENDING/RETRYING 任务、导航句柄和临时战斗目标。
- `stay` 保存锚点；受到推动后本地 Stay Goal 返回锚点，不能只关闭 wander。
- `follow`、`stay`、`stop`、`home` 的每次状态变化立即写入 Store。
- 增加可玩入口：
  - `/squire look me`
  - `/squire look <x> <y> <z>`
  - `/squire action wave|jump|nod|shake_head`
  - FastPath：“看着我”“挥手”“跳一下”“点头”“摇头”。
- Home MoveHandle 启动失败时返回 `PATH_NOT_FOUND/UNREACHABLE`，不能无条件回复“正在回家”。

### A4. 任务持久化

新增 `TaskStateStore` 保存可恢复任务的声明式状态：taskId、agentId、ownerId、type、参数、依赖、优先级、状态、重试次数、超时剩余量和 executor checkpoint。

- Executor 增加可选 `snapshot/restore` 接口。
- 重启后 RUNNING 转为 READY，并从最近 checkpoint 幂等恢复。
- 不可恢复任务必须明确转为 FAILED(`SERVER_RESTARTED`) 并通知 owner，不能静默消失。
- 依赖任务失败时，所有依赖它的任务转为 FAILED(`DEPENDENCY_FAILED`)，不能永久留在 pending。

### A5. 验收

- 重复 summon 后 agentId、名字、背包、装备和 home 不变。
- dismiss 后再次 summon 恢复同一身份。
- 服务器真实保存/重载后 FOLLOW/STAY 和可恢复任务恢复。
- 执行采集时说“停止”，任务和导航在同一服务器 Tick 内停止，之后不再改变方块。
- look/emote 可由命令和无 LLM 聊天触发。

## 4. 工作包 B：FastPath、目标编排和失败重规划（P0）

### B1. 将 FastPath 从控制枚举扩展为结构化意图

用密封接口或 typed record 替代仅返回 `ControlIntent` 的实现：

```text
FastPathIntent
  Control(follow/stay/stop/home/status/look/emote)
  AcquireAndGive(itemId, count)
  Gather(itemOrBlockId, count)
  CraftAndGive(itemId, count)
  Guard(radius, persistent)
  AidOwner
  RecallLocation(type/name)
  CreateNightLightsAutomation
  RequestRealCbp(template/name)
```

解析顺序：

1. 文本规范化但保留原始文本用于审计。
2. 控制短语精确匹配。
3. `ItemAliasResolver` 解析中文别名、数量、组数和 registry id。
4. 领域语法识别“给我/做/砍/收集/保护/我快死了/上次矿洞”等高频句式。
5. 仅在没有可信 FastPath 时提交 LLM。

必须加入：

- “给我 32 个火把” → `AcquireAndGive(torch, 32)`。
- “帮我砍 20 个橡木” → `Gather(oak_log, 20)`。
- “帮我做一把铁镐” → `CraftAndGive(iron_pickaxe, 1)`。
- “保护我” → 持久 Guard policy。
- “我快死了” → Owner Aid。
- 别名解析器必须由生产 `InputGateway` 调用，不能只由 admin probe 使用。

### B2. 目标级任务而不是互不关联的 Tool 调用

新增 `GoalCoordinator` 和持久 `GoalRecord`：

- `AcquireAndGive` 编译为“获取/制作 → 校验 Avatar 持有 → 交付 → 校验玩家持有”的 DAG。
- 交付任务的 verifier 检查玩家最终数量，不再要求 Avatar 交付后仍持有物品。
- `CraftPlanner` 明确区分 `targetCount` 与 `deficitCount`，Executor 和 Verifier 统一使用绝对目标，修复部分库存导致少做/验证失败的问题。
- 任一依赖失败时，GoalCoordinator 汇总结构化失败并决定本地替代计划或请求 LLM 重规划。
- 完成/失败必须通知玩家；离线时写入待送达通知队列。

### B3. 将对话改为有界异步循环

`ConversationOrchestrator` 改为持久 Turn 状态机：

```text
UNDERSTAND -> DISPATCH -> WAIT_TOOL/TASK -> OBSERVE ->
  COMPLETE | REPLAN(max 2) | ASK_USER | FAILED
```

- Tool/Task 的结构化结果（status、data、error、world observation）返回给当前 Turn。
- 查询 Tool 的 data 必须提供给 LLM 和玩家渲染器，不能压缩成 `(tool ok)`。
- `RUNNING` Tool 通过 taskId/callId 关联完成事件。
- 重规划最多两次，并受总 Tool 数、时间、Token 和风险预算约束。
- HIGH 风险重规划不得绕过原确认；参数改变后必须重新预览和确认。
- LLM 不参与 Runtime Tick。

主要文件：

- 重构 `server/fastpath/FastPath.java`
- 修改 `server/input/InputGateway.java`
- 新增 `server/goal/GoalCoordinator.java`、`GoalRecord.java`、`GoalStateStore.java`
- 修改 `server/runtime/ConversationOrchestrator.java`
- 修改 `server/task/TaskCompiler.java`、`TaskScheduler.java`、`CraftPlanner.java`
- 修改 `server/provider/OpenAiCompatibleProvider.java`

### B4. 验收

- 无 Provider 时一句“给我 32 个火把”完成获取和交付。
- 无 Provider 时“帮我做一把铁镐”完成获取材料、合成和交付。
- 部分已有库存的目标不会少做、重复做或卡在 verifier。
- 模拟某种资源不可达后，任务得到明确失败或有限重规划，不产生永久 pending。
- 查询型第三方 Tool 的结果能被玩家看到，并能被模型用于下一步回答。

## 5. 工作包 C：真实背包、装备与后勤（P1）

### C1. 权威 Inventory 模型

新增 `AvatarInventory`，统一管理：

- 36 格主背包；
- main hand / offhand；
- head / chest / legs / feet；
- 插入、提取、堆叠、序列化、容量预检和事务回滚；
- ItemStack 的 NBT、附魔、耐久和自定义数据，不能只按 item id 重建。

所有 Executor 只能通过该 Inventory API 修改物品。`InventoryView` 保持只读。

### C2. 真实工具和装备

- `GatherBlockExecutor.bestToolFor` 返回背包中的真实 slot/stack 引用。
- FakePlayer 破坏完成后，将耐久、附魔消耗和破损结果写回同一装备槽。
- 新增 `inventory.equip` / `inventory.unequip` Tool；Guard 自动选择合法的最佳武器/护甲，但不得复制物品。
- 掉落物通过真实拾取或受控 direct-collect 进入背包；背包满时保留世界掉落并返回 `INVENTORY_FULL`。

### C3. 容器物流

新增 Tool/Task：

- `inventory.pickup_nearby`
- `container.deposit`
- `container.withdraw`
- `container.transfer`
- `container.sort`
- `container.inspect`

规则：

- 容器位置必须来自可见目标、玩家明确坐标或可信位置记忆。
- 必须加载并验证目标 BlockEntity 类型、距离、权限和容量。
- 使用两阶段事务：先计算可移动量，再同时提交源/目标修改；异常时回滚。
- sort 使用稳定、可配置的分类规则，保留物品 NBT，不删除未知物品。

### C4. 真实合成与熔炼

- 合成继续使用 `RecipeManager`，并按配方要求检查工作台距离；2x2 配方允许随身制作。
- 熔炼替换 ADR-022 的模拟实现：寻找/记忆真实熔炉，写入 input/fuel，等待 FurnaceBlockEntity 产生 output，再提取。
- 熔炉被玩家占用、产物槽不兼容或区块卸载时进入 WAITING/FAILED，不直接生成产物。
- 支持 blast furnace/smoker 的配方类型；优先复用现有设备，不自动放置设备，除非上层计划明确获得建造权限。

### C5. 保护覆盖

- `GatherBlockExecutor`、`OneShotWorldExecutors`、容器修改和自动化写入全部调用同一 `ProtectionAdapter`。
- 任何保护拒绝都产生结构化 `PROTECTED_REGION`，用于通知或重规划。

## 6. 工作包 D：Guard 与玩家生存辅助（P0/P1）

### D1. 持久 Guard Policy

“保护我”创建 `GuardPolicy`，而不是最长 24000 Tick 的一次性 Task：

```text
GuardPolicy
  ownerId + agentId
  enabled
  radius
  rules
  createdAt
```

- Policy 保存在 AgentRecord，直到玩家说“停止保护”或显式关闭。
- Owner 在线且 Avatar 已物化时，本地 Combat Runtime 激活；离线时休眠但不删除。
- Combat Runtime 识别 HostileEntity、正在攻击 owner 的中立生物、近程投射物威胁和 owner 最近攻击者。
- 目标选择、寻路、攻击冷却、撤退和换装备都在 Java Runtime 内完成。
- 不追击出保护半径；优先保证 owner 生存，避免无限追怪。

### D2. 真实玩家救助

将 `heal.self` 与 `aid.owner` 分开：

- `heal.self` 只处理伙伴自救。
- `aid.owner` 检查玩家生命、距离、状态效果和可用物品。
- 使用 Avatar 背包中的真实治疗物品；通过物品原生 `finishUsing`/效果路径或真实转交使用，不直接调用 flat `heal` 冒充物品效果。
- 优先级示例：治疗药水/喷溅药水、金苹果、食物；由配置和实际状态决定。
- 每次消耗从权威背包扣除并保留容器物品。
- 没有物品时返回 `INSUFFICIENT_HEALING_ITEM`，可触发获取任务，但不得凭空生成。

### D3. 验收

- “保护我”跨多个昼夜、区块卸载和重启仍有效，直到显式关闭。
- Guard 使用真实已装备武器，耐久变化可见。
- “我快死了”测试伤害的是 owner；最终 owner 血量/效果改善且 Avatar 背包中的真实物品减少。

## 7. 工作包 E：长期位置记忆与基地生活（P0）

### E1. 位置记忆模型

新增 `LocationMemoryStore extends PersistentState`：

```text
LocationMemory
  memoryId
  ownerId + agentId
  type        // HOME, WAREHOUSE, FARM, MINE, CUSTOM
  canonicalName
  aliases
  GlobalPos
  radius/region (optional)
  createdAt / lastVisitedAt / lastConfirmedAt
  confidence
  source      // PLAYER_EXPLICIT, OBSERVED, TASK_RESULT
```

- `GlobalPos` 必须包含维度和 BlockPos。
- 玩家显式命名的位置优先级高于自动观察。
- “上次矿洞”按 type=MINE、lastVisitedAt 最大值解析。
- 同名冲突时必须询问或列出候选，不能静默选择错误位置。
- 删除记忆和修改记忆都要求 owner/admin 权限。

### E2. Tool 和 FastPath

新增：

- `memory.remember_location`
- `memory.recall_location`
- `memory.list_locations`
- `memory.forget_location`
- `memory.set_home`
- `memory.register_container`

FastPath：

- “这里是基地/仓库/农场/矿洞”。
- “回家”。
- “仓库在哪”。
- “上次矿洞在哪”。
- “把矿放回仓库”。

### E3. 基地后勤

- 仓库记忆必须关联一个已验证容器或区域；存矿任务在到达后重新验证容器。
- “把矿放回仓库”编译为筛选矿物 → 导航 → deposit → 最终容器状态验证。
- 农场/矿洞记忆仅提供可信位置，不自动授予破坏或编辑权限。

### E4. 验收

- home、warehouse、farm、mine 跨重启保留。
- 在两个维度记录矿洞后，“上次矿洞在哪”返回正确维度和坐标。
- “把矿放回仓库”真实减少 Avatar 背包并增加目标容器库存。

## 8. 工作包 F：建造与受控 WorldEdit（P0）

### F1. 普通建造

- 保留单方块 place/break，增加结构化 `build.structure` 计划和逐层放置 Task。
- 材料必须来自 Avatar 背包/已授权仓库；普通建造不能通过命令生成方块。
- 每一方块写入前执行 Protection 检查；失败后停止或按明确策略跳过，并给出汇总。

### F2. 玩家 Selection

新增持久或会话级 `SelectionService`：

- `/squire selection pos1 [x y z]`
- `/squire selection pos2 [x y z]`
- `/squire selection clear`
- `/squire selection show`
- 可选使用玩家注视方块，但必须把解析后的维度、边界和体积展示给玩家。

“把选定区域铺成石头”只引用 server-owned selection，LLM 不生成隐式坐标。

### F3. 预览、确认和自动执行

把 `ConfirmationService.Request` 扩展为持久 `PendingOperation`：

```text
PendingOperation
  confirmId
  ownerId + agentId
  operationType
  canonicalArguments
  fingerprint
  preview
  issuedAt / expiresAt
  status
```

- ToolGateway 遇到 HIGH 风险调用时先生成 canonical plan 和 preview，再保存完整 PendingOperation。
- preview 至少包含：工具名、维度、区域、方块数、材料、受影响实体/容器、预计 Undo 大小。
- `/squire confirm <id>` 在服务器线程一次性自动重放保存的 canonical operation。
- 确认后参数不可由 LLM 修改；任何变化生成新的 preview 和 confirmId。
- 返回“executing”之前必须成功提交真实任务；提交失败返回明确错误。
- 增加 `/squire deny <id>` 和过期清理。

### F4. 可用且持久的 Undo

- 为普通 WorldEdit 增加 `/squire undo [operationId]`、`/squire undo list`。
- Undo Journal 持久化到世界存档，保存旧 BlockState、BlockEntity NBT、维度、owner、operationId 和过期时间。
- 撤销也执行 Permission/Protection 检查；发生冲突时预览冲突并要求确认。
- 对超出可撤销容量的操作，在执行前拒绝，不能先改世界再发现无法撤销。

### F5. Capability 生命周期

- Capability 必须在确认成功后签发，绑定 owner、agent、tool、dimension、region、block budget 和 operationId。
- Task 每个批次重新验证；完成、失败、取消、过期后立即撤销。
- 世界编辑开关和权限节点继续默认关闭；控制台/服主显式启用。

### F6. 验收

- 玩家选择区域、发一句自然语言、看到 preview、确认一次后操作自动开始。
- 不需要重复原自然语言请求。
- 未确认、确认者不匹配、已过期、参数篡改或超预算时世界零变化。
- `/squire undo` 精确恢复方块和 BlockEntity NBT；重启后仍可撤销未过期操作。

## 9. 工作包 G：长期 AutomationGraph（P0）

### G1. 开放安全创建入口

新增模型可见但结构化的 `automation.create` Tool，以及等价命令：

```text
/squire automation create <template> ...
```

- Tool 只接受经过白名单的 Trigger、Condition 和节点类型，不能传任意 Java 类或 raw command。
- `AutomationCompiler` 把结构化 DSL 编译为 Graph，验证无环/受控循环、节点数、最短触发间隔、权限和 Tool 存在性。
- 创建前展示 trigger、condition、动作、位置、有效期和权限 preview。
- 写世界的自动化必须确认；只读通知可按低风险策略直接创建。

### G2. 真正的夜间基地开灯

实现 `base.lights.set` Task/Tool：

- 目标区域来自可信 BASE/HOME memory。
- 控制已登记的灯光锚点，或扫描受限半径内的 redstone lamp/lever；不得无界扫描。
- 开灯消耗/改变真实世界资源或红石状态，不用 `example:echo` 代替。
- 默认模板创建夜间开灯和清晨关灯两个触发分支/Graph。
- 所有写入经过 Permission、Protection 和 Capability；首次创建提供 preview/confirm/undo。

### G3. 生命周期和持久化

- recurring Graph 默认无限期，直到 pause/remove；不能默认两周后静默过期。
- 一次性 Graph 可显式 TTL。
- engine enabled/disabled 状态持久化；默认安装后仍为关闭，由服主启用一次。
- pause/resume/remove/fire/list/inspect 保留，并增加 owner 离线通知队列。
- 重启恢复 cursor、last-fired、防重复令牌和 PAUSED 状态。

### G4. 验收

- 玩家一句“每天晚上在基地开灯”可创建真实 Graph。
- 夜晚真实灯光状态变化，白天恢复；世界中不新增命令方块。
- 重启后仍触发；pause 后不触发，resume 后继续，remove 后永久停止。

## 10. 工作包 H：真实命令方块工程（P1）

### H1. 明确意图门槛

- 只有包含“真实/可见/可编辑/命令方块/红石/教学/分享”等明确意图时，才能进入 Materialized CBP。
- 普通“每天晚上开灯”必须走 AutomationGraph，不得自动物化命令方块。
- 意图不明确时询问玩家，不做世界写入。

### H2. CBP Planner 和模板

新增高风险 Tool：

- `cbp.plan_project`
- `cbp.preview_project`
- `cbp.materialize_project`

Planner 输出结构化 `CbpSpec`，支持多 entry、朝向、conditional/auto、红石连接和说明牌。首批白名单模板至少包含：

- 真实命令方块昼夜控制器；
- 可编辑的脉冲/重复命令教学装置。

命令必须由 `StructuredCommandCompiler/SafeCommandPolicy` 生成；LLM 不能绕过白名单塞 raw command。

### H3. Workspace、Registry 和 Undo

- project 必须使用真实 Avatar agentId，不能把 player UUID 填入 agentId。
- 每个 entry 都必须在 owner workspace 内。
- preview 列出每个方块位置、类型、命令摘要、auto/conditional、红石材料和碰撞。
- Confirmation 后由现有 Materializer 分 Tick 放置并验证。
- Registry 持久化 spec、owner、agent、workspace、状态和 undo operationId。
- 重启后 remove 仍能恢复原方块；Undo 数据缺失时不得谎称可恢复。

### H4. 验收

- “做一个真实命令方块昼夜控制器”生成多方块预览，确认前零变化。
- 确认后生成真实、可见、可编辑、可接红石的装置。
- inspect/disable/enable/remove 可用；remove 精确恢复原区域。
- 普通自动化用例全程不产生新命令方块。

## 11. 工作包 I：第三方 Mod 与 MCP 结果闭环（P0/P1）

### I1. 第三方 Fabric Tool

- 保留 `squire` entrypoint、信任等级、数量上限和 Permission Node。
- External Tool 的完整 data/error 返回 `ConversationOrchestrator`。
- `ResultRenderer` 对玩家输出可读摘要；原始结构化数据仅进入受限日志/下一轮模型上下文。
- 返回体大小、字符串长度、嵌套深度和敏感字段执行限制与脱敏。

### I2. MCP 配置和调用关联

新增 `config/squire/mcp.json` 加载器和管理命令：

```text
/squire admin mcp list
/squire admin mcp reload
/squire admin mcp status <server>
```

- 配置引用 stdio/http transport、超时、allowlist 和 trust-store key；不在聊天中接受服务器地址或密钥。
- `PendingExternalCallRegistry` 保存 callId → owner/agent/turn/task/tool/deadline。
- MCP 返回 RUNNING 后，异步结果必须切回服务器线程，完成对应 Turn/Task，并通知玩家。
- 默认 no-op listener 只能作为额外观测钩子，不能作为唯一结果消费者。
- 超时、断线、协议错误和熔断都生成结构化错误，允许有界重试/重规划。
- 对协议采用的 JSON-RPC framing 和 transport 能力写清兼容范围；不声称支持未实现的 SSE/Content-Length 模式。

### I3. 验收

- 第三方只读 Tool 返回 `{"status":"running","energy":...}` 时，玩家能看到机器状态，LLM 能基于该值回答。
- MCP 延迟返回时，Turn 保持 WAIT_TOOL，完成后继续而不是丢失结果。
- 未信任或请求写权限的外部 Tool 被拒绝，世界零变化。
- MCP 配置可在不写 Java 代码的情况下由服主部署和重载。

## 12. 文件级落点汇总

| 区域 | 主要修改 | 主要新增 |
|---|---|---|
| 伙伴 | `AvatarEntity`, `AgentRegistry`, `SquireRuntime`, `SquireMod`, renderer | `SquireAgentStateStore`, `AgentRecord` |
| 控制/对话 | `FastPath`, `InputGateway`, `ConversationOrchestrator`, provider prompt | `FastPathIntent`, `GoalCoordinator`, `GoalStateStore` |
| 任务 | `Task`, `TaskScheduler`, `TaskCompiler`, executors | `TaskStateStore`, executor checkpoint API |
| 背包/后勤 | gather/craft/smelt/give executors | `AvatarInventory`, equip/container executors |
| 生存 | guard/heal executors和 tools | `GuardPolicy`, `OwnerAidExecutor` |
| 记忆 | runtime/tool catalog | `LocationMemoryStore`, memory/container tools |
| 世界编辑 | `ToolGateway`, `ConfirmationService`, `WorldEditor`, `UndoJournal`, commands | `PendingOperationStore`, `SelectionService` |
| 自动化 | `AutomationGraph`, `AutomationEngine`, commands | `AutomationCompiler`, automation tools, light task |
| CBP | `CbpSpec`, `CbpMaterializer`, `CbpRegistry`, commands | CBP planner/tools/templates |
| 扩展/MCP | `ExtensionManager`, `McpClientBridge`, conversation | MCP config loader, pending-call registry, result renderer |
| 测试 | 现有 M1–M6 tests | `B01ToB12BlackBoxGameTests`、真实重启测试夹具 |

## 13. 数据迁移与兼容策略

### 13.1 Avatar 和 AgentRecord

- 现有 Avatar NBT 只有 agentId、owner、home BlockPos 和 9 格 inventory。
- 首次加载时迁移到 36 格，原槽位保持不变；缺失装备槽为空。
- 旧 home 的 dimension 取实体保存时所在维度；若无法确定，标记 `needsConfirmation`，玩家下次回家前确认。
- 旧 mode 缺失时恢复 IDLE；仅新版本开始持久化 mode。
- 已存在实体生成 `AgentRecord`，沿用原 agentId；名字缺失时生成一次并保存。

### 13.2 Automation/CBP/Undo

- 现有 Automation JSON 加 `schemaVersion`；旧 recurring graph 的历史默认 TTL 只迁移为无限期一次，并写迁移日志。
- CBP Registry 旧项目缺少 agentId 时，从 owner 当前 AgentRecord 补全；无法解析则置为 `NEEDS_REPAIR`，禁止继续写世界。
- 旧 UndoJournal 没有持久数据，不能伪造恢复能力；升级后只保证新 operation 可跨重启撤销。

### 13.3 配置

- 新配置字段必须有安全默认值。
- `worldEditEnabled`、`cbpEnabled`、外部写 Tool 信任继续默认关闭。
- 配置错误导致相关功能降级和明确日志，不得阻止服务器启动。

## 14. B01–B12 黑盒验收矩阵

所有测试从 `InputGateway.acceptChat` 或真实命令 dispatcher 开始；除复杂语言理解可使用 ScriptedProvider 外，测试不得直接调用内部 Builder/Gateway/Scheduler。

| 编号 | 玩家输入 | 必须验证的最终状态 | 禁止替代证据 |
|---|---|---|---|
| B01 | 跟着我 | Avatar mode=FOLLOW，实际靠近移动中的 owner | 只检查枚举或 Goal 注册 |
| B02 | 给我 32 个火把 | owner 新增至少 32 火把，世界资源和 Avatar 中间库存守恒 | 测试发送第二条“交出来” |
| B03 | 帮我砍 20 个橡木 | 20 个真实原木被合法采集，工具耐久变化，背包/交付状态正确 | 预先直接塞入原木 |
| B04 | 做一把铁镐 | 消耗真实材料，经真实配方得到并交付铁镐 | 直接 give 或硬编码产物 |
| B05 | 保护我 | 本地 Guard 击退威胁，owner 存活，LLM 无逐 Tick 调用 | 直接 gateway.dispatch guard |
| B06 | 我快死了 | 受伤的是 owner；消耗 Avatar 的真实治疗物品并改善 owner 状态 | 治疗 Avatar 自己 |
| B07 | 把选定区域铺成石头 | preview → confirm → 自动执行；材料/权限/保护/undo 全验证 | 测试直接调用 WorldEditor |
| B08 | 回家 | 使用持久 GlobalPos，在重启后仍到达正确 home | 测试直接 setHomePos |
| B09 | 每天晚上在基地开灯 | Graph 由玩家入口创建；夜晚真实开灯、白天关灯，无新增 CB | `example:echo("lights-on")` |
| B10 | 做一个真实命令方块昼夜控制器 | 明确 CBP 意图后预览、确认、物化真实多方块工程、Registry/Undo 可用 | 手工直接构造 CbpSpec |
| B11 | 上次矿洞在哪 | 返回持久化的最近矿洞维度和坐标，重启后相同 | 只检查 home |
| B12 | 使用第三方 Tool 查询机器状态 | Tool/MCP data 回到 Turn，玩家得到具体状态值 | 仅 outcome listener 收到值 |

额外回归：

- 重复 summon 不换身份。
- stop 取消进行中的世界任务。
- 部分库存的 acquire/craft 目标正确。
- 依赖失败不留下永久 pending。
- 所有普通世界 mutation 执行 Protection 检查。
- HIGH 风险确认自动执行一次，不能重放两次。
- MCP 延迟完成、超时和断线均能结束 Turn。

## 15. 实施顺序与合并门禁

### 阶段 1：身份与任务生命周期

内容：A1–A4、Task dependency failure、实体 load/unload。

门禁：M1 单测/GameTest、真实存档重载测试、stop 黑盒测试通过。

### 阶段 2：FastPath 和目标闭环

内容：B1–B3、B02/B03/B04、结果通知。

门禁：无 Provider 的高频指令全部通过；单条消息完成 acquire-and-give。

### 阶段 3：真实资源、生存和记忆

内容：C、D、E。

门禁：B03–B06、B08、B11 通过；物品守恒和容器事务测试通过。

### 阶段 4：世界编辑安全闭环

内容：F。

门禁：B07 和确认/拒绝/过期/篡改/跨重启 Undo 测试通过。

### 阶段 5：Automation 和 CBP

内容：G、H。

门禁：B09/B10 通过；普通 automation 的命令方块增量为零。

### 阶段 6：扩展和 MCP

内容：I。

门禁：B12、trust、timeout、result round-trip 测试通过。

### 阶段 7：发布验收

执行：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21'
.\gradlew.bat clean test
.\gradlew.bat runGametest
```

然后在独立测试世界完成一次 B01–B12 人工验收，保存：

- 输入文本；
- 可见反馈；
- 最终世界/背包状态；
- 高风险 preview/confirm/undo 记录；
- 重启前后状态；
- MCP/第三方 Tool 返回值。

任何阶段不得用更新文档宣称完成来替代缺失的玩家闭环。

## 16. 最终 Definition of Done

- [ ] AgentRecord 是长期身份事实来源；重复召唤、dismiss、重启不丢身份或状态。
- [ ] follow/stay/stop/home/look/emote 都有命令和无 LLM FastPath。
- [ ] stop 同时取消导航和该 agent 的活动/排队任务。
- [ ] “给我/砍/做”高频资源指令无需 LLM，并能用一条消息完成最终目标。
- [ ] LLM 只做高层规划，能观察 Tool/Task 结果并进行有界重规划。
- [ ] 真实 36 格背包、装备、耐久、容器物流、真实合成和熔炉交互成立。
- [ ] 长期 Guard 和真实 owner aid 成立。
- [ ] home/base/warehouse/farm/mine 位置记忆包含维度并跨重启恢复。
- [ ] 普通建造和采集同样经过 Protection。
- [ ] WorldEdit 完成 Selection、Preview、Confirmation 自动执行、Capability 和持久 Undo 闭环。
- [ ] Automation 可由玩家创建，能真实控制基地灯光并支持持久 pause/resume/remove。
- [ ] 只有明确 CBP 意图才物化真实命令方块工程，Workspace/Registry/Undo 全程有效。
- [ ] 第三方 Tool/MCP 的 data 返回玩家和 LLM，异步完成不丢失。
- [ ] 现有组件测试及 B01–B12 黑盒 GameTest 全部通过。
- [ ] 至少一次真实服务器人工 B01–B12 验收完成并留存证据。

