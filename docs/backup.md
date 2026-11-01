# Sichern und Wiederherstellen

Der Befehl `backup` legt eine Zip-Datei an, mit der sich eine Cloud vollständig
neu aufsetzen lässt: MongoDB-Inhalt, Konfiguration, Templates und die
persistenten Service-Ordner.

## Was in der Sicherung steckt

| Pfad im Archiv | Inhalt |
|---|---|
| `manifest.json` | Zeitpunkt, Cloud-Verzeichnis, Datenbank, Anzahl je Collection und Datei |
| `mongo/<collection>.json` | je Collection ein JSON-Array im Extended-JSON-Format, für `mongoimport` |
| `cloud/config/` | `cloud.json` mit Forwarding-Secret, MongoDB- und Redis-Zugang, Modul-Konfigurationen |
| `cloud/templates/` | alle Template-Layer (`ALL`, `PROXY`, `SERVER`, `FALLBACK`, Gruppen) |
| `cloud/static/` | die Ordner statischer Services inklusive Welten und Plugins |

**Nicht** enthalten ist, was sich aus dem Rest ergibt oder flüchtig ist:

- `running/` — Arbeitsordner dynamischer Services werden beim Stop verworfen,
  ein Backup davon wäre ein halber Zustand.
- `logs/`, `crash-reports/`, `backups/` — Diagnose, keine Daten.
- `lib/`, `platforms/` — kommen aus der Distribution bzw. dem PaperMC-Download.
- Redis — Live-State (Registry, Ports, Heartbeats) wird beim Start neu
  aufgebaut; nur die Quelle der Wahrheit (Mongo) wird gesichert.

Die Datei enthält Secrets und wird deshalb mit `0600` angelegt.

## Sichern

```
bytestorm · › backup
✔ Sicherung angelegt: /home/cloud/backups/bytestorm-20261101-153000.zip (84,2 MB, 5 Collections, 412 Dokumente, 18311 Dateien)

bytestorm · › backup list
bytestorm-20261101-153000.zip   84,2 MB   01.11.2026 15:30
```

`backup --out /pfad/datei.zip` schreibt an einen anderen Ort, etwa auf ein
gemountetes Backup-Volume. Sichern lässt sich auch bei laufenden Services: Die
Mongo-Collections sind klein und werden in einem Durchgang gelesen; die
Service-Ordner können sich währenddessen ändern (Welten). Für ein garantiert
konsistentes Abbild die Services vorher stoppen.

## Wiederherstellen

Reihenfolge: Cloud stoppen → Dateien zurück → Mongo zurück → Cloud starten →
prüfen. Nur so greift keine laufende Instanz auf halb wiederhergestellte Daten zu.

```bash
# 1. Cloud geordnet stoppen (Services werden mitgestoppt)
systemctl stop bytestorm        # oder Strg+C in der Konsole

# 2. Archiv entpacken
mkdir -p /tmp/restore && unzip /home/cloud/backups/bytestorm-20261101-153000.zip -d /tmp/restore

# 3. Dateien zurückspielen (vorher ein eigenes Backup des aktuellen Stands!)
cp -a /tmp/restore/cloud/config/.    /home/cloud/config/
cp -a /tmp/restore/cloud/templates/. /home/cloud/templates/
cp -a /tmp/restore/cloud/static/.    /home/cloud/static/

# 4. MongoDB zurückspielen — pro Collection mit --drop
MONGO_URI="mongodb://admin:GEHEIM@127.0.0.1:27017/?authSource=admin"
DB="bytestorm"
for file in /tmp/restore/mongo/*.json; do
  mongoimport --uri "$MONGO_URI" --db "$DB" \
    --collection "$(basename "$file" .json)" --jsonArray --drop "$file"
done

# 5. Cloud starten
systemctl start bytestorm
```

Danach in der Konsole prüfen:

```
bytestorm · › doctor          # Pfade, Ports, Java, Mongo/Redis, Forwarding
bytestorm · › status          # Services und Gruppen
```

`doctor --fix` wendet Templates erneut an, falls im Service-Ordner noch
Platzhalter aus einem älteren Stand stehen. Gruppen werden erst gestartet, wenn
die Skalierung sie hochzieht oder `service start <name>` sie anfordert.

## Wiederherstellung auf einem anderen Host

1. Distribution entpacken und `config/cloud.json` aus dem Archiv übernehmen —
   sie enthält das Forwarding-Secret, ohne das laufende Proxies und Backends
   nicht mehr zusammenfinden.
2. Zeigen MongoDB-URI und Redis-Adresse in der Datei auf den alten Host, die
   Werte vor dem Start anpassen (oder per Erststart-Wizard neu schreiben).
3. `static/` nur übernehmen, wenn die Weltdaten gebraucht werden; neue Gruppen
   ziehen frische Ordner aus den Templates.
4. Nach dem Start `doctor` laufen lassen: Ports, Forwarding und Datenbanken
   müssen stimmen, bevor Spieler verbinden.

## Aufbewahrung

Die Cloud räumt `backups/` nicht selbst auf. Bewährte Praxis: täglicher Lauf per
Cron oder systemd-Timer, Kopie auf ein anderes Medium, Aufbewahrung nach
Bedarf (z. B. 7 Tage täglich, 4 Wochen wöchentlich).

```bash
# /etc/cron.daily/bytestorm-backup
#!/bin/sh
printf 'backup\n' | curl -s -X POST http://127.0.0.1:8080/... # nur wenn REST aktiv ist
# oder direkt in der Konsole bzw. über den systemd-Timer mit `backup --out …`
```

Ein Restore wird erst zur Sicherheit, wenn er einmal geübt wurde — am besten
gegen eine Testinstanz mit `--out` auf einem anderen Host.
