package com.trainrecruits;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.List;

/** The commander's baton. Shift + right-click opens the mode menu; a plain right-click applies the chosen mode. */
public class BatonItem extends Item {
    public static final int MODE_GENERAL = 0;
    public static final int MODE_DEGRADE = 1;
    public static final int MODE_GUN = 2;
    public static final int MODE_LEAD = 3;
    public static final int MODE_SUPPLY = 4;
    public static final int MODES = 5;
    private static final String TAG = "tr_mode";

    public BatonItem(Properties properties) {
        super(properties);
    }

    public static int mode(ItemStack stack) {
        int m = stack.hasTag() ? stack.getTag().getInt(TAG) : 0;
        return m < 0 || m >= MODES ? 0 : m;
    }

    public static void setMode(ItemStack stack, int mode) {
        stack.getOrCreateTag().putInt(TAG, mode);
    }

    public static Component modeName(int mode) {
        return Component.translatable("baton.mode." + mode);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.trainrecruits.commander_baton.mode", modeName(mode(stack))));
        tooltip.add(Component.translatable("item.trainrecruits.commander_baton.desc1"));
        tooltip.add(Component.translatable("item.trainrecruits.commander_baton.desc2"));
        tooltip.add(Component.translatable("item.trainrecruits.commander_baton.desc3"));
    }
}
