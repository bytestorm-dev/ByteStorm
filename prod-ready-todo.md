# ByteStorm — Production-Readiness-TODO

Diese Datei beantwortet eine andere Frage als [`TODO.md`](./TODO.md): Dort stehen die
Feature-Phasen („ist §11 umgesetzt?"), hier steht, was zwischen *„läuft im Test"* und
*„läuft beim Kunden, unbeaufsichtigt, über Nacht"* fehlt. Jeder Punkt ist bewusst als
Arbeitspaket formuliert und verweist auf die Stelle in `TODO.md`, an der er schon
vorgemerkt ist (falls dort vorgemerkt).

**Stand:** 2026-11-01 · **Version im Repo:** `1.0.0` · **Tests:** 825 grün, opt-in-Tests übersprungen

**Legende:** `[ ]` offen · `[~]` teilweise vorhanden · `[!]` Entscheidung nötig

**Prioritäten**

| Prio | Bedeutung |
|---|---|
| **P0** | Blocker — ohne diesen Punkt darf keine echte Instanz live gehen |
| **P1** | Vor breitem Rollout — sonst fällt es im Betrieb auf die Füße |
| **P2** | Reife/Härtung — nach dem ersten stabilen Betrieb |

---

## 0. Ampel

| Bereich | Stand | Urteil |
|---|---|---|
| Kern (Lifecycle, Crash, Ports, Reconciliation, Templates, Adoption) | weit | tragfähig, Lücke beim Template-Watcher |
| Distribution & Release | Zip + GitHub-Release | **nicht** releasefähig (Version `SNAPSHOT`, keine Prüfsumme, keine PR-CI) |
| Security (§20) | Pflicht-Anmeldung für Mongo/Redis, REST-Scopes + Rate-Limit je Key, Bind auf `127.0.0.1`, Doctor-Prüfungen | weit tragfähig — offen: TLS-Konzept, Rotation, OS-Härtung, CVE-Scan |
| Betrieb (Backup, Monitoring, Deployment) | `backup`-Kommando, `/health` + `/nodes/status` + `/metrics`, Alarmprüfung mit `logs/alerts.log`, Deployment-Runbook | teilweise — Alarme bleiben lokal, Metriken je Gruppe fehlen |
| Funktionen (Plattformen, REST, Discord, Docker) | Paper/Velocity/Bungee | Lücken, die den Alltag treffen (Purpur, REST-Rollen, Docker produktiv) |

**Kurzfassung:** Die Cloud kann heute einen Proxy und Paper-Backends starten, überwachen,
neu starten und skalieren. Für Produktion fehlen vor allem **Betriebsreife** (Release,
Security-Härtung, Backup, Monitoring, Deployment-Doku) und **zwei, drei Funktionslücken**,
die sofort auffallen (EULA-Erstlauf, laufende Prozesse nach Cloud-Neustart, Plattform-Adapter).

---

## 1. P0 — Blocker für den ersten Produktivbetrieb

### 1.1 Release, Versionierung, Auslieferung

- [x] `version` in `gradle.properties` auf `1.0.0` gesetzt; der Release-Workflow läuft nur auf Tag `v<version>` und bricht ab, wenn Tag und Version auseinanderlaufen
- [~] Release-Prozess: Tag `v<version>` löst den Release aus, Tag-Gleichstand wird geprüft; Changelog aus gepflegten Einträgen und „Beta → Stable"-Kriterien fehlen noch (`--generate-notes` läuft weiter)
- [ ] Artefakt prüfbar machen: `SHA-256` zum Zip veröffentlichen (Downloader prüft Jars bereits per Hash — das Zip selbst bisher nicht) und Signatur/Provenance ergänzen
- [x] Eigener CI-Workflow: `.github/workflows/ci.yml` baut und testet auf jedem Push und PR ohne `contents: write`; `release.yml` läuft nur auf Tags
- [ ] Reproduzierbarkeit: Dependency-Locking bzw. `verification-metadata` für alle Module, Wrapper-Checksumme in `gradle-wrapper.properties` pflegen
- [ ] Versionsquelle vereinheitlichen: `version` (Gradle) ↔ Manifest (`CloudVersion`) ↔ Release-Tag müssen zwangsläufig zusammenpassen; ein Test/Wächter verhindert Auseinanderlaufen
- [x] `LICENSE` (Apache-2.0) und `NOTICE` mit den mitgelieferten Bibliotheken; beide liegen im Repository und in der Distributions-Zip
- [ ] Upgrade- **und** Rollback-Pfad dokumentieren und einmal durchspielen: `updater` legt ersetzte Dateien unter `update/backup/` ab, das Zurückrollen ist nirgends beschrieben (offen in `TODO.md` §14: Token für private Repos, Event beim Zustandswechsel)

### 1.2 Erstlauf auf einem echten System

- [x] **EULA:** Der Erststart-Wizard fragt „Minecraft-EULA akzeptieren?" und schreibt erst nach einem Ja `templates/SERVER/eula.txt` mit `eula=true`; ohne Zustimmung bleibt die Datei weg und der Wizard nennt den manuellen Weg. Die Standard-Templates legen sie nicht mehr an, `doctor` meldet unter `EULA` eine fehlende oder abgelehnte Zustimmung (nur wenn eine Server-Gruppe existiert). `eula status|accept|revoke` ändert die Zustimmung später und schreibt sie auch in bestehende Service-Ordner
- [ ] Erststart-Wizard auf leerem System als Abnahmetest durchführen (`TODO.md` §17: offen) — inklusive fehlender Plattform-Jars (erster Start lädt Paper/Velocity nach, das braucht Netz und Zeit)
- [ ] Download-Verhalten in eingeschränkten Netzen dokumentieren: HTTP-Proxy/Corporate-Firewall, Offline-Anleitung (der `DependencyDownloader` hat eine, die Plattform-Downloads brauchen dieselbe Behandlung)
- [ ] Dateisystem-Annahmen prüfen: POSIX-Rechte (`0600` auf `config/cloud.json`) entfallen auf Nicht-POSIX-Dateisystemen — Hinweis in die Doku, nicht nur ins Javadoc
- [ ] Verhalten bei fehlendem `lib/`/`platforms/` und bei halb entpacktem Zip klar dokumentieren (Startfehlermeldung + was zu tun ist)

### 1.3 Security (§20 — in `TODO.md` §15 komplett offen)

- [x] **Redis-Passwort verpflichtend:** der Start verweigert ohne Passwort; nur `security.allowInsecureRedis` (oder das gemeinsame `allowInsecure`) lässt ihn bewusst ohne Authentifizierung laufen und protokolliert das
- [ ] **Kein TLS für Redis** als bewusste Entscheidung dokumentieren (Deployment-Hinweis, Firewall, privates Netzsegment) — inklusive Warnung beim Start, wenn Redis nicht auf `localhost`/privatem Netz lauscht
- [x] **MongoDB-Auth Pflicht:** eine URI ohne Benutzer (`mongodb://localhost:27017`) wird beim Start abgelehnt, Ausnahme nur über `security.allowInsecureMongo`
- [x] **`doctor` prüft die Zugänge:** warnt bei aktiver Ausnahme und bei einer Datenbank, die nicht lokal liegt; Anleitung für eigene Benutzer in [`docs/security.md`](./docs/security.md)
- [x] **MongoDB-Rechte:** eigener Benutzer mit Rechten ausschließlich auf die Cloud-Datenbank — Anleitung in [`docs/security.md`](./docs/security.md) („MongoDB: eigener Benutzer")
- [x] **REST absichern:** Standard-Bind ist `127.0.0.1` (Modul und Erststart-Wizard) — für den Zugriff von außen Reverse-Proxy mit TLS; Doku „REST nie direkt ins Internet"
- [x] **REST-Auth ausgebaut:** Scopes je Key (`services.read` … `monitoring.read`, Familien-Wildcards, `*`), Vorgabe „nur lesen", mehrere Keys über `apiKey.<name>`/`scopes.<name>`, Rate-Limit je Key und Minute; `module list|info|access` zeigt die Rechte, `module info` zusätzlich die Endpunkte je Scope
- [ ] **Key-Rotation:** ein Key lässt sich ersetzen, gilt aber bis zum Entfernen; Ablaufdatum und „letzte Nutzung" fehlen (`TODO.md` §11: „JWT mit Rollen offen")
- [ ] Fehlerformat um `code` + Felddetails erweitern (`TODO.md` §11 offen) und CORS-Entscheidung treffen
- [x] **Secrets nicht mehr in der Prozessumgebung:** der Node schreibt `.bytestorm-bridge.properties` (Rechte `0600`) in den Service-Ordner und übergibt nur den Dateinamen; `/proc/<pid>/environ` und `docker inspect` enthalten weder Adresse noch Passwort. Was bleibt: Wer als Dienstbenutzer an die Datei kommt, liest das Secret weiterhin — das ersetzt keine Benutzertrennung auf dem Host
- [ ] Rotation dokumentieren: Forwarding-Secret, REST-Key, Redis-/Mongo-Passwort — jeweils mit „was bricht dabei" (laufende Proxies/Backends)
- [ ] Redaktions-Test ergänzen, der alle Logpfade und Exception-Texte auf Secrets prüft (heute redigieren `MongoSettings`/`RedisSettings`/`BridgeSettings` gezielt; ein Test über den ganzen Start fehlt)
- [ ] Betriebssystem-Härtung: eigener Dienstbenutzer, Rechte auf `running/`, `static/`, `logs/`, kein Root-Start
- [ ] Dependency-/CVE-Scanning in die CI (Dependabot oder OWASP-Dependency-Check)

### 1.4 Backup & Wiederherstellung

- [x] **MongoDB-Backup:** `backup` exportiert alle Collections per `mongodump` samt `config/`, `templates/` und `static/` in ein Zip, Restore-Runbook in [`docs/backup.md`](./docs/backup.md); Zeitplan und Aufbewahrung bleiben offen
- [ ] **Redis:** Persistenzmodus festlegen (AOF/RDB) und dokumentieren, was bei Verlust rekonstruierbar ist — Live-State ist flüchtig, Namenszähler/Ports zieht die Reconciliation teilweise nach, laufende Services nicht
- [x] `templates/`, `static/` und `config/` liegen im selben Zip; dass Welten laufender Dienste nur als Momentaufnahme gesichert werden, steht in [`docs/backup.md`](./docs/backup.md)
- [ ] **Restore einmal echt üben** und die Schritte (Reihenfolge: Mongo → Redis → Cloud → Services) als Runbook festhalten
- [x] Retention für Service-Logs und Crash-Berichte: `latest.log` wird ab 10 MB nach `latest.log.1` gedreht, je Service bleiben die neuesten 20 Berichte (ältere Ordner samt Kopien werden gelöscht); das Cloud-Log rotiert unverändert über Logback
- [ ] Festplatten-Wächter/Warnung, bevor die Platte durch Logs oder Weltdaten volläuft

### 1.5 Monitoring, Health, Alerting

- [x] Health **nach außen** gebracht: `GET /health`, `GET /nodes/status` und `GET /metrics` (Prometheus-Textformat) im REST-Modul, alle mit `monitoring.read`; erreichbar nur bei aktivem Modul und nur lokal, solange der Bind auf `127.0.0.1` steht
- [ ] Metriken (Prometheus/OpenTelemetry): Services je Gruppe und Zustand, Startdauer, Restarts, Heartbeat-Alter, Redis-/Mongo-Latenz, Event-/Log-Durchsatz, Heap
- [x] **Alarme** für Gruppe `DEGRADED`, Crash, Redis/Mongo/Runtime nicht erreichbar und Disk knapp: die Cloud prüft alle 30 s, entprellt je Zustand und schreibt nach `logs/alerts.log` plus Cloud-Log (`docs/deployment.md` §7)
- [ ] **Alarme nach außen** (Discord/Webhook/E-Mail) als Modul; heute liest nur die Datei, wer sie lesen will
- [x] **Prometheus-Regeln** in `deploy/prometheus/bytestorm.rules.yml` für die Fälle, die `alerts.log` nicht sieht (fehlender Scrape, anhaltend krank, kein `ONLINE`-Service, hängender Start, knappes Crash-Budget, Wartung vergessen); Scrape-Beispiel in `docs/deployment.md` §7.1
- [x] **`alerts`-Kommando** zeigt die aktiven Alarme der laufenden Cloud mit Stufe und Quelle
- [ ] Alarm für **Start-Timeout**, **Heartbeat-TTL-Ablauf** und **REST `5xx`-Rate** — die ersten beiden brauchen einen Zähler über Zeit, der letzte eine Fehlerstatistik im REST-Modul
- [ ] Strukturiertes Logging mit MDC (`serviceId`, `groupId`, `module`) — `TODO.md` §15 offen; heute sind die Meldungen Text mit Parametern
- [ ] Zentrale Log-Sammlung/Zustellung optional (Datei bleibt Quelle), aber Retention und Rotation für Service-Logs klären
- [ ] **Lastmessung** bei ~100 Services (Redis-Roundtrips, Heartbeat-/Scaling-Last, Log-Batching) mit dokumentierten Zahlen (`TODO.md` §15 offen)

### 1.6 Deployment & Betriebshandbuch

- [~] `docs/deployment.md` deckt Installation (systemd, Container-Skizze), Firewall-Matrix, Prüfungen, Update/Rückweg und eine Restore-Übung ab; `README.md` und `docs/architecture.md` fehlen weiterhin
- [~] systemd-Unit steht in [`docs/deployment.md`](./docs/deployment.md); ein fertiges Container-Image und ein Compose-Beispiel mit Mongo/Redis fehlen (dort nur eine Skizze)
- [ ] **JDK-Anforderung klären:** Toolchain und Laufzeit sind JDK 26 (kein LTS). Entweder auf ein LTS-Target gehen oder die Entscheidung samt Support-/Bezugsweg dokumentieren; dazu die Auswahl des Service-JVMs (`javaVersion` pro Gruppe ist Feld, aber ohne Wirkung — `TODO.md` §9)
- [ ] Port-/Firewall-Matrix: Proxy-Port, Serverbereich ab 30000, REST-Port, Redis, Mongo — inklusive „was darf von außen erreichbar sein"
- [ ] Ressourcen-Empfehlungen (RAM/CPU/Disk je Service, Cloud-Heap), `ulimit`/File-Descriptor-Limits, Zeitzone (Systemzone vs. UTC, siehe §4)
- [ ] Docker-Betrieb dokumentieren: benötigte Rechte, Socket-Zugriff, Grenzen (`TODO.md` §10 offen) — inklusive der bewussten Entscheidung „keine CPU/RAM-Limits"
- [ ] **Single-Node-Grenzen** dokumentieren: fällt die Cloud aus, laufen die Prozesse weiter, werden aber nicht adoptiert; ein zweiter Node ist heute nicht vorgesehen. Entscheidung: Ist Single-Node für die Zielgruppe okay?

---

## 2. P1 — Funktionslücken, die im Betrieb auffallen

### 2.1 Templates

- [ ] SHA-256 über Pfad+Inhalt für `TemplateInfo.hash` (`TODO.md` §6 offen)
- [ ] `WatchService` auf `templates/` (rekursiv, Debounce) → `TemplateUpdated` → Metadaten in Mongo (`TODO.md` §6, §7.3 offen)
- [x] `service reapply <name>`: wendet die Templates erneut an, ohne zu starten; `doctor --fix` nutzt denselben Weg und startet laufende Services dafür neu
- [ ] Konfliktregel dokumentieren: Was passiert mit manuell geänderten Dateien im Service-Ordner beim Reapply/Rollout?
- [ ] Template-Verteilung über mehrere Nodes (heute reiner lokaler Ordner) — nur nötig, falls Multi-Node kommt

### 2.2 Plattformen & Java-Versionen

- [ ] `PurpurAdapter` (Purpur-API), `SpigotAdapter`/`BungeeAdapter` mit klarer „nicht verfügbar"-Meldung, wenn keine Build-Quelle existiert (`TODO.md` §9)
- [ ] Folia-Scheduler-Pfad auf einer echten Folia-Version verifizieren (`TODO.md` §13)
- [ ] `javaVersion` pro Gruppe tatsächlich umsetzen (passendes Java-Binary auswählen), nicht nur mitführen
- [ ] Plattform-Updates im laufenden Betrieb: Version pro Gruppe pinnen vs. „neueste stabile", Update-Kommando, Verhalten bei laufenden Services
- [ ] Adapter-Matrix dokumentieren: Plattform × unterstützte Versionen × Java-Anforderung (`TODO.md` §13)

### 2.3 DockerRuntime produktiv

- [ ] Gruppen-Override `runtime` (Process/Docker) durchreichen (`TODO.md` §10)
- [ ] Image-Tag/Java-Version konfigurierbar
- [ ] Port-Mapping, Volume-Mount, Bridge-Ordner im Container prüfen und dokumentieren
- [ ] Fehlerbilder als eigene Typen: Daemon weg, Image fehlt, Port belegt, `permission denied` auf dem Socket
- [ ] Integrationstest gegen einen echten Docker-Daemon (in dieser Umgebung nicht möglich, `TODO.md` §5.3/§10)
- [ ] Ressourcenlimits (CPU/RAM) vorbereiten, **nicht** implementieren — aber das Risiko „ein Service frisst den Host" benennen

### 2.4 REST-Modul

- [x] Routen für den Betrieb: `GET /services`, `GET /services/logs`, `POST /services/{start,stop,restart,command,reapply,online}`, `GET /groups`, `POST /groups/{maintenance,scale}`, `GET /players`, `POST /players/{connect,kick}`, `GET /health`, `GET /nodes/status`, `GET /metrics`
- [ ] Restliche Routen: Gruppen-CRUD (`POST /groups`, `PUT/DELETE /groups/{name}`), Templates (`GET /templates`, `POST /templates/{name}/sync`), Spieler-Aktionen unter `POST /players/{uuid}/connect` (`TODO.md` §11)
- [ ] WebSocket für Live-Logs und Service-Events (`TODO.md` §11)
- [~] Auth: statische Keys mit Scopes je Endpunkt (siehe §1.3); JWT/Rollen und `config/modules/rest.json` als Alternative zur `modules_config` fehlen
- [ ] OpenAPI-Spezifikation + Beispiele, damit Clients nicht gegen den Code entwickeln

### 2.5 Discord-Modul

- [ ] Komplettes Modul (`TODO.md` §12): JDA, Token/Guild/Rollen-Config, `/cloud status`, `/service start|stop|list`, `/group scale`, `/logs`, Benachrichtigungen für Start/Stop/Crash/Scaling, Rechte-Check, Rate-Limit-/Reconnect-Verhalten, Tests mit gemocktem Event

### 2.6 CLI & Administration

- [ ] Command-Framework: Registrierung, Aliases, Argument-Parsing, Usage-Fehler, `help` je Command (`TODO.md` §14)
- [ ] Nicht-blockierende Kommandos (Netz/Runtime auf Virtual Threads, UI bleibt bedienbar)
- [ ] Nicht-interaktiver Modus (`--command`/Pipe) für Skripte und Automatisierung
- [ ] Einheitliche Fehlerausgabe ohne Stacktrace, `--debug` für Details
- [ ] `info` vervollständigen (Cluster-/Runtime-Typ, CPU, Event-Abonnements) und Tab-Completion für Plattformen
- [ ] Betriebskommandos, die im Alltag fehlen: Health-/Diagnose-Übersicht, Backup-Trigger (oder Verweis auf externe Jobs), `module`-Neustart

### 2.7 Robustheit & Betriebsverhalten

- [x] **Adoption laufender Prozesse** nach Cloud-Neustart: Jeder Service schreibt `.bytestorm.pid` in seinen Ordner; der Start übernimmt lebende Prozesse unter ihrer alten ID (die Bridge meldet sie wieder `ONLINE`) und räumt Einträge toter Prozesse samt Port-Reservierung auf. Grenzen: Konsolenbefehle und neue Logzeilen fehlen bis zum Neustart, Container werden nicht übernommen
- [ ] `running/`-Reste beim Start aufräumen (Persistenz-Hygiene, `TODO.md` §15)
- [ ] Wartungsmodus auf Backends: Kick-/Whitelist-Verhalten beim Start (`TODO.md` §8.2)
- [x] Proxy-Priorität beim Skalieren: ein Lauf startet Proxies vor Backends (`TODO.md` §7.2)
- [ ] `config/groups/`-Lader, falls Gruppen zusätzlich als Dateien gepflegt werden sollen (`TODO.md` §3.1)
- [ ] Verhalten bei Redis-Ausfall im Betrieb dokumentieren (Services laufen weiter, Zustand veraltet, Events gehen verloren) und im Runbook abbilden

### 2.8 Kompatibilität & Migration

- [ ] Proto-Versionierung: Major/Minor-Politik und Ablehnungsverhalten im Handshake konkretisieren (`TODO.md` §16 offen)
- [ ] Gemischte Versionen Cloud ↔ Bridge: Was passiert beim Rolling Upgrade, wenn alte Bridges auf neue Cloud treffen? Update-Weg für Bridges in laufenden Services
- [ ] MongoDB-Schema-Versionierung: derzeit bewusst keine — Entscheidung dokumentieren und einen Migrationsweg für spätere Feldänderungen skizzieren
- [ ] Crash-Restart überlebt Cloud-Neustart? (hängt an §2.7 Adoption)

---

## 3. P2 — Reife & Härtung

### 3.1 Tests

- [ ] Testcontainers (Redis, Mongo) als Standard für Integrationspfade statt lokaler Server — macht `BYTESTORM_TEST_REDIS`-Opt-ins überflüssig (`TODO.md` §15)
- [ ] ArchUnit-Check: `api` bleibt frei von Redisson/Mongo und Jackson (`TODO.md` §2.2 offen)
- [ ] Coverage-Ziel definieren (nicht „100 %", sondern kritische Pfade: Lifecycle, Crash, Scaling, Auth)
- [ ] E2E mit einem echten Minecraft-Client (Login über den Proxy auf ein Backend) — bisher nur serverseitig geprüft
- [ ] Last-/Soak-Test: 100 Services, 24–72 h, Speicher/Handles/Redis-Verbindungen beobachten
- [ ] Chaos-Tests: Redis weg, Mongo weg, Netz-Blip, Prozess-Kill, Platte voll — erwartetes Verhalten festhalten
- [ ] Security-Tests: Auth-Bypass, Command-Injection über Gruppennamen/Befehle, Path-Traversal bei Templates

### 3.2 Dokumentation

- [ ] Fehlercode-/Log-Katalog (was bedeutet welche Meldung, was tut man)
- [ ] Runbooks: Crash-Sturm, `DEGRADED`, Redis/Mongo-Ausfall, Update fehlgeschlagen, Port-Erschöpfung
- [ ] Adapter-Matrix, Docker-Doku, REST-API (OpenAPI), Discord-Doku
- [ ] `proto/README.md` ist vorhanden — Änderungsregeln auch im Betriebshandbuch verlinken

### 3.3 Rechtliches & Datenschutz

- [ ] `LICENSE` + `NOTICE` (siehe §1.1)
- [ ] Mojang-/Paper-EULA-Hinweis: Die Cloud lädt Plattform-Jars von Drittanbietern und startet sie — Weitergabe und Zustimmung klar trennen
- [ ] Datenschutz: Spieler-UUIDs und Namen liegen in Mongo (Historie, Spielzeit, letzter Service) → Löschkonzept, Auskunftsweg, Speicherdauer, Verantwortlichkeiten
- [ ] Namens-/Markenrechte „ByteStorm" prüfen, bevor öffentlich released wird

### 3.4 Performance & Skalierung

- [ ] Zahlen aus der Lastmessung (§1.5) in Sizing-Empfehlungen übersetzen
- [ ] Redis-Last durch Heartbeats/Log-Batching bei vielen Services optimieren, falls die Messung es verlangt
- [ ] Prozess-Startzeit (Template-Kopie, Plattform-Download, JVM-Start) messen und ggf. cachen

### 3.5 Plattformübergreifend

- [ ] Windows-Betrieb prüfen: Skripte/`bash`-Annahmen, Pfad-Handling, Prozess-Signale, `gradlew.bat` im Release
- [ ] ARM64 (Hosts und Container-Images) testen, falls Zielumgebung

---

## 4. Offene Entscheidungen

- [!] **JSON-Mapping:** Jackson ist überall im Einsatz, `TODO.md` §16 führt die Entscheidung noch als offen — festhalten und den Punkt schließen
- [!] **Proto-Versionierung:** Major/Minor-Politik + Ablehnungsverhalten im Handshake (§2.8)
- [!] **Zeitzone für Zeitplan-Scaling:** Host-TZ vs. UTC vs. konfigurierbar — heute Systemzone mit optionaler `timezone` je Regel, aber ohne dokumentierte Vorgabe
- [!] **JDK 26 vs. LTS:** Toolchain ist 26; für Produktion braucht es eine bewusste Entscheidung samt Support-Zeitraum
- [!] **REST-Auth:** statischer Key (heute) vs. JWT mit Rollen — abhängig davon, ob die API nur intern oder auch für Kunden/Tools erreichbar sein soll
- [!] **Single-Node vs. HA:** Ist der Ausfall der Cloud bei weiterlaufenden Services akzeptabel? Wenn ja: Adoption (§2.7) ist Pflicht, Multi-Node kann entfallen
- [!] **EULA-Bestätigung:** im Wizard abfragen oder als dokumentierter manueller Schritt
- [!] **Docker-Ressourcenlimits:** bewusst weggelassen — Entscheidung bestätigen und das Risiko im Handbuch nennen

---

## 5. Go-Live-Checkliste

Diese Liste ist die Abnahme: erst wenn jeder Punkt abgehakt ist, ist die Instanz „production ready".

- [ ] Frischer Checkout → `./gradlew build` grün, und dieselbe Prüfung läuft in der CI auf einem PR
- [ ] Version ohne `SNAPSHOT`, Tag gesetzt, Changelog geschrieben, Zip mit veröffentlichter Prüfsumme
- [ ] Auf einer frischen VM: JDK installiert, Cloud startet, Wizard läuft durch, Proxy + Lobby kommen `ONLINE`
- [ ] Ein echter Minecraft-Client loggt sich ein und wird auf ein Backend weitergeleitet
- [ ] Redis und Mongo sind nur im privaten Netz erreichbar, Auth/Passwörter gesetzt, Firewall geprüft
- [ ] REST ist nur intern erreichbar (oder hinter TLS-Proxy), Key ≥ 32 Zeichen, Rotation dokumentiert
- [ ] Backup-Jobs laufen, ein Restore wurde einmal vollständig durchgeführt
- [ ] Monitoring liefert Daten, ein Testalarm kommt an
- [ ] Retention für Logs und Crash-Reports ist aktiv, Plattenwarnung getestet
- [ ] Crash-Test: Service killen → Restart mit Backoff, Crash-Report, Event; Cloud killen → Reconciliation ohne Doppelstarts
- [ ] Scaling-Test: Spielerzahl hoch → Service startet, Leerlauf → Service stoppt, `DEGRADED` bleibt aus
- [ ] Update eingespielt und zurückgerollt (`update/backup/`) — beides einmal geübt
- [ ] Betriebshandbuch übergeben: Runbooks, Alarme, Ansprechpartner, Wartungsfenster

---

## 6. Bekannte Risiken (Stand heute)

| Risiko | Auswirkung | Gegenmaßnahme |
|---|---|---|
| Übernommene Prozesse bleiben stumm | Nach einem Cloud-Absturz laufen Services weiter, haben aber bis zum Neustart keine Konsole und schreiben keine neuen Logzeilen | §2.7 — Stop, Heartbeat und Crash-Pfad funktionieren |
| JDK 26 (kein LTS) | Kein langfristiger Support/Updates, Plattform-Jars fordern eigene Java-Versionen | §1.6, §4 |
| Kein TLS für Redis | Vollzugriff auf Live-State, wenn das Netz offen ist — Passwort ist Pflicht, die Transportverschlüsselung fehlt | §1.3 |
| REST-Key ohne Ablauf, kein TLS im Modul | Ein geleakter Key gilt bis zum Entfernen; Transport nur über Reverse-Proxy sicher | §1.3 |
| Keine Docker-Ressourcenlimits | Ein einzelner Service kann den Host lahmlegen | §2.3 |
| Keine Metriken/Alarme | Ausfälle werden erst bemerkt, wenn Spieler sich beschweren | §1.5 |
| Keine Plattenwarnung | Welten und Logs füllen die Platte, bemerkt wird es erst am Schreibfehler | §1.4 |
| EULA nur nach Zustimmung | Ohne Wizard-Zustimmung startet kein Paper-Backend; `doctor` nennt den Weg | §1.2 |
| Nur `STABLE`-Plattform-Downloads, kein Netz-Proxy | Update/Erststart scheitert in eingeschränkten Netzen | §1.2 |
| Keine Mongo-Schema-Versionierung | Spätere Feldänderungen brauchen Handarbeit | §2.8 |
| Single-Node | Cloud-Ausfall = keine Verwaltung, kein Scaling, keine neuen Starts | §1.6, §4 |

---

## 7. Was schon trägt (nicht doppelt bauen)

Damit niemand bereits Gelöstes erneut einplant:

- Geordnetes Herunterfahren mit Schritt-Timeouts, idempotent (`ShutdownCoordinator`)
- Durchgehende Crash-Kette: Erkennung, Bericht, Event, Backoff-Neustart, `DEGRADED`, Online-Reset
- Reconciliation beim Start: verwaiste Registry-Einträge, Port-Reservierungen, Namenszähler
- Adoption laufender Prozesse: PID-Eintrag je Service, Übernahme unter der alten ID, Aufräumen toter Einträge, Beenden von Prozessen ohne Gruppe
- Port-Vergabe ausschließlich über Redis-Lock + `ServerSocket`-Probe, Freigabe bei Stop **und** Crash
- Secrets: Redaktion in `toString()` von Mongo/Redis/Bridge, `0600` auf `config/cloud.json`, Key-Vergleich in konstanter Zeit
- Healthcheck-Fassade mit parallelen Prüfungen und gemeinsamer Frist, nach außen als `/health`, `/nodes/status` und `/metrics`
- REST-Scopes (sieben, Familien-Wildcards, `*`) mit Rate-Limit je Key; Audit über `module list|info|access`
- `doctor` prüft Pfade, Java, Ports, Adressen, Forwarding, Datenbanken, REST-Erreichbarkeit und EULA
- `backup`/`backup list` samt Restore-Runbook; EULA-Zustimmung im Wizard und über `eula status|accept|revoke`
- SHA-256-geprüfte Downloads mit atomarem Schreiben und Offline-Anleitung
- Standard-Templates, die Velocity Modern Forwarding, `online-mode=false` und Ports korrekt setzen
- 787 grüne Tests, davon 32 opt-in gegen echte Server; Distribution-Zip mit `Main-Class` und Release-Action
