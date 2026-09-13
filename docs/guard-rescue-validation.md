# 工程师战斗与守卫护主验证

验证日期：2026-09-10；Minecraft 1.20.1 / Fabric；构建使用 Java 21。

## 自动化结果

- 职业、守卫、战斗模式与自主程度相关单元测试：78项通过。
- M18 / M19 / M20 / M23 / M25 / M27 / M39 联合回归：55项 GameTest 通过。
- 最终 M39 独立复验：13项通过（包含随后追加的遇袭点追击边界测试）。
- M39覆盖真实致命伤害事件、伤害吸收不误触发、玩家图腾优先、守卫图腾先于舍身、Lv3/6/9/10门槛、16格边界、跨维度及所有权限制、武器附魔伤害45%、攻击冷却、工程师禁弓、相同护甲减伤、金苹果效果、物品与耐久保存、300tick誓约期限、嘲讽、无敌、阻止重复救援和召回、实体NBT恢复、卸载承诺到期与死亡冷却。
- 真实伤害测试使用ServerPlayerEntity，并仅借用Fabric假玩家的空网络连接；Fabric FakePlayer自身永久无敌，不能用于验证真实致命伤害路径。

## 复现

```powershell
$env:JAVA_HOME='C:/Program Files/Java/jdk-21'
./gradlew.bat test --tests '*Profession*' --tests '*Guard*' --tests '*CombatStyle*' --tests '*Autonomy*' --offline --console=plain
./gradlew.bat runGametest --offline --console=plain '-PsquireGameTestClass=M18ProfessionGameTests,M19OwnerSafetyGameTests,M20ProfessionUiGameTests,M23GuardCombatBalanceGameTests,M25CombatStyleGameTests,M27RecallBellAndHuntGameTests,M39GuardRescueGameTests'
./gradlew.bat assemble --offline --console=plain
```

## 尚未人工验收

客户端粒子、音效与文字的观感、多人客户端同步和完整停服重启过程未做人工游戏内验收。自动化已覆盖NBT读写及卸载后的服务端结算；不将其表述为完成了人工重启或客户端视觉测试。
