# Squire AI — Minecraft Java 1.20.1 Fabric 智能玩家助手模组
## 项目开发规格书 / Implementation Specification

> **文档版本：v3.1**  
> **目标平台：Minecraft Java Edition 1.20.1 / Fabric / Java 17**  
> **用途：本文件是项目开发的主要规范，供人类开发者与 Coding Agent 共同执行。**  
> **推荐仓库路径：`docs/IMPLEMENTATION_SPEC.md`**  
> **推荐许可证：Apache-2.0**  
> **状态：M0 技术验证通过后进入实现冻结**

---

# 0. 文档执行规则

## 0.1 本文档的优先级

实现发生冲突时，按以下优先级处理：

1. **安全与权限约束**
2. **明确标记为 MUST / MUST NOT 的要求**
3. **架构决策（ADR）**
4. **接口与数据模型契约**
5. **里程碑 Definition of Done**
6. **功能说明与示例**

不得为了“实现更快”绕过更高优先级规则。

## 0.2 Coding Agent 执行要求

Coding Agent 必须：

- 按 M0 → M1 → M2 → M3 → M4 → M5 → M6 顺序开发；
- 在开始某里程碑前检查该里程碑前置条件；
- 每完成一个独立模块立即补单元测试或 GameTest；
- 任何世界读写只在 Minecraft 服务端线程执行；
- 任何 LLM / MCP / HTTP I/O 都不得阻塞服务端线程；
- 不得自行增加高风险能力；
- 不得自行把 Experimental 功能提升为默认功能；
- 不得新增 Raw Command Tool；
- 不得把权限逻辑写进 Prompt 代替 Java 校验；
- 不得把未验证的 1.20.1 API 当成事实直接大规模实现；
- 遇到版本/API 不确定项时先新增最小 Spike 或测试；
- 修改架构边界时必须增加或修改 ADR；
- 修改持久化格式时必须增加 `schemaVersion` migration；
- 修复安全漏洞时必须新增永久回归测试；
- 完成一个里程碑后必须运行该里程碑规定的测试集合。

## 0.3 禁止通过猜测填补的内容

以下内容若未在 M0 验证，不允许 Coding Agent自行假定：

- Fabric 1.20.1 某 API 的精确方法签名；
- FakePlayer 对第三方模组事件的具体触发行为；
- 皮肤/TabList 的客户端表现；
- 保护区模组的具体兼容接口；
- CommandBlockBlockEntity 的边界行为；
- 某 LLM Provider 的具体 Function Calling 格式；
- MCP SDK 的具体实现类名与传输 API。

这些问题应通过：

```text
官方源码 / Javadoc / IDE 映射 / 最小测试 / GameTest
```

得到结论后再实现。

---

# 1. 产品目标

Squire AI 是一个在 Minecraft 世界中具有长期身份和玩家外观的 AI 伙伴。

玩家可以：

- 与其自然语言聊天；
- 让其跟随、待命、回家；
- 让其保护玩家并攻击威胁；
- 在玩家低血量时使用真实治疗物品；
- 管理 AI 自己的背包和装备；
- 获取、制作、熔炼、搬运和整理物品；
- 让 AI 记住基地、仓库、农场、矿洞等地点；
- 让 AI 建造结构；
- 在授权模式下执行受控世界编辑和 Minecraft 指令；
- 创建长期自动化；
- 在明确要求时生成真实命令方块工程；
- 通过 Tool API / MCP Bridge 使用第三方能力。

核心目标不是“回答得像真人”，而是：

```text
理解目标
→ 选择合法能力
→ 执行
→ 观察真实结果
→ 必要时重试/重规划
→ 验证
→ 汇报
```

---

# 2. 非目标

v1.0 不实现：

- 自动通关；
- X-Ray；
- 反作弊绕过；
- 任意 Shell；
- RCON；
- 任意文件系统访问；
- 模型自主修改服务器权限；
- 模型自主修改 MCP Trust；
- 模型自主修改 Agent Owner；
- LLM 每 Tick 控制角色；
- LLM 直接执行任意 Minecraft 命令字符串；
- 默认远程 MCP；
- 默认世界编辑；
- 默认命令方块自动化；
- 默认无限后台运行；
- Forge / NeoForge；
- 其他 Minecraft 版本；
- 语音输入/语音合成。

---

# 3. 技术基线

```text
Minecraft            1.20.1
Java                 17
Fabric Loader        M0 锁定
Fabric API           候选 0.92.11+1.20.1，M0 锁定
Yarn mappings        M0 锁定
Gradle / Loom         M0 锁定
```

所有依赖版本必须写入：

```text
gradle.properties
libs.versions.toml（若采用 Version Catalog）
docs/version-matrix/mc-1.20.1.md
```

禁止使用浮动版本。

---

# 4. 核心架构决策

## ADR-001：Server Authoritative

服务端是唯一事实源。

服务端负责：

- Agent 状态；
- Task；
- Tool Gateway；
- Permission；
- World modification；
- Inventory；
- Command；
- Automation；
- Memory persistence；
- Audit；
- LLM/MCP 请求调度。

客户端仅负责：

- Renderer；
- GUI；
- HUD；
- Debug visualization；
- 用户输入。

所有 C2S 数据必须重新校验。

---

## ADR-002：AgentBody 抽象

上层系统只依赖：

```java
AgentBody
```

不得依赖具体实体类型。

默认：

```text
AvatarBody
```

可选：

```text
ServerPlayerBody（Experimental）
TestBody
```

---

## ADR-003：AvatarBody 作为 v1 默认身体

默认 Agent 使用：

```text
PathAwareEntity / 自定义 MobEntity
+
PlayerEntityModel Renderer
```

原因：

- 复用 Navigation；
- 复用 Goal / TargetSelector；
- 生命周期稳定；
- 战斗 AI 更容易实现；
- 与 Task Runtime 解耦。

因此 v1 默认要求客户端安装 Squire Client 模组以显示 Avatar。

---

## ADR-004：FakePlayer 只作为 Interaction Proxy

Fabric `FakePlayer` 用于需要 PlayerEntity 语义的短时交互：

- break；
- place；
- use item；
- use block；
- 第三方模组 Player callback。

它不是默认 AgentBody。

不得长期缓存不必要的 World/FakePlayer 强引用。

---

## ADR-005：FastPath 优先于 LLM

高频、明确、低风险意图优先本地解析。

只有 FastPath 未命中或需要自然语言规划时才调用 LLM。

---

## ADR-006：LLM 只运行低频 Cognitive Loop

LLM 负责：

- 对话；
- 意图理解；
- 澄清；
- 高层规划；
- 失败重规划。

LLM 不负责：

- 每 Tick 移动；
- 每 Tick 战斗；
- 攻击 Cooldown；
- 实时寻路；
- 方块破坏 Tick；
- 实时药水投掷控制。

---

## ADR-007：Tool Gateway 是唯一执行边界

所有 Tool，无论来自：

- Squire Core；
- 第三方 Fabric Mod；
- MCP；

都必须经过同一个 Tool Gateway。

不存在旁路。

---

## ADR-008：模型不能直接执行任意命令

官方 Core 不提供：

```text
execute_command(String)
raw_command
```

模型只能调用结构化 Command Tool。

---

## ADR-009：AutomationGraph 替代隐藏命令方块

长期：

- Timer；
- Condition；
- Loop；
- Event；
- Schedule；

由 Java `AutomationGraph` 执行。

不得为了内部自动化偷偷放命令方块。

---

## ADR-010：真实命令方块只作为显式产物

只有玩家明确需要：

- 可见；
- 可编辑；
- 红石联动；
- 教学；
- 可分享；

的命令方块工程时，才创建 Materialized CBP。

---

## ADR-011：Provider Independent Agent Protocol

Core 使用自己的：

```text
Squire Agent Protocol
```

Provider Adapter 负责转换到不同模型 API。

---

## ADR-012：MCP 是扩展协议，不是权限系统

MCP Server 只提供能力描述和调用入口。

Squire 自己决定：

- 是否导入；
- 是否暴露；
- 是否允许；
- 风险；
- 配额；
- Capability；
- 是否执行。

---

## ADR-013：Operational Status 由 Runtime 生成

LLM 可以说：

```text
“好，我去做。”
```

但：

```text
RUNNING
FAILED
COMPLETED
37/64
```

只能由 Runtime 生成。

---

## ADR-014：Policy 与 Memory 分离

以下内容属于 Typed Policy，不属于可被 LLM 写入的 Memory：

- Owner；
- Permission；
- Economy Mode；
- World Edit 权限；
- Command 权限；
- MCP Trust；
- Provider Endpoint；
- Remote MCP；
- Killswitch；
- Confirmation policy。

---

# 5. Repository / Gradle 模块

推荐结构：

```text
squire/
├── common/
├── api/
├── server/
├── client/
├── mcp/
├── gametest/
├── test-fixtures/
└── docs/
```

## 5.1 `common`

不得 import `net.minecraft.*`。

包含：

```text
protocol/
tool/
task/
policy/
capability/
errors/
alias/
quota/
audit-model/
```

## 5.2 `api`

提供给第三方 Fabric Mod。

包含：

```text
Tool API
Task API
Sensor API
Event API
Protection Adapter API
```

## 5.3 `server`

包含：

```text
Agent Runtime
AgentBody implementation
Input
FastPath
Perception
Planner
Task Scheduler
Behavior
Tool Gateway
Native Tools
Command Compiler
WorldEditor
Automation
Memory
Permission
Persistence
Networking
Audit
```

## 5.4 `client`

包含：

```text
Avatar Renderer
Companion GUI
Task HUD
Confirmation UI
Debug Renderer
```

## 5.5 `mcp`

可选模块：

```text
MCP Client
MCP Server Gateway
Transport
Tool Import
Trust Store
MCP Policy
```

Core 不得要求 MCP 才能启动。

---

# 6. Runtime 主数据流

```text
Player Input
 ↓
InputGateway
 ↓
Sender / Address Resolution
 ↓
Permission Context
 ↓
FastPath
 ├─ HIT → Typed Intent
 └─ MISS
       ↓
   ContextBuilder
       ↓
      LLM
       ↓
 Function Calling
       ↓
Squire Agent Protocol
       ↓
TaskCompiler
       ↓
TaskGraph
       ↓
Tool Gateway
       ↓
Native / Mod / MCP Tool
       ↓
Agent Runtime / World
       ↓
Observer
       ↓
Goal Verification
 ├─ not met → Retry/Replan
 └─ met → COMPLETED
```

---

# 7. 线程模型

## 7.1 主线程允许

- Entity；
- World；
- Inventory；
- BlockEntity；
- Command；
- Task 状态提交；
- Tool 最终 World Write。

## 7.2 Worker 线程允许

- HTTP；
- LLM；
- MCP 网络；
- JSON；
- immutable Snapshot 分析；
- 可证明线程安全的纯算法。

## 7.3 强制规则

禁止：

```java
future.join();
httpClient.send(...);
Thread.sleep(...);
```

出现在服务端 Tick 路径。

所有异步结果：

```java
server.execute(() -> applyResult(...));
```

回到服务器线程。

异步线程不得持有：

```text
World
ServerWorld
Entity
BlockEntity
Inventory
```

对象引用。

---

# 8. AgentBody 接口

建议最小接口：

```java
public interface AgentBody {
    UUID agentId();
    UUID ownerId();

    BodyCapabilities capabilities();
    AgentPhysicalState snapshotState();

    MoveHandle moveTo(TargetPosition target, MoveOptions options);
    void stopMoving();

    InteractionResult attack(EntityRef target);
    InteractionResult useItem(HandRef hand);
    InteractionResult interact(BlockRef target);

    InventoryView inventory();

    void lookAt(TargetRef target);
    void emote(EmoteType type);

    boolean alive();
}
```

上层不得：

```java
if (body instanceof CompanionEntity)
```

实现业务逻辑。

如不同 Body 具有不同能力，使用：

```text
BodyCapabilities
```

声明。

---

# 9. AvatarBody

默认基于：

```text
PathAwareEntity
```

需要实现：

- 生命周期；
- Owner UUID；
- Home；
- NBT；
- Inventory；
- Equipment；
- Navigation；
- Look；
- Combat；
- Emote；
- Client tracking。

客户端用玩家模型渲染。

必须显式显示 AI 身份，例如：

```text
[Squire] Alice
```

默认使用原创 Bundled Skin。

---

# 10. FakePlayerInteractionProxy

## 10.1 适用范围

只用于：

```text
break block
place block
use block
use item
modded interaction requiring PlayerEntity
```

## 10.2 权威状态

Avatar Inventory 为权威。

Proxy 执行动作前：

```text
prepare interaction state
```

动作后必须同步：

- ItemStack count；
- durability；
- returned stack；
- drop/result。

## 10.3 测试

必须 GameTest：

- 正常破坏；
- 工具耐久；
- 放置消耗；
- 物品使用；
- 交互失败；
- 区块卸载；
- Agent 死亡中断。

---

# 11. ServerPlayerBody

状态：

```text
EXPERIMENTAL
```

不属于 v1.0 必需。

必须隔离在：

```text
server/body/player/
```

不得为它修改上层 Task / Tool API。

---

# 12. Input Gateway

统一输入：

```java
record InputEnvelope(
    UUID senderId,
    InputSource source,
    UUID agentId,
    String rawText,
    long receivedTick
) {}
```

来源：

```text
CHAT
COMMAND
GUI
QUICK_ACTION
SYSTEM
```

在任何 NLP 之前先解析：

```text
sender
agent
role
permission
```

---

# 13. Sender Role

```text
PUBLIC
TRUSTED
OWNER
ADMIN
```

`PUBLIC`：

- 可以被 Agent 听见；
- 可作为环境上下文；
- 不可创建写操作 Task。

`TRUSTED`：

- 仅能调用 Owner 明确授权能力。

`OWNER`：

- Agent 个人控制。

`ADMIN`：

- 服务端管理能力。

角色不能由 LLM 修改。

---

# 14. FastPath

## 14.1 P0 必须支持

```text
跟着我
过来
待在这里
别动
保护我
停止
回来
回家
你在哪
状态
```

这些意图：

```text
不调用 LLM
```

直接生成 Typed Intent / Task。

## 14.2 FastPath 输出

不得直接改变 World。

仍进入：

```text
Permission
Task Compiler
Tool Gateway
```

## 14.3 高风险意图禁止 FastPath

例如：

```text
清空这里
杀光它们
复制建筑
把这一片填满
```

进入 Planner + Risk/Confirmation。

---

# 15. Context Resolver

自然指代解析优先级：

1. Player Raycast；
2. GUI selection；
3. Current Task Target；
4. 最近交互目标；
5. World Memory；
6. 附近唯一候选；
7. Ask Clarification。

破坏性操作存在歧义时：

```text
MUST ask clarification
```

---

# 16. PerceptionSnapshot

LLM 只能通过 Snapshot 理解世界。

禁止直接把 Chunk / NBT / World 引用给 LLM。

## 16.1 MINIMAL

```text
Agent status
Owner status
Current task
Immediate threats
```

## 16.2 NORMAL

增加：

```text
Inventory summary
Nearby entities
Points of interest
Time/weather
```

## 16.3 DETAILED

增加：

```text
Terrain summary
Region
Container detail
Path feasibility
Build context
```

## 16.4 限额

每个 Sensor 必须定义：

```text
radius
maxItems
priority
serializationBudget
```

禁止无上限扫描。

---

# 17. Cognitive Loop / Runtime Loop

## 17.1 Cognitive Loop

仅在以下情况运行：

- 新自然语言意图；
- 需要澄清；
- Task 失败并需要 Replan；
- 用户追问；
- 主动高级决策。

## 17.2 Runtime Loop

每 Tick / 分频执行：

- Navigation；
- Combat；
- Threat；
- Behavior；
- Task progress；
- Stuck detection。

Runtime Loop 不等待 LLM。

---

# 18. Task 模型

每个 Task 至少包含：

```text
taskId
agentId
requesterId
type
priority
state
createdTick
goal
dependencies
preconditions
successCondition
failureCondition
timeout
retryPolicy
interruptPolicy
resumePolicy
policySnapshot
```

---

# 19. Task 状态机

```text
CREATED
 ↓
PLANNING
 ↓
READY
 ↓
RUNNING
 ├─ WAITING
 ├─ BLOCKED
 ├─ RETRYING
 ├─ REPLANNING
 ├─ INTERRUPTED
 └─ PAUSED
 ↓
VERIFYING
 ↓
COMPLETED

FAILED
CANCELLED
```

只有 Goal Verifier 可以：

```text
VERIFYING → COMPLETED
```

---

# 20. TaskGraph

TaskGraph 支持：

- sequence；
- dependency；
- condition；
- bounded loop；
- parallel branch；
- join。

禁止无上限循环。

每个循环：

```text
maxIterations
timeout
```

必须至少有一个。

---

# 21. Priority / Interrupt

```text
P0 EMERGENCY
P1 SURVIVAL
P2 OWNER_URGENT
P3 USER_TASK
P4 FOLLOW
P5 ROUTINE
P6 SOCIAL
P7 IDLE
```

低优先级被抢占后：

- 保存可恢复状态；
- Runtime Handle 取消；
- 恢复时重新验证世界；
- 不直接继续旧坐标/旧实体引用。

---

# 22. Squire Agent Protocol

`common` 中定义统一 DTO：

```text
AgentRequest
AgentMessage
AgentResponse

ToolDescriptor
ToolCall
ToolResult

PlanSpec
TaskSpec
Observation
ProviderUsage
```

任何 Provider SDK 类型不得泄漏到：

```text
task/
tool/
server/
```

---

# 23. LLM Provider

接口：

```java
public interface LlmProvider {
    String id();
    CompletableFuture<AgentResponse> generate(AgentRequest request);
    ProviderCapabilities capabilities();
    CompletableFuture<ProviderHealth> healthCheck();
}
```

内置至少：

```text
OpenAICompatibleProvider
MockProvider
```

可选：

```text
AnthropicProvider
OllamaProvider
```

Core 不依赖特定 Provider SDK。

---

# 24. Function Calling

Provider 支持原生 Tool Calling：

```text
ToolDefinition
→ provider adapter
```

不支持时：

```text
Strict JSON Schema
```

解析失败：

```text
MALFORMED_MODEL_OUTPUT
```

处理：

1. 可重试 1 次；
2. 再失败返回降级；
3. 不得抛异常导致服务端崩溃。

---

# 25. Tool 分层

## 25.1 MODEL_PUBLIC

LLM 可看到的高层 Tool。

建议：

```text
squire.query.owner_status
squire.query.inventory
squire.query.nearby

squire.task.follow
squire.task.guard
squire.task.acquire_item
squire.task.craft_item
squire.task.build
squire.task.deposit_items
squire.task.return_home

squire.memory.recall
squire.memory.remember_candidate
```

## 25.2 PLANNER_INTERNAL

Planner/TaskCompiler 使用：

```text
minecraft.navigation.move_to
minecraft.inventory.transfer
minecraft.inventory.equip
minecraft.world.break_block
minecraft.world.place_block
minecraft.crafting.craft
```

## 25.3 RUNTIME_ONLY

LLM 永远不可见：

```text
swing_hand
attack_tick
break_progress
path_step
look_step
```

## 25.4 ADMIN_ONLY

仅管理命令/UI：

```text
policy update
mcp trust
provider endpoint
killswitch
```

---

# 26. ToolDefinition 单一真相源

Tool Schema 和 Java Validator 必须来自同一份定义。

推荐：

```java
ToolDefinition.builder("squire.task.acquire_item")
    .arg(ItemIdArg.required("item"))
    .arg(IntArg.range("count", 1, 4096))
    .permission(AgentPermission.ACQUIRE_ITEM)
    .risk(RiskLevel.LOW)
    .exposure(ToolExposure.MODEL_PUBLIC)
    .build();
```

自动生成：

- JSON Schema；
- runtime validation；
- docs metadata；
- Tool Inspector；
- MCP adapter schema。

不得分别维护两份参数规则。

---

# 27. Tool Gateway

唯一入口：

```text
Tool Lookup
 ↓
Schema Validation
 ↓
Exposure Check
 ↓
Sender Identity
 ↓
Permission
 ↓
Context Validation
 ↓
Preconditions
 ↓
Policy
 ↓
Risk
 ↓
Capability
 ↓
Quota / Budget
 ↓
Confirmation
 ↓
Execute
 ↓
Postcondition
 ↓
Audit
```

任何一步拒绝：

```text
Tool 不执行
```

---

# 28. ToolResult

状态：

```text
SUCCESS
PARTIAL
RUNNING
BLOCKED
FAILED
CANCELLED
```

结构：

```json
{
  "callId": "call_x",
  "status": "FAILED",
  "data": {},
  "error": {
    "code": "PATH_NOT_FOUND",
    "retryable": true,
    "message": "No reachable path."
  }
}
```

---

# 29. Error Code

必须统一，至少包括：

```text
INVALID_ARGUMENT
TOOL_NOT_FOUND
TOOL_NOT_VISIBLE

PERMISSION_DENIED
POLICY_DENIED
CAPABILITY_REQUIRED
CAPABILITY_SCOPE_VIOLATION
CONFIRMATION_REQUIRED

ENTITY_NOT_FOUND
BLOCK_NOT_FOUND
ITEM_NOT_FOUND
TARGET_CHANGED
WORLD_CHANGED

PATH_NOT_FOUND
UNREACHABLE
AGENT_STUCK

INVENTORY_FULL
INSUFFICIENT_ITEM
NO_RECIPE
RESOURCE_EXHAUSTED

TIMEOUT
RATE_LIMITED
BUDGET_EXCEEDED
LOOP_DETECTED

PRECONDITION_FAILED
POSTCONDITION_FAILED

COMMAND_DENIED
COMMAND_PARSE_FAILED
COMMAND_FAILED

MCP_UNTRUSTED
MCP_TIMEOUT
MCP_PROTOCOL_ERROR
MCP_RESULT_INVALID

MALFORMED_MODEL_OUTPUT
INTERNAL_ERROR
```

禁止用自然语言字符串作为程序分支的唯一依据。

---

# 30. Capability

Permission 回答：

```text
“是否有资格？”
```

Capability 回答：

```text
“这一次能改什么范围？”
```

高风险操作必须使用 scoped Capability。

字段：

```text
capabilityId
ownerId
taskId
type
allowedTools
dimension
region
maxImpact
issuedAt
expiresAt
nonce
```

Capability 必须：

- 短生命周期；
- 不可跨 Task 使用；
- 不可重放；
- 不可越界；
- 可撤销。

---

# 31. Tool Loop Protection

每个 Planning Turn：

```text
maxToolCalls
maxSameToolCalls
maxEquivalentCalls
maxReplans
```

如果：

```text
同 Tool + 同参数 + 世界状态未变化
```

重复超过阈值：

```text
LOOP_DETECTED
```

---

# 32. Economy Mode

## SURVIVAL

默认。

AI 只能使用真实物资。

## ASSISTED

允许服务器配置的便利能力。

## OPERATOR

允许结构化 Command / WorldEdit。

Mode 不改变 Admin 权限，只改变 Agent 可用工具集合。

---

# 33. Core Skill：Follow / Move

必须支持：

```text
move_to
follow
stay
return_home
look_at
```

Navigation 负责：

- 平地；
- 台阶；
- 跳跃；
- 门；
- 安全坠落；
- 基础水域；
- Repath。

M1 不要求实现：

- Elytra；
- 船；
- 复杂跑酷；
- 大规模挖掘寻路。

---

# 34. Stuck Detection

如果 N Tick 内：

```text
expected movement > threshold
actual displacement < threshold
```

进入 STUCK。

Recovery：

1. local repath；
2. jump；
3. backoff；
4. alternate nearby node；
5. full repath；
6. fail/replan。

全部有次数上限。

---

# 35. Inventory / Equipment

支持：

```text
inspect
equip
unequip
pickup
drop
transfer
deposit
withdraw
sort
```

Inventory 状态以 Avatar 为权威。

任何 `give` 完成条件必须检查真实 Inventory Delta。

---

# 36. Crafting

必须使用：

```text
RecipeManager
```

获取真实配方。

模型不得自行推断配方作为事实。

流程：

```text
Resolve Recipe
 ↓
Check Materials
 ↓
Create Missing Material Tasks
 ↓
Craft
 ↓
Verify Output
```

---

# 37. Gather

v1 P0：

- nearby wood；
- stone；
- common ore；
- dropped item。

所有 Gather Task 有：

```text
searchRadius
maxRuntime
maxBlocks
inventoryLimit
```

不允许无限探索。

---

# 38. Guard / Combat

玩家：

```text
保护我
```

创建长期 `GuardOwnerTask`。

CombatController 本地决定：

- Threat target；
- weapon；
- attack cooldown；
- shield；
- retreat；
- distance。

LLM 不参与战斗 Tick。

---

# 39. Threat Evaluator

至少考虑：

```text
hostile targeting owner
distance
creeper
projectile
owner health
agent health
boss
fire/lava
```

结果：

```text
ThreatScore
```

只供 Runtime 决策。

---

# 40. Healing

Survival：

优先真实使用：

```text
Splash Healing
Regeneration
Golden Apple
Food（适用时）
```

必须检查：

- 是否拥有；
- 是否能安全使用；
- 是否真实生效。

Assisted 模式可以使用结构化 Effect Tool。

---

# 41. World Memory

支持：

```text
base
home
warehouse
ore_storage
farm
mine
portal
danger_region
custom landmark
```

玩家：

```text
“这里以后是矿物仓库。”
```

创建 World Memory。

后续：

```text
“把矿放回仓库。”
```

Context Resolver 使用该 Memory。

---

# 42. Memory 类型

```text
Working
Episodic
Semantic
World
```

Policy 不属于 Memory。

长期 Memory 写入必须经过：

```text
candidate
→ category
→ validation
→ dedup
→ persist
```

---

# 43. Memory 隐私

默认：

```text
persistConversation = false
```

提供：

```text
/squire memory list
/squire memory forget
/squire memory clear
```

不得把其他玩家私聊写入长期记忆。

---

# 44. Build

小型 Build：

```text
Resolve Region
 ↓
BuildPlan
 ↓
Estimate Material
 ↓
Preview if needed
 ↓
Acquire
 ↓
Place
 ↓
Verify
```

v1 支持：

```text
procedural blueprint
NBT structure import（可作为 P1）
```

大规模操作进入 WorldEditor。

---

# 45. WorldEditor

仅在允许 Mode / Permission 下可用。

必须：

- Region bound；
- impact estimate；
- tick budget；
- ProtectionAdapter；
- Confirmation；
- Capability；
- Undo；
- Audit。

大规模编辑必须分帧执行，禁止单 Tick 大量写方块。

---

# 46. Structured Command

模型只看到类型化 Tool，例如：

```text
minecraft.command.give
minecraft.command.teleport
minecraft.command.effect
minecraft.command.fill
minecraft.command.setblock
minecraft.command.clone
minecraft.command.summon_safe
```

Java 将参数编译为命令。

流程：

```text
Typed Command Intent
 ↓
Validator
 ↓
Permission
 ↓
Capability
 ↓
CommandCompiler
 ↓
Brigadier parse
 ↓
Execute
 ↓
Verify
```

官方 Core 无 `raw_command`。

---

# 47. Command 参数安全

任何可能进入命令的参数必须使用类型对象：

```text
PlayerRef
ItemId
BlockId
EntityId
BoundedPosition
BoundedRegion
PositiveInt
SafeEffectId
```

不接受：

```text
@a
@e
任意 selector string
任意 SNBT
任意 execute string
```

除非未来独立安全扩展明确实现。

---

# 48. Execution Ladder

| Level | Backend | 使用场景 |
|---|---|---|
| L0 | FastPath | 高频明确意图 |
| L1 | Native Runtime | 正常玩家式行为 |
| L2 | Structured Command | 一次性受控指令 |
| L3 | WorldEditor | 大规模世界编辑 |
| L4 | AutomationGraph | 长期条件/定时逻辑 |
| L5 | Materialized CBP | 玩家明确需要命令方块产物 |

选择依据：

```text
semantics
policy
risk
persistence requirement
player intent
```

不是“命令长度”。

---

# 49. AutomationGraph

数据模型：

```text
AutomationId
OwnerId
AgentId
Trigger
Conditions
Nodes
Edges
State
TTL
PolicySnapshot
```

Trigger：

```text
MANUAL
TIME
INTERVAL
OWNER_ONLINE
PLAYER_ENTER_REGION
TASK_EVENT
```

Node：

```text
ToolCall
CreateTask
Branch
Wait
Notify
```

长期 Automation 必须支持：

```text
list
inspect
pause
resume
remove
restart recovery
audit
```

---

# 50. Materialized Command Block Project

只有玩家明确要求真实命令方块时使用。

必须：

1. 生成 CBP Spec；
2. 使用结构化 Command Intent；
3. 选择 Workspace；
4. 计算影响；
5. Preview；
6. Owner Confirmation；
7. 生成 Capability；
8. 保存 Undo；
9. 放置；
10. 测试；
11. Registry 登记。

不得在未知地下区域静默建造。

---

# 51. CBP Workspace

推荐命令：

```text
/squire workspace set <from> <to>
/squire workspace clear
```

默认：

```text
workspaceOnly = true
```

命令方块必须位于授权 Workspace。

---

# 52. Undo Journal

保存：

```text
operationId
taskId
blockPos
oldBlockState
oldBlockEntityNbt
newBlockState
timestamp
```

大操作执行前先计算预估 Undo 大小。

默认：

```text
高风险世界编辑如果无法生成 Undo → 拒绝执行
```

服务器管理员可以通过独立 Policy 改变。

---

# 53. MCP 模块

默认：

```text
disabled
```

## 53.1 Client Bridge

用途：

```text
外部 Tool discovery / invocation
```

## 53.2 Server Gateway

Experimental。

默认：

```text
disabled
localhost only
read-only
```

不得作为 v1.0 必需功能阻塞发布。

---

# 54. MCP Tool Import

流程：

```text
Connect
 ↓
Protocol Negotiation
 ↓
tools/list
 ↓
Schema Sanitize
 ↓
Namespace
 ↓
Trust Classification
 ↓
Policy
 ↓
Tool Registry
```

检查：

- name；
- duplicate；
- namespace collision；
- schema depth；
- property count；
- description size；
- payload size；
- max tools/server；
- output schema。

---

# 55. MCP Trust

```text
INTERNAL
TRUSTED_MOD
ADMIN_APPROVED_LOCAL
ADMIN_APPROVED_REMOTE
UNTRUSTED
BLOCKED
```

默认：

```text
UNTRUSTED
```

MCP annotation：

```text
readOnly
destructive
idempotent
```

只作为 hint。

不能作为权限事实。

---

# 56. MCP Transport

允许：

```text
STDIO
Streamable HTTP
```

### STDIO

只有 Admin 配置。

普通玩家和 LLM 不得控制：

```text
executable
args
env
```

### HTTP

必须有：

```text
scheme allowlist
host allowlist
timeout
maxBody
credential isolation
redirect policy
```

---

# 57. MCP Prompt Injection

MCP Tool Result 视为：

```text
UNTRUSTED_EXTERNAL_DATA
```

不能修改：

- System Policy；
- Owner；
- Tool Visibility；
- Capability；
- Permission；
- MCP Trust。

任何后续 Tool 重新独立校验。

---

# 58. Java Tool API

第三方 Mod：

```java
public interface SquireToolProvider {
    void registerTools(ToolRegistrar registrar);
}
```

新增 Tool 必须同时提供：

- stable id；
- schema；
- validator；
- permission；
- risk；
- exposure；
- precondition；
- postcondition；
- unit test；
- GameTest（若写 World）；
- docs。

---

# 59. Sensor API

```java
public interface SquireSensor {
    SensorDefinition definition();
    SensorResult observe(SensorContext context);
}
```

Sensor 默认：

```text
read-only
bounded
budgeted
```

---

# 60. Protection Adapter

接口：

```java
public interface ProtectionAdapter {
    PermissionDecision canBreak(...);
    PermissionDecision canPlace(...);
    PermissionDecision canInteract(...);
    PermissionDecision canEditRegion(...);
}
```

Core 至少实现：

```text
Vanilla spawn protection
```

第三方保护模组使用 Adapter。

---

# 61. Permission Model

能力节点示例：

```text
squire.use
squire.task.follow
squire.task.guard
squire.task.acquire
squire.task.build

squire.world.break
squire.world.place
squire.world.edit

squire.command.give
squire.command.effect
squire.command.teleport
squire.command.worldedit

squire.automation

squire.mcp.manage
squire.admin
```

Role 只是默认节点集合。

Tool Gateway 检查具体 Permission。

---

# 62. Prompt Injection 防御

必须有四层：

## 62.1 Channel Isolation

非授权玩家聊天不作为 Instruction。

## 62.2 Prompt Boundary

Prompt 明确标记：

```text
TRUSTED_USER_INPUT
UNTRUSTED_CHAT_OBSERVATION
UNTRUSTED_TOOL_RESULT
MEMORY
WORLD_STATE
```

## 62.3 Tool Gateway

决定性防线。

模型即使输出越权 Tool，也执行失败。

## 62.4 Audit

记录异常模式。

注入关键词检测只能用于：

```text
audit / rate limit / warning
```

不能替代权限。

---

# 63. Killswitch

```text
/squire admin killswitch on
```

1 Tick 内：

- 禁止新的 World Write；
- 拒绝新的 Command；
- 暂停 Automation；
- 取消未提交的 MCP write；
- Agent 进入安全 Idle/Follow Stop。

必须 GameTest / Integration Test。

---

# 64. Secret 管理

Secret 只可来自：

```text
environment
config/squire/secrets.*
```

不得进入：

- world；
- NBT；
- Packet；
- Audit；
- Replay；
- Debug；
- Crash report（尽量 redact）。

---

# 65. Endpoint / SSRF

自定义：

```text
LLM base URL
MCP URL
Skin URL
```

只允许 Admin 配置。

配置策略：

```text
allowedSchemes
allowedHosts
allowPrivateNetwork
followRedirects
```

Remote MCP 默认关闭。

---

# 66. Budget / Quota

至少：

```text
llmCallsPerHour
llmTokensPerDay
providerCostPerDay
toolCallsPerMinute
mcpCallsPerMinute
blocksChangedPerHour
worldScanBlocksPerTick
maxQueuedTasks
maxTaskRuntime
maxRetries
maxReplans
```

服务器 Cost Breaker 触发后：

```text
LLM disabled
FastPath enabled
deterministic tasks continue
```

---

# 67. Feedback / UX 真值规则

## LLM 允许输出

```text
对话
简短说明
澄清问题
```

## Runtime 输出

```text
task progress
task failure
task completion
permission denial
confirmation request
world edit impact
```

完成信息必须由 Runtime 先生成。

---

# 68. Proactive Behavior

级别：

```text
OFF
LOW
NORMAL
HIGH
```

主动提醒优先使用本地模板。

不得因为：

```text
天黑
低血
工具耐久低
```

频繁调用 LLM。

---

# 69. UI

Companion：

```text
Overview
Chat
Tasks
Inventory
Equipment
Memory
Home
Automation
Permissions
Privacy
```

Admin：

```text
Agents
Policy
Tools
MCP
Audit
Performance
Cost
Killswitch
```

---

# 70. `/squire` 命令

```text
/squire
├── say <text>
├── follow
├── stay
├── guard
├── stop
├── summon
├── dismiss
├── home
│   ├── set
│   └── return
├── task
│   ├── list
│   ├── pause <id>
│   ├── resume <id>
│   └── cancel <id>
├── plan <text>
├── inventory
├── memory
│   ├── list
│   ├── forget
│   └── clear
├── automation
│   ├── list
│   ├── inspect
│   ├── pause
│   ├── resume
│   └── remove
├── cbp
│   ├── list
│   ├── inspect
│   ├── disable
│   └── remove
├── workspace
│   ├── set
│   └── clear
├── undo
├── privacy
├── stats
├── tools
└── admin
    ├── killswitch
    ├── audit
    ├── agents
    ├── quota
    └── reload
```

---

# 71. Persistence

## 71.1 Entity NBT

保存：

- Agent ID；
- Owner；
- inventory；
- equipment；
- physical state；
- home reference。

## 71.2 PersistentState

保存：

- Agent Registry；
- Task metadata；
- Automation；
- permissions；
- World Memory。

## 71.3 文件

```text
audit/
memory/
undo/
replay/
```

所有格式具有：

```text
schemaVersion
```

---

# 72. Restart Recovery

持久化中的：

```text
RUNNING
```

启动时改为：

```text
RECOVERING
```

然后：

```text
validate world
validate target
validate policy
rebuild runtime handle
```

结果：

```text
RESUME
REPLAN
FAIL
```

网络 Future / MoveHandle / Entity Ref 不可直接持久化。

---

# 73. Networking 1.20.1

版本相关网络实现放入：

```text
network/v1_20_1/
```

Packet：

```text
handshake
agent_state
task_delta
confirmation
blueprint_preview
debug_path
client_action
```

每个 C2S：

- size limit；
- frequency limit；
- sender validation；
- permission recheck。

---

# 74. 中文 Item Alias

原创维护：

```text
data/squire/aliases/zh_cn.json
```

解析顺序：

1. Registry ID；
2. exact alias；
3. quantity normalization；
4. typo/fuzzy；
5. LLM candidate；
6. Registry validation。

禁止打包复制完整 Mojang 语言资源。

---

# 75. Observability

Metrics：

```text
fastpath_hit_ratio

llm_requests_total
llm_latency
llm_tokens
llm_failures

tool_calls
tool_rejected
tool_duration

tasks_total
task_duration

mcp_calls
mcp_failures

tick_budget_exceeded
worldedit_blocks
undo_bytes
```

---

# 76. Deterministic Replay

Debug Replay 保存：

```text
InputEnvelope
PerceptionSnapshot
Relevant Memory
Visible Tools
Structured LLM Response
Tool Gateway decisions
ToolResult
Task transitions
```

必须 redact：

- API Key；
- MCP credential；
-敏感配置。

Replay 用于：

```text
bug reproduction
unit/integration fixture generation
```

---

# 77. Runtime Tick Budget

Squire Scheduler 使用全局时间预算。

优先级：

```text
Emergency Combat
Movement
Threat
Task
Perception
WorldEdit
Memory Flush
Debug
```

低优先级任务超预算：

```text
yield next tick
```

不得为了完成大任务突破 Tick Budget。

---

# 78. 性能目标

M0/M1 实测后冻结具体硬阈值。

开发阶段目标：

```text
FastPath internal P95 < 50ms
LLM/MCP main-thread blocking = 0ms
WorldEdit always budgeted across ticks
```

初始整体目标：

```text
1–4 Agent 时 Squire 主线程 P99 <= 2ms/tick
```

若实际基准显示不可行，必须 ADR 调整，而不是隐藏测试失败。

---

# 79. 测试策略

## 79.1 Unit Test

`common`：

- Tool Schema；
- Validator；
- Policy；
- Capability；
- TaskGraph；
- Alias；
- Quota；
- Command Compiler；
- MCP Import。

安全相关逻辑目标：

```text
>= 90% line/branch coverage
```

其他 common：

```text
>= 80%
```

## 79.2 Integration Test

- Request Pipeline；
- FastPath；
- MockProvider；
- Tool Gateway；
- Fallback；
- Mock MCP。

## 79.3 GameTest

- Avatar lifecycle；
- Follow；
- Guard；
- Heal；
- Inventory；
- Break/place；
- Craft；
- WorldEdit；
- Undo；
- Persistence；
- Automation；
- CBP。

## 79.4 Security Test

每次 CI 强制运行。

---

# 80. MockProvider

CI 不得依赖真实 LLM。

MockProvider 必须支持脚本化：

```text
valid tool call
invalid tool
malformed JSON
timeout
hallucinated item
repeated tool loop
prompt-injected response
```

---

# 81. Mock MCP

提供：

```text
SafeMcp
MaliciousMcp
SlowMcp
InvalidSchemaMcp
HugePayloadMcp
InjectionMcp
```

---

# 82. Security Test Case

至少：

```text
Non-owner instruction
Owner replacement prompt
Permission escalation
Selector injection
Command separator injection
Coordinate overflow
Capability replay
Capability region escape
Forged client confirmation
Task ID guessing
MCP annotation spoofing
MCP result prompt injection
Oversized MCP schema
LLM malformed function
LLM unavailable
```

权限类结果：

```text
100% pass
```

---

# 83. Fuzz

至少对：

```text
CommandCompiler
Tool JSON parser
Alias parser
Capability region validation
MCP schema importer
```

执行 Property/Fuzz Test。

任何输入不得：

```text
绕过权限
崩溃服务器
执行未定义行为
```

---

# 84. M0 — Technical Verification

M0 不实现正式玩法，只验证关键技术风险。

## M0-01 Project Baseline

交付：

- Gradle project；
- Fabric runs；
- client/server run config；
- JUnit；
- GameTest；
- CI build。

完成条件：

```text
./gradlew build
```

通过。

## M0-02 Avatar Spike

验证：

- spawn；
- renderer；
- movement；
- save/load。

## M0-03 FakePlayer Proxy Spike

验证：

- break；
- place；
- durability；
- inventory sync。

## M0-04 Chat Spike

验证 1.20.1 Chat event。

## M0-05 Command Spike

验证：

- typed command compiler；
- Brigadier parse；
- execution result。

## M0-06 Threading Spike

验证：

```text
async provider
→ server.execute
→ world change
```

无主线程 blocking。

## M0-07 MCP Spike

若 Java SDK 与项目依赖无冲突：

- tool list；
- stdio；
- streamable HTTP；
- timeout。

若冲突：

- MCP 继续保持独立模块；
- 使用自研最小 protocol adapter；
- 不允许阻塞 Core。

## M0 Exit

必须提交：

```text
docs/version-matrix/mc-1.20.1.md
docs/adr/
```

并记录所有 Spike 结论。

---

# 85. M1 — Playable Companion

目标：

> 一个不依赖高级 Agent 规划也已经可玩的伙伴。

实现：

- AvatarBody；
- Owner；
- Home；
- Follow；
- Stay；
- Stop；
- Look；
- basic emote；
- basic Inventory；
- FastPath；
- Chat；
- Provider abstraction；
- MockProvider；
- persistence；
- client renderer。

M1 DoD：

- [ ] `/squire summon`
- [ ] `/squire follow`
- [ ] `/squire stay`
- [ ] `/squire stop`
- [ ] `/squire home set`
- [ ] FastPath 不访问 LLM
- [ ] LLM 不可用时上述能力正常
- [ ] 重启后 Agent 状态恢复
- [ ] GameTest 通过
- [ ] Dedicated Server 启动通过

---

# 86. M2 — Agent Runtime

实现：

- PerceptionSnapshot；
- Context Resolver；
- Agent Protocol；
- Function Calling；
- TaskGraph；
- TaskScheduler；
- ToolDefinition；
- ToolGateway；
- ToolResult；
- Acquire；
- Craft；
- Inventory transfer；
- Guard；
- Combat；
- Healing；
- Goal Verification；
- Retry/Replan；
- Tool Loop protection。

M2 DoD：

- [ ] “给我 32 个火把” Survival 端到端
- [ ] “帮我砍 20 个木头”
- [ ] “做一把铁镐”
- [ ] “保护我”
- [ ] “我快死了”
- [ ] 模型说“完成”不会改变 Task 状态
- [ ] malformed Tool Call 不执行
- [ ] 所有 Tool 经过 Gateway
- [ ] Security integration tests 通过

---

# 87. M3 — Safety / Commands / World Edit

实现：

- Permission Nodes；
- Policy；
- Capability；
- Quota；
- Audit；
- Killswitch；
- ProtectionAdapter；
- Structured Commands；
- CommandCompiler；
- WorldEditor；
- Preview；
- Confirmation；
- Undo。

M3 DoD：

- [ ] 官方 Core 无 Raw Command
- [ ] WorldEdit 越 Capability 边界 100% 拒绝
- [ ] 非 Owner 无法产生写操作
- [ ] Forged client confirmation 失败
- [ ] `/squire admin killswitch on` 1 tick 生效
- [ ] Undo 精确恢复 BlockState + BlockEntity NBT
- [ ] Security tests 100%

---

# 88. M4 — Extension Platform

实现：

- `squire-api`；
- Tool Provider；
- Sensor Provider；
- Task Provider；
- Tool Inspector；
- MCP Client Bridge；
- MCP Trust Store；
- MCP import sanitize；
- Mock MCP。

M4 DoD：

- [ ] 第三方 Example Mod 可注册 Tool
- [ ] 不修改 Core 即可调用
- [ ] 未信任 MCP write Tool 不可执行
- [ ] MCP result injection 不改变权限
- [ ] MCP disconnect 不崩服
- [ ] MCP timeout 不阻塞主线程

---

# 89. M5 — Automation / CBP

实现：

- AutomationGraph；
- Trigger；
- Condition；
- Task/Tool node；
- persistence；
- pause/resume/remove；
- CBP Spec；
- Workspace；
- Materializer；
- CBP Registry；
- CBP Undo。

M5 DoD：

- [ ] “每天晚上在基地开灯”不放隐藏命令方块
- [ ] Automation 重启恢复
- [ ] 玩家明确要求 CBP 时可物化
- [ ] CBP 未确认不执行
- [ ] CBP 只在 Workspace
- [ ] CBP 可完整删除与 Undo

---

# 90. M6 — Release Candidate

实现：

- UI polishing；
- i18n；
- configuration docs；
- privacy notice；
- metrics；
- replay；
- performance；
- compatibility；
- migration tests；
- release automation。

M6 DoD：

- [ ] README
- [ ] LICENSE
- [ ] SECURITY.md
- [ ] CONTRIBUTING.md
- [ ] CODE_OF_CONDUCT.md
- [ ] CHANGELOG.md
- [ ] Server Guide
- [ ] Player Guide
- [ ] Tool API Guide
- [ ] MCP Guide
- [ ] Security Model
- [ ] CI 全绿
- [ ] 无 P0/P1 已知安全 Bug

---

# 91. v1.0 基准任务

每个场景至少重复多次。

```text
B01 跟着我
B02 给我 32 个火把
B03 帮我砍 20 个橡木
B04 做一把铁镐
B05 保护我
B06 我快死了
B07 把选定区域铺成石头
B08 回家
B09 每天晚上在基地开灯
B10 做一个真实命令方块昼夜控制器
B11 上次矿洞在哪
B12 使用第三方 Tool 查询机器状态
```

任务成功定义：

> **最终游戏世界状态满足 Goal Verifier，而不是 LLM 回复正确。**

---

# 92. v1.0 Definition of Done

## Agent

- [ ] 生命周期
- [ ] 移动
- [ ] Home
- [ ] Inventory
- [ ] Equipment
- [ ] Guard
- [ ] Combat
- [ ] Heal
- [ ] Persistence

## AI

- [ ] FastPath
- [ ] Provider abstraction
- [ ] Function Calling
- [ ] MockProvider
- [ ] Fallback
- [ ] 0 main-thread network blocking

## Task

- [ ] TaskGraph
- [ ] Pause
- [ ] Resume
- [ ] Cancel
- [ ] Retry
- [ ] Replan
- [ ] Interrupt
- [ ] Goal Verification
- [ ] Restart Recovery

## Tool

- [ ] Single Source ToolDefinition
- [ ] Tool Gateway
- [ ] Schema
- [ ] Permission
- [ ] Policy
- [ ] Preconditions
- [ ] Risk
- [ ] Capability
- [ ] Quota
- [ ] Postconditions
- [ ] Audit

## Command / World

- [ ] Structured Command only
- [ ] WorldEditor
- [ ] Confirmation
- [ ] Undo
- [ ] ProtectionAdapter
- [ ] Killswitch

## MCP

- [ ] Optional module
- [ ] Default OFF
- [ ] Tool import validation
- [ ] Trust
- [ ] Timeout
- [ ] Payload limit
- [ ] Prompt injection tests

## Automation

- [ ] AutomationGraph
- [ ] Persistence
- [ ] Pause/Resume
- [ ] Materialized CBP opt-in

## Engineering

- [ ] Unit tests
- [ ] GameTest
- [ ] Security tests
- [ ] Migration tests
- [ ] Compatibility smoke test
- [ ] Secret scan
- [ ] License check
- [ ] Documentation

---

# 93. 默认配置

```json5
{
  schemaVersion: 3,

  agent: {
    maxPerPlayer: 1,
    deathMode: "RESPAWN",
    offlineBehavior: "RETURN_HOME",
    initiative: "NORMAL"
  },

  ai: {
    enabled: true,
    provider: "openai-compatible",
    model: "user-configured",
    timeoutSeconds: 20,
    maxReplans: 2
  },

  fastPath: {
    enabled: true
  },

  policy: {
    mode: "SURVIVAL",
    destructiveConfirmation: true
  },

  tools: {
    contextualExposure: true,
    maxModelVisibleTools: 24,
    maxToolCallsPerTurn: 8
  },

  worldEdit: {
    enabled: false,
    requireUndo: true,
    tickBudgetMicros: 1000
  },

  automation: {
    enabled: false,
    maxPerOwner: 16
  },

  commandBlocks: {
    materializationEnabled: false,
    workspaceOnly: true
  },

  mcp: {
    enabled: false,
    remoteEnabled: false,
    serverGatewayEnabled: false,
    defaultTrust: "UNTRUSTED"
  },

  privacy: {
    persistConversation: false,
    remoteRequiresNotice: true
  },

  audit: {
    enabled: true,
    retentionDays: 30
  }
}
```

---

# 94. 默认安全状态

首次安装必须：

```text
Economy Mode                 SURVIVAL
WorldEdit                    OFF
Structured Admin Commands    OFF
Raw Model Command            不存在
Automation                   OFF
Materialized CBP             OFF
MCP Client                   OFF
Remote MCP                   OFF
MCP Server Gateway           OFF
Telemetry                    OFF
Audit                        ON
Destructive Confirmation     ON
```

---

# 95. CI

至少：

```text
build
unit-test
gametest
integration-test
security-test
fuzz-smoke
migration-test
compat-startup
lint
license-check
secret-scan
```

CI 中禁止访问真实付费 LLM。

Nightly 可以单独运行真实模型 Eval。

---

# 96. Coding 规范

## 96.1 不允许业务代码直接依赖 Gson JsonObject

Tool / Task 参数进入 Core 后应转换成 Typed DTO。

## 96.2 不允许使用 String 作为 Registry ID 的长期内部表示

入口解析后使用：

```text
Identifier / 自定义强类型 ID
```

## 96.3 不允许使用 Entity 实例作为持久 Task target

使用：

```text
UUID / EntityRef
```

执行前重新 Resolve。

## 96.4 不允许 catch Exception 后静默忽略

必须：

```text
log
structured error
task decision
```

## 96.5 不允许无限重试

所有重试具有：

```text
maxRetries
backoff
```

## 96.6 不允许大范围循环写 World

必须经过：

```text
WorldEditor / Tick Budget
```

---

# 97. 新增依赖规则

新增依赖前必须检查：

- 是否真的需要；
- Java 17；
- Minecraft 1.20.1；
- Fabric 环境；
- license；
- transitive dependency；
- client/server side；
- relocation/shading；
- 与 Minecraft 自带库冲突。

关键依赖变更写 ADR。

---

# 98. 开源文件

仓库必须包含：

```text
README.md
LICENSE
SECURITY.md
CONTRIBUTING.md
CODE_OF_CONDUCT.md
CHANGELOG.md
```

文档：

```text
docs/
├── IMPLEMENTATION_SPEC.md
├── adr/
├── architecture/
├── api/
├── mcp/
├── security/
├── player-guide/
├── server-guide/
└── version-matrix/
```

---

# 99. 版本策略

Artifact：

```text
squire-x.y.z+mc1.20.1.jar
```

Semantic Versioning：

- MAJOR：API / config / save breaking；
- MINOR：兼容新功能；
- PATCH：修复。

持久化 Breaking Change 必须提供 Migration。

---

# 100. 最终不可违反的规则

1. LLM 不控制 Tick。
2. LLM 不拥有权限。
3. LLM 不执行 Raw Command。
4. Task 完成状态不由 LLM 决定。
5. 所有 Tool 经过 Tool Gateway。
6. 未授权 Tool 不暴露给模型。
7. MCP Server 默认不可信。
8. MCP Annotation 不是权限事实。
9. 高风险写操作使用 scoped Capability。
10. 网络 I/O 不阻塞 Minecraft 主线程。
11. 异步线程不直接操作 World / Entity。
12. FastPath 必须在无 LLM 时可用。
13. Runtime Core 必须在无 MCP 时可用。
14. 复杂任务不等于作弊。
15. 复杂任务不等于命令方块。
16. 长期自动化默认使用 AutomationGraph。
17. 真实命令方块只作为明确的玩家产物。
18. 世界大修改必须 Preview / Confirmation / Undo。
19. 客户端永远不作为权限事实源。
20. Policy 不允许通过 Memory 修改。
21. Tool / Task 重试必须有上限。
22. 所有长期任务必须可取消。
23. 所有 World Write 必须可审计。
24. 所有安全修复必须增加回归测试。
25. 行为稳定性优先于更复杂的聊天人格。

---

# Appendix A — 首批 Tool Catalog

## Query

```text
squire.query.owner_status
squire.query.agent_status
squire.query.inventory
squire.query.nearby_entities
squire.query.nearby_poi
squire.query.landmark
```

## Task

```text
squire.task.follow
squire.task.stay
squire.task.guard
squire.task.return_home
squire.task.acquire_item
squire.task.craft_item
squire.task.deposit_items
squire.task.build
```

## Internal Navigation

```text
minecraft.navigation.move_to
minecraft.navigation.stop
minecraft.navigation.look_at
```

## Internal Inventory

```text
minecraft.inventory.inspect
minecraft.inventory.equip
minecraft.inventory.transfer
minecraft.inventory.pickup
minecraft.inventory.deposit
minecraft.inventory.withdraw
```

## Internal World

```text
minecraft.world.break_block
minecraft.world.place_block
minecraft.world.interact
```

## Structured Command

```text
minecraft.command.give
minecraft.command.effect
minecraft.command.teleport
minecraft.command.setblock
minecraft.command.fill
minecraft.command.clone
minecraft.command.summon_safe
```

---

# Appendix B — 首批 ADR 文件

```text
ADR-001-server-authoritative.md
ADR-002-agent-body.md
ADR-003-avatar-body-default.md
ADR-004-fakeplayer-interaction-proxy.md
ADR-005-fastpath-before-llm.md
ADR-006-low-frequency-cognitive-loop.md
ADR-007-tool-gateway.md
ADR-008-structured-command-only.md
ADR-009-automation-graph.md
ADR-010-command-block-product-artifact.md
ADR-011-provider-independent-protocol.md
ADR-012-mcp-trust.md
ADR-013-runtime-owned-status.md
ADR-014-policy-memory-separation.md
```

---

# Appendix C — 开发完成链路

```text
Input
→ Identity
→ Permission
→ FastPath / LLM
→ Typed Intent
→ TaskCompiler
→ TaskGraph
→ ToolGateway
→ Runtime
→ Minecraft
→ Observation
→ Goal Verification
→ Runtime Status
```

任何实现如果绕过这条主链路，应视为架构问题并先修改 ADR。

---

# Appendix D — 技术参考

实现时以 Minecraft 1.20.1 对应版本源码/Javadoc为准。

Fabric：

- https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/
- https://maven.fabricmc.net/docs/fabric-api-0.86.0%2B1.20.1/net/fabricmc/fabric/api/entity/FakePlayer.html
- https://docs.fabricmc.net/develop/commands/basics

MCP：

- https://modelcontextprotocol.io/specification/2025-11-25
- https://modelcontextprotocol.io/specification/2025-11-25/server/tools
- https://java.sdk.modelcontextprotocol.io/latest/

---

**End of Specification**
