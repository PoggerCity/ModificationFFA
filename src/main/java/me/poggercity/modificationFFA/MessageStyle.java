package me.poggercity.modificationFFA;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;

final class MessageStyle {

    private MessageStyle() {
    }

    static Component prefixed(String text) {
        return prefix().append(Component.text(text, PluginTheme.text()));
    }

    static Component prefix() {
        return createPrefix();
    }

    static Component permissionDenied(String permission) {
        return prefix()
                .append(Component.text("You do not have permission ", PluginTheme.text()))
                .append(Component.text("(" + permission + ")", PluginTheme.accent()))
                .append(Component.text(" to do that.", PluginTheme.text()));
    }

    private static Component createPrefix() {
        return GradientText.staticGradient(PluginTheme.displayName(), PluginTheme.prefixGradient())
                .append(Component.text(" » ", PluginTheme.muted()));
    }

    static TextColor primary() {
        return PluginTheme.primary();
    }

    static TextColor accent() {
        return PluginTheme.accent();
    }

    static TextColor text() {
        return PluginTheme.text();
    }

    static TextColor muted() {
        return PluginTheme.muted();
    }

    static TextColor error() {
        return PluginTheme.error();
    }
}
