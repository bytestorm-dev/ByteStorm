package dev.bytestorm.controller.scaling;

import com.sun.management.OperatingSystemMXBean;

import java.lang.management.ManagementFactory;

/**
 * Host-Auslastung aus dem {@link OperatingSystemMXBean} der JVM.
 *
 * <p>Die Werte sind Momentaufnahmen und können je nach Betriebssystem leicht
 * schwanken. Fehlt die erweiterte MXBean oder liefert sie {@code -1}, gilt der
 * Wert als {@code 0}; die Last-Regel blockiert dann nicht wegen fehlender Daten.
 */
public final class SystemHostMetrics implements HostMetrics {

    private final OperatingSystemMXBean os;

    /**
     * Erzeugt die Metrik-Quelle der laufenden JVM.
     */
    public SystemHostMetrics() {
        java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
        this.os = bean instanceof OperatingSystemMXBean extended ? extended : null;
    }

    @Override
    public double cpuLoad() {
        if (os == null) {
            return 0;
        }
        return clamp(os.getCpuLoad());
    }

    @Override
    public double memoryLoad() {
        if (os == null) {
            return 0;
        }
        long total = os.getTotalMemorySize();
        if (total <= 0) {
            return 0;
        }
        long used = total - os.getFreeMemorySize();
        return clamp((double) used / total);
    }

    private static double clamp(double value) {
        if (Double.isNaN(value) || value < 0) {
            return 0;
        }
        return Math.min(value, 1);
    }
}
