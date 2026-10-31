package dev.bytestorm.launcher.wizard;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuestionnaireStateTest {

    private static Questionnaire form() {
        return new Questionnaire("Einrichtung", "Untertitel", List.of(
                Question.text("name", "Name").defaultValue("standard").build(),
                Question.select("runtime", "Runtime")
                        .option("PROCESS", "PROCESS")
                        .option("DOCKER", "DOCKER")
                        .defaultValue("PROCESS")
                        .build(),
                Question.text("docker", "Docker-Host")
                        .defaultValue("unix:///var/run/docker.sock")
                        .visibleWhen(answers -> "DOCKER".equals(answers.text("runtime")))
                        .build(),
                Question.confirm("rest", "REST aktivieren?").build(),
                Question.text("tags", "Tags").build(),
                Question.multi("modules", "Module")
                        .option("a", "Alpha")
                        .option("b", "Beta")
                        .build(),
                Question.secret("key", "API-Key")
                        .validate((value, answers) -> value.isBlank() ? "Key fehlt" : null)
                        .build()));
    }

    @Test
    void movingTheFocusNeverChangesAnAnswer() {
        QuestionnaireState state = new QuestionnaireState(form(), Answers.empty(), QuestionnaireNotice.none());

        assertTrue(state.editingText(), "die erste unbeantwortete Frage ist ein Textfeld");

        for (int step = 0; step < 8; step++) {
            state.move(1);
        }
        state.move(-2);
        state.first();
        state.last();

        assertTrue(state.answers().ids().isEmpty(), state.answers().toString());
    }

    @Test
    void expandingAndCollapsingKeepsAnswers() {
        QuestionnaireState state = new QuestionnaireState(form(), Answers.empty(), QuestionnaireNotice.none());
        state.append('A');
        state.append('b');
        assertEquals(1, state.answers().ids().size());

        state.move(1);
        state.enter();
        assertEquals("runtime", state.expanded());
        state.collapse();
        assertNull(state.expanded());
        state.move(1);
        state.expand();

        assertEquals("Ab", state.answers().text("name"));
    }

    @Test
    void selectingAnOptionStoresItsIdButNotTheFocus() {
        QuestionnaireState state = new QuestionnaireState(form(), Answers.empty(), QuestionnaireNotice.none());
        state.move(1);
        state.enter();
        assertEquals("runtime", state.expanded());
        assertEquals(QuestionnaireState.Kind.OPTION, state.focused().kind(), "Enter springt auf die erste Option");

        state.move(1);
        state.select();

        assertEquals("DOCKER", state.answers().text("runtime"));
        assertEquals(QuestionnaireState.Kind.OPTION, state.focused().kind());
        assertEquals(1, state.focused().optionIndex());
    }

    @Test
    void aMultiSelectTogglesItsValues() {
        Question multi = form().question("modules");
        QuestionnaireState state = new QuestionnaireState(
                new Questionnaire("Test", null, List.of(multi)), Answers.empty(), QuestionnaireNotice.none());

        assertEquals(QuestionnaireState.Kind.OPTION, state.focused().kind());
        state.select();
        assertEquals(List.of("a"), state.answers().all("modules"));
        state.move(1);
        state.select();
        assertEquals(List.of("a", "b"), state.answers().all("modules"));
        state.move(-1);
        state.select();
        assertEquals(List.of("b"), state.answers().all("modules"));
    }

    @Test
    void answeringOneQuestionCanRevealTheNext() {
        QuestionnaireState state = new QuestionnaireState(form(), Answers.empty(), QuestionnaireNotice.none());
        assertFalse(state.rows().stream().anyMatch(row -> "docker".equals(row.questionId())));

        state.move(1);
        state.enter();
        state.move(1);
        state.select();
        assertEquals("DOCKER", state.answers().text("runtime"));

        assertTrue(state.rows().stream().anyMatch(row -> row.kind() == QuestionnaireState.Kind.QUESTION
                && "docker".equals(row.questionId())), "der Docker-Host wird sichtbar");
    }

    @Test
    void submitJumpsToTheFirstInvalidQuestion() {
        QuestionnaireState state = new QuestionnaireState(form(), Answers.empty(), QuestionnaireNotice.none());

        state.last();
        assertEquals(QuestionnaireState.Kind.SUBMIT, state.focused().kind());
        assertFalse(state.enter(), "der fehlende API-Key verhindert das Abschicken");

        assertEquals("key", state.expanded());
        assertTrue(state.notice().present());
        assertEquals("key", state.notice().questionId());
        assertEquals(QuestionnaireState.Kind.FIELD, state.focused().kind());
    }

    @Test
    void typingAndBackspaceEditOnlyTheFocusedField() {
        QuestionnaireState state = new QuestionnaireState(form(), Answers.empty(), QuestionnaireNotice.none());

        state.append('a');
        state.append('b');
        state.append('c');
        state.backspace();

        assertEquals("ab", state.answers().text("name"));
        assertFalse(state.notice().present());
    }

    @Test
    void aNoticeOpensItsQuestionOnStart() {
        QuestionnaireNotice notice = new QuestionnaireNotice("name", "MongoDB nicht erreichbar");
        QuestionnaireState state = new QuestionnaireState(form(), Answers.empty(), notice);

        assertEquals("name", state.expanded());
        assertEquals(QuestionnaireState.Kind.FIELD, state.focused().kind());
        assertEquals("MongoDB nicht erreichbar", state.notice().message());
    }
}
