#!/usr/bin/env bash
# Starts N flight-operations instances locally (the Java apps run outside Docker). Ctrl+C stops all of them.
#
#   ./scripts/run-local.sh            2 instances, plain HTTP  (8083, 8093), each with its own PostgreSQL container
#   ./scripts/run-local.sh --tls      2 instances, HTTPS       (needs ./scripts/generate-dev-certs.sh first)
#   ./scripts/run-local.sh -n 3       3 instances              (8083, 8093, 8103)
#   ./scripts/run-local.sh --h2       in-memory H2 instead of PostgreSQL (no Docker needed; data is lost on stop)
#   ./scripts/run-local.sh --stub     built-in aircraft/route data instead of calling the other two services
#                                     (needed for the Postman collection in postman/ while those services are stubs)
#   ./scripts/run-local.sh --no-restart   don't restart an instance that stops on its own
#
# An instance that stops on its own (crash, or POST /actuator/shutdown in stub mode - the Postman resilience test does
# that to instance 2) is started again after RESTART_DELAY seconds (default 10).
#
# PostgreSQL: the database containers (flightops-db-<i>, ports 5433/5434/5435) are started with docker compose and
# left running when you press Ctrl+C, so the data is kept. Stop them: docker compose stop
# Delete their data: docker compose down -v
#
# Instance i runs with the Spring profile instance<i> on port 8083 + 10 * (i - 1). Logs: logs/flightops-<i>.log
set -euo pipefail
cd "$(dirname "$0")/.."

N=2
SCHEME=http
PROFILE=""
DB=postgres
STUB=""
RESTART=yes
RESTART_DELAY="${RESTART_DELAY:-10}"
while [[ $# -gt 0 ]]; do
  case "$1" in
    -n) N="$2"; [[ "$N" =~ ^[123]$ ]] || { echo "-n must be 1, 2 or 3 (one instance profile each)"; exit 1; }; shift 2 ;;
    --tls) SCHEME=https; PROFILE=tls; shift ;;
    --h2) DB=h2; shift ;;
    --stub) STUB=stub; shift ;;
    --no-restart) RESTART=no; shift ;;
    *) echo "unknown option: $1"; exit 1 ;;
  esac
done

port() { echo $((8083 + 10 * ($1 - 1))); }

# Refuse to start if an instance port is taken (e.g. instances still running from VS Code or an earlier run):
# they would keep the old code running and lock the jar, so the build below would fail.
for i in $(seq 1 "$N"); do
  if (exec 3<>"/dev/tcp/127.0.0.1/$(port "$i")") 2>/dev/null; then
    echo "Port $(port "$i") is already in use - stop the running instance(s) first (Ctrl+C in their terminal, or stop them in VS Code)."
    exit 1
  fi
done

if [[ "$DB" == postgres ]]; then
  DBS=(); for i in $(seq 1 "$N"); do DBS+=("flightops-db-$i"); done
  echo "Starting databases: ${DBS[*]}"
  docker compose -f docker-compose.yml -f docker-compose.scale-3.yml up -d --wait "${DBS[@]}"
  PROFILE="${PROFILE:+$PROFILE,}postgres"
fi

[[ -n "$STUB" ]] && PROFILE="${PROFILE:+$PROFILE,}stub"

echo "Building..."
./mvnw -q -B package -DskipTests
JAR=$(ls target/flight-operations-service-*.jar | head -1)
mkdir -p logs

# Each instance runs in a supervisor loop that restarts it when it stops on its own.
SUPERVISORS=()
stop_port() {
  if command -v taskkill >/dev/null; then   # Windows (Git Bash)
    for pid in $(netstat -ano | grep ":$1 .*LISTENING" | awk '{print $NF}' | sort -u); do
      taskkill //F //T //PID "$pid" >/dev/null 2>&1 || true
    done
  elif command -v lsof >/dev/null; then
    kill $(lsof -t -i:"$1" -sTCP:LISTEN) 2>/dev/null || true
  fi
}
cleanup() {
  trap '' INT TERM; trap - EXIT   # ignore further Ctrl+C while stopping (a second one would kill the cleanup)
  echo; echo "Stopping instances..."
  for pid in "${SUPERVISORS[@]}"; do kill "$pid" 2>/dev/null || true; done   # first the supervisors: no restarts
  for i in $(seq 1 "$N"); do stop_port "$(port "$i")"; done
  wait 2>/dev/null || true
}
trap cleanup INT TERM EXIT

supervise() {   # $1 = instance number
  while true; do
    CLUSTER="$CLUSTER" java -jar "$JAR" --spring.profiles.active="instance$1${PROFILE:+,$PROFILE}" >> "logs/flightops-$1.log" 2>&1
    [[ "$RESTART" == yes ]] || break
    echo "flightops-$1 stopped - starting it again in ${RESTART_DELAY}s"
    sleep "$RESTART_DELAY"
    echo "flightops-$1 restarting"
  done
}

# all instances by name, the same list for every instance
members=()
for j in $(seq 1 "$N"); do members+=("instance$j=$SCHEME://localhost:$(port "$j")"); done
CLUSTER=$(IFS=,; echo "${members[*]}")

for i in $(seq 1 "$N"); do

  # CLUSTER depends on how many instances run (with -n 3, instances 1 and 2 must know instance 3)
  : > "logs/flightops-$i.log"
  supervise "$i" &
  SUPERVISORS+=($!)
  echo "flightops-$i  $SCHEME://localhost:$(port "$i")  (logs/flightops-$i.log)"
done

echo "cluster: $CLUSTER"
echo "All $N instances starting. Ctrl+C to stop."
wait
