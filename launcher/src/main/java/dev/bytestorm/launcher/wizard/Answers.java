package dev.bytestorm.launcher.wizard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Antworten eines Fragebogens, nach Frage-ID abgelegt.
 *
 * <p>Jede Frage hat eine Liste von Werten: Text- und Ja/Nein-Fragen genau einen,
 * Mehrfachauswahlen mehrere. Der Typ ist unveränderlich, jede Änderung liefert
 * eine neue Instanz — so kann die Oberfläche den Zustand gefahrlos zwischenspeichern.
 *
 * <p>{@code toString} nennt nur die IDs: Passwörter und API-Keys dürfen nie in
 * einer Meldung oder einem Log landen (§20).
 */
public final class Answers {

    /** Wert einer bestätigten Ja/Nein-Frage. */
    public static final String YES = "ja";

    /** Wert einer verneinten Ja/Nein-Frage. */
    public static final String NO = "nein";

    private final Map<String, List<String>> values;

    private Answers(Map<String, List<String>> values) {
        this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    /**
     * @return leere Antworten
     */
    public static Answers empty() {
        return new Answers(Map.of());
    }

    /**
     * @param id Frage-ID
     * @return {@code true}, wenn die Frage beantwortet wurde
     */
    public boolean has(String id) {
        return values.containsKey(id);
    }

    /**
     * @param id Frage-ID
     * @return erster Wert oder {@code ""}
     */
    public String text(String id) {
        List<String> stored = values.get(id);
        return stored == null || stored.isEmpty() ? "" : stored.get(0);
    }

    /**
     * @param id Frage-ID
     * @return alle Werte, nie {@code null}
     */
    public List<String> all(String id) {
        List<String> stored = values.get(id);
        return stored == null ? List.of() : stored;
    }

    /**
     * @param id Frage-ID einer Ja/Nein-Frage
     * @return {@code true} bei {@code ja}
     */
    public boolean flag(String id) {
        return YES.equals(text(id));
    }

    /**
     * @param id    Frage-ID
     * @param value neuer Wert, {@code null} gilt als leer
     * @return neue Antworten
     */
    public Answers with(String id, String value) {
        Objects.requireNonNull(id, "id");
        Map<String, List<String>> copy = new LinkedHashMap<>(values);
        copy.put(id, List.of(value == null ? "" : value));
        return new Answers(copy);
    }

    /**
     * @param id     Frage-ID
     * @param values neue Werte
     * @return neue Antworten
     */
    public Answers withAll(String id, List<String> values) {
        Objects.requireNonNull(id, "id");
        Map<String, List<String>> copy = new LinkedHashMap<String, List<String>>(this.values);
        copy.put(id, List.copyOf(values));
        return new Answers(copy);
    }

    /**
     * Schaltet einen Wert einer Mehrfachauswahl um.
     *
     * @param id    Frage-ID
     * @param value umzuschaltender Wert
     * @return neue Antworten
     */
    public Answers toggle(String id, String value) {
        List<String> current = new ArrayList<>(all(id));
        if (!current.remove(value)) {
            current.add(value);
        }
        return withAll(id, current);
    }

    /**
     * @param id Frage-ID
     * @return neue Antworten ohne diese Frage
     */
    public Answers without(String id) {
        Map<String, List<String>> copy = new LinkedHashMap<>(values);
        copy.remove(id);
        return new Answers(copy);
    }

    /**
     * @return die beantworteten Frage-IDs in Eingabereihenfolge
     */
    public Set<String> ids() {
        return values.keySet();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Answers answers && values.equals(answers.values);
    }

    @Override
    public int hashCode() {
        return values.hashCode();
    }

    @Override
    public String toString() {
        return "Answers" + values.keySet();
    }
}
