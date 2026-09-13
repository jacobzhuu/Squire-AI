# 上线前审查修复记录（2026-09-13）

审查对象：工作区当前版本，Minecraft 1.20.1、Fabric Loader 0.19.3、Fabric API 0.92.11+1.20.1。预计约 5 人同时在线。用户确认服务器模组清单与 `E:\PCL-MC\PCL-MC\.minecraft\versions\1.20.1-Fabric 0.19.3\mods` 相同。

清单中未发现领地或锁箱模组；有 Sophisticated Backpacks 3.23.4.5.110 与 Sophisticated Core 1.2.7.15.166。目录里有 12 个 `environment=client` 的模组，例如 Iris、Sodium、Continuity、Inventory Profiles Next、Xaero 客户端地图和实体外观模组。这些应保留在客户端分发包，服务器清单只装服务端适用模组。该目录还含多个非 `.jar` 后缀的禁用/备份文件；它们不是 Fabric Loader 的活动模组。

**结论：本报告列出的六项代码缺陷已修复，Sophisticated 背包实际存储 API 的懒注册问题也已按清单版本修正并通过专项 GameTest。可以进入隔离测试服验收。** 完整模组组合的 GameTest 重映射未能启动，45,000 tick 全量 GameTest 与五个真实客户端的联机验收也未完成，因此本报告不将正式存档上线判为已验收。

## 发现与修复

### 1. P1：普通玩家能重新授予管理员收回的权限

已将服主管理的 `granted` / `revoked` 政策与玩家个人关闭偏好分开存储。玩家只能在服务器允许的权限范围内切换；GUI 与普通命令使用同一校验，不能覆盖管理员撤销。新增 OP 管理命令 `/squire admin permission <player_uuid> <node> grant|revoke|reset`。

权限文件 v1 的旧 `granted` 记录无法证明来自管理员，升级时会丢弃；旧撤销政策保留。管理员需检查迁移日志，并对确需开放的节点重新授权。GameTest 验证普通玩家不能通过 GUI 控制命令或 `/squire permission` 绕过撤销，OP 命令可以授权。

### 2. P1：扩容背包 Shift 搬运丢失隐藏数量

显示用堆叠与真实存储数量已分离。快速搬运先按目标库存可接受的普通堆叠模拟插入，再按真实成功数量从背包提取；每段不超过原版物品堆叠上限。`setStack` 不再允许显示副本覆盖仍含超额物品的真实槽位。

修复后的 256 件守恒回归使用实现 Fabric Transfer `SlottedStorage` 契约的扩容测试存储，覆盖“玩家端只能接收 64 件”和“目标库存已满”两种情况。另用服务器模组目录里的真实 Sophisticated Backpacks 3.23.4.5.110 / Core 1.2.7.15.166 运行 GameTest：真实背包存入 32 颗钻石、重新打开读取、提取 12 颗，再次打开确认余下 20 颗。该专项证明目标版本的真实 item-storage API 和 NBT 回存路径可用；256 件案例仍是合约测试，没有在真实物品上安装堆叠升级后重测。

适配中还修复了一个真实集成问题：Sophisticated Backpacks 3.x 在 `BackpackWrapperLookup` 静态初始化时注册物品存储，而 Squire 可能先查询 Porting Lib。适配器现在会尝试初始化这个可选注册类；缺少 Sophisticated 时仍保留其他 Porting Lib 存储提供方的查询路径。

### 3. P1：出生点保护漏判穿过或包围出生点的编辑区域

区域保护改为 X/Z 投影矩形相交判定，Y 轴不影响原版出生点方形范围；坐标差使用 `long`，避免极端坐标溢出。规则已对齐 Minecraft 1.20.1 专用服务器：只保护主世界；OP 列表为空、半径为 0 或负值时不保护；OP 豁免；边界为包含端点的切比雪夫方形。

单元测试覆盖穿过/包围保护区、边界、维度/OP/半径规则与整数边界坐标。

### 4. P1（安装领地/锁箱模组时）：第三方保护未接入

服务端适配入口已加入 Fabric `squire_protection` entrypoint。多个保护适配器采用全部允许才放行；初始化失败、返回空决策或运行时异常均拒绝世界变更。容器操作检查双箱两半的权限，召唤则先逐块检查整套仪式结构，再改动世界。

本次提供的服务器模组清单没有领地或锁箱模组，因此无需为当前清单伪造特定集成。以后若增加此类模组，仍须编写并安装针对对应版本的 provider，并测试未授权玩家无法改动他人领地、锁箱、双箱及施工区域。适配接口本身不代表对任意保护模组自动兼容，细节见 `docs/guides/protection-adapters.md`。

### 5. P2：对话无玩家级/全服级并发上限且历史不断增长

已增加每玩家最多 3 个活动 turn、全服最多 64 个；模型请求最多并行 5 个、等待队列最多 16 个。模型调用限速为每玩家每 60 秒最多 10 次、全服每 60 秒最多 40 次。取消会清理排队请求并取消可取消的 Future；结束历史最多保存 512 条，恢复超限活动 turn 时失败关闭；无变化的工具等待状态不再每 tick 重写存档。

多人 GameTest 使用永不完成的本地假模型验证限额与取消，不会调用外部模型 API。5 人服启用真实模型前仍需按提供方的速率和费用配置核对这些默认值。

### 6. P2：召唤仪式可能在未成功放置时触发，并绕过结构保护

服务器现在记录发起交互的玩家、世界、目标头部位置、物品与手持数量；下一 tick 仅在非旁观模式且头部确实由本次操作放置成功时继续。生存模式检查物品数量确有消耗，创造模式不要求消耗。仪式会先检查所有将被消耗方块的 `canBreak` 权限，之后才清除结构；清除失败会恢复原方块。

GameTest 覆盖未成功/成功放置回调及保护拒绝时整套仪式保持原状。

## 部署与兼容性说明

- Squire 注册自定义实体、物品、菜单和客户端渲染；服务端与玩家客户端应使用同一 Squire 版本及 Fabric API 依赖。没有安装客户端模组的实际连接测试。
- 清单中的优化模组包括 Lithium、Krypton、ModernFix、FerriteCore；Sophisticated Backpacks/Core 的实际 jar 已在定向 GameTest 中加载。其他模组没有全部组合验证。
- 为尝试完整服务端模组集，将目录中标记为服务端可用的活动 jar 放入 Loom GameTest 运行目录；开发加载器未到达服务器启动阶段，先遇到 Twilight Forest 的 `removeColor` 重映射冲突，移除该 jar 后又遇到 Loom/TinyRemapper `ClosedFileSystemException`。这是本次开发 GameTest 组合加载未通过；不能据此推断正常专用服务器必然故障，也不能据此声称整合包兼容。
- 当前默认权限允许普通玩家物品兑现、正面效果和传送。若服务器需要保留生存经济，请先按 `docs/guides/server-guide.md` 调整权限，不要把代码默认的物品兑现当作生存采集。
- 完整 `runGametest` 包含约 45,000 tick 的建筑矩阵，本次没有跑完整套；先前的长测记录仍标记为中止，不计作通过。
- 五个真实客户端尚未同时在线验收。正式存档替换前，仍应在存档副本里验证召唤、同时打开面板与搬运、不同玩家的权限边界、下线召回、跨维度、施工暂停重启与物品总数，并验证备份可恢复。

## 最终验证记录

- Java 21 执行 `gradlew test build --rerun-tasks --console=plain`：**898 tests，0 failures，0 skipped**。最终构建在最后一次代码更改后复跑。
- 使用真实 Sophisticated Backpacks/Core jar，并选择多人、安全、物流、世界编辑、迁档、召唤等相关 GameTest 类：**97 项全部通过**。日志：`build/release-review/multiplayer-regression-final.log`。包含真实 Sophisticated item-storage 往返测试；它不是五个真实客户端的压力测试。
- 单独的真实 Sophisticated 存储集成运行日志：`build/release-review/sophisticated-backpack-server-integration.log`。
- 最终构建日志：`build/release-review/final-test-build.log`。发布 JAR：`build/libs/squire-0.1.0+mc1.20.1.jar`；SHA-256：`3831DCE671B5B40E1C0A4F60CA8688E7A80C4426AA1B94E3537206CFDA179860`。JAR 内恢复了完整 47 个 GameTest 入口，并包含本次新增的可选 Sophisticated 集成用例；未包含 `dev/squire/review/` 临时类。
