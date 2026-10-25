package com.trainrecruits;

import net.minecraft.network.FriendlyByteBuf;

import java.util.UUID;

/** An ordinary (non-commander) recruit of the player, as listed in the map's squad panel. */
public record RecruitEntry(UUID id, String typeKey, String customName, String dim, double x, double z,
                           UUID squad, boolean mounted) {
    public static final UUID NONE = new UUID(0L, 0L);

    public boolean assigned() {
        return !NONE.equals(squad);
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeUUID(id);
        buf.writeUtf(typeKey);
        buf.writeUtf(customName);
        buf.writeUtf(dim);
        buf.writeDouble(x);
        buf.writeDouble(z);
        buf.writeUUID(squad);
        buf.writeBoolean(mounted);
    }

    public static RecruitEntry read(FriendlyByteBuf buf) {
        return new RecruitEntry(buf.readUUID(), buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readDouble(),
                buf.readDouble(), buf.readUUID(), buf.readBoolean());
    }
}
