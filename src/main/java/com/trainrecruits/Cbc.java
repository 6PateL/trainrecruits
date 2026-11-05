package com.trainrecruits;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.Contraption;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Bridge to Create Big Cannons (reflection, no compile-time dependency) + ballistics.
 * The crew does not load the cannon: it aims the mount and spawns an endless supply of shells from the muzzle.
 */
final class Cbc {
    private static boolean ready;
    private static boolean failed;
    private static Class<?> mountClass, bigCannonContraption;
    private static Method isRunning, getContraption, setYaw, setPitch, sendData, maxElevation, maxDepression;
    private static Field cannonYaw, cannonPitch, startPos, initialOrientation;

    private Cbc() {}

    /** Aim state kept by one crew (sign calibration of the mount's yaw / pitch). */
    static final class Aim {
        double signYaw = 1.0D;
        double signPitch = 1.0D;
        double lastAzErr = Double.NaN;
        double lastElErr = Double.NaN;
        boolean moved;
        int calStage; // 0 = not calibrated, 1 = waiting for the test turn to show, 2 = calibrated
        long calTick;
        double calAz, calEl;
        float calYawCmd, calPitchCmd;
        double kYaw = 1.0D;   // world degrees of azimuth per commanded yaw degree
        double kPitch = 1.0D; // world degrees of elevation per commanded pitch degree
        int stable;
        Vec3 cacheTarget;
        Double cacheEl;
        long cacheTick = Long.MIN_VALUE / 2;
    }

    record Geo(Vec3 spawn, Vec3 dir, AbstractContraptionEntity poce) {}

    record Result(boolean feasible, boolean aligned, String reason) {}

    static boolean available() {
        if (failed || !ModList.get().isLoaded("createbigcannons")) {
            return false;
        }
        try {
            init();
            return true;
        } catch (Throwable t) {
            failed = true;
            TrainRecruits.LOGGER.warn("[artillery] cannot talk to Create Big Cannons: {}", t.toString());
            return false;
        }
    }

    private static synchronized void init() throws Exception {
        if (ready) {
            return;
        }
        String base = "rbasamoyai.createbigcannons.cannon_control.";
        mountClass = Class.forName(base + "cannon_mount.CannonMountBlockEntity");
        bigCannonContraption = Class.forName(base + "contraption.MountedBigCannonContraption");
        Class<?> abs = Class.forName(base + "contraption.AbstractMountedCannonContraption");
        Class<?> poceClass = Class.forName(base + "contraption.PitchOrientedContraptionEntity");
        isRunning = mountClass.getMethod("isRunning");
        getContraption = mountClass.getMethod("getContraption");
        setYaw = mountClass.getMethod("setYaw", float.class);
        setPitch = mountClass.getMethod("setPitch", float.class);
        sendData = findMethod(mountClass, "sendData");
        maxElevation = poceClass.getMethod("maximumElevation");
        maxDepression = poceClass.getMethod("maximumDepression");
        cannonYaw = field(mountClass, "cannonYaw");
        cannonPitch = field(mountClass, "cannonPitch");
        startPos = field(abs, "startPos");
        initialOrientation = field(abs, "initialOrientation");
        ready = true;
    }

    private static Method findMethod(Class<?> c, String name) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                Method m = k.getDeclaredMethod(name);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {
                // try the superclass
            }
        }
        return null;
    }

    private static Field field(Class<?> c, String name) throws NoSuchFieldException {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
                // try the superclass
            }
        }
        throw new NoSuchFieldException(name);
    }

    // ------------------------------------------------------------------ finding mounts

    static boolean isUsableMount(BlockEntity be) {
        if (be == null || !available() || !mountClass.isInstance(be)) {
            return false;
        }
        try {
            if (!Boolean.TRUE.equals(isRunning.invoke(be))) {
                return false;
            }
            Object poce = getContraption.invoke(be);
            return poce instanceof AbstractContraptionEntity e && e.isAlive() && bigCannonContraption.isInstance(e.getContraption());
        } catch (Throwable t) {
            return false;
        }
    }

    /** Nearest assembled big cannon mount within the radius (loaded chunks only), or null. */
    static BlockPos nearestMount(ServerLevel level, BlockPos around, int radius) {
        if (!available()) {
            return null;
        }
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        int minCx = (around.getX() - radius) >> 4;
        int maxCx = (around.getX() + radius) >> 4;
        int minCz = (around.getZ() - radius) >> 4;
        int maxCz = (around.getZ() + radius) >> 4;
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                if (!level.hasChunk(cx, cz)) {
                    continue;
                }
                for (BlockEntity be : new ArrayList<>(level.getChunk(cx, cz).getBlockEntities().values())) {
                    double d = be.getBlockPos().distSqr(around);
                    if (d <= (double) radius * radius && d < bd && isUsableMount(be)) {
                        bd = d;
                        best = be.getBlockPos();
                    }
                }
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ geometry

    static Geo geometry(BlockEntity mount) throws Exception {
        AbstractContraptionEntity poce = (AbstractContraptionEntity) getContraption.invoke(mount);
        Contraption c = poce.getContraption();
        Direction orient = (Direction) initialOrientation.get(c);
        BlockPos start = (BlockPos) startPos.get(c);
        Map<BlockPos, ?> blocks = c.getBlocks();
        BlockPos p = start;
        BlockPos last = start;
        for (int i = 0; i < 64 && blocks.containsKey(p); i++) {
            last = p;
            p = p.relative(orient);
        }
        Vec3 a = poce.toGlobalVector(Vec3.atCenterOf(start), 0.0F);
        Vec3 far = poce.toGlobalVector(Vec3.atCenterOf(start.relative(orient, 8)), 0.0F);
        Vec3 dir = far.subtract(a).normalize();
        Vec3 muzzle = poce.toGlobalVector(Vec3.atCenterOf(last), 0.0F);
        return new Geo(muzzle.add(dir.scale(1.5D)), dir, poce);
    }

    // ------------------------------------------------------------------ ballistics

    private static Vec3 step(Vec3 vel, double g, double drag) {
        double speed = vel.length();
        Vec3 v = vel;
        if (speed > 1.0E-6D) {
            v = v.subtract(vel.scale(Math.min(drag * speed, speed) / speed));
        }
        return v.add(0.0D, g, 0.0D);
    }

    /** Height error (y of the shell minus target y) where the shell crosses the target's horizontal distance. */
    private static double heightError(Vec3 spawn, double az, double el, Vec3 target, double hT) {
        double v0 = TrainConfig.GUN_MUZZLE_SPEED.get();
        double g = TrainConfig.GUN_GRAVITY.get();
        double drag = TrainConfig.GUN_DRAG.get();
        Vec3 vel = new Vec3(Math.cos(el) * Math.cos(az) * v0, Math.sin(el) * v0, Math.cos(el) * Math.sin(az) * v0);
        Vec3 pos = spawn;
        for (int i = 0; i < 1200; i++) {
            Vec3 prev = pos;
            vel = step(vel, g, drag);
            pos = pos.add(vel);
            double h = Math.hypot(pos.x - spawn.x, pos.z - spawn.z);
            if (h >= hT) {
                double hp = Math.hypot(prev.x - spawn.x, prev.z - spawn.z);
                double t = h == hp ? 0.0D : (hT - hp) / (h - hp);
                return prev.y + (pos.y - prev.y) * t - target.y;
            }
            if (pos.y < Math.min(spawn.y, target.y) - 128.0D) {
                return -1.0E6D;
            }
        }
        return -1.0E6D;
    }

    /** Does the flight path hit terrain before reaching the target area? */
    private static boolean blocked(ServerLevel level, Vec3 spawn, double az, double el, Vec3 target, double hT) {
        double v0 = TrainConfig.GUN_MUZZLE_SPEED.get();
        Vec3 vel = new Vec3(Math.cos(el) * Math.cos(az) * v0, Math.sin(el) * v0, Math.cos(el) * Math.sin(az) * v0);
        Vec3 pos = spawn;
        for (int i = 0; i < 1200; i++) {
            vel = step(vel, TrainConfig.GUN_GRAVITY.get(), TrainConfig.GUN_DRAG.get());
            pos = pos.add(vel);
            double h = Math.hypot(pos.x - spawn.x, pos.z - spawn.z);
            if (h >= hT - 8.0D || h < 3.0D) {
                if (h >= hT - 8.0D) {
                    return false;
                }
                continue;
            }
            BlockPos bp = BlockPos.containing(pos);
            if (!level.hasChunkAt(bp)) {
                return false;
            }
            BlockState s = level.getBlockState(bp);
            if (!s.getCollisionShape(level, bp).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** Elevation (radians) that puts the shell on the target, preferring the flat trajectory when it is clear. */
    static Double solveElevation(ServerLevel level, Vec3 spawn, Vec3 target, double minDeg, double maxDeg) {
        double az = Math.atan2(target.z - spawn.z, target.x - spawn.x);
        double hT = Math.hypot(target.x - spawn.x, target.z - spawn.z);
        if (hT < 2.0D) {
            return null;
        }
        List<double[]> roots = new ArrayList<>();
        double prevAng = Math.toRadians(minDeg);
        double prevErr = heightError(spawn, az, prevAng, target, hT);
        for (double deg = minDeg + 0.5D; deg <= maxDeg + 1.0E-6D; deg += 0.5D) {
            double ang = Math.toRadians(deg);
            double err = heightError(spawn, az, ang, target, hT);
            if (prevErr > -9.0E5D && err > -9.0E5D && (prevErr < 0 && err >= 0 || prevErr > 0 && err <= 0)) {
                double lo = prevAng;
                double hi = ang;
                double elo = prevErr;
                for (int i = 0; i < 14; i++) {
                    double mid = (lo + hi) / 2.0D;
                    double em = heightError(spawn, az, mid, target, hT);
                    if ((elo < 0) == (em < 0)) {
                        lo = mid;
                        elo = em;
                    } else {
                        hi = mid;
                    }
                }
                roots.add(new double[]{(lo + hi) / 2.0D, prevErr < 0 ? 0 : 1});
            }
            prevAng = ang;
            prevErr = err;
        }
        for (double[] r : roots) { // flat arc first (ascending elevation), then the high one
            if (!blocked(level, spawn, az, r[0], target, hT)) {
                return r[0];
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ aiming and firing

    private static double wrap(double a) {
        while (a > Math.PI) {
            a -= 2.0D * Math.PI;
        }
        while (a < -Math.PI) {
            a += 2.0D * Math.PI;
        }
        return a;
    }

    /** One aiming step towards the target. Result: feasible (a firing solution exists) and aligned (barrel on target). */
    static Result aim(ServerLevel level, BlockPos mountPos, Vec3 target, Aim st) {
        BlockEntity be = level.getBlockEntity(mountPos);
        if (!isUsableMount(be)) {
            return new Result(false, false, "no_gun");
        }
        try {
            Geo g = geometry(be);
            double maxElev = ((Number) maxElevation.invoke(g.poce())).doubleValue();
            double maxDep = ((Number) maxDepression.invoke(g.poce())).doubleValue();
            long now = level.getGameTime();
            if (st.cacheTarget == null || !st.cacheTarget.equals(target) || now - st.cacheTick > 200L) {
                st.cacheEl = solveElevation(level, g.spawn(), target, -maxDep, maxElev);
                st.cacheTarget = target;
                st.cacheTick = now;
            }
            Double el = st.cacheEl;
            if (el == null) {
                return new Result(false, false, "range");
            }
            double az = Math.atan2(target.z - g.spawn().z, target.x - g.spawn().x);
            double curAz = Math.atan2(g.dir().z, g.dir().x);
            double curEl = Math.asin(Mth.clamp(g.dir().y, -1.0D, 1.0D));

            float yaw = cannonYaw.getFloat(be);
            float pitch = cannonPitch.getFloat(be);

            // calibration: one test turn tells how the commanded angles map to the real direction (sign and scale)
            if (st.calStage == 0) {
                st.calAz = curAz;
                st.calEl = curEl;
                st.calYawCmd = 10.0F;
                st.calPitchCmd = pitch + 5.0F <= maxElev ? 5.0F : -5.0F;
                setYaw.invoke(be, yaw + st.calYawCmd);
                setPitch.invoke(be, pitch + st.calPitchCmd);
                if (sendData != null) sendData.invoke(be);
                st.calStage = 1;
                st.calTick = now;
                st.stable = 0;
                return new Result(true, false, "");
            }
            if (st.calStage == 1) {
                if (now - st.calTick < 10L) {
                    return new Result(true, false, "");
                }
                double dAz = Math.toDegrees(wrap(curAz - st.calAz));
                double dEl = Math.toDegrees(curEl - st.calEl);
                double ky = dAz / st.calYawCmd;
                double kp = dEl / st.calPitchCmd;
                if (Math.abs(ky) > 0.2D) st.kYaw = ky;
                if (Math.abs(kp) > 0.2D) st.kPitch = kp;
                st.calStage = 2;
                TrainRecruits.LOGGER.info("[artillery] gun calibrated: yaw x{} pitch x{}", String.format("%.2f", st.kYaw), String.format("%.2f", st.kPitch));
                return new Result(true, false, "");
            }

            double azErr = Math.toDegrees(wrap(az - curAz));
            double elErr = Math.toDegrees(el - curEl);
            boolean close = Math.abs(azErr) < 1.2D && Math.abs(elErr) < 1.2D;
            st.stable = close ? st.stable + 1 : 0;
            boolean aligned = st.stable >= 3;
            if (!close) {
                double turn = TrainConfig.GUN_TURN_SPEED.get();
                // damped, so that a lagging response does not make it overshoot
                float dy = (float) Mth.clamp(0.6D * azErr / st.kYaw, -turn, turn);
                float dp = (float) Mth.clamp(0.6D * elErr / st.kPitch, -turn, turn);
                float newPitch = Mth.clamp(pitch + dp, (float) -maxDep, (float) maxElev);
                setYaw.invoke(be, yaw + dy);
                setPitch.invoke(be, newPitch);
                if (sendData != null) sendData.invoke(be);
            }
            return new Result(true, aligned, "");
        } catch (Throwable t) {
            TrainRecruits.LOGGER.warn("[artillery] aiming failed: {}", t.toString());
            failed = true;
            return new Result(false, false, "error");
        }
    }

    private static final String[] AMMO = {"he_shell", "he_shell", "shrapnel_shell", "ap_shell", "solid_shot"};

    /** Random shell type of the endless supply (the configured one is always among the candidates). */
    static EntityType<?> randomAmmo(ServerLevel level) {
        List<EntityType<?>> list = new ArrayList<>();
        ResourceLocation cfg = ResourceLocation.tryParse(TrainConfig.GUN_AMMO.get());
        EntityType<?> c0 = cfg == null ? null : ForgeRegistries.ENTITY_TYPES.getValue(cfg);
        if (c0 != null && ForgeRegistries.ENTITY_TYPES.containsKey(cfg)) list.add(c0);
        for (String a : AMMO) {
            ResourceLocation id = new ResourceLocation("createbigcannons", a);
            if (ForgeRegistries.ENTITY_TYPES.containsKey(id)) list.add(ForgeRegistries.ENTITY_TYPES.getValue(id));
        }
        return list.isEmpty() ? null : list.get(level.random.nextInt(list.size()));
    }

    /** Spawns an endless-supply shell at the muzzle along the barrel. */
    static boolean fire(ServerLevel level, BlockPos mountPos) {
        BlockEntity be = level.getBlockEntity(mountPos);
        if (!isUsableMount(be)) {
            return false;
        }
        try {
            Geo g = geometry(be);
            EntityType<?> type = randomAmmo(level);
            if (type == null) {
                TrainRecruits.LOGGER.warn("[artillery] no ammo entity found");
                return false;
            }
            Entity shell = type.create(level);
            if (shell == null) {
                return false;
            }
            try {
                Method setFuze = shell.getClass().getMethod("setFuze", ItemStack.class);
                Item fuze = ForgeRegistries.ITEMS.getValue(new ResourceLocation("createbigcannons", "impact_fuze"));
                if (fuze != null) {
                    setFuze.invoke(shell, new ItemStack(fuze));
                }
            } catch (NoSuchMethodException ignored) {
                // not a fuzed shell
            }
            try {
                shell.getClass().getMethod("setChargePower", float.class).invoke(shell, (float) (double) TrainConfig.GUN_MUZZLE_SPEED.get());
            } catch (NoSuchMethodException ignored) {
                // fine
            }
            shell.setPos(g.spawn());
            Vec3 d = g.dir();
            if (shell instanceof Projectile p) {
                p.shoot(d.x, d.y, d.z, (float) (double) TrainConfig.GUN_MUZZLE_SPEED.get(), (float) (double) TrainConfig.GUN_SPREAD.get());
            } else {
                shell.setDeltaMovement(d.scale(TrainConfig.GUN_MUZZLE_SPEED.get()));
            }
            try {
                shell.getClass().getMethod("addUntouchableEntity", Entity.class, int.class).invoke(shell, g.poce(), 1);
            } catch (NoSuchMethodException ignored) {
                // fine
            }
            level.addFreshEntity(shell);
            Vec3 m = g.spawn();
            level.playSound(null, m.x, m.y, m.z, SoundEvents.GENERIC_EXPLODE, SoundSource.BLOCKS, 6.0F, 0.6F);
            level.sendParticles(ParticleTypes.LARGE_SMOKE, m.x, m.y, m.z, 24, 0.4D, 0.4D, 0.4D, 0.05D);
            level.sendParticles(ParticleTypes.FLAME, m.x, m.y, m.z, 10, 0.2D, 0.2D, 0.2D, 0.05D);
            return true;
        } catch (Throwable t) {
            TrainRecruits.LOGGER.warn("[artillery] firing failed: {}", t.toString());
            return false;
        }
    }
}
