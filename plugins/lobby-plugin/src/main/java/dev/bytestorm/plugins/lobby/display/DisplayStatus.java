package dev.bytestorm.plugins.lobby.display;

import dev.bytestorm.proto.ServiceInfo;
import dev.bytestorm.proto.ServiceState;

import java.util.List;

/**
 * Statuszeile unter dem Server-Kopf, als MiniMessage-Text.
 *
 * <p>Die Zeile ist bewusst eine reine Funktion ohne Bukkit: Sie entscheidet nur
 * aus dem Live-Zustand, was dasteht, und lässt sich damit ohne Server testen.
 * Gezählt werden ausschließlich Services im Zustand {@code ONLINE} — ein
 * startender Service hat noch keine Spieler, ein gestoppter würde die Summe mit
 * alten Zahlen verfälschen.
 */
public final class DisplayStatus {

    private DisplayStatus() {
    }

    /**
     * @param services alle Services der Gruppe, auch gestoppte
     * @return MiniMessage-Zeile für das Hologramm
     */
    public static String text(List<ServiceInfo> services) {
        List<ServiceInfo> online = services.stream()
                .filter(service -> service.getState() == ServiceState.ONLINE)
                .toList();
        if (online.isEmpty()) {
            return "<dark_gray>● <red>Offline";
        }

        int players = online.stream().mapToInt(ServiceInfo::getOnlinePlayers).sum();
        int max = online.stream().mapToInt(ServiceInfo::getMaxPlayers).filter(value -> value > 0).sum();
        String count = max > 0 ? players + "/" + max : String.valueOf(players);
        String servers = online.size() == 1 ? "1 Server" : online.size() + " Server";

        return "<dark_gray>● <green>" + count + "</green> <gray>Spieler online"
                + " <dark_gray>· <gray>" + servers;
    }
}
