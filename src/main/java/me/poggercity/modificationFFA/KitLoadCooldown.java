package me.poggercity.modificationFFA;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

final class KitLoadCooldown {

    static final long DURATION_NANOS = 5_500_000_000L;
    private static final long DISPLAY_STEP_NANOS = 250_000_000L;

    private final LongSupplier clock;
    private final Map<UUID, Long> successfulLoads = new HashMap<>();

    KitLoadCooldown() {
        this(System::nanoTime);
    }

    KitLoadCooldown(LongSupplier clock) {
        this.clock = clock;
    }

    String remaining(UUID playerId) {
        Long startedAt = successfulLoads.get(playerId);
        if (startedAt == null) {
            return null;
        }
        long remaining = DURATION_NANOS - (clock.getAsLong() - startedAt);
        if (remaining <= 0L) {
            successfulLoads.remove(playerId);
            return null;
        }
        long steps = Math.max(1L, (remaining + DISPLAY_STEP_NANOS - 1L) / DISPLAY_STEP_NANOS);
        double seconds = steps * 0.25D;
        String formatted = String.format(Locale.ROOT, "%.2f", seconds);
        return formatted.endsWith("0") ? formatted.substring(0, formatted.length() - 1) : formatted;
    }

    void recordSuccess(UUID playerId) {
        successfulLoads.put(playerId, clock.getAsLong());
    }

    void clear(UUID playerId) {
        successfulLoads.remove(playerId);
    }

    void clear() {
        successfulLoads.clear();
    }
}
