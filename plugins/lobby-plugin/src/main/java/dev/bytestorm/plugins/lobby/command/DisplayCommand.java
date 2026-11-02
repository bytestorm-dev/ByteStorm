package dev.bytestorm.plugins.lobby.command;

import dev.bytestorm.plugins.lobby.LobbyPlugin;
import dev.bytestorm.plugins.lobby.display.ServerDisplay;
import dev.bytestorm.plugins.lobby.message.Messages;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code /serverdisplay} — Anzeigen für Gruppen anlegen, verschieben, löschen.
 *
 * <p>Der Standort ist immer der des Spielers: Wer eine Anzeige baut, steht dort,
 * wo sie schweben soll. Der Anzeigename ist der Rest der Zeile und darf Leerzeichen
 * enthalten; {@code --head <url>} setzt optional die Textur. Die Texte stehen in
 * {@code messages/<sprache>.json}.
 */
public final class DisplayCommand implements CommandExecutor, TabCompleter {

    /** Berechtigung zum Verwalten der Anzeigen. */
    public static final String PERMISSION = "bytestorm.lobby.display";

    private static final List<String> SUBCOMMANDS =
            List.of("create", "delete", "move", "list", "reload");

    private final LobbyPlugin plugin;
    private final Messages messages;

    /**
     * @param plugin   Plugin mit dem Anzeigen-Service
     * @param messages Sprachdateien
     */
    public DisplayCommand(LobbyPlugin plugin, Messages messages) {
        this.plugin = plugin;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, String @NotNull [] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages.text("command.player-only"));
            return true;
        }
        if (!player.hasPermission(PERMISSION)) {
            send(player, "command.no-permission", Map.of());
            return true;
        }
        if (args.length == 0) {
            usage(player, label);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "create" -> create(player, label, args);
            case "delete" -> delete(player, label, args);
            case "move" -> move(player, label, args);
            case "list" -> list(player, label);
            case "reload" -> {
                plugin.displays().reload();
                send(player, "command.reloaded",
                        Map.of("count", String.valueOf(plugin.displays().displays().size())));
            }
            default -> usage(player, label);
        }
        return true;
    }

    private void create(Player player, String label, String[] args) {
        if (args.length < 3) {
            send(player, "command.usage-create", Map.of("label", label));
            return;
        }

        List<String> words = new ArrayList<>(List.of(args).subList(2, args.length));
        String head = null;
        int flag = words.indexOf("--head");
        if (flag >= 0) {
            if (flag + 1 >= words.size()) {
                send(player, "command.head-missing", Map.of());
                return;
            }
            head = words.get(flag + 1);
            words = new ArrayList<>(words.subList(0, flag));
        }

        String displayName = String.join(" ", words).trim();
        if (displayName.isEmpty()) {
            send(player, "command.name-empty", Map.of());
            return;
        }

        Location location = player.getLocation().clone();
        ServerDisplay display = plugin.displays().create(args[1], displayName, head, location);
        send(player, "command.created", Map.of(
                "id", String.valueOf(display.id()),
                "group", display.group()));
    }

    private void delete(Player player, String label, String[] args) {
        Integer id = id(args, 1);
        if (id == null) {
            send(player, "command.usage-delete", Map.of("label", label));
            return;
        }
        if (plugin.displays().delete(id)) {
            send(player, "command.deleted", Map.of("id", String.valueOf(id)));
        } else {
            send(player, "command.missing", Map.of("id", String.valueOf(id)));
        }
    }

    private void move(Player player, String label, String[] args) {
        Integer id = id(args, 1);
        if (id == null) {
            send(player, "command.usage-move", Map.of("label", label));
            return;
        }
        if (plugin.displays().move(id, player.getLocation())) {
            send(player, "command.moved", Map.of("id", String.valueOf(id)));
        } else {
            send(player, "command.missing", Map.of("id", String.valueOf(id)));
        }
    }

    private void list(Player player, String label) {
        if (plugin.displays().displays().isEmpty()) {
            send(player, "command.list-empty", Map.of("label", label));
            return;
        }
        for (ServerDisplay display : plugin.displays().displays().stream()
                .sorted(Comparator.comparingInt(ServerDisplay::id)).toList()) {
            send(player, "command.list-row", Map.of(
                    "id", String.valueOf(display.id()),
                    "group", display.group(),
                    "world", display.world(),
                    "x", String.valueOf(Math.round(display.x())),
                    "y", String.valueOf(Math.round(display.y())),
                    "z", String.valueOf(Math.round(display.z()))));
        }
    }

    private Integer id(String[] args, int index) {
        if (args.length <= index) {
            return null;
        }
        try {
            return Integer.valueOf(args[index]);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void usage(Player player, String label) {
        send(player, "command.usage", Map.of("label", label));
        send(player, "command.usage-help", Map.of("label", label));
    }

    private void send(Player player, String key, Map<String, String> values) {
        player.sendMessage(MiniMessage.miniMessage().deserialize(messages.prefixed(key, values)));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String label, String @NotNull [] args) {
        if (args.length == 1) {
            return matching(SUBCOMMANDS, args[0]);
        }
        String subcommand = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2 && subcommand.equals("create")) {
            return matching(plugin.displays().groupNames(), args[1]);
        }
        if (args.length == 2 && (subcommand.equals("delete") || subcommand.equals("move"))) {
            List<String> ids = plugin.displays().displays().stream()
                    .map(display -> String.valueOf(display.id()))
                    .toList();
            return matching(ids, args[1]);
        }
        return List.of();
    }

    private List<String> matching(List<String> values, String prefix) {
        String needle = prefix.toLowerCase(Locale.ROOT);
        return values.stream()
                .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(needle))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }
}
