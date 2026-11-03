package com.trainrecruits;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.fml.ModList;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Reflection bridge to Recruits: Living Villages (no compile-time dependency). Everything is best-effort. */
final class Rv {
    private static final String VILLAGER_TAG = "RVVillagerFaction";
    private static boolean ready;
    private static boolean failed;
    private static Method dataGet, getFactions, getFaction, fId, fGroup, fCenter, fPlayer, fStringId, factionOfPlayer, warBetween;

    private Rv() {}

    static boolean available() {
        if (failed || !ModList.get().isLoaded("recruitsvillages")) {
            return false;
        }
        try {
            init();
            return true;
        } catch (Throwable t) {
            failed = true;
            TrainRecruits.LOGGER.warn("[artillery] cannot talk to Recruits Villages: {}", t.toString());
            return false;
        }
    }

    private static synchronized void init() throws Exception {
        if (ready) {
            return;
        }
        Class<?> data = Class.forName("com.diego.recruitsvillages.data.LivingVillagesSavedData");
        Class<?> faction = Class.forName("com.diego.recruitsvillages.data.FactionData");
        dataGet = data.getMethod("get", net.minecraft.server.MinecraftServer.class);
        getFactions = data.getMethod("getFactions");
        getFaction = data.getMethod("getFaction", UUID.class);
        fId = faction.getMethod("getId");
        fGroup = faction.getMethod("getFactionId");
        fCenter = faction.getMethod("getCenterPos");
        fPlayer = faction.getMethod("isPlayerFaction");
        fStringId = faction.getMethod("getStringID");
        factionOfPlayer = Class.forName("com.diego.recruitsvillages.player.PlayerFactionService")
                .getMethod("factionOfPlayer", ServerLevel.class, ServerPlayer.class);
        warBetween = Class.forName("com.diego.recruitsvillages.war.WarManager").getMethod("warBetween", UUID.class, UUID.class);
        ready = true;
    }

    /** The village (faction record) a villager was spawned for, or null. */
    static Object villageOf(ServerLevel level, Entity villager) throws Exception {
        CompoundTag d = villager.getPersistentData();
        if (!d.hasUUID(VILLAGER_TAG)) {
            return null;
        }
        return getFaction.invoke(dataGet.invoke(null, level.getServer()), d.getUUID(VILLAGER_TAG));
    }

    static boolean isMyVillager(ServerLevel level, ServerPlayer player, Entity villager) {
        if (!available()) {
            return false;
        }
        try {
            Object village = villageOf(level, villager);
            Object mine = factionOfPlayer.invoke(null, level, player);
            return village != null && mine != null && !Boolean.FALSE.equals(fPlayer.invoke(village))
                    && fGroup.invoke(village).equals(fGroup.invoke(mine));
        } catch (Throwable t) {
            TrainRecruits.LOGGER.warn("[artillery] faction check failed: {}", t.toString());
            return false;
        }
    }

    /** Is this entity one of the villagers/soldiers tied to the player's own faction (for friendly-fire checks)? */
    static boolean isFriendlyVillager(ServerLevel level, ServerPlayer player, Entity e) {
        return isMyVillager(level, player, e);
    }

    /** Centres of villages of factions that are at war with the player's faction (or enemies in diplomacy). */
    static List<BlockPos> enemyCenters(ServerLevel level, ServerPlayer player) {
        List<BlockPos> out = new ArrayList<>();
        if (!available()) {
            return out;
        }
        try {
            Object mine = factionOfPlayer.invoke(null, level, player);
            if (mine == null) {
                return out;
            }
            Object group = fGroup.invoke(mine);
            @SuppressWarnings("unchecked")
            Collection<Object> all = (Collection<Object>) getFactions.invoke(dataGet.invoke(null, level.getServer()));
            List<Object> ours = new ArrayList<>();
            for (Object f : all) {
                if (group.equals(fGroup.invoke(f))) {
                    ours.add(f);
                }
            }
            String myTeam = String.valueOf(fStringId.invoke(mine));
            for (Object f : all) {
                if (group.equals(fGroup.invoke(f)) || Boolean.TRUE.equals(fPlayer.invoke(f))) {
                    continue;
                }
                boolean enemy = false;
                for (Object o : ours) {
                    if (Boolean.TRUE.equals(warBetween.invoke(null, fId.invoke(o), fId.invoke(f)))) {
                        enemy = true;
                        break;
                    }
                }
                if (!enemy) {
                    enemy = "ENEMY".equals(relation(myTeam, String.valueOf(fStringId.invoke(f))));
                }
                if (enemy) {
                    out.add((BlockPos) fCenter.invoke(f));
                }
            }
        } catch (Throwable t) {
            TrainRecruits.LOGGER.warn("[artillery] enemy faction scan failed: {}", t.toString());
        }
        return out;
    }

    private static String relation(String a, String b) {
        try {
            Class<?> fe = Class.forName("com.talhanation.recruits.FactionEvents");
            Object dm = fe.getField("recruitsDiplomacyManager").get(null);
            if (dm == null || a.isEmpty() || b.isEmpty()) {
                return "";
            }
            return String.valueOf(dm.getClass().getMethod("getRelation", String.class, String.class).invoke(dm, a, b));
        } catch (Throwable t) {
            return "";
        }
    }
}
