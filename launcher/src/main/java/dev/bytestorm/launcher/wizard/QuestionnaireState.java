package dev.bytestorm.launcher.wizard;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Zustand des Einrichtungs-Formulars: Antworten, Fokus und aufgeklappte Frage (§5.1).
 *
 * <p>Der Zustand ist die einzige Stelle, die Antworten verändert. Wer den Fokus
 * bewegt, ändert keine Antwort; wer eine Frage auf- oder zuklappt, verliert keine
 * Antwort. Genau diese beiden Zusagen machen den Fragebogen vorhersehbar.
 *
 * <p>Die Zeilenliste wird aus dem Fragebogen abgeleitet: jede sichtbare Frage ist
 * eine Kopfzeile, die aufgeklappte Frage bringt ihre Optionen beziehungsweise ihr
 * Eingabefeld mit, am Ende steht die Schaltfläche zum Speichern. Dadurch passt sich
 * der Fokus automatisch an, wenn eine Antwort weitere Fragen ein- oder ausblendet.
 */
public final class QuestionnaireState {

    /** Art einer Zeile im Formular. */
    public enum Kind {

        /** Kopfzeile einer Frage. */
        QUESTION,

        /** Auswählbare Option einer aufgeklappten Frage. */
        OPTION,

        /** Eingabefeld einer Text- oder Passwortfrage. */
        FIELD,

        /** Schaltfläche zum Abschicken. */
        SUBMIT
    }

    /**
     * Eine Zeile des Formulars.
     *
     * @param kind        Art der Zeile
     * @param questionId  zugehörige Frage, bei {@link Kind#SUBMIT} {@code null}
     * @param optionIndex Position in {@link Question#options()}, sonst {@code -1}
     */
    public record Row(Kind kind, String questionId, int optionIndex) {
    }

    private static final int MAX_ANSWER_LENGTH = 512;

    private final Questionnaire form;
    private Answers answers;
    private String expanded;
    private int focus;
    private QuestionnaireNotice notice;

    /**
     * @param form    Fragebogen
     * @param initial bereits gegebene Antworten (leer beim ersten Lauf)
     * @param notice  Meldung, die den Lauf ausgelöst hat
     */
    public QuestionnaireState(Questionnaire form, Answers initial, QuestionnaireNotice notice) {
        this.form = Objects.requireNonNull(form, "form");
        this.answers = initial == null ? Answers.empty() : initial;
        this.notice = notice == null ? QuestionnaireNotice.none() : notice;
        this.expanded = initialFocus();
        this.focus = firstChildOrHeader(expanded);
    }

    /** @return der Fragebogen */
    public Questionnaire form() {
        return form;
    }

    /** @return die aktuellen Antworten */
    public Answers answers() {
        return answers;
    }

    /** @return die aktuelle Meldung, nie {@code null} */
    public QuestionnaireNotice notice() {
        return notice;
    }

    /** @return ID der aufgeklappten Frage oder {@code null} */
    public String expanded() {
        return expanded;
    }

    /**
     * @return alle Zeilen in Anzeigereihenfolge
     */
    public List<Row> rows() {
        List<Row> rows = new ArrayList<>();
        for (Question question : form.visible(answers)) {
            rows.add(new Row(Kind.QUESTION, question.id(), -1));
            if (question.id().equals(expanded)) {
                switch (question.type()) {
                    case TEXT, SECRET -> rows.add(new Row(Kind.FIELD, question.id(), -1));
                    default -> {
                        for (int index = 0; index < question.options().size(); index++) {
                            rows.add(new Row(Kind.OPTION, question.id(), index));
                        }
                    }
                }
            }
        }
        rows.add(new Row(Kind.SUBMIT, null, -1));
        return rows;
    }

    /**
     * @return der auf einen gültigen Bereich begrenzte Fokus
     */
    public int focusIndex() {
        int size = rows().size();
        return Math.max(0, Math.min(focus, size - 1));
    }

    /**
     * @return die fokussierte Zeile
     */
    public Row focused() {
        List<Row> rows = rows();
        return rows.get(Math.max(0, Math.min(focus, rows.size() - 1)));
    }

    /**
     * @return {@code true}, wenn der Fokus in einem Eingabefeld steht
     */
    public boolean editingText() {
        return focused().kind() == Kind.FIELD;
    }

    /**
     * @param id Frage-ID
     * @return Position der Frage unter den sichtbaren Fragen, {@code -1} wenn versteckt
     */
    public int questionNumber(String id) {
        List<Question> visible = form.visible(answers);
        for (int index = 0; index < visible.size(); index++) {
            if (visible.get(index).id().equals(id)) {
                return index + 1;
            }
        }
        return -1;
    }

    /**
     * Bewegt den Fokus um eine Zeile. Die Antworten bleiben unverändert.
     *
     * @param delta Richtung, z. B. {@code -1} oder {@code 1}
     */
    public void move(int delta) {
        int size = rows().size();
        focus = Math.max(0, Math.min(focusIndex() + delta, size - 1));
    }

    /**
     * Springt zur ersten Zeile.
     */
    public void first() {
        focus = 0;
    }

    /**
     * Springt zur letzten Zeile.
     */
    public void last() {
        focus = rows().size() - 1;
    }

    /**
     * Springt zur nächsten oder vorherigen Frage-Kopfzeile ({@code Tab}).
     *
     * @param forward {@code true} für vorwärts
     */
    public void moveQuestion(boolean forward) {
        List<Row> rows = rows();
        int start = focusIndex() + (forward ? 1 : -1);
        for (int index = start; index >= 0 && index < rows.size(); index += forward ? 1 : -1) {
            if (rows.get(index).kind() == Kind.QUESTION) {
                focus = index;
                return;
            }
        }
    }

    /**
     * Klappt die fokussierte Frage auf und setzt den Fokus auf ihre erste Option.
     */
    public void expand() {
        Row row = focused();
        if (row.kind() != Kind.QUESTION) {
            return;
        }
        expanded = row.questionId();
        focus = firstChildOrHeader(row.questionId());
    }

    /**
     * Klappt die fokussierte Frage auf oder zu ({@code Leertaste}).
     */
    public void toggleQuestion() {
        Row row = focused();
        if (row.kind() != Kind.QUESTION) {
            return;
        }
        if (row.questionId().equals(expanded)) {
            collapse();
            return;
        }
        expand();
    }

    /**
     * Klappt die aufgeklappte Frage zu und setzt den Fokus auf ihre Kopfzeile.
     *
     * @return {@code true}, wenn etwas zuzuklappen war
     */
    public boolean collapse() {
        if (expanded == null) {
            return false;
        }
        String collapsed = expanded;
        expanded = null;
        List<Row> rows = rows();
        for (int index = 0; index < rows.size(); index++) {
            Row row = rows.get(index);
            if (row.kind() == Kind.QUESTION && collapsed.equals(row.questionId())) {
                focus = index;
                return true;
            }
        }
        focus = focusIndex();
        return true;
    }

    /**
     * Wählt die fokussierte Option aus beziehungsweise schaltet sie um.
     */
    public void select() {
        Row row = focused();
        if (row.kind() != Kind.OPTION) {
            return;
        }
        Question question = form.question(row.questionId());
        QuestionOption option = question.options().get(row.optionIndex());
        answers = question.type() == QuestionType.MULTI_SELECT
                ? answers.toggle(question.id(), option.id())
                : answers.with(question.id(), option.id());
        clearNotice(question.id());
    }

    /**
     * Behandelt {@code Enter}: Frage aufklappen, Option wählen, Feld verlassen
     * oder abschicken.
     *
     * @return {@code true}, wenn das Formular gültig abgeschickt wurde
     */
    public boolean enter() {
        return switch (focused().kind()) {
            case QUESTION -> {
                expand();
                yield false;
            }
            case OPTION -> {
                select();
                yield false;
            }
            case FIELD -> {
                move(1);
                yield false;
            }
            case SUBMIT -> submit();
        };
    }

    /**
     * Prüft alle sichtbaren Fragen und schickt das Formular ab, wenn keine
     * Antwort fehlerhaft ist. Sonst wird die erste fehlerhafte Frage aufgeklappt
     * und der Fokus auf sie gesetzt.
     *
     * @return {@code true}, wenn alle Antworten gültig sind
     */
    public boolean submit() {
        for (Question question : form.visible(answers)) {
            String error = question.validate(question.effective(answers), answers);
            if (error != null) {
                notice = new QuestionnaireNotice(question.id(), error);
                expanded = question.id();
                focus = firstChildOrHeader(question.id());
                return false;
            }
        }
        return true;
    }

    /**
     * Hängt ein Zeichen an das fokussierte Eingabefeld an.
     *
     * @param character Zeichen
     */
    public void append(char character) {
        Row row = focused();
        if (row.kind() != Kind.FIELD || character < 32) {
            return;
        }
        Question question = form.question(row.questionId());
        String value = answers.text(question.id());
        if (value.length() >= MAX_ANSWER_LENGTH) {
            return;
        }
        answers = answers.with(question.id(), value + character);
        clearNotice(question.id());
    }

    /**
     * Löscht das letzte Zeichen des fokussierten Eingabefelds.
     */
    public void backspace() {
        Row row = focused();
        if (row.kind() != Kind.FIELD) {
            return;
        }
        Question question = form.question(row.questionId());
        String value = answers.text(question.id());
        if (value.isEmpty()) {
            return;
        }
        answers = answers.with(question.id(), value.substring(0, value.length() - 1));
        clearNotice(question.id());
    }

    /**
     * Zeigt eine Meldung ohne Bezug zu einer Frage an.
     *
     * @param message Text
     */
    public void notify(String message) {
        notice = new QuestionnaireNotice(null, message);
    }

    /**
     * Entfernt die aktuelle Meldung.
     */
    public void clearNotice() {
        notice = QuestionnaireNotice.none();
    }

    private void clearNotice(String questionId) {
        if (notice.present() && questionId.equals(notice.questionId())) {
            notice = QuestionnaireNotice.none();
        }
    }

    private String initialFocus() {
        if (notice.present() && form.contains(notice.questionId())) {
            Question target = form.question(notice.questionId());
            if (target.visible(answers)) {
                return target.id();
            }
        }
        return form.firstOpen(answers).id();
    }

    private int firstChildOrHeader(String questionId) {
        List<Row> rows = rows();
        for (int index = 0; index < rows.size(); index++) {
            Row row = rows.get(index);
            if (row.kind() == Kind.QUESTION && questionId.equals(row.questionId())) {
                boolean child = index + 1 < rows.size()
                        && questionId.equals(rows.get(index + 1).questionId());
                return child ? index + 1 : index;
            }
        }
        return 0;
    }
}
