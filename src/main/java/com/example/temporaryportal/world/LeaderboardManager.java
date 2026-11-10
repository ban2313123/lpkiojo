package com.example.temporaryportal.world;

import com.example.temporaryportal.config.ArenaConfigManager;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks player statistics for the leaderboard:
 * - Number of times survived (exited through portal)
 * - Number of mobs killed in the arena
 * - Total waves survived
 */
public class LeaderboardManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static File leaderboardFile;
    
    // UUID -> PlayerStats, thread-safe
    private static final Map<UUID, PlayerStats> stats = new ConcurrentHashMap<>();
    
    public static class PlayerStats {
        public String playerName = "";
        public int survivals = 0;
        public int mobsKilled = 0;
        public int totalWaves = 0;
        public long lastSurvivalTime = 0;
        
        public PlayerStats() {}
        
        public PlayerStats(String name) {
            this.playerName = name;
        }
    }
    
    public static void init(MinecraftServer server) {
        // Use the config directory for leaderboard storage
        File configDir = new File(System.getProperty("user.dir"), "config/temporaryportal/leaderboard");
        configDir.mkdirs();
        leaderboardFile = new File(configDir, "leaderboard.json");
        
        // Load existing stats
        if (leaderboardFile.exists()) {
            load();
        }
    }
    
    private static void load() {
        try (FileReader reader = new FileReader(leaderboardFile)) {
            Map<String, PlayerStats> loaded = GSON.fromJson(reader, 
                new com.google.gson.reflect.TypeToken<Map<String, PlayerStats>>(){}.getType());
            if (loaded != null) {
                for (Map.Entry<String, PlayerStats> entry : loaded.entrySet()) {
                    try {
                        UUID uuid = UUID.fromString(entry.getKey());
                        stats.put(uuid, entry.getValue());
                    } catch (IllegalArgumentException e) {
                        // Skip invalid UUIDs
                    }
                }
            }
        } catch (IOException e) {
            // File might be corrupted, start fresh
        }
    }
    
    public static synchronized void save() {
        if (leaderboardFile == null) return;
        try {
            leaderboardFile.getParentFile().mkdirs();
            try (FileWriter writer = new FileWriter(leaderboardFile)) {
                GSON.toJson(stats, writer);
            }
        } catch (IOException e) {
            // Log error silently to avoid spam
        }
    }
    
    // Periodic save counter to reduce I/O — save every 5th record
    private static volatile int recordCount = 0;
    
    /**
     * Records a successful survival (player exited through the portal)
     */
    public static void recordSurvival(UUID uuid, String playerName, int wave) {
        PlayerStats playerStats = stats.computeIfAbsent(uuid, k -> new PlayerStats(playerName));
        playerStats.playerName = playerName; // Update name in case it changed
        playerStats.survivals++;
        playerStats.totalWaves = Math.max(playerStats.totalWaves, wave);
        playerStats.lastSurvivalTime = System.currentTimeMillis();
        // Save periodically to reduce I/O
        if (++recordCount % 5 == 0) save();
    }
    
    /**
     * Records a mob kill by a player
     */
    public static void recordMobKill(UUID uuid, String playerName) {
        PlayerStats playerStats = stats.computeIfAbsent(uuid, k -> new PlayerStats(playerName));
        playerStats.playerName = playerName;
        playerStats.mobsKilled++;
        // Save periodically (every 10 kills) to reduce I/O
        if (playerStats.mobsKilled % 10 == 0) {
            save();
        }
    }
    
    /**
     * Gets the top N players sorted by survivals
     */
    public static List<PlayerStats> getTopSurvivors(int limit) {
        return stats.values().stream()
            .filter(s -> s.survivals > 0)
            .sorted(Comparator.comparingInt((PlayerStats s) -> s.survivals).reversed()
                .thenComparingInt(s -> s.totalWaves).reversed())
            .limit(limit)
            .toList();
    }
    
    /**
     * Gets the top N players sorted by mobs killed
     */
    public static List<PlayerStats> getTopKillers(int limit) {
        return stats.values().stream()
            .filter(s -> s.mobsKilled > 0)
            .sorted(Comparator.comparingInt((PlayerStats s) -> s.mobsKilled).reversed()
                .thenComparingInt(s -> s.survivals).reversed())
            .limit(limit)
            .toList();
    }
    
    /**
     * Gets stats for a specific player
     */
    public static PlayerStats getPlayerStats(UUID uuid) {
        return stats.get(uuid);
    }
    
    /**
     * Gets stats for a player by name
     */
    public static PlayerStats getPlayerStatsByName(String name) {
        return stats.values().stream()
            .filter(s -> s.playerName.equalsIgnoreCase(name))
            .findFirst()
            .orElse(null);
    }
}
