package me.poggercity.modificationFFA;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class WeaponTypeTest {

    @Test
    void resolvesNamesAndAliases() {
        assertEquals(WeaponType.DASH, WeaponType.fromName("dash"));
        assertEquals(WeaponType.KNOCKBACK, WeaponType.fromName("KB"));
        assertEquals(WeaponType.PROTECTION, WeaponType.fromName("protection_axe"));
        assertEquals(WeaponType.EXPLOSION, WeaponType.fromName("axe_of_explosion"));
        assertNull(WeaponType.fromName("missing"));
        assertNull(WeaponType.fromName(null));
    }

    @Test
    void onlyActiveAbilitiesHaveCooldowns() {
        assertEquals(400, WeaponType.DASH.cooldownTicks());
        assertEquals(1_200, WeaponType.PROTECTION.cooldownTicks());
        assertEquals(800, WeaponType.EXPLOSION.cooldownTicks());
        assertEquals(0, WeaponType.STRIKE.cooldownTicks());
    }
}
