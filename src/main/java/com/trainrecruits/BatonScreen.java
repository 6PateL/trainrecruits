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

    /** Сторінка підпунктів «Рота забезпечення». */
    private boolean supplyPage;

    private void modeButton(int mode, int x, int y, int w) {
        Component label = BatonItem.modeName(mode);
        if (mode == current) {
            label = Component.literal("> ").append(label);
        }
        addRenderableWidget(Button.builder(label, btn -> {
            Net.CHANNEL.sendToServer(new Net.BatonMode((byte) mode));
            this.onClose();
        }).bounds(x, y, w, 20).build());
    }

    @Override
    protected void init() {
        int w = 200;
        int x = (this.width - w) / 2;
        int y = this.height / 2 - 55;
        if (supplyPage) {
            modeButton(BatonItem.MODE_SUPPLY, x, y, w);
            modeButton(BatonItem.MODE_MEMORIZE, x, y + 24, w);
            addRenderableWidget(Button.builder(Component.translatable("baton.menu.back"), btn -> {
                supplyPage = false;
                rebuildWidgets();
            }).bounds(x, y + 56, w, 20).build());
            return;
        }
        int row = 0;
        for (int i = 0; i < BatonItem.MODE_SUPPLY; i++) {
            modeButton(i, x, y + row++ * 24, w);
        }
        boolean inSupply = current == BatonItem.MODE_SUPPLY || current == BatonItem.MODE_MEMORIZE;
        Component sup = Component.translatable("baton.menu.supply");
        if (inSupply) {
            sup = Component.literal("> ").append(sup);
        }
        addRenderableWidget(Button.builder(sup, btn -> {
            supplyPage = true;
            rebuildWidgets();
        }).bounds(x, y + row++ * 24, w, 20).build());
        Button info = Button.builder(Component.translatable("baton.mode.info"), btn -> {
            Net.CHANNEL.sendToServer(new Net.InfoRequest());
            this.onClose();
        }).bounds(x, y + row * 24, w, 20).build();
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
