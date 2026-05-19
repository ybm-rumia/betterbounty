package com.ybm.betterbounty.mixin;

import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorageaddons.item.WirelessCraftingGridItem;
import com.ybm.betterbounty.BountyLogic;
import io.ejekta.bountiful.bounty.*;
import io.ejekta.bountiful.bounty.types.IBountyObjective;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraft.network.chat.Component;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.UUID;

@Mixin(BountyData.class)
public abstract class BountyDataMixin {

    private static final Logger LOGGER = LogManager.getLogger("BetterBounty");

    @org.spongepowered.asm.mixin.gen.Invoker(value = "rewardPlayer", remap = false)
    public abstract void invokeRewardPlayer(Player player);

    @Inject(method = "tryCashIn", at = @At("HEAD"), cancellable = true, remap = false)
    private void tryCashInReplacement(Player player, ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        cir.cancel();

        LOGGER.info("BetterBounty: tryCashInReplacement triggered!");

        if (!(player instanceof ServerPlayer serverPlayer)) {
            cir.setReturnValue(false);
            return;
        }
        if (player.level().isClientSide) {
            cir.setReturnValue(false);
            return;
        }

        // 过期检查（沿用 BountyLogic 的逻辑）
        if (BountyLogic.isBountyExpired(stack, serverPlayer.serverLevel())) {
            player.sendSystemMessage(Component.literal("悬赏令已过期，无法提交。"));
            cir.setReturnValue(false);
            return;
        }

        // 解析悬赏令需求
        List<BountyLogic.BountyRequirement> requirements = BountyLogic.getRequirements(stack);
        if (requirements.isEmpty()) {
            LOGGER.info("No requirements found in bounty NBT");
            cir.setReturnValue(false);
            return;
        }

        // 查找无线合成终端（坐标优先）
        INetwork network = getWirelessNetwork(serverPlayer);

        // 调用现有的检查与消耗逻辑（已改为 public）
        if (!BountyLogic.checkAndConsumeAll(serverPlayer, network, null, requirements)) {
            cir.setReturnValue(false);
            return;
        }

        // 发放奖励（保留原版奖励方法）
        this.invokeRewardPlayer(player);
        stack.shrink(stack.getMaxStackSize());
        cir.setReturnValue(true);
        LOGGER.info("BetterBounty: Cash in success!");
    }

    // ================= 无线网络获取（坐标优先，保留不变）=================
    private INetwork getWirelessNetwork(ServerPlayer player) {
        for (ItemStack stack : player.getAllSlots()) {
            if (stack.getItem() instanceof WirelessCraftingGridItem) {
                BlockPos pos = getNodePos(stack);
                ResourceKey<Level> dim = getNodeDimension(stack);
                if (pos != null && dim != null) {
                    INetwork net = getNetworkByPos(player, pos, dim);
                    if (net != null) return net;
                }
                UUID uuid = getNetworkId(stack);
                if (uuid != null) {
                    INetwork net = getNetworkByUuid(player, uuid);
                    if (net != null) return net;
                }
            }
        }
        if (isCuriosLoaded()) {
            return getCuriosNetwork(player);
        }
        return null;
    }

    private BlockPos getNodePos(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag != null && tag.contains("NodeX") && tag.contains("NodeY") && tag.contains("NodeZ")) {
            return new BlockPos(
                    tag.getInt("NodeX"),
                    tag.getInt("NodeY"),
                    tag.getInt("NodeZ")
            );
        }
        return null;
    }

    private ResourceKey<Level> getNodeDimension(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag != null && tag.contains("Dimension")) {
            String dimStr = tag.getString("Dimension");
            ResourceLocation dimId = ResourceLocation.tryParse(dimStr);
            if (dimId != null) {
                return ResourceKey.create(Registries.DIMENSION, dimId);
            }
        }
        return null;
    }

    private INetwork getNetworkByPos(ServerPlayer player, BlockPos pos, ResourceKey<Level> dim) {
        ServerLevel level = player.server.getLevel(dim);
        if (level == null) level = player.serverLevel();
        try {
            var manager = com.refinedmods.refinedstorage.apiimpl.API.instance().getNetworkManager(level);
            return manager.getNetwork(pos);
        } catch (Exception e) {
            return null;
        }
    }

    private UUID getNetworkId(ItemStack stack) {
        CompoundTag tag = stack.getOrCreateTag();
        if (tag.hasUUID("NetworkId")) {
            return tag.getUUID("NetworkId");
        }
        return null;
    }

    private boolean isCuriosLoaded() {
        try {
            Class.forName("top.theillusivec4.curios.api.CuriosApi");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private INetwork getCuriosNetwork(ServerPlayer player) {
        try {
            var curiosInv = top.theillusivec4.curios.api.CuriosApi.getCuriosInventory(player);
            var handler = curiosInv.resolve().orElse(null);
            if (handler != null) {
                var result = handler.findFirstCurio(stack -> stack.getItem() instanceof WirelessCraftingGridItem);
                if (result.isPresent()) {
                    ItemStack stack = result.get().stack();
                    BlockPos pos = getNodePos(stack);
                    ResourceKey<Level> dim = getNodeDimension(stack);
                    if (pos != null && dim != null) {
                        return getNetworkByPos(player, pos, dim);
                    }
                    UUID uuid = getNetworkId(stack);
                    if (uuid != null) {
                        return getNetworkByUuid(player, uuid);
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private INetwork getNetworkByUuid(ServerPlayer player, UUID uuid) {
        try {
            var manager = com.refinedmods.refinedstorage.apiimpl.API.instance().getNetworkManager(player.serverLevel());
            var method = manager.getClass().getMethod("getNetwork", UUID.class);
            return (INetwork) method.invoke(manager, uuid);
        } catch (Exception ignored) {
            return null;
        }
    }
}