# Quickstart & Verification Guide: Milestone 1 - Foundations & Distributed Idempotency Gate

**Feature**: Milestone 1 - Foundations & Distributed Idempotency Gate  
**Branch**: `001-idempotency-gate`  
**Date**: 2026-11-08  
**Status**: Ready  

---

## 1. Prerequisites

- **Java**: OpenJDK 21 LTS installed (`java -version`).
- **Build Tool**: Maven 3.9+ or Gradle 8.5+.
- **Docker**: Docker daemon running (used automatically by Testcontainers for PostgreSQL and Redis).
- **HTTP Client**: `curl` or `httpie`.

---

## 2. Environment Setup

DeltaFlow runs against containerized PostgreSQL and Redis. For local verification, launch the development dependencies:

```bash
# Start PostgreSQL (port 5432) and Redis (port 6379)
docker run -d --name deltaflow-postgres -e POSTGRES_DB=deltaflow -e POSTGRES_USER=postgres -e POSTGRES_PASSWORD=postgres -p 5432:5432 postgres:16-alpine
docker run -d --name deltaflow-redis -p 6379:6379 redis:7-alpine

# Start the Spring Boot application
./mvnw spring-boot:run
```

---

## 3. End-to-End Validation Scenarios

These four scenarios validate all functional requirements and acceptance criteria defined in [spec.md](./spec.md).

### Scenario 1: Initial Payment Request (Cache Miss & Execution)

Execute a payment transfer with an original `Idempotency-Key`:

```bash
curl -i -X POST http://localhost:8080/api/v1/payments \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: pay_demo_001" \
  -d '{
    "sourceAccountId": "acc_1001",
    "targetAccountId": "acc_2002",
    "amount": 500.00,
    "currency": "USD"
  }'
```

**Expected Outcome**:
- Status: `HTTP/1.1 201 Created`
- Header: `Idempotency-Status: DIRECT`
- Body: Contains generated transaction UUID and `"status": "COMPLETED"`.

---

### Scenario 2: Identical Replay (Cache Hit & Zero Downstream Re-execution)

Immediately resend the exact same HTTP request using the same key and payload:

```bash
curl -i -X POST http://localhost:8080/api/v1/payments \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: pay_demo_001" \
  -d '{
    "sourceAccountId": "acc_1001",
    "targetAccountId": "acc_2002",
    "amount": 500.00,
    "currency": "USD"
  }'
```

**Expected Outcome**:
- Status: `HTTP/1.1 201 Created`
- Header: `Idempotency-Status: CACHED`
- Latency: Sub-15ms response served from cache.
- Verification: Downstream payment service log confirms 0 calls dispatched.

---

### Scenario 3: Payload Tampering Detection (Hash Mismatch)

Send a request using the previous key `pay_demo_001`, but alter the amount to `$999.00`:

```bash
curl -i -X POST http://localhost:8080/api/v1/payments \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: pay_demo_001" \
  -d '{
    "sourceAccountId": "acc_1001",
    "targetAccountId": "acc_2002",
    "amount": 999.00,
    "currency": "USD"
  }'
```

**Expected Outcome**:
- Status: `HTTP/1.1 409 Conflict`
- Body:
  ```json
  {
    "errorCode": "IDEMPOTENCY_KEY_PAYLOAD_MISMATCH",
    "message": "Payload SHA-256 digest does not match the original request for this idempotency key.",
    "idempotencyKey": "pay_demo_001"
  }
  ```

---

### Scenario 4: Concurrent Collision & Locking Gate

Simulate 20 concurrent requests with the identical key using `xargs`:

```bash
seq 1 20 | xargs -n1 -P20 -I{} curl -s -o /dev/null -w "%{http_code}\n" -X POST http://localhost:8080/api/v1/payments \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: pay_concurrent_test" \
  -d '{"sourceAccountId": "acc_1001", "targetAccountId": "acc_2002", "amount": 50.00, "currency": "USD"}'
```

**Expected Outcome**:
- All 20 requests return `201 Created` (with the primary acquiring lock and remaining contenders receiving cached response upon resolution), or `409 Conflict` (`IDEMPOTENCY_KEY_IN_FLIGHT`) if wait budget expires.
- **Invariance**: Database audit log confirms exactly 1 insertion and 1 downstream execution.

---

## 4. Automated Verification Commands

Run the full Testcontainers integration test suite:

```bash
./mvnw test -Dtest=IdempotencyGateIntegrationTest,DistributedLockConcurrencyTest
```

