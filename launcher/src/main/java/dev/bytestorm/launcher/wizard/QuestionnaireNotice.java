package dev.bytestorm.launcher.wizard;

/**
 * Meldung, die über dem Formular erscheint — etwa ein fehlgeschlagener
 * Verbindungstest oder ein Hinweis zum Abbrechen.
 *
 * <p>Gehört die Meldung zu einer Frage, wird sie direkt darunter angezeigt und
 * die Frage aufgeklappt; sonst steht sie am unteren Rand des Formulars. So ist
 * ohne Maus erkennbar, was zu tun ist.
 *
 * @param questionId betroffene Frage oder {@code null}
 * @param message    Text der Meldung; leer bedeutet "keine Meldung"
 */
public record QuestionnaireNotice(String questionId, String message) {

    /**
     * @param questionId betroffene Frage oder {@code null}
     * @param message    Text der Meldung
     */
    public QuestionnaireNotice {
        message = message == null ? "" : message;
        questionId = questionId == null || questionId.isBlank() ? null : questionId;
    }

    /**
     * @return keine Meldung
     */
    public static QuestionnaireNotice none() {
        return new QuestionnaireNotice(null, "");
    }

    /**
     * @return {@code true}, wenn es etwas anzuzeigen gibt
     */
    public boolean present() {
        return !message.isBlank();
    }
}
