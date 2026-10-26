package dev.bytestorm.controller.scaling;

/**
 * Host-Auslastung für die Last-Regel (§11).
 *
 * <p>Beide Werte liegen zwischen {@code 0.0} und {@code 1.0}. Nicht verfügbare
 * Messwerte werden als {@code 0.0} gemeldet, damit ein fehlender Messpfad nicht
 * jeden Start blockiert.
 */
public interface HostMetrics {

    /**
     * @return CPU-Auslastung des Hosts zwischen 0 und 1
     */
    double cpuLoad();

    /**
     * @return Speicherauslastung des Hosts zwischen 0 und 1
     */
    double memoryLoad();
}
