package com.example.temporaryportal.network;

import com.example.temporaryportal.TemporaryPortalMod;
import com.example.temporaryportal.config.ArenaConfigManager;
import com.example.temporaryportal.world.ArenaManager;
import com.example.temporaryportal.world.DimensionSetup;
import com.example.temporaryportal.world.PortalManager;
import com.example.temporaryportal.world.StructureHandler;
import com.google.gson.Gson;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public class NetworkHandler {
    private static final Gson GSON = new Gson();

    public static void registerPayloads(final RegisterPayloadHandlersEvent event) {
        final PayloadRegistrar registrar = event.registrar(TemporaryPortalMod.MODID).versioned("1.0.0");

        // Glyph overlay to client
        registrar.playToClient(
                GlyphOverlayPayload.TYPE,
                GlyphOverlayPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> ClientPacketHandler.handleGlyphOverlay(payload))
        );

        // Sync config JSON to client (for GUI)
        registrar.playToClient(
                SyncConfigToClientPayload.TYPE,
                SyncConfigToClientPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> ClientPacketHandler.handleConfigSync(payload))
        );

        // GUI Action from client to server
        registrar.playToServer(
                GuiActionToServerPayload.TYPE,
                GuiActionToServerPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) {
                        handleServerGuiAction(player, payload);
                    }
                })
        );
    }

    public static void sendGlyphOverlay(ServerPlayer player, int durationTicks) {
        PacketDistributor.sendToPlayer(player, new GlyphOverlayPayload(durationTicks));
    }

    public static void openGuiForPlayer(ServerPlayer player) {
        String json = GSON.toJson(ArenaConfigManager.getData());
        PacketDistributor.sendToPlayer(player, new SyncConfigToClientPayload(json, true));
    }

    public static void sendActionToServer(String actionType, String payloadData) {
        PacketDistributor.sendToServer(new GuiActionToServerPayload(actionType, payloadData));
    }

    private static boolean isPlayerPermitted(ServerPlayer player) {
        if (player.getServer() == null) return false;
        // In singleplayer / dev-env always permit
        if (player.getServer().isSingleplayer()) return true;
        // Check operator status or permission level
        return player.getServer().getPlayerList().isOp(player.getGameProfile()) ||
               player.createCommandSourceStack().hasPermission(2);
    }

    private static void handleServerGuiAction(ServerPlayer player, GuiActionToServerPayload packet) {
        if (!isPlayerPermitted(player)) {
            player.sendSystemMessage(Component.literal("§c[GUI] У вас нет прав оператора для изменения настроек!"), false);
            return;
        }

        String action = packet.actionType();
        String data = packet.payloadData();
        BlockPos pos = player.blockPosition();
        String strPos = pos.getX() + ", " + pos.getY() + ", " + pos.getZ();

        TemporaryPortalMod.LOGGER.info("Handling GUI action: {} with data: {} from player: {}", action, data, player.getName().getString());

        switch (action) {
            case "START_PORTAL" -> {
                PortalManager.spawnNewEntrance(player.getServer());
                player.sendSystemMessage(Component.literal("§a[GUI] Входной портал принудительно открыт!"), false);
            }
            case "STOP_PORTAL" -> {
                PortalManager.forceClose(player.getServer());
                player.sendSystemMessage(Component.literal("§c[GUI] Входной портал закрыт."), false);
            }
            case "LOAD_STRUCTURE" -> {
                ServerLevel arena = player.getServer().getLevel(DimensionSetup.ARENA_LEVEL_KEY);
                if (arena != null) {
                    boolean ok = StructureHandler.placeArenaStructure(arena);
                    if (ok) player.sendSystemMessage(Component.literal("§a[GUI] Постройка арены успешно загружена!"), false);
                    else player.sendSystemMessage(Component.literal("§c[GUI] Ошибка загрузки! Поместите файл в config/temporaryportal/structures/"), false);
                } else {
                    player.sendSystemMessage(Component.literal("§c[GUI] Мир арены не загружен."), false);
                }
            }
            case "TP_ARENA" -> {
                ServerLevel arena = player.getServer().getLevel(DimensionSetup.ARENA_LEVEL_KEY);
                if (arena != null) {
                    BlockPos origin = ArenaConfigManager.parseBlockPos(ArenaConfigManager.getData().structurePlacementPos);
                    player.teleportTo(arena, origin.getX() + 0.5, origin.getY() + 2.0, origin.getZ() + 0.5, player.getYRot(), player.getXRot());
                    player.sendSystemMessage(Component.literal("§a[GUI] Вы телепортированы в мир арены."), false);
                }
            }
            case "ADD_ENTRANCE" -> {
                ArenaConfigManager.addPoint("entrance", strPos);
                ArenaConfigManager.save();
                player.sendSystemMessage(Component.literal("§a[GUI] Добавлена точка входа: " + strPos), false);
            }
            case "ADD_EXIT" -> {
                ArenaConfigManager.addPoint("exit", strPos);
                ArenaConfigManager.save();
                player.sendSystemMessage(Component.literal("§a[GUI] Добавлена точка выхода: " + strPos), false);
            }
            case "ADD_CHEST" -> {
                ArenaConfigManager.addPoint("chest", strPos);
                ArenaConfigManager.save();
                player.sendSystemMessage(Component.literal("§a[GUI] Добавлена точка сундука: " + strPos), false);
            }
            case "ADD_MOB_POINT" -> {
                ArenaConfigManager.addPoint("mob", strPos);
                ArenaConfigManager.save();
                player.sendSystemMessage(Component.literal("§a[GUI] Добавлена точка спавна мобов: " + strPos), false);
            }
            case "CLEAR_POINTS" -> {
                if ("mob_list".equals(data)) {
                    ArenaConfigManager.getData().mobs.clear();
                } else {
                    ArenaConfigManager.clearPoints(data);
                }
                ArenaConfigManager.save();
                player.sendSystemMessage(Component.literal("§e[GUI] Список очищен: " + data), false);
            }
            case "SET_POS1" -> {
                ArenaConfigManager.getData().arenaBounds.minX = pos.getX();
                ArenaConfigManager.getData().arenaBounds.minY = pos.getY();
                ArenaConfigManager.getData().arenaBounds.minZ = pos.getZ();
                ArenaConfigManager.save();
                player.sendSystemMessage(Component.literal("§a[GUI] Точка 1 границ арены: " + pos.toShortString()), false);
            }
            case "SET_POS2" -> {
                var b = ArenaConfigManager.getData().arenaBounds;
                b.maxX = pos.getX();
                b.maxY = pos.getY();
                b.maxZ = pos.getZ();
                int minX = Math.min(b.minX, b.maxX); int maxX = Math.max(b.minX, b.maxX);
                int minY = Math.min(b.minY, b.maxY); int maxY = Math.max(b.minY, b.maxY);
                int minZ = Math.min(b.minZ, b.maxZ); int maxZ = Math.max(b.minZ, b.maxZ);
                b.minX = minX; b.maxX = maxX;
                b.minY = minY; b.maxY = maxY;
                b.minZ = minZ; b.maxZ = maxZ;
                ArenaConfigManager.save();
                player.sendSystemMessage(Component.literal("§a[GUI] Точка 2 границ арены: " + pos.toShortString()), false);
            }
            case "TOGGLE_BOUNDS" -> {
                var b = ArenaConfigManager.getData().arenaBounds;
                b.enableBounds = !b.enableBounds;
                ArenaConfigManager.save();
                player.sendSystemMessage(Component.literal("§e[GUI] Барьер арены: " + (b.enableBounds ? "§aВКЛЮЧЕН" : "§cВЫКЛЮЧЕН")), false);
            }
                        case "REMOVE_MOB" -> {
                try {
                    int idx = Integer.parseInt(data.trim());
                    if (idx >= 0 && idx < ArenaConfigManager.getData().mobs.size()) {
                        var removed = ArenaConfigManager.getData().mobs.remove(idx);
                        ArenaConfigManager.save();
                        player.sendSystemMessage(Component.literal("§e[GUI] Моб удалён из ротации: " + removed.customName), false);
                    }
                } catch (Exception ex) {
                    TemporaryPortalMod.LOGGER.error("Error removing mob at index: " + data, ex);
                }
            }
            case "ADD_MOB" -> {
                // Format: entityId;customName;hp;weapon;customNbt
                // NBT may contain semicolons, so split with limit=5 to keep NBT intact
                String[] parts = data.split(";", 5);
                if (parts.length >= 4) {
                    try {
                        String id = parts[0].trim();
                        String name = parts[1].trim();
                        double hp = Double.parseDouble(parts[2].trim());
                        String weapon = parts.length >= 4 ? parts[3].trim() : "";
                        String customNbt = parts.length >= 5 ? parts[4].trim() : "";
                        
                        // Validate entity ID is not empty
                        if (id.isEmpty()) {
                            player.sendSystemMessage(Component.literal("§c[GUI] ID моба не может быть пустым!"), false);
                            return;
                        }
                        // Validate HP is reasonable
                        if (hp < 1.0 || hp > 10000.0) {
                            player.sendSystemMessage(Component.literal("§c[GUI] HP должно быть от 1 до 10000!"), false);
                            return;
                        }
                        
                        ArenaConfigManager.MobEntry me = new ArenaConfigManager.MobEntry(id, name, hp, weapon, customNbt);
                        ArenaConfigManager.getData().mobs.add(me);
                        ArenaConfigManager.save();
                        player.sendSystemMessage(Component.literal("§a[GUI] Моб добавлен: " + name), false);
                    } catch (NumberFormatException ex) {
                        player.sendSystemMessage(Component.literal("§c[GUI] Ошибка параметров моба. Проверьте HP (число)!"), false);
                    } catch (Exception ex) {
                        TemporaryPortalMod.LOGGER.error("Error adding mob from GUI", ex);
                        player.sendSystemMessage(Component.literal("§c[GUI] Ошибка при добавлении моба!"), false);
                    }
                }
            }
            case "ADD_LOOT" -> {
                String[] parts = data.split(";", -1);
                if (parts.length >= 4) {
                    try {
                        String id = parts[0].trim();
                        int min = Integer.parseInt(parts[1].trim());
                        int max = Integer.parseInt(parts[2].trim());
                        double ch = Double.parseDouble(parts[3].trim());
                        ArenaConfigManager.LootItemEntry le = new ArenaConfigManager.LootItemEntry(id, min, max, ch);
                        ArenaConfigManager.getData().chestLoot.add(le);
                        ArenaConfigManager.save();
                        player.sendSystemMessage(Component.literal("§a[GUI] Предмет лута добавлен: " + id), false);
                    } catch (Exception ex) {
                        player.sendSystemMessage(Component.literal("§c[GUI] Ошибка параметров лута. Проверьте числа!"), false);
                    }
                }
            }
                        case "RESET_ALL" -> {
                ArenaConfigManager.resetToDefaults();
                player.sendSystemMessage(Component.literal("§e[GUI] Все настройки сброшены к значениям по умолчанию!"), false);
            }
                        case "REGEN_ARENA" -> {
                ServerLevel arena = player.getServer().getLevel(DimensionSetup.ARENA_LEVEL_KEY);
                if (arena != null) {
                    ArenaManager.purgeAndRegenerateArena(arena);
                    player.sendSystemMessage(Component.literal("§a[GUI] Арена очищена и перегенерирована!"), false);
                }
            }
            case "RELOAD" -> {
                ArenaConfigManager.load();
                player.sendSystemMessage(Component.literal("§a[GUI] Конфиг перезагружен с диска!"), false);
            }
        }

        // Re-sync back to client so GUI immediately updates state and widgets!
        String json = GSON.toJson(ArenaConfigManager.getData());
        PacketDistributor.sendToPlayer(player, new SyncConfigToClientPayload(json, false));
    }
}
