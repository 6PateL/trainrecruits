package com.trainrecruits;

import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Artillery crews: ordinary villagers of the player's own Recruits Villages faction, mobilised with the baton.
 * A crew binds itself to a nearby assembled Create Big Cannons mount and works it with endless ammunition.
 */
public final class Gunners {
    public static final String TAG = "trainrecruits_gunner";
    public static final String OWNER = "tr_g_owner";
    public static final String MOUNT = "tr_g_mount";
    public static final String AUTO = "tr_g_auto";
    public static final String FACTION = "tr_g_faction"; // crew of a village (no player owner)
    public static final String TX = "tr_g_tx";
    public static final String TY = "tr_g_ty";
    public static final String TZ = "tr_g_tz";
    public static final String SHOTS = "tr_g_shots";
    public static final String STATE = "tr_g_state";
    private static final String NAME_PREFIX = "[A] ";

    public static final byte ORDER_FIRE = 0;
    public static final byte ORDER_STOP = 1;
    public static final byte ORDER_TOGGLE_AUTO = 2;

    private Gunners() {}

    public static boolean isGunner(Entity e) {
        return e instanceof Villager && e.getPersistentData().getBoolean(TAG);
    }

    public static UUID owner(Entity e) {
        CompoundTag d = e.getPersistentData();
        return d.hasUUID(OWNER) ? d.getUUID(OWNER) : null;
    }

    public static void attachGoal(Villager v) {
        for (WrappedGoal g : v.goalSelector.getAvailableGoals()) {
            if (g.getGoal() instanceof GunnerGoal) {
                return;
            }
        }
        v.goalSelector.addGoal(0, new GunnerGoal(v));
        v.getBrain().removeAllBehaviors(); // the villager's own schedule must not drag him away from the gun
    }

    // ------------------------------------------------------------------ baton: mobilise / release

    public static void toggle(ServerPlayer player, Villager v) {
        if (isGunner(v)) {
            if (!player.getUUID().equals(owner(v))) {
                player.displayClientMessage(Component.translatable("msg.trainrecruits.crew_not_yours"), true);
                return;
            }
            CompoundTag d = v.getPersistentData();
            for (String k : new String[]{TAG, OWNER, FACTION, MOUNT, AUTO, TX, TY, TZ, SHOTS, STATE}) {
                d.remove(k);
            }
            Component custom = v.getCustomName();
            if (custom != null && custom.getString().startsWith(NAME_PREFIX)) {
                v.setCustomName(null);
                v.setCustomNameVisible(false);
            }
            player.displayClientMessage(Component.translatable("msg.trainrecruits.crew_released"), true);
            Net.sendSquads(player);
            return;
        }
        if (!(player.level() instanceof ServerLevel level) || !Rv.isMyVillager(level, player, v)) {
            player.displayClientMessage(Component.translatable("msg.trainrecruits.crew_not_faction"), true);
            return;
        }
        if (!Cbc.available()) {
            player.displayClientMessage(Component.translatable("msg.trainrecruits.crew_no_cbc"), true);
            return;
        }
        int cost = TrainConfig.GUNNER_COST.get();
        if (cost > 0 && !Commanders.pay(player, cost)) {
            player.displayClientMessage(Component.translatable("msg.trainrecruits.promote_need", cost,
                    Commanders.currency().getDescription()), true);
            return;
        }
        CompoundTag d = v.getPersistentData();
        d.putBoolean(TAG, true);
        d.putUUID(OWNER, player.getUUID());
        d.putBoolean(AUTO, TrainConfig.GUN_AUTO.get());
        Component custom = v.getCustomName();
        String base = custom != null ? custom.getString() : v.getName().getString();
        v.setCustomName(Component.literal(NAME_PREFIX + base));
        v.setCustomNameVisible(true);
        attachGoal(v);
        player.displayClientMessage(Component.translatable("msg.trainrecruits.crew_mobilised"), true);
        Net.sendSquads(player);
    }

    // ------------------------------------------------------------------ map orders

    public static Villager find(MinecraftServer server, UUID id) {
        for (ServerLevel level : server.getAllLevels()) {
            Entity e = level.getEntity(id);
            if (e instanceof Villager v && isGunner(v)) {
                return v;
            }
        }
        return null;
    }

    public static void order(ServerPlayer player, UUID crew, byte action, BlockPos pos) {
        Villager v = find(player.server, crew);
        if (v == null || !v.isAlive() || !player.getUUID().equals(owner(v))) {
            player.displayClientMessage(Component.translatable("msg.trainrecruits.crew_not_found"), true);
            return;
        }
        CompoundTag d = v.getPersistentData();
        ServerLevel level = (ServerLevel) v.level();
        switch (action) {
            case ORDER_FIRE -> {
                if (!d.contains(MOUNT)) {
                    BlockPos m = Cbc.nearestMount(level, v.blockPosition(), TrainConfig.GUN_BIND_RADIUS.get());
                    if (m == null) {
                        player.displayClientMessage(Component.translatable("msg.trainrecruits.crew_no_gun"), true);
                        return;
                    }
                    d.putLong(MOUNT, Battery.bindTarget(level, m).asLong());
                }
                BlockPos mount = BlockPos.of(d.getLong(MOUNT));
                double dist = Math.sqrt(mount.distSqr(new BlockPos(pos.getX(), mount.getY(), pos.getZ())));
                if (dist < TrainConfig.GUN_MIN_RANGE.get() || dist > TrainConfig.GUN_MAX_RANGE.get()) {
                    player.displayClientMessage(Component.translatable("msg.trainrecruits.crew_range",
                            TrainConfig.GUN_MIN_RANGE.get(), TrainConfig.GUN_MAX_RANGE.get(), (int) dist), true);
                    return;
                }
                int y = level.hasChunkAt(pos) ? level.getHeight(Heightmap.Types.MOTION_BLOCKING, pos.getX(), pos.getZ()) : pos.getY();
                d.putInt(TX, pos.getX());
                d.putInt(TY, y);
                d.putInt(TZ, pos.getZ());
                d.putInt(SHOTS, TrainConfig.GUN_SHOTS_PER_ORDER.get());
                player.displayClientMessage(Component.translatable("msg.trainrecruits.crew_fire", pos.getX(), pos.getZ()), true);
            }
            case ORDER_STOP -> {
                d.remove(TX);
                d.remove(TY);
                d.remove(TZ);
                d.putInt(SHOTS, 0);
                player.displayClientMessage(Component.translatable("msg.trainrecruits.crew_stop"), true);
            }
            case ORDER_TOGGLE_AUTO -> {
                boolean now = !d.getBoolean(AUTO);
                d.putBoolean(AUTO, now);
                player.displayClientMessage(Component.translatable(now ? "msg.trainrecruits.crew_auto_on"
                        : "msg.trainrecruits.crew_auto_off"), true);
            }
            default -> { }
        }
        Net.sendSquads(player);
    }

    public static CrewEntry entry(Villager v, String dim) {
        CompoundTag d = v.getPersistentData();
        Component n = v.getCustomName();
        return new CrewEntry(v.getUUID(), n == null ? v.getName().getString() : n.getString(), dim, v.getX(), v.getZ(),
                d.contains(MOUNT), d.getBoolean(AUTO), d.getInt(STATE));
    }

    // ------------------------------------------------------------------ automatic targets

    private static final List<ResourceLocation> NEST_IDS = List.of(
            new ResourceLocation("flesh_that_hates", "small_scp_6106"),
            new ResourceLocation("flesh_that_hates", "big_scp_6106"),
            new ResourceLocation("flesh_that_hates", "scp_610_spawner"));

    /** Next thing to shell: enemy faction villages, infection nests, barbarian settlements - nearest valid first. */
    static BlockPos autoTarget(ServerLevel level, ServerPlayer owner, BlockPos mount) {
        int min = TrainConfig.GUN_MIN_RANGE.get();
        int max = TrainConfig.GUN_MAX_RANGE.get();
        List<BlockPos> cands = new ArrayList<>();
        if (TrainConfig.GUN_AUTO_FACTIONS.get() && level.dimension() == Level.OVERWORLD) {
            cands.addAll(Rv.enemyCenters(level, owner));
        }
        if (TrainConfig.GUN_AUTO_INFECTION.get()) {
            AABB box = new AABB(mount).inflate(max, 128, max);
            for (Entity e : level.getEntities((Entity) null, box, x -> {
                ResourceLocation k = ForgeRegistries.ENTITY_TYPES.getKey(x.getType());
                return k != null && NEST_IDS.contains(k);
            })) {
                cands.add(e.blockPosition());
            }
        }
        if (TrainConfig.GUN_AUTO_BARBARIANS.get()) {
            cands.addAll(barbarians(level));
        }
        cands.sort(Comparator.comparingDouble(p -> horizontal(p, mount)));
        int checked = 0;
        for (BlockPos p : cands) {
            double d = Math.sqrt(horizontal(p, mount));
            if (d < min || d > max || !level.hasChunkAt(p)) {
                continue;
            }
            if (++checked > 12) {
                break;
            }
            if (friendlyNear(level, owner, p)) {
                continue;
            }
            return p;
        }
        return null;
    }

    /** Targets for a village's own crew: factions at war with the village, infection nests, barbarian settlements. */
    static BlockPos factionTarget(ServerLevel level, UUID factionId, BlockPos mount) {
        int min = TrainConfig.GUN_MIN_RANGE.get();
        int max = TrainConfig.GUN_MAX_RANGE.get();
        List<BlockPos> cands = new ArrayList<>(Rv.enemyCentersOf(level, factionId));
        AABB box = new AABB(mount).inflate(max, 128, max);
        for (Entity e : level.getEntities((Entity) null, box, x -> {
            ResourceLocation k = ForgeRegistries.ENTITY_TYPES.getKey(x.getType());
            return k != null && NEST_IDS.contains(k);
        })) {
            cands.add(e.blockPosition());
        }
        cands.addAll(barbarians(level));
        cands.sort(Comparator.comparingDouble(p -> horizontal(p, mount)));
        int checked = 0;
        for (BlockPos p : cands) {
            double d = Math.sqrt(horizontal(p, mount));
            if (d < min || d > max || !level.hasChunkAt(p)) {
                continue;
            }
            if (++checked > 12) {
                break;
            }
            if (!villagersNear(level, factionId, p)) {
                return p;
            }
        }
        return null;
    }

    private static boolean villagersNear(ServerLevel level, UUID factionId, BlockPos p) {
        int r = TrainConfig.GUN_FRIENDLY_CLEARANCE.get();
        if (r <= 0) {
            return false;
        }
        for (Villager v : level.getEntitiesOfClass(Villager.class, new AABB(p).inflate(r, 64, r))) {
            UUID f = Rv.villagerFaction(v);
            if (f != null && f.equals(factionId)) {
                return true;
            }
        }
        return false;
    }

    private static double horizontal(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }

    private static boolean friendlyNear(ServerLevel level, ServerPlayer owner, BlockPos p) {
        int r = TrainConfig.GUN_FRIENDLY_CLEARANCE.get();
        if (r <= 0) {
            return false;
        }
        AABB box = new AABB(p).inflate(r, 64, r);
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box)) {
            if (e instanceof Player pl && !pl.isSpectator()) {
                return true;
            }
            if (e instanceof AbstractRecruitEntity rec && owner.getUUID().equals(rec.getOwnerUUID())) {
                return true;
            }
            if (e instanceof Villager v && Rv.isMyVillager(level, owner, v)) {
                return true;
            }
        }
        return false;
    }

    private static boolean barbFailed;

    @SuppressWarnings("unchecked")
    private static List<BlockPos> barbarians(ServerLevel level) {
        List<BlockPos> out = new ArrayList<>();
        if (barbFailed || !ModList.get().isLoaded("barbarianraids")) {
            return out;
        }
        try {
            Class<?> dataClass = Class.forName("com.barbarianraids.BarbarianData");
            Object data = dataClass.getMethod("get", MinecraftServer.class).invoke(null, level.getServer());
            Map<?, ?> bosses = (Map<?, ?>) dataClass.getField("bosses").get(data);
            for (Object b : bosses.values()) {
                Class<?> bc = b.getClass();
                boolean dead = bc.getField("dead").getBoolean(b);
                String dim = String.valueOf(bc.getField("dim").get(b));
                BlockPos home = (BlockPos) bc.getField("home").get(b);
                if (!dead && home != null && dim.contains(level.dimension().location().getPath())) {
                    out.add(home);
                }
            }
        } catch (Throwable t) {
            barbFailed = true;
            TrainRecruits.LOGGER.warn("[artillery] cannot read Barbarian Raids data: {}", t.toString());
        }
        return out;
    }

    static Vec3 center(BlockPos p) {
        return Vec3.atCenterOf(p);
    }
}
