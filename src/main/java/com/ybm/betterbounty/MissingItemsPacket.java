package com.ybm.betterbounty;

import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public class MissingItemsPacket {
    private final List<ItemStack> missing;

    public MissingItemsPacket(List<ItemStack> missing) {
        this.missing = missing;
    }

    public static void encode(MissingItemsPacket msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.missing.size());
        for (ItemStack stack : msg.missing) {
            buf.writeItem(stack);
        }
    }

    public MissingItemsPacket(FriendlyByteBuf buf) {
        int size = buf.readInt();
        missing = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            missing.add(buf.readItem());
        }
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            Minecraft.getInstance().execute(() -> {
                JEIPlugin.bookmarkItems(missing);
            });
        });
        ctx.get().setPacketHandled(true);
    }
}