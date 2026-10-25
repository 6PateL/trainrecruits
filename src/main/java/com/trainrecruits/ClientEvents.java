package com.trainrecruits;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

public final class ClientEvents {
    public static final KeyMapping OPEN_MAP =
            new KeyMapping("key.trainrecruits.open_map", GLFW.GLFW_KEY_N, "key.categories.trainrecruits");

    private ClientEvents() {}

    @Mod.EventBusSubscriber(modid = TrainRecruits.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class ModBus {
        @SubscribeEvent
        public static void registerKeys(RegisterKeyMappingsEvent event) {
            event.register(OPEN_MAP);
        }
    }

    @Mod.EventBusSubscriber(modid = TrainRecruits.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
    public static final class ForgeBus {
        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) {
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            TerrainCache.tick(mc);
            while (OPEN_MAP.consumeClick()) {
                if (mc.screen == null && mc.player != null) {
                    mc.setScreen(new TrainMapScreen());
                }
            }
        }
    }
}
