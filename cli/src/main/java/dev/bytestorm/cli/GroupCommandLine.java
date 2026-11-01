package dev.bytestorm.cli;

import dev.bytestorm.api.service.Platforms;
import dev.bytestorm.controller.group.GroupManager;
import dev.bytestorm.controller.group.GroupScaler;
import dev.bytestorm.controller.group.GroupValidationException;
import dev.bytestorm.controller.scaling.ScalingDecision;
import dev.bytestorm.proto.RuntimeType;
import dev.bytestorm.proto.ScalingRule;
import dev.bytestorm.proto.ServiceGroup;
import dev.bytestorm.proto.ServiceMode;
import dev.bytestorm.proto.ServiceType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Gruppen-Kommandos der CLI (§14, §17).
 *
 * <p>Wie {@link ServiceCommandLine} ist der Dispatcher unabhängig von JLine: Er
 * bekommt die Tokens einer Zeile und liefert Text zurück. Alle Schreibzugriffe
 * laufen über den {@link GroupManager}, damit Validierung und Cache-Invalidierung
 * greifen; die Ausgabe nennt bei Fehlern die Probleme aus
 * {@link GroupValidationException}.
 */
public final class GroupCommandLine {

    /** Hilfe für die Gruppen-Kommandos. */
    public static final String USAGE = """
            group list                       alle Gruppen
            group info <name>                Details einer Gruppe
            group create <name> [optionen]   neue Gruppe (ohne Optionen interaktiv)
            group edit <name> [optionen]     bestehende Gruppe ändern
            group delete <name>              Gruppe löschen
            group scale <name> <anzahl>      manuell auf <anzahl> Services bringen
            maintenance <name> on|off        Wartungsmodus schalten

            Optionen für create/edit:
              --type PROXY|SERVER            Rolle (Default SERVER, bei velocity automatisch PROXY)
              --platform <name>              Plattform, z. B. paper oder velocity
              --version <version>            Plattform-Version (leer = neueste stabile)
              --mode STATIC|DYNAMIC          Lebensdauer (Default DYNAMIC)
              --min N                        minimale Servicezahl
              --max N                        maximale Servicezahl (0 = unbegrenzt)
              --max-players N                Spieler je Service
              --memory MB                    Heap-Größe
              --template <name>              Template-Ebene, mehrfach möglich
              --fallback / --no-fallback     Fallback-Ziel
              --fallback-priority N          kleinere Zahl gewinnt
              --java-version N               abweichendes JDK
              --runtime PROCESS|DOCKER       Runtime-Override der Gruppe
              --jvm-arg <arg>                JVM-Argument, mehrfach möglich
              --java-arg <arg>               Argument nach dem Plattform-Jar

            Ohne Optionen fragt der Wizard Typ, Plattform, Modus, Servicezahl, Spieler,
            Speicher, Fallback und Templates ab; mit Optionen bleibt die Zeile skriptbar.
            """;

    private static final String NL = System.lineSeparator();

    /** Optionen ohne Wert. */
    private static final Set<String> FLAGS = Set.of("fallback", "no-fallback");

    /** Optionen mit genau einem Wert; mehrfach angegebene Listenoptionen werden gesammelt. */
    private static final Set<String> VALUE_OPTIONS = Set.of(
            "type", "platform", "version", "mode", "min", "max", "max-players", "memory",
            "template", "fallback-priority", "java-version", "runtime", "jvm-arg", "java-arg");

    /** Standardwerte einer neuen Gruppe. */
    private static final int DEFAULT_MIN_SERVICES = 1;

    private static final int DEFAULT_MAX_SERVICES = 1;

    private static final int DEFAULT_MAX_PLAYERS = 100;

    private static final int DEFAULT_MEMORY_MB = 1024;

    private final GroupManager groups;
    private final GroupScaler scaler;
    private GroupWizard.Prompt wizard;

    /**
     * @param groups Gruppen-Verwaltung; {@code group scale} bleibt ohne Skalierung
     */
    public GroupCommandLine(GroupManager groups) {
        this(groups, null);
    }

    /**
     * @param groups Gruppen-Verwaltung
     * @param scaler manuelles Skalieren, {@code null} wenn nicht verfügbar (z. B. in Tests)
     */
    public GroupCommandLine(GroupManager groups, GroupScaler scaler) {
        this.groups = Objects.requireNonNull(groups, "groups");
        this.scaler = scaler;
    }

    /**
     * Führt ein Gruppen-Kommando aus.
     *
     * @param tokens vollständige Tokens der Zeile, z. B. {@code [group, create, Lobby, --platform, paper]}
     * @return Ausgabe für das Terminal
     */
    public String execute(String[] tokens) {
        if (tokens.length == 0) {
            return Theme.help(USAGE.stripTrailing());
        }
        if (tokens[0].equalsIgnoreCase("maintenance")) {
            return maintenance(tokens);
        }
        return group(tokens);
    }

    /**
     * @return die Namen aller Gruppen, alphabetisch; leer wenn die Gruppen nicht lesbar sind
     */
    public List<String> names() {
        return groupList().stream().map(ServiceGroup::getName).toList();
    }

    /**
     * Hinterlegt den interaktiven Wizard für {@code group create <name>}.
     *
     * @param prompt Ein-/Ausgabe des Wizards, {@code null} schaltet ihn ab
     */
    void wizard(GroupWizard.Prompt prompt) {
        this.wizard = prompt;
    }

    /**
     * @return alle Gruppen, wie sie der {@link GroupManager} liefert; leer, wenn sie
     *         nicht lesbar sind (Tab-Vervollständigung und {@code status})
     */
    public List<ServiceGroup> groupList() {
        try {
            return groups.all();
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private String group(String[] tokens) {
        if (tokens.length < 2) {
            return USAGE.stripTrailing();
        }
        String command = tokens[1].toLowerCase(Locale.ROOT);
        return switch (command) {
            case "help" -> Theme.help(USAGE.stripTrailing());
            case "list" -> list();
            case "info" -> tokens.length < 3
                    ? Theme.warn("Gruppenname fehlt (group info <name>)")
                    : info(tokens[2]);
            case "create" -> create(tokens);
            case "edit" -> edit(tokens);
            case "delete" -> delete(tokens);
            case "scale" -> scale(tokens);
            default -> Theme.warn("Unbekanntes Gruppen-Kommando: " + command) + NL
                    + Theme.help(USAGE.stripTrailing());
        };
    }

    private String list() {
        List<ServiceGroup> groups = new ArrayList<>(this.groups.all());
        if (groups.isEmpty()) {
            return Theme.dim("Keine Gruppen definiert.");
        }
        groups.sort(Comparator.comparing(ServiceGroup::getName, String.CASE_INSENSITIVE_ORDER));
        Table table = Table.of("Name", "Typ", "Plattform", "Version", "Modus", "Min/Max",
                "Fallback", "Wartung");
        for (ServiceGroup group : groups) {
            table.row(group.getName(), group.getType().name(), group.getPlatform(),
                    group.getVersion().isBlank() ? Theme.dim("neueste") : group.getVersion(),
                    group.getMode().name(),
                    group.getMinServices() + "/"
                            + (group.getMaxServices() == 0 ? "unbegrenzt" : group.getMaxServices()),
                    group.getFallback() ? Theme.green("ja (" + group.getFallbackPriority() + ")")
                            : Theme.dim("nein"),
                    Theme.maintenance(group.getMaintenance()));
        }
        return table.render();
    }

    private String info(String name) {
        Optional<ServiceGroup> found = groups.get(name);
        if (found.isEmpty()) {
            return Theme.warn("Unbekannte Gruppe: " + name);
        }
        ServiceGroup group = found.get();
        StringBuilder out = new StringBuilder()
                .append(Theme.field("Name", Theme.bold(group.getName()))).append(NL)
                .append(Theme.field("Typ", group.getType().name())).append(NL)
                .append(Theme.field("Plattform", group.getPlatform()
                        + (group.getVersion().isBlank() ? " (neueste)" : " " + group.getVersion()))).append(NL)
                .append(Theme.field("Modus", group.getMode().name())).append(NL)
                .append(Theme.field("Services", "min " + group.getMinServices()
                        + ", max " + (group.getMaxServices() == 0 ? "unbegrenzt" : group.getMaxServices())))
                .append(NL)
                .append(Theme.field("Spieler je Service", String.valueOf(group.getMaxPlayers()))).append(NL)
                .append(Theme.field("Speicher", group.getMemory() + " MB")).append(NL)
                .append(Theme.field("Fallback", (group.getFallback() ? Theme.green("ja") : Theme.dim("nein"))
                        + " (Priorität " + group.getFallbackPriority() + ")")).append(NL)
                .append(Theme.field("Wartung", Theme.maintenance(group.getMaintenance()))).append(NL)
                .append(Theme.field("Runtime", group.hasRuntime() ? group.getRuntime().name() : "global")).append(NL)
                .append(Theme.field("Java-Version",
                        group.hasJavaVersion() ? String.valueOf(group.getJavaVersion()) : "global")).append(NL)
                .append(Theme.field("Templates", group.getTemplatesCount() == 0
                        ? "keine" : String.join(", ", group.getTemplatesList()))).append(NL)
                .append(Theme.field("JVM-Argumente", join(group.getJvmArgsList()))).append(NL)
                .append(Theme.field("Java-Argumente", join(group.getJavaArgsList())));
        if (group.getScalingRulesCount() > 0) {
            out.append(NL).append(Theme.section("Skalierungsregeln"));
            for (ScalingRule rule : group.getScalingRulesList()) {
                out.append(NL).append("  ").append(describe(rule));
            }
        }
        return out.toString();
    }

    private String create(String[] tokens) {
        if (tokens.length < 3) {
            return Theme.warn("Gruppenname fehlt (group create <name>)");
        }
        String name = tokens[2];
        if (tokens.length == 3 && wizard != null) {
            return createWithWizard(name);
        }
        ServiceGroup.Builder builder = defaultBuilder(name);
        try {
            Map<String, List<String>> options = options(tokens, 3);
            apply(builder, options);
            inferProxyRole(builder, options);
        } catch (IllegalArgumentException e) {
            return Theme.fail("Fehler: " + e.getMessage());
        }
        return save(builder.build());
    }

    /**
     * Eine Proxy-Plattform ohne {@code --type} wird zum Proxy statt zum Server mit
     * Proxy-Jar. Ohne {@code --mode} passt STATIC dazu — ein Proxy belegt genau einen Port.
     *
     * @param builder  Definition aus den Optionen
     * @param options  angegebene Optionen; ein ausdrückliches {@code --type} gewinnt
     */
    private static void inferProxyRole(ServiceGroup.Builder builder, Map<String, List<String>> options) {
        if (options.containsKey("type") || !Platforms.isProxy(builder.getPlatform())) {
            return;
        }
        builder.setType(ServiceType.PROXY);
        if (!options.containsKey("mode")) {
            builder.setMode(ServiceMode.STATIC);
        }
    }

    /**
     * Fragt die Gruppe interaktiv ab und legt sie an. Ein Abbruch legt nichts an.
     *
     * @param name Name der neuen Gruppe
     * @return Ergebnis für das Terminal
     */
    private String createWithWizard(String name) {
        if (groups.get(name).isPresent()) {
            return Theme.fail("Fehler: Gruppe '" + name + "' existiert bereits.");
        }
        Optional<GroupWizard.Draft> draft = GroupWizard.collect(wizard, name);
        if (draft.isEmpty()) {
            return Theme.warn("Gruppe '" + name + "' nicht angelegt — Wizard abgebrochen.");
        }
        GroupWizard.Draft values = draft.get();
        ServiceGroup group = defaultBuilder(name)
                .setType(values.type())
                .setMode(values.mode())
                .setPlatform(values.platform())
                .setVersion(values.version())
                .setMinServices(values.minServices())
                .setMaxServices(values.maxServices())
                .setMaxPlayers(values.maxPlayers())
                .setMemory(values.memory())
                .setFallback(values.fallback())
                .setFallbackPriority(values.fallbackPriority())
                .addAllTemplates(values.templates())
                .build();
        return save(group);
    }

    private static ServiceGroup.Builder defaultBuilder(String name) {
        return ServiceGroup.newBuilder()
                .setName(name)
                .setType(ServiceType.SERVER)
                .setMode(ServiceMode.DYNAMIC)
                .setMinServices(DEFAULT_MIN_SERVICES)
                .setMaxServices(DEFAULT_MAX_SERVICES)
                .setMaxPlayers(DEFAULT_MAX_PLAYERS)
                .setMemory(DEFAULT_MEMORY_MB);
    }

    private String save(ServiceGroup group) {
        try {
            ServiceGroup created = groups.create(group);
            return Theme.ok("Gruppe '" + created.getName() + "' angelegt: " + summary(created));
        } catch (GroupValidationException e) {
            return Theme.fail("Fehler: " + e.getMessage());
        }
    }

    private String edit(String[] tokens) {
        if (tokens.length < 3) {
            return Theme.warn("Gruppenname fehlt (group edit <name> [optionen])");
        }
        String name = tokens[2];
        Optional<ServiceGroup> found = groups.get(name);
        if (found.isEmpty()) {
            return Theme.warn("Unbekannte Gruppe: " + name);
        }
        ServiceGroup.Builder builder = found.get().toBuilder();
        try {
            apply(builder, options(tokens, 3));
        } catch (IllegalArgumentException e) {
            return Theme.fail("Fehler: " + e.getMessage());
        }
        try {
            ServiceGroup updated = groups.update(builder.build());
            return Theme.ok("Gruppe '" + updated.getName() + "' aktualisiert: " + summary(updated));
        } catch (GroupValidationException e) {
            return Theme.fail("Fehler: " + e.getMessage());
        }
    }

    private String delete(String[] tokens) {
        if (tokens.length < 3) {
            return Theme.warn("Gruppenname fehlt (group delete <name>)");
        }
        String name = tokens[2];
        if (!groups.delete(name)) {
            return Theme.warn("Unbekannte Gruppe: " + name);
        }
        return Theme.ok("Gruppe '" + name + "' gelöscht (laufende Services laufen weiter).");
    }

    /**
     * Setzt die manuelle Regel um (§11, §17).
     *
     * @param tokens vollständige Tokens, z. B. {@code [group, scale, Lobby, 3]}
     * @return Ergebnis der Skalierung
     */
    private String scale(String[] tokens) {
        if (tokens.length < 4) {
            return Theme.warn("Verwendung: group scale <name> <anzahl>");
        }
        if (scaler == null) {
            return Theme.warn("Manuelles Skalieren ist nicht verfügbar.");
        }
        String name = tokens[2];
        int target;
        try {
            target = intOf(tokens[3], "anzahl");
        } catch (IllegalArgumentException e) {
            return Theme.fail("Fehler: " + e.getMessage());
        }

        List<ScalingDecision> decisions;
        try {
            decisions = scaler.scaleTo(name, target);
        } catch (IllegalArgumentException | IllegalStateException e) {
            // Unbekannte Gruppe, Ziel außerhalb min/max oder laufender Scaling-Lauf.
            return Theme.fail("Fehler: " + e.getMessage());
        }
        return Theme.ok(describeScale(name, target, decisions));
    }

    /**
     * @param name      Gruppenname
     * @param target    gewünschte Anzahl
     * @param decisions ausgeführte Entscheidungen
     * @return zweizeilige Zusammenfassung mit den Einzelentscheidungen
     */
    private String describeScale(String name, int target, List<ScalingDecision> decisions) {
        long started = decisions.stream()
                .filter(decision -> decision.action() == ScalingDecision.Action.START).count();
        long stopped = decisions.stream()
                .filter(decision -> decision.action() == ScalingDecision.Action.STOP).count();
        StringBuilder out = new StringBuilder("Gruppe '").append(Theme.bold(name)).append("': Ziel ")
                .append(target).append(" ").append(Theme.dash()).append(" ")
                .append(Theme.green(started + " gestartet")).append(", ")
                .append(Theme.yellow(stopped + " gestoppt")).append(".");
        for (ScalingDecision decision : decisions) {
            out.append(NL).append("  ").append(Theme.arrow()).append(' ').append(decision.action())
                    .append(' ').append(decision.current()).append(" ").append(Theme.arrow()).append(" ")
                    .append(decision.desired());
            if (!decision.reason().isBlank()) {
                out.append(": ").append(Theme.dim(decision.reason()));
            }
        }
        groups.get(name).filter(group -> target < group.getMinServices()).ifPresent(group -> out.append(NL)
                .append("  ").append(Theme.warn("unter minServices (" + group.getMinServices()
                        + ") — der nächste automatische Lauf skaliert wieder hoch.")));
        return out.toString();
    }

    private String maintenance(String[] tokens) {
        if (tokens.length < 3) {
            return Theme.warn("Verwendung: maintenance <name> on|off");
        }
        String name = tokens[1];
        String value = tokens[2].toLowerCase(Locale.ROOT);
        Boolean maintenance = switch (value) {
            case "on", "an", "ein", "true", "1" -> Boolean.TRUE;
            case "off", "aus", "false", "0" -> Boolean.FALSE;
            default -> null;
        };
        if (maintenance == null) {
            return Theme.warn("Erwartet 'on' oder 'off': maintenance " + name + " on|off");
        }
        try {
            groups.setMaintenance(name, maintenance);
        } catch (GroupValidationException e) {
            return Theme.fail("Fehler: " + e.getMessage());
        }
        return Theme.ok("Gruppe '" + name + "': Wartungsmodus " + Theme.maintenance(maintenance)
                + (maintenance ? " — keine neuen Services, bestehende laufen weiter" : ""));
    }

    private static void apply(ServiceGroup.Builder builder, Map<String, List<String>> options) {
        for (Map.Entry<String, List<String>> option : options.entrySet()) {
            List<String> values = option.getValue();
            String value = values.getFirst();
            switch (option.getKey()) {
                case "type" -> builder.setType(enumOf(ServiceType.class, value, "type"));
                case "platform" -> builder.setPlatform(value.trim());
                case "version" -> builder.setVersion(value.trim());
                case "mode" -> builder.setMode(enumOf(ServiceMode.class, value, "mode"));
                case "min" -> builder.setMinServices(intOf(value, "min"));
                case "max" -> builder.setMaxServices(intOf(value, "max"));
                case "max-players" -> builder.setMaxPlayers(intOf(value, "max-players"));
                case "memory" -> builder.setMemory(intOf(value, "memory"));
                case "template" -> {
                    builder.clearTemplates();
                    builder.addAllTemplates(values.stream().map(String::trim).toList());
                }
                case "fallback" -> builder.setFallback(true);
                case "no-fallback" -> builder.setFallback(false);
                case "fallback-priority" -> builder.setFallbackPriority(intOf(value, "fallback-priority"));
                case "java-version" -> builder.setJavaVersion(intOf(value, "java-version"));
                case "runtime" -> builder.setRuntime(enumOf(RuntimeType.class, value, "runtime"));
                case "jvm-arg" -> {
                    builder.clearJvmArgs();
                    builder.addAllJvmArgs(values);
                }
                case "java-arg" -> {
                    builder.clearJavaArgs();
                    builder.addAllJavaArgs(values);
                }
                default -> throw new IllegalArgumentException("Unbekannte Option: --" + option.getKey());
            }
        }
    }

    private static Map<String, List<String>> options(String[] tokens, int from) {
        Map<String, List<String>> options = new LinkedHashMap<>();
        for (int index = from; index < tokens.length; index++) {
            String token = tokens[index];
            if (!token.startsWith("--")) {
                throw new IllegalArgumentException("Unerwartetes Argument: '" + token
                        + "' (Optionen beginnen mit --)");
            }
            String key = token.substring(2).toLowerCase(Locale.ROOT);
            if (FLAGS.contains(key)) {
                options.computeIfAbsent(key, ignored -> new ArrayList<>()).add("true");
                continue;
            }
            if (!VALUE_OPTIONS.contains(key)) {
                throw new IllegalArgumentException("Unbekannte Option: --" + key);
            }
            if (index + 1 >= tokens.length) {
                throw new IllegalArgumentException("Wert fehlt für --" + key);
            }
            options.computeIfAbsent(key, ignored -> new ArrayList<>()).add(tokens[++index]);
        }
        return options;
    }

    private static <E extends Enum<E>> E enumOf(Class<E> type, String value, String option) {
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Ungültiger Wert für --" + option + ": '" + value
                    + "' (erlaubt: " + Arrays.stream(type.getEnumConstants())
                    .map(Enum::name).collect(Collectors.joining(", ")) + ")");
        }
    }

    private static int intOf(String value, String option) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Ungültiger Wert für --" + option + ": '" + value + "'");
        }
    }

    private static String summary(ServiceGroup group) {
        return group.getType() + " " + group.getPlatform()
                + (group.getVersion().isBlank() ? "" : " " + group.getVersion())
                + ", " + group.getMode()
                + ", min " + group.getMinServices()
                + " / max " + (group.getMaxServices() == 0 ? "unbegrenzt" : group.getMaxServices())
                + ", Spieler " + group.getMaxPlayers()
                + ", " + group.getMemory() + " MB";
    }

    private static String describe(ScalingRule rule) {
        String name = rule.getId().isBlank() ? "<ohne ID>" : rule.getId();
        String state = rule.getEnabled() ? "aktiv" : "inaktiv";
        return switch (rule.getRuleCase()) {
            case PLAYER_COUNT -> name + " [" + state + "] spielerzahl: ab "
                    + rule.getPlayerCount().getScaleUpUtilization() + " hoch, leer ab "
                    + rule.getPlayerCount().getIdleUtilization() + " für "
                    + rule.getPlayerCount().getIdleMinutes() + " min";
            case SCHEDULE -> name + " [" + state + "] zeitplan: '" + rule.getSchedule().getCron()
                    + "' min " + rule.getSchedule().getMinServices()
                    + " / max " + rule.getSchedule().getMaxServices()
                    + (rule.getSchedule().getTimezone().isBlank()
                    ? "" : " (" + rule.getSchedule().getTimezone() + ")");
            case LOAD -> name + " [" + state + "] last: CPU " + rule.getLoad().getMaxHostCpu()
                    + " %, RAM " + rule.getLoad().getMaxHostMemory() + " %";
            case RULE_NOT_SET -> name + " [" + state + "] ohne Typ";
        };
    }

    private static String join(List<String> values) {
        return values.isEmpty() ? "keine" : String.join(" ", values);
    }
}
