package com.trainrecruits;

import net.minecraft.network.FriendlyByteBuf;

import java.util.UUID;

/** A Recruits commander (and his squad) as shown on the train map. */
public record SquadEntry(UUID id, String name, String dim, double x, double z, int size, boolean riding) {
    public void write(FriendlyByteBuf buf) {
        buf.writeUUID(id);
        buf.writeUtf(name);
        buf.writeUtf(dim);
        buf.writeDouble(x);
        buf.writeDouble(z);
        buf.writeVarInt(size);
        buf.writeBoolean(riding);
    }

    public static SquadEntry read(FriendlyByteBuf buf) {
        return new SquadEntry(buf.readUUID(), buf.readUtf(), buf.readUtf(), buf.readDouble(), buf.readDouble(),
                buf.readVarInt(), buf.readBoolean());
    }
}
