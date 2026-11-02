package dev.bytestorm.plugins.lobby.display;

import dev.bytestorm.plugins.lobby.message.Messages;
import dev.bytestorm.proto.ServiceInfo;
import dev.bytestorm.proto.ServiceState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DisplayStatusTest {

    private Messages messages;

    @BeforeEach
    void setUp() throws IOException {
        messages = Messages.load("de", null);
    }

    @Test
    void leereGruppeGiltAlsOffline() {
        assertEquals("<dark_gray>● <red>Offline</red>", DisplayStatus.text(messages, List.of()));
    }

    @Test
    void gestoppterServiceGiltNichtAlsErreichbar() {
        ServiceInfo stopped = service("Lobby-1", ServiceState.STOPPED, 0, 20);

        assertEquals("<dark_gray>● <red>Offline</red>",
                DisplayStatus.text(messages, List.of(stopped)));
    }

    @Test
    void einOnlineServiceZeigtSpielerUndServer() {
        ServiceInfo online = service("Lobby-1", ServiceState.ONLINE, 3, 20);

        assertEquals("<dark_gray>● <green>3/20</green> <gray>Spieler online"
                        + " <dark_gray>· <gray>1 Server",
                DisplayStatus.text(messages, List.of(online)));
    }

    @Test
    void mehrereServicesWerdenSummiert() {
        List<ServiceInfo> group = List.of(
                service("Lobby-1", ServiceState.ONLINE, 3, 20),
                service("Lobby-2", ServiceState.ONLINE, 4, 20),
                service("Lobby-3", ServiceState.STOPPED, 9, 20));

        assertEquals("<dark_gray>● <green>7/40</green> <gray>Spieler online"
                        + " <dark_gray>· <gray>2 Server",
                DisplayStatus.text(messages, group));
    }

    @Test
    void serviceOhneObergrenzeZeigtNurDieZahl() {
        ServiceInfo online = service("Lobby-1", ServiceState.ONLINE, 5, 0);

        assertEquals("<dark_gray>● <green>5</green> <gray>Spieler online"
                        + " <dark_gray>· <gray>1 Server",
                DisplayStatus.text(messages, List.of(online)));
    }

    @Test
    void englischeTexteBeugenDieServerzahl() throws IOException {
        Messages english = Messages.load("en", null);
        List<ServiceInfo> group = List.of(
                service("Lobby-1", ServiceState.ONLINE, 1, 20),
                service("Lobby-2", ServiceState.ONLINE, 2, 20));

        assertEquals("<dark_gray>● <green>3/40</green> <gray>players online"
                        + " <dark_gray>· <gray>2 servers",
                DisplayStatus.text(english, group));
        assertEquals("<dark_gray>● <green>1/20</green> <gray>players online"
                        + " <dark_gray>· <gray>1 server",
                DisplayStatus.text(english, List.of(service("Lobby-1", ServiceState.ONLINE, 1, 20))));
    }

    private ServiceInfo service(String name, ServiceState state, int online, int max) {
        return ServiceInfo.newBuilder()
                .setName(name)
                .setGroup("Lobby")
                .setState(state)
                .setOnlinePlayers(online)
                .setMaxPlayers(max)
                .build();
    }
}
