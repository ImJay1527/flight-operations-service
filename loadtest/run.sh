#!/usr/bin/env bash
# Starts N flight-ops instances in Docker (1 CPU each), runs the k6 load test against them, and tears them down.
#
#   ./loadtest/run.sh 2          baseline: 2 instances
#   ./loadtest/run.sh 3          scaled:   3 instances
#   VUS=100 DURATION=90s ./loadtest/run.sh 3
#
# Needs: Docker, and ./scripts/generate-dev-certs.sh run once. Results: loadtest/results/<N>-instances.txt
set -euo pipefail
cd "$(dirname "$0")/.."
export MSYS_NO_PATHCONV=1   # Git Bash on Windows: don't rewrite /scripts paths

N="${1:-2}"
VUS="${VUS:-50}"
DURATION="${DURATION:-60s}"
PROJECT=flightops-loadtest

FILES=(-f docker-compose.yml -f loadtest/limits.yml)
SERVICES=(flightops-1 flightops-2)
TARGETS="https://flightops-1:8083,https://flightops-2:8083"
if [[ "$N" == "3" ]]; then
  FILES=(-f docker-compose.yml -f docker-compose.scale-3.yml -f loadtest/limits.yml -f loadtest/limits-3.yml)
  SERVICES+=(flightops-3)
  TARGETS+=",https://flightops-3:8083"
elif [[ "$N" != "2" ]]; then
  echo "only 2 or 3 instances are supported"; exit 1
fi

compose() { docker compose -p "$PROJECT" "${FILES[@]}" "$@"; }
trap 'compose down >/dev/null 2>&1 || true' EXIT

echo "Starting $N instances..."
compose up --build -d "${SERVICES[@]}"
for svc in "${SERVICES[@]}"; do
  until compose exec -T "$svc" bash -c 'exec 3<>/dev/tcp/127.0.0.1/8083' 2>/dev/null; do sleep 2; done
done
sleep 10   # let the JVMs finish starting

k6() {
  docker run --rm --network "${PROJECT}_default" \
    -v "$(pwd -W 2>/dev/null || pwd)/loadtest:/scripts" \
    -e TARGETS="$TARGETS" -e VUS="$VUS" -e DURATION="$1" \
    grafana/k6:latest run --quiet /scripts/flightops.js
}

# On 1 CPU the JVM's JIT compilation competes with requests for a long time; without a warm-up the
# measured run mostly measures JVM start-up, not the system.
echo "Warm-up (2 x 30s, not recorded)..."
k6 30s > /dev/null 2>&1 || true
k6 30s > /dev/null 2>&1 || true

mkdir -p loadtest/results
OUT="loadtest/results/${N}-instances.txt"
echo "Running k6: $VUS virtual users for $DURATION against $TARGETS"
k6 "$DURATION" | tee "$OUT"
echo "Saved to $OUT"
