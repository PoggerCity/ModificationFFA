package me.poggercity.modificationFFA;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class KitLoadCooldownTest {

    @Test
    void displaysQuarterSecondStepsAndExpires() {
        AtomicLong clock = new AtomicLong();
        KitLoadCooldown cooldown = new KitLoadCooldown(clock::get);
        UUID playerId = UUID.randomUUID();

        cooldown.recordSuccess(playerId);
        assertEquals("5.5", cooldown.remaining(playerId));
        clock.set(250_000_000L);
        assertEquals("5.25", cooldown.remaining(playerId));
        clock.set(500_000_000L);
        assertEquals("5.0", cooldown.remaining(playerId));
        clock.set(750_000_000L);
        assertEquals("4.75", cooldown.remaining(playerId));
        clock.set(5_500_000_000L);
        assertNull(cooldown.remaining(playerId));
    }

    @Test
    void roundsUpAndCanBeCleared() {
        AtomicLong clock = new AtomicLong();
        KitLoadCooldown cooldown = new KitLoadCooldown(clock::get);
        UUID playerId = UUID.randomUUID();

        cooldown.recordSuccess(playerId);
        clock.set(250_000_001L);
        assertEquals("5.25", cooldown.remaining(playerId));
        cooldown.clear(playerId);
        assertNull(cooldown.remaining(playerId));
    }
}
