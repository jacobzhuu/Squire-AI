package dev.squire.server.input;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import dev.squire.common.protocol.InputEnvelope;
import dev.squire.common.protocol.InputSource;
import dev.squire.server.fastpath.FastPath;
import dev.squire.server.fastpath.FastPathIntent;
import dev.squire.server.runtime.SquireRuntime;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

/**
 * Input Gateway (spec section 12): every input becomes an {@link InputEnvelope} with
 * the sender resolved BEFORE any NLP runs, then routes deterministically:
 * FastPath phrase → control intent → runtime pipeline; everything else → conversation.
 */
public final class InputGateway {
	private static final org.slf4j.Logger LOG =
		org.slf4j.LoggerFactory.getLogger(InputGateway.class);

	private static final AtomicLong TICK_COUNTER = new AtomicLong();

	/**
	 * 快捷指令展开深度。一条快捷指令的内容里可以再写另一条的名字，链太长（或者指回
	 * 自己）就必须停下来，否则一句话能把服务端拖死。只在服务器主线程上用。
	 */
	private static final int MAX_SHORTCUT_DEPTH = 3;
	private static int shortcutDepth;

	private InputGateway() {
	}

	/** 「你没点名，他没理你」这句提示的冷却（毫秒）：一次提醒够了，别刷屏。 */
	private static final long UNADDRESSED_HINT_COOLDOWN_MS = 30_000L;
	private static final java.util.Map<UUID, Long> LAST_HINT_AT =
		new java.util.concurrent.ConcurrentHashMap<>();

	/**
	 * 明确指向侍从的入口（面板输入框、快捷指令、命令、测试）：不要求点名。
	 */
	public static java.util.Optional<SquireRuntime.ExecutionResult> acceptChat(
			ServerPlayerEntity sender, String rawText) {
		return acceptChat(sender, rawText, false);
	}

	/**
	 * Entry point wired to ServerMessageEvents.CHAT_MESSAGE.
	 * Non-owners may be heard but can never create write tasks (role check inside runtime).
	 *
	 * @param requireName 公共聊天频道要传 {@code true}：话里必须带上他的名字他才理会。
	 *                    在此之前每一句聊天都会被送进来——旁人一句「跟着我」就能支使
	 *                    别人的侍从，自言自语也会白烧一次大模型调用。
	 */
	public static java.util.Optional<SquireRuntime.ExecutionResult> acceptChat(
			ServerPlayerEntity sender, String rawText, boolean requireName) {
		MinecraftServer server = sender.getServer();
		SquireRuntime.ensureInitialized(server);

		SquireRuntime runtime = SquireRuntime.get();
		var avatar = runtime.agents().resolveForOwner(sender.getUuid());
		UUID agentId = avatar.map(a -> a.agentId()).orElse(null);
		String text = rawText;
        var addressed = AgentAddressing.resolve(rawText, runtime.agentStore().recordsOfOwner(sender.getUuid())
            .stream().map(r -> new AgentAddressing.Candidate(r.agentId, r.displayName)).toList());
        if (addressed.ambiguous()) {
            sender.sendMessage(Text.literal("[Squire] 请明确指定一名侍从；重名时使用 /squire as <UUID> <指令>。"), false);
            return java.util.Optional.empty();
        }
        if (addressed.target() != null) {
            var scoped=runtime.agents().explicitTarget(sender.getUuid());
            if(scoped.isPresent() && !scoped.get().equals(addressed.target())) {
                sender.sendMessage(Text.literal("[Squire] Name differs from the selected command/panel target."),false);
                return java.util.Optional.empty();
            }
            agentId = addressed.target();
            text = addressed.text();
        } else if (requireName) {
            hintIfItLookedLikeAnOrder(sender, rawText, runtime.agentStore().recordsOfOwner(sender.getUuid())
                .stream().map(r -> r.displayName).collect(java.util.stream.Collectors.joining(" / ")));
            return java.util.Optional.empty();
        }
        if (agentId == null || runtime.agents().resolveByAgentId(agentId).isEmpty()) {
            sender.sendMessage(Text.literal("[Squire] 指定的侍从当前不在场，请使用绑定的召集铃。"), false);
            return java.util.Optional.empty();
        }
        final UUID inputTarget = agentId;
        if (text.isBlank()) {
            sender.sendMessage(Text.literal("[" + runtime.agentStore().recordOfAgent(agentId)
                .map(r -> r.displayName).orElse("Squire") + "] 我在，你说。"), false);
            return java.util.Optional.empty();
        }
		InputEnvelope envelope = new InputEnvelope(
			sender.getUuid(),
			InputSource.CHAT,
			agentId,
			text,
			TICK_COUNTER.incrementAndGet());

		dev.squire.server.metrics.SquireMetrics metrics =
			SquireRuntime.get().metrics();
		metrics.inc(dev.squire.server.metrics.SquireMetrics.Key.FASTPATH_REQUESTS);
		// 聊天路径绝不允许静默失败：这里抛出去的异常会被 Fabric 的事件总线吞掉，
		// 玩家只会看到"说了句话，什么都没发生"。宁可回一句难看的错误，也不要沉默。
		try {
			// 玩家自定义的快捷指令先于一切内置规则：那是他自己起的名字，
			// 被内置短语抢走会非常费解。深度守卫防止「快捷指令指向自己」转成死循环。
			var shortcut = SquireRuntime.get().shortcuts()
				.byName(sender.getUuid(), envelope.rawText());
			if (shortcut.isPresent() && shortcutDepth < MAX_SHORTCUT_DEPTH) {
				shortcutDepth++;
				try {
					// 和面板上点那一格<b>同一条路</b>：绑定式的走服务端动作并过一遍
					// 能力闸，老存档里的原话才回到这条自然语言管线上。
					var result=withTarget(runtime, sender, inputTarget, () -> runtime.runShortcut(sender, shortcut.get()));
                    if(!result.message().isBlank()) withTarget(runtime,sender,inputTarget,() -> {sender.sendMessage(Text.literal(runtime.namedMessage(sender.getUuid(),result.message())),false);return null;});
                    return java.util.Optional.of(result);
				} finally {
					shortcutDepth--;
				}
			}
			var dialogueControl = NaturalLanguageIntentRouter.route(envelope.rawText());
			if (dialogueControl.kind() == NaturalLanguageIntentRouter.Kind.CANCEL) {
				int cancelled = SquireRuntime.get().conversations()
					.cancelFor(sender.getUuid(), agentId);
				if (cancelled > 0) {
					metrics.inc(dev.squire.server.metrics.SquireMetrics.Key.FASTPATH_HITS);
					var result = new SquireRuntime.ExecutionResult(true,
						"feedback.conversation_cancel", null,
						"[Squire] 已取消刚才的对话任务及未完成步骤。");
					sender.sendMessage(Text.literal(runtime.namedMessage(sender.getUuid(),result.message())), false);
					return java.util.Optional.of(result);
				}
			}
			if (dialogueControl.kind() == NaturalLanguageIntentRouter.Kind.CORRECTION) {
				var corrected = withTarget(runtime, sender, inputTarget, () -> runtime.conversations().tryHandleCorrection(sender, envelope.rawText()));
				if (corrected.isPresent()) {
					metrics.inc(dev.squire.server.metrics.SquireMetrics.Key.FASTPATH_HITS);
					sender.sendMessage(Text.literal(corrected.get().message()), false);
					return corrected;
				}
			}
			// Cross-turn referents ("传送过去", "攻击刚才那个") are resolved before
			// the ordinary phrase table. The resolver also notices an explicit new target
			// and invalidates the stale pending target before letting normal parsing continue.
			var continuation = withTarget(runtime, sender, inputTarget, () -> runtime.conversations().tryHandleContinuation(sender, envelope.rawText()));
			if (continuation.isPresent()) {
				metrics.inc(dev.squire.server.metrics.SquireMetrics.Key.FASTPATH_HITS);
				sender.sendMessage(Text.literal(continuation.get().message()), false);
				return continuation;
			}
			var intent = FastPath.match(envelope.rawText(), SquireRuntime.get().vocabulary());
			if (intent.isPresent()) {
				metrics.inc(dev.squire.server.metrics.SquireMetrics.Key.FASTPATH_HITS);
				UUID routed = agentId;
				return java.util.Optional.of(withTarget(runtime, sender, routed,
					() -> applyIntent(sender, intent.get())));
			}
			UUID routed = agentId;
			withTarget(runtime, sender, routed, () -> {
				runtime.handleConversation(sender, envelope.rawText());
				return null;
			});
			return java.util.Optional.empty();
		} catch (RuntimeException e) {
			LOG.error("[input] chat handling threw for {}",
				sender.getGameProfile().getName(), e);
			sender.sendMessage(Text.literal("[Squire] 处理这句话时出错了："
				+ (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())),
				false);
			return java.util.Optional.empty();
		}
	}

	private static <T> T withTarget(SquireRuntime runtime, ServerPlayerEntity sender,
			UUID agentId, java.util.function.Supplier<T> action) {
		return agentId == null ? action.get()
			: runtime.agents().withTarget(sender.getUuid(), agentId, action);
	}

	private static void hintIfItLookedLikeAnOrder(ServerPlayerEntity sender,
			String rawText, String name) {
		SquireRuntime runtime = SquireRuntime.get();
		boolean looksLikeAnOrder =
			FastPath.match(rawText, runtime.vocabulary()).isPresent()
				|| runtime.shortcuts().byName(sender.getUuid(), rawText).isPresent();
		if (!looksLikeAnOrder) {
			return;
		}
		long now = System.currentTimeMillis();
		Long last = LAST_HINT_AT.get(sender.getUuid());
		if (last != null && now - last < UNADDRESSED_HINT_COOLDOWN_MS) {
			return;
		}
		LAST_HINT_AT.put(sender.getUuid(), now);
		String called = name == null || name.isBlank() ? "侍从" : name;
		sender.sendMessage(Text.literal("[Squire] （对我说话时把名字带上我才会理，"
			+ "比如「§e" + called + " " + rawText.trim() + "§r」；面板里的输入框不用带。）"),
			false);
	}

	/** 结构化意图分发（方案 B1）：控制/注视/表情各走运行时的对应入口。 */
	private static SquireRuntime.ExecutionResult applyIntent(ServerPlayerEntity sender,
			FastPathIntent intent) {
		SquireRuntime runtime = SquireRuntime.get();
		final SquireRuntime.ExecutionResult result;
		if (intent instanceof FastPathIntent.Control control) {
			result = runtime.executeControl(sender,
				FastPath.toControlIntent(control.kind()));
		} else if (intent instanceof FastPathIntent.LookAt look) {
			result = look.target()
				.map(target -> runtime.executeLookAt(sender,
					target[0], target[1], target[2]))
				.orElseGet(() -> runtime.executeLookMe(sender));
		} else if (intent instanceof FastPathIntent.Emote emote) {
			result = runtime.executeEmote(sender, emote.type());
		} else if (intent instanceof FastPathIntent.Fulfil fulfil) {
			result = runtime.runItemOperation(sender, fulfil.operation());
		} else if (intent instanceof FastPathIntent.UndoItemEdit) {
			result = runtime.undoItemEdit(sender);
		} else if (intent instanceof FastPathIntent.LocateStructure locate) {
			result = runtime.locateStructure(sender, locate.structureId(),
				locate.spokenName());
		} else if (intent instanceof FastPathIntent.SetCombatStyle style) {
			result = runtime.setCombatStyle(sender, style.style());
		} else if (intent instanceof FastPathIntent.ComeHere) {
			result = runtime.comeToOwner(sender);
		} else if (intent instanceof FastPathIntent.TeleportOwner tp) {
			result = runtime.teleportOwnerToPlaceOrDimension(sender, tp.dimensionId(),
				tp.place(), tp.bring(), tp.label());
		} else if (intent instanceof FastPathIntent.LocateBiome biome) {
			result = runtime.locateBiome(sender, biome.biomeId(), biome.spokenName());
		} else if (intent instanceof FastPathIntent.SetTime time) {
			result = runtime.setTime(sender, time.preset(), null);
		} else if (intent instanceof FastPathIntent.SetWeather weather) {
			result = runtime.setWeather(sender, weather.preset());
		} else if (intent instanceof FastPathIntent.Inspect inspect) {
			result = runtime.inspect(sender, inspect.kind());
		} else if (intent instanceof FastPathIntent.AttackTarget attack) {
			result = runtime.attackTarget(sender, attack.entityId(),
				attack.spokenName());
		} else if (intent instanceof FastPathIntent.GiveEffect effect) {
			result = runtime.giveEffect(sender, effect.effectId(), effect.spokenName(),
				effect.durationTicks(), effect.amplifier());
		} else if (intent instanceof FastPathIntent.WhatIsYourName) {
			result = runtime.tellName(sender);
		} else if (intent instanceof FastPathIntent.Guard guard) {
			result = runtime.startGuard(sender, guard.radius(), guard.persistent());
		} else if (intent instanceof FastPathIntent.GuardStop) {
			result = runtime.stopGuard(sender);
		} else if (intent instanceof FastPathIntent.AidOwner) {
			result = runtime.startAidOwner(sender);
		} else if (intent instanceof FastPathIntent.RememberLocation remember) {
			result = runtime.rememberLocation(sender, remember.typeOrName());
		} else if (intent instanceof FastPathIntent.RecallLocation recall) {
			result = runtime.recallLocation(sender, recall.typeOrName());
		} else if (intent instanceof FastPathIntent.FillSelection fill) {
			result = runtime.fillSelection(sender, fill.blockId());
		} else if (intent instanceof FastPathIntent.StartProject project) {
			result = runtime.projectStartFromPhrase(sender, project.phrase());
		} else if (intent instanceof FastPathIntent.ProjectControl control) {
			result = switch (control.kind()) {
				case CONFIRM -> runtime.projectConfirm(sender);
				case PAUSE -> runtime.projectPause(sender);
				case RESUME -> runtime.projectResume(sender);
			};
		} else if (intent instanceof FastPathIntent.BuildHouse house) {
			result = runtime.buildHouse(sender, house.styleWord(), 0, 0, 0);
		} else if (intent instanceof FastPathIntent.Help) {
			result = runtime.describeCapabilities(sender);
		} else if (intent instanceof FastPathIntent.HealSelf) {
			result = runtime.startHealSelf(sender);
		} else {
			throw new IllegalStateException("unknown fast-path intent: " + intent);
		}
		// 第二个参数是 overlay：true 会渲染到物品栏上方的动作栏，闪两秒就没、且不进
		// 聊天历史。以前这里绑的是 result.success()，于是所有"成功"都只在动作栏一闪，
		// 只有失败才进聊天——玩家看到的就是"命令毫无反应"。成功比失败更需要被看见。
		sender.sendMessage(Text.literal(runtime.namedMessage(sender.getUuid(),result.message())), false);
		return result;
	}
}
