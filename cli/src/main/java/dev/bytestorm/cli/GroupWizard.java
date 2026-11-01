package dev.bytestorm.cli;

import dev.bytestorm.api.service.Platforms;
import dev.bytestorm.proto.ServiceMode;
import dev.bytestorm.proto.ServiceType;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.IntPredicate;

/**
 * Fragt eine neue Gruppe interaktiv ab (§9.1, §17).
 *
 * <p>{@code group create <name>} ohne Optionen öffnet diesen Wizard: Typ, Modus,
 * Plattform, Servicezahl, Spieler, Speicher, Fallback und Templates. Enter
 * übernimmt jeweils den Vorschlag, Strg+C bricht ab und legt nichts an. Mit
 * Optionen bleibt die Zeile unverändert skriptbar — dann wird nichts gefragt.
 *
 * <p>Die Ein-/Ausgabe steckt hinter {@link Prompt}: Die Konsole fragt über ihren
 * {@code LineReader}, Tests beantworten dasselbe Formular ohne Terminal.
 */
public final class GroupWizard {

    /**
     * @param type             Rolle der Gruppe
     * @param mode             Lebensdauer der Services
     * @param platform         Plattform, z. B. {@code paper}
     * @param version          Plattform-Version, leer für die neueste
     * @param minServices      minimale Servicezahl
     * @param maxServices      maximale Servicezahl, {@code 0} steht für unbegrenzt
     * @param maxPlayers       Spieler je Service, {@code 0} bei Proxies
     * @param memory           Heap-Größe in MB
     * @param templates        zusätzliche Template-Ebenen, nie {@code null}
     * @param fallback         Fallback-Ziel
     * @param fallbackPriority kleinere Zahl gewinnt
     */
    public record Draft(ServiceType type, ServiceMode mode, String platform, String version,
                        int minServices, int maxServices, int maxPlayers, int memory,
                        List<String> templates, boolean fallback, int fallbackPriority) {

        /**
         * @param templates Template-Ebenen
         */
        public Draft {
            templates = templates == null ? List.of() : List.copyOf(templates);
        }
    }

    /**
     * Ein-/Ausgabe des Wizards. Jede Frage liefert bei Abbruch ein leeres
     * Ergebnis.
     */
    public interface Prompt {

        /**
         * @param question     Frage
         * @param defaultValue Vorschlag, den Enter übernimmt
         * @return Eingabe, leer bei Abbruch
         */
        Optional<String> ask(String question, String defaultValue);

        /**
         * @param question     Frage
         * @param options      erlaubte Werte
         * @param defaultValue Vorschlag, den Enter übernimmt
         * @return gewählte Option, leer bei Abbruch
         */
        Optional<String> choose(String question, List<String> options, String defaultValue);

        /**
         * @param question     Frage
         * @param defaultValue Vorschlag, den Enter übernimmt
         * @return Antwort, leer bei Abbruch
         */
        Optional<Boolean> confirm(String question, boolean defaultValue);

        /**
         * Schreibt eine Zeile ohne Frage, z. B. die Überschrift.
         *
         * @param message fertig formatierter Text
         */
        default void note(String message) {
        }

        /**
         * Meldet eine ungültige Eingabe und fragt erneut.
         *
         * @param message Hinweis
         */
        default void warn(String message) {
        }
    }

    private GroupWizard() {
    }

    /**
     * Führt das Formular aus.
     *
     * @param prompt Ein-/Ausgabe
     * @param name   Name der neuen Gruppe
     * @return die Antworten, leer wenn der Benutzer abgebrochen hat
     */
    public static Optional<Draft> collect(Prompt prompt, String name) {
        Objects.requireNonNull(prompt, "prompt");
        try {
            prompt.note(Theme.heading("Neue Gruppe '" + name + "'"));
            prompt.note(Theme.dim("Enter übernimmt den Vorschlag  ·  Strg+C bricht ab"));
            prompt.note("");
            return Optional.of(ask(prompt));
        } catch (Aborted e) {
            return Optional.empty();
        }
    }

    private static Draft ask(Prompt prompt) {
        ServiceType type = ServiceType.valueOf(choice(prompt, "Typ", List.of("SERVER", "PROXY"), "SERVER"));
        ServiceMode mode = ServiceMode.valueOf(choice(prompt, "Modus", List.of("DYNAMIC", "STATIC"),
                type == ServiceType.PROXY ? "STATIC" : "DYNAMIC"));
        String platform = askPlatform(prompt, type);
        String version = text(prompt, "Version (leer = neueste)", "");
        int minServices = number(prompt, "Minimale Services", 1, 1, 1_000);
        int maxServices = number(prompt, "Maximale Services (0 = unbegrenzt)", 1, 0, 1_000,
                value -> value == 0 || value >= minServices,
                "Max muss 0 oder mindestens min (" + minServices + ") sein.");
        int memory = number(prompt, "Speicher je Service in MB",
                type == ServiceType.PROXY ? 512 : 1_024, 128, 65_536);
        // Proxies zählen keine Spieler: das Feld bleibt ihnen erspart.
        int maxPlayers = type == ServiceType.PROXY
                ? 0 : number(prompt, "Spieler je Service", 100, 0, 100_000);
        boolean fallback = type == ServiceType.SERVER && confirm(prompt, "Fallback-Ziel", false);
        int priority = fallback
                ? number(prompt, "Fallback-Priorität (kleiner gewinnt)", 1, 0, 1_000) : 0;
        List<String> templates = templates(text(prompt,
                "Zusätzliche Template-Ebenen (Komma-getrennt, leer = keine)", ""));
        return new Draft(type, mode, platform, version, minServices, maxServices, maxPlayers,
                memory, templates, fallback, priority);
    }

    private static String askPlatform(Prompt prompt, ServiceType type) {
        while (true) {
            String platform = text(prompt, "Plattform", type == ServiceType.PROXY ? "velocity" : "paper");
            if (Platforms.isProxy(platform) == (type == ServiceType.PROXY)) {
                return platform;
            }
            prompt.warn("Plattform '" + platform + "' passt nicht zum Typ " + type
                    + " — " + (type == ServiceType.PROXY ? "z. B. velocity" : "z. B. paper") + " wählen.");
        }
    }

    private static String text(Prompt prompt, String question, String defaultValue) {
        Optional<String> answer = prompt.ask(question, defaultValue);
        if (answer.isEmpty()) {
            throw new Aborted();
        }
        return answer.get().trim();
    }

    private static String choice(Prompt prompt, String question, List<String> options, String defaultValue) {
        Optional<String> answer = prompt.choose(question, options, defaultValue);
        if (answer.isEmpty()) {
            throw new Aborted();
        }
        return answer.get().trim().toUpperCase(Locale.ROOT);
    }

    private static boolean confirm(Prompt prompt, String question, boolean defaultValue) {
        Optional<Boolean> answer = prompt.confirm(question, defaultValue);
        if (answer.isEmpty()) {
            throw new Aborted();
        }
        return answer.get();
    }

    private static int number(Prompt prompt, String question, int defaultValue, int min, int max) {
        return number(prompt, question, defaultValue, min, max, value -> true, null);
    }

    private static int number(Prompt prompt, String question, int defaultValue, int min, int max,
                              IntPredicate valid, String invalidMessage) {
        while (true) {
            String input = text(prompt, question + " (" + min + "–" + max + ")", String.valueOf(defaultValue));
            int value;
            try {
                value = Integer.parseInt(input);
            } catch (NumberFormatException e) {
                prompt.warn("Bitte eine Zahl eingeben: " + input);
                continue;
            }
            if (value < min || value > max) {
                prompt.warn("Bitte einen Wert zwischen " + min + " und " + max + " eingeben.");
                continue;
            }
            if (valid.test(value)) {
                return value;
            }
            prompt.warn(invalidMessage == null ? "Ungültiger Wert: " + value : invalidMessage);
        }
    }

    private static List<String> templates(String value) {
        if (value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(part -> !part.isEmpty())
                .distinct()
                .toList();
    }

    /** Abbruch durch Strg+C, EOF oder ein leeres Antwortobjekt. */
    private static final class Aborted extends RuntimeException {

        private static final long serialVersionUID = 1L;
    }
}
