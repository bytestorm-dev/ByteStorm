package dev.bytestorm.bridge.common;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BridgeSettingsTest {

    private static final String ID = "6f1d1b4e-4f0e-4a9e-9f1a-2c3d4e5f6a7b";

    @Test
    void readsEnvironmentValues() {
        BridgeSettings settings = BridgeSettings.fromEnvironment(new Properties(), Map.of(
                BridgeSettings.ENV_SERVICE_ID, ID,
                BridgeSettings.ENV_SERVICE_NAME, "Lobby-1",
                BridgeSettings.ENV_REDIS_ADDRESS, "redis://localhost:6379",
                BridgeSettings.ENV_REDIS_PASSWORD, "geheim"));

        assertEquals(UUID.fromString(ID), settings.serviceId());
        assertEquals("Lobby-1", settings.serviceName());
        assertEquals("redis://localhost:6379", settings.redisAddress());
        assertEquals("geheim", settings.redisPassword());
        assertEquals(BridgeSettings.DEFAULT_HANDSHAKE_TIMEOUT, settings.handshakeTimeout());
        assertEquals(BridgeSettings.DEFAULT_HEARTBEAT_INTERVAL, settings.heartbeatInterval());
    }

    @Test
    void systemPropertiesOverrideEnvironment() {
        Properties properties = new Properties();
        properties.setProperty(BridgeSettings.PROPERTY_SERVICE_ID, ID);
        properties.setProperty(BridgeSettings.PROPERTY_SERVICE_NAME, "Proxy-1");
        properties.setProperty(BridgeSettings.PROPERTY_REDIS_ADDRESS, "rediss://redis.example:6380");

        BridgeSettings settings = BridgeSettings.fromEnvironment(properties, Map.of(
                BridgeSettings.ENV_SERVICE_NAME, "Lobby-1",
                BridgeSettings.ENV_REDIS_ADDRESS, "redis://localhost:6379"));

        assertEquals("Proxy-1", settings.serviceName());
        assertEquals("rediss://redis.example:6380", settings.redisAddress());
    }

    @Test
    void missingIdIsReported() {
        BridgeConfigurationException error = assertThrows(BridgeConfigurationException.class,
                () -> BridgeSettings.fromEnvironment(new Properties(), Map.of(
                        BridgeSettings.ENV_SERVICE_NAME, "Lobby-1",
                        BridgeSettings.ENV_REDIS_ADDRESS, "redis://localhost:6379")));

        assertTrue(error.getMessage().contains("Service-ID"), error.getMessage());
    }

    @Test
    void invalidAddressIsReported() {
        BridgeConfigurationException error = assertThrows(BridgeConfigurationException.class,
                () -> BridgeSettings.fromEnvironment(new Properties(), Map.of(
                        BridgeSettings.ENV_SERVICE_ID, ID,
                        BridgeSettings.ENV_SERVICE_NAME, "Lobby-1",
                        BridgeSettings.ENV_REDIS_ADDRESS, "localhost:6379")));

        assertTrue(error.getMessage().contains("redis://"), error.getMessage());
    }

    @Test
    void durationsMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> new BridgeSettings(
                UUID.fromString(ID), "Lobby-1", "redis://localhost:6379", null,
                Duration.ZERO, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new BridgeSettings(
                UUID.fromString(ID), "Lobby-1", "redis://localhost:6379", null,
                Duration.ofSeconds(1), Duration.ofSeconds(-1)));
    }

    @Test
    void emptyPasswordBecomesNull() {
        BridgeSettings settings = new BridgeSettings(
                UUID.fromString(ID), "Lobby-1", "redis://localhost:6379", "",
                Duration.ofSeconds(5), Duration.ofSeconds(1));

        assertNull(settings.redisPassword());
    }

    @Test
    void toStringRedactsPassword() {
        BridgeSettings settings = new BridgeSettings(
                UUID.fromString(ID), "Lobby-1", "redis://localhost:6379", "super-geheim",
                Duration.ofSeconds(5), Duration.ofSeconds(1));

        String text = settings.toString();

        assertFalse(text.contains("super-geheim"), text);
        assertTrue(text.contains("***"), text);
    }
}
