package me.poggercity.modificationFFA;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;

final class GradientText {

    private static final double SPATIAL_SPAN = 0.42D;
    private static final int[] COLOR_STOPS = {
            0xA000B8,
            0xC000B4,
            0xE100A8,
            0xF34C68,
            0xFF9200,
            0xFFD21A
    };
    private static final int[] SETTINGS_COLOR_LOOP = {
            0x8C00C3,
            0xB000B8,
            0xD000AE,
            0xEE247F,
            0xFF7A20,
            0xFFD21A,
            0xFF7A20,
            0xEE247F,
            0xD000AE,
            0xB000B8
    };

    private GradientText() {
    }

    static Component animated(String text, int frame, int frameCount) {
        if (frameCount <= 0) {
            throw new IllegalArgumentException("frameCount must be positive");
        }

        int[] codePoints = text.codePoints().toArray();
        Component result = Component.empty();
        double globalPhase = Math.floorMod(frame, frameCount) / (double) frameCount;
        for (int index = 0; index < codePoints.length; index++) {
            double characterPhase = (index / (double) Math.max(1, codePoints.length - 1))
                    * SPATIAL_SPAN;
            double localPhase = wrap(characterPhase - globalPhase);
            double progress = 0.5D + (0.5D * Math.cos(localPhase * Math.PI * 2.0D));
            result = result.append(Component.text(
                    new String(Character.toChars(codePoints[index])),
                    colorAt(progress)
            ));
        }
        return result;
    }

    static Component staticGradient(String text) {
        int[] codePoints = text.codePoints().toArray();
        Component result = Component.empty();
        for (int index = 0; index < codePoints.length; index++) {
            double progress = index / (double) Math.max(1, codePoints.length - 1);
            result = result.append(Component.text(
                    new String(Character.toChars(codePoints[index])),
                    colorAt(progress)
            ));
        }
        return result;
    }

    static Component animatedEvenRightToLeft(String text, int frame, int frameCount) {
        if (frameCount <= 0) {
            throw new IllegalArgumentException("frameCount must be positive");
        }

        int[] codePoints = text.codePoints().toArray();
        Component result = Component.empty();
        double globalPhase = Math.floorMod(frame, frameCount) / (double) frameCount;
        for (int index = 0; index < codePoints.length; index++) {
            double characterPhase = (index / (double) Math.max(1, codePoints.length - 1)) * 0.38D;
            double localPhase = wrap(characterPhase + globalPhase);
            result = result.append(Component.text(
                    new String(Character.toChars(codePoints[index])),
                    settingsColorAt(localPhase)
            ));
        }
        return result;
    }

    private static TextColor settingsColorAt(double phase) {
        double scaled = wrap(phase) * SETTINGS_COLOR_LOOP.length;
        int lowerIndex = (int) Math.floor(scaled);
        int upperIndex = (lowerIndex + 1) % SETTINGS_COLOR_LOOP.length;
        double blend = smootherStep(scaled - lowerIndex);
        int lower = SETTINGS_COLOR_LOOP[lowerIndex];
        int upper = SETTINGS_COLOR_LOOP[upperIndex];
        return TextColor.color(
                blendChannel(lower >> 16, upper >> 16, blend),
                blendChannel(lower >> 8, upper >> 8, blend),
                blendChannel(lower, upper, blend)
        );
    }

    private static double wrap(double value) {
        return value - Math.floor(value);
    }

    private static TextColor colorAt(double progress) {
        double scaled = Math.max(0.0D, Math.min(1.0D, progress))
                * (COLOR_STOPS.length - 1);
        int lowerIndex = Math.min((int) scaled, COLOR_STOPS.length - 2);
        double blend = smootherStep(scaled - lowerIndex);
        int lower = COLOR_STOPS[lowerIndex];
        int upper = COLOR_STOPS[lowerIndex + 1];
        return TextColor.color(
                blendChannel(lower >> 16, upper >> 16, blend),
                blendChannel(lower >> 8, upper >> 8, blend),
                blendChannel(lower, upper, blend)
        );
    }

    private static int blendChannel(int from, int to, double amount) {
        return (int) Math.round((from & 0xFF) + (((to & 0xFF) - (from & 0xFF)) * amount));
    }

    private static double smootherStep(double amount) {
        return amount * amount * amount * (amount * ((amount * 6.0D) - 15.0D) + 10.0D);
    }
}
