package dev.bytestorm.launcher.wizard;

import dev.bytestorm.cli.Theme;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuestionnaireRendererTest {

    @BeforeEach
    void plain() {
        Theme.forcePlain(false);
        Theme.setEnabled(false);
    }

    @AfterEach
    void restore() {
        Theme.forcePlain(false);
        Theme.setEnabled(false);
    }

    @Test
    void drawsATitledPanelWithTheFirstQuestionOpen() {
        Questionnaire form = form();
        QuestionnaireState state = new QuestionnaireState(form, Answers.empty(), QuestionnaireNotice.none());

        List<String> lines = QuestionnaireRenderer.render(form, state, 80, 24);

        assertEquals(80, Theme.stripAnsi(lines.get(0)).length());
        assertTrue(lines.get(0).startsWith("+"), lines.get(0));
        assertTrue(lines.get(0).contains("Einrichtung"), lines.get(0));
        assertTrue(lines.stream().anyMatch(line -> line.contains("v 1. MongoDB-URI")), lines.toString());
        assertTrue(lines.stream().anyMatch(line -> line.contains("mongodb://localhost:27017")),
                "der Vorschlagswert steht im Eingabefeld");
        assertTrue(lines.stream().anyMatch(line -> line.contains("Speichern")), lines.toString());
        assertTrue(lines.stream().anyMatch(line -> line.contains("Esc")), "die Fußzeile nennt den Ausstieg");
        for (String line : lines) {
            assertEquals(80, Theme.stripAnsi(line).length(), line);
        }
    }

    @Test
    void aCollapsedQuestionShowsItsAnswer() {
        Questionnaire form = form();
        Answers answers = Answers.empty().with("mongo.uri", "mongodb://db:27017");
        QuestionnaireState state = new QuestionnaireState(form, answers, QuestionnaireNotice.none());

        List<String> lines = QuestionnaireRenderer.render(form, state, 80, 24);

        assertTrue(lines.stream().anyMatch(line -> line.contains("-> \"mongodb://db:27017\"")),
                lines.toString());
        assertTrue(lines.stream().anyMatch(line -> line.contains("v 2. Runtime")), lines.toString());
        assertTrue(lines.stream().anyMatch(line -> line.contains("(*) PROCESS")),
                "die Vorgabe ist ausgewählt");
    }

    @Test
    void focusIsHighlightedButTheAnswerStays() {
        Theme.setEnabled(true);
        Questionnaire form = form();
        QuestionnaireState state = new QuestionnaireState(
                new Questionnaire("Einrichtung", null, form.questions()), Answers.empty(), QuestionnaireNotice.none());
        List<String> before = QuestionnaireRenderer.render(form, state, 80, 20);
        assertEquals(0, state.answers().ids().size());

        state.move(1);
        state.expand();

        assertEquals(0, state.answers().ids().size(), "Fokus verschieben wählt nichts aus");
        List<String> after = QuestionnaireRenderer.render(form, state, 80, 20);
        assertTrue(after.stream().anyMatch(line -> line.contains("\u001B[42m")),
                "die Fokuszeile ist hinterlegt");
        assertTrue(before.stream().noneMatch(line -> line.contains("◉ 1.")), before.toString());
    }

    @Test
    void aSecretIsNeverRenderedInPlainText() {
        Questionnaire form = new Questionnaire("Einrichtung", null, List.of(
                Question.secret("key", "API-Key").build()));
        Answers answers = Answers.empty().with("key", "geheim-1234");
        QuestionnaireState state = new QuestionnaireState(form, answers, QuestionnaireNotice.none());

        String rendered = String.join("\n", QuestionnaireRenderer.render(form, state, 80, 12));

        assertFalse(rendered.contains("geheim-1234"), rendered);
        assertTrue(rendered.contains("***********"), rendered);
    }

    @Test
    void longDescriptionsWrapInsideTheBorder() {
        Questionnaire form = new Questionnaire("Einrichtung", "Eine Zeile.", List.of(
                Question.select("runtime", "Runtime")
                        .description("Diese Erläuterung ist absichtlich so lang, dass sie über mehrere Zeilen "
                                + "umgebrochen werden muss, ohne den Rahmen zu verlassen.")
                        .option("PROCESS", "PROCESS", "Ein eigener Java-Prozess mit eigener Arbeitskopie des Templates.")
                        .option("DOCKER", "DOCKER", "Ein Container über den Docker-Host.")
                        .build()));
        QuestionnaireState state = new QuestionnaireState(form, Answers.empty(), QuestionnaireNotice.none());

        List<String> lines = QuestionnaireRenderer.render(form, state, 60, 24);

        for (String line : lines) {
            assertTrue(Theme.stripAnsi(line).length() <= 60, line);
        }
        assertTrue(lines.stream().anyMatch(line -> line.contains("Umgebrochen") || line.contains("umgebrochen")),
                lines.toString());
    }

    @Test
    void aNarrowTerminalDropsTheSideBorders() {
        Questionnaire form = form();
        QuestionnaireState state = new QuestionnaireState(form, Answers.empty(), QuestionnaireNotice.none());

        List<String> lines = QuestionnaireRenderer.render(form, state, 40, 24);

        assertTrue(lines.stream().noneMatch(line -> line.contains("|")), lines.toString());
        assertTrue(lines.stream().anyMatch(line -> line.contains("Pfeiltasten · Enter · Esc")),
                "der Hinweis wird kürzer");
        for (String line : lines) {
            assertEquals(40, Theme.stripAnsi(line).length(), line);
        }
    }

    @Test
    void aShortTerminalKeepsTheFocusedRowVisible() {
        Questionnaire form = new Questionnaire("Einrichtung", null, List.of(
                Question.text("a", "Frage A").build(),
                Question.text("b", "Frage B").build(),
                Question.text("c", "Frage C").build(),
                Question.text("d", "Frage D").build(),
                Question.text("e", "Frage E").build(),
                Question.text("f", "Frage F").build()));
        QuestionnaireState state = new QuestionnaireState(form, Answers.empty(), QuestionnaireNotice.none());
        state.last();

        List<String> lines = QuestionnaireRenderer.render(form, state, 60, 8);

        assertTrue(lines.size() <= 8, "das Panel bleibt in der Terminalhöhe: " + lines);
        assertTrue(lines.stream().anyMatch(line -> line.contains("Speichern")),
                "die Schaltfläche bleibt sichtbar: " + lines);
        assertFalse(lines.stream().anyMatch(line -> line.contains("mehr")),
                "am Ende folgt nichts mehr: " + lines);
    }

    @Test
    void aBoxThatDoesNotFitSaysThatMoreFollows() {
        Questionnaire form = new Questionnaire("Einrichtung", null, List.of(
                Question.text("a", "Frage A").build(),
                Question.text("b", "Frage B").build(),
                Question.text("c", "Frage C").build(),
                Question.text("d", "Frage D").build(),
                Question.text("e", "Frage E").build(),
                Question.text("f", "Frage F").build()));
        QuestionnaireState state = new QuestionnaireState(form, Answers.empty(), QuestionnaireNotice.none());

        List<String> lines = QuestionnaireRenderer.render(form, state, 60, 8);

        assertTrue(lines.size() <= 8, lines.toString());
        assertTrue(lines.stream().anyMatch(line -> line.contains("mehr")),
                "sonst wirkt das Formular zu Ende: " + lines);
    }

    @Test
    void anErrorAppearsUnderItsQuestion() {
        Questionnaire form = form();
        QuestionnaireNotice notice = new QuestionnaireNotice("mongo.uri", "MongoDB nicht erreichbar: Verbindung abgelehnt");
        QuestionnaireState state = new QuestionnaireState(form, Answers.empty(), notice);

        List<String> lines = QuestionnaireRenderer.render(form, state, 80, 24);

        assertTrue(lines.stream().anyMatch(line -> line.contains("x MongoDB nicht erreichbar")),
                lines.toString());
    }

    private static Questionnaire form() {
        return new Questionnaire("Einrichtung", "Die Antworten landen in config/cloud.json.", List.of(
                Question.text("mongo.uri", "MongoDB-URI")
                        .description("Adresse der Datenbank.")
                        .defaultValue("mongodb://localhost:27017")
                        .build(),
                Question.select("runtime", "Runtime")
                        .option("PROCESS", "PROCESS", "Eigener Prozess.")
                        .option("DOCKER", "DOCKER", "Container.")
                        .defaultValue("PROCESS")
                        .build(),
                Question.secret("key", "API-Key").build(),
                Question.confirm("rest", "REST aktivieren?").build()));
    }
}
