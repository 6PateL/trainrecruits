package com.trainrecruits;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Shift + right-click with the baton: what do you want to do with the next target. */
public class BatonScreen extends Screen {
    private final int current;

    public BatonScreen(int current) {
        super(Component.translatable("baton.menu.title"));
        this.current = current;
    }

    @Override
    protected void init() {
        int w = 200;
        int x = (this.width - w) / 2;
        int y = this.height / 2 - 55;
        for (int i = 0; i < BatonItem.MODES; i++) {
            final int mode = i;
            Component label = BatonItem.modeName(i);
            if (i == current) {
                label = Component.literal("> ").append(label);
            }
            Button b = Button.builder(label, btn -> {
                Net.CHANNEL.sendToServer(new Net.BatonMode((byte) mode));
                this.onClose();
            }).bounds(x, y + i * 24, w, 20).build();
            this.addRenderableWidget(b);
        }
        Button info = Button.builder(Component.translatable("baton.mode.info"), btn -> {
            Net.CHANNEL.sendToServer(new Net.InfoRequest());
            this.onClose();
        }).bounds(x, y + BatonItem.MODES * 24, w, 20).build();
        this.addRenderableWidget(info);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        this.renderBackground(g);
        g.drawCenteredString(this.font, this.title, this.width / 2, this.height / 2 - 75, 0xFFFFFF);
        super.render(g, mx, my, pt);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    static void open() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.screen == null) {
            mc.setScreen(new BatonScreen(BatonItem.mode(mc.player.getMainHandItem())));
        }
    }
}
