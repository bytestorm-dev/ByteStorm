package dev.bytestorm.plugins.lobby.display;

import dev.bytestorm.proto.ServiceInfo;
import dev.bytestorm.proto.ServiceState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DisplayStatusTest {

    @Test
    void leereGruppeGiltAlsOffline() {
        assertEquals("<dark_gray>● <red>Offline", DisplayStatus.text(List.of()));
    }

    @Test
    void gestoppterServiceGiltNichtAlsErreichbar() {
        ServiceInfo stopped = service("Lobby-1", ServiceState.STOPPED, 0, 20);

        assertEquals("<dark_gray>● <red>Offline", DisplayStatus.text(List.of(stopped)));
    }

    @Test
    void einOnlineServiceZeigtSpielerUndServer() {
        ServiceInfo online = service("Lobby-1", ServiceState.ONLINE, 3, 20);

        assertEquals("<dark_gray>● <green>3/20</green> <gray>Spieler online"
                        + " <dark_gray>· <gray>1 Server",
                DisplayStatus.text(List.of(online)));
    }

    @Test
    void mehrereServicesWerdenSummiert() {
        List<ServiceInfo> group = List.of(
                service("Lobby-1", ServiceState.ONLINE, 3, 20),
                service("Lobby-2", ServiceState.ONLINE, 4, 20),
                service("Lobby-3", ServiceState.STOPPED, 9, 20));

        assertEquals("<dark_gray>● <green>7/40</green> <gray>Spieler online"
                        + " <dark_gray>· <gray>2 Server",
                DisplayStatus.text(group));
    }

    @Test
    void serviceOhneObergrenzeZeigtNurDieZahl() {
        ServiceInfo online = service("Lobby-1", ServiceState.ONLINE, 5, 0);

        assertEquals("<dark_gray>● <green>5</green> <gray>Spieler online"
                        + " <dark_gray>· <gray>1 Server",
                DisplayStatus.text(List.of(online)));
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
