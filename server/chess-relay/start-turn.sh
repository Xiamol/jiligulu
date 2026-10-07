#!/bin/sh
set -eu
# The shared secret stays on the server, never inside the APK or a public config.
exec turnserver --no-cli --fingerprint --use-auth-secret \
  --static-auth-secret="$TURN_SECRET" --realm="$TURN_REALM" \
  --external-ip="$TURN_PUBLIC_IP" --listening-port=3478 --tls-listening-port=443 \
  --cert=/certs/fullchain.pem --pkey=/certs/privkey.pem \
  --min-port=49160 --max-port=49200 --no-tcp-relay \
  --user-quota=4 --total-quota=500 --max-bps=262144 --bps-capacity=10485760 \
  --no-multicast-peers --no-tlsv1 --no-tlsv1_1 \
  --denied-peer-ip=0.0.0.0-0.255.255.255 --denied-peer-ip=10.0.0.0-10.255.255.255 \
  --denied-peer-ip=127.0.0.0-127.255.255.255 --denied-peer-ip=169.254.0.0-169.254.255.255 \
  --denied-peer-ip=172.16.0.0-172.31.255.255 --denied-peer-ip=192.168.0.0-192.168.255.255 \
  --denied-peer-ip=224.0.0.0-255.255.255.255
