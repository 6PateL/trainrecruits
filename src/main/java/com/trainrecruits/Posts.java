package com.trainrecruits;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The artillery posts the villages built (post position -> village id). */
final class Posts extends SavedData {
    private final Map<Long, UUID> posts = new HashMap<>();

    static Posts get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(Posts::load, Posts::new, "trainrecruits_posts");
    }

    private static Posts load(CompoundTag tag) {
        Posts p = new Posts();
        for (Tag t : tag.getList("posts", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) t;
            if (c.hasUUID("v")) p.posts.put(c.getLong("p"), c.getUUID("v"));
        }
        return p;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Map.Entry<Long, UUID> e : posts.entrySet()) {
            CompoundTag c = new CompoundTag();
            c.putLong("p", e.getKey());
            c.putUUID("v", e.getValue());
            list.add(c);
        }
        tag.put("posts", list);
        return tag;
    }

    void add(BlockPos pos, UUID village) {
        posts.put(pos.asLong(), village);
        setDirty();
    }

    void remove(BlockPos pos) {
        if (posts.remove(pos.asLong()) != null) setDirty();
    }

    List<BlockPos> of(UUID village) {
        List<BlockPos> out = new ArrayList<>();
        for (Map.Entry<Long, UUID> e : posts.entrySet()) {
            if (e.getValue().equals(village)) out.add(BlockPos.of(e.getKey()));
        }
        return out;
    }

    /** Any registered post closer than r blocks (horizontally). */
    boolean near(BlockPos p, int r) {
        for (Long l : posts.keySet()) {
            BlockPos o = BlockPos.of(l);
            long dx = o.getX() - p.getX();
            long dz = o.getZ() - p.getZ();
            if (dx * dx + dz * dz < (long) r * r) return true;
        }
        return false;
    }
}
