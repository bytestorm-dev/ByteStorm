package dev.bytestorm.plugins.lobby.display;

import dev.bytestorm.proto.ServiceInfo;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import xyz.xenondevs.invui.gui.Gui;
import xyz.xenondevs.invui.gui.Markers;
import xyz.xenondevs.invui.gui.PagedGui;
import xyz.xenondevs.invui.item.BoundItem;
import xyz.xenondevs.invui.item.Item;
import xyz.xenondevs.invui.item.ItemBuilder;
import xyz.xenondevs.invui.window.Window;

import java.util.Comparator;
import java.util.List;

/**
 * Auswahlmenü einer Gruppe, wenn mehr als ein Service online ist.
 *
 * <p>Jeder Service ist ein Kopf mit Name, Spielerzahl und Hinweis; ein Klick
 * schließt das Menü und fordert den Wechsel an. Die Liste ist seitenweise, damit
 * auch eine große Gruppe vollständig erreichbar bleibt.
 *
 * <p>InvUI arbeitet nur im Server-Thread; der Aufruf kommt aus dem Klick-Event und
 * bleibt damit auf dem richtigen Thread.
 */
public final class ServerSelectMenu {

    private final ServerDisplayService displays;

    /**
     * @param displays Service, der den Wechsel ausführt
     */
    public ServerSelectMenu(ServerDisplayService displays) {
        this.displays = displays;
    }

    /**
     * @param player  Spieler, der die Anzeige angeklickt hat
     * @param display angeklickte Anzeige
     * @param online  Services der Gruppe im Zustand {@code ONLINE}
     */
    public void open(Player player, ServerDisplay display, List<ServiceInfo> online) {
        List<Item> items = online.stream()
                .sorted(Comparator.comparing(ServiceInfo::getName, String.CASE_INSENSITIVE_ORDER))
                .map(service -> entry(display, service))
                .toList();

        BoundItem back = BoundItem.pagedBuilder()
                .setItemProvider((viewer, paged) -> new ItemBuilder(Material.ARROW)
                        .setName(paged.getPage() > 0
                                ? "<gray>Seite <white>" + (paged.getPage() + 1)
                                + "<gray>/<white>" + paged.getPageCount()
                                : "<dark_gray>Anfang"))
                .addClickHandler((item, paged, click) -> {
                    if (paged.getPage() > 0) {
                        paged.setPage(paged.getPage() - 1);
                    }
                })
                .build();

        BoundItem forward = BoundItem.pagedBuilder()
                .setItemProvider((viewer, paged) -> new ItemBuilder(Material.ARROW)
                        .setName(paged.getPage() < paged.getPageCount() - 1
                                ? "<gray>Seite <white>" + (paged.getPage() + 2)
                                + "<gray>/<white>" + paged.getPageCount()
                                : "<dark_gray>Ende"))
                .addClickHandler((item, paged, click) -> {
                    if (paged.getPage() < paged.getPageCount() - 1) {
                        paged.setPage(paged.getPage() + 1);
                    }
                })
                .build();

        Gui gui = PagedGui.itemsBuilder()
                .setStructure(
                        "# # # # # # # # #",
                        "# x x x x x x x #",
                        "# x x x x x x x #",
                        "# # # < # > # # #")
                .addIngredient('#', new ItemBuilder(Material.BLACK_STAINED_GLASS_PANE)
                        .setName("<dark_gray> "))
                .addIngredient('x', Markers.CONTENT_LIST_SLOT_HORIZONTAL)
                .addIngredient('<', back)
                .addIngredient('>', forward)
                .setContent(items)
                .build();

        Window.mergedBuilder()
                .setViewer(player)
                .setTitle("<dark_aqua>" + display.displayName() + " <dark_gray>● <gray>Server wählen")
                .setGui(gui)
                .build()
                .open();
    }

    /**
     * @param display angeklickte Anzeige
     * @param service Service der Gruppe
     * @return Eintrag im Menü
     */
    private Item entry(ServerDisplay display, ServiceInfo service) {
        String players = service.getMaxPlayers() > 0
                ? service.getOnlinePlayers() + "/" + service.getMaxPlayers()
                : String.valueOf(service.getOnlinePlayers());

        return Item.builder()
                .setItemProvider(new ItemBuilder(Material.PLAYER_HEAD)
                        .setName("<white>" + service.getName())
                        .addLoreLines(
                                "<gray>Gruppe <white>" + service.getGroup(),
                                "<gray>Spieler <white>" + players,
                                "",
                                "<aqua>Klicken zum Betreten"))
                .addClickHandler((item, click) -> {
                    click.player().closeInventory();
                    displays.connect(click.player(), service.getName(), display);
                })
                .build();
    }
}
