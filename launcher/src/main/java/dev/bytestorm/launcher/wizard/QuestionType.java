package dev.bytestorm.launcher.wizard;

/**
 * Art einer Frage im Einrichtungs-Formular.
 *
 * <p>Die Art entscheidet, wie die Frage aufgeklappt aussieht und wie ein
 * Tastatur-Ereignis die Antwort verändert (§5.1).
 */
public enum QuestionType {

    /** Genau eine Option; gespeichert wird die ID der Option. */
    SINGLE_SELECT,

    /** Beliebig viele Optionen; gespeichert werden die IDs. */
    MULTI_SELECT,

    /** Freie Texteingabe. */
    TEXT,

    /** Freie Texteingabe, die nie im Klartext angezeigt wird (§20). */
    SECRET,

    /** Ja/Nein-Entscheidung, gespeichert als {@code ja} oder {@code nein}. */
    CONFIRM
}
