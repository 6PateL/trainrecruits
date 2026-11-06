package com.trainrecruits;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/** Baton info: villages that remember your crimes (with coordinates and time until they forget) and the next raids. */
public class InfoScreen extends Screen {
    private final List<String[]> grudges;
    private final List<String[]> raids;

    public InfoScreen(List<String[]> grudges, List<String[]> raids) {
        super(Component.translatable("baton.info.title"));
        this.grudges = grudges;
        this.raids = raids;
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
            long left = Long.parseLong(e[3]);
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
            g.drawString(this.font, Component.translatable("baton.info.raid." + r[0], time(Long.parseLong(r[1]))),
                    x + 6, y, 0xFFE0E0E0);
            y += 12;
        }
        super.render(g, mx, my, pt);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    static void show(List<String[]> grudges, List<String[]> raids) {
        Minecraft.getInstance().setScreen(new InfoScreen(grudges, raids));
    }
}
