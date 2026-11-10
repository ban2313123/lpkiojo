package com.example.temporaryportal;

import com.example.temporaryportal.commands.PortalAdminCommands;
import com.example.temporaryportal.config.ArenaConfigManager;
import com.example.temporaryportal.network.NetworkHandler;
import com.example.temporaryportal.world.ArenaManager;
import com.example.temporaryportal.world.DimensionSetup;
import com.example.temporaryportal.world.LeaderboardManager;
import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.Registries;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import org.slf4j.Logger;

@Mod(TemporaryPortalMod.MODID)
public class TemporaryPortalMod {
    public static final String MODID = "temporaryportal";
    public static final Logger LOGGER = LogUtils.getLogger();

    public TemporaryPortalMod(IEventBus modEventBus) {
        LOGGER.info("Initializing Temporary Portal Mod...");
        
        // Print a huge ASCII cat to the server log
        LOGGER.info("""
        
        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(NetworkHandler::registerPayloads);

        DimensionSetup.DIMENSION_TYPES.register(modEventBus);

        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(this::onServerStarting);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info("Common setup for Temporary Portal completed.");
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        PortalAdminCommands.register(event.getDispatcher());
    }

    private void onServerStarting(ServerStartingEvent event) {
        ArenaConfigManager.init(event.getServer());
        ArenaManager.init(event.getServer());
        LeaderboardManager.init(event.getServer());
    }

    private void onServerStopping(ServerStoppingEvent event) {
        // Save leaderboard data on server shutdown to prevent data loss
        LeaderboardManager.save();
        LOGGER.info("Leaderboard data saved on server stop.");
    }
}
