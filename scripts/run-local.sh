#!/usr/bin/env bash
# Starts N flight-operations instances locally, without Docker. Ctrl+C stops all of them.
#
#   ./scripts/run-local.sh            2 instances, plain HTTP  (8083, 8093)
#   ./scripts/run-local.sh --tls      2 instances, HTTPS       (needs ./scripts/generate-dev-certs.sh first)
#   ./scripts/run-local.sh -n 3       3 instances              (8083, 8093, 8103)
#
# Port scheme: 8083 + 10 * (i - 1). Every instance gets all the others as peers and its own slice of the sample data.
# Logs: logs/flightops-<i>.log
set -euo pipefail
cd "$(dirname "$0")/.."

N=2
SCHEME=http
PROFILE=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    -n) N="$2"; shift 2 ;;
    --tls) SCHEME=https; PROFILE=tls; shift ;;
    *) echo "unknown option: $1"; exit 1 ;;
  esac
done

port() { echo $((8083 + 10 * ($1 - 1))); }

echo "Building..."
./mvnw -q -B package -DskipTests
JAR=$(ls target/flight-operations-service-*.jar | head -1)
mkdir -p logs

PIDS=()
cleanup() {
  trap - INT TERM EXIT
  echo; echo "Stopping instances..."
  for pid in "${PIDS[@]}"; do kill "$pid" 2>/dev/null || true; done
  wait 2>/dev/null || true
}
trap cleanup INT TERM EXIT

for i in $(seq 1 "$N"); do
  peers=()
  for j in $(seq 1 "$N"); do
    [[ $j -ne $i ]] && peers+=("$SCHEME://localhost:$(port "$j")")
  done
  PEERS=$(IFS=,; echo "${peers[*]}")

  SPRING_PROFILES_ACTIVE="$PROFILE" PORT="$(port "$i")" PEERS="$PEERS" \
  BOOTSTRAP_SHARD="$i" SHARD_COUNT="$N" \
  AIRCRAFT_SERVICE_URLS="http://localhost:8081,http://localhost:8091" \
  AIRPORTS_ROUTES_SERVICE_URLS="http://localhost:8082,http://localhost:8092" \
    java -jar "$JAR" > "logs/flightops-$i.log" 2>&1 &
  PIDS+=($!)
  echo "flightops-$i  $SCHEME://localhost:$(port "$i")  peers=$PEERS  (logs/flightops-$i.log)"
done

echo "All $N instances starting. Ctrl+C to stop."
wait
