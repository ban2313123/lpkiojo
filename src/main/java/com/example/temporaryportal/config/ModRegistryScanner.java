package com.example.temporaryportal.config;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.BowItem;

import java.util.*;

public class ModRegistryScanner {

    public record ScannedMob(String id, String name, String modId) {}
    public record ScannedItem(String id, String name, String modId, String type) {}

    private static final List<ScannedMob> scannedMobs = new ArrayList<>();
    private static final List<ScannedItem> scannedWeapons = new ArrayList<>();
    private static final List<ScannedItem> scannedArmors = new ArrayList<>();
    private static final Set<String> installedModIds = new TreeSet<>();
    private static boolean initialized = false;

    public static synchronized void scan() {
        if (initialized) return;

        scannedMobs.clear();
        scannedWeapons.clear();
        scannedArmors.clear();
        installedModIds.clear();

        // 1. Scan Entity Types (all mobs from Minecraft + all installed mods)
        // In 1.21.1 entrySet returns Entry<ResourceKey<EntityType<?>>, EntityType<?>>
        for (Map.Entry<ResourceKey<EntityType<?>>, EntityType<?>> entry : BuiltInRegistries.ENTITY_TYPE.entrySet()) {
            ResourceLocation rl = entry.getKey().location();
            EntityType<?> type = entry.getValue();
            String mod = rl.getNamespace();
            installedModIds.add(mod);

            MobCategory cat = type.getCategory();
            if (cat == MobCategory.MONSTER || cat == MobCategory.CREATURE || cat == MobCategory.UNDERGROUND_WATER_CREATURE || cat == MobCategory.AXOLOTLS) {
                String displayName = rl.getPath().replace('_', ' ');
                scannedMobs.add(new ScannedMob(rl.toString(), displayName, mod));
            }
        }

        // 2. Scan Items (Weapons and Armors from Minecraft + all installed mods)
        for (Map.Entry<ResourceKey<Item>, Item> entry : BuiltInRegistries.ITEM.entrySet()) {
            ResourceLocation rl = entry.getKey().location();
            Item item = entry.getValue();
            String mod = rl.getNamespace();
            String path = rl.getPath().toLowerCase();

            boolean isWeapon = item instanceof SwordItem || item instanceof BowItem ||
                               path.contains("sword") || path.contains("blade") || path.contains("bow") ||
                               path.contains("axe") || path.contains("spear") || path.contains("dagger") ||
                               path.contains("scythe") || path.contains("hammer") || path.contains("mace");

            boolean isArmor = item instanceof ArmorItem ||
                              path.contains("helmet") || path.contains("chestplate") ||
                              path.contains("leggings") || path.contains("boots") ||
                              path.contains("armor") || path.contains("crown");

            if (isWeapon) {
                scannedWeapons.add(new ScannedItem(rl.toString(), rl.getPath().replace('_', ' '), mod, "Оружие"));
            } else if (isArmor) {
                scannedArmors.add(new ScannedItem(rl.toString(), rl.getPath().replace('_', ' '), mod, "Броня"));
            }
        }

        initialized = true;
    }

    private static <T extends Scannable> List<T> filterList(List<T> list, String filter) {
        if (filter == null || filter.trim().isEmpty()) return list;
        String f = filter.toLowerCase().trim();
        List<T> res = new ArrayList<>();
        for (T item : list) {
            if (item.getId().toLowerCase().contains(f) || item.getName().toLowerCase().contains(f) || item.getModId().toLowerCase().contains(f)) {
                res.add(item);
            }
        }
        return res;
    }

    private interface Scannable {
        String getId();
        String getName();
        String getModId();
    }

    public static List<ScannedMob> getMobs(String filter) {
        scan();
        return filterList(scannedMobs.stream().map(m -> (Scannable) new ScannedMobWrapper(m)).toList(), filter).stream()
                .map(w -> ((ScannedMobWrapper) w).getMob())
                .toList();
    }

    public static List<ScannedItem> getWeapons(String filter) {
        scan();
        return filterList(scannedWeapons.stream().map(i -> (Scannable) new ScannedItemWrapper(i)).toList(), filter).stream()
                .map(w -> ((ScannedItemWrapper) w).getItem())
                .toList();
    }

    public static List<ScannedItem> getArmors(String filter) {
        scan();
        return filterList(scannedArmors.stream().map(i -> (Scannable) new ScannedItemWrapper(i)).toList(), filter).stream()
                .map(w -> ((ScannedItemWrapper) w).getItem())
                .toList();
    }

    private static class ScannedMobWrapper implements Scannable {
        private final ScannedMob mob;
        ScannedMobWrapper(ScannedMob m) { this.mob = m; }
        ScannedMob getMob() { return mob; }
        public String getId() { return mob.id(); }
        public String getName() { return mob.name(); }
        public String getModId() { return mob.modId(); }
    }

    private static class ScannedItemWrapper implements Scannable {
        private final ScannedItem item;
        ScannedItemWrapper(ScannedItem i) { this.item = i; }
        ScannedItem getItem() { return item; }
        public String getId() { return item.id(); }
        public String getName() { return item.name(); }
        public String getModId() { return item.modId(); }
    }

    public static Set<String> getInstalledModIds() {
        scan();
        return installedModIds;
    }
}
