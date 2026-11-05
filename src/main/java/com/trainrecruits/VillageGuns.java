package com.trainrecruits;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Villages grow their artillery posts like one more building: on the highest points around, or on a cobblestone
 * tower when there is no real hill. About 10% of the population (rounded up), from 12 inhabitants on.
 * A killed gunner is replaced by the nearest villager after 5 seconds; with nobody left the gun is taken apart.
 */
@Mod.EventBusSubscriber(modid = TrainRecruits.MODID)
public final class VillageGuns {
    private static final Map<UUID, Long> LAST_TRY = new HashMap<>();
    private static final List<Pending> PENDING = new ArrayList<>();
    private static final int HILL = 7;

    private record Pending(long due, BlockPos post, UUID faction) {}

    private VillageGuns() {}

    // ------------------------------------------------------------------ crew replacement

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        if (event.getEntity().level().isClientSide || !(event.getEntity() instanceof Villager v) || !Gunners.isGunner(v)) return;
        var d = v.getPersistentData();
        if (d.contains(Gunners.MOUNT) && d.hasUUID(Gunners.FACTION)) {
            PENDING.add(new Pending(v.level().getGameTime() + 100L, BlockPos.of(d.getLong(Gunners.MOUNT)), d.getUUID(Gunners.FACTION)));
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = event.getServer();
        int t = server.getTickCount();
        if (t % 20 == 0 && !PENDING.isEmpty()) processPending(server.overworld());
        if (t % 600 == 0) tick(server);
    }

    private static void processPending(ServerLevel level) {
        long now = level.getGameTime();
        PENDING.removeIf(p -> {
            if (now < p.due) return false;
            if (!level.isLoaded(p.post)) return now > p.due + 6000L; // try again when the chunk is back
            if (!Battery.isPost(level, p.post)) return true;
            if (Battery.crewNear(level, p.post, p.faction) != null) return true;
            Villager v = Battery.freeVillager(level, p.post, p.faction);
            if (v != null) {
                Battery.assignCrew(level, v, p.post, p.faction);
                TrainRecruits.LOGGER.info("[artillery] a new gunner takes the post at {}", p.post);
            } else {
                Posts.get(level).remove(p.post);
                Battery.dismantle(level, p.post);
            }
            return true;
        });
    }

    private static boolean pendingAt(BlockPos post) {
        for (Pending p : PENDING) if (p.post.equals(post)) return true;
        return false;
    }

    // ------------------------------------------------------------------ growth

    static void tick(MinecraftServer server) {
        if (!TrainConfig.VILLAGE_GUNS.get() || !Cbc.available() || !Rv.available()) return;
        ServerLevel level = server.overworld();
        Posts posts = Posts.get(level);
        long now = level.getGameTime();
        boolean built = false;
        for (Object f : Rv.villages(level)) {
            try {
                UUID id = Rv.idOf(f);
                BlockPos c = Rv.centerOf(f);
                if (!level.hasChunkAt(c)) continue;
                int alive = 0;
                for (BlockPos p : posts.of(id)) {
                    if (!level.isLoaded(p)) {
                        alive++;
                        continue;
                    }
                    if (!Battery.isPost(level, p)) {
                        posts.remove(p);
                        continue;
                    }
                    alive++;
                    if (!pendingAt(p) && Battery.crewNear(level, p, id) == null && Battery.postReady(level, p)) {
                        Villager v = Battery.freeVillager(level, p, id);
                        if (v != null) Battery.assignCrew(level, v, p, id);
                    }
                }
                int pop = Rv.population(f);
                if (built || pop < TrainConfig.VILLAGE_GUN_POP.get()) continue;
                int want = (int) Math.ceil(pop * 0.10D); // always rounded up
                if (alive >= want) continue;
                Long last = LAST_TRY.get(id);
                if (last != null && now - last < TrainConfig.VILLAGE_GUN_RETRY_MIN.get() * 1200L) continue;
                LAST_TRY.put(id, now);
                Villager crew = Battery.freeVillager(level, c, id);
                if (crew == null) continue;
                BlockPos post = site(level, c, posts);
                if (post != null) {
                    posts.add(post, id);
                    Battery.assignCrew(level, crew, post, id);
                    TrainRecruits.LOGGER.info("[artillery] village {} built an artillery post at {} ({} of {})", id, post, alive + 1, want);
                    built = true; // one building per round keeps the load low
                }
            } catch (Throwable t) {
                TrainRecruits.LOGGER.warn("[artillery] village gun failed: {}", t.toString());
            }
        }
    }

    private static int top(ServerLevel level, int x, int z) {
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
    }

    private static boolean natural(ServerLevel level, BlockPos ground) {
        BlockState s = level.getBlockState(ground);
        return level.getFluidState(ground).isEmpty() && (s.is(BlockTags.DIRT) || s.is(BlockTags.BASE_STONE_OVERWORLD)
                || s.is(BlockTags.SAND) || s.is(Blocks.GRAVEL) || s.is(Blocks.SNOW_BLOCK) || s.is(BlockTags.TERRACOTTA));
    }

    /** Highest free hill around the village, else a tower on level ground. Returns the new post or null. */
    private static BlockPos site(ServerLevel level, BlockPos c, Posts posts) {
        long sum = 0;
        int n = 0;
        for (int dx = -24; dx <= 24; dx += 8) {
            for (int dz = -24; dz <= 24; dz += 8) {
                if (!level.hasChunkAt(new BlockPos(c.getX() + dx, 64, c.getZ() + dz))) continue;
                sum += top(level, c.getX() + dx, c.getZ() + dz);
                n++;
            }
        }
        if (n == 0) return null;
        int avg = (int) (sum / n);
        List<BlockPos> hills = new ArrayList<>();
        for (int dx = -40; dx <= 40; dx += 8) {
            for (int dz = -40; dz <= 40; dz += 8) {
                int x = c.getX() + dx;
                int z = c.getZ() + dz;
                if (!level.hasChunkAt(new BlockPos(x, 64, z))) continue;
                int h = top(level, x, z);
                if (h - avg >= HILL) hills.add(new BlockPos(x, h, z));
            }
        }
        hills.sort((a, b) -> Integer.compare(b.getY(), a.getY()));
        for (BlockPos h : hills) {
            if (posts.near(h, 20) || !natural(level, h.below())) continue;
            BlockPos built = Battery.buildPost(level, h, null);
            if (built != null) return built;
        }
        // no hill (or all taken): a tower
        for (int i = 0; i < 10; i++) {
            int x = c.getX() + level.random.nextInt(41) - 20;
            int z = c.getZ() + level.random.nextInt(41) - 20;
            if (Math.abs(x - c.getX()) < 8 && Math.abs(z - c.getZ()) < 8) continue;
            if (!level.hasChunkAt(new BlockPos(x - 3, 64, z - 3)) || !level.hasChunkAt(new BlockPos(x + 3, 64, z + 3))) continue;
            int h = top(level, x, z);
            BlockPos ground = new BlockPos(x, h, z);
            if (posts.near(ground, 12) || !natural(level, ground.below()) || !levelAround(level, ground)) continue;
            return tower(level, ground);
        }
        return null;
    }

    private static boolean levelAround(ServerLevel level, BlockPos g) {
        for (int dx = -3; dx <= 3; dx += 3) {
            for (int dz = -3; dz <= 3; dz += 3) {
                if (Math.abs(top(level, g.getX() + dx, g.getZ() + dz) - g.getY()) > 1) return false;
                if (!level.getFluidState(new BlockPos(g.getX() + dx, g.getY() - 1, g.getZ() + dz)).isEmpty()) return false;
            }
        }
        return true;
    }

    /** A cobblestone pillar with a 3x3 platform on top, the post stands on the platform. */
    private static BlockPos tower(ServerLevel level, BlockPos ground) {
        int topY = ground.getY() + HILL;
        BlockState cobble = Blocks.COBBLESTONE.defaultBlockState();
        for (int y = ground.getY(); y < topY; y++) {
            level.setBlock(new BlockPos(ground.getX(), y, ground.getZ()), cobble, 3);
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                level.setBlock(new BlockPos(ground.getX() + dx, topY, ground.getZ() + dz), cobble, 3);
            }
        }
        return Battery.buildPost(level, new BlockPos(ground.getX(), topY + 1, ground.getZ()), null);
    }
}
