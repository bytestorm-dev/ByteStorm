# Platzhalter (PlaceholderAPI)

Die Paper-Bridge bringt eine PlaceholderAPI-Erweiterung mit. Sie meldet sich als
`bytestorm` an, alle Platzhalter heißen also `%bytestorm_<name>%`.

Die Erweiterung ist Teil von `bridge-paper`: Sie wird nur geladen, wenn
PlaceholderAPI installiert ist (`softdepend` in der `plugin.yml`). Ohne
PlaceholderAPI startet die Bridge unverändert. Weil die Bridge auf jedem Service
läuft, ist auf den Backends nichts zusätzlich zu installieren.

## Werte dieses Servers

| Platzhalter | Bedeutung |
| --- | --- |
| `%bytestorm_service%` | Name dieses Services, z. B. `Lobby-1` |
| `%bytestorm_platform%` | Plattform, z. B. `paper` |
| `%bytestorm_platform_version%` | Version der Plattform |
| `%bytestorm_motd%` | MOTD dieses Servers |
| `%bytestorm_players_current%` | Spieler auf diesem Server |
| `%bytestorm_players_max_current%` | Obergrenze dieses Servers |

## Netzwerk

Netzwerkwerte zählen nur Services im Zustand `ONLINE`; ein startender Service hat
noch keine Spieler, ein gestoppter soll die Summe nicht mit alten Zahlen
verfälschen.

| Platzhalter | Bedeutung |
| --- | --- |
| `%bytestorm_players%` | Spieler im ganzen Netzwerk |
| `%bytestorm_players_max%` | Summe der Obergrenzen (Services ohne Grenze zählen nicht) |
| `%bytestorm_services%` | laufende Services |
| `%bytestorm_services_all%` | bekannte Services, auch gestoppte und abgestürzte |
| `%bytestorm_groups%` | Gruppen mit mindestens einem laufenden Service |
| `%bytestorm_crashed%` | Services im Zustand `CRASHED` |

## Ein Service

`<name>` ist der Servicename, Groß-/Kleinschreibung egal (`Lobby-1`, `lobby-1`).

| Platzhalter | Bedeutung |
| --- | --- |
| `%bytestorm_service_players_<name>%` | Spieler dieses Services |
| `%bytestorm_service_max_players_<name>%` | Obergrenze |
| `%bytestorm_service_state_<name>%` | Zustand, z. B. `ONLINE`, `CRASHED` |
| `%bytestorm_service_group_<name>%` | Gruppe |
| `%bytestorm_service_port_<name>%` | Port |

## Eine Gruppe

`<gruppe>` ist der Gruppenname, Groß-/Kleinschreibung egal.

| Platzhalter | Bedeutung |
| --- | --- |
| `%bytestorm_group_players_<gruppe>%` | Spieler aller laufenden Services der Gruppe |
| `%bytestorm_group_services_<gruppe>%` | laufende Services der Gruppe |
| `%bytestorm_group_fallback_<gruppe>%` | Fallback-Service mit der kleinsten Priorität, `-` wenn keiner läuft |

Unbekannte Namen und unbekannte Platzhalter liefern einen leeren String — im Chat
bleibt dann eine Lücke statt `%bytestorm_...%` stehen.

## Datenquelle

Die Werte kommen aus derselben Registry, aus der die Bridge ihre Server kennt:
beim Verbindungsaufbau ein Snapshot, danach jeder `ServiceStateChanged`. Ein
gerade gestarteter Service erscheint, sobald ihn die Cloud als `ONLINE` meldet;
die Spielerzahlen stammen aus den Heartbeats der Services und sind damit
höchstens einen Heartbeat alt (Standard: 10 s).

Die Erweiterung fragt nichts nach: kein REST-Aufruf, keine MongoDB, kein
zusätzlicher Port. `%bytestorm_...%` funktioniert also auch, wenn das
REST-Modul aus ist.
