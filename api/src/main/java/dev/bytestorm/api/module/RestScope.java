package dev.bytestorm.api.module;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Berechtigungen der REST-Endpunkte (§11, §20).
 *
 * <p>Der REST-Modul vergibt Scopes an API-Keys; damit ein Monitoring-Key den
 * Zustand lesen kann, ohne einen Service stoppen zu dürfen. Die Namen stehen
 * hier, weil zwei Seiten sie kennen müssen: das Modul beim Prüfen jeder Anfrage
 * und die CLI, wenn sie einem Betreiber zeigt, welcher Key was darf.
 *
 * <p>Die Namen sind stabil und stehen in der Modul-Konfiguration
 * ({@code scopes.<name>}). Erlaubt sind einzelne Scopes, eine Familien-Wildcard
 * ({@code services.*}) und {@link #ALL} für alles.
 *
 * <p>Zu jedem Scope stehen die Endpunkte, die er freigibt. Sie sind Teil der
 * Aufzählung, weil ein Betreiber beim Vergeben eines Scopes wissen muss, was er
 * damit erlaubt — {@code module info} zeigt genau diese Liste (§11, §20).
 */
public enum RestScope {

    /** {@code GET /services} und {@code GET /services/logs}. */
    SERVICES_READ("services.read", "GET /services", "GET /services/logs"),

    /** Start, Stop, Neustart, Befehl, Reapply und Online-Meldung eines Services. */
    SERVICES_WRITE("services.write", "POST /services/start", "POST /services/stop",
            "POST /services/restart", "POST /services/command", "POST /services/reapply",
            "POST /services/online"),

    /** {@code GET /groups}. */
    GROUPS_READ("groups.read", "GET /groups"),

    /** Wartungsmodus und Skalierung einer Gruppe. */
    GROUPS_WRITE("groups.write", "POST /groups/maintenance", "POST /groups/scale"),

    /** {@code GET /players}. */
    PLAYERS_READ("players.read", "GET /players"),

    /** Spieler umschalten und trennen. */
    PLAYERS_WRITE("players.write", "POST /players/connect", "POST /players/kick"),

    /** {@code /health}, {@code /nodes/status} und {@code /metrics}. */
    MONITORING_READ("monitoring.read", "GET /health", "GET /nodes/status", "GET /metrics");

    /** Scope, der alles erlaubt. */
    public static final String ALL = "*";

    private final String id;
    private final List<String> endpoints;

    RestScope(String id, String... endpoints) {
        this.id = id;
        this.endpoints = List.of(endpoints);
    }

    /**
     * @return Name des Scopes, z. B. {@code services.write}
     */
    public String id() {
        return id;
    }

    /**
     * @return die Endpunkte, die dieser Scope freigibt
     */
    public List<String> endpoints() {
        return endpoints;
    }

    /**
     * @return die Endpunkte als Text, z. B. {@code POST /services/start, POST /services/stop}
     */
    public String endpointText() {
        return String.join(", ", endpoints);
    }

    /**
     * @return der Teil vor dem Punkt, z. B. {@code services}; Grundlage für {@code services.*}
     */
    public String family() {
        int dot = id.indexOf('.');
        return dot < 0 ? id : id.substring(0, dot);
    }

    /**
     * @param id Name aus der Konfiguration
     * @return der Scope, leer wenn der Name unbekannt ist
     */
    public static Optional<RestScope> of(String id) {
        return Arrays.stream(values()).filter(scope -> scope.id.equals(id)).findFirst();
    }

    /**
     * Die Scopes, die ein Key ohne eigene Angabe bekommt: lesen ja, schreiben nein.
     *
     * @return alle Lese-Scopes
     */
    public static Set<RestScope> readOnly() {
        return EnumSet.of(SERVICES_READ, GROUPS_READ, PLAYERS_READ, MONITORING_READ);
    }

    /**
     * @return alle Scope-Namen in Deklarationsreihenfolge
     */
    public static List<String> ids() {
        return Arrays.stream(values()).map(RestScope::id).toList();
    }
}
