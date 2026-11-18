# Quickstart: Event-Driven Messaging & Asynchronous Webhook Sagas

**Feature Branch**: `008-async-webhook-sagas`  
**Date**: 2026-11-18  
**Spec Reference**: [spec.md](./spec.md) | [data-model.md](./data-model.md)

---

## Overview

This guide walks through validating the four core scenarios of Milestone 8:
1. Initiating a long-running saga with an asynchronous waiting step (`SUSPENDED`).
2. Delivering a cryptographically signed HMAC-SHA256 webhook to resume forward execution.
3. Attempting to deliver a forged/tampered webhook signature (rejected with HTTP 401).
4. Simulating a timeout deadline lapse and verifying automatic backward compensation.
5. Verifying Transactional Outbox Kafka topic streaming and Dead-Letter Queue (DLQ) routing.

---

## 1. Scenario 1: Initiate Long-Running Saga & Verify Suspension

Submit an account-to-account transfer requiring 3D-Secure customer authentication:

```bash
TX_KEY="async_tx_$(date +%s)"
curl -s -i -X POST http://localhost:8080/api/v1/transfers \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: ${TX_KEY}" \
  -H "X-Require-3DS: true" \
  -d '{
    "sourceAccountId": "acc_cust_101",
    "targetAccountId": "acc_merchant_505",
    "amount": 1250.00,
    "currency": "USD"
  }'
```

**Expected Response**: `HTTP 202 Accepted`
```json
{
  "sagaId": "c4b92b67-f31a-4d22-8692-a1b72e50c410",
  "status": "SUSPENDED",
  "currentStep": "3ds-customer-verification",
  "awaitingEvent": "3ds-verification",
  "stepDeadline": "2026-11-18T13:45:00Z"
}
```

**Resource Verification**:
- Check Redis locks: `redis-cli keys "*"` $\to$ no lock is held on the transaction or saga key.
- Check threads: Worker virtual thread has terminated; carrier threads are free.

---

## 2. Scenario 2: Deliver Cryptographic Webhook to Resume Saga

Generate the HMAC-SHA256 signature for the callback:
```bash
SAGA_ID="c4b92b67-f31a-4d22-8692-a1b72e50c410"
TIMESTAMP=$(date +%s000)
SECRET="deltaflow-webhook-secret-key-default"
BODY='{"status":"APPROVED","providerReference":"ch_3N8cXYZ","data":{"cavv":"AAABBBCCC==","eci":"05"}}'

SIGNATURE=$(echo -n "${TIMESTAMP}.${BODY}" | openssl dgst -sha256 -hmac "${SECRET}" | awk '{print $2}')

curl -s -i -X POST "http://localhost:8080/api/v1/sagas/${SAGA_ID}/events/3ds-verification" \
  -H "Content-Type: application/json" \
  -H "X-Webhook-Signature: ${SIGNATURE}" \
  -H "X-Webhook-Timestamp: ${TIMESTAMP}" \
  -H "Idempotency-Key: wh_${SAGA_ID}_3ds" \
  -d "${BODY}"
```

**Expected Response**: `HTTP 200 OK`
```json
{
  "sagaId": "c4b92b67-f31a-4d22-8692-a1b72e50c410",
  "eventName": "3ds-verification",
  "status": "RESUMED",
  "processedAt": "2026-11-18T13:30:15Z"
}
```

**State Verification**:
- Check saga status via API:
  `curl -s http://localhost:8080/api/v1/sagas/${SAGA_ID}` $\to$ status is `COMPLETED`.
- Forward steps `credit-step` and `ledger-step` have executed to terminal completion.

---

## 3. Scenario 3: Reject Forged or Tampered Webhook Signature

Submit a callback with an invalid signature:

```bash
curl -s -i -X POST "http://localhost:8080/api/v1/sagas/${SAGA_ID}/events/3ds-verification" \
  -H "Content-Type: application/json" \
  -H "X-Webhook-Signature: invalid_forged_signature_000000" \
  -H "X-Webhook-Timestamp: ${TIMESTAMP}" \
  -d "${BODY}"
```

**Expected Response**: `HTTP 401 Unauthorized`
```json
{
  "timestamp": "2026-11-18T13:30:20Z",
  "status": 401,
  "error": "Unauthorized",
  "message": "Invalid HMAC-SHA256 webhook signature"
}
```

---

## 4. Scenario 4: Asynchronous Timeout Expiration & Automatic LIFO Compensation

Submit a transfer with a short 5-second timeout and let it expire:

```bash
TIMEOUT_KEY="timeout_tx_$(date +%s)"
curl -s -X POST http://localhost:8080/api/v1/transfers \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: ${TIMEOUT_KEY}" \
  -H "X-Require-3DS: true" \
  -H "X-3DS-Timeout-Seconds: 5" \
  -d '{
    "sourceAccountId": "acc_cust_202",
    "targetAccountId": "acc_merchant_505",
    "amount": 500.00,
    "currency": "USD"
  }'
```

Wait 6 seconds for the `SagaRecoveryScanner` sweep:
```bash
sleep 6
curl -s http://localhost:8080/api/v1/sagas/by-key/${TIMEOUT_KEY}
```

**Expected Outcome**:
- Saga status has automatically transitioned: `SUSPENDED` $\to$ `TIMED_OUT` $\to$ `COMPENSATED`.
- Debit compensation executed: Source account `acc_cust_202` has received a full refund of \$500.00.
- Outbox event `SAGA_COMPENSATED` is recorded in the relational WAL.

---

## 5. Scenario 5: Inspect Kafka Outbox Streaming & DLQ

Verify Kafka topic events using Kafka console consumer or Testcontainers consumer:

```bash
# Verify published event contains W3C traceparent and aggregate partitioning
docker exec -it deltaflow-kafka /bin/kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic deltaflow.domain-events \
  --from-beginning \
  --property print.headers=true \
  --property print.key=true
```

**Expected Output**:
```text
traceparent:00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01,X-Event-Type:SAGA_COMPLETED	c4b92b67-f31a-4d22-8692-a1b72e50c410	{"eventId":"...","eventType":"SAGA_COMPLETED","aggregateId":"c4b92b67-f31a-4d22-8692-a1b72e50c410"}
```
