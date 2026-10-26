package dev.bytestorm.launcher.wizard;

import dev.bytestorm.core.config.MongoSettings;
import dev.bytestorm.core.config.RedisSettings;

/**
 * Prüft die eingegebenen Verbindungen, bevor die Konfiguration gespeichert wird.
 */
public interface ConnectionTester {

    /**
     * @param settings Verbindungsdaten
     * @throws RuntimeException wenn MongoDB nicht erreichbar ist
     */
    void testMongo(MongoSettings settings);

    /**
     * @param settings Verbindungsdaten inklusive Passwort
     * @throws RuntimeException wenn Redis nicht erreichbar ist oder das Passwort nicht passt
     */
    void testRedis(RedisSettings settings);
}
