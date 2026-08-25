package me.poggercity.modificationFFA;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PluginMessagesTest {

    @TempDir
    Path directory;

    @Test
    void customMessagesOverrideDefaultsAndMissingEntriesFallBack() throws Exception {
        YamlConfiguration defaults = new YamlConfiguration();
        defaults.set("core.reloaded", "default reload");
        defaults.set("core.wait", "default wait");

        File file = directory.resolve("messages.yml").toFile();
        Files.writeString(file.toPath(), "core:\n  reloaded: custom reload\n");

        PluginMessages.Catalog catalog = PluginMessages.loadCatalog(file, defaults);

        assertEquals("custom reload", catalog.string("core.reloaded"));
        assertEquals("default wait", catalog.string("core.wait"));
    }

    @Test
    void malformedYamlIsRejectedBeforeItCanReplaceTheCatalog() throws IOException {
        File file = directory.resolve("messages.yml").toFile();
        Files.writeString(file.toPath(), "core:\n  reloaded: [broken\n");

        assertThrows(InvalidConfigurationException.class,
                () -> PluginMessages.loadCatalog(file, new YamlConfiguration()));
    }
}
