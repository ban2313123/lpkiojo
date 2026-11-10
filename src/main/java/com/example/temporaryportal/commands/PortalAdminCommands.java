package com.example.temporaryportal.commands;

import com.example.temporaryportal.config.ArenaConfigManager;
import com.example.temporaryportal.network.NetworkHandler;
import com.example.temporaryportal.world.ArenaManager;
import com.example.temporaryportal.world.DimensionSetup;
import com.example.temporaryportal.world.LeaderboardManager;
import com.example.temporaryportal.world.PortalManager;
import com.example.temporaryportal.world.StructureHandler;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

public class PortalAdminCommands {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("portal")
                .requires(source -> source.hasPermission(4)) // Op level 4
                // /portal gui
                .then(Commands.literal("gui")
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            NetworkHandler.openGuiForPlayer(player);
                            return 1;
                        })
                )

                // /portal start
                                // /portal testmobs
                                // /portal resetarena
                .then(Commands.literal("resetarena")
                        .executes(ctx -> {
                            ServerLevel arena = ctx.getSource().getServer().getLevel(DimensionSetup.ARENA_LEVEL_KEY);
                            if (arena != null) {
                                ArenaManager.purgeAndRegenerateArena(arena);
                                ctx.getSource().sendSuccess(() -> Component.literal("§a[Портал] Арена полностью очищена и перегенерирована!"), true);
                                return 1;
                            }
                            ctx.getSource().sendFailure(Component.literal("§cМир арены не найден!"));
                            return 0;
                        })
                )
                .then(Commands.literal("testmobs")
                        .executes(ctx -> {
                            ServerLevel arena = ctx.getSource().getServer().getLevel(DimensionSetup.ARENA_LEVEL_KEY);
                            if (arena != null) {
                                ArenaManager.spawnMobWave(arena, 1);
                                ctx.getSource().sendSuccess(() -> Component.literal("§a[Портал] Волна мобов принудительно заспавнена на арене!"), true);
                                return 1;
                            }
                            ctx.getSource().sendFailure(Component.literal("§cМир арены не найден!"));
                            return 0;
                        })
                )
                .then(Commands.literal("start")
                        .executes(ctx -> {
                            PortalManager.spawnNewEntrance(ctx.getSource().getServer());
                            ctx.getSource().sendSuccess(() -> Component.literal("§a[Портал] Событие принудительно запущено!"), true);
                            return 1;
                        })
                )

                // /portal stop
                .then(Commands.literal("stop")
                        .executes(ctx -> {
                            PortalManager.forceClose(ctx.getSource().getServer());
                            ctx.getSource().sendSuccess(() -> Component.literal("§c[Портал] Портал принудительно закрыт."), true);
                            return 1;
                        })
                )

                // /portal reload (instant hot-reload)
                .then(Commands.literal("reload")
                        .executes(ctx -> {
                            ArenaConfigManager.load();
                            ctx.getSource().sendSuccess(() -> Component.literal("§a[Портал] Конфигурация portal_config.json мгновенно перезагружена!"), true);
                            return 1;
                        })
                )

                // /portal loadstructure
                .then(Commands.literal("loadstructure")
                        .executes(ctx -> {
                            ServerLevel arena = ctx.getSource().getServer().getLevel(DimensionSetup.ARENA_LEVEL_KEY);
                            if (arena == null) {
                                ctx.getSource().sendFailure(Component.literal("§cМир арены не найден!"));
                                return 0;
                            }
                            boolean ok = StructureHandler.placeArenaStructure(arena);
                            if (ok) {
                                ctx.getSource().sendSuccess(() -> Component.literal("§aПостройка успешно загружена в мир арены!"), true);
                            } else {
                                ctx.getSource().sendFailure(Component.literal("§cНе удалось загрузить постройку. Поместите .nbt файл в config/temporaryportal/structures/"));
                            }
                            return ok ? 1 : 0;
                        })
                )

                // /portal tparena
                .then(Commands.literal("tparena")
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            ServerLevel arena = ctx.getSource().getServer().getLevel(DimensionSetup.ARENA_LEVEL_KEY);
                            if (arena != null) {
                                BlockPos origin = ArenaConfigManager.parseBlockPos(ArenaConfigManager.getData().structurePlacementPos);
                                player.teleportTo(arena, origin.getX() + 0.5, origin.getY() + 2.0, origin.getZ() + 0.5, player.getYRot(), player.getXRot());
                                ctx.getSource().sendSuccess(() -> Component.literal("§aТелепортированы в мир арены."), false);
                                return 1;
                            }
                            ctx.getSource().sendFailure(Component.literal("§cМир арены недоступен!"));
                            return 0;
                        })
                )

                // Subcommand: /portal bounds ...
                .then(Commands.literal("bounds")
                        .then(Commands.literal("set")
                                .then(Commands.argument("minX", IntegerArgumentType.integer())
                                        .then(Commands.argument("minY", IntegerArgumentType.integer())
                                                .then(Commands.argument("minZ", IntegerArgumentType.integer())
                                                        .then(Commands.argument("maxX", IntegerArgumentType.integer())
                                                                .then(Commands.argument("maxY", IntegerArgumentType.integer())
                                                                        .then(Commands.argument("maxZ", IntegerArgumentType.integer())
                                                                                .executes(ctx -> {
                                                                                    int minX = IntegerArgumentType.getInteger(ctx, "minX");
                                                                                    int minY = IntegerArgumentType.getInteger(ctx, "minY");
                                                                                    int minZ = IntegerArgumentType.getInteger(ctx, "minZ");
                                                                                    int maxX = IntegerArgumentType.getInteger(ctx, "maxX");
                                                                                    int maxY = IntegerArgumentType.getInteger(ctx, "maxY");
                                                                                    int maxZ = IntegerArgumentType.getInteger(ctx, "maxZ");

                                                                                    ArenaConfigManager.getData().arenaBounds = 
                                                                                            new ArenaConfigManager.ArenaBounds(
                                                                                                    Math.min(minX, maxX), Math.min(minY, maxY), Math.min(minZ, maxZ),
                                                                                                    Math.max(minX, maxX), Math.max(minY, maxY), Math.max(minZ, maxZ)
                                                                                            );
                                                                                    ArenaConfigManager.save();
                                                                                    ctx.getSource().sendSuccess(() -> Component.literal(
                                                                                            String.format("§aГраницы арены обновлены: [%d, %d, %d] -> [%d, %d, %d]",
                                                                                                    Math.min(minX, maxX), Math.min(minY, maxY), Math.min(minZ, maxZ),
                                                                                                    Math.max(minX, maxX), Math.max(minY, maxY), Math.max(minZ, maxZ))
                                                                                    ), true);
                                                                                    return 1;
                                                                                })
                                                                        )
                                                                )
                                                        )
                                                )
                                        )
                                )
                        )
                        .then(Commands.literal("pos1")
                                .executes(ctx -> {
                                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                                    BlockPos pos = p.blockPosition();
                                    var b = ArenaConfigManager.getData().arenaBounds;
                                    b.minX = pos.getX();
                                    b.minY = pos.getY();
                                    b.minZ = pos.getZ();
                                    ArenaConfigManager.save();
                                    ctx.getSource().sendSuccess(() -> Component.literal("§aТочка 1 границ арены установлена на: " + pos.toShortString()), true);
                                    return 1;
                                })
                        )
                        .then(Commands.literal("pos2")
                                .executes(ctx -> {
                                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                                    BlockPos pos = p.blockPosition();
                                    var b = ArenaConfigManager.getData().arenaBounds;
                                    b.maxX = pos.getX();
                                    b.maxY = pos.getY();
                                    b.maxZ = pos.getZ();
                                    // Ensure min <= max
                                    int rMinX = Math.min(b.minX, b.maxX);
                                    int rMaxX = Math.max(b.minX, b.maxX);
                                    int rMinY = Math.min(b.minY, b.maxY);
                                    int rMaxY = Math.max(b.minY, b.maxY);
                                    int rMinZ = Math.min(b.minZ, b.maxZ);
                                    int rMaxZ = Math.max(b.minZ, b.maxZ);
                                    b.minX = rMinX; b.maxX = rMaxX;
                                    b.minY = rMinY; b.maxY = rMaxY;
                                    b.minZ = rMinZ; b.maxZ = rMaxZ;
                                    ArenaConfigManager.save();
                                    ctx.getSource().sendSuccess(() -> Component.literal("§aТочка 2 границ арены установлена. Границы сохранены."), true);
                                    return 1;
                                })
                        )
                        .then(Commands.literal("info")
                                .executes(ctx -> {
                                    var b = ArenaConfigManager.getData().arenaBounds;
                                    ctx.getSource().sendSuccess(() -> Component.literal(
                                            String.format("§6Границы арены: от [%d, %d, %d] до [%d, %d, %d] (Включено: %b)",
                                                    b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ, b.enableBounds)
                                    ), false);
                                    return 1;
                                })
                        )
                )

                // Subcommand: /portal point ...
                .then(Commands.literal("point")
                        .then(Commands.literal("add")
                                .then(Commands.literal("entrance")
                                        .executes(ctx -> addPoint(ctx.getSource(), "entrance"))
                                )
                                .then(Commands.literal("exit")
                                        .executes(ctx -> addPoint(ctx.getSource(), "exit"))
                                )
                                .then(Commands.literal("chest")
                                        .executes(ctx -> addPoint(ctx.getSource(), "chest"))
                                )
                                .then(Commands.literal("mob")
                                        .executes(ctx -> addPoint(ctx.getSource(), "mob"))
                                )
                        )
                        .then(Commands.literal("clear")
                                .then(Commands.literal("entrance")
                                        .executes(ctx -> clearPoints(ctx.getSource(), "entrance"))
                                )
                                .then(Commands.literal("exit")
                                        .executes(ctx -> clearPoints(ctx.getSource(), "exit"))
                                )
                                .then(Commands.literal("chest")
                                        .executes(ctx -> clearPoints(ctx.getSource(), "chest"))
                                )
                                .then(Commands.literal("mob")
                                        .executes(ctx -> clearPoints(ctx.getSource(), "mob"))
                                )
                        )
                        .then(Commands.literal("list")
                                .executes(ctx -> listPoints(ctx.getSource()))
                        )
                )

                // Subcommand: /portal mob ...
                .then(Commands.literal("mob")
                        .then(Commands.literal("add")
                                .then(Commands.argument("entityId", StringArgumentType.word())
                                        .then(Commands.argument("name", StringArgumentType.string())
                                                .then(Commands.argument("health", DoubleArgumentType.doubleArg(1.0, 1000.0))
                                                        .then(Commands.argument("weapon", StringArgumentType.word())
                                                                .executes(ctx -> {
                                                                    String entityId = StringArgumentType.getString(ctx, "entityId");
                                                                    String name = StringArgumentType.getString(ctx, "name");
                                                                    double hp = DoubleArgumentType.getDouble(ctx, "health");
                                                                    String weapon = StringArgumentType.getString(ctx, "weapon");

                                                                    ArenaConfigManager.MobEntry entry = new ArenaConfigManager.MobEntry(entityId, name, hp, weapon);
                                                                    ArenaConfigManager.getData().mobs.add(entry);
                                                                    ArenaConfigManager.save();
                                                                    ctx.getSource().sendSuccess(() -> Component.literal("§aМоб добавлен: " + name + " (" + entityId + ")"), true);
                                                                    return 1;
                                                                })
                                                        )
                                                )
                                        )
                                )
                        )
                        .then(Commands.literal("clear")
                                .executes(ctx -> {
                                    ArenaConfigManager.getData().mobs.clear();
                                    ArenaConfigManager.save();
                                    ctx.getSource().sendSuccess(() -> Component.literal("§eСписок мобов очищен."), true);
                                    return 1;
                                })
                        )
                )

                // Subcommand: /portal leaderboard
                .then(Commands.literal("leaderboard")
                        .executes(ctx -> showLeaderboard(ctx.getSource()))
                        .then(Commands.literal("survivors")
                                .executes(ctx -> showLeaderboard(ctx.getSource(), "survivors"))
                        )
                        .then(Commands.literal("killers")
                                .executes(ctx -> showLeaderboard(ctx.getSource(), "killers"))
                        )
                        .then(Commands.argument("player", StringArgumentType.string())
                                .executes(ctx -> {
                                    String playerName = StringArgumentType.getString(ctx, "player");
                                    LeaderboardManager.PlayerStats stats = LeaderboardManager.getPlayerStatsByName(playerName);
                                    if (stats != null) {
                                        ctx.getSource().sendSuccess(() -> Component.literal(
                                                "§6📊 Статистика " + stats.playerName + ":\n" +
                                                "  §eВыживаний: §f" + stats.survivals + "\n" +
                                                "  §eМобов убито: §f" + stats.mobsKilled + "\n" +
                                                "  §eМакс. волна: §f" + stats.totalWaves
                                        ), false);
                                    } else {
                                        ctx.getSource().sendFailure(Component.literal("§cИгрок " + playerName + " не найден в статистике."));
                                    }
                                    return 1;
                                })
                        )
                )

                // Subcommand: /portal loot ...
                .then(Commands.literal("loot")
                        .then(Commands.literal("add")
                                .then(Commands.argument("itemId", StringArgumentType.word())
                                        .then(Commands.argument("min", IntegerArgumentType.integer(1, 64))
                                                .then(Commands.argument("max", IntegerArgumentType.integer(1, 64))
                                                        .then(Commands.argument("chance", DoubleArgumentType.doubleArg(0.01, 1.0))
                                                                .executes(ctx -> {
                                                                    String item = StringArgumentType.getString(ctx, "itemId");
                                                                    int min = IntegerArgumentType.getInteger(ctx, "min");
                                                                    int max = IntegerArgumentType.getInteger(ctx, "max");
                                                                    double ch = DoubleArgumentType.getDouble(ctx, "chance");

                                                                    ArenaConfigManager.LootItemEntry entry = new ArenaConfigManager.LootItemEntry(item, min, max, ch);
                                                                    ArenaConfigManager.getData().chestLoot.add(entry);
                                                                    ArenaConfigManager.save();
                                                                    ctx.getSource().sendSuccess(() -> Component.literal("§aЛут добавлен: " + item + " (" + min + "-" + max + ", шанс " + ch + ")"), true);
                                                                    return 1;
                                                                })
                                                        )
                                                )
                                        )
                                )
                        )
                        .then(Commands.literal("clear")
                                .executes(ctx -> {
                                    ArenaConfigManager.getData().chestLoot.clear();
                                    ArenaConfigManager.save();
                                    ctx.getSource().sendSuccess(() -> Component.literal("§eСписок лута очищен."), true);
                                    return 1;
                                })
                        )
                )
        );
    }

    private static final String[] POINT_TYPES = {"entrance", "exit", "chest", "mob"};
    private static final String[] POINT_LABELS = {"входа", "выхода", "сундука", "спавна мобов"};

    private static int addPoint(CommandSourceStack source, String type) {
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.literal("Команду может выполнять только игрок."));
            return 0;
        }

        BlockPos pos = player.blockPosition();
        String strPos = pos.getX() + ", " + pos.getY() + ", " + pos.getZ();

        ArenaConfigManager.addPoint(type, strPos);
        ArenaConfigManager.save();

        int idx = getPointTypeIndex(type);
        String label = idx >= 0 ? POINT_LABELS[idx] : type;
        source.sendSuccess(() -> Component.literal(
                "§aТочка " + label + " добавлена: " + strPos + " (всего: " + ArenaConfigManager.getPointCount(type) + ")"), true);
        return 1;
    }

    private static int clearPoints(CommandSourceStack source, String type) {
        ArenaConfigManager.clearPoints(type);
        ArenaConfigManager.save();
        source.sendSuccess(() -> Component.literal("§eТочки для [" + type + "] очищены."), true);
        return 1;
    }

    private static int listPoints(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("§6=== Точки Временного Портала ==="), false);
        source.sendSuccess(() -> Component.literal("§eВходы (Overworld): §f" + ArenaConfigManager.getPointCount("entrance") + " шт."), false);
        source.sendSuccess(() -> Component.literal("§eВыходы (Arena): §f" + ArenaConfigManager.getPointCount("exit") + " шт."), false);
        source.sendSuccess(() -> Component.literal("§eСундуки (Arena): §f" + ArenaConfigManager.getPointCount("chest") + " шт."), false);
        source.sendSuccess(() -> Component.literal("§eСпавны мобов (Arena): §f" + ArenaConfigManager.getPointCount("mob") + " шт."), false);
        return 1;
    }

    private static int getPointTypeIndex(String type) {
        for (int i = 0; i < POINT_TYPES.length; i++) {
            if (POINT_TYPES[i].equals(type)) return i;
        }
        return -1;
    }

    private static int showLeaderboard(CommandSourceStack source) {
        return showLeaderboard(source, "both");
    }

    private static int showLeaderboard(CommandSourceStack source, String type) {
        source.sendSuccess(() -> Component.literal("§6╔══════════════════════════════════════════╗"), false);
        source.sendSuccess(() -> Component.literal("§6║     🏆 Таблица лидеров арены 🏆         ║"), false);
        source.sendSuccess(() -> Component.literal("§6╚══════════════════════════════════════════╝"), false);

        if ("both".equals(type) || "survivors".equals(type)) {
            source.sendSuccess(() -> Component.literal(""), false);
            source.sendSuccess(() -> Component.literal("§e👑 Топ выживших:"), false);
            List<LeaderboardManager.PlayerStats> topSurvivors = LeaderboardManager.getTopSurvivors(10);
            if (topSurvivors.isEmpty()) {
                source.sendSuccess(() -> Component.literal("§7  (пока пусто)"), false);
            } else {
                int rank = 1;
                for (LeaderboardManager.PlayerStats stats : topSurvivors) {
                    String rankEmoji = rank == 1 ? "🥇" : rank == 2 ? "🥈" : rank == 3 ? "🥉" : "  " + rank + ".";
                    source.sendSuccess(() -> Component.literal(
                            "§f" + rankEmoji + " §e" + stats.playerName + " §7- §f" + stats.survivals + " выживаний, §f" + stats.mobsKilled + " убийств, §fволна " + stats.totalWaves
                    ), false);
                    rank++;
                }
            }
        }

        if ("both".equals(type) || "killers".equals(type)) {
            if ("both".equals(type)) {
                source.sendSuccess(() -> Component.literal(""), false);
            }
            source.sendSuccess(() -> Component.literal("§e⚔️ Топ убийц:"), false);
            List<LeaderboardManager.PlayerStats> topKillers = LeaderboardManager.getTopKillers(10);
            if (topKillers.isEmpty()) {
                source.sendSuccess(() -> Component.literal("§7  (пока пусто)"), false);
            } else {
                int rank = 1;
                for (LeaderboardManager.PlayerStats stats : topKillers) {
                    String rankEmoji = rank == 1 ? "🥇" : rank == 2 ? "🥈" : rank == 3 ? "🥉" : "  " + rank + ".";
                    source.sendSuccess(() -> Component.literal(
                            "§f" + rankEmoji + " §e" + stats.playerName + " §7- §f" + stats.mobsKilled + " убийств, §f" + stats.survivals + " выживаний, §fволна " + stats.totalWaves
                    ), false);
                    rank++;
                }
            }
        }

        return 1;
    }
}
