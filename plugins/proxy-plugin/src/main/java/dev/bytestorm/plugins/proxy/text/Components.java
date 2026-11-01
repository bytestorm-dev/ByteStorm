package dev.bytestorm.plugins.proxy.text;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * Wandelt die Texte aus den Sprachdateien in Adventure-Komponenten.
 *
 * <p>Die Sprachdateien nutzen {@code &}-Codes ({@code &c}, {@code &l}) und
 * {@code \n} für Zeilenumbrüche — so bleiben Übersetzungen reiner Text und
 * brauchen kein Wissen über Komponenten. Diese Klasse ist die einzige Stelle,
 * die die Plattform-Text-API kennt.
 */
public final class Components {

    private static final LegacyComponentSerializer LEGACY =
            LegacyComponentSerializer.legacyAmpersand();

    private Components() {
    }

    /**
     * @param text Text aus einer Sprachdatei, darf {@code null} sein
     * @return die Komponente für Chat, Tablist oder Kick-Bildschirm
     */
    public static Component of(String text) {
        return LEGACY.deserialize(text == null ? "" : text);
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
}
