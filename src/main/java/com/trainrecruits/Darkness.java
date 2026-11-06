package com.trainrecruits;

import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingChangeTargetEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Soldiers fear the dark like people do. Dark (light &lt;= darkLevel): they do not see far, run to the nearest light when
 * not fighting, and in a fight may step only a little out of the light. When they can neither see nor retreat they miss,
 * hit empty air or pick random targets. A soldier who walks to a LIT destination is never stopped by a dark patch on
 * the way; only standing in the dark without an order, or being ordered to a dark place, makes him turn to the light.
 */
@Mod.EventBusSubscriber(modid = TrainRecruits.MODID)
public final class Darkness {
    private static final int LIGHT_OK = 6;

    private static final class State {
        boolean confused;
        long nextSearch;
        BlockPos spot;
        long nextWarn;
    }

    private static final Map<AbstractRecruitEntity, State> STATES = new WeakHashMap<>();

    private Darkness() {}

    private static final Map<UUID, Long> LAST_LOG = new HashMap<>();
    private static final List<Projectile> PENDING = new ArrayList<>();

    private static void debug(AbstractRecruitEntity r, String what) {
        long now = r.level().getGameTime();
        Long last = LAST_LOG.get(r.getUUID());
        if (last == null || now - last > 100L) {
            LAST_LOG.put(r.getUUID(), now);
            TrainRecruits.LOGGER.info("[dark] {} {}", r.getName().getString(), what);
        }
    }

    /** A target standing in the dark (and not right next to the soldier) cannot be seen, whatever the soldier's own light is. */
    private static boolean unseen(AbstractRecruitEntity r, LivingEntity t) {
        if (blind(r) || r.distanceTo(t) <= 4.0D) {
            return false;
        }
        return light(r.level(), t.blockPosition()) <= TrainConfig.DARK_LEVEL.get();
    }

    private static int light(Level level, BlockPos p) {
        return level.getMaxLocalRawBrightness(p);
    }

    private static boolean blind(AbstractRecruitEntity r) {
        return r.hasEffect(MobEffects.NIGHT_VISION);
    }

    @SubscribeEvent
    public static void onTick(LivingEvent.LivingTickEvent event) {
        if (!(event.getEntity() instanceof AbstractRecruitEntity r) || r.level().isClientSide || !TrainConfig.DARKNESS.get()
                || !r.isAlive() || (r.tickCount + r.getId()) % 10 != 0) {
            return;
        }
        LivingEntity seen = r.getTarget();
        if (seen != null && r.getLastHurtByMob() != seen && unseen(r, seen)) {
            debug(r, "lost target " + seen.getName().getString() + ": it stands in the dark (light "
                    + light(r.level(), seen.blockPosition()) + ", dist " + (int) r.distanceTo(seen) + ")");
            r.setTarget(null);
        }
        Level level = r.level();
        State s = STATES.computeIfAbsent(r, k -> new State());
        int dark = TrainConfig.DARK_LEVEL.get();
        int here = light(level, r.blockPosition());
        if (here > dark || blind(r) || r.getVehicle() != null) {
            s.confused = false;
            return;
        }
        // lose sight of far targets
        LivingEntity target = r.getTarget();
        if (target != null && r.distanceTo(target) > sightRange(here) && r.getLastHurtByMob() != target) {
            r.setTarget(null);
            target = null;
        }
        boolean combat = target != null || (r.getLastHurtByMob() != null && r.tickCount - r.getLastHurtByMobTimestamp() < 100);
        BlockPos dest = destination(r);
        boolean destDark = dest != null && light(level, dest) <= dark;
        if (dest != null && !destDark && !combat) {
            s.confused = false; // just crossing a dark patch on the way to a lit place: keep going
            return;
        }
        BlockPos spot = findLight(r, s);
        double ds = spot == null ? Double.MAX_VALUE : Math.sqrt(r.distanceToSqr(spot.getX() + 0.5D, spot.getY(), spot.getZ() + 0.5D));
        if (combat) {
            if (ds <= TrainConfig.DARK_MARGIN.get()) {
                s.confused = false; // a little out of the light is fine in a fight
                return;
            }
            boolean cornered = target != null && r.distanceToSqr(target) < 9.0D;
            if (spot != null && !cornered) {
                s.confused = false;
                retreat(r, spot, s);
            } else {
                s.confused = true;
                flail(r, here);
            }
            return;
        }
        s.confused = false;
        if (spot != null) {
            retreat(r, spot, s);
        } else if (destDark) {
            r.getNavigation().stop(); // does not walk into a darkness it cannot see through
        }
    }

    private static double sightRange(int light) {
        return light <= TrainConfig.PITCH_LEVEL.get() ? 3.0D : 7.0D;
    }

    private static BlockPos destination(AbstractRecruitEntity r) {
        if (r.getNavigation().isInProgress()) {
            return r.getNavigation().getTargetPos();
        }
        return r.getShouldMovePos() ? r.getMovePos() : null;
    }

    private static void retreat(AbstractRecruitEntity r, BlockPos spot, State s) {
        r.getNavigation().moveTo(spot.getX() + 0.5D, spot.getY(), spot.getZ() + 0.5D, 1.15D);
        long now = r.level().getGameTime();
        Player p = r.getOwner();
        if (now >= s.nextWarn && p != null && p.distanceToSqr(r) < 1600.0D) {
            s.nextWarn = now + 200;
            p.displayClientMessage(Component.translatable("msg.trainrecruits.dark_retreat", r.getName()), true);
        }
    }

    /** Chaotic behaviour: swing at nothing, or pick a random neighbour as the target. */
    private static void flail(AbstractRecruitEntity r, int light) {
        if (r.getRandom().nextInt(100) >= 45) {
            return;
        }
        if (r.getRandom().nextBoolean()) {
            r.swing(InteractionHand.MAIN_HAND);
            r.level().playSound(null, r.blockPosition(), SoundEvents.PLAYER_ATTACK_NODAMAGE, SoundSource.NEUTRAL, 0.6F, 1.0F);
            return;
        }
        List<LivingEntity> near = r.level().getEntitiesOfClass(LivingEntity.class, new AABB(r.blockPosition()).inflate(5.0D),
                e -> e != r && e.isAlive() && !(e instanceof Player) && !r.isAlliedTo(e));
        if (!near.isEmpty()) {
            r.setTarget(near.get(r.getRandom().nextInt(near.size())));
        }
    }

    private static BlockPos findLight(AbstractRecruitEntity r, State s) {
        Level level = r.level();
        long now = level.getGameTime();
        if (s.spot != null && now < s.nextSearch && light(level, s.spot) >= LIGHT_OK) {
            return s.spot;
        }
        s.nextSearch = now + 40;
        BlockPos base = r.blockPosition();
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        for (int dx = -12; dx <= 12; dx += 3) {
            for (int dz = -12; dz <= 12; dz += 3) {
                for (int dy = -2; dy <= 2; dy++) {
                    BlockPos p = base.offset(dx, dy, dz);
                    double d = p.distSqr(base);
                    if (d >= bd || light(level, p) < LIGHT_OK || !standable(level, p)) {
                        continue;
                    }
                    bd = d;
                    best = p;
                }
            }
        }
        s.spot = best;
        return best;
    }

    private static boolean standable(Level level, BlockPos p) {
        return level.getBlockState(p).getCollisionShape(level, p).isEmpty()
                && level.getBlockState(p.above()).getCollisionShape(level, p.above()).isEmpty()
                && !level.getBlockState(p.below()).getCollisionShape(level, p.below()).isEmpty();
    }

    @SubscribeEvent
    public static void onTarget(LivingChangeTargetEvent event) {
        if (!(event.getEntity() instanceof AbstractRecruitEntity r) || r.level().isClientSide || !TrainConfig.DARKNESS.get()
                || event.getNewTarget() == null || blind(r)) {
            return;
        }
        if (r.getLastHurtByMob() != event.getNewTarget() && unseen(r, event.getNewTarget())) {
            event.setCanceled(true);
            return;
        }
        int here = light(r.level(), r.blockPosition());
        if (here <= TrainConfig.DARK_LEVEL.get() && r.distanceTo(event.getNewTarget()) > sightRange(here)
                && r.getLastHurtByMob() != event.getNewTarget()) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onHurt(LivingHurtEvent event) {
        if (!TrainConfig.DARKNESS.get() || !(event.getSource().getEntity() instanceof AbstractRecruitEntity r) || r.level().isClientSide
                || blind(r)) {
            return;
        }
        State s = STATES.get(r);
        int here = light(r.level(), r.blockPosition());
        boolean pitch = here <= TrainConfig.PITCH_LEVEL.get();
        if (s == null || !s.confused && !pitch) {
            return;
        }
        double chance = TrainConfig.DARK_MISS_PERCENT.get() * (pitch ? 1.5D : 1.0D) / 100.0D;
        if (r.getRandom().nextDouble() < chance) {
            event.setCanceled(true); // missed in the dark
            r.level().playSound(null, r.blockPosition(), SoundEvents.PLAYER_ATTACK_NODAMAGE, SoundSource.NEUTRAL, 0.6F, 0.9F);
        }
    }

    // ------------------------------------------------------------------ shooting blind (all ranged weapons)

    @SubscribeEvent
    public static void onProjectile(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide || !TrainConfig.DARKNESS.get() || !(event.getEntity() instanceof Projectile pr)) {
            return;
        }
        AbstractRecruitEntity r = pr.getOwner() instanceof AbstractRecruitEntity o ? o : null;
        if (r == null) {
            for (AbstractRecruitEntity n : event.getLevel().getEntitiesOfClass(AbstractRecruitEntity.class,
                    pr.getBoundingBox().inflate(2.5D), e -> e.isAlive() && e.getTarget() != null)) {
                r = n;
                break;
            }
        }
        if (r == null || blind(r)) {
            return;
        }
        int here = light(r.level(), r.blockPosition());
        LivingEntity t = r.getTarget();
        boolean targetDark = t != null && unseen(r, t);
        boolean selfDark = here <= TrainConfig.DARK_LEVEL.get();
        if (!targetDark && !selfDark) {
            return;
        }
        double chance = targetDark ? 0.85D : TrainConfig.DARK_MISS_PERCENT.get() * (here <= TrainConfig.PITCH_LEVEL.get() ? 1.5D : 1.0D) / 100.0D;
        if (r.getRandom().nextDouble() < chance) {
            debug(r, "shoots blind (own light " + here + ", target dark " + targetDark + "): the shot goes astray");
            synchronized (PENDING) {
                PENDING.add(pr);
            }
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        List<Projectile> work;
        synchronized (PENDING) {
            if (PENDING.isEmpty()) {
                return;
            }
            work = new ArrayList<>(PENDING);
            PENDING.clear();
        }
        for (Projectile pr : work) {
            Vec3 v = pr.getDeltaMovement();
            double speed = v.length();
            if (pr.isRemoved() || speed < 0.05D) {
                continue;
            }
            double yaw = (pr.level().getRandom().nextDouble() - 0.5D) * 2.0D * 0.5D;
            double pitch = (pr.level().getRandom().nextDouble() - 0.5D) * 2.0D * 0.25D;
            double cos = Math.cos(yaw);
            double sin = Math.sin(yaw);
            double nx = v.x * cos - v.z * sin;
            double nz = v.x * sin + v.z * cos;
            pr.shoot(nx, v.y + pitch * speed, nz, (float) speed, 0.0F);
        }
    }
}
