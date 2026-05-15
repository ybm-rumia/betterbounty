package com.ybm.betterbounty;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(BetterBounty.MODID)
public class BetterBounty {
    public static final String MODID = "betterbounty";
    public static final Logger LOGGER = LogManager.getLogger();

    public BetterBounty() {
        var bus = FMLJavaModLoadingContext.get().getModEventBus();
        bus.addListener(this::setup);
        ModItems.ITEMS.register(bus);
        MinecraftForge.EVENT_BUS.register(new RightClickHandler());
    }

    private void setup(FMLCommonSetupEvent event) {
        ModNetworking.register();
        LOGGER.info("BetterBounty network registered.");
    }
}