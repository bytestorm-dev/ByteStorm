package dev.bytestorm.plugins.lobby.display;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Kopf der Server-Anzeige.
 *
 * <p>Ohne Angabe schwebt ein schlichter Spielerkopf. Ein Eintrag ist eine
 * Textur-URL; eine falsche URL kostet nur die Textur, nicht die Anzeige — sonst
 * wäre ein Tippfehler in der Konfiguration ein unsichtbarer Server.
 *
 * <p>Paper erwartet die Textur als Base64-Property, nicht als URL. Das JSON
 * darum ist die kleinste Form, die der Client akzeptiert; der Kopf braucht keine
 * echte Spieler-UUID, weil er nie einem Konto zugeordnet wird.
 */
public final class Skulls {

    private static final Logger LOGGER = Logger.getLogger("ByteStormLobby");
    private static final String TEXTURES = "textures";

    private Skulls() {
    }

    /**
     * @param textureUrl Textur-URL oder {@code null}
     * @return Kopf mit Textur, sonst ein schlichter Spielerkopf
     */
    public static ItemStack head(@Nullable String textureUrl) {
        ItemStack stack = new ItemStack(Material.PLAYER_HEAD);
        if (textureUrl == null || textureUrl.isBlank()) {
            return stack;
        }
        if (!(stack.getItemMeta() instanceof SkullMeta meta)) {
            return stack;
        }

        String url = textureUrl.trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            LOGGER.warning("Kopf-Textur '" + url + "' ist keine URL — schlichter Kopf");
            return stack;
        }

        PlayerProfile profile = Bukkit.createProfile(UUID.randomUUID(), null);
        profile.setProperty(new ProfileProperty(TEXTURES, encode(url)));
        meta.setPlayerProfile(profile);
        stack.setItemMeta(meta);
        return stack;
    }

    /**
     * @param url Textur-URL
     * @return Base64 der Texturen-Eigenschaft
     */
    static String encode(String url) {
        String json = "{\"textures\":{\"SKIN\":{\"url\":\"" + url + "\"}}}";
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}
