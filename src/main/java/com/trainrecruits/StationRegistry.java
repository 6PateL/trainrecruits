package com.trainrecruits;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * World-wide list of stations per owner. Lets the Recruits world map (key M) show station markers and
 * command stations even when the player stands far away from them.
 */
public class StationRegistry extends SavedData {
    private final Map<UUID, List<StationEntry>> byOwner = new HashMap<>();

    public static StationRegistry get(MinecraftServer server) {
        return server.overworld().getDataStorage()
                .computeIfAbsent(StationRegistry::load, StationRegistry::new, "trainrecruits_stations");
    }

    public void put(UUID owner, StationEntry entry) {
        List<StationEntry> list = byOwner.computeIfAbsent(owner, k -> new ArrayList<>());
        for (int i = 0; i < list.size(); i++) {
            StationEntry e = list.get(i);
            if (e.dim().equals(entry.dim()) && e.pos().equals(entry.pos())) {
                if (!e.equals(entry)) {
                    list.set(i, entry);
                    setDirty();
                }
                return;
            }
        }
        list.add(entry);
        setDirty();
    }

    public void remove(String dim, BlockPos pos) {
        boolean changed = false;
        for (List<StationEntry> list : byOwner.values()) {
            changed |= list.removeIf(e -> e.dim().equals(dim) && e.pos().equals(pos));
        }
        if (changed) {
            setDirty();
        }
    }

    public List<StationEntry> listFor(UUID owner) {
        return new ArrayList<>(byOwner.getOrDefault(owner, List.of()));
    }

    // ------------------------------------------------------------------ nbt

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag owners = new ListTag();
        for (Map.Entry<UUID, List<StationEntry>> o : byOwner.entrySet()) {
            CompoundTag ot = new CompoundTag();
            ot.putUUID("Owner", o.getKey());
            ListTag stations = new ListTag();
            for (StationEntry e : o.getValue()) {
                CompoundTag st = new CompoundTag();
                st.putString("Dim", e.dim());
                st.put("Pos", NbtUtils.writeBlockPos(e.pos()));
                st.putBoolean("Boarding", e.boarding());
                st.putBoolean("Auto", e.auto());
                stations.add(st);
            }
            ot.put("Stations", stations);
            owners.add(ot);
        }
        tag.put("Owners", owners);
        return tag;
    }

    public static StationRegistry load(CompoundTag tag) {
        StationRegistry reg = new StationRegistry();
        ListTag owners = tag.getList("Owners", Tag.TAG_COMPOUND);
        for (int i = 0; i < owners.size(); i++) {
            CompoundTag ot = owners.getCompound(i);
            UUID owner = ot.getUUID("Owner");
            List<StationEntry> list = new ArrayList<>();
            ListTag stations = ot.getList("Stations", Tag.TAG_COMPOUND);
            for (int j = 0; j < stations.size(); j++) {
                CompoundTag st = stations.getCompound(j);
                list.add(new StationEntry(st.getString("Dim"), NbtUtils.readBlockPos(st.getCompound("Pos")),
                        st.getBoolean("Boarding"), st.getBoolean("Auto")));
            }
            reg.byOwner.put(owner, list);
        }
        return reg;
    }
}
