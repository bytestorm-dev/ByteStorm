package dev.bytestorm.plugins.proxy.command;

/**
 * Eine Antwortzeile von {@code /cloud}.
 *
 * <p>Meist reiner MiniMessage-Text. Ein Hinweis wie „neu starten" trägt
 * zusätzlich den Befehl, den ein Klick ausführt — so bleibt die Ausführung beim
 * Spieler und der Dispatcher frei von Plattform-Code. Zeilen aus der
 * Service-Konsole sind dagegen Rohtext ({@link #literal(String)}): Sie dürfen
 * nicht als MiniMessage gedeutet werden.
 *
 * @param text    übersetzter Text (MiniMessage) oder rohe Logzeile
 * @param command Befehl für einen Klick, leer wenn die Zeile nur Text ist
 * @param literal {@code true}, wenn {@code text} unverändert ausgegeben wird
 */
public record Reply(String text, String command, boolean literal) {

    /**
     * @param text    Text
     * @param command Befehl für einen Klick, {@code null} wird zu leer
     * @param literal Rohtext ohne Auszeichnung
     */
    public Reply {
        text = text == null ? "" : text;
        command = command == null ? "" : command;
    }

    /**
     * @param text Text
     * @return eine Zeile ohne Klickziel
     */
    public static Reply of(String text) {
        return new Reply(text, "", false);
    }

    /**
     * @param text Rohtext, z. B. eine Zeile aus der Service-Konsole
     * @return eine Zeile, die unverändert ausgegeben wird
     */
    public static Reply literal(String text) {
        return new Reply(text, "", true);
    }

    /**
     * @param text    Text
     * @param command Befehl, den ein Klick ausführt
     * @return eine anklickbare Zeile
     */
    public static Reply run(String text, String command) {
        return new Reply(text, command, false);
    }

    /**
     * @return {@code true}, wenn ein Klick einen Befehl ausführt
     */
    public boolean clickable() {
        return !command.isBlank();
    }
}
