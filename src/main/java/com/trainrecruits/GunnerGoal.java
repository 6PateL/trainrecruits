package com.trainrecruits;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
import java.util.UUID;

/**
 * Runs a mobilised villager: stands at the bound cannon, aims it at the ordered point (or at an automatic target)
 * and fires, with endless ammunition. The villager's own brain is kept from walking him away.
 */
public class GunnerGoal extends Goal {
    private final Villager v;
    private Cbc.Aim aim = new Cbc.Aim();
    private BlockPos lastGun;
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
        if (mount != null && level.isLoaded(mount)) {
            boolean isPost = Battery.isPost(level, mount);
            if (isPost) {
                tickPost(level, d, mount);
                return;
            }
            if (!Cbc.isUsableMount(level.getBlockEntity(mount))) {
                d.remove(Gunners.MOUNT); // the single gun was dismantled
                mount = null;
            }
        }
        if (mount == null) {
            state(CrewEntry.IDLE);
            if (t % 100 == 0) {
                BlockPos m = Cbc.nearestMount(level, v.blockPosition(), TrainConfig.GUN_BIND_RADIUS.get());
                if (m != null) {
                    d.putLong(Gunners.MOUNT, Battery.bindTarget(level, m).asLong());
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

        Vec3 target = findTarget(level, d, mount);
        if (target == null) {
            state(CrewEntry.IDLE);
            return;
        }
        Cbc.Result r = Cbc.aim(level, mount, target, aim);
        if (!r.feasible()) {
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

    /** The ordered point first, otherwise an automatic target. */
    private Vec3 findTarget(ServerLevel level, CompoundTag d, BlockPos mount) {
        if (d.getInt(Gunners.SHOTS) > 0 && d.contains(Gunners.TX)) {
            return new Vec3(d.getInt(Gunners.TX) + 0.5D, d.getInt(Gunners.TY) + 0.5D, d.getInt(Gunners.TZ) + 0.5D);
        }
        if (d.getBoolean(Gunners.AUTO) && level.getGameTime() >= nextScan && cooldown == 0) {
            nextScan = level.getGameTime() + 60L;
            UUID ownerId = Gunners.owner(v);
            ServerPlayer owner = ownerId == null ? null : level.getServer().getPlayerList().getPlayer(ownerId);
            BlockPos p = null;
            if (owner != null) {
                p = Gunners.autoTarget(level, owner, mount);
            } else if (d.hasUUID(Gunners.FACTION)) {
                p = Gunners.factionTarget(level, d.getUUID(Gunners.FACTION), mount);
            }
            if (p != null) {
                int y = level.hasChunkAt(p) ? level.getHeight(Heightmap.Types.MOTION_BLOCKING, p.getX(), p.getZ()) : p.getY();
                d.putInt(Gunners.TX, p.getX());
                d.putInt(Gunners.TY, y);
                d.putInt(Gunners.TZ, p.getZ());
                d.putInt(Gunners.SHOTS, TrainConfig.GUN_SHOTS_PER_ORDER.get());
                return new Vec3(p.getX() + 0.5D, y + 0.5D, p.getZ() + 0.5D);
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ the post: the real loading / aiming / firing routine

    private static final int P_START = 0, P_TO_LEVER = 1, P_SHELL = 2, P_CHARGE = 3, P_AIM = 4, P_TO_BUTTON = 5;
    private int phase = P_START;
    private int phaseT;
    private int stuck;
    private Direction curDir;
    private int activateTries;

    private void setPhase(int p) {
        phase = p;
        phaseT = 0;
        stuck = 0;
    }

    /** Walks to the block (teleports when stuck). True when he is there. */
    private boolean walkTo(BlockPos p, double reach) {
        Vec3 c = Vec3.atBottomCenterOf(p);
        if (v.position().distanceTo(c) <= reach) {
            v.getNavigation().stop();
            stuck = 0;
            return true;
        }
        if (t % 10 == 0 || v.getNavigation().isDone()) {
            v.getNavigation().moveTo(c.x, c.y, c.z, 0.7D);
        }
        if (++stuck > 140) {
            v.teleportTo(c.x, c.y, c.z);
            stuck = 0;
        }
        return false;
    }

    private void tickPost(ServerLevel level, CompoundTag d, BlockPos post) {
        // the post holds him: he never leaves it for long
        if (!Battery.postReady(level, post)) {
            state(CrewEntry.IDLE);
            walkTo(post.above(), 2.5D);
            return;
        }
        Vec3 target = phase == P_START && cooldown > 0 ? null : findTarget(level, d, post);
        if (target == null) {
            state(CrewEntry.IDLE);
            if (phase != P_START && cooldown == 0 && d.getInt(Gunners.SHOTS) <= 0) {
                setPhase(P_START);
            }
            if (v.position().distanceTo(Vec3.atBottomCenterOf(post.above())) > 4.0D) {
                walkTo(post.above(), 2.5D);
            } else {
                v.getNavigation().stop();
            }
            return;
        }
        BlockPos gun = Battery.pick(level, post, target);
        if (gun == null) {
            clearTarget(d);
            return;
        }
        Direction dir = Direction.NORTH;
        for (Direction s : Battery.SIDES) {
            if (Battery.mountOf(post, s).equals(gun)) dir = s;
        }
        if (dir != curDir || !gun.equals(lastGun)) {
            curDir = dir;
            lastGun = gun;
            aim = new Cbc.Aim();
            setPhase(P_START);
        }
        phaseT++;
        BlockPos lever = Battery.leverOf(post, dir);
        BlockPos button = Battery.buttonOf(post, dir);
        Vec3 look = Vec3.atCenterOf(gun).add(0.0D, 1.5D, 0.0D);

        // the cannon is activated once (lever on the hammer side); if it is already running he does not touch it
        if (!Cbc.isUsableMount(level.getBlockEntity(gun))) {
            state(CrewEntry.AIMING);
            if (walkTo(lever, 1.8D)) {
                v.getLookControl().setLookAt(Vec3.atCenterOf(lever));
                if (phaseT % 30 == 0) {
                    v.swing(InteractionHand.MAIN_HAND);
                    Battery.activate(level, post, dir);
                    if (++activateTries > 4) {
                        activateTries = 0;
                        clearTarget(d);
                        cooldown = 400;
                    }
                }
            }
            return;
        }
        activateTries = 0;

        switch (phase) {
            case P_START -> {
                if (cooldown == 0) {
                    setPhase(P_TO_LEVER);
                }
            }
            case P_TO_LEVER -> {
                state(CrewEntry.AIMING);
                if (walkTo(lever, 1.8D)) {
                    setPhase(P_SHELL);
                }
            }
            case P_SHELL -> {
                state(CrewEntry.AIMING);
                v.getLookControl().setLookAt(look);
                if (phaseT % 10 == 5) {
                    v.swing(InteractionHand.MAIN_HAND);
                    level.playSound(null, lever, net.minecraft.sounds.SoundEvents.PISTON_EXTEND, net.minecraft.sounds.SoundSource.NEUTRAL, 0.8F, 0.7F);
                    level.sendParticles(net.minecraft.core.particles.ParticleTypes.CRIT, look.x, look.y - 0.8D, look.z, 4, 0.2D, 0.2D, 0.2D, 0.02D);
                }
                if (phaseT >= 40) {
                    setPhase(P_CHARGE);
                }
            }
            case P_CHARGE -> {
                state(CrewEntry.AIMING);
                v.getLookControl().setLookAt(look);
                if (phaseT % 10 == 5) {
                    v.swing(InteractionHand.MAIN_HAND);
                    level.playSound(null, lever, net.minecraft.sounds.SoundEvents.FLINTANDSTEEL_USE, net.minecraft.sounds.SoundSource.NEUTRAL, 0.8F, 0.8F);
                    level.sendParticles(net.minecraft.core.particles.ParticleTypes.SMOKE, look.x, look.y - 0.8D, look.z, 4, 0.2D, 0.2D, 0.2D, 0.01D);
                }
                if (phaseT >= 30) {
                    setPhase(P_AIM);
                }
            }
            case P_AIM -> {
                state(CrewEntry.AIMING);
                Cbc.Result r = Cbc.aim(level, gun, target, aim);
                if (!r.feasible()) {
                    clearTarget(d);
                    setPhase(P_START);
                    return;
                }
                v.getLookControl().setLookAt(look);
                if (r.aligned()) {
                    setPhase(P_TO_BUTTON);
                } else if (phaseT > 900) {
                    clearTarget(d);
                    setPhase(P_START);
                }
            }
            case P_TO_BUTTON -> {
                state(CrewEntry.AIMING);
                Cbc.Result r = Cbc.aim(level, gun, target, aim);
                if (!r.feasible()) {
                    clearTarget(d);
                    setPhase(P_START);
                    return;
                }
                if (walkTo(button, 1.8D)) {
                    v.getLookControl().setLookAt(Vec3.atCenterOf(button));
                    if (r.aligned()) {
                        v.swing(InteractionHand.MAIN_HAND);
                        Battery.pressButton(level, post, dir);
                        if (Cbc.fire(level, gun)) {
                            state(CrewEntry.FIRING);
                            int left = d.getInt(Gunners.SHOTS) - 1;
                            d.putInt(Gunners.SHOTS, left);
                            if (left <= 0) {
                                clearTarget(d);
                            }
                        } else {
                            clearTarget(d);
                        }
                        cooldown = Math.max(40, TrainConfig.GUN_RELOAD_SECONDS.get() * 20 - 140);
                        setPhase(P_START);
                    }
                } else if (phaseT > 600) {
                    setPhase(P_START);
                }
            }
            default -> setPhase(P_START);
        }
    }

    private static void clearTarget(CompoundTag d) {
        d.remove(Gunners.TX);
        d.remove(Gunners.TY);
        d.remove(Gunners.TZ);
        d.putInt(Gunners.SHOTS, 0);
    }
}
