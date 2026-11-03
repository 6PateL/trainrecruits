package com.trainrecruits;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * Runs a mobilised villager: stands at the bound cannon, aims it at the ordered point (or at an automatic target)
 * and fires, with endless ammunition. The villager's own brain is kept from walking him away.
 */
public class GunnerGoal extends Goal {
    private final Villager v;
    private final Cbc.Aim aim = new Cbc.Aim();
    private int t;
    private int cooldown;
    private long nextScan;
    private int lastState = -1;

    public GunnerGoal(Villager v) {
        this.v = v;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        return Gunners.isGunner(v) && !v.isSleeping();
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    private void state(int s) {
        if (s != lastState) {
            lastState = s;
            v.getPersistentData().putInt(Gunners.STATE, s);
        }
    }

    @Override
    public void tick() {
        if (!(v.level() instanceof ServerLevel level)) {
            return;
        }
        v.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        t++;
        CompoundTag d = v.getPersistentData();
        if (cooldown > 0) {
            cooldown--;
        }

        BlockPos mount = d.contains(Gunners.MOUNT) ? BlockPos.of(d.getLong(Gunners.MOUNT)) : null;
        if (mount != null && level.isLoaded(mount) && !Cbc.isUsableMount(level.getBlockEntity(mount))) {
            d.remove(Gunners.MOUNT); // the gun was dismantled
            mount = null;
        }
        if (mount == null) {
            state(CrewEntry.IDLE);
            if (t % 100 == 0) {
                BlockPos m = Cbc.nearestMount(level, v.blockPosition(), TrainConfig.GUN_BIND_RADIUS.get());
                if (m != null) {
                    d.putLong(Gunners.MOUNT, m.asLong());
                }
            }
            return;
        }

        Vec3 mc = Vec3.atCenterOf(mount);
        if (v.position().distanceTo(mc) > TrainConfig.GUN_OPERATE_REACH.get()) {
            state(CrewEntry.IDLE);
            if (t % 20 == 0) {
                v.getNavigation().moveTo(mc.x, mount.getY(), mc.z, 0.6D);
            }
            return;
        }
        v.getNavigation().stop();
        v.getLookControl().setLookAt(mc.x, mc.y + 1.0D, mc.z);

        // target: the ordered point first, otherwise an automatic one
        Vec3 target = null;
        if (d.getInt(Gunners.SHOTS) > 0 && d.contains(Gunners.TX)) {
            target = new Vec3(d.getInt(Gunners.TX) + 0.5D, d.getInt(Gunners.TY) + 0.5D, d.getInt(Gunners.TZ) + 0.5D);
        } else if (d.getBoolean(Gunners.AUTO) && level.getGameTime() >= nextScan && cooldown == 0) {
            nextScan = level.getGameTime() + 100L;
            ServerPlayer owner = level.getServer().getPlayerList().getPlayer(Gunners.owner(v));
            if (owner != null) {
                BlockPos p = Gunners.autoTarget(level, owner, mount);
                if (p != null) {
                    int y = level.hasChunkAt(p) ? level.getHeight(Heightmap.Types.MOTION_BLOCKING, p.getX(), p.getZ()) : p.getY();
                    d.putInt(Gunners.TX, p.getX());
                    d.putInt(Gunners.TY, y);
                    d.putInt(Gunners.TZ, p.getZ());
                    d.putInt(Gunners.SHOTS, TrainConfig.GUN_SHOTS_PER_ORDER.get());
                    target = new Vec3(p.getX() + 0.5D, y + 0.5D, p.getZ() + 0.5D);
                }
            }
        }
        if (target == null) {
            state(CrewEntry.IDLE);
            return;
        }

        Cbc.Result r = Cbc.aim(level, mount, target, aim);
        if (!r.feasible()) {
            ServerPlayer owner = level.getServer().getPlayerList().getPlayer(Gunners.owner(v));
            if (owner != null && !d.getBoolean(Gunners.AUTO)) {
                owner.displayClientMessage(Component.translatable("msg.trainrecruits.crew_unreachable"), true);
            }
            clearTarget(d);
            state(CrewEntry.IDLE);
            return;
        }
        state(CrewEntry.AIMING);
        if (r.aligned() && cooldown == 0) {
            if (Cbc.fire(level, mount)) {
                v.swing(InteractionHand.MAIN_HAND);
                cooldown = TrainConfig.GUN_RELOAD_SECONDS.get() * 20;
                int left = d.getInt(Gunners.SHOTS) - 1;
                d.putInt(Gunners.SHOTS, left);
                state(CrewEntry.FIRING);
                if (left <= 0) {
                    clearTarget(d);
                }
            } else {
                clearTarget(d);
            }
        }
    }

    private static void clearTarget(CompoundTag d) {
        d.remove(Gunners.TX);
        d.remove(Gunners.TY);
        d.remove(Gunners.TZ);
        d.putInt(Gunners.SHOTS, 0);
    }
}
