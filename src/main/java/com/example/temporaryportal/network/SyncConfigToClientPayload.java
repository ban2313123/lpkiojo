package com.example.temporaryportal.network;

import com.example.temporaryportal.TemporaryPortalMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record SyncConfigToClientPayload(String jsonConfig, boolean openScreen) implements CustomPacketPayload {
    public static final Type<SyncConfigToClientPayload> TYPE = 
            new Type<>(ResourceLocation.fromNamespaceAndPath(TemporaryPortalMod.MODID, "sync_config_to_client"));

    public static final StreamCodec<FriendlyByteBuf, SyncConfigToClientPayload> STREAM_CODEC = 
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8,
                    SyncConfigToClientPayload::jsonConfig,
                    ByteBufCodecs.BOOL,
                    SyncConfigToClientPayload::openScreen,
                    SyncConfigToClientPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
