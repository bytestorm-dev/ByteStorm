package dev.bytestorm.launcher;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Minimaler Service für den Bootstrap-Integrationstest.
 *
 * <p>Läuft als eigener Java-Prozess aus einem Platform-Jar, schreibt eine
 * erkennbare Zeile und wartet auf {@code stop} auf stdin.
 */
public final class LifecycleProbe {

    private LifecycleProbe() {
    }

    /**
     * @param args ignoriert
     * @throws Exception wenn stdin nicht gelesen werden kann
     */
    public static void main(String[] args) throws Exception {
        System.out.println("lifecycle-probe ready");
        System.out.flush();
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.trim().equalsIgnoreCase("stop")) {
                    break;
                }
            }
        }
        System.out.println("lifecycle-probe stopped");
    }
}
