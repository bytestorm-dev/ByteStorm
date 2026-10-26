package dev.bytestorm.launcher.wizard;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Skriptgesteuerte Ein-/Ausgabe für Wizard-Tests: Antworten werden nach einem
 * Teilstring der Frage zugeordnet und in Reihenfolge verbraucht.
 */
final class ScriptedWizardIo implements WizardIo {

    private final Map<String, Deque<String>> answers = new LinkedHashMap<>();
    private final List<String> messages = new ArrayList<>();
    private final List<String> asked = new ArrayList<>();
    private String failOn;

    ScriptedWizardIo answer(String promptPart, String... values) {
        answers.put(promptPart, new ArrayDeque<>(List.of(values)));
        return this;
    }

    ScriptedWizardIo failOn(String promptPart) {
        this.failOn = promptPart;
        return this;
    }

    List<String> messages() {
        return messages;
    }

    List<String> asked() {
        return asked;
    }

    @Override
    public String ask(String prompt, String defaultValue) {
        String value = take(prompt);
        return value.isBlank() ? defaultValue : value;
    }

    @Override
    public String askSecret(String prompt) {
        return take(prompt);
    }

    @Override
    public String choose(String prompt, List<String> options, String defaultValue) {
        String value = take(prompt);
        for (String option : options) {
            if (option.equalsIgnoreCase(value)) {
                return option;
            }
        }
        throw new AssertionError("Ungültige Auswahl '" + value + "' für: " + prompt);
    }

    @Override
    public boolean confirm(String prompt, boolean defaultYes) {
        String value = take(prompt).trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "j", "ja", "y", "yes" -> true;
            case "n", "nein", "no" -> false;
            default -> defaultYes;
        };
    }

    @Override
    public void message(String text) {
        messages.add(text);
    }

    private String take(String prompt) {
        asked.add(prompt);
        if (failOn != null && prompt.contains(failOn)) {
            throw new WizardAbortedException("Testabbruch");
        }
        for (Map.Entry<String, Deque<String>> entry : answers.entrySet()) {
            if (prompt.contains(entry.getKey())) {
                String value = entry.getValue().poll();
                if (value == null) {
                    throw new AssertionError("Keine Antwort mehr für: " + prompt);
                }
                return value;
            }
        }
        throw new AssertionError("Unerwartete Frage: " + prompt + " — bekannt: " + answers.keySet());
    }
}
