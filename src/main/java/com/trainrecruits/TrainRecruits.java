package com.trainrecruits;

import net.minecraft.world.item.CreativeModeTabs;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(TrainRecruits.MODID)
public class TrainRecruits {
    public static final String MODID = "trainrecruits";

    public TrainRecruits() {
        IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();
        ModRegistry.register(bus);
        bus.addListener(TrainRecruits::addCreative);
        bus.addListener((FMLCommonSetupEvent e) -> e.enqueueWork(Net::register));
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, TrainConfig.SPEC);
    }

    private static void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.REDSTONE_BLOCKS) {
            event.accept(ModRegistry.BOARDING_STATION_ITEM.get());
            event.accept(ModRegistry.DISEMBARK_STATION_ITEM.get());
        }
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(ModRegistry.COMMANDER_BATON.get());
        }
    }
}
