#!/usr/bin/env bash
# Generates a development PKI for the AISafe services (encryption in transit).
#
#   certs/ca.p12           dev CA (private key - never copy this into a container)
#   certs/ca.crt           dev CA certificate (PEM) - import into Postman / browser to trust the services
#   certs/truststore.p12   contains only the CA certificate; used by clients to verify servers
#   certs/<svc>.p12        key + certificate (signed by the CA) for each service
#   certs/flightops-lb.crt / .key   the same for the nginx load balancer, as PEM files (nginx can't read .p12)
#   certs/rabbitmq.crt / .key       the same for the message broker (AMQPS), as PEM files
#
# An existing CA is kept (only the service certificates are made again), so whoever already trusts certs/ca.crt
# (Postman, a browser) keeps trusting the new ones. Delete certs/ to start from a new CA.
#
# Each certificate is valid for localhost, 127.0.0.1, the docker-compose hostnames <svc>, <svc>-1 .. <svc>-3
# (the load balancer verifies the instances under the name "flightops") and the Kubernetes pod names
# <svc>-0.<svc>-peers .. <svc>-9.<svc>-peers (k8s/).
# Usage: ./scripts/generate-dev-certs.sh            (password defaults to "changeit", override with SSL_PASSWORD)
set -euo pipefail

OUT="$(cd "$(dirname "$0")/.." && pwd)/certs"
PASS="${SSL_PASSWORD:-changeit}"
SERVICES=(flightops aircraft airports flightops-lb rabbitmq)

mkdir -p "$OUT"
cd "$OUT"

if [[ -f ca.p12 && -f ca.crt && -f truststore.p12 ]]; then
  echo "-> CA: keeping the existing one"
  for svc in "${SERVICES[@]}"; do rm -f "$svc.p12" "$svc.crt" "$svc.csr" "$svc.key"; done
  rm -f flightops-lb-chain.crt
else
  rm -f ./*.p12 ./*.crt ./*.csr ./*.key
  echo "-> CA"
  keytool -genkeypair -alias ca -keyalg RSA -keysize 2048 -validity 3650 \
    -dname "CN=AISafe Dev CA, O=ISEP SIDIS" -ext bc:c \
    -keystore ca.p12 -storetype PKCS12 -storepass "$PASS"
  keytool -exportcert -alias ca -keystore ca.p12 -storepass "$PASS" -rfc -file ca.crt
  keytool -importcert -noprompt -alias aisafe-ca -file ca.crt \
    -keystore truststore.p12 -storetype PKCS12 -storepass "$PASS"
fi

for svc in "${SERVICES[@]}"; do
  echo "-> $svc"
  # Kubernetes (k8s/): pod i of the StatefulSet is <svc>-i.<svc>-peers (DNS of the headless Service), up to 10 pods
  K8S_SAN=""
  for i in $(seq 0 9); do K8S_SAN+=",dns:$svc-$i.$svc-peers"; done
  keytool -genkeypair -alias "$svc" -keyalg RSA -keysize 2048 -validity 825 \
    -dname "CN=$svc, O=ISEP SIDIS" \
    -keystore "$svc.p12" -storetype PKCS12 -storepass "$PASS"
  keytool -certreq -alias "$svc" -keystore "$svc.p12" -storepass "$PASS" -file "$svc.csr"
  keytool -gencert -alias ca -keystore ca.p12 -storepass "$PASS" -rfc -validity 825 \
    -infile "$svc.csr" -outfile "$svc.crt" \
    -ext "SAN=dns:localhost,ip:127.0.0.1,dns:$svc,dns:$svc-1,dns:$svc-2,dns:$svc-3$K8S_SAN" \
    -ext "KU=digitalSignature,keyEncipherment" -ext "EKU=serverAuth,clientAuth"
  # install the chain (CA first, then the signed certificate as reply to the key entry)
  keytool -importcert -noprompt -alias aisafe-ca -file ca.crt -keystore "$svc.p12" -storepass "$PASS"
  keytool -importcert -noprompt -alias "$svc" -file "$svc.crt" -keystore "$svc.p12" -storepass "$PASS"
  rm -f "$svc.csr"
done

# nginx and RabbitMQ need the key (and nginx the certificate chain) as PEM files
for svc in flightops-lb rabbitmq; do
  openssl pkcs12 -in "$svc.p12" -passin "pass:$PASS" -nocerts -nodes 2>/dev/null | openssl pkey -out "$svc.key"
done
chmod 644 rabbitmq.key   # read by the rabbitmq user inside its container (development certificates only)
cat flightops-lb.crt ca.crt > flightops-lb-chain.crt

echo "Done. Files in $OUT"
