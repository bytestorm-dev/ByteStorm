package dev.bytestorm.node.runtime;

import dev.bytestorm.api.service.ServiceHandle;

/**
 * Runtime, die Konsolenbefehle an einen laufenden Service weiterleiten kann
 * (§17).
 *
 * <p>Nicht jede Runtime unterstützt eine interaktive Konsole: Die
 * {@link ProcessRuntime} schreibt auf stdin des Prozesses, Container brauchen
 * dafür eine offene stdin-Verbindung. Der Node prüft die Fähigkeit über
 * {@code instanceof}, statt das {@link ServiceRuntime}-Interface zu erweitern.
 */
public interface CommandAware {

    /**
     * Sendet eine Zeile an die Service-Konsole.
     *
     * @param handle  laufender Service
     * @param command Befehl ohne Zeilenende
     */
    void sendCommand(ServiceHandle handle, String command);
}
