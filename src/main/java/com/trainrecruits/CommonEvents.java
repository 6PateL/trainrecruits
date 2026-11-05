package com.trainrecruits;

import com.talhanation.recruits.entities.AbstractRecruitEntity;
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
        if (event.getTarget() instanceof Villager villager) {
            // baton on an ordinary villager: mobilise / release an artillery crew (own faction's villagers only)
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
            if (!event.getLevel().isClientSide && event.getEntity() instanceof ServerPlayer player) {
                Gunners.toggle(player, villager);
            }
            return;
        }
        AbstractRecruitEntity recruit = (AbstractRecruitEntity) event.getTarget();
        // Do not open the recruit's own GUI when using the baton on him
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        if (!event.getLevel().isClientSide && event.getEntity() instanceof ServerPlayer player) {
            Commanders.toggle(player, recruit);
        }
    }

    @SubscribeEvent
    public static void onPostClick(PlayerInteractEvent.RightClickBlock event) {
        if (event.getHand() != InteractionHand.MAIN_HAND || !(event.getItemStack().getItem() instanceof BatonItem)
                || !(event.getLevel() instanceof net.minecraft.server.level.ServerLevel level)
                || !Battery.isPost(level, event.getPos())) {
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
    public static void onServerTick(net.minecraftforge.event.TickEvent.ServerTickEvent event) {
        if (event.phase == net.minecraftforge.event.TickEvent.Phase.END && event.getServer().getTickCount() % 600 == 0) {
            Battery.tick(event.getServer());
        }
    }

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide && event.getEntity() instanceof Villager v && Gunners.isGunner(v)) {
            Gunners.attachGoal(v);
        }
    }
}
