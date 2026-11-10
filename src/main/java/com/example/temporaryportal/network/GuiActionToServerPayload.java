package com.example.temporaryportal.network;

import com.example.temporaryportal.TemporaryPortalMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record GuiActionToServerPayload(String actionType, String payloadData) implements CustomPacketPayload {
    public static final Type<GuiActionToServerPayload> TYPE = 
            new Type<>(ResourceLocation.fromNamespaceAndPath(TemporaryPortalMod.MODID, "gui_action_to_server"));

    public static final StreamCodec<FriendlyByteBuf, GuiActionToServerPayload> STREAM_CODEC = 
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8,
                    GuiActionToServerPayload::actionType,
                    ByteBufCodecs.STRING_UTF8,
                    GuiActionToServerPayload::payloadData,
                    GuiActionToServerPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
