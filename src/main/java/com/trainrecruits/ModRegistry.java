package com.trainrecruits;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModRegistry {
    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, TrainRecruits.MODID);
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, TrainRecruits.MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, TrainRecruits.MODID);

    private static BlockBehaviour.Properties props() {
        return BlockBehaviour.Properties.of()
                .mapColor(MapColor.METAL)
                .strength(3.0F, 6.0F)
                .sound(SoundType.METAL);
    }

    public static final RegistryObject<Block> BOARDING_STATION =
            BLOCKS.register("boarding_station", () -> new StationBlock(true, props()));
    public static final RegistryObject<Block> DISEMBARK_STATION =
            BLOCKS.register("disembark_station", () -> new StationBlock(false, props()));

    public static final RegistryObject<Item> BOARDING_STATION_ITEM =
            ITEMS.register("boarding_station", () -> new BlockItem(BOARDING_STATION.get(), new Item.Properties()));
    public static final RegistryObject<Item> DISEMBARK_STATION_ITEM =
            ITEMS.register("disembark_station", () -> new BlockItem(DISEMBARK_STATION.get(), new Item.Properties()));

    public static final RegistryObject<Item> COMMANDER_BATON =
            ITEMS.register("commander_baton", () -> new BatonItem(new Item.Properties().stacksTo(1)));

    public static final RegistryObject<BlockEntityType<StationBlockEntity>> STATION_BE =
            BLOCK_ENTITIES.register("station", () ->
                    BlockEntityType.Builder.of(StationBlockEntity::new,
                            BOARDING_STATION.get(), DISEMBARK_STATION.get()).build(null));

    public static void register(IEventBus bus) {
        BLOCKS.register(bus);
        ITEMS.register(bus);
        BLOCK_ENTITIES.register(bus);
    }
}
