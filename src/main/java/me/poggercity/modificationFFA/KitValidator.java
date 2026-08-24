package me.poggercity.modificationFFA;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

final class KitValidator {

    private KitValidator() {
    }

    static SaveResult validateForSave(ItemStack[] mainKit, ItemStack[] candidateKit) {
        List<ItemGroup> groups = buildMainGroups(mainKit);

        for (ItemStack item : candidateKit) {
            if (isEmpty(item)) {
                continue;
            }

            ItemStack normalized = normalize(item);
            ItemGroup group = groups.stream()
                    .filter(existing -> existing.prototype.isSimilar(normalized))
                    .findFirst()
                    .orElse(null);

            if (group == null) {
                boolean materialExists = groups.stream()
                        .anyMatch(existing -> existing.prototype.getType() == item.getType());
                return materialExists
                        ? SaveResult.failure(Failure.DIFFERENT_ITEM, item.getType())
                        : SaveResult.failure(Failure.FOREIGN_MATERIAL, item.getType());
            }

            group.candidateAmount += item.getAmount();
            int damage = damage(item);
            for (int count = 0; count < item.getAmount(); count++) {
                group.candidateDamage.add(damage);
            }
        }

        for (ItemGroup group : groups) {
            if (group.candidateAmount > group.mainAmount) {
                return SaveResult.failure(Failure.WRONG_AMOUNT, group.prototype.getType());
            }

            group.mainDamage.sort(Comparator.naturalOrder());
            group.candidateDamage.sort(Comparator.naturalOrder());
            for (int index = 0; index < group.candidateDamage.size(); index++) {
                if (group.candidateDamage.get(index) < group.mainDamage.get(index)) {
                    return SaveResult.failure(Failure.MORE_DURABILITY, group.prototype.getType());
                }
            }
        }

        return SaveResult.success();
    }

    static boolean hasPurchasedMaterial(ItemStack[] inventory, ItemStack[] mainKit) {
        Set<Material> allowedMaterials = EnumSet.noneOf(Material.class);
        for (ItemStack item : mainKit) {
            if (!isEmpty(item)) {
                allowedMaterials.add(item.getType());
            }
        }

        for (ItemStack item : inventory) {
            if (!isEmpty(item) && !allowedMaterials.contains(item.getType())) {
                return true;
            }
        }
        return false;
    }

    static boolean hasAnyItems(ItemStack[] inventory) {
        for (ItemStack item : inventory) {
            if (!isEmpty(item)) {
                return true;
            }
        }
        return false;
    }

    private static List<ItemGroup> buildMainGroups(ItemStack[] mainKit) {
        List<ItemGroup> groups = new ArrayList<>();
        for (ItemStack item : mainKit) {
            if (isEmpty(item)) {
                continue;
            }

            ItemStack normalized = normalize(item);
            ItemGroup group = groups.stream()
                    .filter(existing -> existing.prototype.isSimilar(normalized))
                    .findFirst()
                    .orElseGet(() -> {
                        ItemGroup created = new ItemGroup(normalized);
                        groups.add(created);
                        return created;
                    });

            group.mainAmount += item.getAmount();
            int damage = damage(item);
            for (int count = 0; count < item.getAmount(); count++) {
                group.mainDamage.add(damage);
            }
        }
        return groups;
    }

    private static ItemStack normalize(ItemStack item) {
        ItemStack normalized = item.clone();
        normalized.setAmount(1);
        ItemMeta meta = normalized.getItemMeta();
        if (meta instanceof Damageable damageable) {
            damageable.setDamage(0);
            normalized.setItemMeta(meta);
        }
        return normalized;
    }

    private static int damage(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        return meta instanceof Damageable damageable ? damageable.getDamage() : 0;
    }

    private static boolean isEmpty(ItemStack item) {
        return item == null || item.getType().isAir() || item.getAmount() <= 0;
    }

    enum Failure {
        NONE,
        FOREIGN_MATERIAL,
        DIFFERENT_ITEM,
        WRONG_AMOUNT,
        MORE_DURABILITY
    }

    record SaveResult(boolean valid, Failure failure, Material material) {

        static SaveResult success() {
            return new SaveResult(true, Failure.NONE, null);
        }

        static SaveResult failure(Failure failure, Material material) {
            return new SaveResult(false, failure, material);
        }
    }

    private static final class ItemGroup {
        private final ItemStack prototype;
        private final List<Integer> mainDamage = new ArrayList<>();
        private final List<Integer> candidateDamage = new ArrayList<>();
        private int mainAmount;
        private int candidateAmount;

        private ItemGroup(ItemStack prototype) {
            this.prototype = prototype;
        }
    }
}
