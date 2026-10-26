package dev.bytestorm.core.config;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProxySettingsTest {

    @Test
    void generatedSecretIsHexAndLongEnough() {
        String secret = ProxySettings.generateSecret();

        assertEquals(ProxySettings.SECRET_BYTES * 2, secret.length(),
                "Hex-Schreibweise: zwei Zeichen je Byte");
        assertTrue(secret.matches("[0-9a-f]+"), secret);
    }

    @Test
    void generatedSecretsDiffer() {
        Set<String> secrets = new HashSet<>();
        for (int i = 0; i < 20; i++) {
            assertTrue(secrets.add(ProxySettings.generateSecret()));
        }
        assertNotEquals(ProxySettings.generateSecret(), ProxySettings.generateSecret());
    }

    @Test
    void blankSecretCountsAsMissing() {
        assertFalse(ProxySettings.defaults().hasSecret());
        assertFalse(new ProxySettings(null).hasSecret());
        assertFalse(new ProxySettings("   ").hasSecret());
        assertEquals("a b", new ProxySettings(" a b ").forwardingSecret(),
                "nur außen wird getrimmt");
    }

    @Test
    void withSecretCreatesACopy() {
        ProxySettings settings = ProxySettings.defaults();

        ProxySettings updated = settings.withSecret("neu");

        assertEquals("neu", updated.forwardingSecret());
        assertFalse(settings.hasSecret(), "die ursprünglichen Einstellungen bleiben unverändert");
    }
}
