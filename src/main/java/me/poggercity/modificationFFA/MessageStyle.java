package me.poggercity.modificationFFA;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;

import java.util.Map;

final class MessageStyle {

    private static volatile PluginMessages messages;

    private MessageStyle() {
    }

    static Component prefixed(String text) {
        return prefix().append(Component.text(text, PluginTheme.text()));
    }

    static void installMessages(PluginMessages catalog) {
        messages = catalog;
    }

    static Component message(String path) {
        return message(path, Map.of());
    }

    static Component message(String path, Map<String, ?> placeholders) {
        PluginMessages catalog = messages;
        return catalog == null ? Component.text(path, PluginTheme.text()) : catalog.component(path, placeholders);
    }

    static Component prefixedMessage(String path) {
        return prefixedMessage(path, Map.of());
    }

    static Component prefixedMessage(String path, Map<String, ?> placeholders) {
        return prefix().append(message(path, placeholders));
    }

    static Component messageWithComponents(String path, Map<String, Component> placeholders) {
        PluginMessages catalog = messages;
        return catalog == null
                ? Component.text(path, PluginTheme.text())
                : catalog.componentWithComponents(path, placeholders);
    }

    static Component prefixedMessageWithComponents(String path, Map<String, Component> placeholders) {
        return prefix().append(messageWithComponents(path, placeholders));
    }

    static Component prefix() {
        return createPrefix();
    }

    static Component permissionDenied(String permission) {
        return prefixedMessage("core.no-permission", Map.of("permission", permission));
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
