package dev.bytestorm.launcher.wizard;

import java.util.List;

/**
 * Ein Fragebogen: Titel und eine geordnete Liste von Fragen (§5.1).
 *
 * <p>Fragen können voneinander abhängen — der Docker-Host erscheint nur bei der
 * Docker-Runtime, die REST-Einstellungen nur bei aktivem Modul. Diese Bedingung
 * steckt in {@link Question#visible} und wird immer gegen die aktuellen
 * Antworten ausgewertet.
 *
 * @param title        Überschrift im Rahmen
 * @param subtitle     eine Zeile unter der Überschrift
 * @param questions    Fragen in Anzeigereihenfolge
 */
public record Questionnaire(String title, String subtitle, List<Question> questions) {

    /**
     * @param title        Überschrift im Rahmen
     * @param subtitle     eine Zeile unter der Überschrift
     * @param questions    Fragen in Anzeigereihenfolge
     */
    public Questionnaire {
        title = title == null || title.isBlank() ? "Einrichtung" : title;
        subtitle = subtitle == null || subtitle.isBlank() ? null : subtitle;
        questions = List.copyOf(questions);
    }

    /**
     * @param answers aktuelle Antworten
     * @return die Fragen, die in diesem Zustand sichtbar sind
     */
    public List<Question> visible(Answers answers) {
        return questions.stream().filter(question -> question.visible(answers)).toList();
    }

    /**
     * @param id Frage-ID
     * @return die Frage
     * @throws IllegalArgumentException wenn es keine solche Frage gibt
     */
    public Question question(String id) {
        return questions.stream()
                .filter(question -> question.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unbekannte Frage: " + id));
    }

    /**
     * @param id Frage-ID
     * @return {@code true}, wenn die ID zu einer Frage gehört
     */
    public boolean contains(String id) {
        return id != null && questions.stream().anyMatch(question -> question.id().equals(id));
    }

    /**
     * @param answers aktuelle Antworten
     * @return die erste noch unbeantwortete sichtbare Frage, sonst die erste sichtbare
     */
    public Question firstOpen(Answers answers) {
        List<Question> visible = visible(answers);
        if (visible.isEmpty()) {
            throw new IllegalArgumentException("Der Fragebogen enthält keine sichtbare Frage");
        }
        return visible.stream()
                .filter(question -> !answers.has(question.id()))
                .findFirst()
                .orElse(visible.get(0));
    }

    /**
     * @param question Frage
     * @param answers  aktuelle Antworten
     * @return Position unter den sichtbaren Fragen, {@code -1} wenn ausgeblendet
     */
    public int indexOf(Question question, Answers answers) {
        return visible(answers).indexOf(question);
    }

    /**
     * @param answers aktuelle Antworten
     * @return {@code true}, wenn keine sichtbare Frage unbeantwortet ist
     */
    public boolean complete(Answers answers) {
        return visible(answers).stream().allMatch(question -> answers.has(question.id()));
    }
}
