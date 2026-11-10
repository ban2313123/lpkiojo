package com.example.temporaryportal.world;

import com.example.temporaryportal.TemporaryPortalMod;
import com.example.temporaryportal.config.ArenaConfigManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.io.File;
import java.io.FileInputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class StructureHandler {

    // Cache loaded structure templates to avoid reading from disk on every regeneration
    private static final ConcurrentHashMap<String, CachedStructure> structureCache = new ConcurrentHashMap<>();
    private static final int MAX_CACHE_SIZE = 10;

    private static class CachedStructure {
        final CompoundTag nbt;
        final long lastModified;
        final StructureData data;
        CachedStructure(CompoundTag nbt, long lastModified, StructureData data) {
            this.nbt = nbt;
            this.lastModified = lastModified;
            this.data = data;
        }
    }

    /**
     * Holds all arena data extracted from the .nbt structure file.
     * Marker blocks in structure:
     *   GLOWSTONE = chest spawn point
     *   SEA_LANTERN = mob spawn point
     *   END_PORTAL_FRAME = exit portal position
     */
    public static class StructureData {
        public int minX = Integer.MAX_VALUE;
        public int minY = Integer.MAX_VALUE;
        public int minZ = Integer.MAX_VALUE;
        public int maxX = Integer.MIN_VALUE;
        public int maxY = Integer.MIN_VALUE;
        public int maxZ = Integer.MIN_VALUE;
        public List<BlockPos> chestSpawnPoints = new ArrayList<>();
        public List<BlockPos> mobSpawnPoints = new ArrayList<>();
        public BlockPos exitPortalPos = null;
        public boolean hasData = false;

        public boolean isValid() {
            return hasData && minX <= maxX && minY <= maxY && minZ <= maxZ;
        }
    }

    /**
     * Generates a 30x30 arena platform centered at structurePlacementPos.
     * Optimized: reuse BlockPos where possible to reduce allocations.
     */
    public static void generatePlatform(ServerLevel arenaLevel) {
        BlockPos center = ArenaConfigManager.parseBlockPos(ArenaConfigManager.getData().structurePlacementPos);
        int cx = center.getX();
        int cy = center.getY();
        int cz = center.getZ();

        int radius = 15; // 30x30 (-15 to +15)
        
        // Pre-get default block states to avoid repeated calls
        net.minecraft.world.level.block.state.BlockState blackstoneBricks = Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState();
        net.minecraft.world.level.block.state.BlockState stoneBricks = Blocks.STONE_BRICKS.defaultBlockState();
        net.minecraft.world.level.block.state.BlockState crackedBricks = Blocks.CRACKED_STONE_BRICKS.defaultBlockState();
        net.minecraft.world.level.block.state.BlockState air = Blocks.AIR.defaultBlockState();
        net.minecraft.world.level.block.state.BlockState cryingObsidian = Blocks.CRYING_OBSIDIAN.defaultBlockState();
        net.minecraft.world.level.block.state.BlockState deepslateWall = Blocks.POLISHED_DEEPSLATE_WALL.defaultBlockState();
        net.minecraft.world.level.block.state.BlockState soulLantern = Blocks.SOUL_LANTERN.defaultBlockState();

        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                int bx = cx + x;
                int bz = cz + z;
                
                boolean isEdge = (Math.abs(x) == radius || Math.abs(z) == radius);
                boolean isCorner = (Math.abs(x) == radius && Math.abs(z) == radius);
                
                // Floor
                BlockPos floorPos = new BlockPos(bx, cy - 1, bz);
                arenaLevel.setBlock(floorPos, isEdge ? blackstoneBricks : ((x + z) % 2 == 0 ? stoneBricks : crackedBricks), 3);

                // Wall at edge
                if (isEdge) {
                    BlockPos wallPos = new BlockPos(bx, cy, bz);
                    if (isCorner) {
                        // Corner pillars
                        arenaLevel.setBlock(wallPos, cryingObsidian, 3);
                        arenaLevel.setBlock(new BlockPos(bx, cy + 1, bz), soulLantern, 3);
                    } else {
                        // Regular wall
                        arenaLevel.setBlock(wallPos, deepslateWall, 3);
                    }
                }

                // Clear air above
                for (int airY = cy + 1; airY <= cy + 5; airY++) {
                    BlockPos airPos = new BlockPos(bx, airY, bz);
                    if (!arenaLevel.isEmptyBlock(airPos)) {
                        arenaLevel.setBlock(airPos, air, 3);
                    }
                }
            }
        }

        TemporaryPortalMod.LOGGER.info("Generated 30x30 Arena platform around {}", center);
    }

    /**
     * Loads the structure from .nbt file and extracts all arena data:
     * - Boundaries (min/max X/Y/Z from all blocks in structure)
     * - Chest spawn points (GLOWSTONE blocks)
     * - Mob spawn points (SEA_LANTERN blocks)
     * - Exit portal position (END_PORTAL_FRAME block)
     *
     * Marker blocks are removed after placement (replaced with air).
     * Returns the extracted StructureData, or null if no structure file found.
     */
    public static StructureData loadStructureData() {
        String fileName = ArenaConfigManager.getData().structureFileName;
        Path structPath = ArenaConfigManager.getStructuresDir().resolve(fileName);
        File structFile = structPath.toFile();

        if (!structFile.exists()) {
            TemporaryPortalMod.LOGGER.info("Structure file not found at {}. Will use default platform.", structPath);
            return null;
        }

        // Try to load from cache first
        CachedStructure cached = structureCache.get(fileName);
        if (cached != null && cached.lastModified == structFile.lastModified()) {
            return cached.data;
        }

        // Load from disk and extract data
        try (FileInputStream inputStream = new FileInputStream(structFile)) {
            CompoundTag nbt = NbtIo.readCompressed(inputStream, NbtAccounter.unlimitedHeap());
            StructureData data = extractDataFromNbt(nbt);
            data.hasData = true;

            // Evict oldest entry if cache is full
            if (!structureCache.containsKey(fileName) && structureCache.size() >= MAX_CACHE_SIZE) {
                String oldestKey = structureCache.keySet().iterator().next();
                structureCache.remove(oldestKey);
            }
            structureCache.put(fileName, new CachedStructure(nbt, structFile.lastModified(), data));
            TemporaryPortalMod.LOGGER.info("Loaded structure {} from disk: bounds [{},{},{}-{}},{},{}], {} chests, {} mobs, exit={}",
                fileName, data.minX, data.minY, data.minZ, data.maxX, data.maxY, data.maxZ,
                data.chestSpawnPoints.size(), data.mobSpawnPoints.size(), data.exitPortalPos);
            return data;
        } catch (Exception e) {
            TemporaryPortalMod.LOGGER.error("Failed to load structure from disk: " + fileName, e);
            return null;
        }
    }

    /**
     * Parses the NBT tags to extract arena data without placing the structure.
     * Reads the "palettes" and "blocks" arrays to find marker blocks and compute bounds.
     */
    private static StructureData extractDataFromNbt(CompoundTag nbt) {
        StructureData data = new StructureData();

        try {
            // Structure NBT format: "blocks" array contains block placements with palette indices
            // "palettes" compound contains block state definitions by index
            CompoundTag palettes = nbt.getCompound("palettes");
            // Default palette if no sub-palettes
            CompoundTag defaultPalette = palettes.contains("default") ? palettes.getCompound("default") : palettes;
            net.minecraft.nbt.ListTag paletteList = defaultPalette.getList("states", 10); // 10 = compound

            // Build palette index -> block ID mapping
            java.util.Map<Integer, String> paletteMap = new java.util.HashMap<>();
            for (int i = 0; i < paletteList.size(); i++) {
                CompoundTag state = paletteList.getCompound(i);
                String blockId = state.getString("Name");
                paletteMap.put(i, blockId);
            }

            // Read blocks array
            net.minecraft.nbt.ListTag blocksList = nbt.getList("blocks", 10); // 10 = compound
            for (int i = 0; i < blocksList.size(); i++) {
                CompoundTag block = blocksList.getCompound(i);
                int x = block.getShort("x");
                int y = block.getShort("y");
                int z = block.getShort("z");
                int paletteIndex = block.getShort("state");

                // Update bounds
                if (x < data.minX) data.minX = x;
                if (y < data.minY) data.minY = y;
                if (z < data.minZ) data.minZ = z;
                if (x > data.maxX) data.maxX = x;
                if (y > data.maxY) data.maxY = y;
                if (z > data.maxZ) data.maxZ = z;

                String blockId = paletteMap.get(paletteIndex);
                if (blockId == null) continue;

                // Check for marker blocks
                if (blockId.equals("minecraft:glowstone")) {
                    data.chestSpawnPoints.add(new BlockPos(x, y, z));
                } else if (blockId.equals("minecraft:sea_lantern")) {
                    data.mobSpawnPoints.add(new BlockPos(x, y, z));
                } else if (blockId.equals("minecraft:end_portal_frame")) {
                    data.exitPortalPos = new BlockPos(x, y, z);
                }
            }

            // Also check entities in structure for additional data
            net.minecraft.nbt.ListTag entitiesList = nbt.getList("entities", 10);
            for (int i = 0; i < entitiesList.size(); i++) {
                CompoundTag entity = entitiesList.getCompound(i);
                String entityType = entity.getString("id");
                // Entities with custom tags can define additional spawn points
                // Pos is a double array (tag type 9) in NBT
                if (entityType.contains("arena_chest_spawn") && entity.contains("Pos")) {
                    net.minecraft.nbt.ListTag posList = entity.getList("Pos", 6); // 6 = double
                    if (posList.size() >= 3) {
                        data.chestSpawnPoints.add(new BlockPos((int)posList.getDouble(0), (int)posList.getDouble(1), (int)posList.getDouble(2)));
                    }
                } else if (entityType.contains("arena_mob_spawn") && entity.contains("Pos")) {
                    net.minecraft.nbt.ListTag posList = entity.getList("Pos", 6);
                    if (posList.size() >= 3) {
                        data.mobSpawnPoints.add(new BlockPos((int)posList.getDouble(0), (int)posList.getDouble(1), (int)posList.getDouble(2)));
                    }
                } else if (entityType.contains("arena_exit_portal") && entity.contains("Pos")) {
                    net.minecraft.nbt.ListTag posList = entity.getList("Pos", 6);
                    if (posList.size() >= 3) {
                        data.exitPortalPos = new BlockPos((int)posList.getDouble(0), (int)posList.getDouble(1), (int)posList.getDouble(2));
                    }
                }
            }

        } catch (Exception e) {
            TemporaryPortalMod.LOGGER.error("Failed to extract data from structure NBT", e);
        }

        return data;
    }

    /**
     * Places the structure in the world. Marker blocks (glowstone, sea_lantern, end_portal_frame)
     * are replaced with air during placement so they don't remain visible.
     */
    public static boolean placeArenaStructure(ServerLevel arenaLevel) {
        String fileName = ArenaConfigManager.getData().structureFileName;
        Path structPath = ArenaConfigManager.getStructuresDir().resolve(fileName);
        File structFile = structPath.toFile();

        if (!structFile.exists()) {
            TemporaryPortalMod.LOGGER.info("Structure file not found at {}. Generating default platform.", structPath);
            generatePlatform(arenaLevel);
            return false;
        }

        // Try to load from cache first
        CachedStructure cached = structureCache.get(fileName);
        CompoundTag nbt;

        if (cached != null && cached.lastModified == structFile.lastModified()) {
            nbt = cached.nbt;
        } else {
            try (FileInputStream inputStream = new FileInputStream(structFile)) {
                nbt = NbtIo.readCompressed(inputStream, NbtAccounter.unlimitedHeap());
                if (!structureCache.containsKey(fileName) && structureCache.size() >= MAX_CACHE_SIZE) {
                    String oldestKey = structureCache.keySet().iterator().next();
                    structureCache.remove(oldestKey);
                }
                // Extract data if not already cached
                StructureData data = (cached != null) ? cached.data : extractDataFromNbt(nbt);
                if (data == null) data = extractDataFromNbt(nbt);
                data.hasData = true;
                structureCache.put(fileName, new CachedStructure(nbt, structFile.lastModified(), data));
                TemporaryPortalMod.LOGGER.info("Loaded structure {} from disk into cache.", fileName);
            } catch (Exception e) {
                TemporaryPortalMod.LOGGER.error("Failed to load structure from disk: " + fileName, e);
                return false;
            }
        }

        try {
            StructureTemplate template = new StructureTemplate();
            template.load(arenaLevel.holderLookup(Registries.BLOCK), nbt);

            BlockPos origin = ArenaConfigManager.parseBlockPos(ArenaConfigManager.getData().structurePlacementPos);
            StructurePlaceSettings settings = new StructurePlaceSettings()
                    .setMirror(Mirror.NONE)
                    .setRotation(Rotation.NONE)
                    .setIgnoreEntities(false);

            template.placeInWorld(arenaLevel, origin, origin, settings, arenaLevel.getRandom(), 2);

            // After placement, remove marker blocks (replace with air)
            StructureData structData = loadStructureData();
            if (structData != null && structData.isValid()) {
                removeMarkerBlocks(arenaLevel, origin, structData);
            }

            TemporaryPortalMod.LOGGER.info("Arena structure successfully placed at {}", origin);
            return true;
        } catch (Exception e) {
            TemporaryPortalMod.LOGGER.error("Failed to place structure: " + fileName, e);
            return false;
        }
    }

    /**
     * Removes marker blocks from the placed structure, replacing them with air.
     */
    private static void removeMarkerBlocks(ServerLevel arenaLevel, BlockPos origin, StructureData data) {
        net.minecraft.world.level.block.state.BlockState air = Blocks.AIR.defaultBlockState();

        // Remove chest spawn markers (glowstone)
        for (BlockPos pos : data.chestSpawnPoints) {
            BlockPos worldPos = pos.offset(origin);
            arenaLevel.setBlock(worldPos, air, 3);
        }

        // Remove mob spawn markers (sea_lantern)
        for (BlockPos pos : data.mobSpawnPoints) {
            BlockPos worldPos = pos.offset(origin);
            arenaLevel.setBlock(worldPos, air, 3);
        }

        // Remove exit portal marker (end_portal_frame)
        if (data.exitPortalPos != null) {
            BlockPos worldPos = data.exitPortalPos.offset(origin);
            arenaLevel.setBlock(worldPos, air, 3);
        }
    }
    
    /**
     * Clears the structure cache. Call this when config is reloaded and structure file may have changed.
     */
    public static void clearCache() {
        structureCache.clear();
        TemporaryPortalMod.LOGGER.info("Structure cache cleared.");
    }
}
