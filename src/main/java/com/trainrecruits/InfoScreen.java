package com.trainrecruits;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/** Baton info: villages that remember your crimes (with coordinates and time until they forget) and the next raids. */
public class InfoScreen extends Screen {
    private List<String[]> grudges;
    private List<String[]> raids;
    /** When the data arrived: the timers count down locally from there (live), and are re-synced every few seconds. */
    private long received = System.currentTimeMillis();
    private int sinceRefresh;
    private static boolean refreshPending;

    public InfoScreen(List<String[]> grudges, List<String[]> raids) {
        super(Component.translatable("baton.info.title"));
        this.grudges = grudges;
        this.raids = raids;
    }

    private long live(String seconds) {
        long s = Long.parseLong(seconds);
        return s < 0 ? s : Math.max(0L, s - (System.currentTimeMillis() - received) / 1000L);
    }

    @Override
    public void tick() {
        super.tick();
        if (++sinceRefresh >= 100) { // every 5 s: fresh numbers from the server (raids moved, grudges forgotten)
            sinceRefresh = 0;
            refreshPending = true;
            Net.CHANNEL.sendToServer(new Net.InfoRequest());
        }
    }

    static String time(long s) {
        if (s < 0) {
            return Component.translatable("baton.info.unscheduled").getString();
        }
        long h = s / 3600L;
        long m = s % 3600L / 60L;
        long sec = s % 60L;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, sec) : String.format("%d:%02d", m, sec);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        this.renderBackground(g);
        int x = this.width / 2 - 150;
        int y = 20;
        g.drawCenteredString(this.font, this.title, this.width / 2, y, 0xFFFFFF);
        y += 18;
        g.drawString(this.font, Component.translatable("baton.info.crimes"), x, y, 0xFFFFAA55);
        y += 12;
        if (grudges.isEmpty()) {
            g.drawString(this.font, Component.translatable("baton.info.none"), x + 6, y, 0xFFAAAAAA);
            y += 12;
        }
        for (String[] e : grudges) {
            boolean warn = e.length > 4 && "1".equals(e[4]);
            String key = warn ? "baton.info.warning" : "baton.info.hostile";
            long left = live(e[3]);
            g.drawString(this.font, Component.translatable(key, e[0], e[1], e[2], time(left)), x + 6, y,
                    warn ? 0xFFFFE070 : 0xFFFF6060);
            y += 12;
        }
        y += 8;
        g.drawString(this.font, Component.translatable("baton.info.raids"), x, y, 0xFFFFAA55);
        y += 12;
        if (raids.isEmpty()) {
            g.drawString(this.font, Component.translatable("baton.info.none"), x + 6, y, 0xFFAAAAAA);
        }
        for (String[] r : raids) {
            String[] parts = r[1].split(";");
            g.drawString(this.font, Component.translatable("baton.info.raid." + r[0], time(live(parts[0]))),
                    x + 6, y, 0xFFE0E0E0);
            y += 12;
            if (parts.length > 1 && !parts[1].isEmpty()) {
                int until = Integer.parseInt(parts[1]);
                if (until >= 0) {
                    g.drawString(this.font, until == 0 ? Component.translatable("baton.info.raid.strong_next")
                            : Component.translatable("baton.info.raid.strong_in", until), x + 16, y, until == 0 ? 0xFFFF6060 : 0xFFAAAAAA);
                    y += 12;
                }
            }
        }
        super.render(g, mx, my, pt);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    static void show(List<String[]> grudges, List<String[]> raids) {
        Minecraft mc = Minecraft.getInstance();
        boolean refresh = refreshPending;
        refreshPending = false;
        if (mc.screen instanceof InfoScreen cur) {
            cur.grudges = grudges;
            cur.raids = raids;
            cur.received = System.currentTimeMillis();
            return;
        }
        if (refresh) {
            return; // the screen was closed while a refresh was on its way: do not reopen it
        }
        mc.setScreen(new InfoScreen(grudges, raids));
    }
}
