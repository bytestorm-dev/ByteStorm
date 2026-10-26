package dev.bytestorm.controller.scaling;

import dev.bytestorm.api.service.ServiceId;
import dev.bytestorm.controller.group.GroupScaler;
import dev.bytestorm.core.config.ScalingSettings;
import dev.bytestorm.node.lifecycle.ServiceLifecycleException;
import dev.bytestorm.proto.LoadRule;
import dev.bytestorm.proto.PlayerCountRule;
import dev.bytestorm.proto.ScheduleRule;
import dev.bytestorm.proto.ScalingRule;
import dev.bytestorm.proto.ServiceGroup;
import dev.bytestorm.proto.ServiceInfo;
import dev.bytestorm.proto.ServiceMode;
import dev.bytestorm.proto.ServiceState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * ScalingManager (§11).
 *
 * <p>Der Manager vergleicht alle {@link ScalingSettings#intervalSeconds()} Sekunden
 * den Ist-Zustand einer Gruppe mit ihrem Soll und führt höchstens eine
 * Start-Aktion pro Lauf und Gruppe aus. Umgesetzt sind:
 * <ul>
 *   <li>hartes Minimum und Maximum aus {@link ServiceGroup} (Maximum {@code 0}
 *       bedeutet „nicht gesetzt“),</li>
 *   <li>die Spielerzahl-Regel ({@link PlayerCountRule}) für Hochskalieren und
 *       Leerlauf,</li>
 *   <li>Zeitplan-Regeln ({@link ScheduleRule}) mit Fünf-Feld-Cron und Zeitzone,</li>
 *   <li>Host-Last-Regeln ({@link LoadRule}); bei Überschreitung wird nicht
 *       gestartet, wohl aber überzähliger Service abgebaut,</li>
 *   <li>Wartungsmodus, Start-Limit, Cooldown und Start-Fehler-Backoff,</li>
 *   <li>kein automatischer Stop von statischen Services oder Services mit
 *       Spielern.</li>
 * </ul>
 *
 * <p>{@link #tick()} führt Aktionen aus. {@link #explain()} nutzt denselben
 * Entscheidungspfad, verändert aber weder Services noch interne Zeitstempel.
 * {@link #scaleTo(String, int)} setzt die manuelle Regel um (§11, §17): ein
 * genanntes Ziel wird ohne Rücksicht auf Cooldown und Last sofort hergestellt,
 * aber weiterhin innerhalb von {@code maxServices} und ohne Services mit
 * Spielern zu stoppen.
 *
 * <p>Zeitzonen: Ist bei einer Zeitplan-Regel keine Zone gesetzt, gilt die
 * Systemzone des Hosts. Für reproduzierbare Deployments sollte die Zone
 * explizit angegeben werden.
 */
public final class ScalingManager implements AutoCloseable, GroupScaler {

    private static final Logger log = LoggerFactory.getLogger(ScalingManager.class);

    /** Wartezeit zwischen zwei automatischen Skalierungen derselben Gruppe. */
    static final Duration DEFAULT_COOLDOWN = Duration.ofSeconds(30);

    /** Höchstzahl neuer Services pro Gruppe und Lauf. */
    static final int DEFAULT_MAX_STARTS_PER_CYCLE = 1;

    private final Supplier<List<ServiceGroup>> groups;
    private final Supplier<List<ServiceInfo>> services;
    private final ScalingActions actions;
    private final ScalingSettings settings;
    private final Clock clock;
    private final Duration cooldown;
    private final int maxStartsPerCycle;
    private final HostMetrics hostMetrics;

    private final Map<String, Instant> lastAction = new HashMap<>();
    private final Map<String, Instant> lastFailure = new HashMap<>();
    private final Map<String, Instant> idleSince = new HashMap<>();
    private final Map<String, CronSchedule> cronCache = new HashMap<>();
    private final AtomicBoolean evaluating = new AtomicBoolean();

    private ScheduledExecutorService scheduler;
    private boolean started;

    /**
     * @param groups   liefert alle Gruppen aus MongoDB
     * @param services liefert alle registrierten Services
     * @param actions  Start/Stop über den Controller
     * @param settings Intervall aus {@code cloud.json}
     */
    public ScalingManager(Supplier<List<ServiceGroup>> groups,
                          Supplier<List<ServiceInfo>> services,
                          ScalingActions actions,
                          ScalingSettings settings) {
        this(groups, services, actions, settings, Clock.systemUTC(),
                DEFAULT_COOLDOWN, DEFAULT_MAX_STARTS_PER_CYCLE, new SystemHostMetrics());
    }

    ScalingManager(Supplier<List<ServiceGroup>> groups,
                   Supplier<List<ServiceInfo>> services,
                   ScalingActions actions,
                   ScalingSettings settings,
                   Clock clock,
                   Duration cooldown,
                   int maxStartsPerCycle) {
        this(groups, services, actions, settings, clock, cooldown, maxStartsPerCycle,
                new SystemHostMetrics());
    }

    ScalingManager(Supplier<List<ServiceGroup>> groups,
                   Supplier<List<ServiceInfo>> services,
                   ScalingActions actions,
                   ScalingSettings settings,
                   Clock clock,
                   Duration cooldown,
                   int maxStartsPerCycle,
                   HostMetrics hostMetrics) {
        this.groups = Objects.requireNonNull(groups, "groups");
        this.services = Objects.requireNonNull(services, "services");
        this.actions = Objects.requireNonNull(actions, "actions");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.cooldown = Objects.requireNonNull(cooldown, "cooldown");
        this.hostMetrics = Objects.requireNonNull(hostMetrics, "hostMetrics");
        if (cooldown.isNegative()) {
            throw new IllegalArgumentException("cooldown darf nicht negativ sein: " + cooldown);
        }
        if (maxStartsPerCycle < 1) {
            throw new IllegalArgumentException("maxStartsPerCycle muss positiv sein: " + maxStartsPerCycle);
        }
        this.maxStartsPerCycle = maxStartsPerCycle;
    }

    /**
     * Startet den periodischen Lauf auf einem Virtual Thread. Wiederholte
     * Aufrufe sind idempotent.
     */
    public synchronized void start() {
        if (started) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(
                runnable -> Thread.ofVirtual().name("bytestorm-scaling", 0).unstarted(runnable));
        long interval = Math.max(1, settings.intervalSeconds());
        scheduler.scheduleWithFixedDelay(this::tick, interval, interval, TimeUnit.SECONDS);
        started = true;
        log.info("ScalingManager gestartet (Intervall {} s)", interval);
    }

    /**
     * Führt einen Scaling-Lauf aus.
     *
     * @return die ausgeführten oder versuchten Entscheidungen
     */
    public List<ScalingDecision> tick() {
        return run(true);
    }

    /**
     * Berechnet einen Scaling-Lauf, ohne Services zu starten oder zu stoppen und
     * ohne Cooldown-/Leerlauf-Zeitstempel zu verändern.
     *
     * @return die geplanten Entscheidungen
     */
    public List<ScalingDecision> explain() {
        return run(false);
    }

    /**
     * Bringt eine Gruppe manuell auf die gewünschte Anzahl Services (§11, §17).
     *
     * <p>Die manuelle Regel wartet nicht auf Cooldown, Leerlauf oder freie
     * Host-Last — der Betreiber hat entschieden. Es gelten weiterhin das harte
     * Maximum der Gruppe und die Stop-Schutzregeln: statische Services und
     * Services mit Spielern werden nicht gestoppt, stattdessen berichtet die
     * Entscheidung den verbliebenen Überschuss. Ein Ziel unterhalb des Minimums
     * ist erlaubt; der nächste automatische Lauf hebt es wieder an.
     *
     * @param groupName Gruppenname
     * @param target    gewünschte Anzahl aktiver Services
     * @return die ausgeführten Entscheidungen
     * @throws IllegalArgumentException wenn die Gruppe fehlt oder das Ziel nicht
     *                                  zwischen {@code 0} und {@code maxServices} liegt
     * @throws IllegalStateException    wenn gerade ein Scaling-Lauf aktiv ist
     */
    @Override
    public List<ScalingDecision> scaleTo(String groupName, int target) {
        Objects.requireNonNull(groupName, "group");
        if (target < 0) {
            throw new IllegalArgumentException("Ziel muss 0 oder größer sein: " + target);
        }
        ServiceGroup definition = groups.get().stream()
                .filter(group -> group.getName().equals(groupName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unbekannte Gruppe: " + groupName));
        int maximum = maximum(definition);
        if (target > maximum) {
            throw new IllegalArgumentException("Ziel " + target + " über maxServices (" + maximum
                    + ") der Gruppe '" + groupName + "'");
        }
        if (!evaluating.compareAndSet(false, true)) {
            throw new IllegalStateException("Ein Scaling-Lauf läuft bereits; bitte kurz warten");
        }

        List<ScalingDecision> decisions = new ArrayList<>();
        try {
            Instant now = clock.instant();
            List<ServiceInfo> active = services.get().stream()
                    .filter(info -> info.getGroup().equals(groupName))
                    .filter(ScalingManager::occupiesSlot)
                    .sorted(Comparator.comparingLong(ServiceInfo::getStartedAt).reversed())
                    .toList();
            int current = active.size();
            String reason = "manuell auf " + target + " skaliert";
            if (current == target) {
                add(decisions, definition, ScalingDecision.Action.NONE, "manual", current,
                        "Gruppe ist bereits bei " + target + " Services");
            } else if (current < target) {
                for (int i = current; i < target; i++) {
                    if (!startService(definition, now, "manual", reason, i, i + 1, true, decisions)) {
                        break;
                    }
                }
            } else {
                stopExcess(definition, active, current - target, current, now, "manual", reason, true,
                        decisions);
            }
        } finally {
            evaluating.set(false);
        }
        return List.copyOf(decisions);
    }

    @Override
    public synchronized void close() {
        started = false;
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    private List<ScalingDecision> run(boolean apply) {
        if (!evaluating.compareAndSet(false, true)) {
            return List.of();
        }
        List<ScalingDecision> decisions = new ArrayList<>();
        try {
            evaluate(clock.instant(), apply, decisions);
        } catch (RuntimeException e) {
            log.error("Scaling-Lauf fehlgeschlagen", e);
        } finally {
            evaluating.set(false);
        }
        return List.copyOf(decisions);
    }

    private void evaluate(Instant now, boolean apply, List<ScalingDecision> decisions) {
        List<ServiceGroup> currentGroups = Objects.requireNonNull(groups.get(), "groups");
        List<ServiceInfo> currentServices = Objects.requireNonNull(services.get(), "services");
        Map<String, List<ServiceInfo>> byGroup = currentServices.stream()
                .collect(Collectors.groupingBy(ServiceInfo::getGroup));

        Set<String> seen = new HashSet<>();
        for (ServiceGroup group : currentGroups) {
            seen.add(group.getName());
            List<ServiceInfo> groupServices = byGroup.getOrDefault(group.getName(), List.of());
            try {
                evaluateGroup(group, groupServices, now, apply, decisions);
            } catch (RuntimeException e) {
                log.warn("Scaling für Gruppe '{}' fehlgeschlagen: {}", group.getName(), e.getMessage(), e);
            }
        }
        if (apply) {
            idleSince.keySet().retainAll(seen);
            lastAction.keySet().retainAll(seen);
            lastFailure.keySet().retainAll(seen);
        }
    }

    private void evaluateGroup(ServiceGroup group, List<ServiceInfo> groupServices,
                               Instant now, boolean apply, List<ScalingDecision> decisions) {
        String name = group.getName();
        if (group.getMaintenance()) {
            if (apply) {
                idleSince.remove(name);
            }
            add(decisions, group, ScalingDecision.Action.NONE, "maintenance",
                    countActive(groupServices), "Wartungsmodus");
            return;
        }

        List<ServiceInfo> active = groupServices.stream()
                .filter(ScalingManager::occupiesSlot)
                .sorted(Comparator.comparingLong(ServiceInfo::getStartedAt).reversed())
                .toList();
        int current = active.size();

        ScheduleWindow schedule = scheduleWindow(group, now);
        int maximum = maximum(group);
        if (schedule.active() && schedule.maximum() > 0) {
            maximum = Math.min(maximum, schedule.maximum());
        }
        int minimum = Math.min(Math.max(group.getMinServices(), schedule.minimum()), maximum);
        String minimumRule = schedule.minimum() > group.getMinServices() ? "schedule" : "minimum";
        String maximumRule = schedule.active() && schedule.maximum() > 0 ? "schedule" : "maximum";

        Optional<LoadRule> loadRule = loadRule(group);
        HostLoad hostLoad = loadRule.map(this::hostLoad).orElse(HostLoad.FREE);
        int loadMinimum = loadRule.map(LoadRule::getMinServices).orElse(0);
        if (loadMinimum > minimum) {
            minimum = Math.min(loadMinimum, maximum);
            minimumRule = "load";
        }

        if (current < minimum) {
            if (hostLoad.blocked()) {
                add(decisions, group, ScalingDecision.Action.NONE, "load", current,
                        "Host über Lastschwelle (" + hostLoad.detail() + ")");
                return;
            }
            if (recentFailure(name, now)) {
                add(decisions, group, ScalingDecision.Action.NONE, "start-failure", current,
                        "letzter Start ist fehlgeschlagen; Backoff aktiv");
                return;
            }
            int starts = Math.min(minimum - current, maxStartsPerCycle);
            int started = 0;
            for (int i = 0; i < starts; i++) {
                boolean success = startService(group, now, minimumRule,
                        minimumRule + " " + (current + i) + "/" + minimum,
                        current + i, current + i + 1, apply, decisions);
                if (!success) {
                    break;
                }
                started++;
            }
            if (started == 0) {
                log.warn("Scaling '{}': Minimum {} nicht erreichbar ({} aktiv)", name, minimum, current);
            }
            return;
        }

        if (current > maximum) {
            stopExcess(group, active, current - maximum, current, now, maximumRule,
                    "über Maximum (" + maximum + ")", apply, decisions);
            return;
        }

        Optional<PlayerCountRule> rule = playerCountRule(group);
        if (rule.isEmpty()) {
            if (apply) {
                idleSince.remove(name);
            }
            return;
        }

        PlayerCountRule playerRule = rule.get();
        double utilization = utilization(active);

        if (utilization > playerRule.getScaleUpUtilization() && current < maximum) {
            if (apply) {
                idleSince.remove(name);
            }
            if (hostLoad.blocked()) {
                add(decisions, group, ScalingDecision.Action.NONE, "load", current,
                        "Start wegen Host-Last blockiert (" + hostLoad.detail() + ")");
                return;
            }
            if (recentFailure(name, now)) {
                add(decisions, group, ScalingDecision.Action.NONE, "start-failure", current,
                        "letzter Start ist fehlgeschlagen; Backoff aktiv");
                return;
            }
            if (coolingDown(name, now)) {
                add(decisions, group, ScalingDecision.Action.NONE, "cooldown", current,
                        "Cooldown nach der letzten Aktion aktiv");
                return;
            }
            int requested = Math.max(1, playerRule.getScaleUpServices());
            int starts = Math.min(requested, Math.min(maxStartsPerCycle, maximum - current));
            for (int i = 0; i < starts; i++) {
                boolean success = startService(group, now, "player-count",
                        "Auslastung " + Math.round(utilization * 100) + " %",
                        current + i, current + i + 1, apply, decisions);
                if (!success) {
                    break;
                }
            }
            return;
        }

        scaleDownWhenIdle(group, active, playerRule, current, minimum, now, apply, decisions);
    }

    private void stopExcess(ServiceGroup group, List<ServiceInfo> active, int excess,
                            int current, Instant now, String rule, String reason,
                            boolean apply, List<ScalingDecision> decisions) {
        if (group.getMode() != ServiceMode.DYNAMIC) {
            add(decisions, group, ScalingDecision.Action.NONE, rule, current,
                    reason + ", aber statische Services werden nicht gestoppt");
            log.warn("Scaling '{}': {}, aber statische Services werden nicht gestoppt",
                    group.getName(), reason);
            return;
        }
        int stopped = 0;
        int remaining = current;
        for (ServiceInfo service : active) {
            if (stopped >= excess) {
                break;
            }
            if (canStop(service) && stopService(service, now, rule, reason,
                    remaining, remaining - 1, apply, decisions)) {
                stopped++;
                remaining--;
            }
        }
        if (stopped < excess) {
            add(decisions, group, ScalingDecision.Action.NONE, rule, current,
                    reason + ": " + excess + " Services zu viel, aber nur " + stopped
                            + " ohne Spieler stoppbar");
            log.warn("Scaling '{}': {} — {} Services zu viel, aber nur {} ohne Spieler stoppbar",
                    group.getName(), reason, excess, stopped);
        }
    }

    private void scaleDownWhenIdle(ServiceGroup group, List<ServiceInfo> active,
                                   PlayerCountRule rule, int current, int minimum,
                                   Instant now, boolean apply, List<ScalingDecision> decisions) {
        String name = group.getName();
        long players = totalPlayers(active);
        boolean idle = players == 0 && utilization(active) <= rule.getIdleUtilization();
        if (rule.getIdleMinutes() == 0 || !idle || current <= minimum) {
            if (apply) {
                idleSince.remove(name);
            }
            return;
        }

        Instant since = idleSince.get(name);
        if (since == null) {
            if (apply) {
                idleSince.put(name, now);
            }
            return;
        }
        Duration idleDuration = Duration.ofMinutes(rule.getIdleMinutes());
        if (Duration.between(since, now).compareTo(idleDuration) < 0) {
            return;
        }
        if (coolingDown(name, now)) {
            add(decisions, group, ScalingDecision.Action.NONE, "cooldown", current,
                    "Leerlauf erreicht, aber Cooldown aktiv");
            return;
        }
        if (group.getMode() != ServiceMode.DYNAMIC) {
            add(decisions, group, ScalingDecision.Action.NONE, "player-count", current,
                    "Leerlauf, aber statische Services werden nicht gestoppt");
            return;
        }

        Optional<ServiceInfo> candidate = active.stream()
                .filter(ScalingManager::canStop)
                .findFirst();
        if (candidate.isEmpty()) {
            add(decisions, group, ScalingDecision.Action.NONE, "player-count", current,
                    "Leerlauf, aber kein Service ohne Spieler stoppbar");
            return;
        }
        if (stopService(candidate.get(), now, "player-count",
                "Leerlauf seit " + rule.getIdleMinutes() + " min", current, current - 1,
                apply, decisions) && apply) {
            idleSince.put(name, now);
        }
    }

    private boolean startService(ServiceGroup group, Instant now, String rule, String reason,
                                 int current, int desired, boolean apply,
                                 List<ScalingDecision> decisions) {
        if (!apply) {
            add(decisions, group, ScalingDecision.Action.START, rule, current, desired, reason);
            return true;
        }
        try {
            actions.start(group.getName());
            lastAction.put(group.getName(), now);
            lastFailure.remove(group.getName());
            add(decisions, group, ScalingDecision.Action.START, rule, current, desired, reason);
            log.info("Scaling '{}': Start ({})", group.getName(), reason);
            return true;
        } catch (ServiceLifecycleException | RuntimeException e) {
            lastFailure.put(group.getName(), now);
            add(decisions, group, ScalingDecision.Action.NONE, "start-failure", current,
                    "Start fehlgeschlagen: " + e.getMessage());
            log.warn("Scaling '{}': Start fehlgeschlagen ({}): {}",
                    group.getName(), reason, e.getMessage());
            return false;
        }
    }

    private boolean stopService(ServiceInfo service, Instant now, String rule, String reason,
                                int current, int desired, boolean apply,
                                List<ScalingDecision> decisions) {
        if (!apply) {
            add(decisions, group(service), ScalingDecision.Action.STOP, rule, current, desired, reason);
            return true;
        }
        try {
            actions.stop(ServiceId.parse(service.getId()), false);
            lastAction.put(service.getGroup(), now);
            add(decisions, group(service), ScalingDecision.Action.STOP, rule, current, desired, reason);
            log.info("Scaling '{}': Stop von '{}' ({})", service.getGroup(), service.getName(), reason);
            return true;
        } catch (ServiceLifecycleException | RuntimeException e) {
            add(decisions, group(service), ScalingDecision.Action.NONE, rule, current,
                    "Stop fehlgeschlagen: " + e.getMessage());
            log.warn("Scaling '{}': Stop von '{}' fehlgeschlagen ({}): {}",
                    service.getGroup(), service.getName(), reason, e.getMessage());
            return false;
        }
    }

    private void add(List<ScalingDecision> decisions, ServiceGroup group,
                     ScalingDecision.Action action, String rule, int current, String reason) {
        int desired = action == ScalingDecision.Action.START ? current + 1
                : action == ScalingDecision.Action.STOP ? Math.max(0, current - 1) : current;
        decisions.add(new ScalingDecision(group.getName(), action, rule, current, desired, reason));
    }

    private void add(List<ScalingDecision> decisions, ServiceGroup group,
                     ScalingDecision.Action action, String rule, int current, int desired, String reason) {
        decisions.add(new ScalingDecision(group.getName(), action, rule, current, desired, reason));
    }

    private boolean recentFailure(String group, Instant now) {
        Instant last = lastFailure.get(group);
        return last != null && Duration.between(last, now).compareTo(cooldown) < 0;
    }

    private boolean coolingDown(String group, Instant now) {
        Instant last = lastAction.get(group);
        return last != null && Duration.between(last, now).compareTo(cooldown) < 0;
    }

    private HostLoad hostLoad(LoadRule rule) {
        try {
            double cpu = clamp(hostMetrics.cpuLoad()) * 100;
            double memory = clamp(hostMetrics.memoryLoad()) * 100;
            boolean blocked = (rule.getMaxHostCpu() > 0 && cpu > rule.getMaxHostCpu())
                    || (rule.getMaxHostMemory() > 0 && memory > rule.getMaxHostMemory());
            String detail = String.format(Locale.ROOT,
                    "CPU %.1f %%, RAM %.1f %%; Grenzen CPU %.1f %%, RAM %.1f %%",
                    cpu, memory, rule.getMaxHostCpu(), rule.getMaxHostMemory());
            return new HostLoad(blocked, detail);
        } catch (RuntimeException e) {
            log.warn("Host-Metriken nicht lesbar: {}", e.getMessage());
            return new HostLoad(false, "Host-Metriken nicht lesbar");
        }
    }

    private ScheduleWindow scheduleWindow(ServiceGroup group, Instant now) {
        boolean active = false;
        int minimum = 0;
        int maximum = 0;
        for (ScalingRule rule : group.getScalingRulesList()) {
            if (!rule.getEnabled() || rule.getRuleCase() != ScalingRule.RuleCase.SCHEDULE) {
                continue;
            }
            ScheduleRule schedule = rule.getSchedule();
            try {
                ZoneId zone = schedule.getTimezone().isBlank()
                        ? ZoneId.systemDefault()
                        : ZoneId.of(schedule.getTimezone());
                CronSchedule cron = cronCache.computeIfAbsent(schedule.getCron(), CronSchedule::parse);
                if (cron.matches(now.atZone(zone))) {
                    active = true;
                    minimum = Math.max(minimum, schedule.getMinServices());
                    if (schedule.getMaxServices() > 0) {
                        maximum = maximum == 0
                                ? schedule.getMaxServices()
                                : Math.min(maximum, schedule.getMaxServices());
                    }
                }
            } catch (RuntimeException e) {
                log.warn("Ungültige Zeitplan-Regel '{}' in Gruppe '{}': {}",
                        rule.getId(), group.getName(), e.getMessage());
            }
        }
        return new ScheduleWindow(active, minimum, maximum);
    }

    private static Optional<PlayerCountRule> playerCountRule(ServiceGroup group) {
        return group.getScalingRulesList().stream()
                .filter(ScalingRule::getEnabled)
                .filter(rule -> rule.getRuleCase() == ScalingRule.RuleCase.PLAYER_COUNT)
                .map(ScalingRule::getPlayerCount)
                .findFirst();
    }

    private static Optional<LoadRule> loadRule(ServiceGroup group) {
        return group.getScalingRulesList().stream()
                .filter(ScalingRule::getEnabled)
                .filter(rule -> rule.getRuleCase() == ScalingRule.RuleCase.LOAD)
                .map(ScalingRule::getLoad)
                .findFirst();
    }

    private static int maximum(ServiceGroup group) {
        return group.getMaxServices() == 0 ? Integer.MAX_VALUE : group.getMaxServices();
    }

    private static int countActive(List<ServiceInfo> services) {
        return (int) services.stream().filter(ScalingManager::occupiesSlot).count();
    }

    private static boolean occupiesSlot(ServiceInfo info) {
        return switch (info.getState()) {
            case PREPARING, STARTING, ONLINE, STOPPING -> true;
            default -> false;
        };
    }

    private static boolean canStop(ServiceInfo info) {
        return info.getState() == ServiceState.ONLINE && info.getOnlinePlayers() == 0;
    }

    private static long totalPlayers(List<ServiceInfo> active) {
        return active.stream()
                .filter(info -> info.getState() == ServiceState.ONLINE)
                .mapToLong(ServiceInfo::getOnlinePlayers)
                .sum();
    }

    private static double utilization(List<ServiceInfo> active) {
        long players = 0;
        long capacity = 0;
        for (ServiceInfo info : active) {
            if (info.getState() != ServiceState.ONLINE) {
                continue;
            }
            players += info.getOnlinePlayers();
            capacity += info.getMaxPlayers();
        }
        return capacity == 0 ? 0 : (double) players / capacity;
    }

    private static double clamp(double value) {
        if (Double.isNaN(value) || value < 0) {
            return 0;
        }
        return Math.min(value, 1);
    }

    private static ServiceGroup group(ServiceInfo service) {
        return ServiceGroup.newBuilder().setName(service.getGroup()).build();
    }

    private record ScheduleWindow(boolean active, int minimum, int maximum) {
    }

    private record HostLoad(boolean blocked, String detail) {
        private static final HostLoad FREE = new HostLoad(false, "unter den Lastschwellen");
    }
}
