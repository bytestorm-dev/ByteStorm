package dev.bytestorm.cli;

import dev.bytestorm.controller.group.GroupManager;
import dev.bytestorm.controller.group.GroupScaler;
import dev.bytestorm.controller.scaling.ScalingDecision;
import dev.bytestorm.core.config.PortSettings;
import dev.bytestorm.core.mongo.repository.GroupStore;
import dev.bytestorm.core.redis.GroupCache;
import dev.bytestorm.proto.RuntimeType;
import dev.bytestorm.proto.ServiceGroup;
import dev.bytestorm.proto.ServiceMode;
import dev.bytestorm.proto.ServiceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GroupCommandLineTest {

    /** Entfernt Tabellenabstände: Zellen bleiben durch genau ein Leerzeichen getrennt. */
    private static String plain(String text) {
        return String.join("\n", text.lines()
                .map(line -> line.strip().replaceAll("\\s+", " "))
                .toList());
    }

    private final Map<String, ServiceGroup> stored = new LinkedHashMap<>();
    private final List<String> templates = new ArrayList<>(List.of("ALL", "Lobby", "Survival"));

    private GroupCommandLine cli;

    @BeforeEach
    void setUp() {
        cli = new GroupCommandLine(manager());
    }

    @Test
    void withoutASubcommandTheUsageIsShown() {
        assertTrue(cli.execute("group".split("\\s+")).contains("group create"));
        assertTrue(cli.execute("group help".split("\\s+")).contains("--fallback"));
    }

    @Test
    void createUsesDefaults() {
        String output = execute("group create Lobby --platform paper");

        assertTrue(output.contains("Gruppe 'Lobby' angelegt"), output);
        assertTrue(output.contains("SERVER"), output);
        assertTrue(output.contains("min 1 / max 1"), output);
        ServiceGroup group = stored.get("Lobby");
        assertEquals("paper", group.getPlatform());
        assertEquals(ServiceType.SERVER, group.getType());
        assertEquals(ServiceMode.DYNAMIC, group.getMode());
        assertEquals(1, group.getMinServices());
        assertEquals(1, group.getMaxServices());
        assertEquals(100, group.getMaxPlayers());
        assertEquals(1024, group.getMemory());
    }

    @Test
    void createWithoutOptionsAsksTheWizard() {
        ScriptedPrompt prompt = new ScriptedPrompt("PROXY", "STATIC", "velocity", "", "1", "1",
                "4096", "ALL,PROXY");
        cli.wizard(prompt);

        String output = execute("group create Netz");

        assertTrue(output.contains("Gruppe 'Netz' angelegt"), output);
        ServiceGroup group = stored.get("Netz");
        assertEquals(ServiceType.PROXY, group.getType());
        assertEquals(ServiceMode.STATIC, group.getMode());
        assertEquals("velocity", group.getPlatform());
        assertEquals(4096, group.getMemory());
        assertEquals(List.of("ALL", "PROXY"), group.getTemplatesList());
        assertFalse(group.getFallback());
        assertTrue(prompt.questions.get(0).contains("Typ"), prompt.questions.toString());
    }

    @Test
    void anAbortedWizardCreatesNothing() {
        ScriptedPrompt prompt = new ScriptedPrompt("SERVER", "<abort>");
        cli.wizard(prompt);

        String output = execute("group create Lobby");

        assertTrue(output.contains("nicht angelegt"), output);
        assertFalse(stored.containsKey("Lobby"));
    }

    @Test
    void optionsSkipTheWizard() {
        ScriptedPrompt prompt = new ScriptedPrompt();
        cli.wizard(prompt);

        execute("group create Lobby --platform paper");

        assertTrue(prompt.questions.isEmpty(), prompt.questions.toString());
        assertEquals("paper", stored.get("Lobby").getPlatform());
    }

    @Test
    void theWizardIsNotStartedForAnExistingGroup() {
        execute("group create Lobby --platform paper");
        ScriptedPrompt prompt = new ScriptedPrompt("SERVER");
        cli.wizard(prompt);

        String output = execute("group create Lobby");

        assertTrue(output.contains("existiert bereits"), output);
        assertTrue(prompt.questions.isEmpty(), prompt.questions.toString());
    }

    @Test
    void createAppliesAllOptions() {
        execute("group create Survival --platform paper --version 1.21.4 --mode STATIC --min 2 --max 5"
                + " --max-players 200 --memory 4096 --template ALL --template Survival --fallback"
                + " --fallback-priority 3 --java-version 21 --runtime DOCKER"
                + " --jvm-arg -XX:+UseZGC --java-arg --nogui");

        ServiceGroup group = stored.get("Survival");
        assertEquals("1.21.4", group.getVersion());
        assertEquals(ServiceMode.STATIC, group.getMode());
        assertEquals(2, group.getMinServices());
        assertEquals(5, group.getMaxServices());
        assertEquals(200, group.getMaxPlayers());
        assertEquals(4096, group.getMemory());
        assertEquals(List.of("ALL", "Survival"), group.getTemplatesList());
        assertTrue(group.getFallback());
        assertEquals(3, group.getFallbackPriority());
        assertEquals(21, group.getJavaVersion());
        assertEquals(RuntimeType.DOCKER, group.getRuntime());
        assertEquals(List.of("-XX:+UseZGC"), group.getJvmArgsList());
        assertEquals(List.of("--nogui"), group.getJavaArgsList());
    }

    @Test
    void proxyPlatformInfersTheProxyRole() {
        String output = execute("group create Proxy --platform velocity --mode STATIC --min 1 --max 1"
                + " --max-players 256 --memory 1024 --no-fallback --runtime PROCESS");

        assertTrue(output.contains("PROXY velocity"), output);
        ServiceGroup group = stored.get("Proxy");
        assertEquals(ServiceType.PROXY, group.getType());
        assertEquals(ServiceMode.STATIC, group.getMode());
    }

    @Test
    void proxyPlatformAlsoInfersTheStaticMode() {
        execute("group create Proxy --platform velocity");

        ServiceGroup group = stored.get("Proxy");
        assertEquals(ServiceType.PROXY, group.getType());
        assertEquals(ServiceMode.STATIC, group.getMode(), "ein Proxy belegt genau einen Port");
    }

    @Test
    void aServerTypeOnAProxyPlatformIsRejected() {
        String output = execute("group create Proxy --platform velocity --type SERVER");

        assertTrue(output.contains("Proxy-Plattform"), output);
        assertFalse(stored.containsKey("Proxy"));
    }

    @Test
    void aProxyTypeOnAServerPlatformIsRejected() {
        String output = execute("group create Proxy --platform paper --type PROXY");

        assertTrue(output.contains("Proxy-Plattform"), output);
        assertFalse(stored.containsKey("Proxy"));
    }

    @Test
    void invalidInputIsReportedPerProblem() {
        assertTrue(execute("group create Lobby").contains("platform ist Pflicht"));
        assertTrue(execute("group create Lobby --platform ").contains("Wert fehlt"));
        assertTrue(execute("group create Lobby --platform paper --type KUBERNETES")
                .contains("Ungültiger Wert für --type"));
        assertTrue(execute("group create Lobby --platform paper --min viele")
                .contains("Ungültiger Wert für --min"));
        assertTrue(execute("group create Lobby --platform paper --farbe rot")
                .contains("Unbekannte Option: --farbe"));
        assertTrue(execute("group create Lobby --platform paper zusatz")
                .contains("Unerwartetes Argument"));
        assertTrue(execute("group create 'Lobby 1' --platform paper").contains("Fehler"));
    }

    @Test
    void createRejectsDuplicateNames() {
        execute("group create Lobby --platform paper");

        assertTrue(execute("group create Lobby --platform paper").contains("existiert bereits"));
    }

    @Test
    void editChangesOnlyTheGivenOptions() {
        execute("group create Lobby --platform paper --template ALL --fallback");

        String output = execute("group edit Lobby --max 4");

        assertTrue(output.contains("aktualisiert"), output);
        ServiceGroup group = stored.get("Lobby");
        assertEquals(4, group.getMaxServices());
        assertEquals(1, group.getMinServices(), "nicht genannte Optionen bleiben");
        assertEquals("paper", group.getPlatform());
        assertEquals(List.of("ALL"), group.getTemplatesList());
        assertTrue(group.getFallback());
    }

    @Test
    void editReplacesTemplatesOnlyWhenGiven() {
        execute("group create Lobby --platform paper --template ALL");

        execute("group edit Lobby --template Survival");

        assertEquals(List.of("Survival"), stored.get("Lobby").getTemplatesList());
    }

    @Test
    void editAndDeleteReportUnknownGroups() {
        assertTrue(execute("group edit Fehlt --max 2").contains("Unbekannte Gruppe: Fehlt"));
        assertTrue(execute("group delete Fehlt").contains("Unbekannte Gruppe: Fehlt"));
    }

    @Test
    void deleteRemovesTheGroup() {
        execute("group create Lobby --platform paper");

        String output = execute("group delete Lobby");

        assertTrue(output.contains("gelöscht"), output);
        assertFalse(stored.containsKey("Lobby"));
    }

    @Test
    void listShowsAllGroupsWithTheirSettings() {
        assertTrue(execute("group list").contains("Keine Gruppen definiert"));
        execute("group create Lobby --platform paper --max 3 --fallback --fallback-priority 1");
        execute("group create Proxy --platform velocity --type PROXY");

        String output = execute("group list");

        String text = plain(output);
        assertTrue(text.contains("Name Typ Plattform"), text);
        assertTrue(text.contains("Lobby SERVER paper"), text);
        assertTrue(text.contains("ja (1)"), text);
        assertTrue(text.contains("Proxy PROXY velocity"), text);
    }

    @Test
    void infoShowsTheDetailsOfAGroup() {
        execute("group create Lobby --platform paper --template Lobby --fallback --max-players 250"
                + " --memory 2048 --runtime DOCKER");

        String output = execute("group info Lobby");

        assertTrue(output.contains("Plattform: paper (neueste)"), output);
        assertTrue(output.contains("Spieler je Service: 250"), output);
        assertTrue(output.contains("Speicher: 2048 MB"), output);
        assertTrue(output.contains("Fallback: ja (Priorität 0)"), output);
        assertTrue(output.contains("Templates: Lobby"), output);
        assertTrue(output.contains("Runtime: DOCKER"), output);
        assertTrue(execute("group info Fehlt").contains("Unbekannte Gruppe: Fehlt"));
    }

    @Test
    void maintenanceTogglesTheMode() {
        execute("group create Lobby --platform paper");

        assertTrue(execute("maintenance Lobby on").contains("Wartungsmodus aktiv"));
        assertTrue(stored.get("Lobby").getMaintenance());
        assertTrue(execute("maintenance Lobby off").contains("Wartungsmodus aus"));
        assertFalse(stored.get("Lobby").getMaintenance());
        assertTrue(execute("maintenance Lobby maybe").contains("Erwartet 'on' oder 'off'"));
        assertTrue(execute("maintenance Lobby").contains("Verwendung: maintenance"));
        assertTrue(execute("maintenance Fehlt on").contains("existiert nicht"));
    }

    @Test
    void scaleAsksTheScalerAndReportsTheResult() {
        execute("group create Lobby --platform paper");
        ScalingRecorder scaler = new ScalingRecorder();
        scaler.decisions = List.of(
                new ScalingDecision("Lobby", ScalingDecision.Action.START, "manual", 1, 2, "manuell auf 2 skaliert"),
                new ScalingDecision("Lobby", ScalingDecision.Action.START, "manual", 2, 3, "manuell auf 3 skaliert"));
        GroupCommandLine withScaler = new GroupCommandLine(manager(), scaler);

        String output = withScaler.execute("group scale Lobby 3".split("\\s+"));

        assertEquals("Lobby", scaler.group);
        assertEquals(3, scaler.target);
        assertTrue(output.contains("Gruppe 'Lobby': Ziel 3 — 2 gestartet, 0 gestoppt."), output);
        assertTrue(output.contains("START 1 → 2: manuell auf 2 skaliert"), output);
    }

    @Test
    void scaleHintsAtTheMinimumItUndercuts() {
        execute("group create Lobby --platform paper --min 3 --max 3");
        ScalingRecorder scaler = new ScalingRecorder();
        scaler.decisions = List.of(new ScalingDecision("Lobby", ScalingDecision.Action.STOP,
                "manual", 3, 2, "manuell auf 2 skaliert"));
        GroupCommandLine withScaler = new GroupCommandLine(manager(), scaler);

        String output = withScaler.execute("group scale Lobby 2".split("\\s+"));

        assertTrue(output.contains("1 gestoppt"), output);
        assertTrue(output.contains("unter minServices (3)"), output);
    }

    @Test
    void scaleReportsMissingArgumentsAndUnknownGroups() {
        execute("group create Lobby --platform paper");
        ScalingRecorder scaler = new ScalingRecorder();
        scaler.failure = new IllegalArgumentException("Unbekannte Gruppe: Fehlt");
        GroupCommandLine withScaler = new GroupCommandLine(manager(), scaler);

        assertTrue(withScaler.execute("group scale Lobby".split("\\s+"))
                .startsWith("Verwendung: group scale"));
        assertTrue(withScaler.execute("group scale Lobby drei".split("\\s+"))
                .startsWith("Fehler: Ungültiger Wert für --anzahl"));
        assertTrue(withScaler.execute("group scale Fehlt 2".split("\\s+"))
                .startsWith("Fehler: Unbekannte Gruppe: Fehlt"));
    }

    @Test
    void scaleIsUnavailableWithoutAScaler() {
        execute("group create Lobby --platform paper");

        assertTrue(execute("group scale Lobby 2").contains("nicht verfügbar"));
    }

    @Test
    void namesAreAvailableForCompletion() {
        execute("group create Lobby --platform paper");
        execute("group create Survival --platform paper");

        assertEquals(List.of("Lobby", "Survival"), cli.names());
    }

    @Test
    void serviceCommandLineRoutesGroupCommands() {
        ServiceCommandLine line = new ServiceCommandLine(new ServiceCommandLineTest.FakeController(), cli);
        execute("group create Lobby --platform paper");

        assertTrue(line.execute("group list").contains("Lobby"));
        assertTrue(line.execute("maintenance Lobby on").contains("Wartungsmodus aktiv"));
        assertEquals(List.of("Lobby"), line.groupNames());
        assertTrue(line.execute("help").contains("maintenance <name> on|off"));
    }

    @Test
    void groupCommandsAreUnavailableWithoutAManager() {
        ServiceCommandLine line = new ServiceCommandLine(new ServiceCommandLineTest.FakeController());

        assertTrue(line.execute("group list").contains("nicht verfügbar"));
        assertTrue(line.groupNames().isEmpty());
    }

    private String execute(String line) {
        return cli.execute(line.trim().split("\\s+"));
    }

    private GroupManager manager() {
        return new GroupManager(new FakeStore(), new FakeCache(),
                new PortSettings(25565, 30000), templates::contains);
    }

    /**
     * Merkt sich den Aufruf und liefert vorbereitete Entscheidungen oder einen Fehler.
     */
    private static final class ScalingRecorder implements GroupScaler {

        private List<ScalingDecision> decisions = List.of();
        private RuntimeException failure;
        private String group;
        private int target;

        @Override
        public List<ScalingDecision> scaleTo(String group, int target) {
            this.group = group;
            this.target = target;
            if (failure != null) {
                throw failure;
            }
            return decisions;
        }
    }

    /**
     * Beantwortet das Formular aus einer Liste; {@code <abort>} bricht ab.
     */
    private static final class ScriptedPrompt implements GroupWizard.Prompt {

        private final Deque<String> answers;
        private final List<String> questions = new ArrayList<>();

        private ScriptedPrompt(String... answers) {
            this.answers = new ArrayDeque<>(List.of(answers));
        }

        @Override
        public Optional<String> ask(String question, String defaultValue) {
            return next(question, defaultValue);
        }

        @Override
        public Optional<String> choose(String question, List<String> options, String defaultValue) {
            return next(question, defaultValue);
        }

        @Override
        public Optional<Boolean> confirm(String question, boolean defaultValue) {
            questions.add(question);
            String answer = answers.poll();
            if (answer == null) {
                return Optional.of(defaultValue);
            }
            if (answer.equals("<abort>")) {
                return Optional.empty();
            }
            return Optional.of(answer.equalsIgnoreCase("j") || answer.equalsIgnoreCase("y"));
        }

        private Optional<String> next(String question, String defaultValue) {
            questions.add(question);
            String answer = answers.poll();
            if (answer == null) {
                return Optional.of(defaultValue);
            }
            return answer.equals("<abort>") ? Optional.empty() : Optional.of(answer);
        }
    }

    private final class FakeStore implements GroupStore {

        @Override
        public List<ServiceGroup> findAll() {
            return List.copyOf(stored.values());
        }

        @Override
        public Optional<ServiceGroup> find(String name) {
            return Optional.ofNullable(stored.get(name));
        }

        @Override
        public void upsert(ServiceGroup group) {
            stored.put(group.getName(), group);
        }

        @Override
        public boolean delete(String name) {
            return stored.remove(name) != null;
        }

        @Override
        public boolean setMaintenance(String name, boolean maintenance) {
            ServiceGroup group = stored.get(name);
            if (group == null) {
                return false;
            }
            stored.put(name, group.toBuilder().setMaintenance(maintenance).build());
            return true;
        }
    }

    private static final class FakeCache implements GroupCache {

        @Override
        public Optional<ServiceGroup> get(String name) {
            return Optional.empty();
        }

        @Override
        public void put(ServiceGroup group) {
            // kein Cache im Test
        }

        @Override
        public boolean invalidate(String name) {
            return false;
        }

        @Override
        public void clear() {
            // kein Cache im Test
        }
    }
}
