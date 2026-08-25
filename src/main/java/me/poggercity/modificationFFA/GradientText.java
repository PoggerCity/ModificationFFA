package me.poggercity.modificationFFA;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;

final class GradientText {

    private static final double SPATIAL_SPAN = 0.42D;
    private GradientText() {
    }

    static Component animated(String text, int frame, int frameCount) {
        if (frameCount <= 0) {
            throw new IllegalArgumentException("frameCount must be positive");
        }

        int[] codePoints = text.codePoints().toArray();
        int[] colors = PluginTheme.gradient();
        Component result = Component.empty();
        double globalPhase = Math.floorMod(frame, frameCount) / (double) frameCount;
        for (int index = 0; index < codePoints.length; index++) {
            double characterPhase = (index / (double) Math.max(1, codePoints.length - 1))
                    * SPATIAL_SPAN;
            double localPhase = wrap(characterPhase - globalPhase);
            double progress = 0.5D + (0.5D * Math.cos(localPhase * Math.PI * 2.0D));
            result = result.append(Component.text(
                    new String(Character.toChars(codePoints[index])),
                    colorAt(progress, colors)
            ));
        }
        return result;
    }

    static Component staticGradient(String text) {
        return staticGradient(text, PluginTheme.gradient());
    }

    static Component staticGradient(String text, int[] colors) {
        int[] codePoints = text.codePoints().toArray();
        Component result = Component.empty();
        for (int index = 0; index < codePoints.length; index++) {
            double progress = index / (double) Math.max(1, codePoints.length - 1);
            result = result.append(Component.text(
                    new String(Character.toChars(codePoints[index])),
                    colorAt(progress, colors)
            ));
        }
        return result;
    }

    static Component animatedEvenRightToLeft(String text, int frame, int frameCount) {
        if (frameCount <= 0) {
            throw new IllegalArgumentException("frameCount must be positive");
        }

        int[] codePoints = text.codePoints().toArray();
        int[] colors = loop(PluginTheme.gradient());
        Component result = Component.empty();
        double globalPhase = Math.floorMod(frame, frameCount) / (double) frameCount;
        for (int index = 0; index < codePoints.length; index++) {
            double characterPhase = (index / (double) Math.max(1, codePoints.length - 1)) * 0.38D;
            double localPhase = wrap(characterPhase + globalPhase);
            result = result.append(Component.text(
                    new String(Character.toChars(codePoints[index])),
                    settingsColorAt(localPhase, colors)
            ));
        }
        return result;
    }

    private static TextColor settingsColorAt(double phase, int[] colors) {
        double scaled = wrap(phase) * colors.length;
        int lowerIndex = (int) Math.floor(scaled);
        int upperIndex = (lowerIndex + 1) % colors.length;
        double blend = smootherStep(scaled - lowerIndex);
        int lower = colors[lowerIndex];
        int upper = colors[upperIndex];
        return TextColor.color(
                blendChannel(lower >> 16, upper >> 16, blend),
                blendChannel(lower >> 8, upper >> 8, blend),
                blendChannel(lower, upper, blend)
        );
    }

    private static double wrap(double value) {
        return value - Math.floor(value);
    }

    private static TextColor colorAt(double progress, int[] colors) {
        double scaled = Math.max(0.0D, Math.min(1.0D, progress))
                * (colors.length - 1);
        int lowerIndex = Math.min((int) scaled, colors.length - 2);
        double blend = smootherStep(scaled - lowerIndex);
        int lower = colors[lowerIndex];
        int upper = colors[lowerIndex + 1];
        return TextColor.color(
                blendChannel(lower >> 16, upper >> 16, blend),
                blendChannel(lower >> 8, upper >> 8, blend),
                blendChannel(lower, upper, blend)
        );
    }

    private static int[] loop(int[] colors) {
        if (colors.length == 2) {
            return new int[]{colors[0], colors[1]};
        }
        int[] loop = new int[(colors.length * 2) - 2];
        System.arraycopy(colors, 0, loop, 0, colors.length);
        int target = colors.length;
        for (int index = colors.length - 2; index > 0; index--) {
            loop[target++] = colors[index];
        }
        return loop;
    }

    private static int blendChannel(int from, int to, double amount) {
        return (int) Math.round((from & 0xFF) + (((to & 0xFF) - (from & 0xFF)) * amount));
    }

    private static double smootherStep(double amount) {
        return amount * amount * amount * (amount * ((amount * 6.0D) - 15.0D) + 10.0D);
    }
}
