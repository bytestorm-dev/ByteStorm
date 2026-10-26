package dev.bytestorm.api.service;

/**
 * Inklusives Port-Intervall, z. B. {@code 30000-30999} für Server (Konzept §12.4).
 *
 * @param start erster Port
 * @param end   letzter Port
 */
public record PortRange(int start, int end) {

    /**
     * @param start erster Port, 1–65535
     * @param end   letzter Port, 1–65535 und ≥ {@code start}
     */
    public PortRange {
        if (start < 1 || start > 65535) {
            throw new IllegalArgumentException("start liegt außerhalb 1-65535: " + start);
        }
        if (end < start || end > 65535) {
            throw new IllegalArgumentException("end muss zwischen " + start + " und 65535 liegen: " + end);
        }
    }

    /**
     * @param port zu prüfender Port
     * @return {@code true}, wenn der Port im Intervall liegt
     */
    public boolean contains(int port) {
        return port >= start && port <= end;
    }

    /**
     * @return Anzahl der Ports im Intervall
     */
    public int size() {
        return end - start + 1;
    }
}
