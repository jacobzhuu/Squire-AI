package dev.squire.server.command;

import java.util.*;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.*;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import dev.squire.server.runtime.SquireRuntime;
import dev.squire.server.gui.SquireActions;
import dev.squire.server.registry.SquireScreens;
import static net.minecraft.server.command.CommandManager.*;

/** Structured access to companion actions; shares the server's existing handlers. */
public final class CompanionCommands {
    private CompanionCommands() {}
    public static void register() {
        net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register((d, r, e) -> install(d));
    }
    private static void install(CommandDispatcher<ServerCommandSource> d) {
        var root = literal("squire");
        root.then(literal("list").executes(c -> {
            var p = c.getSource().getPlayerOrThrow();
            for (var record : SquireRuntime.get().agentStore().recordsOfOwner(p.getUuid()))
                p.sendMessage(Text.literal((record.primary ? "▶ " : "") + record.displayName + " · "
                    + record.profile.profession.profession() + " · " + record.agentId), false);
            return 1;
        }));
        root.then(literal("select").then(target().executes(c -> {
            UUID id = resolve(c);
            if (id == null) return 0;
            SquireRuntime.get().agentStore().setPrimary(id);
            c.getSource().sendFeedback(() -> Text.literal("[Squire] Selected " + StringArgumentType.getString(c, "target")), false);
            return 1;
        })));
        root.then(literal("as").then(target().then(argument("command", StringArgumentType.greedyString())
            .suggests((c,b) -> {
                String input = "squire " + b.getRemaining();
                return d.getCompletionSuggestions(d.parse(input, c.getSource())).thenApply(result -> {
                    var out = b.createOffset(b.getStart() + Math.max(0, result.getRange().getStart()-7));
                    result.getList().forEach(s -> out.suggest(s.getText()));
                    return out.build();
                });
            }).executes(c -> {
                UUID id = resolve(c);
                if (id == null) return 0;
                var player = c.getSource().getPlayerOrThrow();
                String command = StringArgumentType.getString(c, "command");
                if (command.startsWith("as ")) return 0;
                return SquireRuntime.get().agents().withTarget(player.getUuid(), id, () -> {
                    try { return d.execute("squire " + command, c.getSource()); }
                    catch (com.mojang.brigadier.exceptions.CommandSyntaxException ex) {
                        c.getSource().sendError(Text.literal(ex.getMessage())); return 0;
                    }
                });
            }))));
        root.then(literal("panel").executes(c -> panel(c, null)).then(target().executes(c -> {UUID id=resolve(c);return id==null?0:panel(c,id);})));
        var controls = literal("control");
        for (var action : SquireActions.ALL) {
            controls.then(literal(actionName(action)).executes(c -> onAgent(c, (p,a) -> {
                var gate = SquireActions.DESIGN_GATES.get(action.id());
                if (gate != null && !SquireRuntime.get().professionOf(a).can(gate)) {
                    p.sendMessage(Text.literal("[Squire] Ability locked: " + gate), false); return;
                }
                action.handler().run(p,a);
            })));
        }
        root.then(controls);
        root.then(literal("rename").then(argument("name", StringArgumentType.string()).executes(c ->
            onAgent(c, (p,a) -> SquireCommands.surface(c.getSource(), SquireRuntime.get().renameAgent(p,a,StringArgumentType.getString(c,"name")))))));
        root.then(literal("personality").then(literal("reroll").then(literal("confirm").executes(c ->
            onAgent(c,(p,a) -> SquireCommands.surface(c.getSource(), SquireRuntime.get().rerollPersonality(p,a)))))));
        root.then(literal("give").then(argument("item", StringArgumentType.word())
            .suggests((c,b) -> net.minecraft.command.CommandSource.suggestIdentifiers(net.minecraft.registry.Registries.ITEM.getIds(),b))
            .then(argument("count", IntegerArgumentType.integer(1,2304)).executes(c -> onAgent(c,(p,a) ->
                SquireCommands.surface(c.getSource(),SquireRuntime.get().giveByCommand(p,StringArgumentType.getString(c,"item"),IntegerArgumentType.getInteger(c,"count"))))))));
        root.then(literal("bell").then(literal("bind").executes(c -> onAgent(c,(p,a) ->
            SquireCommands.surface(c.getSource(),SquireRuntime.get().bindRecallBell(p,a,p.getMainHandStack())))))
            .then(literal("recall").executes(c -> {
                var p = c.getSource().getPlayerOrThrow();
                var rt=SquireRuntime.get();
                var selected=rt.agents().explicitTarget(p.getUuid()).orElseGet(() -> rt.agentStore().recordOfOwner(p.getUuid()).map(r -> r.agentId).orElse(null));
                if(selected==null)return 0;
                var result=rt.recallSelectedWithBell(p,selected);SquireCommands.surface(c.getSource(),result); return result.success()?1:0;
            })));
        root.then(literal("help").executes(c -> {
            c.getSource().sendFeedback(() -> Text.literal("/squire list | select <name/UUID> | as <name/UUID> <command> | panel | control <action>\nTab completes all available commands. Ordinary commands retain profession, resource and permission checks."),false);
            return 1;
        }).then(argument("topic",StringArgumentType.word()).suggests((c,b) -> net.minecraft.command.CommandSource.suggestMatching(
            d.getRoot().getChild("squire").getChildren().stream().map(n -> n.getName()),b)).executes(c -> {
                var node=d.getRoot().getChild("squire").getChild(StringArgumentType.getString(c,"topic"));
                if(node==null || !node.canUse(c.getSource()))return 0;
                var usage=d.getSmartUsage(node,c.getSource());
                c.getSource().sendFeedback(() -> Text.literal("/squire "+node.getName()),false);
                for(String line:usage.values())c.getSource().sendFeedback(() -> Text.literal("  "+line),false);
                return 1;
            })));
        root.then(literal("admin").requires(s -> s.hasPermissionLevel(2))
            .then(literal("health").then(argument("value", FloatArgumentType.floatArg(1)).executes(c -> onAgent(c,(p,a) -> a.setHealth(Math.min(a.getMaxHealth(),FloatArgumentType.getFloat(c,"value")))))))
            .then(literal("xp").then(argument("value", IntegerArgumentType.integer(0)).executes(c -> onAgent(c,(p,a) -> SquireRuntime.get().professionOf(a).xp = IntegerArgumentType.getInteger(c,"value")))))
            .then(literal("revive_ready").then(target().executes(c -> {
                UUID id = resolve(c); if (id == null) return 0;
                var store = SquireRuntime.get().agentStore();
                store.recordOfAgent(id).ifPresent(record -> record.reviveAvailableTick = 0); store.markDirty();
                dev.squire.SquireMod.LOGGER.info("[audit] admin={} revive_ready={}",c.getSource().getName(),id);
                c.getSource().sendFeedback(() -> Text.literal("Revival cooldown cleared: "+id),false);return 1;
            })))
            .then(literal("permission").then(argument("player_uuid", StringArgumentType.word())
                .then(argument("node", StringArgumentType.word())
                    .suggests((c,b) -> net.minecraft.command.CommandSource.suggestMatching(dev.squire.server.security.PermissionNodes.all(), b))
                    .then(literal("grant").executes(c -> updatePermissionPolicy(c, true)))
                    .then(literal("revoke").executes(c -> updatePermissionPolicy(c, false)))
                    .then(literal("reset").executes(c -> resetPermissionPolicy(c))))))
            .then(literal("spawn").then(argument("player", net.minecraft.command.argument.EntityArgumentType.player())
                .then(argument("name", StringArgumentType.string()).then(argument("profession", StringArgumentType.word())
                    .suggests((c,b) -> {b.suggest("guard");b.suggest("engineer");return b.buildFuture();})
                    .executes(c -> {
                        var p = net.minecraft.command.argument.EntityArgumentType.getPlayer(c,"player");
                        var rt = SquireRuntime.get();
                        String job = StringArgumentType.getString(c,"profession");
                        if (!dev.squire.server.profile.SquireName.validate(StringArgumentType.getString(c,"name")).valid()) return 0;
                        if (!Set.of("guard","engineer").contains(job) || rt.agentStore().recordsOfOwner(p.getUuid()).size() >= 2
                            || rt.agentStore().recordsOfOwner(p.getUuid()).stream().anyMatch(record -> record.profile.profession.profession()!=null && record.profile.profession.profession().id().equals(job))) {
                            c.getSource().sendError(Text.literal("[Squire] Invalid profession, duplicate profession, or roster full."));return 0;
                        }
                        var a = rt.summonAdditionalAt(p,p.getBlockPos());
                        rt.renameAgent(p,a,StringArgumentType.getString(c,"name"));
                        rt.agents().withTarget(p.getUuid(),a.agentId(),() -> rt.debugSetProfession(p,job,1));
                        rt.persistSnapshot(a);
                        dev.squire.SquireMod.LOGGER.info("[audit] admin={} spawn={} owner={}",c.getSource().getName(),a.agentId(),p.getUuid());
                        return 1;
                    }))))));
        CompanionOperations.add(root);
        d.register(root);
    }
    private static UUID permissionTarget(CommandContext<ServerCommandSource> c) {
        try { return UUID.fromString(StringArgumentType.getString(c,"player_uuid")); }
        catch (IllegalArgumentException bad) { c.getSource().sendError(Text.literal("Use the target player's UUID.")); return null; }
    }
    private static int updatePermissionPolicy(CommandContext<ServerCommandSource> c, boolean grant) {
        UUID target=permissionTarget(c); if(target==null)return 0;
        String node=StringArgumentType.getString(c,"node");
        if(!dev.squire.server.security.PermissionNodes.isKnown(node))return 0;
        if(grant)SquireRuntime.get().permissions().grant(target,node);
        else SquireRuntime.get().permissions().revoke(target,node);
        c.getSource().sendFeedback(() -> Text.literal("[Squire] policy " + node + " for " + target + " = " + grant), false);
        return 1;
    }
    private static int resetPermissionPolicy(CommandContext<ServerCommandSource> c) {
        UUID target=permissionTarget(c); if(target==null)return 0;
        String node=StringArgumentType.getString(c,"node");
        if(!dev.squire.server.security.PermissionNodes.isKnown(node))return 0;
        SquireRuntime.get().permissions().resetPolicy(target,node);
        c.getSource().sendFeedback(() -> Text.literal("[Squire] policy override cleared for " + node + " / " + target), false);
        return 1;
    }
    public static String actionName(SquireActions.Action action) {
        String name = action.labelKey().replace("squire.gui.", "").replace("button.", "").replace('.', '_');
        long count = SquireActions.ALL.stream().filter(a -> a.labelKey().equals(action.labelKey())).count();
        return count > 1 ? name + "_" + action.id() : name;
    }
    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<ServerCommandSource,String> target() {
        return argument("target",StringArgumentType.string()).suggests((c,b) -> {
            var p = c.getSource().getPlayer();
            if (p != null) for (var r : SquireRuntime.get().agentStore().recordsOfOwner(p.getUuid())) {
                b.suggest(StringArgumentType.escapeIfRequired(r.displayName)); b.suggest(r.agentId.toString());
            }
            return b.buildFuture();
        });
    }
    private static UUID resolve(CommandContext<ServerCommandSource> c) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        String name = StringArgumentType.getString(c,"target");
        var matches = SquireRuntime.get().agentStore().recordsOfOwner(c.getSource().getPlayerOrThrow().getUuid()).stream()
            .filter(r -> r.agentId.toString().equals(name) || r.displayName.equalsIgnoreCase(name)).toList();
        if(matches.size()!=1) {c.getSource().sendError(Text.literal("[Squire] Specify one owned companion; use UUID for duplicate names.")); return null;}
        return matches.get(0).agentId;
    }
    private static int panel(CommandContext<ServerCommandSource> c, UUID id) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        var p=c.getSource().getPlayerOrThrow(); var rt=SquireRuntime.get();
        if(id==null)id=rt.agents().explicitTarget(p.getUuid()).orElse(null);
        var a=(id==null ? rt.agents().resolveForOwner(p.getUuid()):rt.agents().resolveByAgentId(id)).orElse(null);
        if(a==null) {
            var record=id==null?rt.agentStore().recordOfOwner(p.getUuid()):rt.agentStore().recordOfAgent(id);
            if(record.isPresent()){SquireScreens.openSnapshot(p,record.get());return 1;}
            c.getSource().sendError(Text.literal("[Squire] Companion unavailable; use its recall bell."));return 0;
        }
        SquireScreens.open(p,a);return 1;
    }
    private static int onAgent(CommandContext<ServerCommandSource> c, java.util.function.BiConsumer<ServerPlayerEntity,dev.squire.server.body.avatar.AvatarEntity> action) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        var p=c.getSource().getPlayerOrThrow();var rt=SquireRuntime.get();var a=rt.agents().resolveForOwner(p.getUuid()).orElse(null);
        if(a==null){c.getSource().sendError(Text.literal("[Squire] Companion unavailable."));return 0;}
        rt.agents().withTarget(p.getUuid(),a.agentId(),() -> action.accept(p,a));rt.persistSnapshot(a);

        if(c.getSource().hasPermissionLevel(2)) dev.squire.SquireMod.LOGGER.info("[audit] player={} agent={} command={}",p.getUuid(),a.agentId(),c.getInput());
        return 1;
    }
}
