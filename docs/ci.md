# CI-Prüfungen

Zwei Workflows, zwei Aufgaben:

| Workflow | Auslöser | Tut |
|---|---|---|
| `.github/workflows/ci.yml` | jeder Push auf `main`/`master`, jeder Pull Request | `./gradlew build --stacktrace`, **nichts** veröffentlichen |
| `.github/workflows/release.yml` | nur Tag `v<version>`, oder manuell | bauen, testen, Zip packen, GitHub-Release anlegen |

## Prüfen (`ci.yml`)

Temurin JDK 25 mit Gradle-Cache. `build` umfasst `compileJava`, `check` und damit
die Unit-Tests aller Module — `:core`, `:node`, `:controller`, `:cli`,
`:launcher`, die Bridges, das Proxy-Plugin und die Module unter `modules/`.
Ein einziger roter Test bricht den Lauf ab. Berechtigungen sind auf
`contents: read` beschränkt; ein neuer Push auf denselben Branch bricht den
vorherigen Lauf ab.

## Veröffentlichen (`release.yml`)

Läuft ausschließlich auf einem Tag `v<version>` (oder per `workflow_dispatch`)
und verweigert den Lauf, wenn Tag und `version=` aus `gradle.properties` nicht
zusammengepassen:

```
v1.0.0  ↔  version=1.0.0     → Release
v1.0.0  ↔  version=1.0.1     → Abbruch mit ::error
```

Danach wird `bytestorm-<version>.zip` gebaut und als Release veröffentlicht;
endet die Version auf `SNAPSHOT`, als Beta (`prerelease=true`), sonst als
öffentlicher Release. Ein bereits vorhandener Release derselben Version wird
aktualisiert statt abzubrechen.

## Lokal denselben Stand prüfen

```bash
./gradlew build
./gradlew :launcher:distributionZip     # zusätzlich prüfen, dass das Zip entsteht
```

Mehr braucht es nicht: Der Workflow ruft dieselben Befehle ohne zusätzliche
Flags auf.

## Läufe lesen

Das Repository ist privat, deshalb braucht die REST-API ein Token:
`export GH_TOKEN=<PAT mit "repo" oder Actions: Read>`.

```bash
tools/ci-runs.sh                                  # die letzten Läufe
tools/ci-runs.sh --wait "$(git rev-parse HEAD)"   # auf den eigenen Commit warten
tools/ci-runs.sh --logs 41327105743               # nur einen bestimmten Lauf
```

Schlägt ein Lauf fehl, zeigt `--wait` einen Auszug aus dem Log jedes
fehlgeschlagenen Jobs; `tools/ci-log-excerpt.py` filtert dafür auf
Fehlermarker wie `FAILED`, `BUILD FAILED` und `error:`. Das Token geht nur an
`api.github.com` — das signierte Log liegt auf einem anderen Host und wird
ohne Header geladen.
