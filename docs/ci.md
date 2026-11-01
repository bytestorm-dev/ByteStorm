# CI-Prüfungen

Jeder Push auf `master` löst `.github/workflows/release.yml` aus. Der Workflow
prüft und veröffentlicht in einem Lauf:

1. **Prüfen** — `./gradlew build --stacktrace` auf Temurin JDK 25 mit
   Gradle-Cache. `build` umfasst `compileJava`, `check` und damit die
   Unit-Tests aller Module, etwa `:core`, `:node`, `:controller`, `:cli`,
   `:launcher` sowie Bridges, Proxy-Plugin und die Module unter `modules/`.
   Ein einziger roter Test bricht den Lauf ab.
2. **Veröffentlichen** — liest `version=` aus `gradle.properties` und legt einen
   GitHub-Release an: mit `SNAPSHOT` als Beta (`prerelease=true`), sonst als
   öffentlichen Release.

## Lokal denselben Stand prüfen

```bash
./gradlew build
```

Mehr braucht es nicht: Der Workflow ruft denselben Befehl ohne zusätzliche
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
