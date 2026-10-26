package dev.bytestorm.api.module;

import dev.bytestorm.api.ByteStormApi;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.Map;

/**
 * Kontext, den ein {@link dev.bytestorm.api.CloudModule} beim Laden erhält.
 */
public interface ModuleContext {

    /**
     * @return die öffentliche Cloud-API
     */
    ByteStormApi api();

    /**
     * @return Modul-Datenverzeichnis unter {@code config/modules/}
     */
    Path dataDirectory();

    /**
     * @return Logger mit Modulnamen als Kontext
     */
    Logger logger();

    /**
     * @return schreibgeschützte Modul-Einstellungen aus der Modul-Konfiguration
     */
    Map<String, String> settings();
}
