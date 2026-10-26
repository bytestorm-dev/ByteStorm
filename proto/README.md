# `proto` — Wire-Format

Quelle der Wahrheit für alle Nachrichten zwischen Cloud, Node, CLI, Bridges und Modulen
(Konzept §6). Generiert wird nach `build/generated/sources/proto/main/java` im Package
`dev.bytestorm.proto` (`java_multiple_files = true`).

## Dateien

| Datei | Inhalt |
|---|---|
| `enums.proto` | `ServiceState`, `ServiceType`, `ServiceMode`, `GroupState`, `RuntimeType`, `ModuleState` |
| `service.proto` | `ServiceInfo`, Lifecycle-/RPC-Nachrichten, `LogLine`, `ServiceCrashed` |
| `player.proto` | `PlayerInfo`, Join/Leave, `PlayerCountChanged` |
| `group.proto` | `ServiceGroup`, `GroupUpdated`, `GroupHealthChanged` |
| `scaling.proto` | `ScalingRule` mit `oneof` (Spielerzahl / Zeitplan / Last) |
| `template.proto` | `TemplateInfo`, `TemplateUpdated` |
| `bridge.proto` | Handshake (`BridgeHello`/`Ack`), `Heartbeat`, `PlayerSnapshot` |
| `module.proto` | `ModuleInfo`, `ModuleStateChanged` |

`ProtoVersion` (handgeschrieben) hält `MAJOR`/`MINOR` und die Type-URL-Helfer.

## Kompatibilitätsregeln

1. **Nur additive Änderungen** innerhalb einer Major-Version.
2. **Feldnummern niemals wiederverwenden.** Entfernte Felder werden `reserved`, inklusive Name:
   ```proto
   reserved 7;
   reserved "old_field";
   ```
3. Bestehende Feldtypen nicht ändern — auch nicht kompatible Enum-Werte umnummerieren.
4. Neue Enums starten bei `0`, damit Proto3-Defaults eindeutig sind.
5. Inkompatible Änderungen erhöhen `ProtoVersion.MAJOR`; der Bridge-Handshake lehnt
   abweichende Major-Versionen ab (§6.4). Minor-Änderungen sind rein additiv.
6. Bridges relocaten `protobuf-java` im Shadow-Jar nach `dev.bytestorm.libs.protobuf`,
   damit es nicht mit anderen Plugins kollidiert.

## Tests

`./gradlew :proto:test` serialisiert jede Nachricht und prüft die Type-URL gegen
`Any.pack(...)`. Neue Nachrichten gehören in `ProtoRoundtripTest`.
