package com.example.temporaryportal.world;

import com.example.temporaryportal.TemporaryPortalMod;
import com.example.temporaryportal.config.ArenaConfigManager;
import com.example.temporaryportal.network.NetworkHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class PortalManager {
    private static final Random RANDOM = new Random();
    private static final int TELEPORT_DELAY_MIN_TICKS = 60;  // 3 seconds
    private static final int TELEPORT_DELAY_MAX_TICKS = 100; // 5 seconds

    // Read-write locks for thread-safe access to portal state
    private static final ReentrantReadWriteLock entranceLock = new ReentrantReadWriteLock();
    private static final ReentrantReadWriteLock exitLock = new ReentrantReadWriteLock();

    private static volatile BlockPos currentEntrancePos = null;
    private static int lastEntranceIndex = -1;
    private static int entranceTimeRemaining = 0; // ticks
    private static int cooldownRemaining = 0;     // ticks
    // ConcurrentHashMap prevents ConcurrentModificationException when iterating while modifying
    private static final Map<BlockPos, BlockState> entranceBackupBlocks = new ConcurrentHashMap<>();

    private static volatile BlockPos currentExitPos = null;
    private static int lastExitIndex = -1;
    // ConcurrentHashMap prevents ConcurrentModificationException when iterating while modifying
    private static final Map<BlockPos, BlockState> exitBackupBlocks = new ConcurrentHashMap<>();

    // Player UUID -> ticks standing inside portal
    private static final Map<UUID, Integer> entranceStandTicks = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> exitStandTicks = new ConcurrentHashMap<>();
    
    // Player UUID -> total delay ticks for this portal stand (random 3-5 seconds per entry)
    private static final Map<UUID, Integer> entranceDelays = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> exitDelays = new ConcurrentHashMap<>();

    // Cached reusable objects to avoid allocation in hot tick path
    // Volatile ensures visibility across threads when position changes
    private static volatile AABB cachedEntranceBox = null;
    private static volatile AABB cachedExitBox = null;
    private static volatile BlockPos lastEntranceBoxPos = null;
    private static volatile BlockPos lastExitBoxPos = null;

    // Lock objects for AABB cache updates
    private static final Object entranceBoxLock = new Object();
    private static final Object exitBoxLock = new Object();

    // Pre-formatted countdown message components to avoid String.format in hot path
    private static final String ENTRANCE_COUNTDOWN_PREFIX = "§5🌀 Телепортация на арену через: §d";
    private static final String EXIT_COUNTDOWN_PREFIX = "§a✨ Эвакуация на кровать через: §e";

    public static void tick(MinecraftServer server) {
        checkScheduleAndSpawn(server);
        tickEntrancePortal(server);
        tickExitPortal(server);
    }

    private static void checkScheduleAndSpawn(MinecraftServer server) {
        ArenaConfigManager.ConfigData config = ArenaConfigManager.getData();

        if (currentEntrancePos == null && cooldownRemaining <= 0) {
            boolean canSpawn = false;
            if (!config.enableSchedule) {
                canSpawn = true;
            } else {
                LocalTime now = LocalTime.now();
                LocalTime start = LocalTime.of(config.scheduleStartHour, config.scheduleStartMinute);
                LocalTime end = LocalTime.of(config.scheduleEndHour, config.scheduleEndMinute);

                if (start.isBefore(end)) {
                    canSpawn = !now.isBefore(start) && now.isBefore(end);
                } else {
                    canSpawn = !now.isBefore(start) || now.isBefore(end);
                }
            }

            if (canSpawn && !config.overworldEntrancePoints.isEmpty()) {
                spawnNewEntrance(server);
            }
        }

        if (cooldownRemaining > 0) {
            cooldownRemaining--;
        }
    }

    public static void spawnNewEntrance(MinecraftServer server) {
        if (currentEntrancePos != null) {
            restoreEntranceBlocks(server.overworld());
        }

        ArenaConfigManager.ConfigData config = ArenaConfigManager.getData();
        List<String> points = config.overworldEntrancePoints;
        if (points.isEmpty()) {
            TemporaryPortalMod.LOGGER.warn("Cannot spawn entrance portal: no entrance points configured.");
            return;
        }

        int index;
        do {
            index = RANDOM.nextInt(points.size());
        } while (index == lastEntranceIndex && points.size() > 1);
        lastEntranceIndex = index;

        BlockPos configPos = ArenaConfigManager.parseBlockPos(points.get(index));
        ServerLevel overworld = server.overworld();
        
        // Find the actual surface Y at this X,Z location so the portal spawns on ground level
        int surfaceY = findSurfaceY(overworld, configPos.getX(), configPos.getZ());
        currentEntrancePos = new BlockPos(configPos.getX(), surfaceY, configPos.getZ());
        entranceTimeRemaining = config.portalStayDurationSeconds * 20;

        carveHole(overworld, currentEntrancePos);
        ArenaManager.startSharedSession();

        server.getPlayerList().broadcastSystemMessage(
                Component.literal("§5[Временный Портал] §dВ мире открылась пространственная дыра! Координаты: " 
                        + currentEntrancePos.getX() + ", " + currentEntrancePos.getY() + ", " + currentEntrancePos.getZ()), 
                false
        );

        ensureArenaReady(server);
    }

    /**
     * Finds the surface Y level at the given X,Z coordinates by raycasting downward from the highest buildable block.
     * Scans from Y=319 down to Y=1, looking for the first non-air block (ground).
     * Ensures the chunk is loaded before accessing block state to avoid exceptions.
     * Returns the Y coordinate one block above the ground (where the player would stand).
     * If no ground is found, falls back to Y=70.
     */
    private static int findSurfaceY(ServerLevel level, int x, int z) {
        // Start from the highest possible block and scan downward
        // getBlockState() automatically loads chunks if needed, so no explicit check required
        for (int y = 319; y >= 1; y--) {
            BlockPos pos = new BlockPos(x, y, z);
            BlockState state = level.getBlockState(pos);
            
            // Check if this is a solid block (not air, not water, not lava)
            if (!state.isAir() && state.getFluidState().isEmpty()) {
                // Return one block above the solid ground
                return y + 1;
            }
        }
        // Fallback: if no ground found (e.g., flat world or ocean), use Y=70
        TemporaryPortalMod.LOGGER.warn("No surface found at {}, {}, defaulting to Y=70", x, z);
        return 70;
    }

    private static void carveHole(ServerLevel level, BlockPos center) {
        entranceLock.writeLock().lock();
        try {
            entranceBackupBlocks.clear();
            int cx = center.getX();
            int cy = center.getY();
            int cz = center.getZ();

            // Carve a funnel shape: dig 5x5 area around the portal to create a visible pit
            // The center 3x3 is the portal itself, the outer ring is a dug-out funnel for easy entry
            for (int x = -2; x <= 2; x++) {
                for (int z = -2; z <= 2; z++) {
                    int absX = Math.abs(x);
                    int absZ = Math.abs(z);
                    
                    // Determine how deep to dig based on distance from center
                    // Center (0,0) = depth 2, adjacent = depth 1, outer ring = depth 0 (just clear above)
                    int depth = Math.max(0, 2 - Math.max(absX, absZ));
                    
                    for (int d = 0; d <= depth; d++) {
                        BlockPos p = new BlockPos(cx + x, cy - d, cz + z);
                        entranceBackupBlocks.put(p, level.getBlockState(p));
                        level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
                    }
                }
            }

            // Place the portal structure: 3x3 crying obsidian ring with END_GATEWAY in center
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    BlockPos p = new BlockPos(cx + x, cy, cz + z);
                    
                    if (x == 0 && z == 0) {
                        // Center: the gateway (player falls through this)
                        level.setBlock(p, Blocks.END_GATEWAY.defaultBlockState(), 3);
                    } else {
                        // Surrounding ring: crying obsidian border
                        level.setBlock(p, Blocks.CRYING_OBSIDIAN.defaultBlockState(), 3);
                    }

                    // Clear air above the portal (2 blocks high)
                    BlockPos above1 = p.above();
                    BlockPos above2 = p.above(2);
                    entranceBackupBlocks.put(above1, level.getBlockState(above1));
                    entranceBackupBlocks.put(above2, level.getBlockState(above2));
                    level.setBlock(above1, Blocks.AIR.defaultBlockState(), 3);
                    level.setBlock(above2, Blocks.AIR.defaultBlockState(), 3);
                }
            }
            
            // Add torches or soul lanterns around the portal for visibility
            for (int x = -2; x <= 2; x++) {
                for (int z = -2; z <= 2; z++) {
                    if (Math.abs(x) == 2 && Math.abs(z) == 2) {
                        // Corners of the 5x5 area: place soul lanterns
                        BlockPos lanternPos = new BlockPos(cx + x, cy + 1, cz + z);
                        level.setBlock(lanternPos, Blocks.SOUL_LANTERN.defaultBlockState(), 3);
                    }
                }
            }
        } finally {
            entranceLock.writeLock().unlock();
        }
    }

    private static void restoreEntranceBlocks(ServerLevel level) {
        entranceLock.writeLock().lock();
        try {
            // Create a snapshot to avoid ConcurrentModificationException
            List<Map.Entry<BlockPos, BlockState>> snapshot = new ArrayList<>(entranceBackupBlocks.entrySet());
            for (Map.Entry<BlockPos, BlockState> entry : snapshot) {
                level.setBlock(entry.getKey(), entry.getValue(), 3);
            }
            entranceBackupBlocks.clear();
            entranceStandTicks.clear();
        } finally {
            entranceLock.writeLock().unlock();
        }
    }

    public static void spawnNewExit(MinecraftServer server) {
        ServerLevel arena = server.getLevel(DimensionSetup.ARENA_LEVEL_KEY);
        if (arena == null) return;

        if (currentExitPos != null) {
            restoreExitBlocks(arena);
        }

        // First, try to get exit position from structure data
        StructureHandler.StructureData structData = StructureHandler.loadStructureData();
        BlockPos rawExitPos;
        if (structData != null && structData.exitPortalPos != null) {
            // Use exit portal position from structure (relative to origin)
            BlockPos origin = ArenaConfigManager.parseBlockPos(ArenaConfigManager.getData().structurePlacementPos);
            rawExitPos = structData.exitPortalPos.offset(origin);
            TemporaryPortalMod.LOGGER.info("Using exit portal position from structure: {}", rawExitPos);
        } else {
            // Fallback to config points
            ArenaConfigManager.ConfigData config = ArenaConfigManager.getData();
            List<String> points = config.arenaExitPoints;
            if (points.isEmpty()) {
                rawExitPos = new BlockPos(0, 64, 10);
            } else {
                int index;
                do {
                    index = RANDOM.nextInt(points.size());
                } while (index == lastExitIndex && points.size() > 1);
                lastExitIndex = index;
                rawExitPos = ArenaConfigManager.parseBlockPos(points.get(index));
            }
        }

        // Find the actual surface Y so the exit portal spawns on the platform, not underground or in the air
        int exitSurfaceY = findExitSurfaceY(arena, rawExitPos.getX(), rawExitPos.getZ(), rawExitPos.getY());
        currentExitPos = new BlockPos(rawExitPos.getX(), exitSurfaceY, rawExitPos.getZ());

        exitLock.writeLock().lock();
        try {
            exitBackupBlocks.clear();
            int cx = currentExitPos.getX();
            int cy = currentExitPos.getY();
            int cz = currentExitPos.getZ();

            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    BlockPos p = new BlockPos(cx + x, cy, cz + z);
                    exitBackupBlocks.put(p, arena.getBlockState(p));

                    if (x == 0 && z == 0) {
                        arena.setBlock(p, Blocks.END_PORTAL.defaultBlockState(), 3);
                    } else {
                        arena.setBlock(p, Blocks.CHISELED_POLISHED_BLACKSTONE.defaultBlockState(), 3);
                    }
                }
            }
        } finally {
            exitLock.writeLock().unlock();
        }

        TemporaryPortalMod.LOGGER.info("Spawned new Arena Exit Portal at {}", currentExitPos);
    }

    private static void restoreExitBlocks(ServerLevel arena) {
        exitLock.writeLock().lock();
        try {
            // Create a snapshot to avoid ConcurrentModificationException
            List<Map.Entry<BlockPos, BlockState>> snapshot = new ArrayList<>(exitBackupBlocks.entrySet());
            for (Map.Entry<BlockPos, BlockState> entry : snapshot) {
                arena.setBlock(entry.getKey(), entry.getValue(), 3);
            }
            exitBackupBlocks.clear();
            exitStandTicks.clear();
        } finally {
            exitLock.writeLock().unlock();
        }
    }

    public static void ensureArenaReady(MinecraftServer server) {
        ServerLevel arena = server.getLevel(DimensionSetup.ARENA_LEVEL_KEY);
        if (arena == null) return;

        BlockPos center = ArenaConfigManager.parseBlockPos(ArenaConfigManager.getData().structurePlacementPos);
        BlockPos samplePos = new BlockPos(center.getX(), center.getY() - 1, center.getZ());
        if (arena.isEmptyBlock(samplePos)) {
            StructureHandler.generatePlatform(arena);
        }

        if (currentExitPos == null) {
            spawnNewExit(server);
        }
    }

    @FunctionalInterface
    private static interface PortalParticleEmitter {
        void emit(ServerLevel level, BlockPos pos, int ticks);
    }

    @FunctionalInterface
    private static interface PortalTeleportAction {
        void teleport(ServerPlayer player, MinecraftServer server);
    }

    private static void tickPortal(
            BlockPos pos,
            Map<UUID, Integer> standTicks,
            Map<UUID, Integer> delayTicks,
            PortalParticleEmitter particleEmitter,
            PortalTeleportAction teleportAction,
            String countdownPrefix,
            boolean showVortexParticles,
            float soundPitch,
            MinecraftServer server,
            ServerLevel level,
            BlockPos lastBoxPos,
            AABB cachedBox
    ) {
        if (pos == null) return;

        int ticks = server.getTickCount();
        // Emit portal particles every 3 ticks instead of every tick to reduce network load
        if (ticks % 3 == 0) {
            particleEmitter.emit(level, pos, ticks);
        }

        AABB box = cachedBox;
        if (pos != null && !pos.equals(lastBoxPos)) {
            box = new AABB(
                pos.getX() + 0.1, pos.getY() - 0.4, pos.getZ() + 0.1,
                pos.getX() + 0.9, pos.getY() + 1.6, pos.getZ() + 0.9
            );
        }
        List<ServerPlayer> playersInside = level.getEntitiesOfClass(ServerPlayer.class, box);

        // Only rebuild UUID set when standTicks is non-empty to avoid allocation
        if (!standTicks.isEmpty()) {
            Set<UUID> playersInsideUuids = new java.util.HashSet<>();
            for (ServerPlayer p : playersInside) {
                playersInsideUuids.add(p.getUUID());
            }
            
            // Remove UUIDs of players no longer inside the portal
            Iterator<Map.Entry<UUID, Integer>> it = standTicks.entrySet().iterator();
            while (it.hasNext()) {
                UUID uuid = it.next().getKey();
                if (!playersInsideUuids.contains(uuid)) {
                    it.remove();
                    delayTicks.remove(uuid);
                }
            }
        }

        for (ServerPlayer player : playersInside) {
            UUID uuid = player.getUUID();
            int stood = standTicks.getOrDefault(uuid, 0) + 1;
            standTicks.put(uuid, stood);

            // Assign a random delay (3-5 seconds) the first time the player stands in the portal
            int totalDelay = delayTicks.computeIfAbsent(uuid, k -> TELEPORT_DELAY_MIN_TICKS + RANDOM.nextInt(TELEPORT_DELAY_MAX_TICKS - TELEPORT_DELAY_MIN_TICKS + 1));

            int remainingTicks = totalDelay - stood;

            if (remainingTicks > 0) {
                // Send countdown message every 20 ticks (1 sec) instead of every tick
                if (stood % 20 == 0) {
                    int secondsLeft = (remainingTicks + 19) / 20;
                    player.sendSystemMessage(Component.literal(countdownPrefix + secondsLeft + " сек..."), true);
                }
                // Sound every 15 ticks
                if (stood % 15 == 0) {
                    player.playNotifySound(SoundEvents.PORTAL_AMBIENT, SoundSource.PLAYERS, 0.7f, soundPitch);
                }
                // Vortex particles every 5 ticks instead of every tick
                if (showVortexParticles && stood % 5 == 0) {
                    player.serverLevel().sendParticles(ParticleTypes.REVERSE_PORTAL,
                            player.getX(), player.getY() + 1.0, player.getZ(),
                            15, 0.6, 0.8, 0.6, 0.1);
                    player.serverLevel().sendParticles(ParticleTypes.ENCHANT,
                            player.getX(), player.getY() + 1.0, player.getZ(),
                            10, 0.5, 0.5, 0.5, 0.5);
                }
            } else {
                standTicks.remove(uuid);
                delayTicks.remove(uuid);
                teleportAction.teleport(player, server);
            }
        }
    }

    // Cached lambda instances to avoid allocation every tick
    private static final PortalParticleEmitter ENTRANCE_PARTICLES = (level, pos, ticks) -> {
        double angle = ticks * 0.25;
        double rad = 1.2;
        double pX = pos.getX() + 0.5 + Math.cos(angle) * rad;
        double pZ = pos.getZ() + 0.5 + Math.sin(angle) * rad;
        double pY = pos.getY() + 0.3 + (Math.sin(ticks * 0.1) * 0.2);

        level.sendParticles(ParticleTypes.REVERSE_PORTAL, pX, pY, pZ, 3, 0.02, 0.05, 0.02, 0.01);
        level.sendParticles(ParticleTypes.PORTAL, pos.getX() + 0.5, pos.getY() + 1.2, pos.getZ() + 0.5, 6, 0.3, 0.4, 0.3, 0.05);

        if (ticks % 3 == 0) {
            level.sendParticles(ParticleTypes.DRAGON_BREATH, pos.getX() + 0.5, pos.getY() + 0.1, pos.getZ() + 0.5, 5, 0.4, 0.05, 0.4, 0.01);
            level.sendParticles(ParticleTypes.ENCHANT, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 8, 0.5, 0.5, 0.5, 0.05);
        }
        
        // Animated particles around the portal hole edges (indigo/purple ring)
        if (ticks % 2 == 0) {
            int cornerCount = 8;
            for (int i = 0; i < cornerCount; i++) {
                double a = (i * 2 * Math.PI / cornerCount) + ticks * 0.15;
                double r = 1.6;
                double cx = pos.getX() + 0.5 + Math.cos(a) * r;
                double cz = pos.getZ() + 0.5 + Math.sin(a) * r;
                double cy = pos.getY() + 0.2 + Math.sin(ticks * 0.2 + i) * 0.3;
                level.sendParticles(ParticleTypes.PORTAL, cx, cy, cz, 1, 0.02, 0.08, 0.02, 0.01);
            }
            // Rising flame particles from the hole center
            level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, pos.getX() + 0.5, pos.getY() + 0.3, pos.getZ() + 0.5, 4, 0.15, 0.3, 0.15, 0.02);
            level.sendParticles(ParticleTypes.ASH, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 3, 0.2, 0.15, 0.2, 0.01);
        }
    };

    private static final PortalParticleEmitter EXIT_PARTICLES = (level, pos, ticks) -> {
        double angle = ticks * 0.2;
        double eX = pos.getX() + 0.5 + Math.cos(angle) * 1.1;
        double eZ = pos.getZ() + 0.5 + Math.sin(angle) * 1.1;

        level.sendParticles(ParticleTypes.END_ROD, eX, pos.getY() + 0.8, eZ, 2, 0.02, 0.02, 0.02, 0.02);
        level.sendParticles(ParticleTypes.PORTAL, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 12, 0.3, 0.4, 0.3, 0.05);
        if (ticks % 4 == 0) {
            level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, pos.getX() + 0.5, pos.getY() + 0.4, pos.getZ() + 0.5, 3, 0.2, 0.2, 0.2, 0.05);
        }
    };

    private static final float ENTRANCE_SOUND_PITCH = 1.0f + (TELEPORT_DELAY_MIN_TICKS / 60.0f) * 0.8f;

    private static void tickEntrancePortal(MinecraftServer server) {
        BlockPos entrancePos = currentEntrancePos;
        if (entrancePos == null) return;

        // Guard: prevent entranceTimeRemaining from going deeply negative
        if (entranceTimeRemaining <= 0) {
            closeEntrance(server);
            return;
        }

        ServerLevel overworld = server.overworld();
        ServerLevel arena = server.getLevel(DimensionSetup.ARENA_LEVEL_KEY);

        // Update cached AABB — create new box if position changed, then cache it
        AABB box;
        synchronized (entranceBoxLock) {
            if (entrancePos.equals(lastEntranceBoxPos) && cachedEntranceBox != null) {
                box = cachedEntranceBox;
            } else {
                box = new AABB(
                    entrancePos.getX() + 0.1, entrancePos.getY() - 0.4, entrancePos.getZ() + 0.1,
                    entrancePos.getX() + 0.9, entrancePos.getY() + 1.6, entrancePos.getZ() + 0.9
                );
                cachedEntranceBox = box;
                lastEntranceBoxPos = entrancePos;
            }
        }

        tickPortal(
                entrancePos,
                entranceStandTicks,
                entranceDelays,
                ENTRANCE_PARTICLES,
                (player, srv) -> {
                    if (arena != null) {
                        enterArena(player, arena);
                    }
                },
                ENTRANCE_COUNTDOWN_PREFIX,
                true,
                ENTRANCE_SOUND_PITCH,
                server,
                overworld,
                lastEntranceBoxPos,
                box  // Pass the current box directly
        );

        entranceTimeRemaining--;
        if (entranceTimeRemaining <= 0) {
            // Double-check to prevent double-close
            BlockPos pos = currentEntrancePos;
            if (pos != null) {
                closeEntrance(server);
            }
        }
    }

    private static void tickExitPortal(MinecraftServer server) {
        BlockPos exitPos = currentExitPos;
        if (exitPos == null) return;

        ServerLevel arena = server.getLevel(DimensionSetup.ARENA_LEVEL_KEY);
        if (arena == null) return;

        // Update cached AABB — create new box if position changed, then cache it
        AABB box;
        synchronized (exitBoxLock) {
            if (exitPos.equals(lastExitBoxPos) && cachedExitBox != null) {
                box = cachedExitBox;
            } else {
                box = new AABB(
                    exitPos.getX() + 0.1, exitPos.getY() - 0.4, exitPos.getZ() + 0.1,
                    exitPos.getX() + 0.9, exitPos.getY() + 1.6, exitPos.getZ() + 0.9
                );
                cachedExitBox = box;
                lastExitBoxPos = exitPos;
            }
        }

        tickPortal(
                exitPos,
                exitStandTicks,
                exitDelays,
                EXIT_PARTICLES,
                (player, srv) -> exitArena(player, srv),
                EXIT_COUNTDOWN_PREFIX,
                false,
                1.4f,
                server,
                arena,
                lastExitBoxPos,
                box  // Pass the current box directly
        );
    }

    public static void enterArena(ServerPlayer player, ServerLevel arena) {
        ensureArenaReady(player.server);

        NetworkHandler.sendGlyphOverlay(player, 60);
        
        // Dramatic portal rift sound sequence
        player.playNotifySound(SoundEvents.GENERIC_EXPLODE.value(), SoundSource.MASTER, 1.5f, 0.5f);
        player.playNotifySound(SoundEvents.PORTAL_TRIGGER, SoundSource.PLAYERS, 1.0f, 1.0f);
        player.playNotifySound(SoundEvents.END_PORTAL_SPAWN, SoundSource.BLOCKS, 0.8f, 1.2f);
        
        // Play ambient portal sound for nearby players too
        player.serverLevel().playSound(null, player.getX(), player.getY(), player.getZ(),
            SoundEvents.GENERIC_EXPLODE.value(), SoundSource.MASTER, 2.0f, 0.4f);

        BlockPos origin = ArenaConfigManager.parseBlockPos(ArenaConfigManager.getData().structurePlacementPos);
        player.teleportTo(arena, origin.getX() + 0.5, origin.getY() + 1.5, origin.getZ() + 0.5, player.getYRot(), player.getXRot());

        ArenaManager.onPlayerEnter(player, arena);
        player.sendSystemMessage(Component.literal("§c[Арена] §eУ вас есть 15 минут, чтобы найти портал выхода! Если время истечёт — вы погибнете."), false);
    }

    public static void exitArena(ServerPlayer player, MinecraftServer server) {
        NetworkHandler.sendGlyphOverlay(player, 60);
        player.playNotifySound(SoundEvents.PLAYER_TELEPORT, SoundSource.PLAYERS, 1.0f, 1.0f);
        
        // Record survival in leaderboard
        LeaderboardManager.recordSurvival(player.getUUID(), player.getName().getString(), ArenaManager.getCurrentWave());
        
        // Award survival bonus items to the player's inventory
        awardSurvivalBonus(player);

        ArenaManager.removePlayer(player.getUUID());

        // ALWAYS teleport back to the overworld (the main world), not to whatever respawn dimension is set.
        // The respawn dimension might be The End if the player died there before, which is NOT what we want.
        ServerLevel overworld = server.overworld();
        
        // Try to find the player's bed spawn position in the overworld
        // getRespawnPosition() returns the bed position if the player has slept in one
        BlockPos bedPos = player.getRespawnPosition();
        if (bedPos == null) {
            // Fallback: use the world spawn point
            bedPos = overworld.getSharedSpawnPos();
        }

        player.teleportTo(overworld, bedPos.getX() + 0.5, bedPos.getY() + 0.5, bedPos.getZ() + 0.5, player.getYRot(), player.getXRot());
        player.sendSystemMessage(Component.literal("§a[Арена] §fВы успешно спаслись и вернулись со всей добычей!"), false);
    }
    
    /**
     * Awards survival bonus items to the player's inventory when they exit through the portal.
     * Bonus scales with the current wave number — higher waves = better rewards.
     * Items that don't fit in inventory are dropped at the player's feet.
     */
    private static void awardSurvivalBonus(ServerPlayer player) {
        int wave = ArenaManager.getCurrentWave();
        Random rand = new Random();
        
        // Base bonus: enchanted golden apple (rare), diamonds, iron, ender pearls
        net.minecraft.world.item.ItemStack[] bonusItems = new net.minecraft.world.item.ItemStack[] {
            new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.ENCHANTED_GOLDEN_APPLE, Math.max(1, wave / 3)),
            new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND, 2 + rand.nextInt(3)),
            new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_INGOT, 3 + rand.nextInt(4)),
            new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.ENDER_PEARL, 1 + rand.nextInt(2)),
        };
        
        // High wave bonus: nether star, totem of undying, beacon
        if (wave >= 5) {
            bonusItems = java.util.Arrays.copyOf(bonusItems, bonusItems.length + 1);
            bonusItems[bonusItems.length - 1] = new net.minecraft.world.item.ItemStack(
                net.minecraft.world.item.Items.TOTEM_OF_UNDYING, 1);
        }
        if (wave >= 10) {
            bonusItems = java.util.Arrays.copyOf(bonusItems, bonusItems.length + 1);
            bonusItems[bonusItems.length - 1] = new net.minecraft.world.item.ItemStack(
                net.minecraft.world.item.Items.NETHER_STAR, 1);
        }
        
        // Try to add items to inventory; drop remaining on the ground
        for (net.minecraft.world.item.ItemStack item : bonusItems) {
            if (!item.isEmpty()) {
                // add() returns boolean — if false, inventory is full, drop the item
                if (!player.getInventory().add(item)) {
                    player.drop(item, false);
                }
            }
        }
        
        TemporaryPortalMod.LOGGER.info("Player {} received survival bonus for wave {}", player.getName().getString(), wave);
    }

    public static void closeEntrance(MinecraftServer server) {
        if (currentEntrancePos != null) {
            restoreEntranceBlocks(server.overworld());
        }
        currentEntrancePos = null;
        cooldownRemaining = ArenaConfigManager.getData().portalCooldownSeconds * 20;
        server.getPlayerList().broadcastSystemMessage(
                Component.literal("§5[Временный Портал] §7Аномалия закрылась. До следующего появления портала осталось " 
                        + ArenaConfigManager.getData().portalCooldownSeconds / 60 + " мин."), 
                false
        );
    }

    public static BlockPos getCurrentEntrancePos() { return currentEntrancePos; }
    public static BlockPos getCurrentExitPos() { return currentExitPos; }
    public static void forceClose(MinecraftServer server) { closeEntrance(server); }
    
    // Expose locks for external synchronized access
    /**
     * Finds the surface Y level for the exit portal at the given X,Z coordinates.
     * Scans from configY+10 down to configY-20 to find the platform surface.
     */
    private static int findExitSurfaceY(ServerLevel level, int x, int z, int configY) {
        // getBlockState() automatically loads chunks if needed
        for (int y = configY + 10; y >= configY - 20; y--) {
            BlockPos pos = new BlockPos(x, y, z);
            BlockState state = level.getBlockState(pos);
            if (!state.isAir() && state.getFluidState().isEmpty()) {
                return y + 1;
            }
        }
        // Fallback: use config Y + 1
        return configY + 1;
    }

    // Expose locks for external synchronized access
    public static ReentrantReadWriteLock getEntranceLock() { return entranceLock; }
    public static ReentrantReadWriteLock getExitLock() { return exitLock; }
}
