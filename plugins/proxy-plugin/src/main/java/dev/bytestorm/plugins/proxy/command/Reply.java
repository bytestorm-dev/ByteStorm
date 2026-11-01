package dev.bytestorm.plugins.proxy.command;

/**
 * Eine Antwortzeile von {@code /cloud}.
 *
 * <p>Meist reiner Text. Ein Hinweis wie „neu starten" trägt zusätzlich den
 * Befehl, den ein Klick ausführt — so bleibt die Ausführung beim Spieler und der
 * Dispatcher frei von Plattform-Code.
 *
 * @param text    übersetzter Text mit {@code &}-Farbcodes
 * @param command Befehl für einen Klick, leer wenn die Zeile nur Text ist
 */
public record Reply(String text, String command) {

    /**
     * @param text    Text
     * @param command Befehl für einen Klick, {@code null} wird zu leer
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
        return new Reply(text, "");
    }

    /**
     * @param text    Text
     * @param command Befehl, den ein Klick ausführt
     * @return eine anklickbare Zeile
     */
    public static Reply run(String text, String command) {
        return new Reply(text, command);
    }

    /**
     * @return {@code true}, wenn ein Klick einen Befehl ausführt
     */
    public boolean clickable() {
        return !command.isBlank();
    }
}
