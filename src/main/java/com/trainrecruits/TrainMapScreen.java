package com.trainrecruits;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Simple tactical map: terrain, your stations (B / D), your commanders' squads.
 *  - click a squad (blue) or a station -> select it
 *  - click on the ground with a squad selected -> the squad marches there
 *  - drag = pan, wheel = zoom
 */
public class TrainMapScreen extends Screen {
    private static final int WIN = TerrainCache.WINDOW_CHUNKS * 16;

    private double camX;
    private double camZ;
    private double scale = 1.5D;
    private boolean cameraInitialized;

    private DynamicTexture texture;
    private ResourceLocation texId;
    private int winCx;
    private int winCz;
    private int builtVersion = -1;
    private int rebuildCooldown;

    private boolean pressing;
    private boolean dragging;
    private double pressX;
    private double pressY;

    private UUID selSquad;
    private BlockPos selStation;
    private BlockPos lastTarget;
    private int refreshTimer;

    // recruit panel (right side)
    private static final int ROW_H = 11;

    /** Panel width adapts to small screens (large GUI scale). */
    private int panelW() {
        return Math.min(200, Math.max(110, width / 3));
    }
    private record Row(RecruitEntry entry, String label) {}
    private final List<Row> rows = new ArrayList<>();
    private int panelScroll;
    private int hudBottom = 44;
    private Button addFreeBtn;
    private Button clearBtn;

    private Button holdBtn;
    private Button followBtn;
    private Button stationBtn;
    private Button autoBtn;

    public TrainMapScreen() {
        super(Component.translatable("gui.trainrecruits.map.title"));
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void init() {
        Minecraft mc = Minecraft.getInstance();
        if (!cameraInitialized && mc.player != null) {
            camX = mc.player.getX();
            camZ = mc.player.getZ();
            cameraInitialized = true;
        }
        if (texture == null) {
            texture = new DynamicTexture(WIN, WIN, true);
            texId = mc.getTextureManager().register("trainrecruits_map", texture);
            winCx = Mth.floor(camX) >> 4;
            winCz = Mth.floor(camZ) >> 4;
            builtVersion = -1;
        }

        int gap = 2;
        int n = 5;
        int bw = Math.max(40, Math.min(100, (width - 8 - (n - 1) * gap) / n));
        int x0 = (width - (n * bw + (n - 1) * gap)) / 2;
        int y = height - 26;
        addRenderableWidget(Button.builder(Component.translatable("gui.trainrecruits.map.center"),
                b -> centerOnPlayer()).bounds(x0, y, bw, 20).build());
        holdBtn = addRenderableWidget(Button.builder(Component.translatable("gui.trainrecruits.map.hold"),
                b -> squadOrder(Net.SQUAD_HOLD)).bounds(x0 + (bw + gap), y, bw, 20).build());
        followBtn = addRenderableWidget(Button.builder(Component.translatable("gui.trainrecruits.map.follow"),
                b -> squadOrder(Net.SQUAD_FOLLOW)).bounds(x0 + 2 * (bw + gap), y, bw, 20).build());
        stationBtn = addRenderableWidget(Button.builder(Component.translatable("gui.trainrecruits.map.station"),
                b -> stationOrder(Net.ACTION_TRIGGER)).bounds(x0 + 3 * (bw + gap), y, bw, 20).build());
        autoBtn = addRenderableWidget(Button.builder(Component.translatable("gui.trainrecruits.map.auto"),
                b -> stationOrder(Net.ACTION_TOGGLE_AUTO)).bounds(x0 + 4 * (bw + gap), y, bw, 20).build());
        int px = width - panelW() - 6;
        addFreeBtn = addRenderableWidget(Button.builder(Component.translatable("gui.trainrecruits.map.add_free"),
                b -> bulkAssign(Net.ASSIGN_ALL_FREE)).bounds(px, height - 56, panelW() / 2 - 2, 20).build());
        clearBtn = addRenderableWidget(Button.builder(Component.translatable("gui.trainrecruits.map.clear_squad"),
                b -> bulkAssign(Net.ASSIGN_CLEAR)).bounds(px + panelW() / 2 + 2, height - 56, panelW() / 2 - 2, 20).build());
        refreshTimer = 0;
    }

    private void bulkAssign(byte mode) {
        if (selSquad != null) {
            Net.CHANNEL.sendToServer(new Net.SquadAssign(selSquad, RecruitEntry.NONE, mode));
            refreshTimer = 5;
        }
    }

    private int panelX() {
        return width - panelW() - 6;
    }

    private int panelTop() {
        return Math.max(hudBottom + 4, 30);
    }

    private int panelBottom() {
        return height - 60;
    }

    private int panelRowsTop() {
        return panelTop() + 26;
    }

    private int visibleRows() {
        return Math.max(1, (panelBottom() - panelRowsTop()) / ROW_H);
    }

    private boolean inPanel(double mx, double my) {
        return mx >= panelX() && mx <= width - 6 && my >= panelTop() && my <= panelBottom();
    }

    private void rebuildRows() {
        rows.clear();
        String dim = currentDim();
        List<RecruitEntry> src = new ArrayList<>(ClientStations.getRecruits());
        // free recruits first, then those in the selected squad, then the rest
        src.sort((a, b) -> Integer.compare(rank(a), rank(b)));
        for (RecruitEntry e : src) {
            if (!e.dim().equals(dim)) {
                continue;
            }
            String type = Component.translatable(e.typeKey()).getString();
            String label = e.customName().isEmpty() ? type : e.customName() + " (" + type + ")";
            if (e.mounted()) {
                label += " *";
            }
            rows.add(new Row(e, label));
        }
        panelScroll = Mth.clamp(panelScroll, 0, Math.max(0, rows.size() - visibleRows()));
    }

    private int rank(RecruitEntry e) {
        if (!e.assigned()) {
            return 0;
        }
        return e.squad().equals(selSquad) ? 1 : 2;
    }

    @Override
    public void removed() {
        Minecraft mc = Minecraft.getInstance();
        if (texId != null) {
            mc.getTextureManager().release(texId);
        }
        if (texture != null) {
            texture.close();
            texture = null;
        }
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ------------------------------------------------------------------ helpers

    private String currentDim() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level == null ? "" : mc.level.dimension().location().toString();
    }

    private double toScreenX(double wx) {
        return width / 2.0D + (wx - camX) * scale;
    }

    private double toScreenY(double wz) {
        return height / 2.0D + (wz - camZ) * scale;
    }

    private double toWorldX(double sx) {
        return camX + (sx - width / 2.0D) / scale;
    }

    private double toWorldZ(double sy) {
        return camZ + (sy - height / 2.0D) / scale;
    }

    private void centerOnPlayer() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            camX = mc.player.getX();
            camZ = mc.player.getZ();
        }
    }

    private SquadEntry selectedSquad() {
        if (selSquad == null) {
            return null;
        }
        for (SquadEntry s : ClientStations.getSquads()) {
            if (s.id().equals(selSquad)) {
                return s;
            }
        }
        return null;
    }

    private StationEntry selectedStation() {
        if (selStation == null) {
            return null;
        }
        for (StationEntry s : ClientStations.get()) {
            if (s.pos().equals(selStation) && s.dim().equals(currentDim())) {
                return s;
            }
        }
        return null;
    }

    private void squadOrder(byte action) {
        if (selSquad != null) {
            Net.CHANNEL.sendToServer(new Net.SquadCommand(selSquad, action, BlockPos.ZERO));
        }
    }

    private void stationOrder(byte action) {
        if (selStation != null) {
            Net.CHANNEL.sendToServer(new Net.StationAction(selStation, action));
        }
    }

    // ------------------------------------------------------------------ tick

    @Override
    public void tick() {
        super.tick();
        if (--refreshTimer <= 0) {
            refreshTimer = 40;
            Net.CHANNEL.sendToServer(new Net.RequestStations());
            Net.CHANNEL.sendToServer(new Net.RequestSquads());
        }
        SquadEntry squad = selectedSquad();
        StationEntry station = selectedStation();
        rebuildRows();
        addFreeBtn.active = squad != null;
        clearBtn.active = squad != null;
        holdBtn.active = squad != null;
        followBtn.active = squad != null;
        stationBtn.active = station != null;
        autoBtn.active = station != null;
        if (station != null) {
            stationBtn.setMessage(Component.translatable(station.boarding()
                    ? "gui.trainrecruits.map.station_board" : "gui.trainrecruits.map.station_disembark"));
            autoBtn.setMessage(Component.translatable(station.auto()
                    ? "gui.trainrecruits.map.auto_on" : "gui.trainrecruits.map.auto_off"));
        } else {
            stationBtn.setMessage(Component.translatable("gui.trainrecruits.map.station"));
            autoBtn.setMessage(Component.translatable("gui.trainrecruits.map.auto"));
        }

        if (rebuildCooldown > 0) {
            rebuildCooldown--;
        }
        int camCx = Mth.floor(camX) >> 4;
        int camCz = Mth.floor(camZ) >> 4;
        if (Math.abs(camCx - winCx) > 8 || Math.abs(camCz - winCz) > 8) {
            winCx = camCx;
            winCz = camCz;
            builtVersion = -1;
        }
        if (builtVersion == -1 || (builtVersion != TerrainCache.version && rebuildCooldown <= 0)) {
            rebuild();
            builtVersion = TerrainCache.version;
            rebuildCooldown = 10;
        }
    }

    private void rebuild() {
        Minecraft mc = Minecraft.getInstance();
        if (texture == null || mc.level == null) {
            return;
        }
        NativeImage img = texture.getPixels();
        if (img == null) {
            return;
        }
        Map<Long, int[]> cache = TerrainCache.forLevel(mc.level);
        int n = TerrainCache.WINDOW_CHUNKS;
        int half = n / 2;
        for (int cz = 0; cz < n; cz++) {
            for (int cx = 0; cx < n; cx++) {
                int[] arr = cache.get(ChunkPos.asLong(winCx - half + cx, winCz - half + cz));
                for (int lz = 0; lz < 16; lz++) {
                    for (int lx = 0; lx < 16; lx++) {
                        img.setPixelRGBA(cx * 16 + lx, cz * 16 + lz, arr == null ? 0 : arr[lz * 16 + lx]);
                    }
                }
            }
        }
        texture.upload();
    }

    // ------------------------------------------------------------------ render

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, 0xFF0B0B10);

        // terrain
        if (texture != null && texId != null) {
            int half = TerrainCache.WINDOW_CHUNKS / 2;
            double originX = (winCx - half) * 16.0D;
            double originZ = (winCz - half) * 16.0D;
            PoseStack pose = g.pose();
            pose.pushPose();
            pose.translate((float) toScreenX(originX), (float) toScreenY(originZ), 0.0F);
            pose.scale((float) scale, (float) scale, 1.0F);
            g.blit(texId, 0, 0, 0.0F, 0.0F, WIN, WIN, WIN, WIN);
            pose.popPose();
        }

        String dim = currentDim();
        var font = this.font;

        // recruits (small dots): green = in selected squad, blue-grey = other squad, white = free
        for (Row row : rows) {
            RecruitEntry r = row.entry();
            int rx = (int) toScreenX(r.x());
            int ry = (int) toScreenY(r.z());
            int color = !r.assigned() ? 0xFFFFFFFF : r.squad().equals(selSquad) ? 0xFF55FF55 : 0xFF7A8CA8;
            g.fill(rx - 1, ry - 1, rx + 2, ry + 2, color);
        }

        // stations
        for (StationEntry e : ClientStations.get()) {
            if (!e.dim().equals(dim)) {
                continue;
            }
            int cx = (int) toScreenX(e.pos().getX() + 0.5D);
            int cy = (int) toScreenY(e.pos().getZ() + 0.5D);
            int color = e.boarding() ? 0xFF2FBF5A : 0xFFE8552F;
            boolean sel = e.pos().equals(selStation);
            g.fill(cx - 7, cy - 7, cx + 7, cy + 7, sel ? 0xFFFFFF55 : 0xFF000000);
            g.fill(cx - 6, cy - 6, cx + 6, cy + 6, color);
            g.drawCenteredString(font, e.boarding() ? "B" : "D", cx, cy - 4, 0xFFFFFFFF);
            if (e.auto()) {
                g.fill(cx + 4, cy - 6, cx + 6, cy - 4, 0xFFFFE04A);
            }
        }

        // squads
        for (SquadEntry s : ClientStations.getSquads()) {
            if (!s.dim().equals(dim)) {
                continue;
            }
            int cx = (int) toScreenX(s.x());
            int cy = (int) toScreenY(s.z());
            boolean sel = s.id().equals(selSquad);
            g.fill(cx - 6, cy - 6, cx + 6, cy + 6, sel ? 0xFFFFFF55 : 0xFF000000);
            g.fill(cx - 5, cy - 5, cx + 5, cy + 5, s.riding() ? 0xFFB06AFF : 0xFF3D7BFF);
            g.drawCenteredString(font, "C", cx, cy - 4, 0xFFFFFFFF);
            String label = s.name() + " (" + s.size() + ")";
            g.drawCenteredString(font, label, cx, cy - 18, sel ? 0xFFFFFF55 : 0xFFFFFFFF);
        }

        // last order marker
        if (lastTarget != null) {
            g.drawCenteredString(font, "x", (int) toScreenX(lastTarget.getX() + 0.5D),
                    (int) toScreenY(lastTarget.getZ() + 0.5D) - 4, 0xFFFF4040);
        }

        // player
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            int px = (int) toScreenX(mc.player.getX());
            int py = (int) toScreenY(mc.player.getZ());
            g.fill(px - 3, py - 3, px + 3, py + 3, 0xFF000000);
            g.fill(px - 2, py - 2, px + 2, py + 2, 0xFF55FF55);
        }

        // HUD (wrapped so it never runs off the screen)
        SquadEntry squad = selectedSquad();
        Component info;
        if (squad != null) {
            info = Component.translatable("gui.trainrecruits.map.selected_squad", squad.name(), squad.size(),
                    Mth.floor(squad.x()), Mth.floor(squad.z()));
        } else if (ClientStations.getSquads().isEmpty()) {
            info = Component.translatable("gui.trainrecruits.map.no_squads");
        } else {
            info = Component.translatable("gui.trainrecruits.map.select_hint");
        }
        int hudW = width - 8;
        List<net.minecraft.util.FormattedCharSequence> help =
                font.split(Component.translatable("gui.trainrecruits.map.help"), hudW - 8);
        List<net.minecraft.util.FormattedCharSequence> infoLines = font.split(info, hudW - 8);
        int hudLines = Math.min(help.size(), 2) + Math.min(infoLines.size(), 2);
        g.fill(4, 4, 4 + hudW, 8 + hudLines * 10, 0x99000000);
        int hy = 6;
        for (int i = 0; i < Math.min(help.size(), 2); i++, hy += 10) {
            g.drawString(font, help.get(i), 8, hy, 0xFFDDDDDD);
        }
        for (int i = 0; i < Math.min(infoLines.size(), 2); i++, hy += 10) {
            g.drawString(font, infoLines.get(i), 8, hy, 0xFFFFFF88);
        }
        hudBottom = 8 + hudLines * 10;

        renderPanel(g, squad);

        super.render(g, mouseX, mouseY, partialTick);
    }

    private void renderPanel(GuiGraphics g, SquadEntry squad) {
        int x = panelX();
        int top = panelTop();
        int bottom = panelBottom();
        g.fill(x, top, width - 6, bottom, 0xCC000000);
        long free = rows.stream().filter(r -> !r.entry().assigned()).count();
        g.drawString(font, font.plainSubstrByWidth(Component.translatable("gui.trainrecruits.map.panel_title", rows.size(), free).getString(), panelW() - 8),
                x + 4, top + 4, 0xFFFFFFFF);
        Component sub = squad != null
                ? Component.translatable("gui.trainrecruits.map.panel_for", squad.name())
                : Component.translatable("gui.trainrecruits.map.panel_select");
        g.drawString(font, font.plainSubstrByWidth(sub.getString(), panelW() - 8), x + 4, top + 15, squad != null ? 0xFFFFFF88 : 0xFFAAAAAA);

        int y = panelRowsTop();
        int shown = 0;
        for (int i = panelScroll; i < rows.size() && shown < visibleRows(); i++, shown++) {
            Row row = rows.get(i);
            RecruitEntry e = row.entry();
            int color;
            String prefix;
            if (!e.assigned()) {
                color = 0xFFFFFFFF;
                prefix = "  ";
            } else if (e.squad().equals(selSquad)) {
                color = 0xFF55FF55;
                prefix = "+ ";
            } else {
                color = 0xFF7A8CA8;
                prefix = "> ";
            }
            String text = font.plainSubstrByWidth(prefix + row.label(), panelW() - 10);
            g.drawString(font, text, x + 4, y, color);
            y += ROW_H;
        }
        if (rows.isEmpty()) {
            g.drawString(font, Component.translatable("gui.trainrecruits.map.panel_empty"), x + 4, panelRowsTop(), 0xFFAAAAAA);
        }
        g.drawString(font, font.plainSubstrByWidth(Component.translatable("gui.trainrecruits.map.panel_legend").getString(), panelW() - 8),
                x + 4, bottom - 10, 0xFF888888);
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) {
            return true;
        }
        if (inPanel(mx, my)) {
            if (button == 0 && my >= panelRowsTop()) {
                int idx = panelScroll + (int) ((my - panelRowsTop()) / ROW_H);
                if (selSquad != null && idx >= 0 && idx < rows.size()
                        && (idx - panelScroll) < visibleRows()) {
                    Net.CHANNEL.sendToServer(new Net.SquadAssign(selSquad, rows.get(idx).entry().id(), Net.ASSIGN_TOGGLE));
                    refreshTimer = 5;
                }
            }
            return true;
        }
        if (button == 0) {
            pressing = true;
            dragging = false;
            pressX = mx;
            pressY = my;
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (button == 0 && pressing) {
            if (!dragging && Math.hypot(mx - pressX, my - pressY) > 4.0D) {
                dragging = true;
            }
            if (dragging) {
                camX -= dx / scale;
                camZ -= dy / scale;
            }
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        boolean handled = super.mouseReleased(mx, my, button);
        if (button == 0 && pressing) {
            pressing = false;
            if (!dragging) {
                handleClick(mx, my);
            }
            dragging = false;
            return true;
        }
        return handled;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (inPanel(mx, my)) {
            panelScroll = Mth.clamp(panelScroll - (int) Math.signum(delta) * 3, 0, Math.max(0, rows.size() - visibleRows()));
            return true;
        }
        double before = scale;
        scale = Mth.clamp(delta > 0 ? scale * 1.2D : scale / 1.2D, 0.5D, 8.0D);
        if (scale != before) {
            // keep the world point under the cursor fixed
            double wx = camX + (mx - width / 2.0D) / before;
            double wz = camZ + (my - height / 2.0D) / before;
            camX = wx - (mx - width / 2.0D) / scale;
            camZ = wz - (my - height / 2.0D) / scale;
        }
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (ClientEvents.OPEN_MAP.matches(keyCode, scanCode)) {
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void handleClick(double mx, double my) {
        String dim = currentDim();
        // stations first
        StationEntry bestStation = null;
        double best = 11.0D * 11.0D;
        for (StationEntry e : ClientStations.get()) {
            if (!e.dim().equals(dim)) {
                continue;
            }
            double dx = toScreenX(e.pos().getX() + 0.5D) - mx;
            double dy = toScreenY(e.pos().getZ() + 0.5D) - my;
            double d = dx * dx + dy * dy;
            if (d <= best) {
                best = d;
                bestStation = e;
            }
        }
        if (bestStation != null) {
            selStation = bestStation.pos();
            return;
        }
        // then squads
        SquadEntry bestSquad = null;
        best = 12.0D * 12.0D;
        for (SquadEntry s : ClientStations.getSquads()) {
            if (!s.dim().equals(dim)) {
                continue;
            }
            double dx = toScreenX(s.x()) - mx;
            double dy = toScreenY(s.z()) - my;
            double d = dx * dx + dy * dy;
            if (d <= best) {
                best = d;
                bestSquad = s;
            }
        }
        if (bestSquad != null) {
            selSquad = bestSquad.id();
            return;
        }
        // ground: order the selected squad to go there
        if (selSquad != null && selectedSquad() != null) {
            Minecraft mc = Minecraft.getInstance();
            int y = mc.player != null ? Mth.floor(mc.player.getY()) : 64;
            BlockPos target = new BlockPos(Mth.floor(toWorldX(mx)), y, Mth.floor(toWorldZ(my)));
            lastTarget = target;
            Net.CHANNEL.sendToServer(new Net.SquadCommand(selSquad, Net.SQUAD_MOVE, target));
        }
    }
}
