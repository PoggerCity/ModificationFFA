package me.poggercity.modificationFFA;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

final class PetNameFormatter {

    private static final LegacyComponentSerializer FORMATTER = LegacyComponentSerializer.builder()
            .character('&')
            .hexCharacter('#')
            .hexColors()
            .build();

    private PetNameFormatter() {
    }

    static Component format(String input) {
        return FORMATTER.deserialize(input).decoration(TextDecoration.ITALIC, false);
    }
}
