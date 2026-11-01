package dev.bytestorm.core.alert;

import java.util.Objects;

/**
 * Ein einzelner Alarm.
 *
 * <p>Gleichheit ist der ganze Zustand: Zwei Alarme mit gleicher Stufe, gleicher
 * Quelle und gleichem Text sind derselbe Alarm. Die {@code source} identifiziert
 * das betroffene Objekt ({@code group:Lobby}, {@code health:redis},
 * {@code service:Lobby-1}), nicht den Text — so bleibt ein Alarm über mehrere
 * Prüfläufe derselbe und wird nur einmal gemeldet.
 *
 * <p>Der Text soll stabil bleiben: Zahlen, die bei jedem Lauf schwanken, würden
 * sonst jedes Mal „behoben“ und neu gemeldet.
 *
 * @param severity Dringlichkeit
 * @param source   stabile Kennung des Betroffenen, z. B. {@code group:Proxy}
 * @param message  lesbare Beschreibung samt nächstem Schritt
 */
public record Alert(AlertSeverity severity, String source, String message) {

    /**
     * @param severity Dringlichkeit, {@code null} wird zu {@link AlertSeverity#WARNING}
     * @param source   Kennung des Betroffenen
     * @param message  Beschreibung für die Konsole
     */
    public Alert {
        severity = severity == null ? AlertSeverity.WARNING : severity;
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(message, "message");
        if (source.isBlank()) {
            throw new IllegalArgumentException("source darf nicht leer sein");
        }
        if (message.isBlank()) {
            throw new IllegalArgumentException("message darf nicht leer sein");
        }
    }

    /**
     * @param source  Kennung des Betroffenen
     * @param message Beschreibung
     * @return Alarm der Stufe {@link AlertSeverity#WARNING}
     */
    public static Alert warning(String source, String message) {
        return new Alert(AlertSeverity.WARNING, source, message);
    }

    /**
     * @param source  Kennung des Betroffenen
     * @param message Beschreibung
     * @return Alarm der Stufe {@link AlertSeverity#CRITICAL}
     */
    public static Alert critical(String source, String message) {
        return new Alert(AlertSeverity.CRITICAL, source, message);
    }
}
