package com.trainrecruits;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Remembers a top-down colour image of every chunk the client has seen (per dimension) so the train map can
 * show terrain without any server work. Colours are Minecraft map colours with simple relief shading.
 */
public final class TerrainCache {
    public static final int WINDOW_CHUNKS = 48;

    private static final Map<String, Map<Long, int[]>> CACHE = new HashMap<>();
    private static final ArrayDeque<ChunkPos> QUEUE = new ArrayDeque<>();
    private static int refillCooldown;
    /** Bumped whenever any cached chunk changes. */
    public static volatile int version;

    private TerrainCache() {}

    public static Map<Long, int[]> forLevel(ClientLevel level) {
        return CACHE.computeIfAbsent(level.dimension().location().toString(), k -> new HashMap<>());
    }

    /** Called every client tick: scans a couple of loaded chunks around the player. */
    public static void tick(Minecraft mc) {
        ClientLevel level = mc.level;
        if (level == null || mc.player == null) {
            QUEUE.clear();
            return;
        }
        if (QUEUE.isEmpty() && --refillCooldown <= 0) {
            refillCooldown = 100;
            int r = Math.min(mc.options.getEffectiveRenderDistance(), 16);
            ChunkPos c = mc.player.chunkPosition();
            List<ChunkPos> list = new ArrayList<>();
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    list.add(new ChunkPos(c.x + dx, c.z + dz));
                }
            }
            list.sort((a, b) -> Integer.compare(
                    (a.x - c.x) * (a.x - c.x) + (a.z - c.z) * (a.z - c.z),
                    (b.x - c.x) * (b.x - c.x) + (b.z - c.z) * (b.z - c.z)));
            QUEUE.addAll(list);
        }
        for (int i = 0; i < 2 && !QUEUE.isEmpty(); i++) {
            scan(level, QUEUE.poll());
        }
    }

    private static void scan(ClientLevel level, ChunkPos cp) {
        ChunkAccess chunk = level.getChunk(cp.x, cp.z, ChunkStatus.FULL, false);
        if (chunk == null) {
            return;
        }
        int minY = level.getMinBuildHeight();
        int[] out = new int[256];
        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                int wx = cp.getMinBlockX() + lx;
                int wz = cp.getMinBlockZ() + lz;
                int h = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, lx, lz);
                int y = Math.max(h - 1, minY);
                BlockState state = chunk.getBlockState(mp.set(wx, y, wz));
                int color;
                if (!state.getFluidState().isEmpty()) {
                    int depth = 0;
                    while (depth < 14 && y - depth - 1 > minY
                            && !chunk.getBlockState(mp.set(wx, y - depth - 1, wz)).getFluidState().isEmpty()) {
                        depth++;
                    }
                    MapColor.Brightness b = depth < 2 ? MapColor.Brightness.HIGH
                            : depth < 5 ? MapColor.Brightness.NORMAL
                            : depth < 9 ? MapColor.Brightness.LOW : MapColor.Brightness.LOWEST;
                    color = MapColor.WATER.calculateRGBColor(b);
                } else {
                    MapColor mc = state.getMapColor(level, mp.set(wx, y, wz));
                    int hn = lz > 0 ? chunk.getHeight(Heightmap.Types.WORLD_SURFACE, lx, lz - 1) : h;
                    MapColor.Brightness b = h > hn ? MapColor.Brightness.HIGH
                            : h < hn ? MapColor.Brightness.LOW : MapColor.Brightness.NORMAL;
                    color = mc == MapColor.NONE ? 0 : mc.calculateRGBColor(b);
                }
                out[lz * 16 + lx] = color;
            }
        }
        Map<Long, int[]> map = forLevel(level);
        long key = ChunkPos.asLong(cp.x, cp.z);
        int[] old = map.get(key);
        if (old == null || !Arrays.equals(old, out)) {
            map.put(key, out);
            version++;
        }
    }
}
