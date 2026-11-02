<p align="center">
  <img src="assets/logo.png" alt="ByteStorm" width="360">
</p>

<h1 align="center">⚡ ByteStorm</h1>

<p align="center">
  <b>Minecraft-Cloud für Proxy und Backends</b><br>
  starten · überwachen · skalieren · neu starten
</p>

<p align="center">
  <a href="https://github.com/bytestorm-dev/ByteStorm/actions/workflows/ci.yml"><img src="https://github.com/bytestorm-dev/ByteStorm/actions/workflows/ci.yml/badge.svg?branch=master" alt="CI"></a>
  <img src="https://img.shields.io/badge/Java-26-orange" alt="Java 26">
  <img src="https://img.shields.io/badge/Lizenz-Apache--2.0-blue" alt="Apache-2.0">
  <img src="https://img.shields.io/badge/Version-1.1.0-informational" alt="Version 1.1.0">
</p>

> **Ein Host, ein Prozess, ein Netz.** ByteStorm nimmt Spieler an einem Proxy an,
> startet Paper-Backends aus Gruppen, überwacht sie und ersetzt sie, wenn sie sterben.

ByteStorm betreibt ein Minecraft-Netz auf einem Host: Ein Proxy (Velocity, BungeeCord)
nimmt Spieler an, Paper-Backends werden aus Gruppen heraus gestartet, überwacht und bei
Bedarf ersetzt. Die Cloud ist ein einzelner Java-Prozess mit interaktiver Konsole, einer
REST-API und einer Bridge, die als Plugin auf jedem Service mitläuft.

Der Einstieg ist ein Erststart-Wizard: Datenbanken prüfen, Gruppen anlegen, EULA
entscheiden — danach läuft das Netz. 🧙

---

## ✨ Auf einen Blick

| | |
|---|---|
| 🎯 **Ziel** | ein lauffähiges Proxy-/Backend-Netz auf einem Host |
| 🧱 **Betrieb** | ein Java-Prozess, Konsole + REST-API, Bridge als Plugin |
| 🗄️ **Zustand** | Gruppen und Module in MongoDB, Live-Zustand in Redis |
| 📦 **Auslieferung** | ein Zip, `java -jar bytestorm.jar`, fertig |

---

## 🚀 Funktionen

- 🗂️ **Gruppen statt Einzelserver**: statische und dynamische Gruppen mit Minimum/Maximum,
  Regeln für Spielerzahl, Zeitplan (Cron) und Host-Last, Cooldown und Start-Backoff.
  Eine Gruppe, die ihre Neustarts aufgebraucht hat, wird `DEGRADED` und startet nur noch
  auf Zuruf — statt endlos neue Services nachzuschieben.
- 🔐 **Weiterleitung, die geprüft wird**: Velocity Modern Forwarding mit gemeinsamem Secret,
  `paper-global.yml` je Backend. `doctor` erkennt falsche Einstellungen, `doctor --fix`
  setzt Templates und Secret neu — auch für laufende Services.
- 📦 **Prozess oder Container**: `PROCESS` startet Java direkt, `DOCKER` im Container. Jeder
  Service bekommt eine PID-Datei; nach einem Cloud-Neustart werden laufende Prozesse
  übernommen oder aufgeräumt, verwaiste Registry-Einträge entfernt.
- 🔌 **Ports ohne Kollision**: Proxies 25565–29999, Backends ab 30000, Leases in Redis. Ein
  gestoppter Service gibt seinen Port sofort frei.
- 🖥️ **Live-Betrieb**: `service screen` zeigt Logs und schickt Befehle, `watch` die
  Übersicht, `status` Services/Gruppen/Host-Last — alle Zeilen im Format
  `16:42:01|INFO`. Logs und Crash-Berichte werden rotiert und begrenzt.
- 🚨 **Alarme**: Die Cloud prüft alle 30 Sekunden Datenbanken, Runtime, `DEGRADED`-Gruppen,
  Abstürze und freien Speicher und schreibt jeden Alarm einmal nach `logs/alerts.log`;
  `alerts` zeigt die aktiven. Wer Prometheus betreibt, bekommt Regeln für die Fälle, die
  eine Datei nicht sehen kann (Cloud tot, kein `ONLINE`-Service, hängender Start, knappes
  Crash-Budget).
- 🛡️ **Sicher per Vorgabe**: MongoDB und Redis verlangen Anmeldung, die REST-API lauscht
  standardmäßig auf `127.0.0.1`, Keys haben Scopes und Rate-Limits, das Redis-Passwort
  liegt nicht in der Prozessumgebung der Services.
- 🧰 **Betriebswerkzeuge**: `backup` sichert Mongo, `config/`, `templates/` und `static/` in
  ein Zip, `updater` prüft und lädt GitHub-Releases, `doctor` prüft Pfade, Ports, Java,
  Datenbanken, Adressen, Forwarding, EULA und REST-Erreichbarkeit.
- 🧩 **Erweiterbar**: Module (REST, Discord) werden per ServiceLoader aus `lib/` geladen und
  über die öffentliche API angebunden; die Paper-Bridge bringt PlaceholderAPI-Werte für
  Spielerzahlen und Servicezustände mit.

---

## ⚡ Schnellstart

### 🧰 Voraussetzungen

| Bestandteil | Anforderung |
|---|---|
| ☕ Java | JDK 26 (beim Bauen lädt Gradle die Toolchain nach) |
| 🍃 MongoDB | 7.x, **mit Benutzer**; eine Datenbank für die Cloud |
| ⚡ Redis | 7.x, **mit Passwort** |
| 🌐 Netz | ausgehend zu Maven Central und `api.papermc.io` — der erste Start lädt Plattform-Jars |
| 🔌 Ports | 25565–29999 für Proxies und Backends; REST nur lokal |

### 🏁 Erster Start

```bash
unzip bytestorm-1.1.0.zip -d /srv/bytestorm
cd /srv/bytestorm
java -jar bytestorm.jar
```

Der Start ohne `config/cloud.json` öffnet den Wizard: MongoDB und Redis inklusive
Verbindungstest, Runtime, Ports, Minecraft-EULA und die Gruppen (Proxy und Backends) in
einem Durchgang. Erst nach den Tests wird die Konfiguration mit Rechten `0600` geschrieben.

### 🖥️ Die Konsole

Beim Start erscheint das Logo aus `assets/banner.txt` — dunkles Aqua mit dunklerem Schatten,
daneben schlägt ein Blitz ein. Ersetz die Datei, wenn dir ein anderes Logo lieber ist; ohne
Terminal entfällt der Vorspann. Danach folgen die Startzeilen im Format `16:42:01|INFO` und
der Prompt mit Name und Version:

```text
$ java -jar bytestorm.jar
16:42:01|INFO  ByteStorm startet in /srv/bytestorm (Log: /srv/bytestorm/logs/cloud.log)
16:42:01|INFO  Verzeichnislayout geprüft (bytestorm)
16:42:02|INFO  MongoDB verbunden: MongoSettings[uri=mongodb://cloud:***@127.0.0.1:27017/?authSource=admin, database=bytestorm]
16:42:02|INFO  Redis verbunden: RedisSettings[address=redis://127.0.0.1:6379, password=***]
16:42:03|INFO  Node-ServiceManager bereit (Runtime PROCESS, Logs /srv/bytestorm/logs, Scaling 3 s)
16:42:03|INFO  ByteStorm bereit — Strg+C beendet die Cloud
bytestorm@1.1.0» status
```

Jede Zeile trägt Uhrzeit und Level (`16:42:01|INFO`, `|WARN`, `|ERROR` — farbig am
Terminal), den Logger-Namen nur in `logs/cloud.log`. Der Prompt ist `bytestorm@1.1.0» `;
außerhalb eines Release-Jars steht dort `bytestorm@dev» `.

### ⌨️ Die wichtigsten Kommandos

```text
status                     Übersicht über Services und Gruppen
group list                 angelegte Gruppen
service list               laufende Services mit Port und Spielern
doctor                     vollständige Diagnose
alerts                     aktive Alarme
```

🗒️ Ohne Terminal startet die Cloud ohne Konsole und läuft bis `SIGTERM`; alle Kommandos
gibt es auch als Einzelaufruf über die REST-API. Der systemd-Dienst, die Firewall-Matrix und
eine Restore-Übung stehen in [`docs/deployment.md`](docs/deployment.md).

---

## 🧩 Kommandos

| Bereich | Kommandos |
|---|---|
| 🧱 Services | `service list\|info\|start\|stop\|restart\|kill\|reapply\|exec\|screen\|logs` |
| 🗂️ Gruppen | `group list\|info\|create\|edit\|delete\|scale`, `maintenance <name> on\|off` |
| 📁 Templates | `templates list\|create\|delete\|copy\|sync` |
| 👤 Spieler | `player list\|info\|connect\|kick` |
| 🧩 Module | `module list\|info\|access\|settings\|set\|enable\|disable` |
| 🛠️ Betrieb | `status`, `watch`, `doctor [--fix]`, `backup`, `eula`, `alerts`, `reload`, `info` |
| 📥 Plattformen | `platform list\|versions\|install\|update` |
| ⬆️ Updates | `updater versions\|check\|update` |

`help` zeigt alles in der Konsole, jedes Kommando kennt `help` (z. B. `group help`).

---

## 🏗️ Aufbau

| Modul | Aufgabe |
|---|---|
| `proto` | Protobuf-Schemas und Versionskonstante — nur additive Änderungen |
| `api` | öffentliche Fassade (`ByteStormApi`), die Module und Bridges sehen |
| `codec` | Serialisierung (Protobuf, Kryo-Fallback) ohne Mongo- oder Jackson-Ballast |
| `core` | Konfiguration, Mongo/Redis, Event-Bus, RPC, Ports, Healthcheck, Logging, Alarme |
| `controller` | Service-, Gruppen-, Spieler- und Template-Fassade für CLI und REST |
| `node` | Lifecycle, Runtimes (Prozess/Docker), Templates, Crash-Kette, Bridge-Registrar |
| `cli` | Kommandos, Tabellen, Dashboard und die JLine-Konsole |
| `launcher` | Bootstrap, Erststart-Wizard, Downloader, Doctor, Backup, Distribution |
| `bridge:bridge-*` | Plugin für Paper, Velocity und BungeeCord (Heartbeats, Befehle, Placeholder) |
| `modules:module-*` | optionale Module, u. a. REST mit `/health`, `/nodes/status`, `/metrics` |
| `plugins:proxy-plugin` | Proxy-Plugin für Spielerkommandos und Cloud-Befehle |

Ein Start in Kurzform:

```text
CLI / REST ─▶ Controller ─▶ ServiceProvisioner (Name, Port, Template, Plattform-Jar)
             ─▶ Runtime ─▶ Bridge meldet sich an ─▶ Service ist ONLINE
```

Zustände liegen in Redis, Gruppen und Module in MongoDB. Die Cloud ist die einzige Instanz,
die Prozesse startet; die Reconciliation räumt beim Start auf, was ein Absturz hinterlassen hat.

---

## 📚 Dokumentation

| Datei | Inhalt |
|---|---|
| 📘 [`docs/deployment.md`](docs/deployment.md) | Installation, systemd, Firewall, Alarme und Prometheus-Regeln, Restore-Übung |
| 💾 [`docs/backup.md`](docs/backup.md) | Was `backup` sichert, wie man zurückrollt |
| 🔒 [`docs/security.md`](docs/security.md) | Benutzer für MongoDB/Redis, Scopes, was die Cloud verweigert |
| 🧮 [`docs/placeholders.md`](docs/placeholders.md) | PlaceholderAPI-Werte der Paper-Bridge |
| 🤖 [`docs/ci.md`](docs/ci.md) | CI- und Release-Workflows, Umgang mit fehlgeschlagenen Läufen |
| 🔔 [`deploy/prometheus/bytestorm.rules.yml`](deploy/prometheus/bytestorm.rules.yml) | Alerting-Regeln für Prometheus |
| 📝 [`CHANGELOG.md`](CHANGELOG.md) | Änderungen je Version |
| ✅ [`prod-ready-todo.md`](prod-ready-todo.md) | was für den unbeaufsichtigten Betrieb noch fehlt |

---

## 🛠️ Selbst bauen

```bash
./gradlew build                       # kompilieren, testen, alle Jars
./gradlew :launcher:distributionZip   # startbares bytestorm-<version>.zip
```

Die Toolchain ist JDK 26; Gradle lädt sie beim ersten Lauf nach. Tests, die echte
MongoDB-, Redis- oder Docker-Instanzen brauchen, sind opt-in und werden ohne Umgebung
übersprungen — `./gradlew build` läuft ohne Dienste grün. Die Version steht in
`gradle.properties`; ein Tag `v<version>` löst den Release aus, jeder Push und PR nur die CI.

---

## ⚠️ Grenzen

Ehrlich benannt, weil sie den Betrieb betreffen: ByteStorm ist **Single-Node** — fällt die
Cloud aus, laufen die Services weiter, werden aber erst nach einem Neustart wieder
übernommen. JDK 26 ist kein LTS. Ein fertiges Container-Image gibt es nicht, nur die Skizze
im Betriebshandbuch. Alarme bleiben lokal (Datei und Log); Discord/Webhook-Versand und
Fehlerraten-Alarme fehlen. Die offene Liste steht in [`prod-ready-todo.md`](prod-ready-todo.md).

---

## 📄 Lizenz

Apache License 2.0 — siehe [`LICENSE`](LICENSE) und [`NOTICE`](NOTICE) für die
mitgelieferten Bibliotheken. Paper, Velocity und BungeeCord werden nicht mitgeliefert,
sondern zur Laufzeit geladen; „Minecraft“ ist eine Marke von Mojang/Microsoft, dieses
Projekt steht in keiner Verbindung dazu.
