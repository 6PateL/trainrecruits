package com.trainrecruits;

import net.minecraft.network.FriendlyByteBuf;

import java.util.UUID;

/** An artillery crew (a mobilised villager) as shown on the map (purple diamond). */
public record CrewEntry(UUID id, String name, String dim, double x, double z, boolean hasGun, boolean auto, int state) {
    public static final int IDLE = 0;
    public static final int AIMING = 1;
    public static final int FIRING = 2;

    public void write(FriendlyByteBuf buf) {
        buf.writeUUID(id);
        buf.writeUtf(name);
        buf.writeUtf(dim);
        buf.writeDouble(x);
        buf.writeDouble(z);
        buf.writeBoolean(hasGun);
        buf.writeBoolean(auto);
        buf.writeVarInt(state);
    }

    public static CrewEntry read(FriendlyByteBuf buf) {
        return new CrewEntry(buf.readUUID(), buf.readUtf(), buf.readUtf(), buf.readDouble(), buf.readDouble(),
                buf.readBoolean(), buf.readBoolean(), buf.readVarInt());
    }
}
