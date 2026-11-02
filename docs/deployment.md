# Betriebshandbuch — installieren, absichern, wiederherstellen

Diese Datei beschreibt den Weg von einer frischen VM bis zu einer Cloud, die einen
Neustart und einen Datenverlust übersteht. Sie ersetzt keine Architektur-Doku; sie
nennt die Schritte, die beim Aufsetzen tatsächlich anfallen, und die Prüfungen,
mit denen man sie abhakt.

**Stand:** 2026-11-01 · **Version:** 1.0.0 · Single-Node

---

## 1. Voraussetzungen

| Bestandteil | Anforderung | Prüfen mit |
|---|---|---|
| Java | JDK 26 (Toolchain, kein LTS — siehe „Offene Punkte") | `java -version` |
| MongoDB | 7.x, **mit Benutzer**; Datenbank z. B. `bytestorm` | `mongosh --eval 'db.runCommand({ping:1})'` |
| Redis | 7.x, **mit Passwort** (`requirepass`) | `redis-cli -a … ping` |
| Netz | ausgehend zu Maven Central und `api.papermc.io` (erster Start lädt Jars) | `curl -I https://api.papermc.io` |
| Rechte | eigener Dienstbenutzer, **kein Root-Start** | `id bytestorm` |
| Ports | 25565–29999 für Proxies und Backends, REST nur lokal | Abschnitt 3 |

Die Cloud verweigert den Start ohne Anmeldung an MongoDB und Redis, solange
`security.allowInsecure*` nicht ausdrücklich gesetzt ist. Für die Einrichtung
eigener Datenbankbenutzer siehe [`security.md`](./security.md).

---

## 2. Installieren

### 2.1 Zip entpacken

```bash
useradd --system --home /srv/bytestorm --shell /usr/sbin/nologin bytestorm
install -d -o bytestorm -g bytestorm /srv/bytestorm
unzip bytestorm-1.0.0.zip -d /srv/bytestorm
chown -R bytestorm:bytestorm /srv/bytestorm
```

Das Zip enthält `bytestorm.jar`, `lib/`, `plugins/` (Proxy-Plugin zum Verteilen)
sowie `LICENSE` und `NOTICE`. `lib/dependencies.txt` listet die Fremd-Jars mit
SHA-256; der Launcher lädt fehlende beim ersten Start nach und prüft sie.

### 2.2 Erster Start (Wizard)

```bash
sudo -u bytestorm /usr/lib/jvm/temurin-26-jdk/bin/java -jar /srv/bytestorm/bytestorm.jar
```

Der Wizard fragt MongoDB (Benutzer ist Pflicht), Redis (Passwort ist Pflicht),
Runtime, Ports, die **Minecraft-EULA** und legt Gruppen sowie optional das
REST-Modul an. Erst nach erfolgreichen Verbindungstests schreibt er
`config/cloud.json` atomar mit `0600`.

Wer die EULA nicht im Wizard bestätigt, bekommt kein startendes Paper-Backend.
Nachträglich: `eula accept` (Konsole) oder `eula status` zur Kontrolle.

### 2.3 systemd-Unit

```ini
# /etc/systemd/system/bytestorm.service
[Unit]
Description=ByteStorm Cloud
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=bytestorm
Group=bytestorm
WorkingDirectory=/srv/bytestorm
ExecStart=/usr/lib/jvm/temurin-26-jdk/bin/java -XX:MaxRAMPercentage=75 -jar bytestorm.jar
Restart=on-failure
RestartSec=10
# Viele Services, viele offene Dateien, viele Ports.
LimitNOFILE=65536
# Sauber herunterfahren: die Cloud gibt Ports frei und stoppt Services geordnet.
TimeoutStopSec=120
# Secrets liegen in Dateien, nicht in der Unit.
NoNewPrivileges=true
PrivateTmp=true

[Install]
WantedBy=multi-user.target
```

```bash
systemctl daemon-reload
systemctl enable --now bytestorm
journalctl -u bytestorm -f          # Startmeldungen und Fehler
```

`WorkingDirectory` muss das Cloud-Verzeichnis sein — die Cloud löst `config/`,
`static/` und `logs/` relativ dazu auf.

### 2.4 Container-Variante

Ein fertiges Image gibt es noch nicht; die Cloud läuft als gewöhnlicher Prozess
mit Schreibzugriff auf das Cloud-Verzeichnis. Wer sie containerisieren will,
braucht mindestens:

```yaml
# docker-compose.yml (Auszug)
services:
  cloud:
    image: eclipse-temurin:26-jre
    working_dir: /srv/bytestorm
    volumes:
      - ./cloud:/srv/bytestorm
    command: ["java", "-jar", "bytestorm.jar"]
    restart: unless-stopped
    network_mode: host          # Ports 25565–29999 müssen direkt erreichbar sein
```

`network_mode: host` ist hier kein Bequemlichkeitsgriff: Backend-Ports werden
dynamisch vergeben, ein statisches `ports:`-Mapping würde sie nicht abdecken.
Für den Docker-Runtime der Services braucht der Container zusätzlich Zugriff auf
`/var/run/docker.sock` — das ist gleichbedeutend mit Root auf dem Host und gehört
nur auf einen Host, der ausschließlich die Cloud betreibt.

---

## 3. Firewall-Matrix

| Port | Dienst | Von außen erreichbar | Begründung |
|---|---|---|---|
| 25565–29999 | Proxies und Backends | **ja** | Spieler verbinden sich direkt auf den Proxy; Backend-Ports vergibt die Cloud aus demselben Bereich |
| 8080 (REST) | REST-Modul | **nein** | Standard-Bind `127.0.0.1`; von außen nur über Reverse-Proxy mit TLS |
| 27017 | MongoDB | **nein** | nur die Cloud, höchstens privates Netzsegment |
| 6379 | Redis | **nein** | wie MongoDB; TLS gibt es hier nicht, die Netzgrenze ist die Absicherung |
| 22 | SSH | eingeschränkt | nur Administrationsnetz, Schlüssel statt Passwort |

````bash
# Beispiel mit ufw
ufw default deny incoming
ufw allow 22/tcp
ufw allow 25565:29999/tcp
ufw enable
````

Nach jeder Änderung: `ss -tlnp` prüfen, ob MongoDB, Redis und REST tatsächlich
nur auf `127.0.0.1` oder im privaten Netz lauschen.

---

## 4. Prüfen, dass es läuft

```bash
# In der Cloud-Konsole:
doctor                 # Pfade, Java, Ports, Adressen, Forwarding, Datenbanken, REST, EULA
service list           # Proxy und Backend müssen ONLINE sein
```

`doctor` ist die Abnahme: Jeder `✖`-Befund muss vor dem Livebetrieb verschwinden,
jedes `!` ist eine bewusste Entscheidung. `doctor --fix` wendet Templates erneut an
und startet laufende Services dafür neu.

Von außen, wenn das REST-Modul aktiv ist:

```bash
KEY=…                                     # monitoring.read genügt
curl -s -H "Authorization: Bearer $KEY" http://127.0.0.1:8080/health
curl -s -H "Authorization: Bearer $KEY" http://127.0.0.1:8080/metrics
```

---

## 5. Sicherung und ein echter Restore-Test

Die Sicherung selbst ist in [`backup.md`](./backup.md) beschrieben. Ein Backup,
das nie zurückgespielt wurde, ist eine Annahme — deshalb gehört der folgende
Durchlauf einmal auf eine Test-VM, nicht in den Livebetrieb.

### 5.1 Sichern

```bash
service list                  # vorher notieren, was laufen soll
backup                        # Zip unter backups/ ablegen
backup list                   # Zeitstempel und Größe prüfen
```

Das Zip enthält MongoDB (per `mongodump`), `config/`, `templates/` und `static/`.
Zusätzlich das Zip auf ein anderes System kopieren (`scp`, Objektspeicher) — eine
Sicherung auf derselben Platte schützt gegen nichts.

### 5.2 Restore-Übung (Reihenfolge ist Teil des Tests)

1. **Cloud stoppen** (`shutdown` oder `systemctl stop bytestorm`) — sonst schreibt
   die Cloud während des Zurückspielens weiter.
2. **MongoDB zurückspielen**: Ziel-Datenbank leeren, dann `mongorestore` aus dem
   Zip-Inhalt; die Collections heißen `groups`, `templates`, `players`,
   `scaling_rules`, `modules_config`.
3. **Redis**: Live-State ist flüchtig. Nach dem Start räumt die Reconciliation
   verwaiste Einträge auf und vergibt Ports neu; laufende Services werden **nicht**
   wiederhergestellt, sie werden neu gestartet.
4. **Dateien** aus dem Zip nach `config/`, `templates/` und `static/` zurücklegen
   (Rechte `0600` für `cloud.json` prüfen).
5. **Cloud starten**, dann `doctor` und `service list` — Proxy und Lobby müssen
   `ONLINE` werden.
6. **Stichprobe**: ein Spieler verbindet sich, ein Gruppenwechsel funktioniert.
7. Erst danach die Übung als bestanden notieren (Datum, Wiederherstellungsdauer).

### 5.3 Was nicht im Backup steckt

- Laufende Welten im Arbeitsspeicher: `static/` wird als Momentaufnahme gesichert.
  Wer im Betrieb zurückspielt, verliert die Änderungen seit der Sicherung.
- `logs/`, `crash-reports/`, `backups/`: Diagnose, keine Daten.
- Der Updater legt ersetzte Dateien unter `update/backup/` ab; das ist kein
  Ersatz für eine Sicherung.

---

## 6. Update und Rückweg

```bash
updater check
updater update        # ersetzt bytestorm.jar, lib/ und plugins/*.jar,
                      # legt Altes unter update/backup/ ab
shutdown              # danach neu starten
```

`plugins/` enthält die Server-Plugins aus dem Release (`bytestorm-proxy.jar`,
`bytestorm-lobby.jar`). Sie laufen nicht in der Cloud: nach dem Update die
gewünschte Jar einmal nach `templates/<Gruppe>/plugins/` kopieren, damit neue
Services sie erben — ein bereits laufender Service bekommt sie erst beim
nächsten Start.

Vor jedem Update: `backup`. Für den Rückweg `update/backup/` zurückspielen und die
Cloud neu starten; die Version steht in `gradle.properties` und im Manifest.

---

## 7. Alarme

Die Cloud prüft alle 30 Sekunden und meldet jeden Alarm genau einmal: erst beim
Auftreten, dann beim Abklingen. Bleibt die Lage gleich, entsteht keine neue
Zeile. Ziele sind das Cloud-Log (Konsole und `logs/cloud.log`) und die
Ereignisspur `logs/alerts.log`:

```text
2026-11-01T15:42:10.113Z ALARM CRITICAL [group:Proxy] Gruppe 'Proxy' ist DEGRADED — …
2026-11-01T15:47:10.201Z BEHOBEN CRITICAL [group:Proxy] Gruppe 'Proxy' ist DEGRADED — …
```

| Quelle | Stufe | Bedingung |
|---|---|---|
| `health:mongo`, `health:redis`, `health:runtime` | CRITICAL | Bestandteil antwortet nicht (Frist 5 s) |
| `group:<name>` | CRITICAL | Gruppe steht auf `DEGRADED`; der Text nennt `service start <name>` |
| `service:<name>` | WARNING | Service steht auf `CRASHED` und wartet auf den nächsten Versuch |
| `disk` | CRITICAL | unter 5 GiB frei auf dem Cloud-Dateisystem |

`logs/alerts.log` wird bewusst nicht rotiert — Alarme sollen den Vorfall
überdauern, und ein Ereignis pro Zeile lässt sich ohne Parser einsammeln. Wer
Alarme nach außen tragen will, hängt einen Shipper an diese Datei; ein Modul für
Discord/Webhook gibt es noch nicht. `doctor` bleibt die ausführliche
Momentaufnahme, diese Prüfung ist die laufende Überwachung.

### 7.1 Prometheus-Regeln

`deploy/prometheus/bytestorm.rules.yml` deckt die Fälle ab, die `alerts.log`
grundsätzlich nicht sehen kann: Stirbt die Cloud, schreibt niemand mehr in die
Datei; Summen und Vorstufen stehen dort nicht.

```yaml
# prometheus.yml
scrape_configs:
  - job_name: bytestorm
    metrics_path: /metrics
    authorization:
      type: Bearer
      credentials_file: /etc/prometheus/bytestorm.key
    static_configs:
      - targets: ["127.0.0.1:7070"]   # Port aus config/modules/rest.json

rule_files:
  - /etc/prometheus/bytestorm.rules.yml
```

Der Key braucht nur den Scope `monitoring.read` (`module info rest` zeigt ihn).
Läuft Prometheus auf einem anderen Host, muss die REST-API dafür erreichbar sein
— dann über Reverse-Proxy mit TLS, nicht durch Aufbohren des Bind.

| Alarm | Metrik | Was `alerts.log` nicht sieht |
|---|---|---|
| `ByteStormMetricsMissing` | `up{job="bytestorm"}` | die Cloud ist tot, es schreibt niemand mehr |
| `ByteStormUnhealthy` | `bytestorm_up` | wiederholt sich, bis jemand reagiert |
| `ByteStormNoServiceOnline` | `absent(bytestorm_services\{state="ONLINE"\})` | einzelne Abstürze, nicht die Summe |
| `ByteStormServiceStuckStarting` | `bytestorm_services\{state=~"PREPARING\|STARTING"\}` | Start-Timeout wird nicht gemeldet |
| `ByteStormCrashBudgetLow` | `bytestorm_crash_budget_remaining` | warnt erst nach dem letzten Versuch (`DEGRADED`) |
| `ByteStormMaintenanceLeftOn` | `bytestorm_groups_maintenance` | Wartung ist kein Fehlerzustand |

Die Regeln sind absichtlich zweite Meinung, nicht Ersatz: Die Cloud meldet jeden
Zustand selbst, Prometheus wiederholt ihn und fängt den Ausfall der Cloud ab.

---

## 8. Wenn etwas nicht startet

| Symptom | Ursache | Weg |
|---|---|---|
| Backend startet und stirbt sofort, `DEGRADED` | `eula.txt` fehlt oder steht auf `false` | `eula accept`, dann `service restart <name>` |
| „Kein freier Port in …" | Pool erschöpft oder Lease hängen geblieben | `service list`, `doctor`; Reconciliation beim Neustart gibt Leases frei |
| Proxy `ONLINE`, Spieler kommt nicht auf ein Backend | Forwarding-Secret oder `paper-global.yml` falsch | `doctor` (Bereich `Forwarding`), `doctor --fix` |
| `401`/`403` vom REST-Modul | Key fehlt oder Scope reicht nicht | `module info rest` zeigt Scope und Endpunkte |
| MongoDB/Redis „ohne Anmeldung" | Ausnahme gesetzt oder Passwort entfernt | `doctor`, dann `security.allowInsecure*` prüfen |

---

## 9. Offene Punkte dieses Handbuchs

Ehrlich benannt, weil sie den Betrieb betreffen:

- **Kein fertiges Container-Image** und kein Compose-Beispiel mit Datenbanken.
- **Alarme bleiben lokal**: `DEGRADED`, Ausfälle, Abstürze und knapper Speicher
  landen in `logs/alerts.log`; Versand nach außen (Discord, Webhook, E-Mail)
  fehlt. Ein Dienst, der nur die Datei liest, muss selbst gebaut werden.
- **Keine Fehlerrate im Export**: `/metrics` liefert Zustände, aber keine
  Anfrage- oder Fehlerzahlen. `5xx`-Rate und Heartbeat-Alter lassen sich deshalb
  auch mit den Prometheus-Regeln nicht alarmieren.
- **Keine Plattenwarnung für Welten**: Service-Logs und Crash-Berichte sind
  begrenzt, der Speicherstand wird nur global geprüft, nicht je Welt.
- **Single-Node**: Fällt die Cloud aus, laufen die Services weiter, werden aber
  erst nach einem Neustart wieder übernommen (Konsole und Logzeilen fehlen
  bis dahin).
- **JDK 26 ist kein LTS**: Support-Zeitraum und Bezugsweg sind nicht entschieden.
