package me.poggercity.modificationFFA;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;

import java.util.List;
import java.util.Locale;

enum WeaponType {
    STRIKE("strike", "⚡", "Strike Sword", NamedTextColor.YELLOW,
            Material.NETHERITE_SWORD, "⚡ Strike Sword", true,
            "A chance to strike enemies with lightning", List.of(
            "Sharpness V",
            "Sweeping Edge III",
            "Fire Aspect II",
            "Unbreakable",
            "A chance to strike enemies with lightning"
    )),
    DASH("dash", "🚀", "Dash Sword", NamedTextColor.GREEN,
            Material.NETHERITE_SWORD, "🚀 Dash Sword", true,
            "Lets you dash through the air!", List.of(
            "Sharpness V",
            "Sweeping Edge III",
            "Fire Aspect II",
            "Lets you dash through the air!",
            "",
            "Can be activated by shift right clicking or by",
            "doing /sword ability while holding the sword."
    )),
    EXECUTIONER("executioner", "☠", "Executioner Sword", NamedTextColor.GOLD,
            Material.NETHERITE_SWORD, "☠ Executioner Sword", true,
            "A chance to drop player's heads when you kill them", List.of(
            "Sharpness V",
            "Sweeping Edge III",
            "Fire Aspect II",
            "Unbreakable",
            "A chance to drop player's heads when you kill them"
    )),
    VOID("void", "✺", "Void Sword", NamedTextColor.DARK_GRAY,
            Material.NETHERITE_SWORD, "🌑 Void Sword", true,
            "A chance to take enemies' vision!", List.of(
            "Sharpness V",
            "Sweeping Edge III",
            "Fire Aspect II",
            "Unbreakable",
            "A chance to take enemies vision!"
    )),
    LIFESTEAL("lifesteal", "❤", "Lifesteal Sword", NamedTextColor.RED,
            Material.NETHERITE_SWORD, null, false,
            "A chance to heal when you hit someone!", List.of(
            "Sharpness V",
            "Sweeping Edge III",
            "Fire Aspect II",
            "A chance to heal when you hit someone!"
    )),
    INHIBITOR("inhibitor", "🔒", "Inhibitor Sword", NamedTextColor.AQUA,
            Material.NETHERITE_SWORD, null, false,
            "A chance to disable enemies cobwebs!", List.of(
            "Sharpness V",
            "Sweeping Edge III",
            "Fire Aspect II",
            "A chance to disable enemies cobwebs!"
    )),
    KNOCKBACK("knockback", "", "Knockback Sword", NamedTextColor.YELLOW,
            Material.GOLDEN_SWORD, null, false, "", List.of()),
    PROTECTION("protection", "🛡", "Axe of Protection", TextColor.color(0x07E824),
            Material.NETHERITE_AXE, null, false, "", List.of(
            "Sharpness V",
            "Efficiency V",
            "Protects you against a certain amount of",
            "hits, scaling by the amount recent attackers",
            "or for 15 seconds.",
            "",
            "Can be activated shift right clicking or by",
            "doing /sword ability while holding the axe."
    )),
    RESISTANCE("resistance", "⛓", "Axe of Resistance", NamedTextColor.BLUE,
            Material.NETHERITE_AXE, null, false, "", List.of(
            "Sharpness V",
            "Efficiency V",
            "Gain damage reduction per additional attacker in",
            "the last 10 seconds.",
            "",
            "Axe must be in the hotbar to work."
    )),
    EXPLOSION("explosion", "💥", "Axe of Explosion", NamedTextColor.DARK_RED,
            Material.NETHERITE_AXE, null, false, "", List.of(
            "Sharpness V",
            "Efficiency V",
            "Knocks nearby players away and removes",
            "nearby cobwebs, stone, and obsidian.",
            "",
            "Can be activated shift right clicking or by",
            "doing /sword ability while holding the axe."
    )),
    EXCAVATOR("excavator", "", "Excavator Pickaxe", TextColor.color(0x007AC7),
            Material.NETHERITE_PICKAXE, null, false, "", List.of(
            "Silk Touch",
            "Efficiency V",
            "Mines in a 3x3x3 block area!"
    ));

    final String id;
    final String abilityMarker;
    final List<String> lore;
    final String legacyItemName;
    final boolean legacyCompatible;
    final Material material;

    private final String emoji;
    private final String label;
    private final TextColor color;

    WeaponType(String id, String emoji, String label, TextColor color, Material material,
               String legacyItemName, boolean legacyCompatible, String abilityMarker,
               List<String> lore) {
        this.id = id;
        this.emoji = emoji;
        this.label = label;
        this.color = color;
        this.material = material;
        this.legacyItemName = legacyItemName;
        this.legacyCompatible = legacyCompatible;
        this.abilityMarker = abilityMarker;
        this.lore = lore;
    }

    Component displayName() {
        Component name = Component.empty();
        if (!emoji.isEmpty()) {
            name = name.append(Component.text(emoji, color)
                            .decoration(TextDecoration.BOLD, true)
                            .decoration(TextDecoration.ITALIC, false))
                    .append(Component.space());
        }
        return name.append(Component.text(label, color)
                .decoration(TextDecoration.BOLD, false)
                .decoration(TextDecoration.ITALIC, false));
    }

    boolean revealsNativeTooltip() {
        return this == DASH || this == PROTECTION || this == RESISTANCE || this == EXPLOSION;
    }

    int cooldownTicks() {
        return switch (this) {
            case DASH -> 20 * 20;
            case PROTECTION -> 60 * 20;
            case EXPLOSION -> 40 * 20;
            default -> 0;
        };
    }

    String modelId() {
        return switch (this) {
            case STRIKE -> "strike_sword";
            case DASH -> "dash_sword";
            case EXECUTIONER -> "executioner_sword";
            case VOID -> "void_sword";
            case LIFESTEAL -> "lifesteal_sword";
            case INHIBITOR -> "inhibitor_sword";
            case KNOCKBACK -> "knockback_sword";
            case PROTECTION -> "protection_axe";
            case RESISTANCE -> "resistance_axe";
            case EXPLOSION -> "explosion_axe";
            case EXCAVATOR -> "excavator_pickaxe";
        };
    }

    static WeaponType fromName(String name) {
        if (name == null) {
            return null;
        }
        String normalized = name.toLowerCase(Locale.ROOT);
        WeaponType alias = switch (normalized) {
            case "kb" -> KNOCKBACK;
            case "protection_axe", "axe_of_protection" -> PROTECTION;
            case "resistance_axe", "axe_of_resistance" -> RESISTANCE;
            case "explosion_axe", "axe_of_explosion" -> EXPLOSION;
            default -> null;
        };
        if (alias != null) {
            return alias;
        }
        for (WeaponType type : values()) {
            if (type.id.equals(normalized)) {
                return type;
            }
        }
        return null;
    }
}
