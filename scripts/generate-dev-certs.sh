#!/usr/bin/env bash
# Generates a development PKI for the AISafe services (encryption in transit).
#
#   certs/ca.p12           dev CA (private key - never copy this into a container)
#   certs/ca.crt           dev CA certificate (PEM) - import into Postman / browser to trust the services
#   certs/truststore.p12   contains only the CA certificate; used by clients to verify servers
#   certs/<svc>.p12        key + certificate (signed by the CA) for each service
#
# Each service certificate is valid for localhost, 127.0.0.1 and the docker-compose hostnames <svc>-1 / <svc>-2.
# Usage: ./scripts/generate-dev-certs.sh            (password defaults to "changeit", override with SSL_PASSWORD)
set -euo pipefail

OUT="$(cd "$(dirname "$0")/.." && pwd)/certs"
PASS="${SSL_PASSWORD:-changeit}"
SERVICES=(flightops aircraft airports)

mkdir -p "$OUT"
cd "$OUT"
rm -f ./*.p12 ./*.crt ./*.csr

echo "-> CA"
keytool -genkeypair -alias ca -keyalg RSA -keysize 2048 -validity 3650 \
  -dname "CN=AISafe Dev CA, O=ISEP SIDIS" -ext bc:c \
  -keystore ca.p12 -storetype PKCS12 -storepass "$PASS"
keytool -exportcert -alias ca -keystore ca.p12 -storepass "$PASS" -rfc -file ca.crt
keytool -importcert -noprompt -alias aisafe-ca -file ca.crt \
  -keystore truststore.p12 -storetype PKCS12 -storepass "$PASS"

for svc in "${SERVICES[@]}"; do
  echo "-> $svc"
  keytool -genkeypair -alias "$svc" -keyalg RSA -keysize 2048 -validity 825 \
    -dname "CN=$svc, O=ISEP SIDIS" \
    -keystore "$svc.p12" -storetype PKCS12 -storepass "$PASS"
  keytool -certreq -alias "$svc" -keystore "$svc.p12" -storepass "$PASS" -file "$svc.csr"
  keytool -gencert -alias ca -keystore ca.p12 -storepass "$PASS" -rfc -validity 825 \
    -infile "$svc.csr" -outfile "$svc.crt" \
    -ext "SAN=dns:localhost,ip:127.0.0.1,dns:$svc-1,dns:$svc-2" \
    -ext "KU=digitalSignature,keyEncipherment" -ext "EKU=serverAuth,clientAuth"
  # install the chain (CA first, then the signed certificate as reply to the key entry)
  keytool -importcert -noprompt -alias aisafe-ca -file ca.crt -keystore "$svc.p12" -storepass "$PASS"
  keytool -importcert -noprompt -alias "$svc" -file "$svc.crt" -keystore "$svc.p12" -storepass "$PASS"
  rm -f "$svc.csr"
done

echo "Done. Files in $OUT"
