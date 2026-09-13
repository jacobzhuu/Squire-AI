# 双侍从与完整指令入口

公共聊天请点名，例如 `oo，给我 16 个面包`。英文姓名忽略大小写；`oo` 不会匹配 `food`。同时喊两人、重名或对两人使用“侍从”这个通称时，系统要求明确对象，不会随便挑一个执行。指定对象未加载或阵亡时也不会转交给另一人。

## 选择目标

```text
/squire list
/squire select oo
/squire as oo give minecraft:bread 16
/squire as ii follow
/squire panel oo
```

`list` 显示姓名、职业、UUID 和当前选中者。重名请使用 UUID，含空格的姓名使用双引号。`as` 只为本条命令指定目标；普通命令使用选中者。面板聊天始终绑定当前面板，输入另一人的名字会要求先切换。续接、快捷指令、物品交付和确认重放保留原任务的目标身份。

## 命令覆盖表

已有子命令保持可用，Tab 可以补全分支。`/squire control` 下自动注册全部面板动作：名称来自按钮的稳定翻译键；有多个槽位的同名动作带唯一编号。新增按钮必须通过命令覆盖测试。

| 功能 | 结构化入口 | 共用行为与限制 |
|---|---|---|
| 跟随、待命、停止、回家、巡逻 | `follow`、`stay`、`stop`、`home`、`patrol`、`come` | 使用所选侍从的运行时控制 |
| 物品兑现 | `give <物品ID> <数量>` | 权限及单次数量上限；所选侍从交付 |
| 护甲、主手、背包、装备修改 | `item edit <作用域> <筛选> <变换> [附魔ID]` | 已有物品原位处理；走统一物品校验 |
| 自身装备、物品修改撤销 | `item equip <物品ID>`、`item undo` | 复用装备与撤销服务 |
| 物品清单和交换 | `inventory <背囊页码> list`、`inventory <页码> move <源槽> <目标槽> <数量>` | 原版槽位规则、同维度 8 格内交换 |
| 守卫、战斗姿态、攻击 | `guard <半径> <是否持续>`、`combat_style`、`stance`、`attack [实体选择器]` | 职业及能力校验 |
| 救援、自疗、停止保护 | `control aid_owner`、`control heal_self`、`control guard_stop` | 原有资源与能力要求 |
| 观察、地点记忆、世界查询 | `inspect`、`location remember/recall`、`locate structure/biome` | 使用真实世界与档案数据 |
| 玩家传送、时间、天气、效果 | `teleport <维度> <地点名> <携带侍从>`、`time`、`weather`、`effect <ID> <秒> <强度>` | 保留服务端权限与白名单 |
| 身份、性格、成长 | `rename`、`personality reroll confirm`、`profile`、`profession`、`role`、`ability` | 保留改名校验、洗练消耗与成长门槛 |
| 权限与自主行为 | `permission <节点> <true/false>`、`autonomy` | 与面板授权相同，仅限已列出的节点 |
| 建筑目录、设计、预设 | `blueprint list/place/status`、`design`、`design preset` | 服务端建筑目录与职业门槛 |
| 预览、材料 | `blueprint rotate`、`blueprint nudge <前后> <左右>`、`control material_*`、`control project_transfer` | 所选侍从必须是施工者 |
| 工程生命周期 | `project start/confirm/status/pause/resume/cancel`，以及原有 `project` 查询 | 原工程、材料池、地形及区域保护校验 |
| 平整地形 | `terrain open/width_up/width_down/depth_up/depth_down/up/down/north/south/west/east/refresh` | 使用工程师的地形预览；随后 `project confirm` |
| 召唤、召回、铃铛 | `summon <南瓜头坐标>`、`summon`、`bell bind/recall/upgrade confirm` | 仪式实体结构、绑定物品、升级材料、冷却照常校验 |
| 快捷指令、对话、确认 | `shortcut`、`conversation`、`confirm`、`deny`、`say` | 对话与后续任务按侍从身份关联 |
| 自动化、工作区、CBP、诊断 | 既有 `automation`、`workspace`、`cbp`、`admin` 分支 | 原服务器开关及管理员权限 |

物品修改作用域：`player_equipped`、`player_held`、`player_inventory`、`agent_equipped`。筛选：`all`、`armor`、`held`、`item <物品ID>`。变换：`none`、`set_max_enchants`、`add_enchant`、`remove_enchant`、`clear_enchants`、`repair`。

```text
/squire as oo item edit player_equipped armor remove_enchant minecraft:thorns
/squire as oo item edit player_held held repair
/squire as ii terrain open
/squire as ii terrain width_up
/squire as ii project confirm
```

背包页码从 0 开始。`inventory list` 列出容器槽位：0–5 为装备，6 为背囊本体，7–42 为侍从背包，43–78 为玩家背包与快捷栏，79–114 为背囊当前页。交换复用原版拿起／放下逻辑，不生成物品；错误槽位、装备不兼容、数量不足或距离过远时拒绝。

## 职业面板与铃铛

双侍从姓名职业按钮常驻面板顶部，一次点击切换；K 打开上次选择者。守卫工作页集中姿态、保护、救援、自疗、巡逻和补给；工程师工作页集中建筑目录、预览、工程进度、材料及阻塞信息。成长、背包、设置和聊天共用基础组件。

打开远方或跨维度侍从的面板不会召回或打断施工。未加载者显示档案快照；依赖身体的操作不可用。背包交换必须同维度且在 8 格以内。独立“召回”按钮要求背包中携带绑定该侍从的铃铛，并遵守铃铛及复活冷却。

召集铃图标：无徽记＝未绑定，白色徽记＝已绑定未转职，蓝色盾牌＝守卫，橙色工具＝工程师；边框独立表示品质。悬浮提示显示绑定姓名与职业。存档中的绑定 UUID 不变，旧铃铛在进入背包后补齐显示缓存；持有期间最多约 2 秒刷新改名与职业变化。

## 管理员双侍从测试

```text
/squire admin spawn <玩家> oo guard
/squire admin spawn <玩家> ii engineer
/squire as oo admin profession set guard 5
/squire as ii admin xp 1000
/squire as oo admin health 10
/squire admin revive_ready <姓名或UUID>
```

需要权限等级 2。创建命令限制每位玩家最多两人且不能创建重复职业。普通指令不会因管理员测试入口而跳过材料、距离或职业条件。客户端与服务端需一起更新，以匹配新增的面板目标与可用性字段。

## Structured tool commands

`/squire tool <tool_name>` displays the registered argument schema; append a JSON object to execute it. Tool names support Tab completion. This covers model-visible built-in and extension tools without requiring an LLM, while retaining the Tool Gateway's ownership, capability, schema, permission, protection and confirmation checks. Internal tools are not exposed. Explicit command targeting also applies here: `/squire as oo tool <tool_name> <JSON>`.

Use `/squire help <topic>` for branch usage and `/squire shortcut bind <slot> <name> <entry> <variant>` for a structured shortcut (slots start at 0). The entry and variant parameters offer completion.
