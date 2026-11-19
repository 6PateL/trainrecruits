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

    private static final Map<BlockState, Integer> TEXTURE_COLOR = new HashMap<>();

    /**
     * Блоки без кольору мапи (багато модових, напр. уся плоть Flesh That Hates) малювались чорними. Беремо середній
     * колір їхньої текстури (у тому ж форматі ABGR, що й MapColor), з тією ж тінню за висотою.
     */
    private static int fallback(BlockState state, MapColor.Brightness b) {
        if (state.isAir()) {
            return 0;
        }
        Integer base = TEXTURE_COLOR.computeIfAbsent(state, TerrainCache::textureColor);
        int mod = b == MapColor.Brightness.HIGH ? 255 : b == MapColor.Brightness.LOW ? 180 : b == MapColor.Brightness.LOWEST ? 135 : 220;
        int r = (base & 0xFF) * mod / 255, g = (base >> 8 & 0xFF) * mod / 255, bl = (base >> 16 & 0xFF) * mod / 255;
        return 0xFF000000 | bl << 16 | g << 8 | r;
    }

    private static int textureColor(BlockState state) {
        try {
            net.minecraft.client.renderer.texture.TextureAtlasSprite sprite = net.minecraft.client.Minecraft.getInstance()
                    .getBlockRenderer().getBlockModelShaper().getParticleIcon(state);
            Object contents = sprite.contents();
            net.minecraft.client.renderer.texture.SpriteContents sc = (net.minecraft.client.renderer.texture.SpriteContents) contents;
            com.mojang.blaze3d.platform.NativeImage img = null;
            for (java.lang.reflect.Field f : sc.getClass().getDeclaredFields()) {
                if (f.getType() == com.mojang.blaze3d.platform.NativeImage.class) {
                    f.setAccessible(true);
                    img = (com.mojang.blaze3d.platform.NativeImage) f.get(sc);
                    break;
                }
            }
            if (img != null) {
                long r = 0, g = 0, bl = 0, n = 0;
                int w = Math.min(img.getWidth(), 16), h = Math.min(img.getHeight(), 16);
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        int abgr = img.getPixelRGBA(x, y);
                        if ((abgr >>> 24) < 128) {
                            continue;
                        }
                        r += abgr & 0xFF;
                        g += abgr >> 8 & 0xFF;
                        bl += abgr >> 16 & 0xFF;
                        n++;
                    }
                }
                if (n > 0) {
                    return (int) (bl / n) << 16 | (int) (g / n) << 8 | (int) (r / n);
                }
            }
        } catch (Throwable ignored) {
            // нижче — запасний колір
        }
        String id = String.valueOf(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()));
        boolean flesh = id.contains("flesh") || id.contains("rott");
        return flesh ? (0x22 << 16 | 0x1C << 8 | 0x8C) : (0x70 << 16 | 0x70 << 8 | 0x70); // ABGR: плоть темно-червона, решта сіра
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
                if (state.isAir()) {
                    // висота стовпця може бути хибною (напр. плоский світ із повітрям унизу, як кишеньковий острів):
                    // тоді шукаємо верхній блок самі, згори вниз
                    int top = chunk.getHighestSectionPosition() + 15;
                    for (int yy = top; yy > minY; yy--) {
                        BlockState st = chunk.getBlockState(mp.set(wx, yy, wz));
                        if (!st.isAir()) {
                            y = yy;
                            h = yy + 1;
                            state = st;
                            break;
                        }
                    }
                }
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
                    color = mc == MapColor.NONE ? fallback(state, b) : mc.calculateRGBColor(b);
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
