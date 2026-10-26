package dev.bytestorm.node.template;

import dev.bytestorm.node.lifecycle.ServiceDefinition;

/**
 * Werte für die Platzhalter beim Kopieren eines Templates (§10, §14).
 *
 * <p>Fehlt ein Wert, bleibt sein Platzhalter stehen, statt einen Ersatzwert zu
 * schreiben: ohne reservierten Port gilt das für {@code %PORT%}, ohne
 * Forwarding-Secret für {@code %SECRET%}. So fällt beim Blick in die Datei auf,
 * dass eine Angabe fehlt.
 *
 * @param port       Port des Services, {@code 0} wenn noch nicht vergeben
 * @param name       Service-Name
 * @param group      Gruppenname
 * @param maxPlayers maximale Spielerzahl der Gruppe
 * @param memoryMb   Heap-Größe des Services
 * @param secret     Forwarding-Secret für Proxy und Backends, leer wenn nicht erzeugt
 */
record TemplateContext(int port, String name, String group, int maxPlayers, int memoryMb, String secret) {

    /**
     * @param definition Service
     * @param port       reservierter Port oder {@code 0}
     * @param secret     Forwarding-Secret oder {@code null}
     * @return Kontext für die Platzhalter
     */
    static TemplateContext of(ServiceDefinition definition, int port, String secret) {
        return new TemplateContext(port, definition.name(), definition.group().getName(),
                definition.group().getMaxPlayers(), definition.options().memoryMb(), secret);
    }

    /**
     * @param text Dateiinhalt
     * @return Inhalt mit ersetzten Platzhaltern
     */
    String apply(String text) {
        String result = text
                .replace("%NAME%", name)
                .replace("%GROUP%", group)
                .replace("%MAXPLAYERS%", Integer.toString(maxPlayers))
                .replace("%MEMORY%", Integer.toString(memoryMb));
        // Ohne bekannten Port bleibt der Platzhalter stehen, statt eine 0 zu schreiben.
        if (port > 0) {
            result = result.replace("%PORT%", Integer.toString(port));
        }
        if (secret != null && !secret.isBlank()) {
            result = result.replace("%SECRET%", secret);
        }
        return result;
    }
}
