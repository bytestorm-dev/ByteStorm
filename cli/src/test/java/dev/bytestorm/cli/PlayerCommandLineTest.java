package dev.bytestorm.cli;

import dev.bytestorm.api.bridge.PlayerBridge;
import dev.bytestorm.controller.player.PlayerManager;
import dev.bytestorm.controller.registry.ServiceRegistry;
import dev.bytestorm.core.mongo.model.StoredPlayer;
import dev.bytestorm.proto.PlayerInfo;
import dev.bytestorm.proto.ServiceInfo;
import dev.bytestorm.proto.ServiceState;
import dev.bytestorm.proto.ServiceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerCommandLineTest {

    /** Entfernt Tabellenabstände: Zellen bleiben durch genau ein Leerzeichen getrennt. */
    private static String plain(String text) {
        return String.join("\n", text.lines()
                .map(line -> line.strip().replaceAll("\\s+", " "))
                .toList());
    }

    private static final UUID BOB = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private final List<PlayerInfo> online = new ArrayList<>();
    private final List<StoredPlayer> history = new ArrayList<>();
    private final FakeBridge bridge = new FakeBridge();

    private PlayerCommandLine cli;

    @BeforeEach
    void setUp() {
        List<ServiceInfo> services = List.of(
                service("Proxy-1", "Proxy", ServiceType.PROXY, 25565),
                service("Lobby-1", "Lobby", ServiceType.SERVER, 30001));
        ServiceRegistry registry = new ServiceRegistry(() -> services, List::of);
        cli = new PlayerCommandLine(new PlayerManager(() -> online,
                name -> history.stream()
                        .filter(stored -> stored.name().equalsIgnoreCase(name))
                        .findFirst(),
                registry, bridge));
    }

    @Test
    void showsTheUsageWithoutACommand() {
        String output = cli.execute("player");

        assertTrue(output.contains("player list"), output);
        assertTrue(output.contains("player kick <name> [grund]"), output);
    }

    @Test
    void listsOnlinePlayers() {
        online.add(player("bob"));

        String output = cli.execute("player list");

        String text = plain(output);
        assertEquals("Name UUID Proxy Server", text.lines().findFirst().orElse(""));
        assertTrue(text.contains("bob " + BOB + " Proxy-1 Lobby-1"), text);
    }

    @Test
    void reportsWhenNobodyIsOnline() {
        assertEquals("Keine Spieler online.", cli.execute("player list"));
    }

    @Test
    void showsLiveStateAndHistory() {
        online.add(player("bob"));
        history.add(stored());

        String output = cli.execute("player info bob");

        assertTrue(output.contains("Status: online"), output);
        assertTrue(output.contains("Proxy: Proxy-1"), output);
        assertTrue(output.contains("Server: Lobby-1"), output);
        assertTrue(output.contains("Spielzeit: 3h 00m"), output);
        assertTrue(output.contains("Letzter Service: Lobby-1"), output);
    }

    @Test
    void showsHistoryOfPlayersThatAreNotOnline() {
        history.add(stored());

        String output = cli.execute("player info BOB");

        assertTrue(output.contains("Status: nicht online"), output);
        assertTrue(output.contains("Spielzeit: 3h 00m"), output);
    }

    @Test
    void reportsUnknownPlayers() {
        assertTrue(cli.execute("player info carol")
                .startsWith("Unbekannter Spieler: carol"));
        assertTrue(cli.execute("player info").startsWith("Spielername fehlt"));
    }

    @Test
    void connectsAndKicksThroughThePlayersProxy() {
        online.add(player("bob"));

        assertEquals("Spieler 'bob' wird über Proxy-1 nach 'Lobby-1' geschickt.",
                cli.execute("player connect bob Lobby-1"));
        assertEquals("Spieler 'bob' wird über Proxy-1 getrennt: zu viele Kicks",
                cli.execute("player kick bob zu viele Kicks"));
        assertEquals(List.of("bob -> Lobby-1"), bridge.connects);
        assertEquals(List.of("bob:zu viele Kicks"), bridge.kicks);
    }

    @Test
    void kickWithoutReasonUsesTheDefaultMessage() {
        online.add(player("bob"));

        assertEquals("Spieler 'bob' wird über Proxy-1 getrennt.",
                cli.execute("player kick bob"));
        assertEquals(List.of("bob:"), bridge.kicks);
    }

    @Test
    void reportsErrorsInPlainText() {
        assertTrue(cli.execute("player connect bob Lobby-1").startsWith("Fehler: Spieler 'bob' ist nicht online"));
        assertTrue(cli.execute("player connect").startsWith("Ziel fehlt"));
        assertTrue(cli.execute("player connect bob Lobby-1 extra").startsWith("Erwartet genau einen Service"));
        assertTrue(cli.execute("player kick").startsWith("Spielername fehlt"));
        assertTrue(cli.execute("player fly bob").startsWith("Unbekanntes Spieler-Kommando: fly"));
    }

    @Test
    void offersThePlayerNamesForCompletion() {
        online.add(player("bob"));

        assertEquals(List.of("bob"), cli.names());
    }

    @Test
    void completionSurvivesAFailingSource() {
        PlayerCommandLine broken = new PlayerCommandLine(new PlayerManager(
                () -> {
                    throw new IllegalStateException("Redis weg");
                },
                name -> java.util.Optional.empty(),
                new ServiceRegistry(List::of, List::of),
                bridge));

        // Die Vervollständigung darf an einem Fehler nicht scheitern.
        assertEquals(List.of(), broken.names());
    }

    private static PlayerInfo player(String name) {
        return PlayerInfo.newBuilder()
                .setUuid(BOB.toString())
                .setName(name)
                .setProxy("Proxy-1")
                .setServer("Lobby-1")
                .build();
    }

    private static ServiceInfo service(String name, String group, ServiceType type, int port) {
        return ServiceInfo.newBuilder()
                .setId(UUID.randomUUID().toString())
                .setName(name)
                .setGroup(group)
                .setType(type)
                .setState(ServiceState.ONLINE)
                .setPort(port)
                .build();
    }

    private static StoredPlayer stored() {
        return new StoredPlayer(BOB, "bob", Duration.ofHours(3),
                Instant.parse("2026-10-20T18:00:00Z"), Instant.parse("2026-10-26T17:30:00Z"), "Lobby-1");
    }

    private static final class FakeBridge implements PlayerBridge {

        private final List<String> connects = new ArrayList<>();
        private final List<String> kicks = new ArrayList<>();

        @Override
        public void connectPlayer(String serviceId, String player, String target) {
            connects.add(player + " -> " + target);
        }

        @Override
        public void kickPlayer(String serviceId, String player, String reason) {
            kicks.add(player + ":" + reason);
        }
    }
}
