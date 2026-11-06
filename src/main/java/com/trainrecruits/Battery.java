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

    // Layout (d = direction the muzzle points; M = mount; P = post, C = yaw controller):
    //   P - M - C in a line across d (post and controller right next to the mount)
    //   M-d: lever on the hammer side (assembles the cannon, pulled once and left on: a button would disassemble it again)
    //   M+d: button on the firing side
    //   the cannon (breech first) lies along d two blocks above the mount

    static BlockPos leverOf(BlockPos mount, Direction d) {
        return mount.relative(d.getOpposite());
    }

    static BlockPos buttonOf(BlockPos mount, Direction d) {
        return mount.relative(d);
    }

    private static boolean free(ServerLevel level, BlockPos p) {
        return level.hasChunkAt(p) && level.getBlockState(p).canBeReplaced() && level.getFluidState(p).isEmpty();
    }

    /** Nearest artillery post block within r blocks (null if none): a complete one is preferred. */
    static BlockPos postNear(ServerLevel level, BlockPos near, int r) {
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.betweenClosed(near.offset(-r, -4, -r), near.offset(r, 4, r))) {
            if (!isPost(level, p)) continue;
            double d = p.distSqr(near) - (postReady(level, p) ? 100000 : 0);
            if (d < bd) {
                bd = d;
                best = p.immutable();
            }
        }
        return best;
    }

    /** The mount block next to the post (null if none). */
    static BlockPos gunOf(ServerLevel level, BlockPos post) {
        Block mount = block("cannon_mount");
        for (Direction s : SIDES) {
            BlockPos m = post.relative(s);
            if (mount != null && level.isLoaded(m) && level.getBlockState(m).getBlock() == mount) return m;
        }
        return null;
    }

    static Direction facingOf(ServerLevel level, BlockPos mount) {
        BlockState ms = level.getBlockState(mount);
        for (Property<?> p : ms.getProperties()) {
            if (p instanceof DirectionProperty dp && p.getName().equals("facing")) return ms.getValue(dp);
        }
        return Direction.NORTH;
    }

    /** What is wrong with the post, as a lang key suffix (null: all fine). The same check for villages and players. */
    static String problem(ServerLevel level, BlockPos post) {
        if (!isPost(level, post)) return "no_post";
        BlockPos m = gunOf(level, post);
        if (m == null) return "no_gun";
        Direction d = facingOf(level, m);
        if (!(level.getBlockState(leverOf(m, d)).getBlock() instanceof net.minecraft.world.level.block.LeverBlock)) return "no_lever";
        if (!(level.getBlockState(buttonOf(m, d)).getBlock() instanceof net.minecraft.world.level.block.ButtonBlock)) return "no_button";
        return null;
    }

    static boolean postReady(ServerLevel level, BlockPos post) {
        return problem(level, post) == null;
    }

    /** Clears the space of the gun (any blocks, plants, trees) and makes sure there is solid ground under it. */
    private static void prepare(ServerLevel level, BlockPos post, Direction d) {
        Direction side = d.getClockWise();
        BlockPos m = post.relative(side);
        List<BlockPos> ground = new java.util.ArrayList<>(List.of(post, m, leverOf(m, d), buttonOf(m, d)));
        List<BlockPos> clear = new java.util.ArrayList<>(ground);
        clear.add(m.relative(d, 2));
        clear.add(m.relative(d, 3));
        for (BlockPos g : clear) {
            for (int y = 0; y <= 4; y++) {
                BlockPos p = g.above(y);
                BlockState st = level.getBlockState(p);
                if (!st.isAir() && st.getDestroySpeed(level, p) >= 0) level.setBlock(p, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
            }
        }
        for (BlockPos g : ground) {
            for (int y = 1; y <= 6; y++) {
                BlockPos f = g.below(y);
                if (level.getBlockState(f).isSolid()) break;
                level.setBlock(f, net.minecraft.world.level.block.Blocks.COBBLESTONE.defaultBlockState(), 3);
            }
        }
    }

    private static boolean build(ServerLevel level, BlockPos post, Direction d) {
        Block mount = block("cannon_mount");
        if (mount == null) return false;
        Block[] parts = new Block[PARTS.length];
        for (int i = 0; i < PARTS.length; i++) {
            parts[i] = block(PARTS[i]);
            if (parts[i] == null) {
                TrainRecruits.LOGGER.warn("[artillery] no block createbigcannons:{}", PARTS[i]);
                return false;
            }
        }
        prepare(level, post, d);
        Direction side = d.getClockWise();
        BlockPos m = post.relative(side);
        level.setBlock(post, ModRegistry.ARTILLERY_POST.get().defaultBlockState(), 3);
        level.setBlock(m, faced(mount, d), 3);
        for (int i = 0; i < parts.length; i++) {
            level.setBlock(m.above(2).relative(d, i), faced(parts[i], d), 3);
        }
        connectCannon(level, m, d);
        level.setBlock(leverOf(m, d), net.minecraft.world.level.block.Blocks.LEVER.defaultBlockState()
                .setValue(net.minecraft.world.level.block.LeverBlock.FACING, d)
                .setValue(net.minecraft.world.level.block.LeverBlock.FACE, net.minecraft.world.level.block.state.properties.AttachFace.FLOOR), 3);
        level.setBlock(buttonOf(m, d), net.minecraft.world.level.block.Blocks.STONE_BUTTON.defaultBlockState()
                .setValue(net.minecraft.world.level.block.ButtonBlock.FACING, d)
                .setValue(net.minecraft.world.level.block.ButtonBlock.FACE, net.minecraft.world.level.block.state.properties.AttachFace.FLOOR), 3);
        return activate(level, m, d);
    }

    /** The cannon blocks join each other (what the game does when a player places them; the block entities did not exist yet). */
    private static void connectCannon(ServerLevel level, BlockPos m, Direction d) {
        try {
            Method on = Class.forName("rbasamoyai.createbigcannons.cannons.big_cannons.BigCannonBlock")
                    .getMethod("onPlace", net.minecraft.world.level.Level.class, BlockPos.class);
            for (int i = 0; i < PARTS.length; i++) {
                on.invoke(null, level, m.above(2).relative(d, i));
            }
        } catch (Throwable t) {
            TrainRecruits.LOGGER.warn("[artillery] could not join the cannon blocks: {}", String.valueOf(t.getCause() != null ? t.getCause() : t));
        }
    }

    /** The lever is pulled once: the mount gets the assembly signal. Falls back to assembling directly. */
    static boolean activate(ServerLevel level, BlockPos m, Direction d) {
        if (Cbc.isUsableMount(level.getBlockEntity(m))) return true;
        BlockPos lp = leverOf(m, d);
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
            Throwable c = t.getCause() != null ? t.getCause() : t;
            String why = String.valueOf(c);
            try {
                Object comp = c.getClass().getMethod("getComponent").invoke(c);
                why += " -> " + comp;
            } catch (Throwable ignored) {
                // no component
            }
            TrainRecruits.LOGGER.warn("[artillery] could not assemble the gun at {}: {}", m, why);
        }
        return Cbc.isUsableMount(level.getBlockEntity(m));
    }

    /** Presses the firing button of the gun. */
    static void pressButton(ServerLevel level, BlockPos m, Direction d) {
        BlockPos bp = buttonOf(m, d);
        BlockState bs = level.getBlockState(bp);
        if (bs.getBlock() instanceof net.minecraft.world.level.block.ButtonBlock bb && !bs.getValue(net.minecraft.world.level.block.ButtonBlock.POWERED)) {
            bb.press(bs, level, bp);
        }
    }

    /** Builds the post with its gun (muzzle towards preferred if it fits, else any side). Returns the post or null. */
    static BlockPos buildPost(ServerLevel level, BlockPos post, Direction preferred) {
        if (!Cbc.available() || block("cannon_mount") == null) return null;
        if (!level.hasChunkAt(post) || !level.hasChunkAt(post.relative(Direction.NORTH, 6)) || !level.hasChunkAt(post.relative(Direction.SOUTH, 6))
                || !level.hasChunkAt(post.relative(Direction.EAST, 6)) || !level.hasChunkAt(post.relative(Direction.WEST, 6))) return null;
        List<Direction> order = new java.util.ArrayList<>(List.of(SIDES));
        if (preferred != null && preferred.getAxis().isHorizontal()) {
            order.remove(preferred);
            order.add(0, preferred);
        }
        Direction d = order.get(0);
        boolean ok = build(level, post, d);
        TrainRecruits.LOGGER.info("[artillery] post at {}, muzzle {}: {}", post, d, ok ? "running" : "assembly failed");
        return ok ? post : null;
    }


    /** If the mount belongs to a post (adjacent post block), the crew binds to the post instead of the single gun. */
    static BlockPos bindTarget(ServerLevel level, BlockPos mount) {
        for (Direction d : SIDES) {
            if (isPost(level, mount.relative(d))) return mount.relative(d);
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
        Rv.detach(v);
        if (v.position().distanceToSqr(mount.getX() + 0.5D, mount.getY(), mount.getZ() + 0.5D) > 1600.0D) {
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
        for (Villager v : level.getEntitiesOfClass(Villager.class, new AABB(near).inflate(96.0D, 48.0D, 96.0D))) {
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

    /** Takes the gun apart (the cannon is disassembled first, then everything is removed, nothing drops). */
    static void dismantle(ServerLevel level, BlockPos post) {
        BlockPos m = gunOf(level, post);
        if (m != null) {
            Direction d = facingOf(level, m);
            BlockEntity be = level.getBlockEntity(m);
            if (be != null) {
                try {
                    for (Class<?> k = be.getClass(); k != null; k = k.getSuperclass()) {
                        try {
                            Method dm = k.getDeclaredMethod("disassemble");
                            dm.setAccessible(true);
                            dm.invoke(be);
                            break;
                        } catch (NoSuchMethodException ignored) {
                            // superclass
                        }
                    }
                } catch (Throwable t) {
                    TrainRecruits.LOGGER.warn("[artillery] could not disassemble the gun at {}: {}", m, t.toString());
                }
            }
            BlockState air = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
            for (int i = 0; i < PARTS.length; i++) level.setBlock(m.above(2).relative(d, i), air, 3);
            level.setBlock(leverOf(m, d), air, 3);
            level.setBlock(buttonOf(m, d), air, 3);
            level.setBlock(m, air, 3);
        }
        level.setBlock(post, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
        TrainRecruits.LOGGER.info("[artillery] post at {} dismantled", post);
    }
}
