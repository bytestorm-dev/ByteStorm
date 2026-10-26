package dev.bytestorm.launcher.wizard;

import java.util.List;

/**
 * Ein- und Ausgabe des Erststart-Wizards.
 *
 * <p>Die Abstraktion erlaubt Tests ohne Terminal und hält die Ablauflogik frei
 * von JLine. Implementierungen müssen bei Strg+C/EOF eine
 * {@link WizardAbortedException} werfen.
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
}
