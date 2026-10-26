package dev.bytestorm.core.support;

import org.junit.jupiter.api.Assumptions;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;

/**
 * Hilfen für Integrationstests gegen lokale Server.
 *
 * <p>Diese Tests laufen nur, wenn die Server erreichbar sind:
 * <ul>
 *   <li>MongoDB: {@code BYTESTORM_TEST_MONGO} (Default {@code mongodb://localhost:27017}),
 *       es wird eine zufällige Test-Datenbank benutzt und danach gelöscht.</li>
 *   <li>Redis: {@code BYTESTORM_TEST_REDIS} muss gesetzt sein, z. B.
 *       {@code redis://127.0.0.1:6390}. Nur so werden {@code bs:*} Keys aufgeräumt,
 *       ohne eine echte Instanz zu berühren.</li>
 * </ul>
 */
public final class TestServers {

    private TestServers() {
    }

    /**
     * @return Mongo-URI aus der Umgebung oder der lokale Default
     */
    public static String mongoUri() {
        return env("BYTESTORM_TEST_MONGO", "mongodb://localhost:27017");
    }

    /**
     * @return Redis-Adresse aus der Umgebung oder {@code null}, wenn nicht gesetzt
     */
    public static String redisAddress() {
        return env("BYTESTORM_TEST_REDIS", null);
    }

    /**
     * @return eindeutiger Datenbankname für einen Testlauf
     */
    public static String uniqueDatabase() {
        return "bytestorm_test_" + Long.toHexString(System.nanoTime());
    }

    /**
     * Überspringt den Test, wenn MongoDB nicht erreichbar ist.
     */
    public static void assumeMongo() {
        Assumptions.assumeTrue(reachable(mongoUri()),
                "MongoDB nicht erreichbar unter " + mongoUri() + " — Integrationstest übersprungen");
    }

    /**
     * Überspringt den Test, wenn Redis nicht konfiguriert oder erreichbar ist.
     */
    public static void assumeRedis() {
        String address = redisAddress();
        Assumptions.assumeTrue(address != null,
                "BYTESTORM_TEST_REDIS nicht gesetzt — Redis-Integrationstest übersprungen");
        Assumptions.assumeTrue(reachable(address),
                "Redis nicht erreichbar unter " + address + " — Integrationstest übersprungen");
    }

    private static boolean reachable(String address) {
        URI uri;
        try {
            uri = URI.create(address.contains("://") ? address : "redis://" + address);
        } catch (IllegalArgumentException e) {
            return false;
        }
        String host = uri.getHost();
        int port = uri.getPort();
        if (host == null) {
            return false;
        }
        if (port < 0) {
            port = "mongodb+srv".equals(uri.getScheme()) ? 27017 : 6379;
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 1000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            value = System.getProperty(name.toLowerCase().replace('_', '.'));
        }
        return value == null || value.isBlank() ? fallback : value;
    }
}
