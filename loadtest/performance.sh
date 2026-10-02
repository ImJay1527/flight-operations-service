#!/usr/bin/env bash
# PL3 p.21 performance measurements, in Docker (1 CPU per flight-ops instance, HTTPS, PostgreSQL, stub mode):
#
#   ./loadtest/performance.sh latency        local vs forwarded response times      -> loadtest/results/latency.txt
#   ./loadtest/performance.sh availability   kill instance 2 during steady traffic  -> loadtest/results/availability.txt
#                                            (per 10-s window: before / during / after the outage)
#
# Needs: Docker, and ./scripts/generate-dev-certs.sh run once. Uses its own Docker project, so it can run while
# your normal setup is up; everything (including its databases) is removed at the end.
set -euo pipefail
cd "$(dirname "$0")/.."
export MSYS_NO_PATHCONV=1   # Git Bash on Windows: don't rewrite /scripts paths

MODE="${1:-}"
[[ "$MODE" == latency || "$MODE" == availability ]] || { echo "usage: $0 latency|availability"; exit 1; }

PROJECT=flightops-perf
FILES=(-f docker-compose.yml -f loadtest/limits.yml -f loadtest/stub.yml)
compose() { docker compose -p "$PROJECT" "${FILES[@]}" "$@"; }
trap 'compose down -v >/dev/null 2>&1 || true' EXIT

k6() {   # $1 = script, $2 = duration, $3 = virtual users (optional, default 5)
  docker run --rm --network "${PROJECT}_default" \
    -v "$(pwd -W 2>/dev/null || pwd)/loadtest:/scripts" \
    -e DURATION="$2" -e VUS="${3:-5}" \
    grafana/k6:latest run --quiet "/scripts/$1"
}

echo "Starting 2 instances..."
compose up --build -d flightops-1 flightops-2
for svc in flightops-1 flightops-2; do
  until compose exec -T "$svc" bash -c 'exec 3<>/dev/tcp/127.0.0.1/8083' 2>/dev/null; do sleep 2; done
done
sleep 10
echo "Warm-up (30 s, not recorded)..."
k6 latency.js 30s > /dev/null 2>&1 || true

mkdir -p loadtest/results
OUT="loadtest/results/$MODE.txt"

if [[ "$MODE" == latency ]]; then
  echo "Measuring local vs forwarded lookups: 60 s with 1 user, then 60 s with 5 users..."
  { echo "=== 1 virtual user (pure latency, no queueing) ==="; k6 latency.js 60s 1
    echo; echo "=== 5 virtual users (under load) ==="; k6 latency.js 60s 5; } | tee "$OUT"
else
  echo "Steady traffic for 150 s; instance 2 is killed at ~30 s and started again at ~50 s (ready ~27 s later)..."
  ( sleep 30; echo ">>> $(date +%T) killing flightops-2"; compose kill flightops-2 >/dev/null 2>&1
    sleep 20; echo ">>> $(date +%T) starting flightops-2"; compose start flightops-2 >/dev/null 2>&1 ) &
  k6 availability.js 150s 2>&1 | grep -v "level=warning" | tee "$OUT"   # drop k6's per-request failure warnings
  wait
fi
echo "Saved to $OUT"
