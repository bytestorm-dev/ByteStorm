package dev.bytestorm.plugins.proxy.command;

/**
 * Zugriff von {@code /cloud} auf das laufende Plugin.
 *
 * <p>Tablist und Sprache sind Plugin-Zustand, nicht Cloud-Zustand: Sie kommen
 * aus {@code config.json} und den Sprachdateien. Über diese Schnittstelle
 * schaltet {@code /cloud tablist} die Datei um, ohne dass der Dispatcher das
 * Plugin oder dessen Dateisystem kennt — und Tests können beides ersetzen.
 */
public interface PluginControl {

    /**
     * @return {@code true}, wenn die Tablist gerade geschrieben wird
     */
    boolean tablistEnabled();

    /**
     * Schaltet die Tablist und persistiert das in {@code config.json}.
     *
     * @param enabled neuer Zustand
     * @throws IllegalStateException wenn die Datei nicht geschrieben oder gelesen werden kann
     */
    void tablistEnabled(boolean enabled);

    /**
     * @return die eingestellte Sprache, {@code null} wenn noch keine Konfiguration geladen ist
     */
    String language();

    /**
     * @return die tatsächlich verwendete Sprache — weicht ab, wenn die Datei fehlt
     */
    String effectiveLanguage();
}
