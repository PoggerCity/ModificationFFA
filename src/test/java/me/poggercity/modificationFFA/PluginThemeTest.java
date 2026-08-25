package me.poggercity.modificationFFA;

import net.kyori.adventure.text.format.TextColor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PluginThemeTest {

    @Test
    void loadsBrandingAliasesAndColors() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("branding.name", "Test FFA");
        config.set("branding.menu-name", "Test");
        config.set("branding.command-aliases", List.of("m", "P", "p"));
        config.set("colors.primary", "#123456");
        config.set("colors.accent", "#22CC44");
        config.set("colors.text", "#AABBCC");
        config.set("colors.muted", "#445566");
        config.set("colors.error", "#EE3344");
        config.set("colors.prefix-gradient", List.of("#100010", "#200020"));
        config.set("colors.gradient", List.of("#300030", "#400040", "#500050"));

        PluginTheme.install(PluginTheme.from(config));

        assertEquals("Test FFA", PluginTheme.displayName());
        assertEquals("Test", PluginTheme.menuName());
        assertEquals(List.of("m", "p"), PluginTheme.rootAliases());
        assertEquals(TextColor.color(0x123456), PluginTheme.primary());
        assertArrayEquals(new int[]{0x100010, 0x200020}, PluginTheme.prefixGradient());
        assertArrayEquals(new int[]{0x300030, 0x400040, 0x500050}, PluginTheme.gradient());
    }

    @Test
    void rejectsInvalidAliasAndGradientShapes() {
        YamlConfiguration invalidAlias = new YamlConfiguration();
        invalidAlias.set("branding.command-aliases", List.of("p bin"));
        assertThrows(IllegalArgumentException.class, () -> PluginTheme.from(invalidAlias));

        YamlConfiguration invalidGradient = new YamlConfiguration();
        invalidGradient.set("colors.gradient", "#123456");
        assertThrows(IllegalArgumentException.class, () -> PluginTheme.from(invalidGradient));
    }
}
