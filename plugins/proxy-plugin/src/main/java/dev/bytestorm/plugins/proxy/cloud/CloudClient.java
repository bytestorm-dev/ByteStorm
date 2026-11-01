package dev.bytestorm.plugins.proxy.cloud;

import java.util.List;

/**
 * Zugriff des Proxy-Plugins auf die Cloud (§11, §16).
 *
 * <p>Die Schnittstelle bleibt absichtlich datenorientiert: Sie kennt nur die
 * Felder, die {@code /cloud} anzeigt und ändert. Die Umsetzung
 * ({@link HttpCloudClient}) spricht mit dem REST-Modul der Cloud; Tests setzen
 * eine eigene Implementierung ein und brauchen weder Netz noch Cloud.
 */
public interface CloudClient {

    /**
     * @return alle Services, alphabetisch nach Namen
     * @throws CloudException wenn die Cloud nicht antwortet oder ablehnt
     */
    List<ServiceView> services();

    /**
     * @return alle Gruppen, alphabetisch nach Namen
     * @throws CloudException wenn die Cloud nicht antwortet oder ablehnt
     */
    List<GroupView> groups();

    /**
     * @return alle aktuell verbundenen Spieler, alphabetisch nach Namen
     * @throws CloudException wenn die Cloud nicht antwortet oder ablehnt
     */
    List<PlayerView> players();

    /**
     * @param serviceId Service-UUID als String
     * @param lines     gewünschte Zeilenzahl
     * @return die letzten Logzeilen, leer wenn keine vorliegen
     * @throws CloudException wenn die Cloud nicht antwortet oder ablehnt
     */
    List<String> logs(String serviceId, int lines);

    /**
     * Startet einen Service der Gruppe.
     *
     * @param group Gruppenname
     * @return der gestartete Service
     * @throws CloudException wenn der Start scheitert
     */
    ServiceView start(String group);

    /**
     * Fährt einen Service herunter.
     *
     * @param serviceId Service-UUID als String
     * @param force     {@code true} überspringt den Graceful-Timeout
     * @throws CloudException wenn der Stop scheitert
     */
    void stop(String serviceId, boolean force);

    /**
     * Startet einen Service neu.
     *
     * @param serviceId Service-UUID als String
     * @throws CloudException wenn der Neustart scheitert
     */
    void restart(String serviceId);

    /**
     * Schickt eine Zeile an die Service-Konsole.
     *
     * @param serviceId Service-UUID als String
     * @param command   Befehl ohne Zeilenende
     * @throws CloudException wenn der Service ihn nicht annimmt
     */
    void execute(String serviceId, String command);

    /**
     * Wendet die Templates eines Services erneut an.
     *
     * @param serviceId Service-UUID als String
     * @throws CloudException wenn das Anwenden scheitert
     */
    void reapply(String serviceId);

    /**
     * Meldet einen Service als {@code ONLINE}.
     *
     * @param serviceId Service-UUID als String
     * @throws CloudException wenn der Service unbekannt ist
     */
    void online(String serviceId);

    /**
     * Schaltet den Wartungsmodus einer Gruppe.
     *
     * @param group   Gruppenname
     * @param enabled {@code true} aktiviert ihn
     * @throws CloudException wenn die Cloud ablehnt
     */
    void maintenance(String group, boolean enabled);

    /**
     * Skaliert eine Gruppe.
     *
     * @param group  Gruppenname
     * @param target gewünschte Anzahl Services
     * @throws CloudException wenn die Cloud ablehnt
     */
    void scale(String group, int target);

    /**
     * Schickt einen Spieler auf einen anderen Service.
     *
     * @param uuid    Spieler-UUID als String
     * @param service Ziel-Service
     * @throws CloudException wenn das Routing scheitert
     */
    void connect(String uuid, String service);

    /**
     * Trennt einen Spieler vom Netz.
     *
     * @param uuid   Spieler-UUID als String
     * @param reason Begründung für den Spieler
     * @throws CloudException wenn der Kick scheitert
     */
    void kick(String uuid, String reason);

    /**
     * Ein Service, so wie {@code /cloud} ihn zeigt.
     *
     * @param id      Service-UUID
     * @param name    Name, z. B. {@code Lobby-1}
     * @param group   Gruppe
     * @param state   Zustand, z. B. {@code ONLINE}
     * @param port    Port, {@code 0} wenn unbekannt
     * @param online  verbundene Spieler
     * @param max     Obergrenze, {@code 0} steht für unbegrenzt
     * @param runtime Runtime, z. B. {@code PAPER}
     * @param crash   Absturz-Budget und letzter Absturz
     */
    record ServiceView(String id, String name, String group, String state,
                       int port, int online, int max, String runtime, CrashView crash) {

        /**
         * @return {@code online/max} oder nur die Zahl ohne Obergrenze
         */
        public String players() {
            return max <= 0 ? String.valueOf(online) : online + "/" + max;
        }

        /**
         * @return der Port, oder {@code -} ohne Port
         */
        public String address() {
            return port <= 0 ? "-" : String.valueOf(port);
        }
    }

    /**
     * Absturz-Zustand eines Services (§12.5): wie viele Neustarts ihm noch
     * bleiben und warum er zuletzt beendet wurde.
     *
     * @param remaining verbleibende Abstürze
     * @param total     Budget eines frisch gestarteten Services, {@code 0} wenn unbekannt
     * @param reason    Ursache des letzten Absturzes, leer wenn keiner vorliegt
     * @param time      Zeitpunkt des letzten Absturzes als Epochen-Millis
     * @param exitCode  Exit-Code des letzten Absturzes
     */
    record CrashView(int remaining, int total, String reason, long time, int exitCode) {

        /**
         * @return der Zustand eines Services ohne Budget und ohne Absturz
         */
        public static CrashView none() {
            return new CrashView(0, 0, "", 0, 0);
        }

        /**
         * @return {@code remaining/total}, oder {@code -} wenn kein Budget bekannt ist
         */
        public String budget() {
            return total <= 0 ? "-" : remaining + "/" + total;
        }

        /**
         * @return {@code true}, wenn ein Absturz samt Ursache vorliegt
         */
        public boolean crashed() {
            return reason != null && !reason.isBlank();
        }
    }

    /**
     * Eine Gruppe, so wie {@code /cloud} sie zeigt.
     *
     * @param name        Gruppenname
     * @param type        Service-Typ
     * @param mode        Modus
     * @param maintenance Wartungsmodus aktiv
     * @param services    aktuell laufende Services der Gruppe
     * @param maxServices Obergrenze der Gruppe
     * @param maxPlayers  Spieler-Obergrenze
     */
    record GroupView(String name, String type, String mode, boolean maintenance,
                     int services, int maxServices, int maxPlayers) {
    }

    /**
     * Ein Spieler, so wie {@code /cloud} ihn zeigt.
     *
     * @param uuid   Spieler-UUID
     * @param name   Name
     * @param proxy  Proxy, über den er verbunden ist
     * @param server Service, auf dem er spielt
     */
    record PlayerView(String uuid, String name, String proxy, String server) {
    }
}
