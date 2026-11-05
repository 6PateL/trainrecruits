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
    private static final String[] PARTS = {"bronze_quickfiring_breech", "bronze_cannon_chamber", "bronze_cannon_barrel", "bronze_cannon_barrel"};
    private static final Map<UUID, Long> LAST_TRY = new HashMap<>();

    private Battery() {}

    private static Block block(String path) {
        Block b = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("createbigcannons", path));
        return b == null || b == net.minecraft.world.level.block.Blocks.AIR ? null : b;
    }

    private static BlockState faced(Block b, Direction f) {
        BlockState s = b.defaultBlockState();
        for (Property<?> p : s.getProperties()) {
            if (p instanceof DirectionProperty dp && (p.getName().equals("facing") || p.getName().equals("horizontal_facing")) && dp.getPossibleValues().contains(f)) {
                s = s.setValue(dp, f);
            }
        }
        return s;
    }

    static final Direction[] SIDES = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};

    static boolean isPost(ServerLevel level, BlockPos p) {
        return level.isLoaded(p) && level.getBlockState(p).is(ModRegistry.ARTILLERY_POST.get());
    }

    // Layout of one gun of the post (d = direction the muzzle points, P = post):
    //   P+d   lever on the hammer side of the mount (assembles the cannon: redstone signal from behind)
    //   P+2d  cannon mount (facing d), yaw controller under it, a shaft at its side (pitch input)
    //   P+2d, 2 up: breech; the cannon lies along d at that height (the space 1 up is for the mount's arms)
    //   P+3d  button on the firing side (redstone signal from the front fires the cannon)

    static BlockPos mountOf(BlockPos post, Direction d) {
        return post.relative(d, 2);
    }

    static BlockPos leverOf(BlockPos post, Direction d) {
        return post.relative(d, 1);
    }

    static BlockPos buttonOf(BlockPos post, Direction d) {
        return post.relative(d, 3);
    }

    private static Direction.Axis pitchAxis(Direction d) {
        return d.getAxis() == Direction.Axis.X ? Direction.Axis.Z : Direction.Axis.X;
    }

    private static BlockPos shaftOf(BlockPos mount, Direction d) {
        return mount.relative(Direction.get(Direction.AxisDirection.POSITIVE, pitchAxis(d)));
    }

    private static boolean free(ServerLevel level, BlockPos p) {
        return level.hasChunkAt(p) && level.getBlockState(p).canBeReplaced() && level.getFluidState(p).isEmpty();
    }

    /** Is there room for one gun with all its parts. */
    private static boolean fitsGun(ServerLevel level, BlockPos post, Direction d) {
        BlockPos m = mountOf(post, d);
        BlockPos lever = leverOf(post, d);
        BlockPos button = buttonOf(post, d);
        for (BlockPos floor : new BlockPos[]{lever.below(), m.below(), button.below()}) {
            if (!level.hasChunkAt(floor) || !level.getBlockState(floor).isSolid()) return false;
        }
        if (!free(level, lever) || !free(level, m) || !free(level, m.above()) || !free(level, button)
                || !free(level, shaftOf(m, d))) return false;
        for (int i = 0; i < PARTS.length; i++) {
            if (!free(level, m.above(2).relative(d, i))) return false;
        }
        return true;
    }

    /** Places one gun with its controls and pulls the lever (assembly). Returns true when the mount is running. */
    private static boolean buildGun(ServerLevel level, BlockPos post, Direction d) {
        Block mount = block("cannon_mount");
        Block yaw = block("yaw_controller");
        Block shaft = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("create", "shaft"));
        if (mount == null || yaw == null || shaft == null) return false;
        Block[] parts = new Block[PARTS.length];
        for (int i = 0; i < PARTS.length; i++) {
            parts[i] = block(PARTS[i]);
            if (parts[i] == null) {
                TrainRecruits.LOGGER.warn("[artillery] no block createbigcannons:{}", PARTS[i]);
                return false;
            }
        }
        BlockPos m = mountOf(post, d);
        level.setBlock(m.below(), yaw.defaultBlockState(), 3);
        level.setBlock(m, faced(mount, d), 3);
        BlockState sh = shaft.defaultBlockState();
        if (sh.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.AXIS)) {
            sh = sh.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.AXIS, pitchAxis(d));
        }
        level.setBlock(shaftOf(m, d), sh, 3);
        for (int i = 0; i < parts.length; i++) {
            level.setBlock(m.above(2).relative(d, i), faced(parts[i], d), 3);
        }
        level.setBlock(leverOf(post, d), net.minecraft.world.level.block.Blocks.LEVER.defaultBlockState()
                .setValue(net.minecraft.world.level.block.LeverBlock.FACING, d)
                .setValue(net.minecraft.world.level.block.LeverBlock.FACE, net.minecraft.world.level.block.state.properties.AttachFace.FLOOR), 3);
        level.setBlock(buttonOf(post, d), net.minecraft.world.level.block.Blocks.STONE_BUTTON.defaultBlockState()
                .setValue(net.minecraft.world.level.block.ButtonBlock.FACING, d)
                .setValue(net.minecraft.world.level.block.ButtonBlock.FACE, net.minecraft.world.level.block.state.properties.AttachFace.FLOOR), 3);
        return activate(level, post, d);
    }

    /** The lever is pulled once: the mount gets the assembly signal. Falls back to assembling directly. */
    static boolean activate(ServerLevel level, BlockPos post, Direction d) {
        BlockPos m = mountOf(post, d);
        if (Cbc.isUsableMount(level.getBlockEntity(m))) return true;
        BlockPos lp = leverOf(post, d);
        BlockState ls = level.getBlockState(lp);
        if (ls.getBlock() instanceof net.minecraft.world.level.block.LeverBlock lv) {
            if (ls.getValue(net.minecraft.world.level.block.LeverBlock.POWERED)) {
                ls = lv.pull(ls, level, lp); // off, so that the next pull is a fresh signal
            }
            lv.pull(ls, level, lp);
        }
        if (Cbc.isUsableMount(level.getBlockEntity(m))) return true;
        BlockEntity be = level.getBlockEntity(m);
        if (be == null) return false;
        try {
            Method am = null;
            for (Class<?> k = be.getClass(); k != null && am == null; k = k.getSuperclass()) {
                try {
                    am = k.getDeclaredMethod("assemble");
                } catch (NoSuchMethodException ignored) {
                    // superclass
                }
            }
            if (am == null) return false;
            am.setAccessible(true);
            am.invoke(be);
        } catch (Throwable t) {
            TrainRecruits.LOGGER.warn("[artillery] could not assemble the gun at {}: {}", m, String.valueOf(t.getCause() != null ? t.getCause() : t));
        }
        return Cbc.isUsableMount(level.getBlockEntity(m));
    }

    /** Presses the firing button of the gun. */
    static void pressButton(ServerLevel level, BlockPos post, Direction d) {
        BlockPos bp = buttonOf(post, d);
        BlockState bs = level.getBlockState(bp);
        if (bs.getBlock() instanceof net.minecraft.world.level.block.ButtonBlock bb && !bs.getValue(net.minecraft.world.level.block.ButtonBlock.POWERED)) {
            bb.press(bs, level, bp);
        }
    }

    /**
     * Builds an artillery post: the post block in the middle and four guns around it, muzzles to N/E/S/W,
     * each with its lever, button, yaw controller and pitch shaft. Returns the post position or null.
     */
    static BlockPos buildPost(ServerLevel level, BlockPos post) {
        if (!Cbc.available() || block("cannon_mount") == null || block("yaw_controller") == null) return null;
        if (!level.hasChunkAt(post) || !level.getBlockState(post.below()).isSolid()
                || !level.getBlockState(post).canBeReplaced() || !level.getBlockState(post.above()).canBeReplaced()) return null;
        for (Direction d : SIDES) {
            if (!fitsGun(level, post, d)) return null;
        }
        level.setBlock(post, ModRegistry.ARTILLERY_POST.get().defaultBlockState(), 3);
        int ok = 0;
        for (Direction d : SIDES) {
            if (buildGun(level, post, d)) ok++;
        }
        TrainRecruits.LOGGER.info("[artillery] post at {}: {}/4 guns running", post, ok);
        return ok == 4 ? post : null;
    }

    /** One gun is built right: mount facing d, yaw controller under it, pitch shaft beside it, lever, button and the cannon. */
    static boolean gunBuilt(ServerLevel level, BlockPos post, Direction d) {
        BlockPos m = mountOf(post, d);
        if (!level.isLoaded(m)) return false;
        BlockState ms = level.getBlockState(m);
        if (ms.getBlock() != block("cannon_mount")) return false;
        for (Property<?> p : ms.getProperties()) {
            if (p instanceof DirectionProperty dp && p.getName().equals("facing") && ms.getValue(dp) != d) return false;
        }
        if (level.getBlockState(m.below()).getBlock() != block("yaw_controller")) return false;
        boolean pitch = false;
        for (int s : new int[]{1, -1}) {
            BlockState ps = level.getBlockState(m.relative(Direction.get(s > 0 ? Direction.AxisDirection.POSITIVE : Direction.AxisDirection.NEGATIVE, pitchAxis(d))));
            ResourceLocation id = ForgeRegistries.BLOCKS.getKey(ps.getBlock());
            if (id != null && id.getNamespace().equals("create") && !ps.isAir()) pitch = true;
        }
        if (!pitch) return false;
        if (!(level.getBlockState(leverOf(post, d)).getBlock() instanceof net.minecraft.world.level.block.LeverBlock)) return false;
        if (!(level.getBlockState(buttonOf(post, d)).getBlock() instanceof net.minecraft.world.level.block.ButtonBlock)) return false;
        if (Cbc.isUsableMount(level.getBlockEntity(m))) return true;
        ResourceLocation cid = ForgeRegistries.BLOCKS.getKey(level.getBlockState(m.above(2)).getBlock());
        return cid != null && cid.getNamespace().equals("createbigcannons"); // not assembled yet, the crew pulls the lever
    }

    /** The four guns around a post that are assembled and usable (null entries for missing ones). */
    static BlockPos[] gunsOf(ServerLevel level, BlockPos post) {
        BlockPos[] out = new BlockPos[4];
        for (int i = 0; i < 4; i++) {
            out[i] = gunBuilt(level, post, SIDES[i]) ? mountOf(post, SIDES[i]) : null;
        }
        return out;
    }

    /** A post works only when it is built right: all four guns assembled around it. */
    static boolean postReady(ServerLevel level, BlockPos post) {
        if (!isPost(level, post)) return false;
        for (BlockPos g : gunsOf(level, post)) {
            if (g == null) return false;
        }
        return true;
    }

    /** The gun of the post whose side points best at the target (horizontally). */
    static BlockPos pick(ServerLevel level, BlockPos post, net.minecraft.world.phys.Vec3 target) {
        BlockPos[] guns = gunsOf(level, post);
        double dx = target.x - (post.getX() + 0.5D);
        double dz = target.z - (post.getZ() + 0.5D);
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1.0E-3D) return null;
        BlockPos best = null;
        double bd = -2.0D;
        for (int i = 0; i < 4; i++) {
            if (guns[i] == null) continue;
            double dot = (SIDES[i].getStepX() * dx + SIDES[i].getStepZ() * dz) / len;
            if (dot > bd) {
                bd = dot;
                best = guns[i];
            }
        }
        return best;
    }

    /** If the mount belongs to a post (adjacent post block), the crew binds to the post instead of the single gun. */
    static BlockPos bindTarget(ServerLevel level, BlockPos mount) {
        for (Direction d : SIDES) {
            if (isPost(level, mount.relative(d, 2))) return mount.relative(d, 2);
        }
        return mount;
    }

    /** Makes the villager the crew of the gun (village crew: no player owner, shells what the village is at war with). */
    static void assignCrew(ServerLevel level, Villager v, BlockPos mount, UUID faction) {
        CompoundTag d = v.getPersistentData();
        d.putBoolean(Gunners.TAG, true);
        d.putUUID(Gunners.FACTION, faction);
        d.putLong(Gunners.MOUNT, mount.asLong());
        d.putBoolean(Gunners.AUTO, true);
        if (v.position().distanceToSqr(mount.getX() + 0.5D, mount.getY(), mount.getZ() + 0.5D) > 64.0D) {
            v.teleportTo(mount.getX() + 0.5D, mount.getY() + 1.0D, mount.getZ() + 0.5D);
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
                        if (v != null) assignCrew(level, v, bindTarget(level, mount), id);
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
                    BlockPos built = buildPost(level, base);
                    if (built != null) {
                        assignCrew(level, crew, built, id);
                        TrainRecruits.LOGGER.info("[artillery] village {} built an artillery post at {}", id, built);
                        break;
                    }
                }
            } catch (Throwable t) {
                TrainRecruits.LOGGER.warn("[artillery] village gun failed: {}", t.toString());
            }
        }
    }
}
