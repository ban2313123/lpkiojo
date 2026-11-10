package com.example.temporaryportal.network;

import com.example.temporaryportal.client.GlyphOverlayRenderer;
import com.example.temporaryportal.client.gui.PortalConfigScreen;
import net.minecraft.client.Minecraft;

public class ClientPacketHandler {
    public static void handleGlyphOverlay(GlyphOverlayPayload payload) {
        GlyphOverlayRenderer.trigger(payload.durationTicks());
    }

    public static void handleConfigSync(SyncConfigToClientPayload payload) {
        Minecraft mc = Minecraft.getInstance();
        PortalConfigScreen.updateCache(payload.jsonConfig());
        if (payload.openScreen()) {
            mc.setScreen(new PortalConfigScreen());
        } else if (mc.screen instanceof PortalConfigScreen screen) {
            screen.refreshData();
        }
    }
}
