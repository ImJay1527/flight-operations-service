#!/usr/bin/env bash
# Runs the AISafe system on the local Kubernetes of Docker Desktop (Settings -> Kubernetes, kubeadm cluster).
#
#   ./k8s/deploy.sh           the 3 services, 3 instances each; Flight Operations auto-scales between 3 and 5
#   ./k8s/deploy.sh --stub    only Flight Operations, with built-in aircraft/route data (no other services needed)
#   ./k8s/deploy.sh --down    removes everything (namespace aisafe, including the databases)
#   OTHER_INSTANCES=1 ./k8s/deploy.sh   only 1 pod of each of the other two services (less memory; default 3)
#
# Clients: https://localhost:9443 (Kubernetes load balancer in front of the Flight Operations pods).
# Watch:   kubectl -n aisafe get pods,hpa -w
# Logs:    kubectl -n aisafe logs flightops-0 -c flightops -f
#
# Needs ./scripts/generate-dev-certs.sh run once (certificates for HTTPS). Without --stub the other two repos must be
# cloned next to this one (../aircraft-maintenance-service, ../airports-routes-service).
set -euo pipefail
cd "$(dirname "$0")/.."
export MSYS_NO_PATHCONV=1   # Git Bash on Windows: don't rewrite /paths in arguments

NS=aisafe
METRICS_SERVER=https://github.com/kubernetes-sigs/metrics-server/releases/download/v0.7.2/components.yaml
STUB=""
OTHERS="${OTHER_INSTANCES:-3}"
[[ "$OTHERS" =~ ^[1-9]$ ]] || { echo "OTHER_INSTANCES must be 1..9"; exit 1; }
case "${1:-}" in
  "") ;;
  --stub) STUB=yes ;;
  --down) kubectl delete namespace "$NS" --ignore-not-found; exit 0 ;;
  *) echo "usage: $0 [--stub | --down]"; exit 1 ;;
esac

kubectl version >/dev/null 2>&1 \
  || { echo "Kubernetes is not running: Docker Desktop -> Kubernetes -> Create cluster (kubeadm)."; exit 1; }
echo "Kubernetes context: $(kubectl config current-context)"

if ! grep -q "flightops-0.flightops-peers" <(keytool -printcert -file certs/flightops.crt 2>/dev/null); then
  echo "The certificates have no Kubernetes names yet: run ./scripts/generate-dev-certs.sh first."; exit 1
fi

# ---------- images (Kubernetes uses the images of the local Docker engine) ----------
echo "Building flightops:dev..."
docker build -q -t flightops:dev . >/dev/null
if [[ -z "$STUB" ]]; then
  # this Dockerfile copies an already built jar
  if ! ls ../aircraft-maintenance-service/target/*.jar >/dev/null 2>&1; then
    echo "Packaging aircraft-maintenance-service..."
    # MSYS_NO_PATHCONV would hand Maven a /c/... classpath that the Windows JVM cannot read
    (cd ../aircraft-maintenance-service && unset MSYS_NO_PATHCONV && ./mvnw -q -B package -DskipTests)
  fi
  echo "Building aircraft:dev and airports:dev..."
  docker build -q -t aircraft:dev ../aircraft-maintenance-service >/dev/null
  docker build -q -t airports:dev ../airports-routes-service >/dev/null
fi

# ---------- metrics-server (CPU usage for the auto-scaler; not included in Docker Desktop) ----------
if ! kubectl -n kube-system get deployment metrics-server >/dev/null 2>&1; then
  echo "Installing metrics-server..."
  kubectl apply -f "$METRICS_SERVER"
  # Docker Desktop's kubelet has a self-signed certificate
  kubectl -n kube-system patch deployment metrics-server --type=json \
    -p '[{"op":"add","path":"/spec/template/spec/containers/0/args/-","value":"--kubelet-insecure-tls"}]'
fi

# ---------- namespace, secrets, configuration ----------
apply() { kubectl apply -f - >/dev/null; }
kubectl create namespace "$NS" --dry-run=client -o yaml | apply

# Secrets are kept out of the manifests (and out of git); same defaults as docker-compose.yml, override with env vars.
kubectl -n "$NS" create secret generic flightops-certs \
  --from-file=certs/flightops.p12 --from-file=certs/truststore.p12 --dry-run=client -o yaml | apply
kubectl -n "$NS" create secret generic flightops-secrets \
  --from-literal=JWT_SECRET="${JWT_SECRET:-404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970}" \
  --from-literal=DB_PASSWORD="${DB_PASSWORD:-flightops}" \
  --from-literal=SSL_PASSWORD="${SSL_PASSWORD:-changeit}" \
  --from-literal=DATA_ENCRYPTION_KEY="${DATA_ENCRYPTION_KEY:-9BvH3kXoOq3m1o9mJmPFjB6m4cO1kz3Hqk2iY7G1M8o=}" \
  --dry-run=client -o yaml | apply

peer_urls() { local u=""; for ((i = 0; i < OTHERS; i++)); do u+="${u:+,}http://$1-$i.$1-peers:$2"; done; echo "$u"; }
kubectl -n "$NS" create configmap flightops-config \
  --from-literal=SPRING_PROFILES_ACTIVE="tls,postgres,k8s${STUB:+,stub}" \
  --from-literal=AIRCRAFT_SERVICE_URLS="$(peer_urls aircraft 8081)" \
  --from-literal=AIRPORTS_ROUTES_SERVICE_URLS="$(peer_urls airports 8082)" \
  --dry-run=client -o yaml | apply

# ---------- services ----------
REDEPLOY=""
kubectl -n "$NS" get statefulset flightops >/dev/null 2>&1 && REDEPLOY=yes
kubectl apply -f k8s/flightops.yaml -f k8s/hpa.yaml
if [[ -z "$STUB" ]]; then
  sed -e "s/replicas: 3/replicas: $OTHERS/" -e "s/name: INSTANCES, value: \"3\"/name: INSTANCES, value: \"$OTHERS\"/" \
    k8s/other-services.yaml | kubectl apply -f -
else
  kubectl -n "$NS" delete -f k8s/other-services.yaml --ignore-not-found >/dev/null
fi
if [[ -n "$REDEPLOY" ]]; then
  # new image or configuration: restart the pods one at a time (each catches up before the next goes)
  kubectl -n "$NS" rollout restart statefulset/flightops >/dev/null
  if [[ -z "$STUB" ]]; then
    kubectl -n "$NS" rollout restart statefulset/aircraft statefulset/airports >/dev/null 2>&1 || true
  fi
fi

echo "Waiting for Flight Operations (first start: a few minutes)..."
kubectl -n "$NS" rollout status statefulset/flightops --timeout=10m
echo
kubectl -n "$NS" get pods,hpa
echo
echo "READY: https://localhost:9443  (watch: kubectl -n $NS get pods,hpa -w)"
