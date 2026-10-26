package dev.bytestorm.controller.group;

import dev.bytestorm.controller.scaling.ScalingDecision;

import java.util.List;

/**
 * Manuelle Skalierung einer Gruppe (§11, §17).
 *
 * <p>Der Aufrufer entscheidet nicht selbst, wie viele Services eine Gruppe
 * braucht; er nennt nur das Ziel. Umgesetzt wird die Schnittstelle vom
 * {@code ScalingManager}, damit die manuelle Regel dieselben Schutzregeln nutzt
 * wie die automatischen: kein Stop von statischen Services oder Services mit
 * Spielern und kein Überschreiten von {@code maxServices}.
 */
@FunctionalInterface
public interface GroupScaler {

    /**
     * Bringt eine Gruppe auf die gewünschte Anzahl Services.
     *
     * @param group  Gruppenname
     * @param target gewünschte Anzahl aktiver Services
     * @return die ausgeführten Entscheidungen, in Reihenfolge
     * @throws IllegalArgumentException wenn die Gruppe fehlt oder das Ziel unzulässig ist
     */
    List<ScalingDecision> scaleTo(String group, int target);
}
