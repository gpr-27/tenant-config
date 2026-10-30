#!/usr/bin/env bash
# Write throughput: hey against POST /changes for 30 s with 20 workers.
# Needs `hey` (brew install hey) and a running server on localhost:8080.
# No Idempotency-Key header on purpose: hey repeats the same headers on every
# request, and one shared key would turn the run into 1 write + replays.
set -euo pipefail

BASE=${BASE:-http://localhost:8080}
TOKEN=$(curl -s -X POST "$BASE/api/v1/auth/token" \
  -H 'Content-Type: application/json' \
  -d '{"username":"acme-ops","password":"ops"}' | sed -E 's/.*"token":"([^"]+)".*/\1/')

BODY='{"reason":"bench","operations":[{"op":"SET","level":"OFFICE","scopeId":"blr-bench","key":"booking.cutoff","value":"PT45M"}]}'

hey -z 30s -c 20 -m POST \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d "$BODY" \
  "$BASE/api/v1/tenants/acme/changes"
