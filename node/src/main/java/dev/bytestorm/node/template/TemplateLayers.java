package dev.bytestorm.node.template;

import dev.bytestorm.proto.ServiceGroup;
import dev.bytestorm.proto.ServiceType;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * Reihenfolge der Template-Ebenen (§10).
 *
 * <p>Alle Ebenen liegen als Ordner unter {@code templates/} und werden
 * nacheinander angewendet; spätere Ebenen überschreiben frühere.
 *
 * <ol>
 *   <li>{@code ALL} — jeder Service</li>
 *   <li>{@code PROXY} bzw. {@code SERVER} — nach Gruppentyp</li>
 *   <li>{@code FALLBACK} — zusätzlich für Server-Gruppen mit Fallback-Flag</li>
 *   <li>die Template-Liste der Gruppe, in ihrer Reihenfolge</li>
 * </ol>
 */
public final class TemplateLayers {

    /** Ebene für alle Services. */
    public static final String ALL = "ALL";

    /** Ebene für Proxy-Gruppen. */
    public static final String PROXY = "PROXY";

    /** Ebene für Fallback-Server; stapelt auf {@link #SERVER}. */
    public static final String FALLBACK = "FALLBACK";

    /** Ebene für Server-Gruppen. */
    public static final String SERVER = "SERVER";

    private TemplateLayers() {
    }

    /**
     * @param group Gruppe des Services
     * @return die Ebenen in Anwendungsreihenfolge, ohne Duplikate
     */
    public static List<String> forGroup(ServiceGroup group) {
        LinkedHashSet<String> layers = new LinkedHashSet<>();
        layers.add(ALL);
        if (group.getType() == ServiceType.PROXY) {
            layers.add(PROXY);
        } else {
            layers.add(SERVER);
            if (group.getFallback()) {
                layers.add(FALLBACK);
            }
        }
        layers.addAll(group.getTemplatesList());
        return List.copyOf(layers);
    }
}
