package com.trainrecruits;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class StationBlockEntity extends BlockEntity {
    private UUID owner;
    private boolean auto;

    // runtime-only state of a boarding call
    private long boardingUntil = -1L;
    private int seatedTotal;
    private final Map<UUID, Long> orders = new HashMap<>();
    private long nextAutoCheck;

    public StationBlockEntity(BlockPos pos, BlockState state) {
        super(ModRegistry.STATION_BE.get(), pos, state);
    }

    private boolean isBoardingStation() {
        return getBlockState().getBlock() instanceof StationBlock sb && sb.isBoarding();
    }

    public void setOwner(UUID owner) {
        this.owner = owner;
        setChanged();
        if (level instanceof ServerLevel serverLevel) {
            register(serverLevel);
        }
    }

    /** Keep the world-wide registry (used by the world map) up to date. */
    private void register(ServerLevel serverLevel) {
        if (owner != null && serverLevel.getServer() != null) {
            StationRegistry.get(serverLevel.getServer()).put(owner, new StationEntry(
                    serverLevel.dimension().location().toString(), worldPosition, isBoardingStation(), auto));
        }
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel serverLevel) {
            register(serverLevel);
        }
    }

    // ------------------------------------------------------------------ triggers

    public void onUse(Player player) {
        command(player, player.isShiftKeyDown());
    }

    /** Right click (toggleAuto == false: give the command now) or shift+click / map menu (toggleAuto == true). */
    public void command(Player player, boolean toggleAuto) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        if (owner == null) {
            owner = player.getUUID();
            setChanged();
        } else if (!owner.equals(player.getUUID()) && !player.hasPermissions(2)) {
            player.displayClientMessage(Component.translatable("msg.trainrecruits.not_owner"), true);
            return;
        }
        if (toggleAuto) {
            auto = !auto;
            setChanged();
            register(serverLevel);
            player.displayClientMessage(Component.translatable(auto
                    ? "msg.trainrecruits.auto_on" : "msg.trainrecruits.auto_off"), true);
            return;
        }
        trigger(serverLevel, true);
    }

    public void onRedstone() {
        if (level instanceof ServerLevel serverLevel && owner != null) {
            trigger(serverLevel, true);
        }
    }

    private void trigger(ServerLevel serverLevel, boolean manual) {
        if (owner == null) {
            return;
        }
        if (isBoardingStation()) {
            startBoarding(serverLevel);
        } else {
            runDisembark(serverLevel, manual);
        }
    }

    // ------------------------------------------------------------------ ticking

    public static void serverTick(Level level, BlockPos pos, BlockState state, StationBlockEntity be) {
        if (level instanceof ServerLevel serverLevel && level.getGameTime() % 10L == 0L) {
            be.tick(serverLevel);
        }
    }

    private void tick(ServerLevel serverLevel) {
        long now = serverLevel.getGameTime();
        if (isBoardingStation()) {
            if (boardingUntil >= 0L) {
                if (now > boardingUntil) {
                    finishBoarding(serverLevel, "msg.trainrecruits.board_timeout");
                } else {
                    runBoarding(serverLevel);
                }
            } else if (auto && owner != null && now >= nextAutoCheck) {
                nextAutoCheck = now + 40L;
                var trains = TrainSeating.trainsNear(serverLevel, worldPosition, TrainConfig.REQUIRE_STOPPED_TRAIN.get());
                if (!TrainSeating.freeSlots(trains).isEmpty()
                        && !TrainSeating.candidates(serverLevel, worldPosition, owner).isEmpty()) {
                    startBoarding(serverLevel);
                }
            }
        } else if (auto && owner != null && now >= nextAutoCheck) {
            nextAutoCheck = now + 20L;
            runDisembark(serverLevel, false);
        }
    }

    // ------------------------------------------------------------------ boarding

    private void startBoarding(ServerLevel serverLevel) {
        boardingUntil = serverLevel.getGameTime() + TrainConfig.BOARDING_SECONDS.get() * 20L;
        seatedTotal = 0;
        orders.clear();
        notifyOwner(serverLevel, Component.translatable("msg.trainrecruits.board_start"));
        runBoarding(serverLevel);
    }

    private void runBoarding(ServerLevel serverLevel) {
        TrainSeating.Step step = TrainSeating.boardStep(serverLevel, worldPosition, owner, orders);
        seatedTotal += step.seated();
        if (step.pending() == 0) {
            if (step.unassigned() == 0) {
                finishBoarding(serverLevel, seatedTotal > 0
                        ? "msg.trainrecruits.board_done" : "msg.trainrecruits.board_nobody");
            } else if (step.trainPresent()) {
                finishBoarding(serverLevel, "msg.trainrecruits.board_full");
            }
            // otherwise: nobody seated yet and no stopped train with free seats -> keep waiting for a train
        }
    }

    private void finishBoarding(ServerLevel serverLevel, String messageKey) {
        TrainSeating.releaseWalkers(serverLevel, orders);
        boardingUntil = -1L;
        nextAutoCheck = serverLevel.getGameTime() + 600L;
        notifyOwner(serverLevel, Component.translatable(messageKey, seatedTotal));
    }

    // ------------------------------------------------------------------ disembarking

    private void runDisembark(ServerLevel serverLevel, boolean manual) {
        int count = TrainSeating.disembark(serverLevel, worldPosition, owner);
        if (count > 0) {
            notifyOwner(serverLevel, Component.translatable("msg.trainrecruits.disembarked", count));
        } else if (manual) {
            notifyOwner(serverLevel, Component.translatable("msg.trainrecruits.no_riders"));
        }
    }

    private void notifyOwner(ServerLevel serverLevel, Component message) {
        if (owner == null || serverLevel.getServer() == null) {
            return;
        }
        ServerPlayer player = serverLevel.getServer().getPlayerList().getPlayer(owner);
        if (player != null) {
            player.sendSystemMessage(message);
        }
    }

    // ------------------------------------------------------------------ nbt

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        if (owner != null) {
            tag.putUUID("Owner", owner);
        }
        tag.putBoolean("Auto", auto);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        owner = tag.hasUUID("Owner") ? tag.getUUID("Owner") : null;
        auto = tag.getBoolean("Auto");
    }
}
