package dev.squire.server.blueprint;

import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.util.Identifier;

/**
 * 一次施工的材料账：要多少、已经就位多少、伙伴身上有多少、还差多少。
 *
 * <p>这是「资源真实流动」的账本。以前 {@code executeHouse} 一个 tick 把整栋房子
 * {@code setBlockState} 出来，不检查也不消耗任何东西——玩家的探索、采集、运输在
 * 建造这一环完全没有意义。现在缺料清单是玩家真正要凑齐的东西，而施工时逐格从
 * 背包里 {@code extract}。</p>
 *
 * <p>{@code alreadyInPlace} 不是装饰：在半成品上再跑一次施工，或者玩家自己先砌了
 * 一面墙，都必须从需求里扣掉，否则每次预览都报同一个吓人的总数。</p>
 */
public record BillOfMaterials(Map<Identifier, Integer> required,
		Map<Identifier, Integer> alreadyInPlace, Map<Identifier, Integer> carried) {

	public BillOfMaterials {
		required = Map.copyOf(required);
		alreadyInPlace = Map.copyOf(alreadyInPlace);
		carried = Map.copyOf(carried);
	}

	/** 还差的东西（要求减去随从身上已有的）。已经就位的方块不在 required 里。 */
	public Map<Identifier, Integer> missing() {
		Map<Identifier, Integer> out = new LinkedHashMap<>();
		for (Map.Entry<Identifier, Integer> entry : required.entrySet()) {
			int deficit = entry.getValue() - carried.getOrDefault(entry.getKey(), 0);
			if (deficit > 0) {
				out.put(entry.getKey(), deficit);
			}
		}
		return out;
	}

	public boolean satisfied() {
		return missing().isEmpty();
	}

	public int totalRequired() {
		return required.values().stream().mapToInt(Integer::intValue).sum();
	}

	public int totalPlaced() {
		return alreadyInPlace.values().stream().mapToInt(Integer::intValue).sum();
	}

	/** 给玩家看的一行行清单。空清单说「材料齐了」而不是留一片空白。 */
	public String describe() {
		if (required.isEmpty()) {
			return "\u4e0d\u9700\u8981\u4efb\u4f55\u65b0\u6750\u6599\u3002";
		}
		StringBuilder text = new StringBuilder();
		for (Map.Entry<Identifier, Integer> entry : required.entrySet()) {
			int have = carried.getOrDefault(entry.getKey(), 0);
			text.append("\n  \u00b7 ").append(shortName(entry.getKey()))
				.append(" ").append(have).append("/").append(entry.getValue());
			if (have < entry.getValue()) {
				text.append("\uff08\u8fd8\u5dee ").append(entry.getValue() - have).append("\uff09");
			}
		}
		if (totalPlaced() > 0) {
			text.append("\n  \uff08\u5df2\u6709 ").append(totalPlaced())
				.append(" \u683c\u5c31\u4f4d\uff0c\u4e0d\u91cd\u590d\u8ba1\u7b97\uff09");
		}
		return text.toString();
	}

	private static String shortName(Identifier id) {
		return "minecraft".equals(id.getNamespace()) ? id.getPath() : id.toString();
	}
}
