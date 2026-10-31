package dev.bytestorm.launcher.wizard;

import java.util.Objects;

/**
 * Eine auswählbare Antwort einer Frage.
 *
 * @param id          stabiler Wert, der gespeichert wird
 * @param label       Text in der Liste
 * @param description optionale Erläuterung unter dem Label
 */
public record QuestionOption(String id, String label, String description) {

    /**
     * @param id    stabiler Wert
     * @param label Text in der Liste
     * @param description optionale Erläuterung, darf {@code null} sein
     */
    public QuestionOption {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(label, "label");
        description = description == null || description.isBlank() ? null : description;
    }

    /**
     * @param id    stabiler Wert
     * @param label Text in der Liste
     * @return Option ohne Erläuterung
     */
    public static QuestionOption of(String id, String label) {
        return new QuestionOption(id, label, null);
    }
}
