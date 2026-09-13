package dev.squire.server.command;

import java.util.*;
import com.mojang.brigadier.arguments.*;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import dev.squire.server.runtime.SquireRuntime;
import dev.squire.server.nlu.ItemOperation;
import static net.minecraft.server.command.CommandManager.*;

/** Parameterized operations that previously only had chat or GUI entry points. */
final class CompanionOperations {
    private CompanionOperations() {}
    interface Operation { SquireRuntime.ExecutionResult run(ServerPlayerEntity player); }
    private static int run(CommandContext<ServerCommandSource> c, Operation op) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        var p=c.getSource().getPlayerOrThrow();
        var result=op.run(p);SquireCommands.surface(c.getSource(),result);return result.success()?1:0;
    }
    private static String str(CommandContext<ServerCommandSource> c,String name) {return StringArgumentType.getString(c,name);}
    static void add(LiteralArgumentBuilder<ServerCommandSource> root) {
        root.then(literal("tool").then(argument("tool_name",StringArgumentType.word())
            .suggests((c,b) -> net.minecraft.command.CommandSource.suggestMatching(SquireRuntime.get().toolRegistry().modelVisible().stream().map(t -> t.descriptor().name()),b))
            .executes(c -> {
                var definition=SquireRuntime.get().toolRegistry().lookup(str(c,"tool_name")).orElse(null);
                if(definition==null || !definition.exposure().visibleToModel())return 0;
                c.getSource().sendFeedback(() -> net.minecraft.text.Text.literal(new com.google.gson.Gson().toJson(definition.descriptor())),false);return 1;
            }).then(argument("arguments",StringArgumentType.greedyString()).executes(c -> {
                try {
                    var json=com.google.gson.JsonParser.parseString(str(c,"arguments"));
                    if(!json.isJsonObject())throw new IllegalArgumentException("Expected a JSON object.");
                    java.lang.reflect.Type type=new com.google.gson.reflect.TypeToken<Map<String,Object>>(){}.getType();
                    Map<String,Object> arguments=new com.google.gson.Gson().fromJson(json,type);
                    return run(c,p -> SquireRuntime.get().conversations().executeStructured(p,str(c,"tool_name"),arguments));
                }catch(com.google.gson.JsonParseException | IllegalArgumentException ex) {
                    c.getSource().sendError(net.minecraft.text.Text.literal("Invalid tool arguments: "+ex.getMessage()));return 0;
                }
            }))));
        root.then(literal("shortcut").then(literal("bind").then(argument("slot",IntegerArgumentType.integer(0,7))
            .then(argument("name",StringArgumentType.string()).then(argument("entry",StringArgumentType.word())
                .suggests((c,b) -> net.minecraft.command.CommandSource.suggestMatching(dev.squire.server.gui.CommandCatalog.ENTRIES.stream().filter(e -> e.bindable()).map(e -> e.id()),b))
                .then(argument("variant",StringArgumentType.string()).suggests((c,b) -> {
                    var entry=dev.squire.server.gui.CommandCatalog.byId(str(c,"entry"));
                    if(entry!=null) entry.variants().forEach(v -> b.suggest(StringArgumentType.escapeIfRequired(v.arg())));
                    return b.buildFuture();
                }).executes(c -> run(c,p -> SquireRuntime.get().bindShortcutAt(p,IntegerArgumentType.getInteger(c,"slot"),str(c,"name"),str(c,"entry"),str(c,"variant"))))))))));
        root.then(literal("inventory").then(argument("page",IntegerArgumentType.integer(0,63))
            .then(literal("list").executes(c -> inventory(c,false)))
            .then(literal("move").then(argument("from",IntegerArgumentType.integer(0,114)).then(argument("to",IntegerArgumentType.integer(0,114))
                .then(argument("count",IntegerArgumentType.integer(1,64)).executes(c -> inventory(c,true))))))));
        root.then(literal("permission").then(argument("node",StringArgumentType.word()).suggests((c,b) -> net.minecraft.command.CommandSource.suggestMatching(dev.squire.server.gui.SquireScreenHandler.TOGGLEABLE_NODES,b))
            .then(argument("enabled",BoolArgumentType.bool()).executes(c -> {
                var p=c.getSource().getPlayerOrThrow();String node=str(c,"node");
                if(!dev.squire.server.gui.SquireScreenHandler.TOGGLEABLE_NODES.contains(node))return 0;
                if(!SquireRuntime.get().permissions().setPlayerEnabled(p.getUuid(),node,BoolArgumentType.getBool(c,"enabled"),p.hasPermissionLevel(2))) {
                    c.getSource().sendError(net.minecraft.text.Text.literal("Server policy does not allow enabling " + node));return 0;
                }
                c.getSource().sendFeedback(() -> net.minecraft.text.Text.literal(node+" = "+BoolArgumentType.getBool(c,"enabled")),false);return 1;
            }))));
        root.then(literal("summon").then(argument("head",net.minecraft.command.argument.BlockPosArgumentType.blockPos()).executes(c -> {
            var p=c.getSource().getPlayerOrThrow();var pos=net.minecraft.command.argument.BlockPosArgumentType.getBlockPos(c,"head");
            if(p.squaredDistanceTo(net.minecraft.util.math.Vec3d.ofCenter(pos))>64)return 0;
            return dev.squire.server.summon.SummoningService.tryActivate(p,pos)?1:0;
        })));
        root.then(literal("come").executes(c -> run(c,p -> SquireRuntime.get().comeToOwner(p))));
        root.then(literal("fill").then(argument("block",StringArgumentType.word()).suggests((c,b) -> net.minecraft.command.CommandSource.suggestIdentifiers(net.minecraft.registry.Registries.BLOCK.getIds(),b))
            .executes(c -> run(c,p -> SquireRuntime.get().fillSelection(p,str(c,"block"))))));
        var inspect=literal("inspect");
        for(var kind:dev.squire.server.fastpath.FastPathIntent.Inspect.Kind.values()) inspect.then(literal(kind.name().toLowerCase(Locale.ROOT))
            .executes(c -> run(c,p -> SquireRuntime.get().inspect(p,kind))));
        root.then(inspect);
        var style=literal("combat_style");
        for(var kind:dev.squire.server.combat.CombatStyle.Style.values()) style.then(literal(kind.name().toLowerCase(Locale.ROOT))
            .executes(c -> run(c,p -> SquireRuntime.get().setCombatStyle(p,kind))));
        root.then(style);
        root.then(literal("attack").executes(c -> run(c,p -> SquireRuntime.get().attackTarget(p,null,"nearby")))
            .then(argument("entity",net.minecraft.command.argument.EntityArgumentType.entity()).executes(c -> {
                var entity=net.minecraft.command.argument.EntityArgumentType.getEntity(c,"entity");
                return run(c,p -> SquireRuntime.get().attackTarget(p,entity.getUuidAsString(),entity.getName().getString()));
            })));
        root.then(literal("guard").then(argument("radius",IntegerArgumentType.integer(1,64)).then(argument("persistent",BoolArgumentType.bool())
            .executes(c -> run(c,p -> SquireRuntime.get().startGuard(p,IntegerArgumentType.getInteger(c,"radius"),BoolArgumentType.getBool(c,"persistent")))))));
        var time=literal("time");for(String value:List.of("day","noon","night","midnight")) time.then(literal(value).executes(c -> run(c,p -> SquireRuntime.get().setTime(p,value,null))));root.then(time);
        var weather=literal("weather");for(String value:List.of("clear","rain","thunder")) weather.then(literal(value).executes(c -> run(c,p -> SquireRuntime.get().setWeather(p,value))));root.then(weather);
        root.then(literal("locate").then(literal("structure").then(argument("id",StringArgumentType.word()).executes(c -> run(c,p -> SquireRuntime.get().locateStructure(p,str(c,"id"),str(c,"id"))))))
            .then(literal("biome").then(argument("id",StringArgumentType.word()).executes(c -> run(c,p -> SquireRuntime.get().locateBiome(p,str(c,"id"),str(c,"id")))))));
        root.then(literal("location").then(literal("remember").then(argument("name",StringArgumentType.string()).executes(c -> run(c,p -> SquireRuntime.get().rememberLocation(p,str(c,"name"))))))
            .then(literal("recall").then(argument("name",StringArgumentType.string()).executes(c -> run(c,p -> SquireRuntime.get().recallLocation(p,str(c,"name")))))));
        root.then(literal("teleport").then(argument("dimension",StringArgumentType.string()).then(argument("place",StringArgumentType.string()).then(argument("bring",BoolArgumentType.bool())
            .executes(c -> run(c,p -> SquireRuntime.get().teleportOwnerToPlaceOrDimension(p,str(c,"dimension"),str(c,"place"),BoolArgumentType.getBool(c,"bring"),str(c,"place"))))))));
        root.then(literal("effect").then(argument("id",StringArgumentType.word()).suggests((c,b) -> net.minecraft.command.CommandSource.suggestIdentifiers(net.minecraft.registry.Registries.STATUS_EFFECT.getIds(),b))
            .then(argument("seconds",IntegerArgumentType.integer(1,3600)).then(argument("amplifier",IntegerArgumentType.integer(0,255))
                .executes(c -> run(c,p -> SquireRuntime.get().giveEffect(p,str(c,"id"),str(c,"id"),IntegerArgumentType.getInteger(c,"seconds")*20,IntegerArgumentType.getInteger(c,"amplifier"))))))));
        var item=literal("item");
        item.then(literal("undo").executes(c -> run(c,p -> SquireRuntime.get().undoItemEdit(p))));
        var edit=literal("edit");
        for(var scope:ItemOperation.Scope.values()) {
            if(scope==ItemOperation.Scope.CONJURE)continue;
            var scopes=literal(scope.name().toLowerCase(Locale.ROOT));
            for(var kind:ItemOperation.Selector.Filter.Kind.values()) {
                var filter=literal(kind.name().toLowerCase(Locale.ROOT));
                var operations=argument("item_id",StringArgumentType.word());
                for(var transform:ItemOperation.Transform.values()) {
                    var action=literal(transform.name().toLowerCase(Locale.ROOT));
                    com.mojang.brigadier.Command<ServerCommandSource> execute=c -> run(c,p -> SquireRuntime.get().runItemOperation(p,
                        ItemOperation.edit(scope,new ItemOperation.Selector.Filter(kind,kind==ItemOperation.Selector.Filter.Kind.ITEM?str(c,"item_id"):null),transform,
                            transform.needsEnchantmentList()?List.of(str(c,"enchantment")):List.of(),"item edit")));
                    if(transform.needsEnchantmentList()) action.then(argument("enchantment",StringArgumentType.word()).suggests((c,b) -> net.minecraft.command.CommandSource.suggestIdentifiers(net.minecraft.registry.Registries.ENCHANTMENT.getIds(),b)).executes(execute));
                    else action.executes(execute);
                    if(kind==ItemOperation.Selector.Filter.Kind.ITEM) operations.then(action);else filter.then(action);
                }
                if(kind==ItemOperation.Selector.Filter.Kind.ITEM) filter.then(operations);
                scopes.then(filter);
            }
            edit.then(scopes);
        }
        item.then(edit);
        item.then(literal("equip").then(argument("item_id",StringArgumentType.word()).suggests((c,b) -> net.minecraft.command.CommandSource.suggestIdentifiers(net.minecraft.registry.Registries.ITEM.getIds(),b))
            .executes(c -> run(c,p -> SquireRuntime.get().runItemOperation(p,ItemOperation.conjureOne(str(c,"item_id"),1,ItemOperation.Transform.NONE,ItemOperation.Delivery.TO_AGENT_EQUIP,"equip"))))));
        root.then(item);
        var terrain=literal("terrain");
        for(var action:dev.squire.server.runtime.TerrainLevelingService.Action.values()) terrain.then(literal(action.name().toLowerCase(Locale.ROOT)).executes(c -> run(c,p -> {
            var rt=SquireRuntime.get();return dev.squire.server.runtime.TerrainLevelingService.act(p,rt.agents().resolveForOwner(p.getUuid()).orElse(null),action);
        })));
        root.then(terrain);
        root.then(literal("project").then(literal("confirm").executes(c -> run(c,p -> SquireRuntime.get().projectConfirm(p)))));
        root.then(literal("blueprint").then(literal("rotate").executes(c -> run(c,p -> SquireRuntime.get().blueprintRotate(p))))
            .then(literal("nudge").then(argument("forward",IntegerArgumentType.integer(-64,64)).then(argument("right",IntegerArgumentType.integer(-64,64))
                .executes(c -> run(c,p -> SquireRuntime.get().blueprintNudge(p,IntegerArgumentType.getInteger(c,"forward"),IntegerArgumentType.getInteger(c,"right"))))))));
        root.then(literal("bell").then(literal("upgrade").then(literal("confirm").executes(c -> run(c,CompanionOperations::upgradeBell)))));
    }
    private static int inventory(CommandContext<ServerCommandSource> c, boolean move) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        var p=c.getSource().getPlayerOrThrow();var rt=SquireRuntime.get();var a=rt.agents().resolveForOwner(p.getUuid()).orElse(null);
        if(a==null)return 0;
        var panel=new dev.squire.server.gui.SquireScreenHandler(-1,p.getInventory(),a.items().mainInventory(),
            new dev.squire.server.gui.AvatarEquipmentInventory(a,dev.squire.server.gui.SquireScreenHandler.EQUIPMENT_ORDER),a.backpackSlotInventory(),a);
        int page=IntegerArgumentType.getInteger(c,"page");panel.turnBackpackPage(page);
        if(panel.backpackPage()!=page)return 0;
        if(!move) {
            for(int i=0;i<panel.slots.size();i++) {
                final int index=i;var stack=panel.slots.get(i).getStack();
                c.getSource().sendFeedback(() -> net.minecraft.text.Text.literal(index+": "+stack.getCount()+" x "+stack.getName().getString()),false);
            }
            return 1;
        }
        if(!panel.canExchange(p)) {c.getSource().sendError(net.minecraft.text.Text.translatable("squire.gui.panel.remote_items"));return 0;}
        int from=IntegerArgumentType.getInteger(c,"from"),to=IntegerArgumentType.getInteger(c,"to"),count=IntegerArgumentType.getInteger(c,"count");
        if(from==to || from>=panel.slots.size() || to>=panel.slots.size())return 0;
        var source=panel.slots.get(from);var target=panel.slots.get(to);var stack=source.getStack();var destination=target.getStack();
        if(stack.getCount()<count || !source.canTakeItems(p) || !target.canInsert(stack)
            || !destination.isEmpty() && !net.minecraft.item.ItemStack.canCombine(stack,destination)
            || destination.getCount()+count>Math.min(stack.getMaxCount(),target.getMaxItemCount(stack)))return 0;
        int before=destination.getCount();
        try {
            panel.onSlotClick(from,0,net.minecraft.screen.slot.SlotActionType.PICKUP,p);
            for(int i=0;i<count;i++)panel.onSlotClick(to,1,net.minecraft.screen.slot.SlotActionType.PICKUP,p);
            if(!panel.getCursorStack().isEmpty())panel.onSlotClick(from,0,net.minecraft.screen.slot.SlotActionType.PICKUP,p);
        } finally {panel.onClosed(p);rt.persistSnapshot(a);}
        return target.getStack().getCount()-before;
    }
    private static SquireRuntime.ExecutionResult upgradeBell(ServerPlayerEntity p) {
        var bell=p.getMainHandStack();var rt=SquireRuntime.get();
        var nbt=bell.getNbt();
        if(!bell.isOf(dev.squire.server.registry.SquireItems.RECALL_BELL) || nbt==null || !nbt.containsUuid(dev.squire.server.registry.SquireItems.NBT_AGENT)) return SquireRuntime.ExecutionResult.refused("Hold a bound recall bell.");
        var record=rt.agentStore().recordOfAgent(nbt.getUuid(dev.squire.server.registry.SquireItems.NBT_AGENT)).orElse(null);
        if(record!=null && rt.agents().explicitTarget(p.getUuid()).filter(id -> !id.equals(record.agentId)).isPresent())
            return SquireRuntime.ExecutionResult.refused("Held bell belongs to a different companion than the command target.");
        var tier=dev.squire.server.registry.SquireItems.tierOf(bell);
        if(record==null || !record.ownerId.equals(p.getUuid()) || !tier.id().equals(record.bellTier) || tier.next()==null) return SquireRuntime.ExecutionResult.refused("Bell ownership or tier is invalid.");
        var cost=dev.squire.server.item.BellUpgradeRecipe.costFor(tier);
        for(var entry:cost.entrySet()) if(p.getInventory().count(entry.getKey())<entry.getValue()) return SquireRuntime.ExecutionResult.refused("Missing upgrade materials: "+cost);
        var upgraded=bell.copy();upgraded.getOrCreateNbt().putString(dev.squire.server.registry.SquireItems.NBT_UPGRADE_FROM,tier.id());
        upgraded.getOrCreateNbt().putString(dev.squire.server.registry.SquireItems.NBT_BELL_TIER,tier.next().id());
        if(!rt.commitBellUpgrade(p,upgraded)) return SquireRuntime.ExecutionResult.refused("Bell upgrade failed.");
        for(var entry:cost.entrySet()) {
            int left=entry.getValue();
            for(int i=0;i<p.getInventory().size() && left>0;i++) {
                var stack=p.getInventory().getStack(i);if(!stack.isOf(entry.getKey()))continue;
                int take=Math.min(left,stack.getCount());stack.decrement(take);left-=take;
            }
        }
        upgraded.getOrCreateNbt().remove(dev.squire.server.registry.SquireItems.NBT_UPGRADE_FROM);
        p.setStackInHand(net.minecraft.util.Hand.MAIN_HAND,upgraded);p.getInventory().markDirty();
        return new SquireRuntime.ExecutionResult(true,"feedback.bell_upgraded",null,"Bell upgraded.");
    }
}
