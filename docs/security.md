# Zugänge absichern

MongoDB und Redis tragen den gesamten Zustand der Cloud: Gruppen, Templates,
Spieler-Historie, Service-Registry, Ports und Heartbeats. Wer dort schreiben
kann, kann Services starten, stoppen und Befehle ausführen — die Cloud selbst ist
dann keine Grenze mehr. Deshalb startet sie ohne Anmeldung nicht.

## Was die Cloud verlangt

| Feld in `config/cloud.json` | Bedeutung |
|---|---|
| `mongo.uri` | muss einen Benutzer nennen, z. B. `mongodb://bytestorm:…@127.0.0.1:27017/bytestorm?authSource=bytestorm` |
| `redis.password` | Pflicht, sobald Redis Authentifizierung verlangt |
| `security.allowInsecureMongo` | Ausnahme nur für MongoDB |
| `security.allowInsecureRedis` | Ausnahme nur für Redis |
| `security.allowInsecure` | gemeinsame Ausnahme, falls der Einzelwert fehlt |

Fehlt die Anmeldung und keine Ausnahme greift, bricht der Start ab, **bevor**
eine Verbindung geöffnet wird:

```
Ungültige cloud.json:
 - mongo.uri braucht einen Benutzer, z. B. mongodb://user:pass@host:27017 — ohne Anmeldung nur mit security.allowInsecureMongo=true (nicht empfohlen)
 - redis.password ist Pflicht (Redis-Auth, §20) — ohne Passwort nur mit security.allowInsecureRedis=true (nicht empfohlen)
```

Die Ausnahme ist absichtlich unhandlich geschrieben. Sie ist für eine lokale
Testumgebung gedacht, nicht für den Betrieb: Der Start protokolliert jede
Datenbank, die ohne Anmeldung läuft, und `doctor` meldet sie unter `Sicherheit`.

```json
{
  "mongo": { "uri": "mongodb://bytestorm:PASSWORT@127.0.0.1:27017/bytestorm?authSource=bytestorm" },
  "redis": { "address": "redis://127.0.0.1:6379", "password": "PASSWORT" },
  "security": { "allowInsecureMongo": false, "allowInsecureRedis": false }
}
```

## MongoDB: eigener Benutzer

Die Cloud braucht nur Lese- und Schreibrechte auf ihre eigene Datenbank — kein
`root`, kein `admin`.

```
mongosh --host 127.0.0.1:27017 -u <admin> -p --authenticationDatabase admin

use bytestorm
db.createUser({
  user: "bytestorm",
  pwd: "<langes zufälliges Passwort>",
  roles: [ { role: "readWrite", db: "bytestorm" } ]
})
```

- `authSource=bytestorm` in der URI, weil der Benutzer in dieser Datenbank liegt
  (liegt er in `admin`, `authSource=admin` setzen).
- Sonderzeichen im Passwort prozentkodieren (`@` → `%40`, `:` → `%3A`); der
  Erststart-Wizard macht das automatisch.
- Der Benutzer darf **nicht** `dbAdmin`, `clusterAdmin` oder `userAdminAnyDatabase`
  haben: Indizes legt die Cloud im eigenen Namensraum an, mehr braucht sie nicht.
- MongoDB nur an `127.0.0.1` oder ein privates Interface binden (`net.bindIp`),
  `security.authorization: enabled` in `mongod.conf`.

## Redis: Passwort, Bindung, Kommandos

```
requirepass "<langes zufälliges Passwort>"
bind 127.0.0.1 ::1
protected-mode yes

# Was die Cloud nie aufruft, aber ein Eindringling gerne hätte:
rename-command FLUSHALL ""
rename-command FLUSHDB ""
rename-command CONFIG ""
```

- Läuft Redis in einem eigenen Container, ist `bind 0.0.0.0` innerhalb des
  Docker-Netzes üblich — dann darf der Port **nicht** nach außen veröffentlicht
  werden (`ports:` weglassen, nur `expose:`).
- TLS: Die Cloud akzeptiert `rediss://`-Adressen. Der Redis-Port gehört trotzdem
  hinter eine Firewall; TLS ersetzt keine Netzgrenze.
- **Grenze:** Die Cloud meldet sich mit Passwort, aber ohne Benutzernamen an.
  Ein benannter Redis-ACL-Benutzer (`ACL SETUSER bytestorm …`) ist damit noch
  nicht nutzbar; der unterstützte Weg ist der `default`-Benutzer mit
  `requirepass`. Für Rechte je Benutzer müsste die Cloud zusätzlich einen
  Benutzernamen mitsenden — das ist noch nicht umgesetzt.

## Prüfen

`doctor` ordnet beide Datenbanken ein — lokal, privates Netz oder von außen
erreichbar:

```
✔ Sicherheit  MongoDB nur lokal (127.0.0.1) und mit Anmeldung
✔ Sicherheit  Redis im privaten Netz (10.0.0.5) und mit Anmeldung
! Sicherheit  Redis ohne Anmeldung (security.allowInsecureRedis) — nur zulässig, solange der Dienst ausschließlich lokal lauscht
✖ Sicherheit  Redis ohne Anmeldung (security.allowInsecureRedis) auf 10.0.0.5 — von anderen Hosts erreichbar; Anmeldung setzen oder den Port per Firewall sperren
```

Die Einordnung kennt nur die konfigurierte Adresse, nicht die Firewall davor: Ein
einzelner Name wie `redis` gilt als Netznamen, ein Name mit Punkt
(`redis.example.com`) oder eine öffentliche Adresse als von außen erreichbar. Ein
`!` ist ein Hinweis, ein `✖` muss vor dem Livebetrieb verschwinden.

Dieselbe Einordnung gilt für die REST-API: Sie lauscht standardmäßig auf
`127.0.0.1`, und solange sie das tut, ist ein Key mit `*` unkritisch. Ein anderer
Host macht daraus eine Warnung:

```
✔ REST  nur lokal (127.0.0.1:8080) — Key 'apiKey' hat vollen Zugriff (*), von außen aber nicht erreichbar
! REST  0.0.0.0:8080 (alle Adressen) — Key 'apiKey' hat vollen Zugriff (*); host auf 127.0.0.1 beschränken oder scopes.<key> vergeben, sonst nur mit TLS-Proxy davor
```

## Überblick in der CLI

Wer wissen will, welcher Key was darf, fragt die Cloud:

```
bytestorm@1.0.0» module list
Name     Aktiviert  Zustand   Version  Zugriff
rest     ja         ENABLED   1.0.0    2 Keys, 1× voll
discord  nein       DISABLED  1.0.0    —

bytestorm@1.0.0» module info rest
Name: rest
Status: ENABLED
Aktiviert: ja
Version: 1.0.0
Zugriff: apiKey [*]
Zugriff: monitoring [monitoring.read, services.read]
Scopes: je Scope die freigegebenen Endpunkte
  services.read   GET /services, GET /services/logs
  services.write  POST /services/start, POST /services/stop, POST /services/restart, POST /services/command, POST /services/reapply, POST /services/online
  groups.read     GET /groups
  groups.write    POST /groups/maintenance, POST /groups/scale
  players.read    GET /players
  players.write   POST /players/connect, POST /players/kick
  monitoring.read GET /health, GET /nodes/status, GET /metrics
```

Die Scope-Liste in `module info` nennt zu jedem Scope die Endpunkte, die er
freigibt. Sie erscheint nur bei Modulen, die REST-Scopes vergeben — so lässt sich
vor dem Setzen von `scopes.<name>` nachsehen, was ein Key danach darf.

```
bytestorm@1.0.0» module access
Modul    Zugang      Scopes
rest     apiKey      *
rest     monitoring  monitoring.read, services.read
```

`module access` ist die Audit-Ansicht: alle Module untereinander, je Key eine
Zeile. Mit `module access rest` lässt sie sich auf ein Modul eingrenzen.

Die Rechte rechnet das Modul aus seinen Einstellungen aus — dieselbe Auswertung,
die auch jede Anfrage prüft. Ein Key ohne `scopes.<name>` erscheint mit den vier
Lese-Scopes, `*` heißt voller Zugriff, `services.*` deckt eine ganze Familie ab.
Die Werte selbst stehen nie in der Ausgabe.

## Firewall-Matrix

| Dienst | Sichtbar für | Nie erreichbar für |
|---|---|---|
| Proxy (25565–29999) | Spieler | — |
| MongoDB (27017) | die Cloud, höchstens das private Netz | Internet |
| Redis (6379) | die Cloud, höchstens das private Netz | Internet |
| REST-Modul (8080) | Monitoring/Admin, hinter Reverse-Proxy mit TLS | Internet direkt |

Der API-Key des REST-Moduls ist kein Ersatz für eine Netzgrenze: Er reist im
Klartext, solange kein TLS davorsteht.
