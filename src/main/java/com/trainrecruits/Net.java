package com.trainrecruits;

import com.talhanation.recruits.entities.AbstractLeaderEntity;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import com.talhanation.recruits.util.RecruitCommanderUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

public final class Net {
    private static final String PROTOCOL = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(TrainRecruits.MODID, "main"), () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);

    public static final byte ACTION_TRIGGER = 0;
    public static final byte ACTION_TOGGLE_AUTO = 1;

    public static final byte SQUAD_MOVE = 0;
    public static final byte SQUAD_HOLD = 1;
    public static final byte SQUAD_FOLLOW = 2;

    public static final byte ASSIGN_TOGGLE = 0;
    public static final byte ASSIGN_ALL_FREE = 1;
    public static final byte ASSIGN_CLEAR = 2;

    private Net() {}

    public static void register() {
        int id = 0;
        CHANNEL.registerMessage(id++, RequestStations.class, RequestStations::encode, RequestStations::decode, RequestStations::handle);
        CHANNEL.registerMessage(id++, StationAction.class, StationAction::encode, StationAction::decode, StationAction::handle);
        CHANNEL.registerMessage(id++, StationList.class, StationList::encode, StationList::decode, StationList::handle);
        CHANNEL.registerMessage(id++, RequestSquads.class, RequestSquads::encode, RequestSquads::decode, RequestSquads::handle);
        CHANNEL.registerMessage(id++, SquadList.class, SquadList::encode, SquadList::decode, SquadList::handle);
        CHANNEL.registerMessage(id++, SquadCommand.class, SquadCommand::encode, SquadCommand::decode, SquadCommand::handle);
        CHANNEL.registerMessage(id++, RecruitList.class, RecruitList::encode, RecruitList::decode, RecruitList::handle);
        CHANNEL.registerMessage(id++, SquadAssign.class, SquadAssign::encode, SquadAssign::decode, SquadAssign::handle);
    }

    public static void sendSquads(ServerPlayer player) {
        List<AbstractRecruitEntity> commanders = new ArrayList<>();
        List<String> commanderDims = new ArrayList<>();
        List<RecruitEntry> recruits = new ArrayList<>();
        Map<UUID, Integer> counts = new HashMap<>();
        java.util.Set<UUID> commanderIds = new java.util.HashSet<>();

        for (ServerLevel level : player.server.getAllLevels()) {
            String dim = level.dimension().location().toString();
            for (Entity e : level.getAllEntities()) {
                if (e instanceof AbstractRecruitEntity r && r.isAlive() && player.getUUID().equals(r.getOwnerUUID())
                        && Commanders.isCommander(r)) {
                    commanders.add(r);
                    commanderDims.add(dim);
                    commanderIds.add(r.getUUID());
                }
            }
        }
        for (ServerLevel level : player.server.getAllLevels()) {
            String dim = level.dimension().location().toString();
            for (Entity e : level.getAllEntities()) {
                if (!(e instanceof AbstractRecruitEntity r) || !r.isAlive()
                        || !player.getUUID().equals(r.getOwnerUUID()) || Commanders.isCommander(r)) {
                    continue;
                }
                UUID squad = Commanders.squadOf(r);
                if (squad == null || !commanderIds.contains(squad)) {
                    squad = RecruitEntry.NONE;
                } else {
                    counts.merge(squad, 1, Integer::sum);
                }
                Component custom = r.getCustomName();
                recruits.add(new RecruitEntry(r.getUUID(), r.getType().getDescriptionId(),
                        custom == null ? "" : custom.getString(), dim, r.getX(), r.getZ(), squad,
                        r.isPassenger() && !Commanders.onTrain(r)));
            }
        }
        List<SquadEntry> squads = new ArrayList<>();
        for (int i = 0; i < commanders.size(); i++) {
            AbstractRecruitEntity cmd = commanders.get(i);
            int size = counts.getOrDefault(cmd.getUUID(), 0);
            if (cmd instanceof AbstractLeaderEntity leader && leader.army != null) {
                size += leader.army.size();
            }
            squads.add(new SquadEntry(cmd.getUUID(), cmd.getName().getString(), commanderDims.get(i),
                    cmd.getX(), cmd.getZ(), size, Commanders.onTrain(cmd)));
        }
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new SquadList(squads));
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new RecruitList(recruits));
    }

    public static void sendList(ServerPlayer player) {
        List<StationEntry> list = StationRegistry.get(player.server).listFor(player.getUUID());
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new StationList(list));
    }

    // ---------------------------------------------------------------- client -> server: "send me my stations"
    public record RequestStations() {
        static void encode(RequestStations m, FriendlyByteBuf buf) {}

        static RequestStations decode(FriendlyByteBuf buf) {
            return new RequestStations();
        }

        static void handle(RequestStations m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> {
                ServerPlayer player = c.getSender();
                if (player != null) {
                    sendList(player);
                }
            });
            c.setPacketHandled(true);
        }
    }

    // ---------------------------------------------------------------- client -> server: command a station
    public record StationAction(BlockPos pos, byte action) {
        static void encode(StationAction m, FriendlyByteBuf buf) {
            buf.writeBlockPos(m.pos);
            buf.writeByte(m.action);
        }

        static StationAction decode(FriendlyByteBuf buf) {
            return new StationAction(buf.readBlockPos(), buf.readByte());
        }

        static void handle(StationAction m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> {
                ServerPlayer player = c.getSender();
                if (player == null) {
                    return;
                }
                ServerLevel level = player.serverLevel();
                if (!level.isLoaded(m.pos) || !(level.getBlockEntity(m.pos) instanceof StationBlockEntity be)) {
                    player.sendSystemMessage(Component.translatable("msg.trainrecruits.station_unloaded"));
                    return;
                }
                be.command(player, m.action == ACTION_TOGGLE_AUTO);
                sendList(player);
            });
            c.setPacketHandled(true);
        }
    }

    // ---------------------------------------------------------------- server -> client: the list
    public record StationList(List<StationEntry> stations) {
        static void encode(StationList m, FriendlyByteBuf buf) {
            buf.writeVarInt(m.stations.size());
            for (StationEntry e : m.stations) {
                e.write(buf);
            }
        }

        static StationList decode(FriendlyByteBuf buf) {
            int n = buf.readVarInt();
            List<StationEntry> list = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                list.add(StationEntry.read(buf));
            }
            return new StationList(list);
        }

        static void handle(StationList m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientStations.set(m.stations)));
            c.setPacketHandled(true);
        }
    }

    // ---------------------------------------------------------------- squads
    public record RequestSquads() {
        static void encode(RequestSquads m, FriendlyByteBuf buf) {}

        static RequestSquads decode(FriendlyByteBuf buf) {
            return new RequestSquads();
        }

        static void handle(RequestSquads m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> {
                ServerPlayer player = c.getSender();
                if (player != null) {
                    sendSquads(player);
                }
            });
            c.setPacketHandled(true);
        }
    }

    public record SquadList(List<SquadEntry> squads) {
        static void encode(SquadList m, FriendlyByteBuf buf) {
            buf.writeVarInt(m.squads.size());
            for (SquadEntry e : m.squads) {
                e.write(buf);
            }
        }

        static SquadList decode(FriendlyByteBuf buf) {
            int n = buf.readVarInt();
            List<SquadEntry> list = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                list.add(SquadEntry.read(buf));
            }
            return new SquadList(list);
        }

        static void handle(SquadList m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientStations.setSquads(m.squads)));
            c.setPacketHandled(true);
        }
    }

    /** Order for a commander's whole squad: go to a point / hold position / follow the owner. */
    public record SquadCommand(UUID leader, byte action, BlockPos pos) {
        static void encode(SquadCommand m, FriendlyByteBuf buf) {
            buf.writeUUID(m.leader);
            buf.writeByte(m.action);
            buf.writeBlockPos(m.pos);
        }

        static SquadCommand decode(FriendlyByteBuf buf) {
            return new SquadCommand(buf.readUUID(), buf.readByte(), buf.readBlockPos());
        }

        static void handle(SquadCommand m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> {
                ServerPlayer player = c.getSender();
                if (player == null) {
                    return;
                }
                AbstractRecruitEntity cmd = Commanders.find(player.server, m.leader);
                if (cmd == null || !cmd.isAlive() || !player.getUUID().equals(cmd.getOwnerUUID())) {
                    player.displayClientMessage(Component.translatable("msg.trainrecruits.squad_not_found"), true);
                    return;
                }
                List<AbstractRecruitEntity> units = Commanders.unitsOf(player.server, cmd);
                AbstractLeaderEntity nativeLeader = cmd instanceof AbstractLeaderEntity l ? l : null;
                switch (m.action) {
                    case SQUAD_MOVE -> {
                        if (Commanders.onTrain(cmd)) {
                            player.displayClientMessage(Component.translatable("msg.trainrecruits.squad_on_train"), true);
                            return;
                        }
                        if (nativeLeader != null) {
                            // Native commander walks his own patrol: one waypoint, his squad follows in formation.
                            nativeLeader.loadRouteWaypointsFromData(List.of(m.pos), List.of(0));
                            nativeLeader.setCycle(false);
                            nativeLeader.retreating = false;
                            nativeLeader.returning = false;
                            nativeLeader.setPatrolState(AbstractLeaderEntity.State.PATROLLING);
                        } else {
                            Commanders.march(cmd, units, m.pos);
                        }
                        player.displayClientMessage(Component.translatable("msg.trainrecruits.squad_move",
                                m.pos.getX(), m.pos.getZ()), true);
                    }
                    case SQUAD_HOLD -> {
                        if (nativeLeader != null) {
                            nativeLeader.setPatrolState(AbstractLeaderEntity.State.PAUSED);
                        }
                        RecruitCommanderUtil.setRecruitsHoldPos(units);
                        player.displayClientMessage(Component.translatable("msg.trainrecruits.squad_hold"), true);
                    }
                    case SQUAD_FOLLOW -> {
                        if (nativeLeader != null) {
                            nativeLeader.setPatrolState(AbstractLeaderEntity.State.IDLE);
                        }
                        for (AbstractRecruitEntity u : units) {
                            u.setFollowState(1);
                        }
                        player.displayClientMessage(Component.translatable("msg.trainrecruits.squad_follow"), true);
                    }
                    default -> { }
                }
                sendSquads(player);
            });
            c.setPacketHandled(true);
        }
    }

    // ---------------------------------------------------------------- recruit panel
    public record RecruitList(List<RecruitEntry> recruits) {
        static void encode(RecruitList m, FriendlyByteBuf buf) {
            buf.writeVarInt(m.recruits.size());
            for (RecruitEntry e : m.recruits) {
                e.write(buf);
            }
        }

        static RecruitList decode(FriendlyByteBuf buf) {
            int n = buf.readVarInt();
            List<RecruitEntry> list = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                list.add(RecruitEntry.read(buf));
            }
            return new RecruitList(list);
        }

        static void handle(RecruitList m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientStations.setRecruits(m.recruits)));
            c.setPacketHandled(true);
        }
    }

    /** Assign recruits to a commander from the map UI: toggle one, add all free ones, or clear the squad. */
    public record SquadAssign(UUID commander, UUID recruit, byte mode) {
        static void encode(SquadAssign m, FriendlyByteBuf buf) {
            buf.writeUUID(m.commander);
            buf.writeUUID(m.recruit);
            buf.writeByte(m.mode);
        }

        static SquadAssign decode(FriendlyByteBuf buf) {
            return new SquadAssign(buf.readUUID(), buf.readUUID(), buf.readByte());
        }

        static void handle(SquadAssign m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> {
                ServerPlayer player = c.getSender();
                if (player == null) {
                    return;
                }
                switch (m.mode) {
                    case ASSIGN_TOGGLE -> Commanders.toggleMember(player, m.recruit, m.commander);
                    case ASSIGN_ALL_FREE -> {
                        int n = Commanders.addAllFree(player, m.commander);
                        player.displayClientMessage(Component.translatable("msg.trainrecruits.assigned_free", n), true);
                    }
                    case ASSIGN_CLEAR -> {
                        AbstractRecruitEntity cmd = Commanders.find(player.server, m.commander);
                        if (cmd != null && player.getUUID().equals(cmd.getOwnerUUID())) {
                            Commanders.clearSquad(player.server, m.commander);
                        }
                    }
                    default -> { }
                }
                sendSquads(player);
            });
            c.setPacketHandled(true);
        }
    }
}
