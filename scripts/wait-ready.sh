#!/usr/bin/env bash
# Waits until the local flight-operations instances are ready for tests, then prints READY FOR TESTS.
# "Ready" = /actuator/health/readiness answers UP: Spring has fully started and the sample data is loaded
# (/actuator/health turns UP earlier, while the sample data may still be loading).
#
#   ./scripts/wait-ready.sh              instances 1 and 2, plain HTTP (8083, 8093)
#   ./scripts/wait-ready.sh -n 3         instances 1..3
#   ./scripts/wait-ready.sh --tls        HTTPS (verified against certs/ca.crt)
#   ./scripts/wait-ready.sh --instance 2 only instance 2, no banner (run-local.sh uses this after a restart)
#   ./scripts/wait-ready.sh --timeout 300
#
# Exit code 0 when all are ready, 1 when one is not ready within the timeout (default 180 s each).
# Called by run-local.sh; run it yourself when the instances are started some other way (e.g. from VS Code).
set -uo pipefail
cd "$(dirname "$0")/.."

N=2
SCHEME=http
ONLY=""
TIMEOUT=180
while [[ $# -gt 0 ]]; do
  case "$1" in
    -n) N="$2"; shift 2 ;;
    --tls) SCHEME=https; shift ;;
    --instance) ONLY="$2"; shift 2 ;;
    --timeout) TIMEOUT="$2"; shift 2 ;;
    *) echo "unknown option: $1"; exit 1 ;;
  esac
done

port() { echo $((8083 + 10 * ($1 - 1))); }
CURL=(curl -fs -o /dev/null --max-time 2)
[[ "$SCHEME" == https ]] && CURL+=(--cacert certs/ca.crt)

ready() { "${CURL[@]}" "$SCHEME://localhost:$(port "$1")/actuator/health/readiness"; }

wait_for() {   # $1 = instance number
  local start=$SECONDS
  until ready "$1"; do
    if (( SECONDS - start >= TIMEOUT )); then
      echo "flightops-$1 is NOT ready after ${TIMEOUT}s - see logs/flightops-$1.log"
      return 1
    fi
    sleep 1
  done
  echo "flightops-$1 ready  $SCHEME://localhost:$(port "$1")  ($((SECONDS - start))s)"
}

if [[ -n "$ONLY" ]]; then
  wait_for "$ONLY"
  exit
fi

echo "Waiting for $N instance(s) to be ready..."
failed=0
for i in $(seq 1 "$N"); do
  wait_for "$i" || failed=1
done
(( failed )) && exit 1

env_name="Flight Ops - local (2 instances)"
[[ "$SCHEME" == https ]] && env_name="Flight Ops - local HTTPS (2 instances)"
echo
echo "=================================================================="
echo "  READY FOR TESTS - all $N instance(s) are up"
echo "  Postman: environment \"$env_name\", then run the collection"
echo "=================================================================="
echo
