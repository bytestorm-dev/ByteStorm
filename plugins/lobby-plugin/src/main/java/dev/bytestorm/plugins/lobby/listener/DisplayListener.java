package dev.bytestorm.plugins.lobby.listener;

import dev.bytestorm.plugins.lobby.display.ServerDisplay;
import dev.bytestorm.plugins.lobby.display.ServerDisplayService;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Klick auf eine Server-Anzeige.
 *
 * <p>Es reicht ein Event: Die Hitbox ist eine {@code Interaction}, deren
 * Rechtsklick als {@link PlayerInteractEntityEvent} ankommt. Ein zweiter Handler
 * auf {@code PlayerInteractAtEntityEvent} würde denselben Klick doppelt zählen.
 */
public final class DisplayListener implements Listener {

    private final ServerDisplayService displays;

    /**
     * @param displays Service, der die Anzeigen kennt
     */
    public DisplayListener(ServerDisplayService displays) {
        this.displays = displays;
    }

    /**
     * @param event Rechtsklick auf eine Entity
     */
    @EventHandler(ignoreCancelled = true)
    public void onInteract(@NotNull PlayerInteractEntityEvent event) {
        Player player = event.getPlayer();
        Entity clicked = event.getRightClicked();

        ServerDisplay display = displays.display(clicked);
        if (display == null) {
            return;
        }

        // Die Hitbox liegt unter dem Kopf; wer den Kopf selbst anklickt, meint
        // dieselbe Anzeige.
        event.setCancelled(true);
        displays.join(player, display);
    }
}
