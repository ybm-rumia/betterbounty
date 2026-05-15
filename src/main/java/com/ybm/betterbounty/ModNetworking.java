package com.ybm.betterbounty;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

public class ModNetworking {
    private static final String VER = "1.0";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(BetterBounty.MODID, "main"),
            () -> VER,
            VER::equals,
            VER::equals
    );

    public static void register() {
        int id = 0;
        CHANNEL.registerMessage(id++, MissingItemsPacket.class, MissingItemsPacket::encode, MissingItemsPacket::new, MissingItemsPacket::handle);
    }
}