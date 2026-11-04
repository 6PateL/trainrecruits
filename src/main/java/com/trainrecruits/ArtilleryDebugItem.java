package com.trainrecruits;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;

import java.util.UUID;

/** Debug tool: right-click the ground - a simple gun appears and a villager of the nearest village is seated at it at once. */
public class ArtilleryDebugItem extends Item {
    public ArtilleryDebugItem(Properties p) {
        super(p);
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        if (!(ctx.getLevel() instanceof ServerLevel level) || !(ctx.getPlayer() instanceof ServerPlayer player)) {
            return InteractionResult.SUCCESS;
        }
        BlockPos base = ctx.getClickedPos().relative(ctx.getClickedFace());
        Direction f = player.getDirection();
        BlockPos mount = Battery.build(level, base, f);
        if (mount == null) {
            player.displayClientMessage(Component.translatable("msg.trainrecruits.debug_battery_fail"), true);
            return InteractionResult.FAIL;
        }
        Villager v = Battery.freeVillager(level, mount, null);
        UUID faction = v == null ? null : Rv.villagerFaction(v);
        if (v == null) {
            // no village villager around: take the nearest village and bring a villager of it
            Object best = null;
            double bd = Double.MAX_VALUE;
            for (Object o : Rv.villages(level)) {
                try {
                    double d = Rv.centerOf(o).distSqr(mount);
                    if (d < bd) {
                        bd = d;
                        best = o;
                    }
                } catch (Throwable ignored) {
                    // skip
                }
            }
            if (best != null) {
                try {
                    faction = Rv.idOf(best);
                    v = new Villager(net.minecraft.world.entity.EntityType.VILLAGER, level);
                    v.setPos(mount.getX() + 1.5D, mount.getY(), mount.getZ() + 0.5D);
                    v.getPersistentData().putUUID("RVVillagerFaction", faction);
                    level.addFreshEntity(v);
                } catch (Throwable t) {
                    v = null;
                }
            }
        }
        if (v == null || faction == null) {
            player.displayClientMessage(Component.translatable("msg.trainrecruits.debug_battery_nocrew"), true);
            return InteractionResult.SUCCESS;
        }
        Battery.assignCrew(level, v, mount, faction);
        player.displayClientMessage(Component.translatable("msg.trainrecruits.debug_battery_ok"), true);
        return InteractionResult.SUCCESS;
    }
}
