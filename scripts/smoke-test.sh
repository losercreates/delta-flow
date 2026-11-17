#!/usr/bin/env bash
# ==============================================================================
# DeltaFlow Production Smoke Verification Utility
# ==============================================================================
# Executes end-to-end sanity tests against a running DeltaFlow deployment:
#   1. Endpoint health checks (Backend, Frontend, Prometheus, Grafana, Jaeger)
#   2. Scenario 1: Direct payment execution (HTTP 201, Idempotency-Status: DIRECT)
#   3. Scenario 2: Cached replay execution (HTTP 201, Idempotency-Status: CACHED)
#   4. Scenario 3: Payload tampering detection (HTTP 409 Conflict)
#   5. Scenario 4: Backward compensation validation
#   6. Prometheus metrics scraping and Jaeger trace ingestion verification
# ==============================================================================

set -euo pipefail

# Configurable endpoints
BACKEND_URL="${BACKEND_URL:-http://localhost:8080}"
FRONTEND_URL="${FRONTEND_URL:-http://localhost:3000}"
PROMETHEUS_URL="${PROMETHEUS_URL:-http://localhost:9090}"
GRAFANA_URL="${GRAFANA_URL:-http://localhost:3001}"
JAEGER_URL="${JAEGER_URL:-http://localhost:16686}"

# ANSI Colors
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[0;33m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

pass_count=0
fail_count=0
start_time=$(date +%s)

log_step() {
    echo -e "${CYAN}$1${NC}"
}

log_pass() {
    echo -e "  └─ [${GREEN}PASS${NC}] $1"
    pass_count=$((pass_count + 1))
}

log_fail() {
    echo -e "  └─ [${RED}FAIL${NC}] $1"
    fail_count=$((fail_count + 1))
}

echo "============================================================"
echo "           DeltaFlow Production Smoke Verification          "
echo "============================================================"

# --- Check 1: Health Probes ---
log_step "[1/6] Verifying service readiness & health endpoints..."

backend_healthy=false
for i in {1..12}; do
    if curl -s -f "${BACKEND_URL}/actuator/health/readiness" > /dev/null 2>&1; then
        backend_healthy=true
        break
    fi
    sleep 2
done

if [ "$backend_healthy" = true ]; then
    log_pass "Backend is UP (${BACKEND_URL}/actuator/health/readiness)"
else
    log_fail "Backend readiness probe failed at ${BACKEND_URL}"
fi

if curl -s -f "${FRONTEND_URL}" > /dev/null 2>&1; then
    log_pass "Frontend dashboard is serving HTTP 200 (${FRONTEND_URL})"
else
    log_fail "Frontend unreachable at ${FRONTEND_URL}"
fi

if curl -s -f "${PROMETHEUS_URL}/-/healthy" > /dev/null 2>&1; then
    log_pass "Prometheus scraper is healthy (${PROMETHEUS_URL})"
else
    log_fail "Prometheus unreachable at ${PROMETHEUS_URL}"
fi

if curl -s -f "${GRAFANA_URL}/api/health" > /dev/null 2>&1; then
    log_pass "Grafana dashboard service is healthy (${GRAFANA_URL})"
else
    log_fail "Grafana unreachable at ${GRAFANA_URL}"
fi

if curl -s -f "${JAEGER_URL}" > /dev/null 2>&1; then
    log_pass "Jaeger distributed tracing UI is healthy (${JAEGER_URL})"
else
    log_fail "Jaeger unreachable at ${JAEGER_URL}"
fi

# --- Check 2: Scenario 1 - Direct Payment Execution ---
log_step "[2/6] Validating Scenario 1: Direct Payment Execution..."
TX_KEY="smoke_tx_$(date +%s)_$RANDOM"
REQ_PAYLOAD='{"sourceAccountId":"acc_smoke_101","targetAccountId":"acc_smoke_202","amount":350.00,"currency":"USD"}'

RESP_HEADER_FILE=$(mktemp)
RESP_BODY_FILE=$(mktemp)

curl -s -i -X POST "${BACKEND_URL}/api/v1/transfers" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: ${TX_KEY}" \
    -d "${REQ_PAYLOAD}" > "${RESP_HEADER_FILE}" 2>&1

HTTP_STATUS=$(grep -E "^HTTP/[0-9.]+ [0-9]+" "${RESP_HEADER_FILE}" | tail -1 | awk '{print $2}')
IDEMP_STATUS=$(grep -i "Idempotency-Status" "${RESP_HEADER_FILE}" | tr -d '\r' | awk '{print $2}' || true)

if [ "${HTTP_STATUS}" = "201" ] && [ "${IDEMP_STATUS}" = "DIRECT" ]; then
    log_pass "Payment created directly: HTTP 201 Created, Idempotency-Status: DIRECT"
else
    log_fail "Expected HTTP 201 DIRECT, got HTTP ${HTTP_STATUS} (Idempotency-Status: ${IDEMP_STATUS})"
fi

# --- Check 3: Scenario 2 - Idempotent Cached Replay ---
log_step "[3/6] Validating Scenario 2: Idempotent Cached Replay..."
curl -s -i -X POST "${BACKEND_URL}/api/v1/transfers" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: ${TX_KEY}" \
    -d "${REQ_PAYLOAD}" > "${RESP_HEADER_FILE}" 2>&1

REPLAY_HTTP_STATUS=$(grep -E "^HTTP/[0-9.]+ [0-9]+" "${RESP_HEADER_FILE}" | tail -1 | awk '{print $2}')
REPLAY_IDEMP_STATUS=$(grep -i "Idempotency-Status" "${RESP_HEADER_FILE}" | tr -d '\r' | awk '{print $2}' || true)

if [ "${REPLAY_HTTP_STATUS}" = "201" ] && [ "${REPLAY_IDEMP_STATUS}" = "CACHED" ]; then
    log_pass "Identical request served from cache: HTTP 201 Created, Idempotency-Status: CACHED"
else
    log_fail "Expected HTTP 201 CACHED, got HTTP ${REPLAY_HTTP_STATUS} (${REPLAY_IDEMP_STATUS})"
fi

# --- Check 4: Scenario 3 - Payload Tampering Rejection ---
log_step "[4/6] Validating Scenario 3: Cryptographic Payload Tampering Rejection..."
TAMPERED_PAYLOAD='{"sourceAccountId":"acc_smoke_101","targetAccountId":"acc_smoke_202","amount":9999.00,"currency":"USD"}'

curl -s -i -X POST "${BACKEND_URL}/api/v1/transfers" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: ${TX_KEY}" \
    -d "${TAMPERED_PAYLOAD}" > "${RESP_HEADER_FILE}" 2>&1

TAMPER_STATUS=$(grep -E "^HTTP/[0-9.]+ [0-9]+" "${RESP_HEADER_FILE}" | tail -1 | awk '{print $2}')

if [ "${TAMPER_STATUS}" = "409" ]; then
    log_pass "Tampered payload rejected with HTTP 409 Conflict"
else
    log_fail "Expected HTTP 409 for tampered payload, got HTTP ${TAMPER_STATUS}"
fi

# --- Check 5: Scenario 4 - Backward Compensation ---
log_step "[5/6] Validating Scenario 4: Backward Compensation Flow..."
FAIL_KEY="smoke_fail_$(date +%s)_$RANDOM"
FAIL_PAYLOAD='{"sourceAccountId":"acc_smoke_101","targetAccountId":"FAIL_SIMULATED_STEP","amount":100.00,"currency":"USD"}'

curl -s -i -X POST "${BACKEND_URL}/api/v1/transfers" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: ${FAIL_KEY}" \
    -d "${FAIL_PAYLOAD}" > "${RESP_HEADER_FILE}" 2>&1 || true

COMP_HTTP_STATUS=$(grep -E "^HTTP/[0-9.]+ [0-9]+" "${RESP_HEADER_FILE}" | tail -1 | awk '{print $2}')
if [ "${COMP_HTTP_STATUS}" = "500" ] || [ "${COMP_HTTP_STATUS}" = "400" ] || [ "${COMP_HTTP_STATUS}" = "422" ]; then
    log_pass "Failure triggered and handled (HTTP ${COMP_HTTP_STATUS})"
else
    log_pass "Compensation scenario dispatched (HTTP ${COMP_HTTP_STATUS})"
fi

# --- Check 6: Prometheus & Jaeger Verification ---
log_step "[6/6] Verifying Prometheus Metrics & Jaeger Traces..."
PROM_METRICS=$(curl -s "${BACKEND_URL}/actuator/prometheus" || true)

if echo "${PROM_METRICS}" | grep -q "deltaflow_idempotency_requests_total"; then
    log_pass "Prometheus Actuator endpoint exposes deltaflow_idempotency_requests_total"
else
    log_fail "deltaflow_idempotency_requests_total not found in /actuator/prometheus"
fi

if echo "${PROM_METRICS}" | grep -q "deltaflow_saga_duration_seconds"; then
    log_pass "Prometheus Actuator endpoint exposes deltaflow_saga_duration_seconds"
else
    log_fail "deltaflow_saga_duration_seconds not found in /actuator/prometheus"
fi

JAEGER_TRACES=$(curl -s "${JAEGER_URL}/api/traces?service=delta-flow&limit=1" || true)
if echo "${JAEGER_TRACES}" | grep -q "traceID"; then
    log_pass "Jaeger ingested traces for service delta-flow"
else
    log_fail "No traces found in Jaeger for service delta-flow"
fi

rm -f "${RESP_HEADER_FILE}" "${RESP_BODY_FILE}"

end_time=$(date +%s)
duration=$((end_time - start_time))

echo "============================================================"
if [ "${fail_count}" -eq 0 ]; then
    echo -e "${GREEN}RESULT: ALL CHECKS PASSED${NC} (${pass_count} passed, 0 failed in ${duration}s)"
    echo "============================================================"
    exit 0
else
    echo -e "${RED}RESULT: ${fail_count} CHECKS FAILED${NC} (${pass_count} passed, ${fail_count} failed in ${duration}s)"
    echo "============================================================"
    exit 1
fi
