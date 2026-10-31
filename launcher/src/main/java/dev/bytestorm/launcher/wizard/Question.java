package dev.bytestorm.launcher.wizard;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Eine Frage des Einrichtungs-Formulars (§5.1).
 *
 * <p>Die Frage kennt ihren Typ, ihre Optionen, ob sie Pflicht ist und wann sie
 * überhaupt sichtbar ist. Sie kennt dagegen weder Terminal noch Tastatur — das
 * Rendering liegt in {@link QuestionnaireRenderer}, die Ablauflogik im
 * {@link QuestionnaireState}. So bleibt die Frage testbar und auch im
 * sequenziellen Modus verwendbar.
 *
 * <p>Eine Frage mit {@link QuestionType#SECRET} liefert in {@link #summary} nie
 * den Wert, nur die Information, dass einer gesetzt ist (§20).
 */
public final class Question {

    /**
     * Prüft einen Wert im Kontext der übrigen Antworten — nötig, wenn eine Frage
     * erst durch eine andere Pflicht wird (etwa ein Passwort zu einem Benutzer).
     */
    @FunctionalInterface
    public interface Validator {

        /**
         * @param value   der eingegebene Wert, leer wenn nichts eingegeben wurde
         * @param answers alle bisherigen Antworten
         * @return Fehlermeldung oder {@code null}, wenn der Wert in Ordnung ist
         */
        String validate(String value, Answers answers);
    }

    private static final QuestionOption YES = QuestionOption.of(Answers.YES, "Ja");
    private static final QuestionOption NO = QuestionOption.of(Answers.NO, "Nein");

    private final String id;
    private final String title;
    private final String description;
    private final QuestionType type;
    private final List<QuestionOption> options;
    private final boolean required;
    private final String defaultValue;
    private final Predicate<Answers> visible;
    private final Validator validator;

    private Question(Builder builder) {
        this.id = builder.id;
        this.title = builder.title;
        this.description = builder.description;
        this.type = builder.type;
        if (builder.type == QuestionType.CONFIRM) {
            this.options = List.of(YES, NO);
        } else {
            this.options = List.copyOf(builder.options);
        }
        this.required = builder.required;
        this.defaultValue = builder.defaultValue;
        this.visible = builder.visible;
        this.validator = builder.validator;
    }

    /**
     * @param id    stabile ID, auch der Schlüssel im Ergebnis
     * @param title Frage
     * @return eine Textfrage
     */
    public static Builder text(String id, String title) {
        return new Builder(id, title, QuestionType.TEXT);
    }

    /**
     * @param id    stabile ID
     * @param title Frage
     * @return eine Frage mit verdeckter Eingabe
     */
    public static Builder secret(String id, String title) {
        return new Builder(id, title, QuestionType.SECRET);
    }

    /**
     * @param id    stabile ID
     * @param title Frage
     * @return eine Auswahl mit genau einer Antwort
     */
    public static Builder select(String id, String title) {
        return new Builder(id, title, QuestionType.SINGLE_SELECT);
    }

    /**
     * @param id    stabile ID
     * @param title Frage
     * @return eine Auswahl mit beliebig vielen Antworten
     */
    public static Builder multi(String id, String title) {
        return new Builder(id, title, QuestionType.MULTI_SELECT);
    }

    /**
     * @param id    stabile ID
     * @param title Frage
     * @return eine Ja/Nein-Frage
     */
    public static Builder confirm(String id, String title) {
        return new Builder(id, title, QuestionType.CONFIRM);
    }

    /** @return stabile ID */
    public String id() {
        return id;
    }

    /** @return Frage, wie sie in der Übersicht steht */
    public String title() {
        return title;
    }

    /** @return Erläuterung unter der aufgeklappten Frage oder {@code null} */
    public String description() {
        return description;
    }

    /** @return Art der Frage */
    public QuestionType type() {
        return type;
    }

    /** @return Optionen; bei Ja/Nein-Fragen {@code Ja}/{@code Nein} */
    public List<QuestionOption> options() {
        return options;
    }

    /** @return {@code true}, wenn die Frage nicht leer bleiben darf */
    public boolean required() {
        return required;
    }

    /** @return Vorschlagswert für Textfragen oder {@code null} */
    public String defaultValue() {
        return defaultValue;
    }

    /**
     * @param answers bisherige Antworten
     * @return {@code true}, wenn die Frage in diesem Zustand angezeigt wird
     */
    public boolean visible(Answers answers) {
        return visible == null || visible.test(answers);
    }

    /**
     * @param answers bisherige Antworten
     * @return der wirksame Wert inklusive Vorschlagswert für Textfragen
     */
    public String effective(Answers answers) {
        String value = answers.text(id);
        if (value.isBlank() && defaultValue != null) {
            return defaultValue;
        }
        return value;
    }

    /**
     * @param answers bisherige Antworten
     * @return ausgewählte Option-ID inklusive Vorgabe ({@code nein}, erster Wert)
     */
    public String currentId(Answers answers) {
        String value = answers.text(id);
        if (!value.isBlank()) {
            return value;
        }
        if (type == QuestionType.CONFIRM) {
            return Answers.NO;
        }
        if (defaultValue != null) {
            return defaultValue;
        }
        return options.isEmpty() ? "" : options.get(0).id();
    }

    /**
     * @param answers bisherige Antworten
     * @param option  zu prüfende Option
     * @return {@code true}, wenn die Option gewählt ist
     */
    public boolean isSelected(Answers answers, QuestionOption option) {
        if (type == QuestionType.MULTI_SELECT) {
            return answers.all(id).contains(option.id());
        }
        return option.id().equals(currentId(answers));
    }

    /**
     * @param answers bisherige Antworten
     * @return Kurzfassung für die zugeklappte Frage
     */
    public String summary(Answers answers) {
        return switch (type) {
            case SINGLE_SELECT -> labelOf(currentId(answers));
            case MULTI_SELECT -> multiSummary(answers);
            case CONFIRM -> answers.flag(id) ? "Ja" : "Nein";
            case TEXT -> effective(answers).isBlank() ? "(leer)" : effective(answers);
            case SECRET -> answers.text(id).isBlank() ? "(leer)" : "(gesetzt)";
        };
    }

    /**
     * @param value   eingegebener Wert
     * @param answers bisherige Antworten
     * @return Fehlermeldung oder {@code null}
     */
    public String validate(String value, Answers answers) {
        String effectiveValue = value == null ? "" : value;
        if (required && effectiveValue.isBlank()) {
            return "Pflichtfeld — bitte ausfüllen.";
        }
        return validator == null ? null : validator.validate(effectiveValue, answers);
    }

    private String multiSummary(Answers answers) {
        List<String> selected = options.stream()
                .filter(option -> isSelected(answers, option))
                .map(QuestionOption::label)
                .toList();
        return selected.isEmpty() ? "(offen)" : String.join(", ", selected);
    }

    private String labelOf(String optionId) {
        return options.stream()
                .filter(option -> option.id().equals(optionId))
                .map(QuestionOption::label)
                .findFirst()
                .orElse("(offen)");
    }

    /**
     * Schreibt eine Frage lesbar zusammen. Die Optionen und der Vorschlagswert
     * helfen, Fehler in der Ablauflogik zu finden.
     *
     * @return Kurzbeschreibung der Frage
     */
    @Override
    public String toString() {
        return "Question[" + id + ", " + type + "]";
    }

    /** Baut eine unveränderliche {@link Question}. */
    public static final class Builder {

        private final String id;
        private final String title;
        private final QuestionType type;
        private final List<QuestionOption> options = new ArrayList<>();
        private String description;
        private boolean required;
        private String defaultValue;
        private Predicate<Answers> visible;
        private Validator validator;

        private Builder(String id, String title, QuestionType type) {
            this.id = Objects.requireNonNull(id, "id");
            this.title = Objects.requireNonNull(title, "title");
            this.type = type;
        }

        /**
         * @param value Erläuterung unter der aufgeklappten Frage
         * @return dieser Builder
         */
        public Builder description(String value) {
            this.description = value;
            return this;
        }

        /**
         * @param id    Wert der Option
         * @param label Text in der Liste
         * @return dieser Builder
         */
        public Builder option(String id, String label) {
            options.add(QuestionOption.of(id, label));
            return this;
        }

        /**
         * @param id          Wert der Option
         * @param label       Text in der Liste
         * @param description Erläuterung unter dem Label
         * @return dieser Builder
         */
        public Builder option(String id, String label, String description) {
            options.add(new QuestionOption(id, label, description));
            return this;
        }

        /**
         * @param value Vorschlagswert; erscheint als Platzhalter und gilt bei leerer Eingabe
         * @return dieser Builder
         */
        public Builder defaultValue(String value) {
            this.defaultValue = value;
            return this;
        }

        /**
         * @param value {@code true}, wenn die Frage nicht leer bleiben darf
         * @return dieser Builder
         */
        public Builder required(boolean value) {
            this.required = value;
            return this;
        }

        /**
         * @param condition Bedingung, unter der die Frage erscheint
         * @return dieser Builder
         */
        public Builder visibleWhen(Predicate<Answers> condition) {
            this.visible = condition;
            return this;
        }

        /**
         * @param check zusätzliche Prüfung des Werts
         * @return dieser Builder
         */
        public Builder validate(Validator check) {
            this.validator = check;
            return this;
        }

        /**
         * @return die fertige Frage
         */
        public Question build() {
            if (type != QuestionType.CONFIRM && options.isEmpty()
                    && type != QuestionType.TEXT && type != QuestionType.SECRET) {
                throw new IllegalStateException("Frage " + id + " braucht Optionen");
            }
            if (defaultValue != null && type != QuestionType.TEXT && type != QuestionType.SINGLE_SELECT) {
                throw new IllegalStateException("Frage " + id + " kann keinen Vorschlagswert tragen");
            }
            return new Question(this);
        }
    }
}
