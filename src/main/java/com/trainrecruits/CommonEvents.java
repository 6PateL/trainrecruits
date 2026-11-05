package com.trainrecruits;

import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.Villager;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = TrainRecruits.MODID)
public final class CommonEvents {
    private CommonEvents() {}

    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.getHand() != InteractionHand.MAIN_HAND
                || !(event.getItemStack().getItem() instanceof BatonItem)
                || !(event.getTarget() instanceof AbstractRecruitEntity || event.getTarget() instanceof Villager)) {
            return;
        }
        // never open the target's own GUI with the baton
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        if (event.getLevel().isClientSide || !(event.getEntity() instanceof ServerPlayer player) || player.isShiftKeyDown()) {
            return; // shift opens the mode menu on the client
        }
        int mode = BatonItem.mode(event.getItemStack());
        if (event.getTarget() instanceof AbstractRecruitEntity recruit) {
            if (mode == BatonItem.MODE_GENERAL) {
                if (Commanders.isCommander(recruit)) {
                    player.displayClientMessage(Component.translatable("msg.trainrecruits.general_already"), true);
                } else {
                    Commanders.toggle(player, recruit);
                }
            } else if (mode == BatonItem.MODE_DEGRADE) {
                if (Commanders.isCommander(recruit)) {
                    Commanders.toggle(player, recruit);
                } else {
                    player.displayClientMessage(Component.translatable("msg.trainrecruits.general_not"), true);
                }
            } else {
                player.displayClientMessage(Component.translatable("msg.trainrecruits.baton_wrong_target"), true);
            }
            return;
        }
        Villager villager = (Villager) event.getTarget();
        switch (mode) {
            case BatonItem.MODE_GUN -> {
                if (Follow.isFollowing(villager)) Follow.release(villager);
                Gunners.toggle(player, villager);
            }
            case BatonItem.MODE_LEAD -> Follow.toggle(player, villager);
            case BatonItem.MODE_SUPPLY -> player.displayClientMessage(Component.translatable("msg.trainrecruits.baton_reserved"), true);
            default -> player.displayClientMessage(Component.translatable("msg.trainrecruits.baton_wrong_target"), true);
        }
    }

    @SubscribeEvent
    public static void onPostClick(PlayerInteractEvent.RightClickBlock event) {
        if (event.getHand() != InteractionHand.MAIN_HAND || !(event.getItemStack().getItem() instanceof BatonItem)
                || !(event.getLevel() instanceof net.minecraft.server.level.ServerLevel level)
                || event.getEntity().isShiftKeyDown() || !Battery.isPost(level, event.getPos())) {
            return;
        }
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        String problem = Battery.problem(level, event.getPos());
        event.getEntity().displayClientMessage(problem == null
                ? net.minecraft.network.chat.Component.translatable("msg.trainrecruits.post_ready")
                : net.minecraft.network.chat.Component.translatable("msg.trainrecruits.crew_not_assigned",
                        net.minecraft.network.chat.Component.translatable("msg.trainrecruits.post_problem." + problem)), true);
    }

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide && event.getEntity() instanceof Villager v) {
            if (Gunners.isGunner(v)) {
                Gunners.attachGoal(v);
            } else if (Follow.isFollowing(v)) {
                Follow.attach(v);
            }
        }
    }
}
