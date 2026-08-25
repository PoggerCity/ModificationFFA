package me.poggercity.modificationFFA;

import net.kyori.adventure.text.format.TextColor;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class PluginTheme {

    private static volatile PluginTheme current = defaults();

    private final String displayName;
    private final String menuName;
    private final List<String> rootAliases;
    private final TextColor primary;
    private final TextColor accent;
    private final TextColor text;
    private final TextColor muted;
    private final TextColor error;
    private final int[] prefixGradient;
    private final int[] gradient;

    private PluginTheme(String displayName, String menuName, List<String> rootAliases,
                        TextColor primary, TextColor accent, TextColor text,
                        TextColor muted, TextColor error, int[] prefixGradient, int[] gradient) {
        this.displayName = displayName;
        this.menuName = menuName;
        this.rootAliases = rootAliases;
        this.primary = primary;
        this.accent = accent;
        this.text = text;
        this.muted = muted;
        this.error = error;
        this.prefixGradient = prefixGradient;
        this.gradient = gradient;
    }

    static PluginTheme from(FileConfiguration config) {
        String displayName = cleanName(config.getString("branding.name", "Modification FFA"), "branding.name");
        String menuName = cleanName(config.getString("branding.menu-name", "Modification"), "branding.menu-name");
        requireList(config, "branding.command-aliases");
        List<String> aliases = aliases(config.getStringList("branding.command-aliases"));
        TextColor primary = color(config, "colors.primary", "#8C00C3");
        TextColor accent = color(config, "colors.accent", "#55FF55");
        TextColor text = color(config, "colors.text", "#AAAAAA");
        TextColor muted = color(config, "colors.muted", "#555555");
        TextColor error = color(config, "colors.error", "#FF5555");
        int[] prefixGradient = gradient(config, "colors.prefix-gradient",
                List.of("#8300C3", "#8E00C3"));
        int[] gradient = gradient(config, "colors.gradient",
                List.of("#A000B8", "#C000B4", "#E100A8", "#F34C68", "#FF9200", "#FFD21A"));
        return new PluginTheme(displayName, menuName, aliases, primary, accent, text, muted, error,
                prefixGradient, gradient);
    }

    static void install(PluginTheme theme) {
        current = theme;
    }

    static String displayName() {
        return current.displayName;
    }

    static String menuName() {
        return current.menuName;
    }

    static List<String> rootAliases() {
        return current.rootAliases;
    }

    static TextColor primary() {
        return current.primary;
    }

    static TextColor accent() {
        return current.accent;
    }

    static TextColor text() {
        return current.text;
    }

    static TextColor muted() {
        return current.muted;
    }

    static TextColor error() {
        return current.error;
    }

    TextColor textColor() {
        return text;
    }

    TextColor accentColor() {
        return accent;
    }

    static int[] gradient() {
        return current.gradient.clone();
    }

    static int[] prefixGradient() {
        return current.prefixGradient.clone();
    }

    private static PluginTheme defaults() {
        return new PluginTheme(
                "Modification FFA",
                "Modification",
                List.of("m", "p"),
                TextColor.color(0x8C00C3),
                TextColor.color(0x55FF55),
                TextColor.color(0xAAAAAA),
                TextColor.color(0x555555),
                TextColor.color(0xFF5555),
                new int[]{0x8300C3, 0x8E00C3},
                new int[]{0xA000B8, 0xC000B4, 0xE100A8, 0xF34C68, 0xFF9200, 0xFFD21A}
        );
    }

    private static String cleanName(String value, String path) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(path + " cannot be empty");
        }
        String cleaned = value.strip();
        if (cleaned.length() > 48) {
            throw new IllegalArgumentException(path + " cannot be longer than 48 characters");
        }
        return cleaned;
    }

    private static List<String> aliases(List<String> values) {
        Set<String> aliases = new LinkedHashSet<>();
        for (String value : values.isEmpty() ? List.of("m", "p") : values) {
            if (value == null) {
                continue;
            }
            String alias = value.strip().toLowerCase(Locale.ROOT);
            if (!alias.matches("[a-z0-9_-]{1,16}")) {
                throw new IllegalArgumentException("Invalid branding.command-aliases entry: " + value);
            }
            aliases.add(alias);
        }
        return List.copyOf(new ArrayList<>(aliases));
    }

    private static TextColor color(FileConfiguration config, String path, String fallback) {
        return TextColor.color(colorValue(config.getString(path, fallback), path));
    }

    private static int[] gradient(FileConfiguration config, String path, List<String> fallback) {
        requireList(config, path);
        List<String> values = config.getStringList(path);
        if (values.isEmpty()) {
            values = fallback;
        }
        if (values.size() < 2 || values.size() > 16) {
            throw new IllegalArgumentException(path + " must contain between 2 and 16 colors");
        }
        int[] colors = new int[values.size()];
        for (int index = 0; index < values.size(); index++) {
            colors[index] = colorValue(values.get(index), path);
        }
        return colors;
    }

    private static void requireList(FileConfiguration config, String path) {
        if (config.contains(path) && !config.isList(path)) {
            throw new IllegalArgumentException(path + " must be a list");
        }
    }

    private static int colorValue(String value, String path) {
        if (value == null || !value.matches("#[0-9A-Fa-f]{6}")) {
            throw new IllegalArgumentException(path + " must use #RRGGBB");
        }
        return Integer.parseInt(value.substring(1), 16);
    }
}
