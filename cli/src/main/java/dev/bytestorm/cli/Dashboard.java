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
     * @param runtime          Runtime des Services
     * @param remainingCrashes verbleibende Abstürze vor der {@code DEGRADED}-Sperre
     * @param crashBudget      Budget eines frisch gestarteten Services, {@code 0} wenn unbekannt
     */
    public record ServiceRow(String name, String group, ServiceState state, int port,
                             int online, int max, String runtime,
                             int remainingCrashes, int crashBudget) {
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
     * Warum ein Service gestorben ist: der Zustand {@code CRASHED} allein sagt es
     * nicht, und ohne die Ursache sucht man den Absturz im Log statt im Bericht.
     *
     * @param service  Service-Name
     * @param reason   Ursache des letzten Absturzes
     * @param exitCode Exit-Code, {@code -1} wenn der Node abgebrochen hat
     * @param time     Zeitpunkt, bereits formatiert
     * @param report   Ordnername des Crash-Berichts
     */
    public record CrashRow(String service, String reason, int exitCode, String time, String report) {
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
     * @param crashes        letzte Crashs je Service, leer wenn keiner abstürzte
     */
    public record Snapshot(String uptime, int onlineServices, int totalServices, int groups,
                           List<String> degradedGroups, long usedMemoryMb, long maxMemoryMb,
                           Double cpuLoad, Double memoryLoad,
                           List<ServiceRow> services, List<GroupRow> groupRows,
                           List<CrashRow> crashes) {
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
        out.append(Theme.bold("ByteStorm")).append(Theme.dim("  ·  Cloud Console"))
                .append("    ").append(Theme.dim("Uptime ")).append(Theme.accent(snapshot.uptime()));
        out.append(NL).append(summary(snapshot));
        if (!snapshot.degradedGroups().isEmpty()) {
            out.append(NL).append(Theme.warn("Degradierte Gruppen: "
                    + String.join(", ", snapshot.degradedGroups())));
        }

        out.append(NL).append(NL).append(Theme.heading("Services"));
        if (snapshot.services().isEmpty()) {
            out.append(NL).append(Theme.dim("Keine Services registriert."));
        } else {
            Table table = Table.of("Name", "Gruppe", "Status", "Port", "Spieler", "Runtime",
                    "Crash-Budget");
            for (ServiceRow row : snapshot.services()) {
                table.row(row.name(), row.group(), Theme.serviceState(row.state()),
                        String.valueOf(row.port()), players(row.online(), row.max()), row.runtime(),
                        crashBudget(row.remainingCrashes(), row.crashBudget()));
            }
            out.append(NL).append(table.render());
        }

        out.append(NL).append(NL).append(Theme.heading("Gruppen"));
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

        if (!snapshot.crashes().isEmpty()) {
            out.append(NL).append(NL).append(Theme.heading("Letzte Crashs"));
            Table table = Table.of("Service", "Ursache", "Exit", "Zeit", "Bericht");
            for (CrashRow row : snapshot.crashes()) {
                table.row(row.service(), Theme.warn(row.reason()), String.valueOf(row.exitCode()),
                        row.time(), row.report());
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
                .append("/").append(snapshot.maxMemoryMb()).append(" MB")
                .append(" ").append(Theme.bar(snapshot.usedMemoryMb(), snapshot.maxMemoryMb(), 12));
        if (snapshot.cpuLoad() != null) {
            out.append(SEPARATOR).append(Theme.dim("Host-CPU ")).append(percent(snapshot.cpuLoad()))
                    .append(" ").append(Theme.bar(snapshot.cpuLoad(), 8));
        }
        if (snapshot.memoryLoad() != null) {
            out.append(SEPARATOR).append(Theme.dim("Host-RAM ")).append(percent(snapshot.memoryLoad()))
                    .append(" ").append(Theme.bar(snapshot.memoryLoad(), 8));
        }
        return out.toString();
    }

    private static String percent(double load) {
        return Math.round(load * 100) + " %";
    }

    /**
     * Ein Service, der zweimal abgestürzt ist, sieht im Dashboard aus wie ein
     * frisch gestarteter. Das verbleibende Budget macht den Unterschied sichtbar,
     * bevor die Gruppe {@code DEGRADED} wird und niemand mehr nachschiebt.
     *
     * @param remaining verbleibende Abstürze
     * @param total     Budget eines frisch gestarteten Services, {@code 0} wenn unbekannt
     * @return {@code verbleibend/gesamt} oder {@code -} ohne Budgetangabe
     */
    private static String crashBudget(int remaining, int total) {
        if (total <= 0) {
            return Theme.dim("-");
        }
        String text = remaining + "/" + total;
        if (remaining <= 0) {
            return Theme.red(text);
        }
        return remaining < total ? Theme.yellow(text) : Theme.green(text);
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
