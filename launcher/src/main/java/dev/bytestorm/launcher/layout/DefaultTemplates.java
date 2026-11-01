package dev.bytestorm.launcher.layout;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Legt die Standard-Templates für Proxy und Server an (§10, §14).
 *
 * <p>Die Dateien ergänzen die Gruppen aus dem Erststart
 * ({@link dev.bytestorm.launcher.wizard.GroupSetup}): Gruppen allein starten noch
 * keinen funktionierenden Netzwerkverbund. Erst diese
 * Templates setzen den Port, schalten Paper auf {@code online-mode=false}, aktivieren
 * Velocity Modern Forwarding und verteilen das gemeinsame Secret über
 * {@code %SECRET%} an Proxy und Backends.
 *
 * <p>Vorhandene Dateien werden <b>nie</b> überschrieben — Anpassungen des
 * Betreibers bleiben erhalten; fehlende werden ergänzt. Fehler beim Anlegen sind
 * nicht tödlich: die Cloud startet auch ohne Templates, dann fehlen nur Port und
 * Forwarding in den Service-Ordnern.
 */
@Slf4j
@UtilityClass
public final class DefaultTemplates {

    /**
     * Datei, aus der Velocity das Forwarding-Secret liest; muss zu
     * {@code forwarding-secret-file} in {@code velocity.toml} passen.
     */
    public static final String SECRET_FILE = "forwarding.secret";

    /** Paper-Konfiguration mit dem Abschnitt {@code proxies.velocity}. */
    public static final String PAPER_GLOBAL_CONFIG = "config/paper-global.yml";

    private static final List<File> FILES = List.of(
            new File("PROXY/velocity.toml", """
                    # Von ByteStorm angelegtes Standard-Template. Anpassungen bleiben erhalten;
                    # die Platzhalter %PORT% und %GROUP% ersetzt die Cloud beim Start.

                    # Version des Velocity-Dateiformats. Fehlt sie, hält Velocity die Datei für
                    # eine alte und ergänzt beim Start Beispiel-Server und Forced Hosts aus
                    # seiner eigenen Vorgabe — der Proxy startet dann nicht, weil diese
                    # Beispiel-Einträge auf keine echten Services zeigen.
                    config-version = "2.9"

                    bind = "0.0.0.0:%PORT%"
                    motd = "ByteStorm %GROUP%"
                    show-max-players = %MAXPLAYERS%
                    online-mode = true

                    # Modern Forwarding (§14): Paper-Backends prüfen die Verbindung mit dem
                    # Secret aus forwarding.secret — dasselbe Secret in config/paper-global.yml.
                    player-info-forwarding-mode = "modern"
                    forwarding-secret-file = "forwarding.secret"

                    # Velocity startet nur mit mindestens einem Server-Eintrag und einer nicht
                    # leeren Reihenfolge. Diesen Platzhalter entfernt die Bridge direkt nach dem
                    # Start; die echten Services kommen aus der Registry (§14).
                    [servers]
                    bytestorm-placeholder = "127.0.0.1:1"
                    try = ["bytestorm-placeholder"]

                    # Leer, aber vorhanden: ohne den Abschnitt füllt Velocity seine Beispiel-Forced-
                    # Hosts nach, die wiederum auf nicht existierende Server verweisen.
                    [forced-hosts]
                    """),
            // Nur das Secret, ohne Kommentare: Velocity liest die Datei als Ganzes und
            // trimmt sie lediglich — Kommentare würden Teil des Schlüssels.
            new File("PROXY/" + SECRET_FILE, "%SECRET%\n"),
            new File("SERVER/server.properties", """
                    # Von ByteStorm angelegtes Standard-Template. Anpassungen bleiben erhalten.
                    # Der Proxy authentifiziert die Spieler (§14), deshalb kein Mojang-Login hier.
                    online-mode=false
                    server-port=%PORT%
                    """),
            // Ohne diese Datei schreibt sich Paper beim Start selbst eine eula.txt mit
            // eula=false und beendet sich sofort ("You need to agree to the EULA"). Der
            // Node sieht nur einen Exit unmittelbar nach dem Start, startet im Backoff neu
            // und killt nach dem dritten Versuch die ganze Gruppe als DEGRADED — genau das
            // Fehlerbild "Server stürzen dauernd ab".
            new File("SERVER/eula.txt", """
                    # Von ByteStorm angelegtes Standard-Template.
                    # eula=true ist die Zustimmung zur Minecraft-EULA (https://aka.ms/MinecraftEULA).
                    eula=true
                    """),
            new File("SERVER/" + PAPER_GLOBAL_CONFIG, """
                    # Von ByteStorm angelegtes Standard-Template. Anpassungen bleiben erhalten.
                    # Modern Forwarding (§14): enabled + Secret wie in forwarding.secret des Proxys,
                    # online-mode wie online-mode in velocity.toml (der Proxy authentifiziert).
                    proxies:
                      velocity:
                        enabled: true
                        online-mode: true
                        secret: '%SECRET%'
                    """));

    /**
     * Legt fehlende Standard-Templates unter {@code templates/} an.
     *
     * @param layout Cloud-Layout mit {@code templates/}
     * @return die neu angelegten Dateien, leer wenn schon alles vorhanden war
     * @throws IOException wenn eine Datei nicht geschrieben werden kann
     */
    public static List<Path> write(CloudLayout layout) throws IOException {
        List<Path> created = new ArrayList<>();
        for (File file : FILES) {
            Path target = layout.templates().resolve(file.relative()).normalize();
            if (!target.startsWith(layout.templates())) {
                log.warn("Standard-Template '{}' zeigt aus templates/ heraus", file.relative());
                continue;
            }
            if (Files.exists(target)) {
                continue;
            }
            Files.createDirectories(target.getParent());
            Files.writeString(target, file.content(), StandardCharsets.UTF_8);
            created.add(target);
        }
        return List.copyOf(created);
    }

    /**
     * @return die relativen Pfade der Standard-Templates
     */
    public static List<String> relativePaths() {
        return FILES.stream().map(File::relative).toList();
    }

    private record File(String relative, String content) {
    }
}
