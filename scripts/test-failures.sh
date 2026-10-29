#!/usr/bin/env bash
# Failure tests on the whole system (docker compose up): runs Postman folder 11 in phases and stops / starts the
# containers in between, since Postman can't stop a container by itself (Week 5 p.14 "simulate instance failure
# (stop container, verify 404)", Week 4 p.11, PL3 p.14/p.16).
#
#   ./scripts/test-failures.sh                         all phases, HTTPS environment (the compose instances run TLS)
#   ./scripts/test-failures.sh --skip-other-services   without 11e (aircraft/airports run on other machines)
#
#   11a everything up          records who holds CS-TPC (owner instance2) and books flights
#   11b flightops-2 stopped    data still readable from the copy, lists complete, the backup takes bookings
#   11c CS-TPC's backup also   404 for its flights, lists flagged partial; a booking is kept where received (AP)
#   11d both started again     caught up, outbox empty, left-over copy handed off (can take up to a minute)
#   11e aircraft-1, airports-1 stopped: bookings still checked with their other instances
#
# Needs the system running and healthy (docker compose up -d --build) and Node.js (npx downloads newman).
# Every container it stops is started again at the end, also when a phase fails or on Ctrl+C.
# Exit code 0 when every phase passed.
set -uo pipefail
cd "$(dirname "$0")/.."

OTHERS=yes
while [[ $# -gt 0 ]]; do
  case "$1" in
    --skip-other-services) OTHERS=no; shift ;;
    *) echo "unknown option: $1"; exit 1 ;;
  esac
done

COLLECTION=postman/flight-operations.postman_collection.json
ENV_FILE=$(mktemp --suffix=.json)
cp postman/local-https.postman_environment.json "$ENV_FILE"
NEWMAN=(npx -y newman@6 run "$COLLECTION" --ssl-extra-ca-certs certs/ca.crt --color on)
PYTHON=$(command -v python3 || command -v python)
STOPPED=()
RESULTS=()
failed=0

health() { docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' \
             "$(docker compose ps -aq "$1")" 2>/dev/null; }

wait_healthy() {   # services...
  local svc start=$SECONDS
  for svc in "$@"; do
    until [[ "$(health "$svc")" == healthy ]]; do
      if (( SECONDS - start >= 180 )); then echo "$svc is not healthy after 180 s"; return 1; fi
      sleep 2
    done
    echo "$svc healthy"
  done
}

stop() { echo; echo ">>> docker compose stop $*"; docker compose stop "$@" >/dev/null 2>&1; STOPPED+=("$@"); }

start() {
  echo; echo ">>> docker compose start $*"
  docker compose start "$@" >/dev/null 2>&1
  wait_healthy "$@"
  local s keep=()
  for s in "${STOPPED[@]}"; do [[ " $* " == *" $s "* ]] || keep+=("$s"); done
  STOPPED=("${keep[@]}")
}

restore() {   # start whatever is still stopped (phase failed, Ctrl+C)
  if (( ${#STOPPED[@]} )); then start "${STOPPED[@]}"; fi
  rm -f "$ENV_FILE"
}
trap restore EXIT

# $1 = phase letter (a..e), $2 = folder name. Phase a also runs 00 Setup (logins, whole-system check), with everything
# up; the token and the values 11a records are carried to the next phases in the exported environment.
phase() {
  local setup=()
  [[ "$1" == a ]] && setup=(--folder "00 Setup")
  echo; echo "=================== phase $1: $2"
  "${NEWMAN[@]}" -e "$ENV_FILE" --env-var "failurePhase=$1" "${setup[@]}" --folder "$2" \
    --export-environment "$ENV_FILE"
  local rc=$?
  RESULTS+=("$( ((rc)) && echo FAILED || echo passed )  $2")
  (( rc )) && failed=1
  return 0
}

echo "Checking that the whole system is up..."
for svc in flightops-1 flightops-2 flightops-3 aircraft-1 aircraft-2 aircraft-3 airports-1 airports-2 airports-3; do
  [[ "$(health "$svc")" == healthy ]] || { echo "$svc is not healthy: start the system with docker compose up -d --build"; exit 1; }
done

phase a "11a Before - everything up"
backup=$("$PYTHON" -c "import json,sys; print(next(v['value'] for v in json.load(open(sys.argv[1]))['values'] if v['key']=='failBackup'))" \
  "$ENV_FILE" 2>/dev/null)
[[ "$backup" =~ ^instance[0-9]+$ ]] || { echo "11a did not record CS-TPC's backup - stopping here"; exit 1; }
backup_svc="flightops-${backup#instance}"

stop flightops-2
phase b "11b flightops-2 stopped"

stop "$backup_svc"
phase c "11c Both holders of CS-TPC stopped"

start flightops-2 "$backup_svc"
phase d "11d Both started again - automatic recovery"

if [[ "$OTHERS" == yes ]]; then
  stop aircraft-1 airports-1
  phase e "11e Other services: aircraft-1 and airports-1 stopped"
  start aircraft-1 airports-1
fi

echo; echo "=================== summary"
printf '  %s\n' "${RESULTS[@]}"
exit "$failed"
