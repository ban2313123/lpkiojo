package com.example.temporaryportal.network;

import com.example.temporaryportal.TemporaryPortalMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record GlyphOverlayPayload(int durationTicks) implements CustomPacketPayload {
    public static final Type<GlyphOverlayPayload> TYPE = 
            new Type<>(ResourceLocation.fromNamespaceAndPath(TemporaryPortalMod.MODID, "glyph_overlay"));

    public static final StreamCodec<FriendlyByteBuf, GlyphOverlayPayload> STREAM_CODEC = 
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT,
                    GlyphOverlayPayload::durationTicks,
                    GlyphOverlayPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
