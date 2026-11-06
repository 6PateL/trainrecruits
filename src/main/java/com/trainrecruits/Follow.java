package com.trainrecruits;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;

import java.util.EnumSet;
import java.util.UUID;

/** A villager of your faction that walks after you (baton mode "lead"). */
final class Follow {
    static final String TAG = "trainrecruits_follow";
    static final String OWNER = "trainrecruits_follow_owner";

    private Follow() {}

    static boolean isFollowing(Villager v) {
        return v.getPersistentData().getBoolean(TAG);
    }

    static void toggle(ServerPlayer player, Villager v) {
        if (!(player.level() instanceof ServerLevel level) || !Rv.isMyVillager(level, player, v)) {
            player.displayClientMessage(Component.translatable("msg.trainrecruits.crew_not_faction"), true);
            return;
        }
        if (Gunners.isGunner(v) || Supply.isSupply(v)) {
            player.displayClientMessage(Component.translatable("msg.trainrecruits.follow_gunner"), true);
            return;
        }
        CompoundTag d = v.getPersistentData();
        if (isFollowing(v)) {
            if (d.hasUUID(OWNER) && !player.getUUID().equals(d.getUUID(OWNER))) {
                player.displayClientMessage(Component.translatable("msg.trainrecruits.crew_not_yours"), true);
                return;
            }
            release(v);
            player.displayClientMessage(Component.translatable("msg.trainrecruits.follow_stopped"), true);
            return;
        }
        d.putBoolean(TAG, true);
        d.putUUID(OWNER, player.getUUID());
        Rv.detach(v);
        if (v.getCustomName() == null) {
            v.setCustomName(Component.literal("[>] " + v.getName().getString()));
            v.setCustomNameVisible(true);
        }
        attach(v);
        player.displayClientMessage(Component.translatable("msg.trainrecruits.follow_started"), true);
    }

    static void attach(Villager v) {
        for (var g : v.goalSelector.getAvailableGoals()) {
            if (g.getGoal() instanceof FollowGoal) return;
        }
        v.goalSelector.addGoal(1, new FollowGoal(v));
        v.getBrain().removeAllBehaviors();
    }

    static void release(Villager v) {
        v.getPersistentData().remove(TAG);
        v.getPersistentData().remove(OWNER);
        v.goalSelector.removeAllGoals(g -> g instanceof FollowGoal);
        if (!Gunners.isGunner(v) && !Supply.isSupply(v)) {
            Rv.reattach(v);
            Component c = v.getCustomName();
            if (c != null && c.getString().startsWith("[>] ")) {
                v.setCustomName(null);
                v.setCustomNameVisible(false);
            }
        }
        if (v.level() instanceof ServerLevel sl) {
            v.refreshBrain(sl);
        }
    }

    private static final class FollowGoal extends Goal {
        private final Villager v;

        FollowGoal(Villager v) {
            this.v = v;
            this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        private ServerPlayer owner() {
            CompoundTag d = v.getPersistentData();
            if (!d.getBoolean(TAG) || !d.hasUUID(OWNER) || !(v.level() instanceof ServerLevel level)) return null;
            UUID id = d.getUUID(OWNER);
            ServerPlayer p = level.getServer().getPlayerList().getPlayer(id);
            return p != null && p.level() == v.level() ? p : null;
        }

        @Override
        public boolean canUse() {
            return owner() != null;
        }

        @Override
        public boolean canContinueToUse() {
            return owner() != null;
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void tick() {
            ServerPlayer o = owner();
            if (o == null) return;
            v.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            double d = v.distanceToSqr(o);
            if (d > 576.0D) {
                v.teleportTo(o.getX() + (v.getRandom().nextDouble() - 0.5D) * 3.0D, o.getY(), o.getZ() + (v.getRandom().nextDouble() - 0.5D) * 3.0D);
                v.getNavigation().stop();
            } else if (d > 16.0D) {
                if (v.tickCount % 10 == 0 || v.getNavigation().isDone()) v.getNavigation().moveTo(o, 0.7D);
            } else {
                v.getNavigation().stop();
                v.getLookControl().setLookAt(o);
            }
        }
    }
}
