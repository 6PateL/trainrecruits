package com.trainrecruits;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Data for the baton info screen: villages that remember the player's crimes and the next scheduled raids. */
final class Info {
    private Info() {}

    /** {name, x, z, secondsLeft, state(0 hostile / 1 warning), extra} */
    @SuppressWarnings("unchecked")
    static List<String[]> grudges(ServerPlayer p) {
        try {
            return (List<String[]>) Class.forName("com.villagefall.Grudges").getMethod("report", ServerPlayer.class).invoke(null, p);
        } catch (Throwable t) {
            return new ArrayList<>();
        }
    }

    /** {kind, secondsLeft}; -1 = not scheduled yet. Only mods that are present appear. */
    static List<String[]> raids(ServerPlayer p) {
        List<String[]> out = new ArrayList<>();
        ServerLevel ow = p.server.overworld();
        long now = ow.getGameTime();
        try {
            Class<?> c = Class.forName("com.barbarianraids.BarbarianData");
            Object d = c.getMethod("get", net.minecraft.server.MinecraftServer.class).invoke(null, p.server);
            long next = c.getField("nextVillageRaid").getLong(d);
            out.add(new String[]{"barbarian_village", left(next, now) + ";" + untilStrong(c, d)});
            long best = Long.MAX_VALUE;
            for (Object b : ((Map<String, Object>) c.getField("bosses").get(d)).values()) {
                if (!b.getClass().getField("dead").getBoolean(b)) {
                    best = Math.min(best, b.getClass().getField("nextRaid").getLong(b));
                }
            }
            if (best != Long.MAX_VALUE) {
                out.add(new String[]{"barbarian_hideout", String.valueOf(left(best, now))});
            }
        } catch (Throwable ignored) {
            // barbarian raids not installed
        }
        try {
            Class<?> c = Class.forName("com.example.fleshspread.RaidData");
            Object d = c.getMethod("get", ServerLevel.class).invoke(null, ow);
            out.add(new String[]{"infected", left(c.getField("next").getLong(d), now) + ";" + untilStrong(c, d)});
        } catch (Throwable ignored) {
            // infected raids not installed
        }
        return out;
    }

    /** Ordinary raids left before a reinforced one ("" when the mod version has no such thing). */
    private static String untilStrong(Class<?> c, Object data) {
        try {
            return String.valueOf(c.getField("untilStrong").getInt(data));
        } catch (Throwable t) {
            return "";
        }
    }

    private static long left(long next, long now) {
        return next <= 0L ? -1L : Math.max(0L, (next - now) / 20L);
    }
}
