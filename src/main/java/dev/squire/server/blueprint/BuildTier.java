package dev.squire.server.blueprint;

import java.util.Locale;

/**
 * 一份蓝图属于<b>基础施工</b>还是<b>参数化设计</b>。
 *
 * <h2>为什么需要这条线</h2>
 * <p>「盖房子」在这个模组里其实是两件事，而它们以前共用同一套权限判断，于是两条路
 * 给出了相反的答案：</p>
 * <ul>
 *   <li>面板上的「快速开工」只校验主人身份，守卫点一下就能盖；</li>
 *   <li>而 {@code ToolGate} 把 {@code project.start} / {@code blueprint.place} 整条
 *       判给工程师，于是同一个守卫在聊天里说「盖个仓库」会被拒。</li>
 * </ul>
 * <p>「按钮能盖，说话不能盖」是玩家完全无法理解的一种不一致。修的办法不是把整条
 * 工程线放开，而是<b>按蓝图本身分档</b>：照着现成模板盖是所有随从的基础本事，
 * 而调尺寸、加层、换结构、挂模块是工程师那条成长线买来的东西。</p>
 *
 * <h2>怎么分</h2>
 * <p>分档只看蓝图 id，因为 id 里就编着「这份图是不是被改过参数」：</p>
 * <ul>
 *   <li>{@link ProjectSpec#ID_PREFIX} 开头 —— 工程师设计页产出的参数化规格，
 *       {@link #PARAMETRIC}；</li>
 *   <li>{@link HouseSpec#ID_PREFIX} 开头 —— 只有两份<b>默认</b>户型（橡木 / 石砖，
 *       也就是面板上那两个按钮和「盖个木屋」那句话）算 {@link #BASIC}，
 *       其余尺寸/材料/屋顶都是玩家<b>调过</b>的，算 {@link #PARAMETRIC}；</li>
 *   <li>其它一律 {@link #BASIC} —— 注册表里的内置蓝图、数据包蓝图、GameTest 塞进来的
 *       那几份，全都是固定形状，选它就是选一个现成模板。</li>
 * </ul>
 *
 * <p>这里<b>只做分类</b>，不判断谁能用：那条判断在
 * {@code SquireEngineerService.checkBuildTier}，和其余等级闸放在一起，
 * 面板、聊天、命令、模型工具四条路全部经过它。</p>
 */
public enum BuildTier {

	/** 固定模板。Lv.0、守卫、工程师都能盖，形状和尺寸都不可调。 */
	BASIC,

	/** 参数化蓝图。工程师专属，还要看等级够不够那一份规格。 */
	PARAMETRIC;

	/**
	 * 这个蓝图 id 属于哪一档。
	 *
	 * <p>认不出的 id 一律算 {@link #BASIC}：让「没有这份蓝图」那条错误自己说话，
	 * 比先冒出一句「你不是工程师」有用得多——后者会把一个拼错的名字说成职业问题。</p>
	 */
	public static BuildTier of(String blueprintId) {
		if (blueprintId == null) {
			return BASIC;
		}
		String id = blueprintId.trim().toLowerCase(Locale.ROOT);
		if (id.isEmpty()) {
			return BASIC;
		}
		if (id.startsWith(ProjectSpec.ID_PREFIX)) {
			return PARAMETRIC;
		}
		if (id.startsWith(HouseSpec.ID_PREFIX)) {
			return isDefaultHouse(id) ? BASIC : PARAMETRIC;
		}
		return BASIC;
	}

	public boolean isBasic() {
		return this == BASIC;
	}

	/** 面板那两个按钮和「盖个木屋 / 石屋」那两句话产出的就是这两个 id。 */
	private static boolean isDefaultHouse(String id) {
		return id.equals(HouseSpec.defaults().blueprintId().toLowerCase(Locale.ROOT))
			|| id.equals(HouseSpec.stoneDefaults().blueprintId()
				.toLowerCase(Locale.ROOT));
	}
}
