package com.ybm.betterbounty;

import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.blockentity.grid.GridBlockEntity;
import io.ejekta.bountiful.content.board.BoardBlockEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.items.IItemHandler;
import org.jetbrains.annotations.Nullable;

@Mod.EventBusSubscriber
public class RightClickHandler {

    @SubscribeEvent
    public static void onRightClick(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        ItemStack held = player.getMainHandItem();
        if (held.isEmpty()) return;

        // 1. 手持悬赏令右键容器方块
        if (BountyLogic.isBounty(held)) {
            BlockEntity be = event.getLevel().getBlockEntity(event.getPos());
            if (be != null) {
                // RS 网格终端（所有类型的网格）
                if (be instanceof GridBlockEntity gridBe) {
                    INetwork network = gridBe.getNode().getNetwork();
                    BountyLogic.trySubmitBountyFromNetwork(player, held, network);
                    event.setCanceled(true);
                    event.setCancellationResult(InteractionResult.SUCCESS);
                    return;
                }
                // 普通容器（箱子等）
                IItemHandler itemHandler = getItemHandler(be);
                if (itemHandler != null) {
                    BountyLogic.trySubmitBountyFromContainer(player, held, itemHandler);
                    event.setCanceled(true);
                    event.setCancellationResult(InteractionResult.SUCCESS);
                    return;
                }
            }
        }

        // 2. 手持悬赏令经验右键悬赏板
        if (held.getItem() == ModItems.BOUNTY_REWARD.get()) {
            BlockEntity be = event.getLevel().getBlockEntity(event.getPos());
            if (be instanceof BoardBlockEntity board) {
                board.updateCompletedBounties(player);
                held.shrink(1);
                event.setCanceled(true);
                event.setCancellationResult(InteractionResult.SUCCESS);
            }
        }
    }

    @Nullable
    private static IItemHandler getItemHandler(BlockEntity be) {
        return be.getCapability(ForgeCapabilities.ITEM_HANDLER, null).resolve().orElse(null);
    }
}