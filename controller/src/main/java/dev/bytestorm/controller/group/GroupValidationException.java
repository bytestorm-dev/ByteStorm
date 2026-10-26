package dev.bytestorm.controller.group;

import java.util.List;
import java.util.Objects;

/**
 * Eine Gruppen-Definition wurde abgelehnt (§9.1).
 *
 * <p>Die Meldung nennt die Gruppe und alle gefundenen Probleme, damit CLI und
 * REST sie unverändert ausgeben können.
 */
public class GroupValidationException extends Exception {

    private final transient List<String> problems;

    /**
     * @param group    Gruppenname
     * @param problems gefundene Probleme, mindestens eines
     */
    public GroupValidationException(String group, List<String> problems) {
        super("Gruppe '" + group + "' ist ungültig:" + System.lineSeparator()
                + String.join(System.lineSeparator(),
                        problems.stream().map(problem -> " - " + problem).toList()));
        this.problems = List.copyOf(Objects.requireNonNull(problems, "problems"));
    }

    /**
     * @return die gefundenen Probleme
     */
    public List<String> problems() {
        return problems;
    }
}
