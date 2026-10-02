#!/usr/bin/env bash
# Starts N flight-operations instances locally, without Docker. Ctrl+C stops all of them.
#
#   ./scripts/run-local.sh            2 instances, plain HTTP  (8083, 8093)
#   ./scripts/run-local.sh --tls      2 instances, HTTPS       (needs ./scripts/generate-dev-certs.sh first)
#   ./scripts/run-local.sh -n 3       3 instances              (8083, 8093, 8103)
#
# Each instance runs with its Spring profile instance<i> (src/main/resources/application-instance<i>.properties).
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
    -n) N="$2"; [[ "$N" =~ ^[123]$ ]] || { echo "-n must be 1, 2 or 3 (one instance profile each)"; exit 1; }; shift 2 ;;
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
stop_pid() {
  # Git Bash on Windows: a plain kill does not always stop a native java.exe, so use its Windows PID
  if [[ -r /proc/$1/winpid ]] && command -v taskkill >/dev/null; then
    taskkill //F //T //PID "$(cat /proc/$1/winpid)" >/dev/null 2>&1 || true
  else
    kill "$1" 2>/dev/null || true
  fi
}
cleanup() {
  trap '' INT TERM; trap - EXIT   # ignore further Ctrl+C while stopping (a second one would kill the cleanup)
  echo; echo "Stopping instances..."
  for pid in "${PIDS[@]}"; do stop_pid "$pid"; done
  wait 2>/dev/null || true
}
trap cleanup INT TERM EXIT

for i in $(seq 1 "$N"); do
  peers=()
  for j in $(seq 1 "$N"); do
    [[ $j -ne $i ]] && peers+=("$SCHEME://localhost:$(port "$j")")
  done
  PEERS=$(IFS=,; echo "${peers[*]}")

  # Port, DB, data slice etc. come from application-instance<i>.properties. PEERS and SHARD_COUNT are passed
  # because they depend on how many instances run (instance 1 and 2 must learn about instance 3 when N=3).
  PEERS="$PEERS" SHARD_COUNT="$N" \
    java -jar "$JAR" --spring.profiles.active="instance$i${PROFILE:+,$PROFILE}" > "logs/flightops-$i.log" 2>&1 &
  PIDS+=($!)
  echo "flightops-$i  $SCHEME://localhost:$(port "$i")  peers=$PEERS  (logs/flightops-$i.log)"
done

echo "All $N instances starting. Ctrl+C to stop."
wait
