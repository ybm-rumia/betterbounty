package com.ybm.betterbounty.mixin;

import io.ejekta.bountiful.content.gui.BoardScreenHandler;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 修复悬赏板 UI 关闭时手上物品消失的 Bug
 * 原理：在 BoardScreenHandler.removed 执行前，手动将 carried 物品安全归还给玩家。
 */
@Mixin(BoardScreenHandler.class)
public class BoardScreenHandlerFix {

    @Inject(method = "removed", at = @At("HEAD"))
    private void beforeRemoved(Player player, CallbackInfo ci) {
        ItemStack carried = this.getCarried();
        if (!carried.isEmpty()) {
            this.setCarried(ItemStack.EMPTY);
            if (!player.addItem(carried)) {
                player.drop(carried, false);
            }
        }
    }

    private ItemStack getCarried() {
        return ((net.minecraft.world.inventory.AbstractContainerMenu) (Object) this).getCarried();
    }

    private void setCarried(ItemStack stack) {
        ((net.minecraft.world.inventory.AbstractContainerMenu) (Object) this).setCarried(stack);
    }
}