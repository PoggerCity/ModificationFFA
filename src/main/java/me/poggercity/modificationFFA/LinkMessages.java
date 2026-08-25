package me.poggercity.modificationFFA;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.TextDecoration;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class LinkMessages {

    private final List<Component> discord;
    private final List<Component> store;
    private final PluginMessages messages;

    private LinkMessages(List<Component> discord, List<Component> store, PluginMessages messages) {
        this.discord = List.copyOf(discord);
        this.store = List.copyOf(store);
        this.messages = messages;
    }

    public static LinkMessages load(PluginMessages messages, String discordUrl, String storeUrl) {
        Objects.requireNonNull(messages, "messages");
        List<Component> discord = buildMessage(messages, "discord", discordUrl);
        List<Component> store = buildMessage(messages, "store", storeUrl);
        return new LinkMessages(discord, store, messages);
    }

    public List<Component> discord() {
        return discord;
    }

    public List<Component> store() {
        return store;
    }

    public Component cooldown(long seconds) {
        return messages.component("links.cooldown", Map.of("seconds", seconds));
    }

    private static List<Component> buildMessage(PluginMessages messages, String name, String url) {
        validateWebUrl(url);
        String root = "links." + name;
        Component separator = messages.component("links.separator").decorate(TextDecoration.STRIKETHROUGH);
        List<Component> result = new ArrayList<>();
        result.add(separator);
        result.addAll(messages.components(root + ".lines"));
        result.add(Component.empty());

        Component hover = messages.component(root + ".hover");
        Component link = messages.component(root + ".label", Map.of("link", url))
                .clickEvent(ClickEvent.openUrl(url))
                .hoverEvent(HoverEvent.showText(hover));
        result.add(link);
        result.add(separator);
        return result;
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
