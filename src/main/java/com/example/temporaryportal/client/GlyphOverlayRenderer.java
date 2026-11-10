package com.example.temporaryportal.client;

import com.example.temporaryportal.TemporaryPortalMod;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;

import java.util.Random;

@EventBusSubscriber(modid = TemporaryPortalMod.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public class GlyphOverlayRenderer implements LayeredDraw.Layer {
    private static int activeTicks = 0;
    private static int totalTicks = 0;
    private static final Random RANDOM = new Random(42);

    // Rain drop offsets for 60 columns
    private static final int NUM_COLUMNS = 80;
    private static final float[] columnOffsets = new float[NUM_COLUMNS];
    private static final float[] columnSpeeds = new float[NUM_COLUMNS];

    static {
        for (int i = 0; i < NUM_COLUMNS; i++) {
            columnOffsets[i] = RANDOM.nextFloat() * 100f;
            columnSpeeds[i] = 2.5f + RANDOM.nextFloat() * 3.5f;
        }
    }

    public static void trigger(int ticks) {
        activeTicks = ticks;
        totalTicks = ticks;
    }

    @SubscribeEvent
    public static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAboveAll(ResourceLocation.fromNamespaceAndPath(TemporaryPortalMod.MODID, "glyph_layer"), new GlyphOverlayRenderer());
    }

    @Override
    public void render(GuiGraphics guiGraphics, DeltaTracker deltaTracker) {
        if (activeTicks <= 0) return;

        Minecraft mc = Minecraft.getInstance();
        int width = mc.getWindow().getGuiScaledWidth();
        int height = mc.getWindow().getGuiScaledHeight();

        // Smooth cinematic curve: smooth fade-in, intense middle, smooth fade-out
        float progress = 1.0f - ((float) activeTicks / (float) Math.max(1, totalTicks));
        float alpha;
        if (progress < 0.2f) {
            alpha = progress / 0.2f; // Fade in
        } else if (progress > 0.7f) {
            alpha = (1.0f - progress) / 0.3f; // Fade out
        } else {
            alpha = 1.0f; // Full curtain
        }
        alpha = Math.max(0.0f, Math.min(1.0f, alpha));

        // 1. Dark Vignette Pulse / Cosmic Backdrop
        int bgAlpha = (int) (alpha * 215); // up to 85% darkness
        int bgColor = (bgAlpha << 24) | 0x070114;
        guiGraphics.fill(0, 0, width, height, bgColor);

        // 2. Raining Matrix-Style Glyph Columns
        int fontH = mc.font.lineHeight;
        int colWidth = width / NUM_COLUMNS;
        if (colWidth < 10) colWidth = 12;

        int frame = totalTicks - activeTicks;

        for (int i = 0; i < NUM_COLUMNS; i++) {
            int colX = i * colWidth;
            if (colX > width) break;

            columnOffsets[i] += columnSpeeds[i] * 0.25f;
            float currentY = columnOffsets[i] % (height + 60) - 30;

            // Render a stream of 8 falling runes per column with fading opacity
            for (int r = 0; r < 8; r++) {
                int runeY = (int) (currentY - (r * (fontH + 3)));
                if (runeY >= -10 && runeY <= height) {
                    float runeAlpha = alpha * (1.0f - ((float) r / 8.0f));
                    if (runeAlpha > 0.05f) {
                        int rAlphaInt = (int) (runeAlpha * 255);
                        int charColor = (r == 0) ? ((rAlphaInt << 24) | 0xE0AAFF) // Bright cyan/white head
                                                 : ((rAlphaInt << 24) | 0x7B2CBF); // Deep purple tail

                        char runeChar = (char) ('a' + (Math.abs(i * 7 + r * 13 + frame) % 26));
                        // §k obfuscated runic glyph
                        guiGraphics.drawString(mc.font, "§k" + runeChar, colX, runeY, charColor, false);
                    }
                }
            }
        }

        // 3. Central Mystic Spatial Rupture Emblem
        int centerAlpha = (int) (alpha * 255);
        if (centerAlpha > 20) {
            int titleColor = (centerAlpha << 24) | 0xFFAAFF;
            int subColor = (centerAlpha << 24) | 0xCCCCCC;

            // Animated pulse scale for title
            String title = "§5§l« ПРОСТРАНСТВЕННЫЙ РАЗРЫВ »";
            guiGraphics.drawCenteredString(mc.font, title, width / 2, height / 2 - 14, titleColor);

            // Shifting runic subtitle
            String runeSub = "§kabcdef§r  §dТЕЛЕПОРТАЦИЯ  §kabcdef";
            guiGraphics.drawCenteredString(mc.font, runeSub, width / 2, height / 2 + 2, subColor);
        }

        activeTicks--;
    }
}
