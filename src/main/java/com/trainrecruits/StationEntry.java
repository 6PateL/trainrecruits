package com.trainrecruits;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;

/** One station as known to the registry / the client map. */
public record StationEntry(String dim, BlockPos pos, boolean boarding, boolean auto) {
    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(dim);
        buf.writeBlockPos(pos);
        buf.writeBoolean(boarding);
        buf.writeBoolean(auto);
    }

    public static StationEntry read(FriendlyByteBuf buf) {
        return new StationEntry(buf.readUtf(), buf.readBlockPos(), buf.readBoolean(), buf.readBoolean());
    }
}
