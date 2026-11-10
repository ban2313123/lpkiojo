package com.example.temporaryportal.config;

import com.example.temporaryportal.TemporaryPortalMod;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.phys.AABB;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

public class ArenaConfigManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static File configFile;
    private static Path structuresDir;
    // AtomicReference ensures thread-safe atomic replacement of ConfigData on hot-reload
    // All readers see either the old or new config, never a partially-written one
    private static final AtomicReference<ConfigData> dataRef = new AtomicReference<>(new ConfigData());
    private static volatile long lastModifiedTime = 0;

    // Caches for frequently parsed values to avoid repeated string operations
    private static final ConcurrentHashMap<String, BlockPos> BLOCK_POS_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, ResourceLocation> RESOURCE_LOC_CACHE = new ConcurrentHashMap<>();

    /**
     * Clears the parsing caches. Call this when config is reloaded to avoid stale data.
     */
    public static void clearCaches() {
        BLOCK_POS_CACHE.clear();
        RESOURCE_LOC_CACHE.clear();
    }

    public static class ArenaBounds {
        public boolean enableBounds = true;
        public int minX = -60;
        public int minY = 50;
        public int minZ = -60;
        public int maxX = 60;
        public int maxY = 100;
        public int maxZ = 60;

        public ArenaBounds() {}
        public ArenaBounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxY = maxY;
            this.maxZ = maxZ;
        }

        public boolean contains(double x, double y, double z) {
            return x >= minX && x <= maxX &&
                   y >= minY && y <= maxY &&
                   z >= minZ && z <= maxZ;
        }

        public AABB toAABB() {
            return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
        }
    }

    public static class ConfigData {
        // Schedule
        public boolean enableSchedule = true;
        public int scheduleStartHour = 20;
        public int scheduleStartMinute = 0;
        public int scheduleEndHour = 22;
        public int scheduleEndMinute = 0;

        // Timers in seconds
        public int portalStayDurationSeconds = 600; // 10 minutes
        public int portalCooldownSeconds = 1800;     // 30 minutes
        public int playerStayDurationSeconds = 900;  // 15 minutes
        public int chestRespawnSeconds = 300;        // 5 minutes
        public int mobWaveSeconds = 60;             // 2 minutes

        // Arena boundary configuration — Gson may deserialize as null if missing from JSON
        public ArenaBounds arenaBounds = null;

        // Structure file in config/temporaryportal/structures/
        public String structureFileName = "arena_structure.nbt";
        public String structurePlacementPos = "0, 64, 0";

        // Coordinates (stored as "x, y, z")
        public List<String> overworldEntrancePoints = new ArrayList<>();
        public List<String> arenaExitPoints = new ArrayList<>();
        public List<String> chestSpawnPoints = new ArrayList<>();
        public List<String> mobSpawnPoints = new ArrayList<>();

        // Mobs config
        public List<MobEntry> mobs = new ArrayList<>();

        // Loot table or item list for chests
        public List<LootItemEntry> chestLoot = new ArrayList<>();
        
        // Post-deserialization fix: ensure no null collections or bounds
        public void fixNulls() {
            if (arenaBounds == null) arenaBounds = new ArenaBounds();
            if (overworldEntrancePoints == null) overworldEntrancePoints = new ArrayList<>();
            if (arenaExitPoints == null) arenaExitPoints = new ArrayList<>();
            if (chestSpawnPoints == null) chestSpawnPoints = new ArrayList<>();
            if (mobSpawnPoints == null) mobSpawnPoints = new ArrayList<>();
            if (mobs == null) mobs = new ArrayList<>();
            if (chestLoot == null) chestLoot = new ArrayList<>();
        }
    }

        public static class MobEntry {
        public String entityId = "minecraft:zombie";
        public String customName = "§cГладиатор Арены";
        public double maxHealth = 40.0;
        public double attackDamage = 6.0;
        public double movementSpeed = 0.28;
        public int count = 2;
        public String mainHandItem = "minecraft:iron_sword";
        public String helmet = "minecraft:iron_helmet";
        public String chestplate = "minecraft:iron_chestplate";
        public String leggings = "minecraft:iron_leggings";
        public String boots = "minecraft:iron_boots";
        public List<String> potionEffects = new ArrayList<>();
        public String customNbt = "";

        public MobEntry() {}
        public MobEntry(String entityId, String name, double health, String weapon) {
            this.entityId = entityId;
            this.customName = name;
            this.maxHealth = health;
            this.mainHandItem = weapon;
            this.customNbt = "";
        }
        public MobEntry(String entityId, String name, double health, String weapon, String customNbt) {
            this.entityId = entityId;
            this.customName = name;
            this.maxHealth = health;
            this.mainHandItem = weapon;
            this.customNbt = customNbt != null ? customNbt : "";
        }
    }

    public static class LootItemEntry {
        public String itemId = "minecraft:diamond";
        public int minCount = 1;
        public int maxCount = 3;
        public double chance = 0.5;

        public LootItemEntry() {}
        public LootItemEntry(String itemId, int minCount, int maxCount, double chance) {
            this.itemId = itemId;
            this.minCount = minCount;
            this.maxCount = maxCount;
            this.chance = chance;
        }
    }

    public static void init(MinecraftServer server) {
        Path configDirPath = net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get().resolve("temporaryportal");
        structuresDir = configDirPath.resolve("structures");
        configFile = configDirPath.resolve("portal_config.json").toFile();

        try {
            Files.createDirectories(structuresDir);
        } catch (IOException e) {
            TemporaryPortalMod.LOGGER.error("Failed to create config/temporaryportal/structures directory", e);
        }

        if (!configFile.exists()) {
            populateDefaultData();
            save();
        } else {
            load();
        }
    }

    private static void populateDefaultData() {
        ConfigData newData = new ConfigData();
        for (int i = 0; i < 10; i++) {
            newData.overworldEntrancePoints.add((i * 100) + ", 70, " + (i * 100));
        }
        for (int i = 0; i < 10; i++) {
            newData.arenaExitPoints.add((i * 15 - 75) + ", 65, " + (i * 15 - 75));
        }
        newData.chestSpawnPoints.add("10, 65, 10");
        newData.chestSpawnPoints.add("-10, 65, 10");
        newData.chestSpawnPoints.add("10, 65, -10");
        newData.chestSpawnPoints.add("-10, 65, -10");

        newData.mobSpawnPoints.add("0, 65, 20");
        newData.mobSpawnPoints.add("20, 65, 0");
        newData.mobSpawnPoints.add("0, 65, -20");
        newData.mobSpawnPoints.add("-20, 65, 0");

        newData.mobs.add(new MobEntry("minecraft:zombie", "§4Берсерк Арены", 45.0, "minecraft:iron_axe"));
        newData.mobs.add(new MobEntry("minecraft:skeleton", "§6Снайпер Пустоты", 30.0, "minecraft:bow"));

        newData.chestLoot.add(new LootItemEntry("minecraft:golden_apple", 1, 2, 0.7));
        newData.chestLoot.add(new LootItemEntry("minecraft:diamond", 1, 3, 0.4));
        newData.chestLoot.add(new LootItemEntry("minecraft:iron_ingot", 2, 6, 0.9));
        newData.chestLoot.add(new LootItemEntry("minecraft:ender_pearl", 1, 2, 0.5));
        newData.chestLoot.add(new LootItemEntry("minecraft:potion", 1, 1, 0.6));
        
        // Atomically replace the config data
        dataRef.set(newData);
    }

        public static void resetToDefaults() {
        populateDefaultData();
        save();
        TemporaryPortalMod.LOGGER.info("Config reset to defaults.");
    }

    public static synchronized void load() {
        if (!configFile.exists()) return;
        try (FileReader reader = new FileReader(configFile)) {
            ConfigData loaded = GSON.fromJson(reader, ConfigData.class);
            if (loaded != null) {
                // Fix null fields that Gson may not initialize from old JSON files
                loaded.fixNulls();
                // Atomically replace — readers will see the new config immediately
                dataRef.set(loaded);
                lastModifiedTime = configFile.lastModified();
                clearCaches();
                // Clear structure cache when config is reloaded — structure file may have changed
                com.example.temporaryportal.world.StructureHandler.clearCache();
                TemporaryPortalMod.LOGGER.info("Dynamic config loaded: portal_config.json");
            }
        } catch (Exception e) {
            TemporaryPortalMod.LOGGER.error("Failed to load portal_config.json", e);
        }
    }

    public static synchronized void save() {
        try {
            configFile.getParentFile().mkdirs();
            try (FileWriter writer = new FileWriter(configFile)) {
                GSON.toJson(dataRef.get(), writer);
            }
            lastModifiedTime = configFile.lastModified();
        } catch (Exception e) {
            TemporaryPortalMod.LOGGER.error("Failed to save portal_config.json", e);
        }
    }

    /**
     * Checks if the config file was modified externally on disk (hot-reload without restart)
     */
    public static void checkFileHotReload() {
        if (configFile != null && configFile.exists()) {
            long currentModTime = configFile.lastModified();
            if (currentModTime > lastModifiedTime) {
                TemporaryPortalMod.LOGGER.info("Detected external change in portal_config.json. Hot-reloading...");
                load();
            }
        }
    }

    /**
     * Returns the current ConfigData atomically.
     * Callers receive a reference that is guaranteed to be a complete, consistent config snapshot.
     * On hot-reload, a new ConfigData is atomically swapped in, so existing references remain valid.
     */
    public static ConfigData getData() {
        return dataRef.get();
    }

    public static Path getStructuresDir() {
        return structuresDir;
    }

    public static BlockPos parseBlockPos(String str) {
        return BLOCK_POS_CACHE.computeIfAbsent(str, s -> {
            try {
                // Manual split to avoid regex overhead of String.split(",")
                int len = s.length();
                int comma1 = -1, comma2 = -1;
                for (int i = 0; i < len; i++) {
                    if (s.charAt(i) == ',') {
                        if (comma1 < 0) comma1 = i;
                        else { comma2 = i; break; }
                    }
                }
                if (comma1 < 0 || comma2 < 0) return BlockPos.ZERO;
                return new BlockPos(
                        Integer.parseInt(s.substring(0, comma1).trim()),
                        Integer.parseInt(s.substring(comma1 + 1, comma2).trim()),
                        Integer.parseInt(s.substring(comma2 + 1).trim())
                );
            } catch (Exception e) {
                return BlockPos.ZERO;
            }
        });
    }

    /**
     * Parses a ResourceLocation with caching to avoid repeated string parsing.
     */
    public static ResourceLocation parseResourceLocation(String id) {
        return RESOURCE_LOC_CACHE.computeIfAbsent(id, ResourceLocation::parse);
    }

    /**
     * Adds a position string to the specified point list by type.
     * Thread-safe: captures current config snapshot atomically before mutating.
     * @param type one of "entrance", "exit", "chest", "mob"
     * @param posString position in "x, y, z" format
     */
    public static void addPoint(String type, String posString) {
        ConfigData data = getData();
        if ("entrance".equals(type)) data.overworldEntrancePoints.add(posString);
        else if ("exit".equals(type)) data.arenaExitPoints.add(posString);
        else if ("chest".equals(type)) data.chestSpawnPoints.add(posString);
        else if ("mob".equals(type)) data.mobSpawnPoints.add(posString);
    }

    /**
     * Clears the specified point list by type.
     * Thread-safe: captures current config snapshot atomically before mutating.
     * @param type one of "entrance", "exit", "chest", "mob"
     */
    public static void clearPoints(String type) {
        ConfigData data = getData();
        if ("entrance".equals(type)) data.overworldEntrancePoints.clear();
        else if ("exit".equals(type)) data.arenaExitPoints.clear();
        else if ("chest".equals(type)) data.chestSpawnPoints.clear();
        else if ("mob".equals(type)) data.mobSpawnPoints.clear();
    }

    /**
     * Returns the size of the specified point list by type.
     * Thread-safe: reads from current atomic config snapshot.
     * @param type one of "entrance", "exit", "chest", "mob"
     */
    public static int getPointCount(String type) {
        ConfigData data = getData();
        switch (type) {
            case "entrance": return data.overworldEntrancePoints.size();
            case "exit": return data.arenaExitPoints.size();
            case "chest": return data.chestSpawnPoints.size();
            case "mob": return data.mobSpawnPoints.size();
            default: return 0;
        }
    }
}
