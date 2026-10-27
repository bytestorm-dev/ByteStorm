package dev.bytestorm.cli;

import dev.bytestorm.proto.GroupState;
import dev.bytestorm.proto.ServiceMode;
import dev.bytestorm.proto.ServiceState;
import dev.bytestorm.proto.ServiceType;

import java.util.List;

/**
 * Übersicht der Cloud für die CLI (§17): Services, Gruppen und Host-Last.
 *
 * <p>{@code status} zeigt genau eine solche Momentaufnahme, {@code watch} baut
 * sie im Sekundentakt neu auf ({@link DashboardScreen}). Die Klasse selbst
 * rendert nur; sie liest nichts und kennt kein Terminal, damit die Ausgabe ohne
 * Terminal prüfbar bleibt.
 */
public final class Dashboard {

    /** Aktualisierung der fortlaufenden Ansicht. */
    public static final long DEFAULT_INTERVAL_MILLIS = 2_000;

    /** Kürzer als das ergibt keinen Sinn und würde nur flackern. */
    public static final long MIN_INTERVAL_MILLIS = 500;

    private static final String NL = System.lineSeparator();

    private static final String SEPARATOR = "  ·  ";

    /**
     * @param name    Service-Name
     * @param group   Gruppenzugehörigkeit
     * @param state   Live-Zustand
     * @param port    Port
     * @param online  Spieler online
     * @param max     maximale Spielerzahl
     * @param runtime Runtime des Services
     */
    public record ServiceRow(String name, String group, ServiceState state, int port,
                             int online, int max, String runtime) {
    }

    /**
     * @param name        Gruppenname
     * @param type        Rolle der Gruppe
     * @param mode        Lebensdauer der Services
     * @param state       Zustand der Gruppe
     * @param running     Anzahl der Services dieser Gruppe
     * @param min         hartes Minimum
     * @param max         hartes Maximum, {@code 0} steht für unbegrenzt
     * @param maintenance Wartungsmodus
     */
    public record GroupRow(String name, ServiceType type, ServiceMode mode, GroupState state,
                           int running, int min, int max, boolean maintenance) {
    }

    /**
     * @param uptime         Laufzeit der Cloud, lesbar formatiert
     * @param onlineServices Services im Zustand {@code ONLINE}
     * @param totalServices  alle bekannten Services
     * @param groups         Anzahl der Gruppen
     * @param degradedGroups Gruppen im Zustand {@code DEGRADED}
     * @param usedMemoryMb   belegter Heap in MB
     * @param maxMemoryMb    maximaler Heap in MB
     * @param cpuLoad        CPU-Last des Hosts zwischen 0 und 1, {@code null} wenn unbekannt
     * @param memoryLoad     Speicherlast des Hosts zwischen 0 und 1, {@code null} wenn unbekannt
     * @param services       Service-Zeilen
     * @param groupRows      Gruppen-Zeilen
     */
    public record Snapshot(String uptime, int onlineServices, int totalServices, int groups,
                           List<String> degradedGroups, long usedMemoryMb, long maxMemoryMb,
                           Double cpuLoad, Double memoryLoad,
                           List<ServiceRow> services, List<GroupRow> groupRows) {
    }

    private Dashboard() {
    }

    /**
     * Zeichnet die Übersicht.
     *
     * @param snapshot Momentaufnahme
     * @return Textblock für das Terminal
     */
    public static String render(Snapshot snapshot) {
        StringBuilder out = new StringBuilder();
        out.append(Theme.bold("ByteStorm")).append("  ")
                .append(Theme.dim("Uptime " + snapshot.uptime()));
        out.append(NL).append(summary(snapshot));
        if (!snapshot.degradedGroups().isEmpty()) {
            out.append(NL).append(Theme.warn("Degradierte Gruppen: "
                    + String.join(", ", snapshot.degradedGroups())));
        }

        out.append(NL).append(NL).append(Theme.section("Services"));
        if (snapshot.services().isEmpty()) {
            out.append(NL).append(Theme.dim("Keine Services registriert."));
        } else {
            Table table = Table.of("Name", "Gruppe", "Status", "Port", "Spieler", "Runtime");
            for (ServiceRow row : snapshot.services()) {
                table.row(row.name(), row.group(), Theme.serviceState(row.state()),
                        String.valueOf(row.port()), players(row.online(), row.max()), row.runtime());
            }
            out.append(NL).append(table.render());
        }

        out.append(NL).append(NL).append(Theme.section("Gruppen"));
        if (snapshot.groupRows().isEmpty()) {
            out.append(NL).append(Theme.dim("Keine Gruppen definiert."));
        } else {
            Table table = Table.of("Name", "Typ", "Modus", "Status", "Services", "Min/Max", "Wartung");
            for (GroupRow row : snapshot.groupRows()) {
                table.row(row.name(), row.type().name(), row.mode().name(),
                        Theme.groupState(row.state()), String.valueOf(row.running()),
                        row.min() + "/" + (row.max() == 0 ? "unbegrenzt" : row.max()),
                        row.maintenance() ? Theme.maintenance(true) : Theme.maintenance(false));
            }
            out.append(NL).append(table.render());
        }
        return out.toString();
    }

    private static String summary(Snapshot snapshot) {
        StringBuilder out = new StringBuilder();
        out.append(Theme.field("Services",
                Theme.counts(snapshot.onlineServices(), snapshot.totalServices()) + " online"));
        out.append(SEPARATOR).append(Theme.dim("Gruppen ")).append(snapshot.groups());
        out.append(SEPARATOR).append(Theme.dim("Speicher ")).append(snapshot.usedMemoryMb())
                .append("/").append(snapshot.maxMemoryMb()).append(" MB");
        if (snapshot.cpuLoad() != null) {
            out.append(SEPARATOR).append(Theme.dim("Host-CPU ")).append(percent(snapshot.cpuLoad()));
        }
        if (snapshot.memoryLoad() != null) {
            out.append(SEPARATOR).append(Theme.dim("Host-RAM ")).append(percent(snapshot.memoryLoad()));
        }
        return out.toString();
    }

    private static String percent(double load) {
        return Math.round(load * 100) + " %";
    }

    /**
     * @param online Spieler online
     * @param max    Obergrenze, {@code 0} steht für unbegrenzt (etwa bei Proxies)
     * @return {@code online/max} oder nur die Zahl ohne Obergrenze
     */
    private static String players(int online, int max) {
        return max <= 0 ? String.valueOf(online) : online + "/" + max;
    }

    /**
     * Liest das Intervall aus {@code watch [sekunden]}.
     *
     * @param argument Sekunden oder {@code null}
     * @return Intervall in Millisekunden
     * @throws IllegalArgumentException wenn die Angabe keine positive Zahl ist
     */
    public static long intervalMillis(String argument) {
        if (argument == null || argument.isBlank()) {
            return DEFAULT_INTERVAL_MILLIS;
        }
        long seconds;
        try {
            seconds = Long.parseLong(argument.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Intervall ist keine Zahl: " + argument);
        }
        if (seconds < 1) {
            throw new IllegalArgumentException("Intervall muss mindestens 1 Sekunde sein: " + seconds);
        }
        return Math.max(MIN_INTERVAL_MILLIS, seconds * 1_000);
    }
}
