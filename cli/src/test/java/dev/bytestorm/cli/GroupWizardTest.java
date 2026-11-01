package dev.bytestorm.cli;

import dev.bytestorm.proto.ServiceMode;
import dev.bytestorm.proto.ServiceType;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GroupWizardTest {

    @Test
    void serverDraftCollectsEveryAnswer() {
        Scripted prompt = new Scripted("SERVER", "DYNAMIC", "paper", "1.21.4", "2", "5", "4096",
                "250", "j", "3", "ALL, Lobby , ALL");

        GroupWizard.Draft draft = GroupWizard.collect(prompt, "Lobby").orElseThrow();

        assertEquals(ServiceType.SERVER, draft.type());
        assertEquals(ServiceMode.DYNAMIC, draft.mode());
        assertEquals("paper", draft.platform());
        assertEquals("1.21.4", draft.version());
        assertEquals(2, draft.minServices());
        assertEquals(5, draft.maxServices());
        assertEquals(4096, draft.memory());
        assertEquals(250, draft.maxPlayers());
        assertTrue(draft.fallback());
        assertEquals(3, draft.fallbackPriority());
        assertEquals(List.of("ALL", "Lobby"), draft.templates(), "doppelte Ebenen werden entfernt");
        assertTrue(prompt.questions.get(0).contains("Typ"), prompt.questions.toString());
        assertTrue(prompt.questions.stream().anyMatch(question -> question.contains("Fallback-Ziel")));
    }

    @Test
    void proxiesSkipPlayerAndFallbackQuestions() {
        Scripted prompt = new Scripted("PROXY", "STATIC", "velocity", "", "1", "1", "512", "");

        GroupWizard.Draft draft = GroupWizard.collect(prompt, "Proxy").orElseThrow();

        assertEquals(0, draft.maxPlayers());
        assertFalse(draft.fallback());
        assertEquals(0, draft.fallbackPriority());
        assertTrue(draft.templates().isEmpty());
        assertTrue(prompt.questions.stream().noneMatch(question -> question.contains("Spieler")),
                prompt.questions.toString());
        assertTrue(prompt.questions.stream().noneMatch(question -> question.contains("Fallback-Ziel")),
                prompt.questions.toString());
    }

    @Test
    void invalidNumbersAreAskedAgain() {
        Scripted prompt = new Scripted("SERVER", "DYNAMIC", "paper", "", "viele", "1", "1",
                "1024", "100", "n", "");

        GroupWizard.Draft draft = GroupWizard.collect(prompt, "Lobby").orElseThrow();

        assertEquals(1, draft.minServices(), "die ungültige Eingabe zählt nicht");
        assertEquals(1, prompt.warnings.size());
        assertTrue(prompt.warnings.get(0).contains("Zahl"), prompt.warnings.toString());
    }

    @Test
    void maxMustNotUndercutMin() {
        // "2" liegt unter min (3) und ist nicht 0 — der Wert wird abgelehnt und erneut gefragt.
        Scripted prompt = new Scripted("SERVER", "DYNAMIC", "paper", "", "3", "2", "3",
                "1024", "100", "n", "");

        GroupWizard.Draft draft = GroupWizard.collect(prompt, "Lobby").orElseThrow();

        assertEquals(3, draft.minServices());
        assertEquals(3, draft.maxServices(), "erst der gültige Wert wird übernommen");
        assertTrue(prompt.warnings.stream().anyMatch(warning -> warning.contains("mindestens min")),
                prompt.warnings.toString());
    }

    @Test
    void abortProducesNoDraft() {
        Scripted prompt = new Scripted("SERVER", "<abort>");

        assertTrue(GroupWizard.collect(prompt, "Lobby").isEmpty());
    }

    private static final class Scripted implements GroupWizard.Prompt {

        private final Deque<String> answers;
        private final List<String> questions = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();

        private Scripted(String... answers) {
            this.answers = new ArrayDeque<>(List.of(answers));
        }

        @Override
        public Optional<String> ask(String question, String defaultValue) {
            return next(question, defaultValue);
        }

        @Override
        public Optional<String> choose(String question, List<String> options, String defaultValue) {
            return next(question, defaultValue);
        }

        @Override
        public Optional<Boolean> confirm(String question, boolean defaultValue) {
            questions.add(question);
            String answer = answers.poll();
            if (answer == null) {
                return Optional.of(defaultValue);
            }
            if (answer.equals("<abort>")) {
                return Optional.empty();
            }
            return Optional.of(answer.equalsIgnoreCase("j") || answer.equalsIgnoreCase("y"));
        }

        @Override
        public void warn(String message) {
            warnings.add(message);
        }

        private Optional<String> next(String question, String defaultValue) {
            questions.add(question);
            String answer = answers.poll();
            if (answer == null) {
                return Optional.of(defaultValue);
            }
            return answer.equals("<abort>") ? Optional.empty() : Optional.of(answer);
        }
    }
}
