package dev.bytestorm.launcher.wizard;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Ein- und Ausgabe des Erststart-Wizards.
 *
 * <p>Die Abstraktion erlaubt Tests ohne Terminal und hält die Ablauflogik frei
 * von JLine. Implementierungen müssen bei Strg+C/EOF eine
 * {@link WizardAbortedException} werfen.
 *
 * <p>{@link #fill} ist der neue Einstieg: Es füllt einen ganzen Fragebogen aus
 * und liefert die Antworten gesammelt zurück. Die Voreinstellung fragt die Fragen
 * nacheinander am Prompt ab — das ist der Rückfall für Terminals ohne
 * Cursor-Steuerung (§5.1). {@link JLineWizardIo} überschreibt sie mit dem
 * interaktiven Panel.
 */
public interface WizardIo {

    /**
     * @param prompt       Frage
     * @param defaultValue Wert bei leerer Eingabe
     * @return die Antwort oder {@code defaultValue}
     */
    String ask(String prompt, String defaultValue);

    /**
     * @param prompt Frage
     * @return die Eingabe, ohne Echo
     */
    String askSecret(String prompt);

    /**
     * @param prompt       Frage
     * @param options      erlaubte Werte
     * @param defaultValue Wert bei leerer Eingabe
     * @return einer der {@code options}
     */
    String choose(String prompt, List<String> options, String defaultValue);

    /**
     * @param prompt     Frage
     * @param defaultYes Antwort bei leerer Eingabe
     * @return {@code true} für ja
     */
    boolean confirm(String prompt, boolean defaultYes);

    /**
     * Gibt einen Hinweis aus.
     *
     * @param text Meldung
     */
    void message(String text);

    /**
     * Fragt alle sichtbaren Fragen des Fragebogens ab.
     *
     * <p>Fehlerhafte Eingaben werden sofort erneut abgefragt; Fragen, die erst
     * durch eine andere Antwort sichtbar werden, kommen in derselben Runde dazu.
     * {@code initial} füllt bereits beantwortete Fragen vor, damit ein zweiter
     * Lauf nach einem fehlgeschlagenen Verbindungstest nicht alles neu verlangt.
     *
     * @param form    Fragebogen
     * @param initial bereits gegebene Antworten
     * @param notice  Meldung, die den Lauf ausgelöst hat
     * @return die gesammelten Antworten
     */
    default Answers fill(Questionnaire form, Answers initial, QuestionnaireNotice notice) {
        Objects.requireNonNull(form, "form");
        Answers answers = initial == null ? Answers.empty() : initial;
        if (notice != null && notice.present()) {
            message(notice.message());
        }
        for (Question question : form.questions()) {
            if (!question.visible(answers)) {
                continue;
            }
            answers = ask(question, answers);
        }
        return answers;
    }

    /**
     * Fragt eine einzelne Frage so lange, bis ihr Wert die Prüfung besteht.
     */
    private Answers ask(Question question, Answers answers) {
        while (true) {
            Answers updated = answered(question, answers);
            String error = question.validate(question.effective(updated), updated);
            if (error == null) {
                return updated;
            }
            message(error);
        }
    }

    private Answers answered(Question question, Answers answers) {
        return switch (question.type()) {
            case TEXT -> answers.with(question.id(),
                    ask(question.title(), question.effective(answers)));
            case SECRET -> {
                String current = answers.text(question.id());
                String prompt = current.isBlank()
                        ? question.title()
                        : question.title() + " [leer = bisheriger Wert]";
                String typed = askSecret(prompt);
                yield answers.with(question.id(), typed.isBlank() ? current : typed);
            }
            case SINGLE_SELECT -> {
                List<String> labels = question.options().stream().map(QuestionOption::label).toList();
                String current = labelOf(question, question.currentId(answers));
                String chosen = choose(question.title(), labels, current);
                yield answers.with(question.id(), idOf(question, chosen));
            }
            case MULTI_SELECT -> {
                List<String> selected = question.options().stream()
                        .filter(option -> question.isSelected(answers, option))
                        .map(QuestionOption::label)
                        .toList();
                String typed = ask(question.title() + " (Komma-getrennt)",
                        String.join(", ", selected));
                List<String> ids = new ArrayList<>();
                for (String part : typed.split(",")) {
                    String label = part.trim();
                    if (!label.isEmpty()) {
                        ids.add(idOf(question, label));
                    }
                }
                yield answers.withAll(question.id(), ids);
            }
            case CONFIRM -> answers.with(question.id(),
                    confirm(question.title(), answers.flag(question.id())) ? Answers.YES : Answers.NO);
        };
    }

    private static String labelOf(Question question, String optionId) {
        return question.options().stream()
                .filter(option -> option.id().equals(optionId))
                .map(QuestionOption::label)
                .findFirst()
                .orElse(optionId);
    }

    private static String idOf(Question question, String label) {
        return question.options().stream()
                .filter(option -> option.label().equalsIgnoreCase(label))
                .map(QuestionOption::id)
                .findFirst()
                .orElse(label);
    }
}
