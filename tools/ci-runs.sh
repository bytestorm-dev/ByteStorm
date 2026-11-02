#!/usr/bin/env bash
# Liest die GitHub-Actions-Läufe dieses Repos über die REST-API.
#
# Das Repository ist privat, deshalb braucht die API ein Token:
#   export GH_TOKEN=$(gh auth token)     # oder ein Personal Access Token mit "repo"
#   tools/ci-runs.sh                     # die letzten Läufe
#   tools/ci-runs.sh --wait "$(git rev-parse HEAD)"
#   tools/ci-runs.sh --logs 41327105743  # nur einen bestimmten Lauf
#
# Schlägt der beobachtete Lauf fehl, zeigt --wait einen Auszug aus dem Log des
# fehlgeschlagenen Jobs. Das Token geht nur als Authorization-Header raus.
set -euo pipefail

here="$(dirname "$0")"
repo="${CI_REPO:-bytestorm-dev/ByteStorm}"
limit=5
wait_sha=""
logs_run=""
while [ $# -gt 0 ]; do
  case "$1" in
    --limit)
      limit="${2:?--limit braucht eine Zahl}"
      shift 2
      ;;
    --wait)
      wait_sha="${2:?--wait braucht einen Commit}"
      shift 2
      ;;
    --logs)
      logs_run="${2:?--logs braucht eine Run-ID}"
      shift 2
      ;;
    -h | --help)
      sed -n '2,11p' "$0" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *)
      echo "Unbekannte Option: $1" >&2
      exit 2
      ;;
  esac
done

token="${GH_TOKEN:-${GITHUB_TOKEN:-}}"
if [ -z "$token" ]; then
  echo "Kein Token gefunden: GH_TOKEN (oder GITHUB_TOKEN) exportieren." >&2
  echo "  export GH_TOKEN=\$(gh auth token)    # oder Personal Access Token mit 'repo'" >&2
  exit 2
fi

api="https://api.github.com/repos/$repo"
runs="$api/actions/runs"

# fetch <url> — API-Aufruf mit Token; Fehler landen als curl-Exitcode.
fetch() {
  curl -fsS -H "Accept: application/vnd.github+json" \
    -H "Authorization: Bearer $token" \
    -H "X-GitHub-Api-Version: 2022-11-28" "$1"
}

# failedJobs <run-id> — "id|name" der fehlgeschlagenen Jobs eines Laufs.
failedJobs() {
  fetch "$runs/$1/jobs?per_page=100" | python3 -c '
import json, sys

for job in json.load(sys.stdin).get("jobs", []):
    if job.get("conclusion") == "failure":
        print("%s|%s" % (job["id"], job["name"].replace("|", "/")))
'
}

# logOf <job-id> — Log eines Jobs. Die signierte Weiterleitung bekommt kein
# Token: Sie ist bereits lesbar, und Authorization darf nicht an fremde Hosts.
logOf() {
  local target
  target="$(curl -sS -o /dev/null -w '%{redirect_url}' \
    -H "Accept: application/vnd.github+json" \
    -H "Authorization: Bearer $token" \
    -H "X-GitHub-Api-Version: 2022-11-28" \
    "$api/actions/jobs/$1/logs")"
  if [ -z "$target" ]; then
    echo "(kein Log abrufbar)" >&2
    return 0
  fi
  curl -fsSL "$target"
}

# showFailure <run-id> — Auszug aus jedem fehlgeschlagenen Job.
showFailure() {
  local jobs job name
  jobs="$(failedJobs "$1")"
  if [ -z "$jobs" ]; then
    echo "Kein fehlgeschlagener Job mit Log gefunden."
    return 0
  fi
  while IFS='|' read -r job name; do
    [ -z "$job" ] && continue
    printf '\nAuszug aus dem Log von \x27%s\x27 (Job %s):\n' "$name" "$job"
    logOf "$job" | python3 "$here/ci-log-excerpt.py" || true
  done <<< "$jobs"
}

# waitFor <sha> — pollt, bis der Lauf zu diesem Commit fertig ist.
waitFor() {
  local sha="$1" line status rest conclusion run_id url
  while :; do
    line="$(fetch "$runs?head_sha=$sha&per_page=1" | python3 -c '
import json, sys

runs = json.load(sys.stdin).get("workflow_runs", [])
if not runs:
    print("pending|-|-|-")
else:
    run = runs[0]
    print("%s|%s|%s|%s" % (run.get("status"), run.get("conclusion") or "-",
                          run.get("id"), run.get("html_url")))
')"
    status="${line%%|*}"
    rest="${line#*|}"
    conclusion="${rest%%|*}"
    rest="${rest#*|}"
    run_id="${rest%%|*}"
    url="${rest#*|}"
    if [ "$status" = "completed" ]; then
      echo "Ergebnis: $conclusion  $url"
      if [ "$conclusion" = "success" ]; then
        return 0
      fi
      showFailure "$run_id"
      return 1
    fi
    echo "… $status ($url)"
    sleep 15
  done
}

if [ -n "$logs_run" ]; then
  line="$(fetch "$runs/$logs_run" | python3 -c '
import json, sys

run = json.load(sys.stdin)
print("%s|%s|%s" % (run.get("conclusion") or run.get("status") or "-",
                    (run.get("head_sha") or "")[:12], run.get("html_url")))
')"
  conclusion="${line%%|*}"
  rest="${line#*|}"
  sha="${rest%%|*}"
  url="${rest#*|}"
  echo "Lauf $logs_run: $conclusion ($sha)  $url"
  if [ "$conclusion" != "failure" ]; then
    echo "Kein Fehlschlag — kein Fehlerlog zu zeigen."
    exit 0
  fi
  showFailure "$logs_run"
  exit 0
fi

if [ -n "$wait_sha" ]; then
  waitFor "$wait_sha"
  exit $?
fi

fetch "$runs?per_page=$limit" | python3 -c '
import json, sys

runs = json.load(sys.stdin).get("workflow_runs", [])
if not runs:
    print("keine Läufe gefunden")
for run in runs:
    print("%-11s %-11s %-9s %-12s %s" % (
        run.get("status"),
        run.get("conclusion") or "-",
        run.get("head_branch") or "-",
        (run.get("head_sha") or "")[:12],
        run.get("html_url"),
    ))
'
