package dev.bytestorm.bridge.paper.placeholder;

import dev.bytestorm.proto.ServiceInfo;
import dev.bytestorm.proto.ServiceState;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Rechnet Platzhalter aus dem, was die Bridge aus der Registry kennt (§15).
 *
 * <p>Die Klasse kennt weder PlaceholderAPI noch Bukkit: Sie nimmt eine Liste von
 * {@link ServiceInfo} und die eigenen Werte des Servers. Dadurch sind alle
 * Platzhalter ohne laufenden Server testbar, und die Erweiterung bleibt eine
 * dünne Übersetzung von {@code %bytestorm_<name>%} auf {@link #value(String)}.
 *
 * <p>Netzwerkwerte zählen nur Services im Zustand {@code ONLINE} — ein gerade
 * startender Service hat noch keine Spieler, und ein gestoppter soll die Summe
 * nicht mit alten Zahlen verfälschen.
 *
 * <table border="1">
 *   <caption>Platzhalter</caption>
 *   <tr><th>Name</th><th>Bedeutung</th></tr>
 *   <tr><td>{@code service}</td><td>Name dieses Services, z. B. {@code Lobby-1}</td></tr>
 *   <tr><td>{@code platform}, {@code platform_version}</td><td>Plattform und Version</td></tr>
 *   <tr><td>{@code motd}</td><td>MOTD dieses Servers</td></tr>
 *   <tr><td>{@code players}</td><td>Spieler im ganzen Netzwerk</td></tr>
 *   <tr><td>{@code players_max}</td><td>Summe der Obergrenzen im Netzwerk</td></tr>
 *   <tr><td>{@code players_current}</td><td>Spieler auf diesem Server</td></tr>
 *   <tr><td>{@code players_max_current}</td><td>Obergrenze dieses Servers</td></tr>
 *   <tr><td>{@code services}</td><td>laufende Services</td></tr>
 *   <tr><td>{@code services_all}</td><td>bekannte Services, auch gestoppte</td></tr>
 *   <tr><td>{@code groups}</td><td>Gruppen mit laufenden Services</td></tr>
 *   <tr><td>{@code crashed}</td><td>Services im Zustand {@code CRASHED}</td></tr>
 *   <tr><td>{@code service_players_<name>}</td><td>Spieler eines Services</td></tr>
 *   <tr><td>{@code service_max_players_<name>}</td><td>Obergrenze eines Services</td></tr>
 *   <tr><td>{@code service_state_<name>}</td><td>Zustand, z. B. {@code ONLINE}</td></tr>
 *   <tr><td>{@code service_group_<name>}</td><td>Gruppe eines Services</td></tr>
 *   <tr><td>{@code service_port_<name>}</td><td>Port eines Services</td></tr>
 *   <tr><td>{@code group_players_<group>}</td><td>Spieler einer Gruppe</td></tr>
 *   <tr><td>{@code group_services_<group>}</td><td>laufende Services einer Gruppe</td></tr>
 *   <tr><td>{@code group_fallback_<group>}</td><td>Fallback-Service der Gruppe oder {@code -}</td></tr>
 * </table>
 */
public final class PlaceholderValues {

    private static final String UNKNOWN = "";

    /** Anzeige, wenn eine Gruppe keinen Fallback hat. */
    private static final String NO_FALLBACK = "-";

    /**
     * Werte dieses Servers; sie kommen aus der Plattform, nicht aus der Registry.
     *
     * @param serviceName   Name dieses Services
     * @param platform      Plattformname, z. B. {@code paper}
     * @param platformVersion Plattformversion
     * @param onlinePlayers Spieler auf diesem Server
     * @param maxPlayers    Obergrenze dieses Servers
     * @param motd          MOTD dieses Servers
     */
    public record Local(String serviceName, String platform, String platformVersion,
                        int onlinePlayers, int maxPlayers, String motd) {

        /**
         * @param serviceName     Name dieses Services
         * @param platform        Plattformname
         * @param platformVersion Plattformversion
         * @param onlinePlayers   Spieler auf diesem Server
         * @param maxPlayers      Obergrenze dieses Servers
         * @param motd            MOTD
         */
        public Local {
            serviceName = serviceName == null ? "" : serviceName;
            platform = platform == null ? "" : platform;
            platformVersion = platformVersion == null ? "" : platformVersion;
            motd = motd == null ? "" : motd;
            onlinePlayers = Math.max(0, onlinePlayers);
            maxPlayers = Math.max(0, maxPlayers);
        }
    }

    private final Supplier<Collection<ServiceInfo>> registry;
    private final Supplier<Local> localValues;

    /**
     * @param registry    liefert die zuletzt bekannten Services
     * @param localValues liefert die Werte dieses Servers; sie ändern sich mit
     *                    jedem Tick, deshalb werden sie je Abfrage gelesen
     */
    public PlaceholderValues(Supplier<Collection<ServiceInfo>> registry, Supplier<Local> localValues) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.localValues = Objects.requireNonNull(localValues, "localValues");
    }

    /**
     * @param params Platzhalter ohne Präfix, z. B. {@code players}
     * @return der Wert; ein unbekannter Platzhalter liefert einen leeren String
     */
    public String value(String params) {
        if (params == null) {
            return UNKNOWN;
        }
        String key = params.trim().toLowerCase(Locale.ROOT);
        if (key.isEmpty()) {
            return UNKNOWN;
        }
        Local local = localValues.get();
        return switch (key) {
            case "service" -> local.serviceName();
            case "platform" -> local.platform();
            case "platform_version" -> local.platformVersion();
            case "motd" -> local.motd();
            case "players" -> Integer.toString(networkPlayers());
            case "players_max" -> Integer.toString(networkMaxPlayers());
            case "players_current" -> Integer.toString(local.onlinePlayers());
            case "players_max_current" -> Integer.toString(local.maxPlayers());
            case "services" -> Integer.toString(online().size());
            case "services_all" -> Integer.toString(services().size());
            case "groups" -> Integer.toString(groups().size());
            case "crashed" -> Long.toString(services().stream()
                    .filter(service -> service.getState() == ServiceState.CRASHED)
                    .count());
            default -> byName(key);
        };
    }

    /**
     * Löst Platzhalter mit einem Namen im Schlüssel auf.
     *
     * @param key bereits kleingeschriebener Platzhalter
     * @return der Wert oder ein leerer String
     */
    private String byName(String key) {
        if (key.startsWith("service_players_")) {
            return find(name(key, "service_players_"))
                    .map(service -> Integer.toString(service.getOnlinePlayers()))
                    .orElse(UNKNOWN);
        }
        if (key.startsWith("service_max_players_")) {
            return find(name(key, "service_max_players_"))
                    .map(service -> Integer.toString(service.getMaxPlayers()))
                    .orElse(UNKNOWN);
        }
        if (key.startsWith("service_state_")) {
            return find(name(key, "service_state_"))
                    .map(service -> service.getState().name())
                    .orElse(UNKNOWN);
        }
        if (key.startsWith("service_group_")) {
            return find(name(key, "service_group_"))
                    .map(ServiceInfo::getGroup)
                    .orElse(UNKNOWN);
        }
        if (key.startsWith("service_port_")) {
            return find(name(key, "service_port_"))
                    .map(service -> Integer.toString(service.getPort()))
                    .orElse(UNKNOWN);
        }
        if (key.startsWith("group_players_")) {
            String group = name(key, "group_players_");
            return Integer.toString(ofGroup(group).stream()
                    .filter(PlaceholderValues::isOnline)
                    .mapToInt(ServiceInfo::getOnlinePlayers)
                    .sum());
        }
        if (key.startsWith("group_services_")) {
            String group = name(key, "group_services_");
            return Long.toString(ofGroup(group).stream().filter(PlaceholderValues::isOnline).count());
        }
        if (key.startsWith("group_fallback_")) {
            String group = name(key, "group_fallback_");
            return fallback(group).map(ServiceInfo::getName).orElse(NO_FALLBACK);
        }
        return UNKNOWN;
    }

    /**
     * @return Spieler im Netzwerk über alle laufenden Services
     */
    private int networkPlayers() {
        return online().stream().mapToInt(ServiceInfo::getOnlinePlayers).sum();
    }

    /**
     * @return Summe der Obergrenzen; ein Service ohne Grenze zählt nicht mit
     */
    private int networkMaxPlayers() {
        return online().stream()
                .mapToInt(ServiceInfo::getMaxPlayers)
                .filter(max -> max > 0)
                .sum();
    }

    /**
     * @return Fallback-Service der Gruppe, kleinste Priorität zuerst
     */
    private Optional<ServiceInfo> fallback(String group) {
        return ofGroup(group).stream()
                .filter(PlaceholderValues::isOnline)
                .filter(ServiceInfo::getFallback)
                .min((left, right) -> {
                    int byPriority = Integer.compare(priority(left), priority(right));
                    return byPriority != 0
                            ? byPriority
                            : left.getName().compareToIgnoreCase(right.getName());
                });
    }

    private static int priority(ServiceInfo service) {
        // Ohne gesetzte Priorität gilt 0 als "vorn"; die Cloud schreibt sie immer.
        return service.getFallbackPriority();
    }

    /**
     * @param name Servicename, Groß-/Kleinschreibung egal
     * @return der Service, falls bekannt
     */
    private Optional<ServiceInfo> find(String name) {
        if (name.isEmpty()) {
            return Optional.empty();
        }
        return services().stream()
                .filter(service -> service.getName().equalsIgnoreCase(name))
                .findFirst();
    }

    /**
     * @param group Gruppenname, Groß-/Kleinschreibung egal
     * @return alle bekannten Services der Gruppe
     */
    private List<ServiceInfo> ofGroup(String group) {
        return services().stream()
                .filter(service -> service.getGroup().equalsIgnoreCase(group))
                .toList();
    }

    /**
     * @return laufende Services
     */
    private List<ServiceInfo> online() {
        return services().stream().filter(PlaceholderValues::isOnline).toList();
    }

    /**
     * @return Gruppen mit mindestens einem laufenden Service
     */
    private Set<String> groups() {
        return online().stream()
                .map(ServiceInfo::getGroup)
                .filter(group -> !group.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    private List<ServiceInfo> services() {
        Collection<ServiceInfo> services = registry.get();
        return services == null ? List.of() : List.copyOf(services);
    }

    private static boolean isOnline(ServiceInfo service) {
        return service.getState() == ServiceState.ONLINE;
    }

    private static String name(String key, String prefix) {
        return key.substring(prefix.length());
    }
}
