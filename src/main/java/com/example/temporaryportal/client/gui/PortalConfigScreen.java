package com.example.temporaryportal.client.gui;

import com.example.temporaryportal.config.ArenaConfigManager;
import com.example.temporaryportal.config.ModRegistryScanner;
import com.example.temporaryportal.network.NetworkHandler;
import com.example.temporaryportal.world.PortalManager;
import com.google.gson.Gson;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

public class PortalConfigScreen extends Screen {
    private static final Gson GSON = new Gson();
    private static ArenaConfigManager.ConfigData clientCache = new ArenaConfigManager.ConfigData();

    // Dialog Window Standard Dimensions
    public static final int CARD_W = 320;
    public static final int CARD_H = 215;

    private int getCardLeft() {
        return (this.width - CARD_W) / 2;
    }

    private int getCardTop() {
        return Math.max(12, (this.height - CARD_H) / 2);
    }

    // Tabs: 0 = General, 1 = Points & Bounds, 2 = Scratch Editor, 3 = Mob List Manager, 4 = Chest Loot
    private int currentTab = 0;
    private int mobListPage = 0;

    // Simple tab indicator position (no animation)
    private float currentTabIndicatorX = 0.0f;

    // Mob Carousel Selection
    private int mobCarouselIndex = 0;
    private List<ModRegistryScanner.ScannedMob> scannedMobsList = new ArrayList<>();

    // --- MCreator / Scratch Puzzle Engine with Dynamic Level Parameter ---
    public static class ScratchBlockDefinition {
        public String id;
        public String label;
        public String nbtTemplate;
        public int color;
        public int darkColor;
        public int category; // 0=Оружие, 1=Броня, 2=Баффы, 3=Свойства, 4=Моды, 5=Мобы
        public boolean hasLevel;
        public int currentLevel;
        public int maxLevel;

        public ScratchBlockDefinition(String id, String label, String nbtTemplate, int color, int darkColor, int category, boolean hasLevel, int defaultLevel, int maxLevel) {
            this.id = id;
            this.label = label;
            this.nbtTemplate = nbtTemplate;
            this.color = color;
            this.darkColor = darkColor;
            this.category = category;
            this.hasLevel = hasLevel;
            this.currentLevel = defaultLevel;
            this.maxLevel = maxLevel;
        }

        public ScratchBlockDefinition copy() {
            return new ScratchBlockDefinition(id, label, nbtTemplate, color, darkColor, category, hasLevel, currentLevel, maxLevel);
        }

        public String getCompiledNbt() {
            if (!hasLevel) return nbtTemplate;
            int amp = Math.max(0, currentLevel - 1);
            return nbtTemplate.replace("%LEVEL%", String.valueOf(currentLevel)).replace("%AMP%", String.valueOf(amp));
        }

        public String getDisplayLabel() {
            if (!hasLevel) return label;
            return label + " [" + currentLevel + "]";
        }
    }

    private final List<ScratchBlockDefinition> canvasPuzzleStack = new ArrayList<>();
    private final List<ScratchBlockDefinition> toolboxBlocks = new ArrayList<>();
    private int activeToolboxCategory = 0; // 0=Оружие, 1=Броня, 2=Баффы, 3=Свойства, 4=Моды, 5=Мобы
    private int puzzleScrollOffset = 0; // Scroll offset for puzzle canvas

    // Cat mascot animation
    private long catAnimTick = 0; // Increments each render frame

    // Mob Parameters
    private EditBox mobIdField;
    private EditBox mobNameField;
    private EditBox mobHpField;

    // Tab 4 Loot Parameters
    private EditBox lootIdField;
    private EditBox lootMinField;
    private EditBox lootMaxField;
    private EditBox lootChanceField;

    public PortalConfigScreen() {
        super(Component.literal("Панель Временного Портала"));
        initToolbox();
    }

    private void initToolbox() {
        toolboxBlocks.clear();
        ModRegistryScanner.scan();
        scannedMobsList = ModRegistryScanner.getMobs("");

        // 0: Оружие
        toolboxBlocks.add(new ScratchBlockDefinition("sword_sharp", "⚔ Меч: Острота", 
                "HandItems:[{id:\"minecraft:netherite_sword\",Count:1b,components:{\"minecraft:enchantments\":{levels:{\"minecraft:sharpness\":%LEVEL%,\"minecraft:fire_aspect\":2}}}},{}]", 
                0xFFFF8C00, 0xFFB35900, 0, true, 5, 20));
        toolboxBlocks.add(new ScratchBlockDefinition("bow_power", "🏹 Лук: Сила", 
                "HandItems:[{id:\"minecraft:bow\",Count:1b,components:{\"minecraft:enchantments\":{levels:{\"minecraft:power\":%LEVEL%,\"minecraft:flame\":1,\"minecraft:infinity\":1}}}},{}]", 
                0xFFFF8C00, 0xFFB35900, 0, true, 5, 20));
        toolboxBlocks.add(new ScratchBlockDefinition("axe_smite", "🪓 Топор: Кара", 
                "HandItems:[{id:\"minecraft:netherite_axe\",Count:1b,components:{\"minecraft:enchantments\":{levels:{\"minecraft:smite\":%LEVEL%,\"minecraft:knockback\":2}}}},{}]", 
                0xFFFF8C00, 0xFFB35900, 0, true, 5, 20));
        toolboxBlocks.add(new ScratchBlockDefinition("trident_imp", "🔱 Трезубец: Пронзание", 
                "HandItems:[{id:\"minecraft:trident\",Count:1b,components:{\"minecraft:enchantments\":{levels:{\"minecraft:impaling\":%LEVEL%,\"minecraft:loyalty\":3}}}},{}]", 
                0xFFFF8C00, 0xFFB35900, 0, true, 5, 20));

        // 1: Броня
        toolboxBlocks.add(new ScratchBlockDefinition("armor_neth", "🛡 Сет Незерит: Защита", 
                "ArmorItems:[{id:\"minecraft:netherite_boots\",Count:1b,components:{\"minecraft:enchantments\":{levels:{\"minecraft:protection\":%LEVEL%}}}},{id:\"minecraft:netherite_leggings\",Count:1b,components:{\"minecraft:enchantments\":{levels:{\"minecraft:protection\":%LEVEL%}}}},{id:\"minecraft:netherite_chestplate\",Count:1b,components:{\"minecraft:enchantments\":{levels:{\"minecraft:protection\":%LEVEL%,\"minecraft:thorns\":3}}}},{id:\"minecraft:netherite_helmet\",Count:1b,components:{\"minecraft:enchantments\":{levels:{\"minecraft:protection\":%LEVEL%}}}}],ArmorDropChances:[0.05f,0.05f,0.05f,0.05f]", 
                0xFF22C55E, 0xFF15803D, 1, true, 4, 10));
        toolboxBlocks.add(new ScratchBlockDefinition("armor_dia", "💎 Сет Алмаз: Защита", 
                "ArmorItems:[{id:\"minecraft:diamond_boots\",Count:1b,components:{\"minecraft:enchantments\":{levels:{\"minecraft:protection\":%LEVEL%}}}},{id:\"minecraft:diamond_leggings\",Count:1b},{id:\"minecraft:diamond_chestplate\",Count:1b,components:{\"minecraft:enchantments\":{levels:{\"minecraft:protection\":%LEVEL%}}}},{id:\"minecraft:diamond_helmet\",Count:1b}],ArmorDropChances:[0.08f,0.08f,0.08f,0.08f]", 
                0xFF22C55E, 0xFF15803D, 1, true, 4, 10));
        toolboxBlocks.add(new ScratchBlockDefinition("skull_wither", "👑 Голова: Череп Визера", 
                "ArmorItems:[{},{},{},{id:\"minecraft:wither_skeleton_skull\",Count:1b}]", 
                0xFF22C55E, 0xFF15803D, 1, false, 1, 1));
        toolboxBlocks.add(new ScratchBlockDefinition("pumpkin_head", "🎃 Голова: Тыква", 
                "ArmorItems:[{},{},{},{id:\"minecraft:carved_pumpkin\",Count:1b}]", 
                0xFF22C55E, 0xFF15803D, 1, false, 1, 1));

        // 2: Баффы
        toolboxBlocks.add(new ScratchBlockDefinition("buff_speed", "⚡ Скорость", 
                "active_effects:[{id:\"minecraft:speed\",amplifier:%AMP%b,duration:99999}]", 
                0xFF8B5CF6, 0xFF6D28D9, 2, true, 2, 5));
        toolboxBlocks.add(new ScratchBlockDefinition("buff_strength", "💪 Сила", 
                "active_effects:[{id:\"minecraft:strength\",amplifier:%AMP%b,duration:99999}]", 
                0xFF8B5CF6, 0xFF6D28D9, 2, true, 2, 5));
        toolboxBlocks.add(new ScratchBlockDefinition("buff_resist", "🛡 Сопротивление", 
                "active_effects:[{id:\"minecraft:resistance\",amplifier:%AMP%b,duration:99999}]", 
                0xFF8B5CF6, 0xFF6D28D9, 2, true, 2, 5));
        toolboxBlocks.add(new ScratchBlockDefinition("buff_fire", "🔥 Огнестойкость", 
                "active_effects:[{id:\"minecraft:fire_resistance\",amplifier:0b,duration:99999}]", 
                0xFF8B5CF6, 0xFF6D28D9, 2, false, 1, 1));

        // 3: Свойства
        toolboxBlocks.add(new ScratchBlockDefinition("prop_glowing", "✨ Свечение", "Glowing:1b", 0xFF3B82F6, 0xFF1D4ED8, 3, false, 1, 1));
        toolboxBlocks.add(new ScratchBlockDefinition("prop_silent", "🔇 Безмолвность", "Silent:1b", 0xFF3B82F6, 0xFF1D4ED8, 3, false, 1, 1));
        toolboxBlocks.add(new ScratchBlockDefinition("prop_baby", "👶 Малыш", "IsBaby:1b", 0xFF3B82F6, 0xFF1D4ED8, 3, false, 1, 1));
        toolboxBlocks.add(new ScratchBlockDefinition("prop_boom", "💥 Радиус взрыва", "ExplosionRadius:%LEVEL%b,Fuse:15s", 0xFF3B82F6, 0xFF1D4ED8, 3, true, 4, 15));

        // 4: Оружие из Модов
        List<ModRegistryScanner.ScannedItem> modItems = ModRegistryScanner.getWeapons("");
        int added = 0;
        for (ModRegistryScanner.ScannedItem item : modItems) {
            if (!item.modId().equals("minecraft")) {
                toolboxBlocks.add(new ScratchBlockDefinition("mod_" + item.id(), "📦 [" + item.modId() + "] " + item.name(), 
                        "HandItems:[{id:\"" + item.id() + "\",Count:1b,components:{\"minecraft:enchantments\":{levels:{\"minecraft:sharpness\":%LEVEL%}}}},{}]", 
                        0xFFEF4444, 0xFFB91C1C, 4, true, 4, 20));
                added++;
                if (added >= 4) break;
            }
        }
        if (added == 0) {
            toolboxBlocks.add(new ScratchBlockDefinition("mod_placeholder", "📦 Оружие Мода", 
                    "HandItems:[{id:\"modid:custom_sword\",Count:1b},{}]", 0xFFEF4444, 0xFFB91C1C, 4, false, 1, 1));
        }

        // 5: Мобы
        toolboxBlocks.add(new ScratchBlockDefinition("mob_zombie", "👾 Зомби", "minecraft:zombie", 0xFFEAB308, 0xFFCA8A04, 5, false, 1, 1));
        toolboxBlocks.add(new ScratchBlockDefinition("mob_skel", "🏹 Скелет", "minecraft:skeleton", 0xFFEAB308, 0xFFCA8A04, 5, false, 1, 1));
        toolboxBlocks.add(new ScratchBlockDefinition("mob_wither_skel", "💀 Иссушитель", "minecraft:wither_skeleton", 0xFFEAB308, 0xFFCA8A04, 5, false, 1, 1));

        List<ModRegistryScanner.ScannedMob> modMobs = ModRegistryScanner.getMobs("");
        int addedMobs = 0;
        for (ModRegistryScanner.ScannedMob mm : modMobs) {
            if (!mm.modId().equals("minecraft")) {
                toolboxBlocks.add(new ScratchBlockDefinition("modmob_" + mm.id(), "👾 [" + mm.modId() + "] " + mm.name(), mm.id(), 0xFFEAB308, 0xFFCA8A04, 5, false, 1, 1));
                addedMobs++;
                if (addedMobs >= 3) break;
            }
        }
    }

    public static void updateCache(String json) {
        try {
            ArenaConfigManager.ConfigData data = GSON.fromJson(json, ArenaConfigManager.ConfigData.class);
            if (data != null) clientCache = data;
        } catch (Exception ignored) {}
    }

    public void refreshData() {
        this.init();
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, 0xB0000000);
    }

    @Override
    protected void init() {
        this.clearWidgets();

        int cardLeft = getCardLeft();
        int cardTop = getCardTop();
        int centerX = this.width / 2;

        int topNavY = cardTop + 14;

        // 5 Top Navigation Tabs
        int tabW = 62;
        int startTabsX = cardLeft + 5;

        this.addRenderableWidget(Button.builder(Component.literal(currentTab == 0 ? "§a⚡ Инфо" : "⚡ Инфо"), b -> switchTab(0))
                .bounds(startTabsX, topNavY, tabW, 18).build());
        this.addRenderableWidget(Button.builder(Component.literal(currentTab == 1 ? "§a📍 Точки" : "📍 Точки"), b -> switchTab(1))
                .bounds(startTabsX + tabW + 2, topNavY, tabW, 18).build());
        this.addRenderableWidget(Button.builder(Component.literal(currentTab == 2 ? "§a🧩 Пазлы" : "🧩 Пазлы"), b -> switchTab(2))
                .bounds(startTabsX + (tabW + 2) * 2, topNavY, tabW, 18).build());
        this.addRenderableWidget(Button.builder(Component.literal(currentTab == 3 ? "§e📜 Мобы" : "📜 Мобы"), b -> switchTab(3))
                .bounds(startTabsX + (tabW + 2) * 3, topNavY, tabW, 18).build());
        this.addRenderableWidget(Button.builder(Component.literal(currentTab == 4 ? "§a📦 Лут" : "📦 Лут"), b -> switchTab(4))
                .bounds(startTabsX + (tabW + 2) * 4, topNavY, tabW, 18).build());

        int contentY = topNavY + 22;

        switch (currentTab) {
            case 0 -> initTabGeneral(cardLeft, contentY);
            case 1 -> initTabPoints(cardLeft, contentY);
            case 2 -> initTabScratchEditor(cardLeft, contentY);
            case 3 -> initTabMobRoster(cardLeft, contentY);
            case 4 -> initTabLoot(cardLeft, contentY);
        }

        // Bottom Bar Buttons
        int bottomY = cardTop + CARD_H - 22;
        this.addRenderableWidget(Button.builder(Component.literal("§4⚠ Сброс"), b -> NetworkHandler.sendActionToServer("RESET_ALL", ""))
                .bounds(cardLeft + 6, bottomY, 100, 17).build());

        this.addRenderableWidget(Button.builder(Component.literal("§c✕ Закрыть"), b -> this.onClose())
                .bounds(cardLeft + CARD_W - 96, bottomY, 90, 17).build());
    }

    private void switchTab(int tab) {
        this.currentTab = tab;
        if (tab == 2) {
            puzzleScrollOffset = 0; // Reset scroll when switching to puzzle tab
        }
        this.init();
    }

    private void initTabGeneral(int cardLeft, int y) {
        this.addRenderableWidget(Button.builder(Component.literal("§a▶ Запустить портал"), b -> NetworkHandler.sendActionToServer("START_PORTAL", ""))
                .bounds(cardLeft + 8, y + 4, 148, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("§c⏹ Закрыть портал"), b -> NetworkHandler.sendActionToServer("STOP_PORTAL", ""))
                .bounds(cardLeft + 164, y + 4, 148, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("§d🔮 Телепорт на Арену"), b -> NetworkHandler.sendActionToServer("TP_ARENA", ""))
                .bounds(cardLeft + 8, y + 27, 148, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("§6🏗 Загрузить постройку"), b -> NetworkHandler.sendActionToServer("LOAD_STRUCTURE", ""))
                .bounds(cardLeft + 164, y + 27, 148, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("§b🔄 Перезагрузить JSON"), b -> NetworkHandler.sendActionToServer("RELOAD", ""))
                .bounds(cardLeft + 8, y + 50, 148, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("§e🧹 Перегенерировать"), b -> NetworkHandler.sendActionToServer("REGEN_ARENA", ""))
                .bounds(cardLeft + 164, y + 50, 148, 20).build());
    }

    private void initTabPoints(int cardLeft, int y) {
        this.addRenderableWidget(Button.builder(Component.literal("§a+ Вход (Мир)"), b -> NetworkHandler.sendActionToServer("ADD_ENTRANCE", ""))
                .bounds(cardLeft + 8, y + 2, 148, 18).build());
        this.addRenderableWidget(Button.builder(Component.literal("§d+ Выход (Арена)"), b -> NetworkHandler.sendActionToServer("ADD_EXIT", ""))
                .bounds(cardLeft + 164, y + 2, 148, 18).build());

        this.addRenderableWidget(Button.builder(Component.literal("§e+ Точка сундука"), b -> NetworkHandler.sendActionToServer("ADD_CHEST", ""))
                .bounds(cardLeft + 8, y + 22, 148, 18).build());
        this.addRenderableWidget(Button.builder(Component.literal("§c+ Спавн мобов"), b -> NetworkHandler.sendActionToServer("ADD_MOB_POINT", ""))
                .bounds(cardLeft + 164, y + 22, 148, 18).build());

        // Bounds row
        int boundsY = y + 74;
        this.addRenderableWidget(Button.builder(Component.literal("📐 POS 1"), b -> NetworkHandler.sendActionToServer("SET_POS1", ""))
                .bounds(cardLeft + 8, boundsY, 96, 18).build());
        this.addRenderableWidget(Button.builder(Component.literal("📐 POS 2"), b -> NetworkHandler.sendActionToServer("SET_POS2", ""))
                .bounds(cardLeft + 108, boundsY, 96, 18).build());

        boolean barrierOn = clientCache.arenaBounds != null && clientCache.arenaBounds.enableBounds;
        this.addRenderableWidget(Button.builder(Component.literal(barrierOn ? "§aБарьер ВКЛ" : "§cБарьер ВЫКЛ"), b -> NetworkHandler.sendActionToServer("TOGGLE_BOUNDS", ""))
                .bounds(cardLeft + 208, boundsY, 104, 18).build());

        // Clear Points
        int clearY = boundsY + 42;
        this.addRenderableWidget(Button.builder(Component.literal("Сброс входов"), b -> NetworkHandler.sendActionToServer("CLEAR_POINTS", "entrance"))
                .bounds(cardLeft + 8, clearY, 72, 16).build());
        this.addRenderableWidget(Button.builder(Component.literal("Сброс выходов"), b -> NetworkHandler.sendActionToServer("CLEAR_POINTS", "exit"))
                .bounds(cardLeft + 84, clearY, 72, 16).build());
        this.addRenderableWidget(Button.builder(Component.literal("Сброс сундуков"), b -> NetworkHandler.sendActionToServer("CLEAR_POINTS", "chest"))
                .bounds(cardLeft + 160, clearY, 72, 16).build());
        this.addRenderableWidget(Button.builder(Component.literal("Сброс мобов"), b -> NetworkHandler.sendActionToServer("CLEAR_POINTS", "mob"))
                .bounds(cardLeft + 236, clearY, 76, 16).build());
    }

    private void initTabScratchEditor(int cardLeft, int y) {
        String curMobName = mobNameField != null ? mobNameField.getValue() : "§4Гладиатор";
        String curMobId = mobIdField != null ? mobIdField.getValue() : "minecraft:zombie";
        String curHp = mobHpField != null ? mobHpField.getValue() : "50";

        // Carousel Mob Picker: [ ◀ ] [ 👾 entity_name ] [ ▶ ] + Name + HP
        this.addRenderableWidget(Button.builder(Component.literal("◀"), b -> {
            if (!scannedMobsList.isEmpty()) {
                mobCarouselIndex = (mobCarouselIndex - 1 + scannedMobsList.size()) % scannedMobsList.size();
                var m = scannedMobsList.get(mobCarouselIndex);
                mobIdField.setValue(m.id());
                mobNameField.setValue("§c" + m.name());
                this.init();
            }
        }).bounds(cardLeft + 8, y + 2, 18, 16).build());

        String shortMobId = curMobId.contains(":") ? curMobId.substring(curMobId.indexOf(':') + 1) : curMobId;
        this.addRenderableWidget(Button.builder(Component.literal(this.font.plainSubstrByWidth("👾 " + shortMobId, 85)), b -> {
            if (!scannedMobsList.isEmpty()) {
                mobCarouselIndex = (mobCarouselIndex + 1) % scannedMobsList.size();
                var m = scannedMobsList.get(mobCarouselIndex);
                mobIdField.setValue(m.id());
                mobNameField.setValue("§c" + m.name());
                this.init();
            }
        }).bounds(cardLeft + 28, y + 2, 92, 16).build());

        this.addRenderableWidget(Button.builder(Component.literal("▶"), b -> {
            if (!scannedMobsList.isEmpty()) {
                mobCarouselIndex = (mobCarouselIndex + 1) % scannedMobsList.size();
                var m = scannedMobsList.get(mobCarouselIndex);
                mobIdField.setValue(m.id());
                mobNameField.setValue("§c" + m.name());
                this.init();
            }
        }).bounds(cardLeft + 122, y + 2, 18, 16).build());

        mobNameField = new EditBox(this.font, cardLeft + 144, y + 2, 85, 16, Component.literal("Имя"));
        mobNameField.setValue(curMobName);
        this.addRenderableWidget(mobNameField);

        mobHpField = new EditBox(this.font, cardLeft + 233, y + 2, 28, 16, Component.literal("HP"));
        mobHpField.setValue(curHp);
        this.addRenderableWidget(mobHpField);

        mobIdField = new EditBox(this.font, cardLeft + 265, y + 2, 47, 16, Component.literal("ID"));
        mobIdField.setValue(curMobId);
        mobIdField.setEditable(false);
        this.addRenderableWidget(mobIdField);

        // Left Toolbox
        int leftColX = cardLeft + 8;
        int paletteY = y + 20;

        int catBtnW = 24;
        this.addRenderableWidget(Button.builder(Component.literal(activeToolboxCategory == 0 ? "§6⚔" : "⚔"), b -> { activeToolboxCategory = 0; this.init(); })
                .bounds(leftColX, paletteY, catBtnW, 15).build());
        this.addRenderableWidget(Button.builder(Component.literal(activeToolboxCategory == 1 ? "§2🛡" : "🛡"), b -> { activeToolboxCategory = 1; this.init(); })
                .bounds(leftColX + 25, paletteY, catBtnW, 15).build());
        this.addRenderableWidget(Button.builder(Component.literal(activeToolboxCategory == 2 ? "§5🧪" : "🧪"), b -> { activeToolboxCategory = 2; this.init(); })
                .bounds(leftColX + 50, paletteY, catBtnW, 15).build());
        this.addRenderableWidget(Button.builder(Component.literal(activeToolboxCategory == 3 ? "§1⚙" : "⚙"), b -> { activeToolboxCategory = 3; this.init(); })
                .bounds(leftColX + 75, paletteY, catBtnW, 15).build());
        this.addRenderableWidget(Button.builder(Component.literal(activeToolboxCategory == 4 ? "§4📦" : "📦"), b -> { activeToolboxCategory = 4; this.init(); })
                .bounds(leftColX + 100, paletteY, catBtnW, 15).build());
        this.addRenderableWidget(Button.builder(Component.literal(activeToolboxCategory == 5 ? "§e👾" : "👾"), b -> { activeToolboxCategory = 5; this.init(); })
                .bounds(leftColX + 125, paletteY, catBtnW, 15).build());

        int itemY = paletteY + 18;
        for (ScratchBlockDefinition def : toolboxBlocks) {
            if (def.category == activeToolboxCategory) {
                final ScratchBlockDefinition finalDef = def;
                this.addRenderableWidget(Button.builder(Component.literal("➕ " + def.label), b -> {
                    if (def.category == 5) {
                        mobIdField.setValue(def.nbtTemplate);
                        mobNameField.setValue("§6" + def.label.replace("👾 ", ""));
                    } else {
                        canvasPuzzleStack.add(def.copy());
                        // Scroll to the newly added block
                        puzzleScrollOffset = Math.max(0, canvasPuzzleStack.size() - 1);
                    }
                    this.init();
                }).bounds(leftColX, itemY, 149, 18).build());
                itemY += 20;
            }
        }

        // Right Canvas Controls
        int rightColX = cardLeft + 165;
        int actionY = getCardTop() + CARD_H - 42;

        this.addRenderableWidget(Button.builder(Component.literal("📜 Список (" + clientCache.mobs.size() + ")"), b -> switchTab(3))
                .bounds(rightColX, actionY, 68, 17).build());

        this.addRenderableWidget(Button.builder(Component.literal("§a✔ СОХРАНИТЬ"), b -> {
            // Merge all puzzle block NBT into a single valid compound
            // Each block's getCompiledNbt() returns a fragment like "Key:Value" or "Key:[...]"
            // We need to merge them into one compound: {fragment1, fragment2, ...}
            List<String> fragments = new ArrayList<>();
            for (ScratchBlockDefinition def : canvasPuzzleStack) {
                String compiled = def.getCompiledNbt();
                if (compiled == null || compiled.trim().isEmpty()) continue;
                
                // Clean up the fragment - remove outer braces if present
                String cleaned = compiled.trim();
                if (cleaned.startsWith("{")) cleaned = cleaned.substring(1);
                if (cleaned.endsWith("}")) cleaned = cleaned.substring(0, cleaned.length() - 1);
                
                // Skip empty fragments after cleaning
                if (!cleaned.trim().isEmpty()) {
                    fragments.add(cleaned.trim());
                }
            }
            String nbt;
            if (fragments.isEmpty()) {
                nbt = "{}";
            } else {
                nbt = "{" + String.join(",", fragments) + "}";
            }
            
            // Validate fields before sending
            String entityId = mobIdField.getValue().trim();
            String mobName = mobNameField.getValue().trim();
            String hpStr = mobHpField.getValue().trim();
            
            if (entityId.isEmpty()) {
                Minecraft.getInstance().player.displayClientMessage(Component.literal("§c[GUI] ID моба не может быть пустым!"), true);
                return;
            }
            
            try {
                double hp = Double.parseDouble(hpStr);
                if (hp < 1.0 || hp > 10000.0) {
                    Minecraft.getInstance().player.displayClientMessage(Component.literal("§c[GUI] HP должно быть от 1 до 10000!"), true);
                    return;
                }
            } catch (NumberFormatException e) {
                Minecraft.getInstance().player.displayClientMessage(Component.literal("§c[GUI] HP должно быть числом!"), true);
                return;
            }
            
            String data = entityId + ";" + mobName + ";" + hpStr + ";minecraft:air;" + nbt;
            NetworkHandler.sendActionToServer("ADD_MOB", data);
            // Clear canvas after successful save
            canvasPuzzleStack.clear();
            puzzleScrollOffset = 0;
            this.init();
        }).bounds(rightColX + 72, actionY, 74, 17).build());
    }

    private static final int ROSTER_PER_PAGE = 4;
    private static final int ROSTER_ROW_HEIGHT = 34;

    private void initTabMobRoster(int cardLeft, int y) {
        List<ArenaConfigManager.MobEntry> mobs = clientCache.mobs;
        int total = mobs.size();
        int maxPages = Math.max(1, (int) Math.ceil((double) total / ROSTER_PER_PAGE));
        if (mobListPage >= maxPages) mobListPage = Math.max(0, maxPages - 1);

        this.addRenderableWidget(Button.builder(Component.literal("§4🗑 Очистить всех"), b -> {
            NetworkHandler.sendActionToServer("CLEAR_POINTS", "mob_list");
        }).bounds(cardLeft + 8, y + 2, 110, 18).build());

        if (maxPages > 1) {
            this.addRenderableWidget(Button.builder(Component.literal("◀ Пред."), b -> {
                if (mobListPage > 0) { mobListPage--; this.init(); }
            }).bounds(cardLeft + 198, y + 2, 52, 18).build());

            this.addRenderableWidget(Button.builder(Component.literal("След. ▶"), b -> {
                if (mobListPage < maxPages - 1) { mobListPage++; this.init(); }
            }).bounds(cardLeft + 254, y + 2, 52, 18).build());
        }

        int startIndex = mobListPage * ROSTER_PER_PAGE;
        int endIndex = Math.min(startIndex + ROSTER_PER_PAGE, total);
        int rosterStartY = getCardTop() + 60;

        for (int i = startIndex; i < endIndex; i++) {
            final int mobIndex = i;
            int cardY = rosterStartY + ((i - startIndex) * ROSTER_ROW_HEIGHT);
            
            this.addRenderableWidget(Button.builder(Component.literal("§c✕ Удалить"), b -> {
                NetworkHandler.sendActionToServer("REMOVE_MOB", String.valueOf(mobIndex));
            }).bounds(cardLeft + CARD_W - 74, cardY + 7, 66, 18).build());
        }
    }

    private void initTabLoot(int cardLeft, int y) {
        lootIdField = new EditBox(this.font, cardLeft + 8, y + 10, 110, 17, Component.literal("ID"));
        lootIdField.setValue("minecraft:diamond");
        this.addRenderableWidget(lootIdField);

        lootMinField = new EditBox(this.font, cardLeft + 122, y + 10, 30, 17, Component.literal("Мин"));
        lootMinField.setValue("1");
        this.addRenderableWidget(lootMinField);

        lootMaxField = new EditBox(this.font, cardLeft + 156, y + 10, 30, 17, Component.literal("Макс"));
        lootMaxField.setValue("3");
        this.addRenderableWidget(lootMaxField);

        lootChanceField = new EditBox(this.font, cardLeft + 190, y + 10, 40, 17, Component.literal("Шанс"));
        lootChanceField.setValue("0.6");
        this.addRenderableWidget(lootChanceField);

        this.addRenderableWidget(Button.builder(Component.literal("§a+ Добавить"), b -> {
            String data = lootIdField.getValue() + ";" + lootMinField.getValue() + ";" + lootMaxField.getValue() + ";" + lootChanceField.getValue();
            NetworkHandler.sendActionToServer("ADD_LOOT", data);
        }).bounds(cardLeft + 235, y + 9, 75, 18).build());
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int cardLeft = getCardLeft();
        int cardTop = getCardTop();

        // Scratch Block Canvas clicks (Steppers and [X] delete)
        if (currentTab == 2) {
            int rightColX = cardLeft + 165;
            int blockX = rightColX + 4;
            int blockW = 138;
            int blockH = 18;
            // Hat block: y=contentStartY+12, h=15. Stack starts at hatY+15 = contentStartY+27
            int contentStartY = cardTop + 35;
            int startStackY = contentStartY + 27;

            // Check clicks in REVERSE order so top-most (last drawn) blocks are clicked first
            for (int i = canvasPuzzleStack.size() - 1; i >= 0; i--) {
                // Skip blocks that are scrolled out of view
                int visibleIndex = i - puzzleScrollOffset;
                if (visibleIndex < 0) continue;
                if (visibleIndex >= 5) continue; // Only 5 blocks visible at a time
                
                ScratchBlockDefinition def = canvasPuzzleStack.get(i);
                int by = startStackY + (visibleIndex * 19);

                // Check if mouse is within the block's vertical range
                if (mouseY >= by && mouseY <= by + blockH) {
                    // Check if mouse is within the block's horizontal range
                    if (mouseX < blockX || mouseX > blockX + blockW) continue;

                    // [X] close button - rightmost 12 pixels of the block
                    // Rendered at: x + w - 12 to x + w - 2 (width = 10)
                    int closeLeft = blockX + blockW - 12;
                    int closeRight = blockX + blockW;
                    if (mouseX >= closeLeft && mouseX <= closeRight) {
                        canvasPuzzleStack.remove(i);
                        puzzleScrollOffset = Math.max(0, puzzleScrollOffset - 1);
                        this.init();
                        return true;
                    }

                    if (def.hasLevel) {
                        // Stepper buttons match drawScratchConnectedBlock exactly:
                        // [-] button: fill(stepBaseX, y+2, stepBaseX+11, y+h-2) → pixels [stepBaseX, stepBaseX+11)
                        // [+] button: fill(stepBaseX+28, y+2, stepBaseX+39, y+h-2) → pixels [stepBaseX+28, stepBaseX+39)
                        int stepBaseX = blockX + blockW - 48;
                        
                        // [-] button
                        if (mouseX >= stepBaseX && mouseX < stepBaseX + 11) {
                            if (def.currentLevel > 1) {
                                def.currentLevel--;
                                this.init();
                            }
                            return true;
                        }

                        // [+] button
                        if (mouseX >= stepBaseX + 28 && mouseX < stepBaseX + 39) {
                            if (def.currentLevel < def.maxLevel) {
                                def.currentLevel++;
                                this.init();
                            }
                            return true;
                        }
                    }
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta, double delta2) {
        if (currentTab == 2) {
            int cardLeft = getCardLeft();
            int cardTop = getCardTop();
            int rightColX = cardLeft + 165;
            int contentStartY = cardTop + 35;
            int boxH = CARD_H - 85;
            
            // Check if scroll is over the canvas area
            if (mouseX >= rightColX && mouseX <= rightColX + 146 &&
                mouseY >= contentStartY && mouseY <= contentStartY + boxH + 16) {
                int maxScroll = Math.max(0, canvasPuzzleStack.size() - 5);
                if (delta > 0 && puzzleScrollOffset < maxScroll) {
                    puzzleScrollOffset++;
                } else if (delta < 0 && puzzleScrollOffset > 0) {
                    puzzleScrollOffset--;
                }
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, delta, delta2);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int centerX = this.width / 2;
        int cardLeft = getCardLeft();
        int cardTop = getCardTop();

        // Simple static card background
        graphics.fill(cardLeft, cardTop, cardLeft + CARD_W, cardTop + CARD_H, 0xF80C0D15);
        graphics.renderOutline(cardLeft, cardTop, CARD_W, CARD_H, 0xFF8888AA);

        // Static tab indicator
        int tabW = 62;
        int startTabsX = cardLeft + 5;
        int indicatorX = startTabsX + currentTab * (tabW + 2);
        int indicatorY = cardTop + 32;
        graphics.fill(indicatorX, indicatorY, indicatorX + tabW, indicatorY + 2, 0xFF8888AA);

        // Static title
        graphics.drawCenteredString(this.font, "§lНАСТРОЙКИ ВРЕМЕННОГО ПОРТАЛА", centerX, cardTop + 4, 0xFFAAAAFF);

        int contentStartY = cardTop + 35;

        switch (currentTab) {
            case 0 -> {
                int infoY = contentStartY + 76;
                graphics.fill(cardLeft + 8, infoY, cardLeft + CARD_W - 8, infoY + 52, 0x90000000);
                graphics.renderOutline(cardLeft + 8, infoY, CARD_W - 16, 52, 0xFF444455);

                boolean portalActive = PortalManager.getCurrentEntrancePos() != null;
                int dotX = cardLeft + 22;
                int dotY = infoY + 10;
                graphics.fill(dotX - 1, dotY - 1, dotX + 1, dotY + 1, portalActive ? 0xFF00FF00 : 0xFFFF0000);
                graphics.drawString(this.font, "§fСтатус портала: " + (portalActive ? "§a АКТИВЕН" : "§c НЕ АКТИВЕН"), dotX + 6, infoY + 6, 0xFFFFFF);
                
                int barX = cardLeft + 16;
                int barY = infoY + 18;
                int barW = CARD_W - 40;
                int barH = 8;
                graphics.fill(barX, barY, barX + barW, barY + barH, 0x40000000);
                if (portalActive) {
                    graphics.fill(barX, barY, barX + barW / 2, barY + barH, 0xFF22D3EE);
                }
                graphics.renderOutline(barX, barY, barW, barH, 0xFF444455);
                
                graphics.drawString(this.font, "§fОкно события: §eс " + clientCache.scheduleStartHour + ":00 до " + clientCache.scheduleEndHour + ":00", cardLeft + 16, infoY + 32, 0xFFFFFF);
                graphics.drawString(this.font, "§fФайл структуры: §b" + clientCache.structureFileName, cardLeft + 16, infoY + 46, 0xFFFFFF);
            }
            case 1 -> {
                int textY = contentStartY + 44;
                graphics.drawString(this.font, "§eУстановленные точки:", cardLeft + 8, textY, 0xFFFFAA);
                graphics.drawString(this.font, "§aВходы: §f" + clientCache.overworldEntrancePoints.size() + "  |  §dВыходы: §f" + clientCache.arenaExitPoints.size() + "  |  §6Сундуки: §f" + clientCache.chestSpawnPoints.size() + "  |  §cМобы: §f" + clientCache.mobSpawnPoints.size(), cardLeft + 8, textY + 12, 0xFFFFFF);

                var b = clientCache.arenaBounds;
                if (b != null) {
                    int boxY = contentStartY + 96;
                    graphics.fill(cardLeft + 8, boxY, cardLeft + CARD_W - 8, boxY + 18, 0x90000000);
                    graphics.renderOutline(cardLeft + 8, boxY, CARD_W - 16, 18, 0xFF444455);
                    graphics.drawString(this.font, "§fГраницы: §a[" + b.minX + "," + b.minY + "," + b.minZ + "] §7➜ §a[" + b.maxX + "," + b.maxY + "," + b.maxZ + "]", cardLeft + 16, boxY + 5, 0xFFFFFF);
                }
            }
            case 2 -> {
                int leftColX = cardLeft + 8;
                int rightColX = cardLeft + 165;
                int boxH = CARD_H - 85;
                graphics.fill(leftColX - 2, contentStartY + 18, leftColX + 151, contentStartY + 18 + boxH, 0x70000000);
                graphics.renderOutline(leftColX - 2, contentStartY + 18, 153, boxH, 0xFF3B4252);
                int canvasW = 146;
                graphics.fill(rightColX, contentStartY + 2, rightColX + canvasW, contentStartY + 2 + boxH + 16, 0x85111827);
                graphics.renderOutline(rightColX, contentStartY + 2, canvasW, boxH + 16, 0xFF4C566A);

                int hatY = contentStartY + 12;
                drawScratchHatBlock(graphics, rightColX + 4, hatY, 138, 15, 0xFFF59E0B);
                int stackY = hatY + 15;
                
                int totalBlocks = canvasPuzzleStack.size();
                int maxVisible = 5;
                int maxScroll = Math.max(0, totalBlocks - maxVisible);
                puzzleScrollOffset = Math.max(0, Math.min(puzzleScrollOffset, maxScroll));
                
                if (totalBlocks == 0) {
                    graphics.drawString(this.font, "§8(Жмите ➕ слева)", rightColX + 12, stackY + 10, 0x888888);
                } else {
                    for (int i = 0; i < maxVisible; i++) {
                        int actualIndex = puzzleScrollOffset + i;
                        if (actualIndex >= totalBlocks) break;
                        
                        ScratchBlockDefinition def = canvasPuzzleStack.get(actualIndex);
                        drawScratchConnectedBlock(graphics, rightColX + 4, stackY, 138, 18, def);
                        stackY += 19;
                    }
                    // Show scroll indicator if there are more blocks
                    if (totalBlocks > maxVisible) {
                        String scrollInfo = "§7↑ " + (puzzleScrollOffset + 1) + "-" + Math.min(puzzleScrollOffset + maxVisible, totalBlocks) + " из " + totalBlocks + " ↓";
                        graphics.drawCenteredString(this.font, scrollInfo, rightColX + 69, stackY + 2, 0xAAAAAA);
                    }
                }
            }
            case 3 -> {
                List<ArenaConfigManager.MobEntry> mobs = clientCache.mobs;
                int total = mobs.size();
                int maxPages = Math.max(1, (int) Math.ceil((double) total / ROSTER_PER_PAGE));
                if (maxPages > 1) {
                    graphics.drawString(this.font, "§7Стр. " + (mobListPage + 1) + "/" + maxPages, cardLeft + 130, contentStartY + 8, 0xDDDDDD);
                }
                int startIndex = mobListPage * ROSTER_PER_PAGE;
                int endIndex = Math.min(startIndex + ROSTER_PER_PAGE, total);
                int rosterStartY = cardTop + 60;

                if (mobs.isEmpty()) {
                    graphics.drawCenteredString(this.font, "§7Список мобов пуст.", centerX, cardTop + 80, 0xAAAAAA);
                    graphics.drawCenteredString(this.font, "§8Перейдите в '🧩 Пазлы' для создания!", centerX, cardTop + 95, 0x888888);
                } else {
                    for (int i = startIndex; i < endIndex; i++) {
                        var m = mobs.get(i);
                        int cardY = rosterStartY + ((i - startIndex) * ROSTER_ROW_HEIGHT);
                        graphics.fill(cardLeft + 8, cardY, cardLeft + CARD_W - 8, cardY + 31, 0x95141424);
                        graphics.renderOutline(cardLeft + 8, cardY, CARD_W - 16, 31, 0xFF4A4A6A);
                        int hpBarX = cardLeft + 14;
                        int hpBarY = cardY + 23;
                        int hpBarW = 80;
                        graphics.fill(hpBarX, hpBarY, hpBarX + hpBarW, hpBarY + 4, 0x30000000);
                        float hpRatio = Math.min(1.0f, (float) m.maxHealth / 100.0f);
                        int hpFillW = (int) (hpBarW * hpRatio);
                        graphics.fill(hpBarX, hpBarY, hpBarX + hpFillW, hpBarY + 4, 0xFF22C55E);
                        String nbtBadge = (m.customNbt != null && !m.customNbt.isEmpty()) ? " §d[NBT]" : "";
                        String title = "§6#" + (i + 1) + " §f" + m.customName + nbtBadge;
                        graphics.drawString(this.font, this.font.plainSubstrByWidth(title, 210), cardLeft + 14, cardY + 4, 0xFFFFFF);
                        String stats = "§7" + m.entityId + " §7| §a" + (int)m.maxHealth + " HP";
                        graphics.drawString(this.font, this.font.plainSubstrByWidth(stats, 210), cardLeft + 14, cardY + 17, 0xAAAAAA);
                    }
                }
            }
            case 4 -> {
                graphics.drawString(this.font, "§7ID предмета              Мин  Макс  Шанс", cardLeft + 8, contentStartY, 0xBBBBBB);
                graphics.drawString(this.font, "§eПредметы в сундуках (" + clientCache.chestLoot.size() + " шт.):", cardLeft + 8, contentStartY + 32, 0xFFFFAA);
                graphics.fill(cardLeft + 8, contentStartY + 44, cardLeft + CARD_W - 8, contentStartY + 130, 0x90000000);
                graphics.renderOutline(cardLeft + 8, contentStartY + 44, CARD_W - 16, 86, 0xFF444455);
                int yOff = contentStartY + 49;
                for (int i = 0; i < Math.min(5, clientCache.chestLoot.size()); i++) {
                    var l = clientCache.chestLoot.get(i);
                    graphics.drawString(this.font, "§b• §f" + l.itemId + " §7(§e" + l.minCount + "-" + l.maxCount + " шт§7, §a" + (int)(l.chance * 100) + "%§7)", cardLeft + 16, yOff, 0xFFFFFF);
                    yOff += 14;
                }
            }
        }

        // Draw cat mascot in the bottom-right corner of the card
        drawCatMascot(graphics, cardLeft + CARD_W - 36, cardTop + CARD_H - 32, partialTick);

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    /**
     * Draws a lightweight animated cat mascot.
     * Animations: blink (eyes close briefly) and bounce (subtle vertical jump).
     * Uses only text rendering — no heavy graphics operations.
     */
    private void drawCatMascot(GuiGraphics graphics, int x, int y, float partialTick) {
        catAnimTick++;

        // Bounce: every 120 ticks (6 seconds), jump for 20 ticks
        long bounceCycle = catAnimTick % 120;
        int bounceOffset = 0;
        if (bounceCycle < 20) {
            // Parabolic jump: up and down over 20 ticks
            float t = (float) bounceCycle / 20.0f;
            bounceOffset = -(int) Math.round(4.0f * t * (1.0f - t));
        }

        // Blink: every ~90 ticks, blink for 6 ticks
        long blinkCycle = catAnimTick % 90;
        boolean isBlinking = blinkCycle < 6;

        int catY = y + bounceOffset;

        // Cat face using text characters
        //    ╰⊙ ⊙╯  (normal)
        //    ╰_ _╯  (blinking)
        //    ╰◕ ◕╯  (wide eyes when not bouncing)
        String catFace;
        if (isBlinking) {
            catFace = "§f╰§7_ §7_§f╯";
        } else if (bounceOffset < -2) {
            // Wide eyes during peak of jump
            catFace = "§f╰§e◕ §e◕§f╯";
        } else {
            catFace = "§f╰§b◉ §b◉§f╯";
        }

        // Ears above the face
        String ears = "§f(˚˚)";
        // Body below
        String body = "§f/|\\";

        // Draw centered at x
        int earsW = this.font.width(ears);
        graphics.drawString(this.font, ears, x - earsW / 2, catY - 8, 0xFFFFFF, false);

        int faceW = this.font.width(catFace);
        graphics.drawString(this.font, catFace, x - faceW / 2, catY, 0xFFFFFF, false);

        int bodyW = this.font.width(body);
        graphics.drawString(this.font, body, x - bodyW / 2, catY + 10, 0xFFFFFF, false);

        // Tail wagging animation
        long tailCycle = catAnimTick % 60;
        String tail;
        if (tailCycle < 20) {
            tail = "  §f)";
        } else if (tailCycle < 40) {
            tail = "  §f)~";
        } else {
            tail = "  §f)";
        }
        int tailX = x + faceW / 2 + 2;
        graphics.drawString(this.font, tail, tailX, catY + 2, 0xFFFFFF, false);
    }

    private void drawScratchHatBlock(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x, y + 3, x + w, y + h, color);
        g.fill(x + 8, y, x + w - 8, y + 3, color);
        g.fill(x + 12, y + h, x + 28, y + h + 2, color);
        g.fill(x, y + 3, x + w, y + 4, 0x40FFFFFF);
        g.drawString(this.font, "🚩 Моб спавнится:", x + 5, y + 4, 0xFFFFFF, false);
    }

    private void drawScratchConnectedBlock(GuiGraphics g, int x, int y, int w, int h, ScratchBlockDefinition def) {
        int color = def.color;
        int darkColor = def.darkColor;

        g.fill(x, y, x + w, y + h, color);
        g.fill(x + 12, y + h, x + 28, y + h + 2, color);
        g.fill(x, y + h - 2, x + w, y + h, darkColor);
        g.fill(x, y, x + w, y + 1, 0x50FFFFFF);

        int reservedRight = def.hasLevel ? 52 : 16;
        String display = this.font.plainSubstrByWidth(def.label, w - reservedRight);
        g.drawString(this.font, display, x + 5, y + 4, 0xFFFFFF, false);

        // Stepper buttons with exact pixel matching:
        if (def.hasLevel) {
            int stepBaseX = x + w - 48;
            g.fill(stepBaseX, y + 2, stepBaseX + 11, y + h - 2, 0x50000000);
            g.drawString(this.font, "-", stepBaseX + 3, y + 4, 0xFFFFFF, false);

            g.fill(stepBaseX + 12, y + 2, stepBaseX + 27, y + h - 2, 0x70000000);
            g.drawString(this.font, String.valueOf(def.currentLevel), stepBaseX + 16, y + 4, 0xFFFFAA, false);

            g.fill(stepBaseX + 28, y + 2, stepBaseX + 39, y + h - 2, 0x50000000);
            g.drawString(this.font, "+", stepBaseX + 31, y + 4, 0xFFFFFF, false);
        }

        // [✕]
        g.fill(x + w - 12, y + 2, x + w - 2, y + h - 2, 0x60000000);
        g.drawString(this.font, "§c✕", x + w - 10, y + 4, 0xFFFFFF, false);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
