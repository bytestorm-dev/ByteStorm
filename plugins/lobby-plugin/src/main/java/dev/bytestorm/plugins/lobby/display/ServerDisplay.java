package dev.bytestorm.plugins.lobby.display;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.jetbrains.annotations.Nullable;

/**
 * Eine Server-Anzeige in der Lobby: schwebender Kopf mit Hologramm, Klick führt
 * in die Gruppe.
 *
 * <p>Die Anzeige hängt an einer Gruppe, nicht an einem Service: Welcher Service
 * der Gruppe gerade frei ist, entscheidet erst der Klick. Hat die Gruppe nur
 * einen Service, geht es direkt dorthin; bei mehreren öffnet sich die Auswahl.
 *
 * <p>Die Definition ist unveränderlich und wird in {@code displays.yml}
 * gespeichert; die Entities entstehen daraus bei jedem Chunk-Laden neu. Ein
 * Weltname statt einer {@link Location} hält die Datei lesbar und übersteht einen
 * Neustart, bei dem die Welt noch nicht geladen ist.
 *
 * @param id          laufende Nummer, stabil über Neustarts
 * @param group       Gruppenname, z. B. {@code Lobby}
 * @param displayName Anzeigename über dem Kopf, MiniMessage
 * @param headId      Textur-URL des Kopfes oder {@code null} für einen schlichten Kopf
 * @param world       Weltname des Sockels
 */
public record ServerDisplay(
        int id,
        String group,
        String displayName,
        @Nullable String headId,
        String world,
        double x,
        double y,
        double z,
        float yaw,
        float pitch) {

    /**
     * @return Standort des Sockels oder {@code null}, wenn die Welt nicht geladen ist
     */
    public @Nullable Location location() {
        World loaded = Bukkit.getWorld(world);
        return loaded == null ? null : new Location(loaded, x, y, z, yaw, pitch);
    }

    /**
     * @param location neuer Standort
     * @return dieselbe Anzeige an einem anderen Ort
     */
    public ServerDisplay movedTo(Location location) {
        World target = location.getWorld();
        if (target == null) {
            throw new IllegalArgumentException("Standort ohne Welt");
        }
        return new ServerDisplay(id, group, displayName, headId, target.getName(),
                location.getX(), location.getY(), location.getZ(),
                location.getYaw(), location.getPitch());
    }
}
