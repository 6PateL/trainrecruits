package com.trainrecruits;

import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Supply company (only for the player's own faction villagers, baton mode 5). Marked with a green [+].
 * With at least 3 members the company walks with the player's recruits and: places torches, repairs and (on threat or
 * order) builds iron golems (1 from 3 members, +1 for every 5), lights campfires in cold biomes and feeds + heals nearby recruits.
 */
@Mod.EventBusSubscriber(modid = TrainRecruits.MODID)
public final class Supply {
    static final String TAG = "trainrecruits_supply";
    static final String OWNER = "tr_s_owner";
    static final String BUILD = "tr_s_build";
    static final String GOLEM_OWNER = "tr_s_golem";
    private static final String PREFIX = "[+] ";

    private static final Map<UUID, Vec3> ANCHOR = new HashMap<>();
    private static final Map<UUID, Long> BUSY = new HashMap<>();
    private static final Map<UUID, Long> NEXT_TORCH = new HashMap<>();
    private static final Map<UUID, Long> NEXT_GOLEM = new HashMap<>();
    private static final Map<UUID, Long> NEXT_FIRE = new HashMap<>();
    private static final Map<UUID, List<BlockPos>> FIRES = new HashMap<>();
    private static boolean warned;

    private Supply() {}

    static boolean isSupply(Entity e) {
        return e instanceof Villager && e.getPersistentData().getBoolean(TAG);
    }

    static UUID owner(Villager v) {
        CompoundTag d = v.getPersistentData();
        return d.hasUUID(OWNER) ? d.getUUID(OWNER) : null;
    }

    // ------------------------------------------------------------------ baton

    static void toggle(ServerPlayer player, Villager v) {
        if (!TrainConfig.SUPPLY.get()) {
            player.displayClientMessage(Component.translatable("msg.trainrecruits.baton_reserved"), true);
            return;
        }
        if (isSupply(v)) {
            if (!player.getUUID().equals(owner(v))) {
                player.displayClientMessage(Component.translatable("msg.trainrecruits.crew_not_yours"), true);
                return;
            }
            release(v);
            player.displayClientMessage(Component.translatable("msg.trainrecruits.supply_removed", members(player).size()), true);
            return;
        }
        if (!(player.level() instanceof ServerLevel level) || !Rv.isMyVillager(level, player, v)) {
            player.displayClientMessage(Component.translatable("msg.trainrecruits.supply_not_faction"), true);
            return;
        }
        if (Gunners.isGunner(v) || Follow.isFollowing(v)) {
            player.displayClientMessage(Component.translatable("msg.trainrecruits.supply_busy"), true);
            return;
        }
        CompoundTag d = v.getPersistentData();
        d.putBoolean(TAG, true);
        d.putUUID(OWNER, player.getUUID());
        Rv.detach(v);
        Component custom = v.getCustomName();
        String base = custom != null ? custom.getString() : v.getName().getString();
        v.setCustomName(Component.literal(PREFIX).withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD)
                .append(Component.literal(base).withStyle(ChatFormatting.WHITE)));
        v.setCustomNameVisible(true);
        attach(v);
        int n = members(player).size();
        int min = TrainConfig.SUPPLY_MIN.get();
        player.displayClientMessage(n >= min
                ? Component.translatable("msg.trainrecruits.supply_formed", n, golemCap(n))
                : Component.translatable("msg.trainrecruits.supply_added", n, min), true);
    }

    /** Baton mode 5 on an iron golem: switches the standing order "build golems" for the whole company. */
    static void orderGolems(ServerPlayer player) {
        List<Villager> list = members(player);
        if (list.size() < TrainConfig.SUPPLY_MIN.get()) {
            player.displayClientMessage(Component.translatable("msg.trainrecruits.supply_small", list.size(), TrainConfig.SUPPLY_MIN.get()), true);
            return;
        }
        boolean on = !list.get(0).getPersistentData().getBoolean(BUILD);
        for (Villager v : list) {
            v.getPersistentData().putBoolean(BUILD, on);
        }
        player.displayClientMessage(Component.translatable(on ? "msg.trainrecruits.supply_build_on" : "msg.trainrecruits.supply_build_off"), true);
    }

    static void release(Villager v) {
        CompoundTag d = v.getPersistentData();
        d.remove(TAG);
        d.remove(OWNER);
        d.remove(BUILD);
        v.goalSelector.removeAllGoals(g -> g instanceof SupplyGoal);
        if (!Gunners.isGunner(v) && !Follow.isFollowing(v)) {
            Rv.reattach(v);
        }
        Component c = v.getCustomName();
        if (c != null && c.getString().startsWith(PREFIX)) {
            v.setCustomName(null);
            v.setCustomNameVisible(false);
        }
        BUSY.remove(v.getUUID());
        if (v.level() instanceof ServerLevel sl) {
            v.refreshBrain(sl);
        }
    }

    static void attach(Villager v) {
        for (var g : v.goalSelector.getAvailableGoals()) {
            if (g.getGoal() instanceof SupplyGoal) {
                return;
            }
        }
        v.goalSelector.addGoal(1, new SupplyGoal(v));
        v.getBrain().removeAllBehaviors();
    }

    static List<Villager> members(ServerPlayer p) {
        if (!(p.level() instanceof ServerLevel level)) {
            return new ArrayList<>();
        }
        return level.getEntitiesOfClass(Villager.class, p.getBoundingBox().inflate(96.0D, 64.0D, 96.0D),
                v -> v.isAlive() && isSupply(v) && p.getUUID().equals(owner(v)));
    }

    static int golemCap(int members) {
        if (members < TrainConfig.SUPPLY_MIN.get()) {
            return 0;
        }
        return 1 + members / 5 * TrainConfig.SUPPLY_GOLEMS_PER_FIVE.get();
    }

    // ------------------------------------------------------------------ company work (once a second)

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !TrainConfig.SUPPLY.get() || event.getServer().getTickCount() % 20 != 0) {
            return;
        }
        for (ServerPlayer p : event.getServer().getPlayerList().getPlayers()) {
            try {
                work(p);
            } catch (Throwable t) {
                if (!warned) {
                    warned = true;
                    TrainRecruits.LOGGER.warn("[supply] company tick failed: {}", t.toString());
                }
            }
        }
    }

    private static void work(ServerPlayer p) {
        List<Villager> list = members(p);
        if (list.isEmpty()) {
            ANCHOR.remove(p.getUUID());
            return;
        }
        ServerLevel level = p.serverLevel();
        long now = level.getGameTime();
        List<AbstractRecruitEntity> troops = level.getEntitiesOfClass(AbstractRecruitEntity.class, p.getBoundingBox().inflate(48.0D, 24.0D, 48.0D),
                r -> r.isAlive() && p.getUUID().equals(r.getOwnerUUID()));
        Vec3 anchor = p.position();
        if (!troops.isEmpty()) {
            double x = 0, y = 0, z = 0;
            for (AbstractRecruitEntity r : troops) {
                x += r.getX();
                y += r.getY();
                z += r.getZ();
            }
            anchor = new Vec3(x / troops.size(), y / troops.size(), z / troops.size());
        }
        ANCHOR.put(p.getUUID(), anchor);
        int n = list.size();
        if (n < TrainConfig.SUPPLY_MIN.get()) {
            return; // not a company yet
        }
        BlockPos ap = BlockPos.containing(anchor);

        // 1. food and regeneration for the infantry near any member
        for (AbstractRecruitEntity r : troops) {
            for (Villager v : list) {
                if (v.distanceToSqr(r) <= 14.0D * 14.0D) {
                    r.setHunger(Math.min(100.0F, r.getHunger() + 8.0F));
                    r.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 80, 0, true, false));
                    break;
                }
            }
        }

        // 2. torches (the company does not fear the dark)
        for (Villager v : list) {
            if (BUSY.getOrDefault(v.getUUID(), 0L) > now || NEXT_TORCH.getOrDefault(v.getUUID(), 0L) > now) {
                continue;
            }
            if (placeTorch(level, v)) {
                NEXT_TORCH.put(v.getUUID(), now + 80);
            }
        }

        // 3. golems: repair, build on threat or on order
        AABB zone = new AABB(ap).inflate(40.0D, 20.0D, 40.0D);
        List<IronGolem> golems = level.getEntitiesOfClass(IronGolem.class, zone, g -> g.isAlive() && !g.isAngryAt(p));
        List<Villager> free = new ArrayList<>(list);
        for (IronGolem g : golems) {
            if (g.getHealth() >= g.getMaxHealth() - 1.0F || free.isEmpty()) {
                continue;
            }
            Villager best = null;
            double bd = Double.MAX_VALUE;
            for (Villager v : free) {
                double d = v.distanceToSqr(g);
                if (d < bd) {
                    bd = d;
                    best = v;
                }
            }
            free.remove(best);
            if (bd <= 3.5D * 3.5D) {
                g.heal(g.getMaxHealth() * 0.25F);
                best.swing(InteractionHand.MAIN_HAND);
                level.playSound(null, g.blockPosition(), SoundEvents.IRON_GOLEM_REPAIR, SoundSource.NEUTRAL, 1.0F, 1.0F);
                level.sendParticles(ParticleTypes.HAPPY_VILLAGER, g.getX(), g.getY() + 1.5D, g.getZ(), 6, 0.4D, 0.5D, 0.4D, 0.0D);
            } else {
                BUSY.put(best.getUUID(), now + 40);
                best.getNavigation().moveTo(g, 0.9D);
            }
        }
        int mine = 0;
        for (IronGolem g : golems) {
            CompoundTag gd = g.getPersistentData();
            if (gd.hasUUID(GOLEM_OWNER) && gd.getUUID(GOLEM_OWNER).equals(p.getUUID())) {
                mine++;
            }
        }
        boolean order = list.get(0).getPersistentData().getBoolean(BUILD);
        boolean threat = !level.getEntitiesOfClass(Monster.class, new AABB(ap).inflate(24.0D, 12.0D, 24.0D), Monster::isAlive).isEmpty();
        if ((order || threat) && mine < golemCap(n) && NEXT_GOLEM.getOrDefault(p.getUUID(), 0L) <= now) {
            if (buildGolem(level, p, list.get(level.random.nextInt(list.size())))) {
                NEXT_GOLEM.put(p.getUUID(), now + 600);
            }
        }

        // 4. campfires in cold places so the infantry can warm up
        List<BlockPos> fires = FIRES.computeIfAbsent(p.getUUID(), k -> new ArrayList<>());
        fires.removeIf(f -> !level.getBlockState(f).is(Blocks.CAMPFIRE) || f.distSqr(ap) > 20.0D * 20.0D);
        if (level.getBiome(ap).value().coldEnoughToSnow(ap) && fires.size() < 2 && NEXT_FIRE.getOrDefault(p.getUUID(), 0L) <= now) {
            BlockPos f = campfireSpot(level, list.get(level.random.nextInt(list.size())).blockPosition());
            if (f != null) {
                level.setBlock(f, Blocks.CAMPFIRE.defaultBlockState(), 3);
                fires.add(f);
                NEXT_FIRE.put(p.getUUID(), now + 600);
            }
        }
    }

    private static boolean placeTorch(ServerLevel level, Villager v) {
        BlockPos base = v.blockPosition();
        if (level.getBrightness(LightLayer.BLOCK, base) >= 7 || level.getMaxLocalRawBrightness(base) > 9) {
            return false;
        }
        for (int i = 0; i < 8; i++) {
            BlockPos pos = base.offset(level.random.nextInt(7) - 3, level.random.nextInt(3) - 1, level.random.nextInt(7) - 3);
            if (level.isEmptyBlock(pos) && level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP)
                    && level.getBrightness(LightLayer.BLOCK, pos) < 7) {
                level.setBlock(pos, Blocks.TORCH.defaultBlockState(), 3);
                v.swing(InteractionHand.MAIN_HAND);
                level.playSound(null, pos, SoundEvents.WOOD_PLACE, SoundSource.BLOCKS, 0.8F, 1.0F);
                return true;
            }
        }
        return false;
    }

    private static BlockPos campfireSpot(ServerLevel level, BlockPos base) {
        for (int i = 0; i < 10; i++) {
            BlockPos pos = base.offset(level.random.nextInt(9) - 4, level.random.nextInt(3) - 1, level.random.nextInt(9) - 4);
            if (level.isEmptyBlock(pos) && level.isEmptyBlock(pos.above()) && level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP)
                    && !level.getBlockState(pos.below()).is(Blocks.HAY_BLOCK) && level.getFluidState(pos).isEmpty()) {
                return pos;
            }
        }
        return null;
    }

    private static boolean buildGolem(ServerLevel level, ServerPlayer p, Villager builder) {
        BlockPos base = builder.blockPosition();
        for (int i = 0; i < 10; i++) {
            BlockPos pos = base.offset(level.random.nextInt(7) - 3, 0, level.random.nextInt(7) - 3);
            if (level.isEmptyBlock(pos) && level.isEmptyBlock(pos.above()) && level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP)) {
                IronGolem g = EntityType.IRON_GOLEM.spawn(level, pos, MobSpawnType.MOB_SUMMONED);
                if (g == null) {
                    return false;
                }
                g.setPlayerCreated(true);
                g.getPersistentData().putUUID(GOLEM_OWNER, p.getUUID());
                builder.swing(InteractionHand.MAIN_HAND);
                level.sendParticles(ParticleTypes.CLOUD, pos.getX() + 0.5D, pos.getY() + 1.0D, pos.getZ() + 0.5D, 15, 0.5D, 0.8D, 0.5D, 0.02D);
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ the goal: keep to the company's anchor

    private static final class SupplyGoal extends Goal {
        private final Villager v;

        SupplyGoal(Villager v) {
            this.v = v;
            this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        private ServerPlayer owner() {
            UUID id = owner(v);
            if (id == null || !(v.level() instanceof ServerLevel level)) {
                return null;
            }
            ServerPlayer p = level.getServer().getPlayerList().getPlayer(id);
            return p != null && p.level() == v.level() ? p : null;
        }

        @Override
        public boolean canUse() {
            return owner() != null;
        }

        @Override
        public boolean canContinueToUse() {
            return owner() != null;
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void tick() {
            ServerPlayer o = owner();
            if (o == null) {
                return;
            }
            v.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            if (BUSY.getOrDefault(v.getUUID(), 0L) > v.level().getGameTime()) {
                return;
            }
            Vec3 a = ANCHOR.getOrDefault(o.getUUID(), o.position());
            double d = v.position().distanceToSqr(a);
            if (d > 80.0D * 80.0D) {
                v.teleportTo(a.x + (v.getRandom().nextDouble() - 0.5D) * 4.0D, a.y, a.z + (v.getRandom().nextDouble() - 0.5D) * 4.0D);
                v.getNavigation().stop();
            } else if (d > 8.0D * 8.0D) {
                if (v.tickCount % 20 == 0 || v.getNavigation().isDone()) {
                    v.getNavigation().moveTo(a.x, a.y, a.z, 0.8D);
                }
            } else if (v.getNavigation().isDone()) {
                v.getNavigation().stop();
            }
        }
    }
}
