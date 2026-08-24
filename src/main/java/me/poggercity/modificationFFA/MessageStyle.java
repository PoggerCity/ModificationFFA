package me.poggercity.modificationFFA;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;

import java.util.Objects;

final class MessageStyle {

    private static final Component PREFIX = createPrefix();

    private MessageStyle() {
    }

    static Component prefixed(String text) {
        return PREFIX.append(Component.text(text, NamedTextColor.GRAY));
    }

    static Component prefix() {
        return PREFIX;
    }

    private static Component createPrefix() {
        String text = "Modification FFA";
        String[] colors = {
                "#8300C3", "#8400C3", "#8500C3", "#8500C3", "#8600C3",
                "#8700C3", "#8800C3", "#8900C3", "#8900C3", "#8A00C3",
                "#8B00C3", "#8C00C3", "#8C00C3", "#8D00C3", "#8E00C3"
        };

        Component prefix = Component.empty();
        int colorIndex = 0;
        for (char character : text.toCharArray()) {
            if (character == ' ') {
                prefix = prefix.append(Component.space());
                continue;
            }

            TextColor color = Objects.requireNonNull(TextColor.fromHexString(colors[colorIndex++]));
            prefix = prefix.append(Component.text(character, color));
        }
        return prefix.append(Component.text(" » ", NamedTextColor.DARK_GRAY));
    }
}
