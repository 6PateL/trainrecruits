package com.trainrecruits;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.List;

/** Right-click one of your recruits with it to make him a squad commander (costs a multiple of his hire price). */
public class BatonItem extends Item {
    public BatonItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.trainrecruits.commander_baton.desc1"));
        tooltip.add(Component.translatable("item.trainrecruits.commander_baton.desc2"));
    }
}
