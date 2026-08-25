package me.poggercity.modificationFFA;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PetNameFormatterTest {

    @Test
    void supportsPerCharacterHexColorsAndBoldFormatting() {
        Component name = PetNameFormatter.format(
                "&#FB0000&lH&#E40000&lA&#CD0000&lB&#B50000&lI&#9E0000&lB");

        assertEquals("HABIB", PlainTextComponentSerializer.plainText().serialize(name));
        assertTrue(hasStyledText(name, "H", 0xFB0000));
        assertTrue(hasStyledText(name, "B", 0x9E0000));
    }

    private boolean hasStyledText(Component component, String text, int color) {
        if (component instanceof net.kyori.adventure.text.TextComponent value
                && value.content().equals(text)
                && TextColor.color(color).equals(value.color())
                && value.decoration(TextDecoration.BOLD) == TextDecoration.State.TRUE) {
            return true;
        }
        return component.children().stream().anyMatch(child -> hasStyledText(child, text, color));
    }
}
