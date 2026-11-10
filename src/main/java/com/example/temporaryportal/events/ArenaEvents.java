package com.example.temporaryportal.events;

import com.example.temporaryportal.TemporaryPortalMod;
import com.example.temporaryportal.config.ArenaConfigManager;
import com.example.temporaryportal.world.ArenaManager;
import com.example.temporaryportal.world.DimensionSetup;
import com.example.temporaryportal.world.LeaderboardManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

@EventBusSubscriber(modid = TemporaryPortalMod.MODID)
public class ArenaEvents {

    private static boolean isOp(Entity entity) {
        if (entity instanceof ServerPlayer sp) {
            return sp.getServer() != null && sp.getServer().getPlayerList().isOp(sp.getGameProfile());
        }
        return false;
    }

    private static boolean isInArena(Entity entity) {
        return entity != null && entity.level().dimension().equals(DimensionSetup.ARENA_LEVEL_KEY);
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % 40 == 0) {
            ArenaConfigManager.checkFileHotReload();
        }
        ArenaManager.tick(event.getServer());
    }

    // Per-player boundary cooldown to prevent teleport spam (track last tick each player was teleported)
    private static final java.util.concurrent.ConcurrentHashMap<java.util.UUID, Integer> boundaryCooldown = new java.util.concurrent.ConcurrentHashMap<>();

    // 1. Boundary & Force Survival Gamemode (admins exempt from gamemode enforcement)
    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer player && isInArena(player)) {
            // Strictly enforce Survival Gamemode inside arena (except for admins)
            if (!isOp(player) && player.gameMode.getGameModeForPlayer() != GameType.SURVIVAL) {
                player.setGameMode(GameType.SURVIVAL);
            }

            // Boundary enforcement — throttled to every 10 ticks per player to prevent teleport spam
            ArenaConfigManager.ArenaBounds bounds = ArenaConfigManager.getData().arenaBounds;
            if (bounds != null && bounds.enableBounds) {
                int currentTick = player.serverLevel().getServer().getTickCount();
                int lastTick = boundaryCooldown.getOrDefault(player.getUUID(), 0);
                
                // Skip boundary check if player was teleported recently (cooldown 10 ticks = 0.5s)
                if (currentTick - lastTick < 10) return;

                double px = player.getX();
                double py = player.getY();
                double pz = player.getZ();

                if (!bounds.contains(px, py, pz)) {
                    boundaryCooldown.put(player.getUUID(), currentTick);
                    
                    // If player fell below the arena (Y too low), teleport to center on the platform surface
                    // instead of clamping to minY+1 which can be inside a block
                    if (py < bounds.minY) {
                        // Find the platform surface by scanning from maxY down to minY
                        int centerX = (bounds.minX + bounds.maxX) / 2;
                        int centerZ = (bounds.minZ + bounds.maxZ) / 2;
                        int surfaceY = findSurfaceY(player.serverLevel(), centerX, centerZ, bounds.minY, bounds.maxY);
                        
                        player.teleportTo(player.serverLevel(), centerX + 0.5, surfaceY, centerZ + 0.5, player.getYRot(), player.getXRot());
                        player.sendSystemMessage(Component.literal("§c[Барьер] Вы упали за арену! Возврат на платформу..."), false);
                        player.serverLevel().sendParticles(ParticleTypes.SMOKE, px, py + 1.0, pz, 5, 0.3, 0.3, 0.3, 0.0);
                    } else {
                        // Player is outside X/Z bounds but at a valid Y — push back to nearest edge
                        double targetX = Math.max(bounds.minX + 1.5, Math.min(bounds.maxX - 1.5, px));
                        double targetY = Math.max(bounds.minY + 1.0, Math.min(bounds.maxY - 1.0, py));
                        double targetZ = Math.max(bounds.minZ + 1.5, Math.min(bounds.maxZ - 1.5, pz));

                        player.teleportTo(player.serverLevel(), targetX, targetY, targetZ, player.getYRot(), player.getXRot());
                        player.sendSystemMessage(Component.literal("§c[Барьер] Вы достигли границы боевой арены!"), true);
                        player.serverLevel().sendParticles(ParticleTypes.SMOKE, px, py + 1.0, pz, 3, 0.2, 0.2, 0.2, 0.0);
                    }
                }
            }
        }
    }

    // 2. STRICT: FORBID BLOCK BREAKING IN ARENA (admins exempt)
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (isInArena(event.getPlayer()) && !isOp(event.getPlayer())) {
            event.setCanceled(true);
            if (event.getPlayer() instanceof ServerPlayer sp) {
                sp.sendSystemMessage(Component.literal("§c[Арена] Здесь запрещено ломать блоки!"), true);
            }
        }
    }

    // 3. STRICT: FORBID BLOCK PLACING IN ARENA (admins exempt)
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.getEntity() instanceof ServerPlayer && isInArena(event.getEntity()) && !isOp(event.getEntity())) {
            event.setCanceled(true);
            if (event.getEntity() instanceof ServerPlayer sp) {
                sp.sendSystemMessage(Component.literal("§c[Арена] Здесь запрещено ставить блоки!"), true);
            }
        }
    }

    // 4. STRICT: FORBID INTERACTION WITH BLOCK ITEMS / WORLD ALTERING ITEMS (admins exempt)
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (isInArena(event.getEntity()) && !isOp(event.getEntity())) {
            ItemStack stack = event.getItemStack();

            // Forbid placing any block by right clicking
            if (stack.getItem() instanceof BlockItem) {
                event.setCanceled(true);
                event.setCancellationResult(InteractionResult.FAIL);
                return;
            }

            // Forbid buckets, fire, bone meal
            if (stack.is(Items.WATER_BUCKET) || stack.is(Items.LAVA_BUCKET) ||
                stack.is(Items.FLINT_AND_STEEL) || stack.is(Items.FIRE_CHARGE) ||
                stack.is(Items.BONE_MEAL) || stack.is(Items.ARMOR_STAND) ||
                stack.is(Items.END_CRYSTAL)) {
                event.setCanceled(true);
                event.setCancellationResult(InteractionResult.FAIL);
            }
        }
    }

    // 5. Forbid Commands inside Arena (admins/operators can use any command)
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onCommand(CommandEvent event) {
        try {
            CommandSourceStack source = event.getParseResults().getContext().getSource();
            if (source.getEntity() instanceof ServerPlayer player && isInArena(player)) {
                // Admins/operators are allowed to use any command in the arena
                if (isOp(player)) {
                    return;
                }
                String rawCommand = event.getParseResults().getReader().getString();
                // Allow only /portal commands for regular players
                if (!rawCommand.startsWith("portal") && !rawCommand.startsWith("/portal")) {
                    event.setCanceled(true);
                    player.sendSystemMessage(Component.literal("§c[Арена] Использование команд здесь запрещено!"));
                }
            }
        } catch (Exception e) {
            // If command parsing fails, don't crash the server — just skip the check
            TemporaryPortalMod.LOGGER.debug("Failed to parse command in arena check", e);
        }
    }

// Track players who died in arena so we can trigger respawn animation
// Cleaned up on logout to prevent memory leak when player dies then quits without respawning
private static final java.util.Set<java.util.UUID> playersDiedInArena = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    // Clean up dead player UUID on logout to prevent memory leak
    @SubscribeEvent
    public static void onPlayerLogout(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            playersDiedInArena.remove(player.getUUID());
        }
    }

    // 7. STRICT: FORBID ANY EXPLOSION FROM DESTROYING BLOCKS IN ARENA (Creepers, TNT, etc.)
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onExplosionDetonate(net.neoforged.neoforge.event.level.ExplosionEvent.Detonate event) {
        if (event.getLevel().dimension().equals(DimensionSetup.ARENA_LEVEL_KEY)) {
            // Clear all blocks from explosion damage list, blocks never break
            event.getAffectedBlocks().clear();
        }
    }

    // 8. STRICT: FORBID MOB GRIEFING IN ARENA (Endermen, Wither, Ravager, etc.)
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onMobGriefing(net.neoforged.neoforge.event.entity.EntityMobGriefingEvent event) {
        if (event.getEntity() != null && isInArena(event.getEntity())) {
            event.setCanGrief(false);
        }
    }

    // 6. Handle Player Death on Arena
    @SubscribeEvent
    public static void onPlayerDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && isInArena(player)) {
            playersDiedInArena.add(player.getUUID());
            ArenaManager.removePlayer(player.getUUID());
            // Play glyph overlay on death screen
            com.example.temporaryportal.network.NetworkHandler.sendGlyphOverlay(player, 60);
            player.sendSystemMessage(Component.literal("§c[Арена] Вы погибли в бою! Возрождение на кровати..."));
        }
        
        // Track mob kills by players in the arena for leaderboard
        if (isInArena(event.getEntity()) && event.getSource().getDirectEntity() instanceof ServerPlayer killer) {
            // Only count if the killed entity is a mob (not a player)
            if (!(event.getEntity() instanceof Player)) {
                LeaderboardManager.recordMobKill(killer.getUUID(), killer.getName().getString());
            }
        }
    }

    

    // 7. Trigger Glyph Animation on Respawn after dying in Arena
    @SubscribeEvent
    public static void onPlayerRespawn(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            if (playersDiedInArena.remove(player.getUUID())) {
                com.example.temporaryportal.network.NetworkHandler.sendGlyphOverlay(player, 60);
                player.playNotifySound(net.minecraft.sounds.SoundEvents.PLAYER_TELEPORT, net.minecraft.sounds.SoundSource.PLAYERS, 1.0f, 1.0f);
                player.sendSystemMessage(Component.literal("§e[Арена] Вы переродились на точке возрождения."));
            }
        }
    }

    /**
     * Finds the surface Y level at the given X,Z coordinates by scanning downward from maxY to minY.
     * Returns the Y coordinate one block above the solid ground.
     * If no ground is found, falls back to (minY + maxY) / 2.
     */
    private static int findSurfaceY(net.minecraft.server.level.ServerLevel level, int x, int z, int minY, int maxY) {
        for (int y = maxY; y >= minY; y--) {
            net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(x, y, z);
            net.minecraft.world.level.block.state.BlockState state = level.getBlockState(pos);
            
            // Check if this is a solid block (not air, not water, not lava)
            if (!state.isAir() && state.getFluidState().isEmpty()) {
                // Return one block above the solid ground
                return y + 1;
            }
        }
        // Fallback: middle of the arena bounds
        return (minY + maxY) / 2;
    }
}
