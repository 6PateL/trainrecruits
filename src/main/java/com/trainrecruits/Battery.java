package com.trainrecruits;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.registries.ForgeRegistries;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Simple artillery for villages: a Create Big Cannons mount with a short bronze cannon on top, assembled in code,
 * and a villager of the village as its crew. Used by villages that grew big enough, and by the debug tool.
 */
final class Battery {
    private static final String[] PARTS = {"bronze_sliding_breech", "bronze_cannon_chamber", "bronze_cannon_barrel", "bronze_cannon_barrel"};
    private static final Map<UUID, Long> LAST_TRY = new HashMap<>();

    private Battery() {}

    private static Block block(String path) {
        Block b = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("createbigcannons", path));
        return b == null || b == net.minecraft.world.level.block.Blocks.AIR ? null : b;
    }

    private static BlockState faced(Block b, Direction f) {
        BlockState s = b.defaultBlockState();
        for (Property<?> p : s.getProperties()) {
            if (p instanceof DirectionProperty dp && p.getName().equals("facing") && dp.getPossibleValues().contains(f)) {
                s = s.setValue(dp, f);
            }
        }
        return s;
    }

    /** Is there room for the gun: mount at base, cannon line above it along f. */
    private static boolean fits(ServerLevel level, BlockPos base, Direction f) {
        if (!level.hasChunkAt(base) || !level.getBlockState(base.below()).isSolid()) return false;
        if (!level.getBlockState(base).canBeReplaced() || !level.getFluidState(base).isEmpty()) return false;
        for (int i = 0; i < PARTS.length; i++) {
            BlockPos p = base.above().relative(f, i);
            if (!level.hasChunkAt(p) || !level.getBlockState(p).canBeReplaced() || !level.getFluidState(p).isEmpty()) return false;
            if (!level.getBlockState(p.above()).canBeReplaced()) return false;
        }
        return true;
    }

    /** Builds and assembles a gun with its mount at {@code base}, muzzle towards f. Returns the mount position or null. */
    static BlockPos build(ServerLevel level, BlockPos base, Direction f) {
        if (!Cbc.available()) return null;
        Block mount = block("cannon_mount");
        if (mount == null || !fits(level, base, f)) return null;
        Block[] parts = new Block[PARTS.length];
        for (int i = 0; i < PARTS.length; i++) {
            parts[i] = block(PARTS[i]);
            if (parts[i] == null) {
                TrainRecruits.LOGGER.warn("[artillery] no block createbigcannons:{}", PARTS[i]);
                return null;
            }
        }
        level.setBlock(base, mount.defaultBlockState(), 3);
        for (int i = 0; i < parts.length; i++) {
            level.setBlock(base.above().relative(f, i), faced(parts[i], f), 3);
        }
        BlockEntity be = level.getBlockEntity(base);
        if (be == null) return null;
        try {
            Method m = null;
            for (Class<?> k = be.getClass(); k != null && m == null; k = k.getSuperclass()) {
                try {
                    m = k.getDeclaredMethod("assemble");
                } catch (NoSuchMethodException ignored) {
                    // superclass
                }
            }
            if (m == null) return null;
            m.setAccessible(true);
            m.invoke(be);
        } catch (Throwable t) {
            TrainRecruits.LOGGER.warn("[artillery] could not assemble the gun at {}: {}", base, String.valueOf(t.getCause() != null ? t.getCause() : t));
            return null;
        }
        return Cbc.isUsableMount(level.getBlockEntity(base)) ? base : null;
    }

    /** Makes the villager the crew of the gun (village crew: no player owner, shells what the village is at war with). */
    static void assignCrew(ServerLevel level, Villager v, BlockPos mount, UUID faction) {
        CompoundTag d = v.getPersistentData();
        d.putBoolean(Gunners.TAG, true);
        d.putUUID(Gunners.FACTION, faction);
        d.putLong(Gunners.MOUNT, mount.asLong());
        d.putBoolean(Gunners.AUTO, true);
        if (v.position().distanceToSqr(mount.getX() + 0.5D, mount.getY(), mount.getZ() + 0.5D) > 64.0D) {
            v.teleportTo(mount.getX() + 1.5D, mount.getY(), mount.getZ() + 0.5D);
        }
        if (v.getCustomName() == null) {
            v.setCustomName(Component.literal("[A] " + v.getName().getString()));
            v.setCustomNameVisible(true);
        }
        Gunners.attachGoal(v);
    }

    static Villager crewNear(ServerLevel level, BlockPos mount, UUID faction) {
        for (Villager v : level.getEntitiesOfClass(Villager.class, new AABB(mount).inflate(24.0D, 16.0D, 24.0D))) {
            if (Gunners.isGunner(v) && v.getPersistentData().hasUUID(Gunners.FACTION)
                    && faction.equals(v.getPersistentData().getUUID(Gunners.FACTION))) {
                return v;
            }
        }
        return null;
    }

    static Villager freeVillager(ServerLevel level, BlockPos near, UUID faction) {
        Villager best = null;
        double bd = Double.MAX_VALUE;
        for (Villager v : level.getEntitiesOfClass(Villager.class, new AABB(near).inflate(48.0D, 24.0D, 48.0D))) {
            UUID f = Rv.villagerFaction(v);
            if (f == null || Gunners.isGunner(v) || v.isBaby() || (faction != null && !faction.equals(f))) continue;
            double d = v.distanceToSqr(near.getX() + 0.5D, near.getY(), near.getZ() + 0.5D);
            if (d < bd) {
                bd = d;
                best = v;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ villages grow guns

    static void tick(MinecraftServer server) {
        if (!TrainConfig.VILLAGE_GUNS.get() || !Cbc.available() || !Rv.available()) return;
        ServerLevel level = server.overworld();
        long now = level.getGameTime();
        for (Object f : Rv.villages(level)) {
            try {
                UUID id = Rv.idOf(f);
                BlockPos c = Rv.centerOf(f);
                if (!level.hasChunkAt(c) || Rv.population(f) < TrainConfig.VILLAGE_GUN_POP.get()) continue;
                BlockPos mount = Cbc.nearestMount(level, c, 40);
                if (mount != null) {
                    if (crewNear(level, mount, id) == null) {
                        Villager v = freeVillager(level, mount, id);
                        if (v != null) assignCrew(level, v, mount, id);
                    }
                    continue;
                }
                Long last = LAST_TRY.get(id);
                if (last != null && now - last < TrainConfig.VILLAGE_GUN_RETRY_MIN.get() * 1200L) continue;
                LAST_TRY.put(id, now);
                Villager crew = freeVillager(level, c, id);
                if (crew == null) continue;
                for (int i = 0; i < 24; i++) {
                    int x = c.getX() + level.random.nextInt(25) - 12;
                    int z = c.getZ() + level.random.nextInt(25) - 12;
                    if (!level.hasChunkAt(new BlockPos(x, 64, z))) continue;
                    BlockPos base = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
                    Direction f2 = Direction.Plane.HORIZONTAL.getRandomDirection(level.random);
                    BlockPos built = build(level, base, f2);
                    if (built != null) {
                        assignCrew(level, crew, built, id);
                        TrainRecruits.LOGGER.info("[artillery] village {} built a gun at {}", id, built);
                        break;
                    }
                }
            } catch (Throwable t) {
                TrainRecruits.LOGGER.warn("[artillery] village gun failed: {}", t.toString());
            }
        }
    }
}
