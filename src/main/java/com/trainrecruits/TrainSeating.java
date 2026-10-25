package com.trainrecruits;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.talhanation.recruits.entities.AbstractLeaderEntity;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import com.talhanation.recruits.util.RecruitCommanderUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * All the "glue" between Recruits and Create trains.
 *
 * Boarding: Create seats on an assembled contraption are not blocks in the world, so the vanilla / Recruits
 * "mount" goal cannot use them. We walk the recruit near the seat using Recruits' own "move to position" order
 * and then seat it with {@link AbstractContraptionEntity#addSittingPassenger(Entity, int)} - the same call
 * Create uses when a player right-clicks a seat.
 */
public final class TrainSeating {
    private static final String PREV_STATE_TAG = "trainrecruits_prev_state";

    private TrainSeating() {}

    public record Slot(AbstractContraptionEntity train, int index, Vec3 pos) {}

    /** seated: seated during this step; pending: walking to an assigned seat; unassigned: no free seat left for them. */
    public record Step(int seated, int pending, int unassigned, boolean trainPresent) {}

    // ------------------------------------------------------------------ trains

    public static boolean isStopped(AbstractContraptionEntity entity) {
        if (entity instanceof CarriageContraptionEntity carriageEntity) {
            Carriage carriage = carriageEntity.getCarriage();
            if (carriage != null && carriage.train != null) {
                return Math.abs(carriage.train.speed) < 0.01D;
            }
        }
        return true;
    }

    public static List<AbstractContraptionEntity> trainsNear(ServerLevel level, BlockPos center, boolean requireStopped) {
        AABB box = new AABB(center).inflate(TrainConfig.TRAIN_RADIUS.get());
        List<AbstractContraptionEntity> result = new ArrayList<>();
        for (AbstractContraptionEntity e : level.getEntitiesOfClass(AbstractContraptionEntity.class, box)) {
            if (!e.isAlive() || e.getContraption() == null) {
                continue;
            }
            if (requireStopped && !isStopped(e)) {
                continue;
            }
            result.add(e);
        }
        return result;
    }

    public static List<Slot> freeSlots(List<AbstractContraptionEntity> trains) {
        List<Slot> slots = new ArrayList<>();
        for (AbstractContraptionEntity train : trains) {
            Contraption contraption = train.getContraption();
            List<BlockPos> seats = contraption.getSeats();
            Set<Integer> used = new HashSet<>(contraption.getSeatMapping().values());
            for (int i = 0; i < seats.size(); i++) {
                if (used.contains(i)) {
                    continue;
                }
                Vec3 pos = train.toGlobalVector(Vec3.atCenterOf(seats.get(i)), 1.0F);
                slots.add(new Slot(train, i, pos));
            }
        }
        return slots;
    }

    // ------------------------------------------------------------------ recruits

    /** Recruits owned by {@code owner} (plus armies of owned commanders) that are standing around and may board. */
    public static List<AbstractRecruitEntity> candidates(ServerLevel level, BlockPos center, UUID owner) {
        double radius = TrainConfig.SEARCH_RADIUS.get();
        AABB box = new AABB(center).inflate(radius);
        Vec3 c = Vec3.atCenterOf(center);

        List<AbstractRecruitEntity> result = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        List<AbstractRecruitEntity> nearby = level.getEntitiesOfClass(AbstractRecruitEntity.class, box);

        for (AbstractRecruitEntity r : nearby) {
            if (r.isAlive() && !r.isPassenger() && owner.equals(r.getOwnerUUID()) && seen.add(r.getUUID())) {
                result.add(r);
            }
        }
        // Squads of owned commanders (their members might not be directly owned / listening)
        for (AbstractRecruitEntity r : nearby) {
            if (r instanceof AbstractLeaderEntity leader && owner.equals(leader.getOwnerUUID()) && leader.army != null) {
                for (AbstractRecruitEntity u : leader.army.getAllRecruitUnits()) {
                    if (u != null && u.isAlive() && !u.isPassenger()
                            && u.position().distanceToSqr(c) <= radius * radius * 4.0D
                            && seen.add(u.getUUID())) {
                        result.add(u);
                    }
                }
            }
        }
        result.sort(Comparator
                .<AbstractRecruitEntity>comparingInt(r -> Commanders.isCommander(r) ? 0 : 1)
                .thenComparingDouble(r -> r.position().distanceToSqr(c)));
        return result;
    }

    // ------------------------------------------------------------------ boarding

    public static Step boardStep(ServerLevel level, BlockPos center, UUID owner, Map<UUID, Long> orders) {
        List<AbstractContraptionEntity> trains = trainsNear(level, center, TrainConfig.REQUIRE_STOPPED_TRAIN.get());
        List<Slot> slots = freeSlots(trains);
        List<AbstractRecruitEntity> candidates = candidates(level, center, owner);

        long now = level.getGameTime();
        long walkTimeout = TrainConfig.WALK_TIMEOUT_SECONDS.get() * 20L;
        double reach = TrainConfig.SEAT_REACH.get();

        int seated = 0;
        int pending = 0;
        int unassigned = 0;

        for (AbstractRecruitEntity r : candidates) {
            if (slots.isEmpty()) {
                unassigned++;
                continue;
            }
            if (r instanceof AbstractLeaderEntity leader) {
                pauseLeader(leader);
            }
            Slot best = null;
            double bestDist = Double.MAX_VALUE;
            for (Slot s : slots) {
                double d = r.position().distanceToSqr(s.pos());
                if (d < bestDist) {
                    bestDist = d;
                    best = s;
                }
            }
            slots.remove(best);

            double dist = Math.sqrt(bestDist);
            Long orderedAt = orders.get(r.getUUID());
            boolean timedOut = orderedAt != null && now - orderedAt > walkTimeout && dist < 16.0D;

            if (dist <= reach || timedOut) {
                if (seat(r, best)) {
                    seated++;
                    orders.remove(r.getUUID());
                    continue;
                }
            }
            pending++;
            if (orderedAt == null) {
                orders.put(r.getUUID(), now);
            }
            if (orderedAt == null || (now / 10L) % 6L == 0L) {
                RecruitCommanderUtil.setRecruitsMove(List.of(r), BlockPos.containing(best.pos()));
            }
        }
        return new Step(seated, pending, unassigned, !trains.isEmpty());
    }

    /** A commander on patrol would drag his squad away; pause him while the squad boards. */
    private static void pauseLeader(AbstractLeaderEntity leader) {
        CompoundTag data = leader.getPersistentData();
        if (!data.contains(PREV_STATE_TAG)) {
            data.putByte(PREV_STATE_TAG, leader.getPatrollingState());
            leader.setPatrolState(AbstractLeaderEntity.State.PAUSED);
        }
    }

    private static boolean seat(AbstractRecruitEntity recruit, Slot slot) {
        // Stop any walking / patrol order so the recruit does not try to run off while seated.
        RecruitCommanderUtil.setRecruitsHoldPos(List.of(recruit));
        slot.train().addSittingPassenger(recruit, slot.index());
        return recruit.getVehicle() == slot.train();
    }

    /** Called when a boarding call ends: whoever is still walking just stands where they are. */
    public static void releaseWalkers(ServerLevel level, Map<UUID, Long> orders) {
        List<AbstractRecruitEntity> walkers = new ArrayList<>();
        for (UUID id : orders.keySet()) {
            Entity e = level.getEntity(id);
            if (e instanceof AbstractRecruitEntity r && r.isAlive() && !r.isPassenger()) {
                walkers.add(r);
            }
        }
        RecruitCommanderUtil.setRecruitsHoldPos(walkers);
        for (AbstractRecruitEntity r : walkers) {
            if (r instanceof AbstractLeaderEntity leader) {
                CompoundTag data = leader.getPersistentData();
                if (data.contains(PREV_STATE_TAG)) {
                    leader.setPatrolState(AbstractLeaderEntity.State.fromIndex(data.getByte(PREV_STATE_TAG)));
                    data.remove(PREV_STATE_TAG);
                }
            }
        }
        orders.clear();
    }

    // ------------------------------------------------------------------ disembarking

    /** @return how many recruits were let out */
    public static int disembark(ServerLevel level, BlockPos center, UUID owner) {
        List<AbstractContraptionEntity> trains = trainsNear(level, center, TrainConfig.REQUIRE_STOPPED_TRAIN.get());
        if (trains.isEmpty()) {
            return 0;
        }

        // Which riders belong to us: owned directly, or members of an owned commander's squad that rides along.
        Set<UUID> squad = new HashSet<>();
        for (AbstractContraptionEntity train : trains) {
            for (Entity p : train.getPassengers()) {
                if (p instanceof AbstractLeaderEntity leader && owner.equals(leader.getOwnerUUID()) && leader.army != null) {
                    for (AbstractRecruitEntity u : leader.army.getAllRecruitUnits()) {
                        if (u != null) {
                            squad.add(u.getUUID());
                        }
                    }
                }
            }
        }

        List<AbstractRecruitEntity> riders = new ArrayList<>();
        for (AbstractContraptionEntity train : trains) {
            for (Entity p : train.getPassengers()) {
                if (p instanceof AbstractRecruitEntity r && (owner.equals(r.getOwnerUUID()) || squad.contains(r.getUUID()))) {
                    riders.add(r);
                }
            }
        }
        if (riders.isEmpty()) {
            return 0;
        }
        // commanders last so they step off after their soldiers
        riders.sort(Comparator.comparingInt(r -> Commanders.isCommander(r) ? 1 : 0));

        List<Vec3> used = new ArrayList<>();
        for (AbstractRecruitEntity r : riders) {
            Entity vehicle = r.getVehicle();
            Vec3 fallback = vehicle instanceof AbstractContraptionEntity ace ? ace.getPassengerPosition(r, 1.0F) : null;

            r.stopRiding();
            r.shouldMount(false, null);

            Vec3 spot = findDropSpot(level, center, trains, used, true);
            if (spot == null) {
                spot = findDropSpot(level, center, trains, used, false);
            }
            if (spot == null) {
                spot = fallback;
            }
            if (spot != null) {
                used.add(spot);
                r.teleportTo(spot.x, spot.y, spot.z);
            }
            r.setDeltaMovement(Vec3.ZERO);
            r.fallDistance = 0.0F;
            afterDisembark(r, owner);
        }
        return riders.size();
    }

    private static void afterDisembark(AbstractRecruitEntity r, UUID owner) {
        r.dismount = 0;
        switch (TrainConfig.AFTER_DISEMBARK.get()) {
            case FOLLOW -> r.setFollowState(1);
            case WANDER -> r.setFollowState(0);
            default -> RecruitCommanderUtil.setRecruitsHoldPos(List.of(r));
        }
        if (r instanceof AbstractLeaderEntity leader) {
            CompoundTag data = leader.getPersistentData();
            if (data.contains(PREV_STATE_TAG)) {
                if (TrainConfig.RESTORE_LEADER_STATE.get()) {
                    leader.setPatrolState(AbstractLeaderEntity.State.fromIndex(data.getByte(PREV_STATE_TAG)));
                }
                data.remove(PREV_STATE_TAG);
            }
        }
    }

    private static Vec3 findDropSpot(ServerLevel level, BlockPos center, List<AbstractContraptionEntity> trains,
                                     List<Vec3> used, boolean avoidTrains) {
        for (int ring = 1; ring <= 8; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                        continue;
                    }
                    for (int dy = 2; dy >= -2; dy--) {
                        BlockPos feet = center.offset(dx, dy, dz);
                        if (!standable(level, feet)) {
                            continue;
                        }
                        Vec3 spot = Vec3.atBottomCenterOf(feet);
                        boolean taken = false;
                        for (Vec3 u : used) {
                            if (u.distanceToSqr(spot) < 0.8D) {
                                taken = true;
                                break;
                            }
                        }
                        if (taken) {
                            continue;
                        }
                        if (avoidTrains) {
                            Vec3 mid = spot.add(0.0D, 1.0D, 0.0D);
                            boolean inTrain = false;
                            for (AbstractContraptionEntity t : trains) {
                                if (t.getBoundingBox().contains(mid)) {
                                    inTrain = true;
                                    break;
                                }
                            }
                            if (inTrain) {
                                continue;
                            }
                        }
                        return spot;
                    }
                }
            }
        }
        return null;
    }

    private static boolean standable(ServerLevel level, BlockPos feet) {
        BlockPos below = feet.below();
        BlockState floor = level.getBlockState(below);
        if (!floor.isFaceSturdy(level, below, Direction.UP)) {
            return false;
        }
        BlockState body = level.getBlockState(feet);
        BlockState head = level.getBlockState(feet.above());
        return body.getCollisionShape(level, feet).isEmpty()
                && head.getCollisionShape(level, feet.above()).isEmpty()
                && level.getFluidState(feet).isEmpty();
    }
}
