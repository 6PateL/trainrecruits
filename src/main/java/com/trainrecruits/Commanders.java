package com.trainrecruits;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.talhanation.recruits.config.RecruitsServerConfig;
import com.talhanation.recruits.entities.AbstractLeaderEntity;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import com.talhanation.recruits.util.RecruitCommanderUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * "Commander" = any recruit that was promoted with the Commander's Baton (or a native Recruits commander).
 * His squad = the recruits explicitly assigned to him on the train map (stored as a tag on each recruit).
 * Nothing is ever assigned automatically.
 */
public final class Commanders {
    public static final String TAG = "trainrecruits_commander";
    public static final String SQUAD_TAG = "trainrecruits_squad";
    private static final String NAME_PREFIX = "[C] ";

    private Commanders() {}

    public static boolean isCommander(Entity e) {
        return e instanceof AbstractLeaderEntity || (e instanceof AbstractRecruitEntity && e.getPersistentData().getBoolean(TAG));
    }

    /** True only when the entity sits on a Create contraption (train carriage) - NOT on a horse or boat. */
    public static boolean onTrain(Entity e) {
        return e.getVehicle() instanceof AbstractContraptionEntity;
    }

    /** The commander this recruit was assigned to, or null. */
    public static UUID squadOf(Entity r) {
        CompoundTag d = r.getPersistentData();
        return d.hasUUID(SQUAD_TAG) ? d.getUUID(SQUAD_TAG) : null;
    }

    // ------------------------------------------------------------------ promote / demote (baton)

    public static void toggle(ServerPlayer player, AbstractRecruitEntity recruit) {
        if (!player.getUUID().equals(recruit.getOwnerUUID())) {
            player.displayClientMessage(Component.translatable("msg.trainrecruits.not_your_recruit"), true);
            return;
        }
        if (recruit instanceof AbstractLeaderEntity) {
            player.displayClientMessage(Component.translatable("msg.trainrecruits.already_native_commander"), true);
            return;
        }
        if (recruit.getPersistentData().getBoolean(TAG)) {
            recruit.getPersistentData().remove(TAG);
            Component custom = recruit.getCustomName();
            if (custom != null && custom.getString().startsWith(NAME_PREFIX)) {
                recruit.setCustomName(Component.literal(custom.getString().substring(NAME_PREFIX.length())));
            }
            clearSquad(player.server, recruit.getUUID());
            player.displayClientMessage(Component.translatable("msg.trainrecruits.demoted"), true);
        } else {
            int cost = Math.max(1, recruit.getCost() * TrainConfig.COMMANDER_COST_MULTIPLIER.get());
            if (!pay(player, cost)) {
                player.displayClientMessage(Component.translatable("msg.trainrecruits.promote_need", cost, currency().getDescription()), true);
                return;
            }
            recruit.getPersistentData().putBoolean(TAG, true);
            recruit.getPersistentData().remove(SQUAD_TAG);
            Component custom = recruit.getCustomName();
            String base = custom != null ? custom.getString() : recruit.getName().getString();
            if (!base.startsWith(NAME_PREFIX)) {
                recruit.setCustomName(Component.literal(NAME_PREFIX + base));
            }
            recruit.setCustomNameVisible(true);
            player.displayClientMessage(Component.translatable("msg.trainrecruits.promoted", cost, currency().getDescription()), true);
        }
        Net.sendSquads(player);
    }

    private static Item currency() {
        Item item = null;
        try {
            ResourceLocation id = ResourceLocation.tryParse(RecruitsServerConfig.RecruitCurrency.get());
            if (id != null) {
                item = ForgeRegistries.ITEMS.getValue(id);
            }
        } catch (RuntimeException ignored) {
            // fall through to emerald
        }
        return item == null || item == Items.AIR ? Items.EMERALD : item;
    }

    private static boolean pay(ServerPlayer player, int amount) {
        if (player.isCreative()) {
            return true;
        }
        Item currency = currency();
        Inventory inv = player.getInventory();
        int have = 0;
        for (ItemStack s : inv.items) {
            if (s.is(currency)) {
                have += s.getCount();
            }
        }
        if (have < amount) {
            return false;
        }
        int left = amount;
        for (ItemStack s : inv.items) {
            if (left <= 0) {
                break;
            }
            if (s.is(currency)) {
                int take = Math.min(left, s.getCount());
                s.shrink(take);
                left -= take;
            }
        }
        inv.setChanged();
        return true;
    }

    // ------------------------------------------------------------------ squad assignment (from the map UI)

    private static List<AbstractRecruitEntity> ownedRecruits(MinecraftServer server, UUID owner) {
        List<AbstractRecruitEntity> list = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity e : level.getAllEntities()) {
                if (e instanceof AbstractRecruitEntity r && r.isAlive() && owner.equals(r.getOwnerUUID())) {
                    list.add(r);
                }
            }
        }
        return list;
    }

    /** Toggle: put the recruit into the commander's squad, or take him out if he already is in it. */
    public static void toggleMember(ServerPlayer player, UUID recruitId, UUID commanderId) {
        AbstractRecruitEntity cmd = find(player.server, commanderId);
        Entity e = findEntity(player.server, recruitId);
        if (cmd == null || !player.getUUID().equals(cmd.getOwnerUUID())
                || !(e instanceof AbstractRecruitEntity r) || !player.getUUID().equals(r.getOwnerUUID())
                || isCommander(r)) {
            return;
        }
        if (commanderId.equals(squadOf(r))) {
            r.getPersistentData().remove(SQUAD_TAG);
        } else {
            r.getPersistentData().putUUID(SQUAD_TAG, commanderId);
        }
    }

    /** Put every recruit that has no squad yet into this commander's squad. */
    public static int addAllFree(ServerPlayer player, UUID commanderId) {
        AbstractRecruitEntity cmd = find(player.server, commanderId);
        if (cmd == null || !player.getUUID().equals(cmd.getOwnerUUID())) {
            return 0;
        }
        int n = 0;
        for (AbstractRecruitEntity r : ownedRecruits(player.server, player.getUUID())) {
            if (!isCommander(r) && squadOf(r) == null) {
                r.getPersistentData().putUUID(SQUAD_TAG, commanderId);
                n++;
            }
        }
        return n;
    }

    public static void clearSquad(MinecraftServer server, UUID commanderId) {
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity e : level.getAllEntities()) {
                if (e instanceof AbstractRecruitEntity r && commanderId.equals(squadOf(r))) {
                    r.getPersistentData().remove(SQUAD_TAG);
                }
            }
        }
    }

    // ------------------------------------------------------------------ lookup

    public static AbstractRecruitEntity find(MinecraftServer server, UUID id) {
        Entity e = findEntity(server, id);
        return e instanceof AbstractRecruitEntity r && isCommander(r) ? r : null;
    }

    private static Entity findEntity(MinecraftServer server, UUID id) {
        for (ServerLevel level : server.getAllLevels()) {
            Entity e = level.getEntity(id);
            if (e != null) {
                return e;
            }
        }
        return null;
    }

    /** The commander himself first, then his squad (loaded members only). */
    public static List<AbstractRecruitEntity> unitsOf(MinecraftServer server, AbstractRecruitEntity cmd) {
        List<AbstractRecruitEntity> units = new ArrayList<>();
        units.add(cmd);
        if (cmd instanceof AbstractLeaderEntity leader && leader.army != null) {
            for (AbstractRecruitEntity u : leader.army.getAllRecruitUnits()) {
                if (u != null && u.isAlive() && u != cmd) {
                    units.add(u);
                }
            }
        }
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity e : level.getAllEntities()) {
                if (e instanceof AbstractRecruitEntity u && u != cmd && u.isAlive() && !isCommander(u)
                        && cmd.getUUID().equals(squadOf(u)) && !units.contains(u)) {
                    units.add(u);
                }
            }
        }
        return units;
    }

    // ------------------------------------------------------------------ orders for promoted commanders

    /** Commander walks to the target, the squad takes positions behind him. */
    public static void march(AbstractRecruitEntity cmd, List<AbstractRecruitEntity> units, BlockPos target) {
        int i = 0;
        for (AbstractRecruitEntity u : units) {
            if (onTrain(u)) {
                continue;
            }
            BlockPos p = u == cmd ? target : target.offset((i % 5 - 2) * 2, 0, (i / 5 + 1) * 2);
            if (u != cmd) {
                i++;
            }
            if (u.level() instanceof ServerLevel level && level.hasChunkAt(p)) {
                p = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p);
            }
            RecruitCommanderUtil.setRecruitsMove(List.of(u), p);
        }
    }
}
