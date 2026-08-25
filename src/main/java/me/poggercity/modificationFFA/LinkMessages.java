package me.poggercity.modificationFFA;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public record LinkMessages(List<Component> discord, List<Component> store, String cooldown) {

    public static LinkMessages load(Path path, String discordUrl, String storeUrl,
                                    TextColor textColor, TextColor accentColor) throws IOException {
        JsonObject root;
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            root = JsonParser.parseReader(reader).getAsJsonObject();
        }

        String separator = requiredString(root, "separator");
        String cooldown = requiredString(root, "cooldown");
        List<Component> discord = buildMessage(
                root.getAsJsonObject("discord"), separator, discordUrl, textColor, accentColor);
        List<Component> store = buildMessage(
                root.getAsJsonObject("store"), separator, storeUrl, textColor, accentColor);
        return new LinkMessages(discord, store, cooldown);
    }

    private static List<Component> buildMessage(JsonObject template, String separator, String url,
                                                TextColor textColor, TextColor accentColor) {
        if (template == null) {
            throw new IllegalArgumentException("A message template is missing from messages.json.");
        }

        validateWebUrl(url);
        JsonArray lines = template.getAsJsonArray("lines");
        if (lines == null) {
            throw new IllegalArgumentException("A message template is missing its lines array.");
        }

        List<Component> result = new ArrayList<>();
        result.add(Component.text(separator, textColor).decorate(TextDecoration.STRIKETHROUGH));
        for (JsonElement line : lines) {
            result.add(Component.text(line.getAsString(), textColor));
        }
        result.add(Component.empty());

        String label = requiredString(template, "linkLabel");
        String hoverText = requiredString(template, "hoverText");
        Component link = Component.text(url, accentColor)
                .clickEvent(ClickEvent.openUrl(url))
                .hoverEvent(HoverEvent.showText(Component.text(hoverText, accentColor)));
        result.add(Component.text(label, textColor).append(link));
        result.add(Component.text(separator, textColor).decorate(TextDecoration.STRIKETHROUGH));
        return List.copyOf(result);
    }

    private static String requiredString(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            throw new IllegalArgumentException("Missing messages.json value: " + key);
        }

        String value = object.get(key).getAsString();
        if (value.isBlank()) {
            throw new IllegalArgumentException("Blank messages.json value: " + key);
        }
        return value;
    }

    private static void validateWebUrl(String url) {
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme();
            if (uri.getHost() == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
                throw new IllegalArgumentException("Link must be a valid http or https URL: " + url);
            }
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("Link is not a valid URL: " + url, exception);
        }
    }
}
