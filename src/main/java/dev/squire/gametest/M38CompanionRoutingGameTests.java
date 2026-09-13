package dev.squire.gametest;

import java.util.UUID;
import com.mojang.authlib.GameProfile;
import dev.squire.server.runtime.SquireRuntime;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

public final class M38CompanionRoutingGameTests implements FabricGameTest {
    @GameTest(templateName=FabricGameTest.EMPTY_STRUCTURE)
    public void playersCannotRegrantAnAdminRevokedPermission(TestContext context) {
        var world=context.getWorld();
        SquireRuntime.ensureInitialized(world.getServer());
        var rt=SquireRuntime.get();
        var owner=FakePlayer.get(world,new GameProfile(UUID.randomUUID(),"permission-owner"));
        world.spawnEntity(owner);
        var avatar=rt.summonAdditionalAt(owner,context.getAbsolutePos(new BlockPos(1,2,1)));
        String node=dev.squire.server.security.PermissionNodes.COMMAND_GIVE;
        rt.permissions().revoke(owner.getUuid(),node);
        context.assertTrue(!rt.permissions().has(owner,node),"server policy starts with the node denied");

        var dispatcher=world.getServer().getCommandManager().getDispatcher();
        var action=dev.squire.server.gui.SquireActions.byId(
            dev.squire.server.gui.SquireScreenHandler.BUTTON_PERMISSION_BASE
                +dev.squire.server.gui.SquireScreenHandler.TOGGLEABLE_NODES.indexOf(node));
        try {
            dispatcher.execute("squire control "+dev.squire.server.command.CompanionCommands.actionName(action),owner.getCommandSource());
            dispatcher.execute("squire permission "+node+" true",owner.getCommandSource());
            context.assertTrue(!rt.permissions().has(owner,node),
                "neither public permission surface may override a server revocation");
            dispatcher.execute("squire admin permission "+owner.getUuid()+" "+node+" grant",world.getServer().getCommandSource());
            context.assertTrue(rt.permissions().has(owner,node),"only the admin command may grant the node");
        } catch(Exception ex) { throw new RuntimeException(ex); }

        rt.executeControl(owner,SquireRuntime.ControlIntent.DISMISS);
        avatar.discard();
        owner.discard();
        context.complete();
    }

    @GameTest(templateName=FabricGameTest.EMPTY_STRUCTURE)
    public void professionAppearanceIsPerCompanionAndUpdatesOnChange(TestContext context) {
        var world=context.getWorld(); SquireRuntime.ensureInitialized(world.getServer()); var rt=SquireRuntime.get();
        var owner=FakePlayer.get(world,new GameProfile(UUID.randomUUID(),"skin-owner")); world.spawnEntity(owner);
        var first=rt.summonAdditionalAt(owner,context.getAbsolutePos(new BlockPos(1,2,1)));
        var second=rt.summonAdditionalAt(owner,context.getAbsolutePos(new BlockPos(4,2,1)));
        context.assertTrue(first.syncedProfession().isEmpty(),"untrained companion starts with common appearance");
        rt.profileOf(first).profession.setProfession(dev.squire.server.profession.SquireProfession.GUARD);
        rt.profileOf(second).profession.setProfession(dev.squire.server.profession.SquireProfession.ENGINEER);
        first.refreshNameplate(); second.refreshNameplate();
        context.assertTrue(first.syncedProfession().equals("guard"),"first appearance uses own profession");
        context.assertTrue(second.syncedProfession().equals("engineer"),"second appearance uses own profession");
        rt.profileOf(first).profession.setProfession(dev.squire.server.profession.SquireProfession.ENGINEER);
        context.runAtTick(3, () -> {
            context.assertTrue(first.syncedProfession().equals("engineer"),"tick catches profession changes");
            context.assertTrue(second.syncedProfession().equals("engineer"),"other companion remains unchanged");
            first.discard(); second.discard(); owner.discard(); context.complete();
        });
    }

    @GameTest(templateName=FabricGameTest.EMPTY_STRUCTURE)
    public void commandCoverageAndInventoryConservation(TestContext context) {
        var world=context.getWorld();SquireRuntime.ensureInitialized(world.getServer());var rt=SquireRuntime.get();
        var owner=FakePlayer.get(world,new GameProfile(UUID.randomUUID(),"command-owner"));world.spawnEntity(owner);
        var a=rt.summonAdditionalAt(owner,context.getAbsolutePos(new BlockPos(1,2,1)));rt.agentStore().setPrimary(a.agentId());
        owner.refreshPositionAndAngles(a.getX()+1,a.getY(),a.getZ(),0,0);
        var dispatcher=world.getServer().getCommandManager().getDispatcher();
        var root=dispatcher.getRoot().getChild("squire");
        for(var action:dev.squire.server.gui.SquireActions.ALL) {
            context.assertTrue(root.getChild("control").getChild(dev.squire.server.command.CompanionCommands.actionName(action))!=null,"every panel action has a command");
        }
        a.items().mainInventory().setStack(0,new net.minecraft.item.ItemStack(net.minecraft.item.Items.DIAMOND,3));
        try {
            int moved=dispatcher.execute("squire inventory 0 move 7 43 2",owner.getCommandSource());
            context.assertTrue(moved==2,"command transfers requested count");
            context.assertTrue(a.items().mainInventory().getStack(0).getCount()==1,"source decremented once");
            context.assertTrue(owner.getInventory().count(net.minecraft.item.Items.DIAMOND)==2,"destination gets exact count");
            owner.refreshPositionAndAngles(a.getX()+30,a.getY(),a.getZ(),0,0);
            context.assertTrue(dispatcher.execute("squire inventory 0 move 7 43 1",owner.getCommandSource())==0,"remote command refused");
            context.assertTrue(a.items().mainInventory().getStack(0).getCount()==1,"refused transfer preserves source");
        }catch(Exception ex){throw new RuntimeException(ex);}
        a.discard();owner.discard();context.complete();
    }
    @GameTest(templateName=FabricGameTest.EMPTY_STRUCTURE)
    public void namedChatAndScopedCommandsNeverChangePartner(TestContext context) {
        var world=context.getWorld();SquireRuntime.ensureInitialized(world.getServer());var rt=SquireRuntime.get();
        var owner=FakePlayer.get(world,new GameProfile(UUID.randomUUID(),"routing-owner"));world.spawnEntity(owner);
        var first=rt.summonAdditionalAt(owner,context.getAbsolutePos(new BlockPos(1,2,1)));
        var second=rt.summonAdditionalAt(owner,context.getAbsolutePos(new BlockPos(4,2,1)));
        rt.renameAgent(owner,first,"oo");rt.renameAgent(owner,second,"ii");rt.agentStore().setPrimary(second.agentId());
        first.setIdleMode();second.setIdleMode();
        dev.squire.server.input.InputGateway.acceptChat(owner,"oo 跟着我",true);
        context.assertTrue(first.mode()==dev.squire.server.body.avatar.AvatarEntity.MovementMode.FOLLOW,"oo must follow");
        context.assertTrue(second.mode()==dev.squire.server.body.avatar.AvatarEntity.MovementMode.IDLE,"ii must remain idle");
        try {world.getServer().getCommandManager().getDispatcher().execute("squire as oo stay",owner.getCommandSource());}
        catch(Exception ex){throw new RuntimeException(ex);}
        context.assertTrue(first.mode()==dev.squire.server.body.avatar.AvatarEntity.MovementMode.STAY,"targeted command must reach oo");
        context.assertTrue(rt.agents().resolveForOwner(owner.getUuid()).orElseThrow().agentId().equals(second.agentId()),"temporary target must not change selection");
        rt.permissions().grant(owner.getUuid(),dev.squire.server.security.PermissionNodes.COMMAND_GIVE);
        var fulfil=dev.squire.server.input.InputGateway.acceptChat(owner,"oo 给我16个面包",true);
        context.assertTrue(fulfil.isPresent() && fulfil.get().success(),"named item request accepted");
        context.assertTrue(rt.scheduler().liveTasks().stream().anyMatch(t -> t.agentId().equals(first.agentId())),"delivery task belongs to oo");
        context.assertTrue(rt.scheduler().liveTasks().stream().noneMatch(t -> t.agentId().equals(second.agentId())),"ii receives no delivery task");
        rt.scheduler().cancelAgent(first.agentId(),"test cleanup");
        first.discard();rt.agents().unregister(first.getUuid());
        rt.agents().withTarget(owner.getUuid(),first.agentId(),() -> context.assertTrue(rt.agents().resolveForOwner(owner.getUuid()).isEmpty(),"missing target must never resolve partner"));
        second.discard();owner.discard();context.complete();
    }
    @GameTest(templateName=FabricGameTest.EMPTY_STRUCTURE)
    public void bellMetadataAndRemoteInventoryAreIsolated(TestContext context) {
        var world=context.getWorld();SquireRuntime.ensureInitialized(world.getServer());var rt=SquireRuntime.get();
        var owner=FakePlayer.get(world,new GameProfile(UUID.randomUUID(),"bell-owner"));world.spawnEntity(owner);
        var a=rt.summonAdditionalAt(owner,context.getAbsolutePos(new BlockPos(1,2,1)));rt.renameAgent(owner,a,"oo");
        var bell=new net.minecraft.item.ItemStack(dev.squire.server.registry.SquireItems.RECALL_BELL);
        context.assertTrue(rt.bindRecallBell(owner,a,bell).success(),"bell binds");
        context.assertTrue("oo".equals(bell.getNbt().getString("SquireDisplayName")),"bell displays correct name");
        rt.renameAgent(owner,a,"renamed");rt.syncRecallBellDisplay(bell,dev.squire.server.item.BellTier.COMMON);
        context.assertTrue("renamed".equals(bell.getNbt().getString("SquireDisplayName")),"bell name refreshes");
        var panel=new dev.squire.server.gui.SquireScreenHandler(1,owner.getInventory(),a.items().mainInventory(),
            new dev.squire.server.gui.AvatarEquipmentInventory(a,dev.squire.server.gui.SquireScreenHandler.EQUIPMENT_ORDER),a.backpackSlotInventory(),a);
        owner.refreshPositionAndAngles(a.getX()+30,a.getY(),a.getZ(),0,0);
        a.items().mainInventory().setStack(0,new net.minecraft.item.ItemStack(net.minecraft.item.Items.DIAMOND,3));
        context.assertTrue(!panel.canExchange(owner),"remote inventory locked");
        panel.quickMove(owner,dev.squire.server.gui.SquireScreenHandler.EQUIPMENT_ORDER.size()+1);
        context.assertTrue(a.items().mainInventory().getStack(0).getCount()==3,"remote transfer cannot remove items");
        a.discard();owner.discard();context.complete();
    }
}
