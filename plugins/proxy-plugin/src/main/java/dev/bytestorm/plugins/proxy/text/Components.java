package dev.bytestorm.plugins.proxy.text;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * Wandelt die Texte aus den Sprachdateien in Adventure-Komponenten.
 *
 * <p>Die Sprachdateien sind MiniMessage ({@code <aqua>}, {@code <bold>},
 * {@code <gradient:...>}) und dürfen Zeilenumbrüche enthalten — so bleiben
 * Übersetzungen reiner Text und brauchen kein Wissen über Komponenten. Diese
 * Klasse ist die einzige Stelle, die die Plattform-Text-API kennt.
 *
 * <p>Zwei Ausnahmen: Ein {@code &}-Code aus einer alten Sprachdatei wird weiter
 * gelesen, damit eine bestehende Übersetzung nach dem Update nicht plötzlich
 * Farbcodes zeigt. Und Konsolenzeilen eines Services sind kein MiniMessage —
 * sie gehen über {@link #literal(String)}, damit {@code <} und {@code &} in
 * Logausgaben unverändert ankommen.
 */
public final class Components {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private static final LegacyComponentSerializer LEGACY =
            LegacyComponentSerializer.legacyAmpersand();

    private Components() {
    }

    /**
     * @param text MiniMessage-Text aus einer Sprachdatei, darf {@code null} sein
     * @return die Komponente für Chat, Tablist oder Kick-Bildschirm
     */
    public static Component of(String text) {
        String source = text == null ? "" : text;
        if (source.isEmpty()) {
            return Component.empty();
        }
        try {
            return MINI_MESSAGE.deserialize(source);
        } catch (RuntimeException e) {
            // Ein Tippfehler im Tag darf die Anzeige nicht kippen: alte &-Codes
            // bleiben lesbar, alles andere erscheint unverändert.
            return LEGACY.deserialize(source);
        }
    }

    /**
     * Wie {@link #of(String)}, aber mit Klickziel: Ein Klick führt den Befehl
     * aus, der Zeiger zeigt ihn als Vorschau.
     *
     * @param text    Text aus einer Sprachdatei
     * @param command Befehl, leer liefert reinen Text
     * @return die Komponente für Chat, Tablist oder Kick-Bildschirm
     */
    public static Component of(String text, String command) {
        Component component = of(text);
        if (command == null || command.isBlank()) {
            return component;
        }
        return component
                .clickEvent(ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(Component.text(command)));
    }

    /**
     * Gibt den Text unverändert aus, ohne MiniMessage zu deuten.
     *
     * @param text rohe Zeile, z. B. aus der Service-Konsole
     * @return die Komponente ohne Auszeichnung
     */
    public static Component literal(String text) {
        return Component.text(text == null ? "" : text);
    }
}
