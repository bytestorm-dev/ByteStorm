# Changelog

Alle nennenswerten Änderungen an ByteStorm. Aufbau nach
[Keep a Changelog](https://keepachangelog.com/de/1.1.0/), Versionen nach
[Semantic Versioning](https://semver.org/lang/de/).

## [Unveröffentlicht]

## [1.0.1] - 2026-11-02

### Neu

- **Startbanner**: Beim Start deckt sich das Logo aus `assets/banner.txt` mittig in
  der Terminalbreite auf, hält drei Sekunden und verschwindet, bevor das Start-Log
  beginnt. Ohne Terminal (Pipe, CI) entfällt es; die Vorlage lässt sich ersetzen.
- **Alarmierung**: Die Cloud prüft alle 30 Sekunden MongoDB, Redis, die Runtime
  (über denselben Healthcheck wie `/health`), `DEGRADED`-Gruppen, abgestürzte
  Services und den freien Speicher. Jeder Alarm wird genau einmal gemeldet und
  beim Abklingen genau einmal als behoben — im Cloud-Log und in der
  Ereignisspur `logs/alerts.log`. Aufbau und Schwellen stehen in
  [`docs/deployment.md`](docs/deployment.md) §7.
- **`alerts`**: Kommando für die aktive Alarmliste in der laufenden Cloud,
  kritische zuerst, mit Quelle und nächstem Schritt.
- **Prometheus-Regeln**: `deploy/prometheus/bytestorm.rules.yml` alarmiert die
  Fälle, die `alerts.log` nicht sehen kann — fehlender Scrape, anhaltender
  Healthcheck-Fehler, kein `ONLINE`-Service, hängender Start, knappes
  Crash-Budget und vergessene Wartung.

### Geändert

- **Einheitliches Zeilenformat**: Konsole, `service screen` und `watch`/`status`
  schreiben jede Zeile als `16:42:01|INFO  Text` — Uhrzeit, farbiges Level und
  Nachricht, den Logger-Namen nur in `logs/cloud.log`. Der Service-Screen
  übersetzt `[OUT]`/`[ERR]` in `INFO`/`ERROR` und das Dashboard zeigt die letzten
  Zeilen aus `logs/cloud.log`.
- **Konsole ohne Vorspann**: Der Begrüßungsrahmen entfällt. Der Prompt
  `bytestorm@1.0.1» ` folgt direkt auf das Start-Log.
- **Whitelist standardmäßig aus**: Das Server-Template setzt `white-list=false` und
  `enforce-whitelist=false`. Den Zugang regelt der Proxy (Wartungsmodus und
  Rechte); eine Backend-Whitelist würde jeden Spieler abweisen, den nur der Proxy
  kennt.
- **`/cloud` zeigt jede Unterkommando-Zeile**: Statt `<list|info|…>` steht jedes
  Kommando mit seinen Argumenten auf einer eigenen Zeile, in beiden Sprachen.

## [1.0.0] - 2026-11-01

Erste stabile Version: eine Cloud, die Proxy und Backends startet, überwacht,
neu startet und skaliert — mit Erststart-Einrichtung, Betriebswerkzeugen und
einer prüfbaren Auslieferung.

### Neu

- **Erststart-Wizard**: Abfrage von MongoDB und Redis inklusive Verbindungstest,
  Anlegen mehrerer Gruppen (Proxy und Backends) in einem Durchgang, Auswahl der
  optionalen Module und Entscheidung über die Minecraft-EULA. Ohne Terminal
  bricht die Einrichtung mit einer Anleitung ab, statt auf Eingaben zu warten.
- **Gruppen**: statische und dynamische Gruppen, Minimum/Maximum,
  Spielerzahl-, Zeitplan- und Host-Last-Regeln, Wartungsmodus und
  `DEGRADED`-Sperre nach aufgebrauchten Neustarts. Proxies werden vor Backends
  gestartet; `group create` führt durch alle Felder.
- **Node**: PROCESS- und Docker-Runtime, PID-Datei je Service mit Übernahme oder
  Aufräumen laufender Prozesse beim Start, Port-Leases aus dem Pool
  25565–29999 (Server ab 30000), Template-Reapply im Betrieb sowie Rotation und
  Begrenzung von Service-Logs und Crash-Berichten.
- **Templates**: geschichtete Vorlagen (`all`, `proxy`, `server`, `fallback`) mit
  Platzhaltern, Forwarding-Secret und `paper-global.yml`; `doctor` prüft die
  Weiterleitung und `doctor --fix` repariert sie, laufende Services inklusive.
- **Bridge**: Plugins für Paper, Velocity und BungeeCord mit Heartbeat,
  Dienst-Registrierung, Fallback-Auswahl und PlaceholderAPI-Erweiterung für
  Spielerzahlen und Dienstzustände.
- **REST-Modul**: `GET /health`, `GET /nodes/status` und ein
  Prometheus-`GET /metrics`; Scopes je API-Key mit Familien-Wildcards,
  Rate-Limit pro Key und Minute, Standard-Bind auf `127.0.0.1`.
- **Diagnose**: `doctor` prüft Pfade, Ports, Java, MongoDB/Redis, doppelte
  Adressen, Forwarding, Proxy und Fallback-Backend, EULA und die Erreichbarkeit
  der REST-API; `module info` zeigt die Endpunkte je Scope.
- **Betrieb**: `backup` sichert MongoDB, `config/`, `templates/` und `static/` in
  ein Zip, dazu ein Restore-Runbook; `updater` sucht und lädt Aktualisierungen
  über GitHub-Releases (nur auf Anforderung, nie automatisch installiert).
- **Auslieferung**: Launcher mit Nachladen der Abhängigkeiten und startbares
  Zip mit `bytestorm.jar`, `lib/`, `plugins/`, `LICENSE` und `NOTICE`;
  `.github/workflows/ci.yml` baut jeden Push und PR, `release.yml` nur auf Tag
  `v<version>`.

### Sicherheit

- MongoDB und Redis verweigern den Start ohne Authentifizierung; Ausnahmen sind
  ausdrücklich zu setzen (`security.allowInsecureMongo` bzw.
  `allowInsecureRedis`) und werden im Log gemeldet.
- Das Redis-Passwort der Bridge liegt nicht mehr in der Prozessumgebung: Der Node
  schreibt `.bytestorm-bridge.properties` mit Rechten `0600` in den
  Service-Ordner und übergibt nur den Dateinamen.
- `doctor` warnt vor aktiven Ausnahmen, vor Datenbanken außerhalb von localhost
  und vor einem Vollzugriffs-Key, während die REST-API auf einer
  Nicht-Loopback-Adresse lauscht.

### Behoben

- Ein gestoppter Proxy gab seinen Port nicht frei; ein Neustart scheiterte mit
  „Kein freier Port in 25565-25565". Ports werden beim Stop freigegeben, der
  Proxy-Pool reicht von 25565 bis 29999.
- Der Erststart legte ungefragt eine `Proxy`- und eine `Lobby`-Gruppe an; jetzt
  entstehen nur die Gruppen, die im Wizard gewählt wurden.
- Ein frischer Paper-Service startete nicht, weil die EULA ungeklärt war; der
  Erststart fragt sie ab, `eula status|accept|revoke` ändert sie später.
- Nach einem Cloud-Neustart blieben Prozesse als verwaiste Registry-Einträge
  zurück; PID-Dateien werden jetzt übernommen oder aufgeräumt.
- Falsches Forwarding (`player-info-forwarding-mode`, abweichendes Secret) fiel
  erst beim Verbindungsversuch eines Spielers auf; `doctor` erkennt es,
  `doctor --fix` setzt Template und Secret neu.

[Unveröffentlicht]: https://github.com/bytestorm-dev/ByteStorm/compare/v1.0.1...HEAD
[1.0.1]: https://github.com/bytestorm-dev/ByteStorm/compare/v1.0.0...v1.0.1
[1.0.0]: https://github.com/bytestorm-dev/ByteStorm/releases/tag/v1.0.0
