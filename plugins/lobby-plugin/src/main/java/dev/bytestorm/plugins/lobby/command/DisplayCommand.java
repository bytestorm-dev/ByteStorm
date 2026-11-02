package dev.bytestorm.plugins.lobby.command;

import dev.bytestorm.plugins.lobby.LobbyPlugin;
import dev.bytestorm.plugins.lobby.display.ServerDisplay;
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

/**
 * {@code /serverdisplay} — Anzeigen für Gruppen anlegen, verschieben, löschen.
 *
 * <p>Der Standort ist immer der des Spielers: Wer eine Anzeige baut, steht dort,
 * wo sie schweben soll. Der Anzeigename ist der Rest der Zeile und darf Leerzeichen
 * enthalten; {@code --head <url>} setzt optional die Textur.
 */
public final class DisplayCommand implements CommandExecutor, TabCompleter {

    /** Berechtigung zum Verwalten der Anzeigen. */
    public static final String PERMISSION = "bytestorm.lobby.display";

    private static final String PREFIX = "<dark_aqua>ByteStorm <dark_gray>● <gray>";
    private static final List<String> SUBCOMMANDS =
            List.of("create", "delete", "move", "list", "reload");

    private final LobbyPlugin plugin;

    /**
     * @param plugin Plugin mit dem Anzeigen-Service
     */
    public DisplayCommand(LobbyPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, String @NotNull [] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Dieses Kommando braucht einen Spieler im Spiel.");
            return true;
        }
        if (!player.hasPermission(PERMISSION)) {
            send(player, PREFIX + "<red>Dazu fehlt dir die Berechtigung.");
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
                send(player, PREFIX + "<green>displays.yml neu geladen: <white>"
                        + plugin.displays().displays().size() + "</white> Anzeigen.");
            }
            default -> usage(player, label);
        }
        return true;
    }

    private void create(Player player, String label, String[] args) {
        if (args.length < 3) {
            send(player, PREFIX + "Nutzung: /" + label
                    + " create <gruppe> <anzeigename> [--head <textur-url>]");
            return;
        }

        List<String> words = new ArrayList<>(List.of(args).subList(2, args.length));
        String head = null;
        int flag = words.indexOf("--head");
        if (flag >= 0) {
            if (flag + 1 >= words.size()) {
                send(player, PREFIX + "<red>Nach --head fehlt die Textur-URL.");
                return;
            }
            head = words.get(flag + 1);
            words = new ArrayList<>(words.subList(0, flag));
        }

        String displayName = String.join(" ", words).trim();
        if (displayName.isEmpty()) {
            send(player, PREFIX + "<red>Der Anzeigename darf nicht leer sein.");
            return;
        }

        Location location = player.getLocation().clone();
        ServerDisplay display = plugin.displays().create(args[1], displayName, head, location);
        send(player, PREFIX + "<green>Anzeige <white>#" + display.id()
                + "</white> für Gruppe <white>" + display.group() + "</white> angelegt.");
    }

    private void delete(Player player, String label, String[] args) {
        Integer id = id(args, 1);
        if (id == null) {
            send(player, PREFIX + "Nutzung: /" + label + " delete <id>");
            return;
        }
        if (plugin.displays().delete(id)) {
            send(player, PREFIX + "<green>Anzeige <white>#" + id + "</white> entfernt.");
        } else {
            send(player, PREFIX + "<red>Es gibt keine Anzeige #" + id + ".");
        }
    }

    private void move(Player player, String label, String[] args) {
        Integer id = id(args, 1);
        if (id == null) {
            send(player, PREFIX + "Nutzung: /" + label + " move <id>");
            return;
        }
        if (plugin.displays().move(id, player.getLocation())) {
            send(player, PREFIX + "<green>Anzeige <white>#" + id + "</white> steht jetzt hier.");
        } else {
            send(player, PREFIX + "<red>Es gibt keine Anzeige #" + id + ".");
        }
    }

    private void list(Player player, String label) {
        if (plugin.displays().displays().isEmpty()) {
            send(player, PREFIX + "<gray>Noch keine Anzeige. <white>/" + label
                    + " create <gruppe> <name></white> legt die erste an.");
            return;
        }
        for (ServerDisplay display : plugin.displays().displays().stream()
                .sorted(Comparator.comparingInt(ServerDisplay::id)).toList()) {
            send(player, PREFIX + "<white>#" + display.id() + "</white> <dark_gray>● <gray>"
                    + display.group() + " <dark_gray>● <gray>" + display.world()
                    + " <dark_gray>@ <gray>" + Math.round(display.x()) + "/"
                    + Math.round(display.y()) + "/" + Math.round(display.z()));
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
        send(player, PREFIX + "Anzeigen: <white>/" + label + " create|delete|move|list|reload");
        send(player, PREFIX + "<gray>create <gruppe> <name> [--head <url>] <dark_gray>● "
                + "legt die Anzeige an deiner Position an");
    }

    private void send(Player player, String message) {
        player.sendMessage(MiniMessage.miniMessage().deserialize(message));
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
