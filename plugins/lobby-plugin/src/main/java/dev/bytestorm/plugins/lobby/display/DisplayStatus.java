package dev.bytestorm.plugins.lobby.display;

import dev.bytestorm.plugins.lobby.message.Messages;
import dev.bytestorm.proto.ServiceInfo;
import dev.bytestorm.proto.ServiceState;

import java.util.List;
import java.util.Map;

/**
 * Statuszeile unter dem Server-Kopf, als MiniMessage-Text.
 *
 * <p>Gezählt werden ausschließlich Services im Zustand {@code ONLINE} — ein
 * startender Service hat noch keine Spieler, ein gestoppter würde die Summe mit
 * alten Zahlen verfälschen. Die Texte stehen in {@code messages/<sprache>.json};
 * diese Klasse entscheidet nur, welcher Schlüssel gilt und welche Zahlen hinein
 * gehören, und bleibt damit ohne Server testbar.
 */
public final class DisplayStatus {

    private DisplayStatus() {
    }

    /**
     * @param messages Sprachdateien
     * @param services alle Services der Gruppe, auch gestoppte
     * @return MiniMessage-Zeile für das Hologramm
     */
    public static String text(Messages messages, List<ServiceInfo> services) {
        List<ServiceInfo> online = services.stream()
                .filter(service -> service.getState() == ServiceState.ONLINE)
                .toList();
        if (online.isEmpty()) {
            return messages.text("display.offline");
        }

        int players = online.stream().mapToInt(ServiceInfo::getOnlinePlayers).sum();
        int max = online.stream().mapToInt(ServiceInfo::getMaxPlayers).filter(value -> value > 0).sum();

        Map<String, String> values = Map.of(
                "players", String.valueOf(players),
                "max", String.valueOf(max),
                "servers", servers(messages, online.size()));

        return messages.text(max > 0 ? "display.players" : "display.players-unlimited", values);
    }

    /**
     * @param messages Sprachdateien
     * @param count    Anzahl der erreichbaren Services
     * @return Einzahl oder Mehrzahl, je nach Sprache
     */
    private static String servers(Messages messages, int count) {
        return count == 1
                ? messages.text("display.servers-one")
                : messages.text("display.servers", Map.of("count", String.valueOf(count)));
    }
}
