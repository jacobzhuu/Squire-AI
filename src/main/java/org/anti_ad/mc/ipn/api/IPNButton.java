package org.anti_ad.mc.ipn.api;

/**
 * Inventory Profiles Next（IPN）注入到容器界面上的按钮种类。
 *
 * <p><b>这是对 IPN 公开集成 API 的原样声明，不是它的实现。</b>IPN 只把这几个注解类型
 * 放在 {@code org.anti_ad.mc.ipn.api} 包里，作者要适配就得让自己的 Screen 带上这些注解；
 * 而把 IPN 本体做成编译依赖既拉不到（离线构建）、也会让没装 IPN 的人多背一个 jar。
 * 所以这里按公开签名重新声明一遍：类名、成员、保留策略必须和 IPN 的完全一致，
 * 运行时两份同名类只会有一份被加载，注解值靠<em>名字</em>解析，因此哪一份胜出都能对上。</p>
 *
 * <p>改动纪律：<b>这个包里的四个文件只准照抄签名，不准加任何逻辑或成员</b>。
 * 少一个成员就可能让 IPN 自己的代码在读注解时 {@code NoSuchMethodError}。</p>
 *
 * @see <a href="https://github.com/blackd/Inventory-Profiles">Inventory Profiles Next</a>
 */
public enum IPNButton {
	MOVE_TO_CONTAINER,
	MOVE_TO_PLAYER,
	SORT,
	SORT_COLUMNS,
	SORT_ROWS,
	CONTINUOUS_CRAFTING,
	PROFILE_SELECTOR,
	SHOW_EDITOR,
	SETTINGS,
	VILLAGER_DO_GLOBAL_TRADES,
	VILLAGER_DO_GLOBAL_TRADES1,
	VILLAGER_DO_GLOBAL_TRADES2,
	VILLAGER_DO_LOCAL_TRADES,
	VILLAGER_DO_LOCAL_TRADES1,
	VILLAGER_DO_LOCAL_TRADES2,
	VILLAGER_GLOBAL_BOOKMARK,
	VILLAGER_LOCAL_BOOKMARK
}
