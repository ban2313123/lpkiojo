package com.example.temporaryportal.world;

import com.example.temporaryportal.TemporaryPortalMod;
import com.example.temporaryportal.config.ArenaConfigManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class ArenaManager {
    // Thread-safe atomic session state — prevents race conditions when multiple players enter simultaneously
    private static final AtomicInteger arenaSessionTimeRemaining = new AtomicInteger(0);
    private static final AtomicBoolean isSessionActive = new AtomicBoolean(false);
    private static final AtomicBoolean chunksForcedLoaded = new AtomicBoolean(false);
    private static final AtomicBoolean sessionInitialized = new AtomicBoolean(false);

    // Lock object for compound operations that must be atomic (e.g., check-then-act on sessionInitialized)
    private static final Object sessionLock = new Object();

    // Cached arena center to avoid repeated BlockPos parsing
    private static volatile BlockPos cachedArenaCenter = null;
    private static volatile String lastStructurePlacementPos = null;

    // Pre-defined component prefix for timer actionbar to avoid String.format
    private static final String TIMER_PREFIX = "§e⏳ Общий таймер арены: §c";

    public static void ensureChunksForced(ServerLevel arena) {
        if (!chunksForcedLoaded.get() && arena != null) {
            // Use CAS to ensure only one thread sets this to true
            if (chunksForcedLoaded.compareAndSet(false, true)) {
                BlockPos center = ArenaConfigManager.parseBlockPos(ArenaConfigManager.getData().structurePlacementPos);
                int centerChunkX = center.getX() >> 4;
                int centerChunkZ = center.getZ() >> 4;

                // Force load a 5x5 chunk area around arena center (-2 to +2 chunks)
                for (int dx = -2; dx <= 2; dx++) {
                    for (int dz = -2; dz <= 2; dz++) {
                        arena.setChunkForced(centerChunkX + dx, centerChunkZ + dz, true);
                    }
                }
                TemporaryPortalMod.LOGGER.info("Arena platform chunks permanently force-loaded.");
            }
        }
    }


    // Track active players currently inside the arena
    private static final Set<UUID> playersInArena = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private static int chestTimer = 0;
    private static int mobTimer = 0;
    private static int currentWave = 0; // Wave counter for escalating difficulty
    private static final Random RANDOM = new Random();
    
    // Cached AABB for mob cap check — reused across spawnMobWave calls
    private static volatile net.minecraft.world.phys.AABB cachedMobCheckBox = null;
    private static volatile BlockPos lastMobCheckCenter = null;

    public static void init(MinecraftServer server) {
        chestTimer = 0;
        mobTimer = 0;
        currentWave = 0;
    }

    /**
     * Starts or ensures a shared 15-minute arena session — thread-safe via synchronized block.
     */
    public static void startSharedSession() {
        synchronized (sessionLock) {
            if (!isSessionActive.get() || arenaSessionTimeRemaining.get() <= 0) {
                arenaSessionTimeRemaining.set(ArenaConfigManager.getData().playerStayDurationSeconds * 20);
                isSessionActive.set(true);
                mobTimer = Math.max(30, ArenaConfigManager.getData().mobWaveSeconds) * 20;
                chestTimer = Math.max(60, ArenaConfigManager.getData().chestRespawnSeconds) * 20;
                sessionInitialized.set(false);
                currentWave = 0; // Reset wave counter for new session
                TemporaryPortalMod.LOGGER.info("Shared arena session started. Time remaining: {} seconds", ArenaConfigManager.getData().playerStayDurationSeconds);
            }
        }
    }

    public static void onPlayerEnter(ServerPlayer player, ServerLevel arena) {
        startSharedSession();
        ensureChunksForced(arena);
        playersInArena.add(player.getUUID());

        // Spawn initial chests and mobs ONLY ONCE per session lifecycle — atomic check-then-act
        synchronized (sessionLock) {
            if (!sessionInitialized.get()) {
                // Update bounds from structure data before spawning anything
                updateBoundsFromStructure();
                spawnChests(arena);
                currentWave = 1;
                spawnMobWave(arena, currentWave);
                sessionInitialized.set(true);
            }
        }
    }

    public static void tick(MinecraftServer server) {
        PortalManager.tick(server);

        ServerLevel arena = server.getLevel(DimensionSetup.ARENA_LEVEL_KEY);
        if (arena == null) return;
        PortalManager.ensureArenaReady(server);
        ensureChunksForced(arena);

        // Tick shared session timer if active
        // Use decrementAndGet() result directly to avoid TOCTOU race between get() and decrementAndGet()
        int newTime = arenaSessionTimeRemaining.decrementAndGet();
        if (isSessionActive.get() && newTime > 0) {

            int currentTicks = server.getTickCount();
            
            // Clean up players who left the arena every 20 ticks (1 sec) instead of every tick
            if (currentTicks % 20 == 0) {
                Iterator<UUID> it = playersInArena.iterator();
                while (it.hasNext()) {
                    UUID uuid = it.next();
                    ServerPlayer player = server.getPlayerList().getPlayer(uuid);
                    if (player == null || !player.level().dimension().equals(DimensionSetup.ARENA_LEVEL_KEY)) {
                        it.remove();
                    }
                }
            }

            // Send actionbar timer every 20 ticks (1 sec) — build message once, send to all
            if (newTime % 20 == 0) {
                int secondsLeft = newTime / 20;
                int mins = secondsLeft / 60;
                int secs = secondsLeft % 60;
                String timeStr = (mins < 10 ? "0" + mins : mins + ":") + (secs < 10 ? "0" + secs : secs);
                Component timerMsg = Component.literal(TIMER_PREFIX + timeStr);
                for (UUID uuid : playersInArena) {
                    ServerPlayer player = server.getPlayerList().getPlayer(uuid);
                    if (player != null) {
                        player.sendSystemMessage(timerMsg, true);
                    }
                }
            }

            // Synchronize playersInArena every 40 ticks (2 sec) instead of every tick
            if (currentTicks % 40 == 0) {
                for (ServerPlayer sp : arena.players()) {
                    playersInArena.add(sp.getUUID());
                }
            }

            // Check if anyone is in arena for chest/mob spawning (cheap check)
            boolean anyoneInArena = !playersInArena.isEmpty();
            
            // Chests and Mobs spawn waves during active session
            if (anyoneInArena) {
                chestTimer--;
                if (chestTimer <= 0) {
                    spawnChests(arena);
                    chestTimer = Math.max(60, ArenaConfigManager.getData().chestRespawnSeconds) * 20;
                }

                mobTimer--;
                if (mobTimer <= 0) {
                    currentWave++;
                    spawnMobWave(arena, currentWave);
                    mobTimer = Math.max(30, ArenaConfigManager.getData().mobWaveSeconds) * 20;
                }
            }

            // TIME EXPIRED: Everyone remaining inside dies!
            if (newTime <= 0) {
                isSessionActive.set(false);
                List<ServerPlayer> playersToKill = new ArrayList<>();
                for (UUID uuid : playersInArena) {
                    ServerPlayer p = server.getPlayerList().getPlayer(uuid);
                    if (p != null && p.level().dimension().equals(DimensionSetup.ARENA_LEVEL_KEY)) {
                        playersToKill.add(p);
                    }
                }
                playersInArena.clear();

                for (ServerPlayer p : playersToKill) {
                    p.sendSystemMessage(Component.literal("§4[Арена] §cВремя сессии истекло! Бездна поглотила всех выживших..."), false);
                    p.hurt(p.damageSources().fellOutOfWorld(), Float.MAX_VALUE);
                }

                purgeAndRegenerateArena(arena);
                PortalManager.forceClose(server);
            }
        }
    }

    public static void addPlayer(ServerPlayer player) {
        // Start or continue shared session
        startSharedSession();
        playersInArena.add(player.getUUID());
    }

    public static void removePlayer(UUID uuid) {
        playersInArena.remove(uuid);
    }

    public static boolean isPlayerInArena(UUID uuid) {
        return playersInArena.contains(uuid);
    }

    public static int getSharedTimeRemainingSeconds() {
        return Math.max(0, arenaSessionTimeRemaining.get() / 20);
    }

    public static boolean isSessionActive() {
        return isSessionActive.get();
    }

    public static int getCurrentWave() {
        return currentWave;
    }

        /**
     * Completely purges all mobs, drops, old chests and regenerates the arena platform / structure
     */
    public static void purgeAndRegenerateArena(ServerLevel arena) {
        sessionInitialized.set(false);
        TemporaryPortalMod.LOGGER.info("Purging and regenerating Arena world...");

        // 1. Remove all non-player entities using targeted queries instead of getAllEntities()
        // This is O(n) on specific entity types instead of O(all entities in world)
        net.minecraft.world.phys.AABB worldBox = new net.minecraft.world.phys.AABB(-300, 0, -300, 300, 256, 300);
        for (Mob m : arena.getEntitiesOfClass(Mob.class, worldBox)) {
            m.discard();
        }
        for (net.minecraft.world.entity.item.ItemEntity item : arena.getEntitiesOfClass(
            net.minecraft.world.entity.item.ItemEntity.class, worldBox)) {
            item.discard();
        }
        for (net.minecraft.world.entity.projectile.Projectile proj : arena.getEntitiesOfClass(
            net.minecraft.world.entity.projectile.Projectile.class, worldBox)) {
            proj.discard();
        }

        // 2. Clear out any previous chests
        List<BlockPos> chestPositions = getChestSpawnPositions();
        for (BlockPos pos : chestPositions) {
            if (arena.getBlockState(pos).is(Blocks.CHEST)) {
                arena.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            }
        }

        // 3. Regenerate base platform (and structure if provided)
        StructureHandler.placeArenaStructure(arena);

        // 4. Update arena bounds from structure data
        updateBoundsFromStructure();

        // 5. Respawn fresh exit portal at structure-defined position
        PortalManager.spawnNewExit(arena.getServer());

        // 6. Fill brand-new chests with freshly rolled loot
        spawnChests(arena);

        // 7. Spawn mobs immediately after regeneration (wave 1)
        currentWave = 1;
        spawnMobWave(arena, currentWave);

        TemporaryPortalMod.LOGGER.info("Arena world completely refreshed and ready for next session.");
    }

    /**
     * Updates the arena bounds in config from the structure data.
     * This ensures the boundary enforcement in ArenaEvents uses the correct bounds.
     */
    private static void updateBoundsFromStructure() {
        StructureHandler.StructureData structData = StructureHandler.loadStructureData();
        if (structData != null && structData.isValid()) {
            ArenaConfigManager.ArenaBounds bounds = ArenaConfigManager.getData().arenaBounds;
            // Add padding of 5 blocks around the structure for boundary enforcement
            bounds.minX = structData.minX - 5;
            bounds.minY = structData.minY - 5;
            bounds.minZ = structData.minZ - 5;
            bounds.maxX = structData.maxX + 5;
            bounds.maxY = structData.maxY + 20; // More headroom above
            bounds.maxZ = structData.maxZ + 5;
            bounds.enableBounds = true;
            TemporaryPortalMod.LOGGER.info("Arena bounds updated from structure: [{},{},{}-{}},{},{}]",
                bounds.minX, bounds.minY, bounds.minZ, bounds.maxX, bounds.maxY, bounds.maxZ);
        }
    }

    /**
     * Gets chest spawn positions from structure data, falling back to config or defaults.
     */
    private static List<BlockPos> getChestSpawnPositions() {
        StructureHandler.StructureData structData = StructureHandler.loadStructureData();
        if (structData != null && !structData.chestSpawnPoints.isEmpty()) {
            // Convert relative structure positions to world positions
            BlockPos origin = ArenaConfigManager.parseBlockPos(ArenaConfigManager.getData().structurePlacementPos);
            List<BlockPos> worldPositions = new ArrayList<>();
            for (BlockPos pos : structData.chestSpawnPoints) {
                worldPositions.add(pos.offset(origin));
            }
            return worldPositions;
        }

        // Fallback to config points
        List<String> configPoints = ArenaConfigManager.getData().chestSpawnPoints;
        if (!configPoints.isEmpty()) {
            List<BlockPos> positions = new ArrayList<>();
            for (String pt : configPoints) {
                positions.add(ArenaConfigManager.parseBlockPos(pt));
            }
            return positions;
        }

        // Default points
        return getDefaultChestBlockPositions();
    }

    /**
     * Gets mob spawn positions from structure data, falling back to config or defaults.
     */
    private static List<BlockPos> getMobSpawnPositions() {
        StructureHandler.StructureData structData = StructureHandler.loadStructureData();
        if (structData != null && !structData.mobSpawnPoints.isEmpty()) {
            // Convert relative structure positions to world positions
            BlockPos origin = ArenaConfigManager.parseBlockPos(ArenaConfigManager.getData().structurePlacementPos);
            List<BlockPos> worldPositions = new ArrayList<>();
            for (BlockPos pos : structData.mobSpawnPoints) {
                worldPositions.add(pos.offset(origin));
            }
            return worldPositions;
        }

        // Fallback to config points
        List<String> configPoints = ArenaConfigManager.getData().mobSpawnPoints;
        if (!configPoints.isEmpty()) {
            List<BlockPos> positions = new ArrayList<>();
            for (String pt : configPoints) {
                positions.add(ArenaConfigManager.parseBlockPos(pt));
            }
            return positions;
        }

        // Default points
        return getDefaultMobBlockPositions();
    }

    private static List<BlockPos> getDefaultChestBlockPositions() {
        BlockPos center = getArenaCenter();
        int cx = center.getX(); int cy = center.getY(); int cz = center.getZ();
        List<BlockPos> points = new ArrayList<>();
        points.add(new BlockPos(cx + 7, cy, cz + 7));
        points.add(new BlockPos(cx - 7, cy, cz + 7));
        points.add(new BlockPos(cx + 7, cy, cz - 7));
        points.add(new BlockPos(cx - 7, cy, cz - 7));
        return points;
    }

    private static List<BlockPos> getDefaultMobBlockPositions() {
        BlockPos center = getArenaCenter();
        int cx = center.getX(); int cy = center.getY(); int cz = center.getZ();
        List<BlockPos> points = new ArrayList<>();
        points.add(new BlockPos(cx + 6, cy + 1, cz));
        points.add(new BlockPos(cx - 6, cy + 1, cz));
        points.add(new BlockPos(cx, cy + 1, cz + 6));
        points.add(new BlockPos(cx, cy + 1, cz - 6));
        return points;
    }

    public static void resetSession() {
        synchronized (sessionLock) {
            isSessionActive.set(false);
            arenaSessionTimeRemaining.set(0);
            playersInArena.clear();
        }
    }

    private static List<ArenaConfigManager.LootItemEntry> getDefaultLoot() {
        List<ArenaConfigManager.LootItemEntry> loot = new ArrayList<>();
        loot.add(new ArenaConfigManager.LootItemEntry("minecraft:golden_apple", 1, 2, 0.7));
        loot.add(new ArenaConfigManager.LootItemEntry("minecraft:diamond", 1, 3, 0.5));
        loot.add(new ArenaConfigManager.LootItemEntry("minecraft:iron_ingot", 2, 5, 0.9));
        loot.add(new ArenaConfigManager.LootItemEntry("minecraft:ender_pearl", 1, 2, 0.5));
        return loot;
    }

    private static List<ArenaConfigManager.MobEntry> getDefaultMobs() {
        List<ArenaConfigManager.MobEntry> mobs = new ArrayList<>();
        mobs.add(new ArenaConfigManager.MobEntry("minecraft:zombie", "§4Гладиатор Арены", 40.0, "minecraft:iron_sword"));
        mobs.add(new ArenaConfigManager.MobEntry("minecraft:skeleton", "§6Снайпер Пустоты", 30.0, "minecraft:bow"));
        return mobs;
    }

    private static BlockPos getArenaCenter() {
        // Fast path: cached value is likely valid
        if (cachedArenaCenter != null) {
            String pos = ArenaConfigManager.getData().structurePlacementPos;
            if (pos != null && pos.equals(lastStructurePlacementPos)) {
                return cachedArenaCenter;
            }
        }
        // Slow path: re-parse
        synchronized (sessionLock) {
            String pos = ArenaConfigManager.getData().structurePlacementPos;
            if (pos == null || !pos.equals(lastStructurePlacementPos)) {
                cachedArenaCenter = ArenaConfigManager.parseBlockPos(pos);
                lastStructurePlacementPos = pos;
            }
        }
        return cachedArenaCenter;
    }

    private static void fillChest(ServerLevel arena, BlockPos pos, List<ArenaConfigManager.LootItemEntry> lootList) {
        arena.setBlock(pos, Blocks.CHEST.defaultBlockState(), 3);
        BlockEntity be = arena.getBlockEntity(pos);
        if (be instanceof ChestBlockEntity chest) {
            chest.clearContent();
            for (ArenaConfigManager.LootItemEntry entry : lootList) {
                if (RANDOM.nextDouble() <= entry.chance) {
                    Item item = BuiltInRegistries.ITEM.get(ArenaConfigManager.parseResourceLocation(entry.itemId));
                    if (item != Items.AIR) {
                        int count = entry.minCount + RANDOM.nextInt(Math.max(1, entry.maxCount - entry.minCount + 1));
                        int slot = RANDOM.nextInt(chest.getContainerSize());
                        chest.setItem(slot, new ItemStack(item, count));
                    }
                }
            }
        }
    }

    public static void spawnChests(ServerLevel arena) {
        List<BlockPos> positions = getChestSpawnPositions();
        List<ArenaConfigManager.LootItemEntry> lootList = ArenaConfigManager.getData().chestLoot;

        if (lootList.isEmpty()) {
            lootList = getDefaultLoot();
        }

        BlockPos center = getArenaCenter();
        int cy = center.getY();

        for (BlockPos rawPos : positions) {
            // Find the actual surface Y at this chest position so chests spawn on the platform
            int surfaceY = findChestSurfaceY(arena, rawPos.getX(), rawPos.getZ(), cy);
            BlockPos chestPos = new BlockPos(rawPos.getX(), surfaceY, rawPos.getZ());
            fillChest(arena, chestPos, lootList);
        }
        TemporaryPortalMod.LOGGER.info("Respawned chests in arena.");
    }


    public static void spawnMobWave(ServerLevel arena, int wave) {
        List<BlockPos> positions = getMobSpawnPositions();
        List<ArenaConfigManager.MobEntry> mobConfigs = ArenaConfigManager.getData().mobs;

        if (mobConfigs.isEmpty()) {
            mobConfigs = getDefaultMobs();
        }

        // Check mob cap: do not spawn if arena already has >= 16 hostile mobs
        // Use cached AABB to avoid allocation
        BlockPos center = getArenaCenter();
        int cx = center.getX(); int cy = center.getY(); int cz = center.getZ();
        
        net.minecraft.world.phys.AABB arenaBox;
        synchronized (sessionLock) {
            if (center.equals(lastMobCheckCenter) && cachedMobCheckBox != null) {
                arenaBox = cachedMobCheckBox;
            } else {
                arenaBox = new net.minecraft.world.phys.AABB(
                    cx - 16, cy - 16, cz - 16, cx + 16, cy + 16, cz + 16
                );
                cachedMobCheckBox = arenaBox;
                lastMobCheckCenter = center;
            }
        }
        
        int existingMobs = 0;
        for (Mob m : arena.getEntitiesOfClass(Mob.class, arenaBox)) {
            // Mob is never a Player, so count all mobs
            existingMobs++;
        }
        if (existingMobs >= 16) {
            TemporaryPortalMod.LOGGER.info("Mob cap reached ({} mobs alive on arena). Skipping wave.", existingMobs);
            return;
        }
        int totalSpawned = 0;
        for (BlockPos rawPos : positions) {
            // STRICT CLAMP: Mobs MUST spawn inside the platform (radius max 10 blocks from center)
            int clampedX = Math.max(cx - 10, Math.min(cx + 10, rawPos.getX()));
            int clampedZ = Math.max(cz - 10, Math.min(cz + 10, rawPos.getZ()));
            
            // Find the actual surface Y at this spawn point so mobs spawn on the platform, not in the air or underground
            int spawnY = findSurfaceY(arena, clampedX, clampedZ, cy - 10, cy + 10);

            ArenaConfigManager.MobEntry template = mobConfigs.get(RANDOM.nextInt(mobConfigs.size()));
            EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(ArenaConfigManager.parseResourceLocation(template.entityId));

            if (type != null) {
                // Wave scaling: each wave increases difficulty by 15% HP and 10% attack damage
                double waveHealthMultiplier = 1.0 + (wave - 1) * 0.15;
                double waveAttackMultiplier = 1.0 + (wave - 1) * 0.10;
                double waveSpeedMultiplier = 1.0 + (wave - 1) * 0.03;
                
                int countToSpawn = Math.max(1, Math.min(3, template.count));
                // Increase spawn count slightly with waves (up to +2 extra per point)
                countToSpawn = Math.min(countToSpawn + Math.min(wave - 1, 2), 5);
                
                for (int i = 0; i < countToSpawn; i++) {
                    double spawnX = clampedX + 0.5 + (RANDOM.nextDouble() - 0.5) * 1.5;
                    double finalSpawnY = spawnY;
                    double spawnZ = clampedZ + 0.5 + (RANDOM.nextDouble() - 0.5) * 1.5;

                    var entity = type.create(arena);
                    if (entity instanceof Mob mob) {
                        mob.moveTo(spawnX, finalSpawnY, spawnZ, RANDOM.nextFloat() * 360F, 0.0F);
                        mob.setPersistenceRequired(); // Do NOT despawn naturally

                        // Wave-based custom name with wave indicator
                        String waveName = (template.customName != null && !template.customName.isEmpty()) 
                            ? template.customName + " [Волна " + wave + "]"
                            : "Моб Арены [Волна " + wave + "]";
                        mob.setCustomName(Component.literal(waveName));
                        mob.setCustomNameVisible(true);

                        if (mob.getAttribute(Attributes.MAX_HEALTH) != null) {
                            double scaledHealth = template.maxHealth * waveHealthMultiplier;
                            mob.getAttribute(Attributes.MAX_HEALTH).setBaseValue(scaledHealth);
                            mob.setHealth((float) scaledHealth);
                        }
                        if (mob.getAttribute(Attributes.ATTACK_DAMAGE) != null) {
                            double scaledAttack = template.attackDamage * waveAttackMultiplier;
                            mob.getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(scaledAttack);
                        }
                        if (mob.getAttribute(Attributes.MOVEMENT_SPEED) != null) {
                            mob.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(template.movementSpeed * waveSpeedMultiplier);
                        }

                        equipItem(mob, EquipmentSlot.MAINHAND, template.mainHandItem);
                        equipItem(mob, EquipmentSlot.HEAD, template.helmet);
                        equipItem(mob, EquipmentSlot.CHEST, template.chestplate);
                        equipItem(mob, EquipmentSlot.LEGS, template.leggings);
                        equipItem(mob, EquipmentSlot.FEET, template.boots);

                        // Prevent equipment from dropping as 100% free loot on every mob
                        mob.setDropChance(EquipmentSlot.MAINHAND, 0.1f);
                        mob.setDropChance(EquipmentSlot.HEAD, 0.05f);

                                                // Cinematic Mob Spawn Burst (Dark smoke & soul flame flare)
                        arena.sendParticles(net.minecraft.core.particles.ParticleTypes.SOUL_FIRE_FLAME, spawnX, spawnY + 0.5, spawnZ, 12, 0.3, 0.5, 0.3, 0.05);
                        arena.sendParticles(net.minecraft.core.particles.ParticleTypes.LARGE_SMOKE, spawnX, spawnY + 0.2, spawnZ, 6, 0.2, 0.3, 0.2, 0.02);
                        arena.playSound(null, spawnX, spawnY, spawnZ, net.minecraft.sounds.SoundEvents.ZOMBIE_VILLAGER_CONVERTED, net.minecraft.sounds.SoundSource.HOSTILE, 0.7f, 1.5f);
                        arena.addFreshEntity(mob);
                        totalSpawned++;
                    }
                }
            }
        }
        TemporaryPortalMod.LOGGER.info("Wave {} — Successfully spawned {} arena mobs across {} points.", wave, totalSpawned, positions.size());
        
        // Broadcast wave announcement to all players in arena
        if (wave > 1) {
            Component waveMsg = Component.literal("§6⚔️ Волна " + wave + "! §eМобы становятся сильнее!");
            for (UUID uuid : playersInArena) {
                ServerPlayer p = arena.getServer().getPlayerList().getPlayer(uuid);
                if (p != null) {
                    p.sendSystemMessage(waveMsg, false);
                }
            }
        }
    }

    private static void equipItem(Mob mob, EquipmentSlot slot, String itemId) {
        if (itemId != null && !itemId.isEmpty()) {
            Item item = BuiltInRegistries.ITEM.get(ArenaConfigManager.parseResourceLocation(itemId));
            if (item != Items.AIR) {
                mob.setItemSlot(slot, new ItemStack(item));
            }
        }
    }

    /**
     * Finds the surface Y level at the given X,Z coordinates by scanning downward from maxY to minY.
     * Returns the Y coordinate one block above the solid ground.
     * If no ground is found, falls back to the center Y.
     */
    private static int findSurfaceY(ServerLevel level, int x, int z, int minY, int maxY) {
        for (int y = maxY; y >= minY; y--) {
            BlockPos pos = new BlockPos(x, y, z);
            net.minecraft.world.level.block.state.BlockState state = level.getBlockState(pos);
            
            // Check if this is a solid block (not air, not water, not lava)
            if (!state.isAir() && state.getFluidState().isEmpty()) {
                // Return one block above the solid ground
                return y + 1;
            }
        }
        // Fallback: center Y + 1
        return (minY + maxY) / 2 + 1;
    }

    /**
     * Finds the surface Y level for a chest at the given X,Z coordinates.
     * Scans from the config Y + 5 down to config Y - 20 to find the platform surface.
     */
    private static int findChestSurfaceY(ServerLevel level, int x, int z, int centerY) {
        return findSurfaceY(level, x, z, centerY - 20, centerY + 5);
    }
}
