# 双侍从改造验证记录

构建使用本机 JDK 21；默认 Java 11 无法加载项目使用的 Fabric Loom 1.11.8。

## 已通过的自动化验证

- 全部 845 项 JUnit 测试，包括新增的点名解析、重名、多名字、英文单词边界及中文长短名字测试；覆盖 `food oo give bread` 只移除真实点名位置的回归。
- 30 项定向 GameTest：M18 职业、M20 职业面板、M21 新手流程、M24 召唤、M27 召集铃及 M38 双侍从回归。
- M38 验证点名、临时 `as` 命令和取物任务固定绑定 `agentId`；目标移除后不回退另一名侍从；临时命令不改变当前选择。
- M38 验证铃铛绑定姓名及改名刷新、远程背包拒绝、命令槽位交换的数量守恒，以及全部面板动作均有对应命令。
- Java 编译与 JAR 构建成功；16 种铃铛职业状态／品质组合的模型 JSON 均可解析。

报告位置：`build/reports/tests/test/index.html`、`build/reports/gametest/report.xml`。命令说明见 [玩家指南](guides/companion-commands.md)。

## 全量 GameTest 尚未通过

尝试执行 284 项全量游戏测试。运行途中已出现 11 个失败批次；停止长时间全量运行后，改为执行上述定向回归。失败记录保留在 `build/companion-full-validation.log`，不宣称这些失败已被证明是既有问题。

已出现失败的批次：

- `squire-blueprint-bill`、`squire-blueprint-level`
- `squire-runtime-guard-owner`
- `squire-project-midbuild-topup`、`squire-project-site-preparation`
- `squire-native-tier-five`、`squire-native-sawmill`
- `squire-access-import-fountain`、`squire-access-import-library`
- `fluid-npc-artificial`、`squire-smeltery-master`

全量日志还出现工程存档临时文件替换的 `AccessDeniedException`。这些施工／战斗场景需要另行定位，不能据此宣称全套游戏测试通过。

继续核查后，为 M13 的带窗蓝图分配了独立 ID，避免覆盖纯石壳测试素材；材料断言更新为满级工程师当前的 50% 节材规则。合并重跑 M13 和上述六组测试，共 44 项，42 项通过。双侍从相关 30 项全部通过；M13 的材料账单测试通过，`buildingSpendsRealMaterialsOutOfTheBackpack` 和 `anObstructedSiteIsLevelledInsteadOfSkipped` 仍失败，实际完整石块数分别为 14 和 25（预期 26）。运行中再次出现存档 `AccessDeniedException`，尚不能确认它解释了全部施工失败。

本次合并回归日志：`build/companion-final-validation.log`；保留报告：`build/reports/gametest/companion-44-regression.xml`。测试素材隔离修复并未解决全部施工问题。

## 召集铃显示修复（后续反馈）

定位到原模型谓词返回 0/1/2/3，而 Minecraft 1.20.1 的 `ClampedModelPredicateProvider.call` 将返回值限制为 0–1，导致守卫、工程师与高品质分支无法命中。改为 0/0.25/0.5/0.75，并同步模型 JSON 阈值。新增测试按原版截断及最后匹配规则检查全部 16 种组合，而非只检查 JSON 是否能解析。

模型显式配置第一／第三人称左右手姿态，正反面均添加职业和品质标记；原始 PNG 保持不变，模型侧面按其透明轮廓生成，避免将整张图拉伸到矩形薄片侧边。

上述模型资源检查不能替代游戏客户端的实际渲染验收。

## 尚需客户端实机确认

自动化测试不包含 Minecraft 客户端截图验收。两套职业工作页在不同 GUI 缩放下的布局、双侍从顶部切换的视觉效果，以及铃铛在物品栏与手持状态的最终渲染，尚未实机确认。
