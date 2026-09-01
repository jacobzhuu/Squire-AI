package dev.squire.server.shortcut;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 玩家自定义的快捷指令：<b>一个名字 → 一个动作</b>。
 *
 * <h2>存的是动作，不是一句话</h2>
 * <p>这一层原来存的是「原话」，点一下把那句话重新送回 {@code InputGateway}——
 * 也就是<b>可能再走一次模型</b>。代价有三个：同一个快捷两次点出不同结果；离线或
 * 模型报错时它整个不能用；而且它绕开了面板按钮那条「职业 → 能力 → 权限」的判定，
 * 因为自然语言那条路是另一套判据。现在存的是
 * {@code entryId + arg}（{@code guard.start}、{@code inventory.give(count=64)}），
 * 点一下走的是服务端动作，判定和面板上那个按钮<b>逐字相同</b>。</p>
 *
 * <p>老存档里的原话仍然读得出来（{@link Shortcut#legacy()}），还照旧走输入网关——
 * 玩家存了半年的快捷不该因为一次改版凭空消失。面板新建的一律是绑定式的。</p>
 *
 * <p>每人上限 {@link #MAX_PER_PLAYER} 条：面板一页放得下，也不至于变成一个需要
 * 翻页的第二套命令系统。</p>
 */
public final class ShortcutStore {

	private static final Logger LOG = LoggerFactory.getLogger(ShortcutStore.class);
	/**
	 * 存档版本。
	 *
	 * <p>2 起每条多了 {@code entry} / {@code arg} 两个字段。版本 1 的文件照读——
	 * 那时候每条只有一句原话，读进来就是一条 {@link Shortcut#legacy()}。</p>
	 */
	private static final int VERSION = 2;

	/** 每人最多几条。面板一页正好放得下。 */
	public static final int MAX_PER_PLAYER = 8;

	public static final int MAX_NAME_LENGTH = 16;
	public static final int MAX_PHRASE_LENGTH = 256;

	public static final int MAX_ENTRY_ID_LENGTH = 48;
	public static final int MAX_ARG_LENGTH = 32;

	/**
	 * 一条快捷指令。
	 *
	 * @param phrase  老存档里的那句原话；绑定式的这一条是空串
	 * @param entryId {@code CommandCatalog} 里那一格的 id；老存档是空串
	 * @param arg     档位参数（数量、材质、模板…），只有一个档位时是空串
	 */
	public record Shortcut(String name, String phrase, String entryId, String arg) {

		public Shortcut {
			name = name == null ? "" : name;
			phrase = phrase == null ? "" : phrase;
			entryId = entryId == null ? "" : entryId;
			arg = arg == null ? "" : arg;
		}

		/** 老存档里的自然语言快捷。 */
		public Shortcut(String name, String phrase) {
			this(name, phrase, "", "");
		}

		/** 绑定到一个服务端动作上了吗。 */
		public boolean bound() {
			return !entryId.isEmpty();
		}

		/** 还是那种「点一下重新说一遍」的老快捷吗。 */
		public boolean legacy() {
			return entryId.isEmpty();
		}

		/**
		 * 面板同步用的一行：{@code entryId ␟ arg ␟ phrase}。
		 *
		 * <p>打包成一个字段而不是往状态包里再加两个 list：{@code PanelState} 的构造器
		 * 已经是三十多个位置参数，再加两个正是那个类的类级文档里写着要避免的东西。</p>
		 */
		public String spec() {
			return entryId + SPEC_SEPARATOR + arg + SPEC_SEPARATOR + phrase;
		}
	}

	/** 打包分隔符（ASCII unit separator）：玩家打不出这个字符。 */
	public static final String SPEC_SEPARATOR = "";

	private final Map<UUID, List<Shortcut>> byPlayer = new ConcurrentHashMap<>();
	private final Supplier<Path> fileSupplier;
	private volatile boolean writable = true;

	public ShortcutStore(Supplier<Path> fileSupplier) {
		this.fileSupplier = fileSupplier;
	}

	// ------------------------------------------------------------------ 读写

	public List<Shortcut> list(UUID playerId) {
		return List.copyOf(byPlayer.getOrDefault(playerId, List.of()));
	}

	public Optional<Shortcut> byName(UUID playerId, String name) {
		if (name == null) {
			return Optional.empty();
		}
		String key = name.trim();
		return list(playerId).stream()
			.filter(s -> s.name().equalsIgnoreCase(key)).findFirst();
	}

	/** 第 {@code index} 条（面板按钮按位置点），越界返回空。 */
	public Optional<Shortcut> byIndex(UUID playerId, int index) {
		List<Shortcut> all = list(playerId);
		return index < 0 || index >= all.size() ? Optional.empty()
			: Optional.of(all.get(index));
	}

	/** 结果：成功与否 + 一句给玩家看的话。 */
	public record Result(boolean success, String message) { }

	/** 新增或覆盖一条。同名视为改写——玩家改一句话不该先删再加。 */
	public Result put(UUID playerId, String rawName, String rawPhrase) {
		String name = rawName == null ? "" : rawName.trim();
		String phrase = rawPhrase == null ? "" : rawPhrase.trim();
		if (name.isEmpty() || phrase.isEmpty()) {
			return new Result(false, "格式是「名字=要说的话」，两边都不能空。");
		}
		if (name.length() > MAX_NAME_LENGTH) {
			return new Result(false, "名字最多 " + MAX_NAME_LENGTH + " 个字。");
		}
		if (phrase.length() > MAX_PHRASE_LENGTH) {
			return new Result(false, "内容最多 " + MAX_PHRASE_LENGTH + " 个字。");
		}
		List<Shortcut> current = new java.util.ArrayList<>(list(playerId));
		int existing = indexOf(current, name);
		if (existing >= 0) {
			current.set(existing, new Shortcut(name, phrase));
		} else {
			if (current.size() >= MAX_PER_PLAYER) {
				return new Result(false,
					"最多只能存 " + MAX_PER_PLAYER + " 条，先删掉一条再加。");
			}
			current.add(new Shortcut(name, phrase));
		}
		byPlayer.put(playerId, List.copyOf(current));
		save();
		return new Result(true, (existing >= 0 ? "改好了：" : "记下了：") + name);
	}

	/**
	 * 面板上第 {@code index} 个槽位的保存。
	 *
	 * <p>面板给的是「第几格」，不是一个名字——玩家点开哪一格就在编辑哪一条，
	 * 于是改名字也只是改这一格的内容，而不会变成"又新增了一条"。{@code index}
	 * 超出现有条数时按新增处理（面板上的空槽就是这么来的）。</p>
	 */
	public Result putAt(UUID playerId, int index, String rawName, String rawPhrase) {
		String name = rawName == null ? "" : rawName.trim();
		String phrase = rawPhrase == null ? "" : rawPhrase.trim();
		Result invalid = validate(name, phrase);
		if (invalid != null) {
			return invalid;
		}
		return replaceAt(playerId, index, new Shortcut(name, phrase));
	}

	/**
	 * 面板新建/改写第 {@code index} 格：绑定到一个动作上。
	 *
	 * <p>和 {@link #putAt} 的区别只有一处——存下来的是 {@code entryId + arg} 而不是
	 * 一句话。校验「这个 id 现在还点不点得动」<b>不在这里</b>：那要拿服务端的职业
	 * 档案判，而且失效的快捷是要留着显示成锁着的，不是要拒绝保存。</p>
	 */
	public Result bindAt(UUID playerId, int index, String rawName, String rawEntryId,
			String rawArg) {
		String name = rawName == null ? "" : rawName.trim();
		String entryId = rawEntryId == null ? "" : rawEntryId.trim();
		String arg = rawArg == null ? "" : rawArg.trim();
		if (name.isEmpty()) {
			return new Result(false, "先给它起个名字。");
		}
		if (name.length() > MAX_NAME_LENGTH) {
			return new Result(false, "名称最多 " + MAX_NAME_LENGTH + " 个字。");
		}
		if (entryId.isEmpty()) {
			return new Result(false, "还没选动作。");
		}
		if (entryId.length() > MAX_ENTRY_ID_LENGTH || arg.length() > MAX_ARG_LENGTH) {
			return new Result(false, "动作参数太长了。");
		}
		return replaceAt(playerId, index, new Shortcut(name, "", entryId, arg));
	}

	/** {@link #putAt} 与 {@link #bindAt} 共用的落盘一步：撞名检查 + 覆盖或新增。 */
	private Result replaceAt(UUID playerId, int index, Shortcut shortcut) {
		List<Shortcut> current = new java.util.ArrayList<>(list(playerId));
		int clash = indexOf(current, shortcut.name());
		if (clash >= 0 && clash != index) {
			return new Result(false, "已经有一条叫「" + shortcut.name() + "」了，换个名字。");
		}
		if (index >= 0 && index < current.size()) {
			current.set(index, shortcut);
		} else {
			if (current.size() >= MAX_PER_PLAYER) {
				return new Result(false,
					"最多只能存 " + MAX_PER_PLAYER + " 条，先删掉一条再加。");
			}
			current.add(shortcut);
		}
		byPlayer.put(playerId, List.copyOf(current));
		save();
		return new Result(true, "记下了：" + shortcut.name());
	}

	/** 名字/内容的公共校验；没问题时返回 {@code null}。 */
	private static Result validate(String name, String phrase) {
		if (name.isEmpty() || phrase.isEmpty()) {
			return new Result(false, "名称和内容都要填。");
		}
		if (name.length() > MAX_NAME_LENGTH) {
			return new Result(false, "名称最多 " + MAX_NAME_LENGTH + " 个字。");
		}
		if (phrase.length() > MAX_PHRASE_LENGTH) {
			return new Result(false, "内容最多 " + MAX_PHRASE_LENGTH + " 个字。");
		}
		return null;
	}

	/** 面板上第 {@code index} 个槽位的删除；空槽返回失败。 */
	public Result removeAt(UUID playerId, int index) {
		var existing = byIndex(playerId, index);
		return existing.isEmpty() ? new Result(false, "这个位置本来就是空的。")
			: remove(playerId, existing.get().name());
	}

	public Result remove(UUID playerId, String rawName) {
		String name = rawName == null ? "" : rawName.trim();
		List<Shortcut> current = new java.util.ArrayList<>(list(playerId));
		int existing = indexOf(current, name);
		if (existing < 0) {
			return new Result(false, "没有叫「" + name + "」的快捷指令。");
		}
		current.remove(existing);
		byPlayer.put(playerId, List.copyOf(current));
		save();
		return new Result(true, "删掉了：" + name);
	}

	private static int indexOf(List<Shortcut> list, String name) {
		for (int i = 0; i < list.size(); i++) {
			if (list.get(i).name().equalsIgnoreCase(name)) {
				return i;
			}
		}
		return -1;
	}

	/**
	 * 把「名字=内容」这一行拆开。
	 *
	 * <p>接受 {@code = ： :} 三种分隔符：玩家在中文输入法下打出全角冒号是常态，
	 * 因为这个而报「格式不对」纯粹是刁难人。</p>
	 */
	public static Optional<Shortcut> parseLine(String line) {
		if (line == null) {
			return Optional.empty();
		}
		for (String separator : new String[] {"=", "：", ":"}) {
			int at = line.indexOf(separator);
			if (at > 0 && at < line.length() - separator.length()) {
				return Optional.of(new Shortcut(line.substring(0, at).trim(),
					line.substring(at + separator.length()).trim()));
			}
		}
		return Optional.empty();
	}

	// ------------------------------------------------------------------ 持久化

	public void save() {
		if (!writable) {
			return;
		}
		try {
			Path file = fileSupplier.get();
			if (file == null) {
				return;
			}
			Files.createDirectories(file.getParent());
			JsonObject root = new JsonObject();
			root.addProperty("version", VERSION);
			JsonObject players = new JsonObject();
			for (var entry : byPlayer.entrySet()) {
				com.google.gson.JsonArray list = new com.google.gson.JsonArray();
				for (Shortcut shortcut : entry.getValue()) {
					JsonObject one = new JsonObject();
					one.addProperty("name", shortcut.name());
					one.addProperty("phrase", shortcut.phrase());
					one.addProperty("entry", shortcut.entryId());
					one.addProperty("arg", shortcut.arg());
					list.add(one);
				}
				players.add(entry.getKey().toString(), list);
			}
			root.add("players", players);
			Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
			Files.writeString(tmp, root.toString());
			try {
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
					StandardCopyOption.ATOMIC_MOVE);
			} catch (java.nio.file.AtomicMoveNotSupportedException e) {
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (Exception e) {
			LOG.warn("[squire-shortcuts] save failed: {}", e.toString());
		}
	}

	/** 坏文件按「没有快捷指令」处理，绝不抛——不能因为一份存档拒绝启动。 */
	public int load() {
		byPlayer.clear();
		try {
			Path file = fileSupplier.get();
			if (file == null || !Files.isRegularFile(file)) {
				return 0;
			}
			JsonObject root = JsonParser.parseString(Files.readString(file))
				.getAsJsonObject();
			// 版本 1 的文件照读：那时候每条只有一句原话，读进来就是一条老快捷。
			// 拒读的代价是玩家存了半年的快捷凭空消失，而这份数据完全兼容。
			int version = root.has("version") ? root.get("version").getAsInt() : 0;
			if (version < 1 || version > VERSION || !root.has("players")) {
				return 0;
			}
			int total = 0;
			for (var entry : root.getAsJsonObject("players").entrySet()) {
				UUID playerId;
				try {
					playerId = UUID.fromString(entry.getKey());
				} catch (IllegalArgumentException e) {
					continue; // 一行坏数据不该毁掉其余的
				}
				List<Shortcut> list = new java.util.ArrayList<>();
				for (var element : entry.getValue().getAsJsonArray()) {
					JsonObject one = element.getAsJsonObject();
					String name = text(one, "name");
					String phrase = text(one, "phrase");
					String entryId = text(one, "entry");
					String arg = text(one, "arg");
					// 名字空、或者既没绑动作也没有原话的一条，是坏数据，跳过。
					if (!name.isBlank() && (!entryId.isBlank() || !phrase.isBlank())
							&& list.size() < MAX_PER_PLAYER) {
						list.add(new Shortcut(name, phrase, entryId, arg));
					}
				}
				byPlayer.put(playerId, List.copyOf(list));
				total += list.size();
			}
			return total;
		} catch (Exception e) {
			LOG.warn("[squire-shortcuts] load failed, starting empty: {}", e.toString());
			byPlayer.clear();
			return 0;
		}
	}

	/** 测试用的内存实例。 */
	public static ShortcutStore inMemory() {
		ShortcutStore store = new ShortcutStore(() -> null);
		store.writable = false;
		return store;
	}

	/** 名字列表，面板按钮直接用。 */
	public List<String> names(UUID playerId) {
		return list(playerId).stream().map(Shortcut::name).toList();
	}

	/** 内容列表，下标与 {@link #names} 一一对应；面板的编辑框要拿它回填。 */
	public List<String> phrases(UUID playerId) {
		return list(playerId).stream().map(Shortcut::phrase).toList();
	}

	/**
	 * 面板同步用的一行一条：{@code entryId ␟ arg ␟ phrase}，下标与 {@link #names} 对齐。
	 *
	 * <p>面板要靠它知道每一格<b>绑的是哪个动作</b>——不知道的话既画不出「他会做什么」
	 * 那行说明，也判不出这一条现在是不是锁着的。</p>
	 */
	public List<String> specs(UUID playerId) {
		return list(playerId).stream().map(Shortcut::spec).toList();
	}

	/** 缺字段按空串算：一行坏数据不该毁掉整份存档。 */
	private static String text(JsonObject object, String key) {
		return object.has(key) && object.get(key).isJsonPrimitive()
			? object.get(key).getAsString() : "";
	}
}
