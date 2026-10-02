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
CURL=(curl -sS -o /dev/null -w '%{http_code}' --max-time 2)
# --ssl-no-revoke: curl on Windows (Schannel) otherwise rejects the dev certificates, which have no revocation list.
# The certificate itself is still verified against the dev CA.
[[ "$SCHEME" == https ]] && CURL+=(--cacert certs/ca.crt --ssl-no-revoke)
ERRF=$(mktemp)
trap 'rm -f "$ERRF"' EXIT

# prints the HTTP status (000 = no answer); curl's error message goes to $ERRF
probe() { "${CURL[@]}" "$SCHEME://localhost:$(port "$1")/actuator/health/readiness" 2>"$ERRF"; }

wait_for() {   # $1 = instance number
  local start=$SECONDS code rc
  while true; do
    code=$(probe "$1"); rc=$?
    [[ "$code" == 200 ]] && break
    # Keep waiting while nothing listens yet (7), the answer is slow (28) or the instance is still starting (503).
    # Anything else, e.g. a TLS error, won't go away by waiting.
    if (( rc != 0 && rc != 7 && rc != 28 )); then
      echo "flightops-$1: $(head -1 "$ERRF")"
      return 1
    elif (( rc == 0 )) && [[ "$code" != 503 ]]; then
      case "$code" in
        400) echo "flightops-$1: HTTP 400 - the instance runs HTTPS, add --tls" ;;
        404) echo "flightops-$1: HTTP 404 - built before /actuator/health/readiness existed, restart it" ;;
        *)   echo "flightops-$1: HTTP $code from /actuator/health/readiness" ;;
      esac
      return 1
    fi
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
