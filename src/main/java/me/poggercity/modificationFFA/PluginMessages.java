package me.poggercity.modificationFFA;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class PluginMessages {

    private static final LegacyComponentSerializer FORMATTER = LegacyComponentSerializer.builder()
            .character('&')
            .hexCharacter('#')
            .hexColors()
            .build();

    private final JavaPlugin plugin;
    private final File file;
    private volatile Catalog catalog;

    public PluginMessages(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.file = new File(plugin.getDataFolder(), "messages.yml");
        this.catalog = new Catalog(new YamlConfiguration(), loadBundledDefaults());
        reload();
    }

    public synchronized boolean reload() {
        ensureFileExists();
        YamlConfiguration defaults = loadBundledDefaults();
        try {
            Catalog loaded = loadCatalog(file, defaults);
            catalog = loaded;
            return true;
        } catch (IOException | InvalidConfigurationException exception) {
            plugin.getLogger().warning("Could not load messages.yml: " + exception.getMessage());
            return false;
        }
    }

    static Catalog loadCatalog(File file, YamlConfiguration defaults)
            throws IOException, InvalidConfigurationException {
        YamlConfiguration custom = new YamlConfiguration();
        custom.load(file);
        return new Catalog(custom, defaults);
    }

    public String text(String path) {
        return text(path, Map.of());
    }

    public String text(String path, Map<String, ?> placeholders) {
        return replace(catalog.string(path), placeholders);
    }

    public Component component(String path) {
        return component(path, Map.of());
    }

    public Component component(String path, Map<String, ?> placeholders) {
        return deserialize(text(path, placeholders));
    }

    public Component componentWithComponents(String path, Map<String, Component> placeholders) {
        Component result = component(path);
        for (Map.Entry<String, Component> entry : placeholders.entrySet()) {
            result = result.replaceText(builder -> builder
                    .matchLiteral("{" + entry.getKey() + "}")
                    .replacement(Objects.requireNonNullElse(entry.getValue(), Component.empty())));
        }
        return result;
    }

    public List<Component> components(String path) {
        return components(path, Map.of());
    }

    public List<Component> components(String path, Map<String, ?> placeholders) {
        List<Component> components = new ArrayList<>();
        for (String line : catalog.lines(path)) {
            components.add(deserialize(replace(line, placeholders)));
        }
        return List.copyOf(components);
    }

    public void send(CommandSender sender, String path) {
        sender.sendMessage(component(path));
    }

    public void send(CommandSender sender, String path, Map<String, ?> placeholders) {
        sender.sendMessage(component(path, placeholders));
    }

    public void sendPrefixed(CommandSender sender, String path) {
        sender.sendMessage(MessageStyle.prefix().append(component(path)));
    }

    public void sendPrefixed(CommandSender sender, String path, Map<String, ?> placeholders) {
        sender.sendMessage(MessageStyle.prefix().append(component(path, placeholders)));
    }

    public void sendLines(CommandSender sender, String path, Map<String, ?> placeholders) {
        components(path, placeholders).forEach(sender::sendMessage);
    }

    private void ensureFileExists() {
        if (file.isFile()) {
            return;
        }
        plugin.getDataFolder().mkdirs();
        if (plugin.getResource("messages.yml") == null) {
            return;
        }
        plugin.saveResource("messages.yml", false);
    }

    private YamlConfiguration loadBundledDefaults() {
        InputStream resource = plugin.getResource("messages.yml");
        if (resource == null) {
            plugin.getLogger().warning("The bundled messages.yml could not be found.");
            return new YamlConfiguration();
        }
        try (InputStreamReader reader = new InputStreamReader(resource, StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        } catch (IOException exception) {
            plugin.getLogger().warning("The bundled messages.yml could not be read: " + exception.getMessage());
            return new YamlConfiguration();
        }
    }

    private String replace(String message, Map<String, ?> supplied) {
        Map<String, Object> placeholders = new LinkedHashMap<>();
        placeholders.put("plugin", PluginTheme.displayName());
        placeholders.put("menu", PluginTheme.menuName());
        if (supplied != null) {
            supplied.forEach((key, value) -> {
                if (key != null) {
                    placeholders.put(key, value);
                }
            });
        }
        String result = message;
        for (Map.Entry<String, Object> entry : placeholders.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", Objects.toString(entry.getValue(), ""));
        }
        return result;
    }

    private Component deserialize(String message) {
        try {
            return FORMATTER.deserialize(applyTheme(message));
        } catch (RuntimeException exception) {
            return Component.text(message == null ? "" : message);
        }
    }

    private String applyTheme(String message) {
        if (message == null) {
            return "";
        }
        return message
                .replace("&d", "&" + PluginTheme.primary().asHexString())
                .replace("&a", "&" + PluginTheme.accent().asHexString())
                .replace("&7", "&" + PluginTheme.text().asHexString())
                .replace("&8", "&" + PluginTheme.muted().asHexString())
                .replace("&c", "&" + PluginTheme.error().asHexString());
    }

    record Catalog(YamlConfiguration custom, YamlConfiguration defaults) {

        String string(String path) {
            String value = custom.getString(path);
            if (value == null) {
                value = defaults.getString(path);
            }
            return value == null ? "&cMissing message: &f" + path : value;
        }

        List<String> lines(String path) {
            if (custom.isList(path)) {
                return custom.getStringList(path);
            }
            if (custom.isString(path)) {
                return List.of(Objects.requireNonNullElse(custom.getString(path), ""));
            }
            if (defaults.isList(path)) {
                return defaults.getStringList(path);
            }
            if (defaults.isString(path)) {
                return List.of(Objects.requireNonNullElse(defaults.getString(path), ""));
            }
            return List.of("&cMissing message: &f" + path);
        }
    }
}
