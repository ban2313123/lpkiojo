package com.example.temporaryportal.world;

import com.example.temporaryportal.TemporaryPortalMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import net.neoforged.neoforge.registries.DeferredRegister;

public class DimensionSetup {
    public static final DeferredRegister<DimensionType> DIMENSION_TYPES = 
            DeferredRegister.create(Registries.DIMENSION_TYPE, TemporaryPortalMod.MODID);

    public static final ResourceKey<Level> ARENA_LEVEL_KEY = 
            ResourceKey.create(Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath(TemporaryPortalMod.MODID, "arena"));

    public static final ResourceKey<DimensionType> ARENA_DIMENSION_TYPE_KEY = 
            ResourceKey.create(Registries.DIMENSION_TYPE, ResourceLocation.fromNamespaceAndPath(TemporaryPortalMod.MODID, "arena_type"));
}
