# Quickstart & Verification Scenarios: Enterprise Security, Multi-Tenancy RBAC & Distributed Rate Limiting

This guide details four end-to-end verification scenarios to validate authentication, RBAC authorization, and multi-tenant rate limiting.

---

## Scenario 1: Unauthenticated & Unauthorized Rejection (401 & 403)

### 1.1 Unauthenticated Request Rejection
```bash
# Attempt to access protected dashboard telemetry without credentials
curl -i -X GET http://localhost:8080/api/v1/dashboard/telemetry

# Expected Response: HTTP 401 Unauthorized
# WWW-Authenticate: Bearer error="unauthorized"
# {"error": "Unauthorized", "message": "Authentication credentials missing or invalid"}
```

### 1.2 Privilege Escalation Rejection (ROLE_VIEWER -> Chaos Injection)
```bash
# 1. Log in as a VIEWER operator
TOKEN_VIEWER=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"viewer","password":"password123","tenantId":"tenant-alpha"}' | jq -r .token)

# 2. Attempt to inject latency into payment-gateway (requires ROLE_ADMIN)
curl -i -X POST http://localhost:8080/api/v1/chaos/inject \
  -H "Authorization: Bearer $TOKEN_VIEWER" \
  -H "Content-Type: application/json" \
  -d '{"proxyName":"payment-gateway","toxicType":"LATENCY","latency":500,"jitter":50,"operatorId":"viewer","justification":"Test"}'

# Expected Response: HTTP 403 Forbidden
# {"error": "Forbidden", "message": "Access Denied: Requires ROLE_ADMIN"}
```

---

## Scenario 2: Administrative Access & Forensic Intervention (ROLE_ADMIN)

```bash
# 1. Log in as an ADMIN operator
TOKEN_ADMIN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"password123","tenantId":"tenant-alpha"}' | jq -r .token)

# 2. Check current identity profile
curl -s -X GET http://localhost:8080/api/v1/auth/me \
  -H "Authorization: Bearer $TOKEN_ADMIN" | jq .

# Expected Response:
# {
#   "principalId": "admin",
#   "tenantId": "tenant-alpha",
#   "roles": ["ROLE_ADMIN"],
#   "authType": "JWT_BEARER"
# }

# 3. Trigger on-demand outbox lease reclamation (requires ROLE_ADMIN)
curl -i -X POST http://localhost:8080/api/v1/cluster/outbox/reclaim-leases \
  -H "Authorization: Bearer $TOKEN_ADMIN"

# Expected Response: HTTP 200 OK
```

---

## Scenario 3: Machine-to-Machine API-Key Transaction Ingress

```bash
# Submit a payment transfer using a pre-shared API Key
curl -i -X POST http://localhost:8080/api/v1/transfers \
  -H "X-API-Key: df_live_tenant_alpha_secret_key_12345" \
  -H "Idempotency-Key: transfer-sec-001" \
  -H "Content-Type: application/json" \
  -d '{
    "tenantId": "tenant-alpha",
    "senderAccountId": "acc-sender-1",
    "recipientAccountId": "acc-recipient-2",
    "amount": 250.00,
    "currency": "USD"
  }'

# Expected Response: HTTP 201 Created
# Idempotency-Status: DIRECT
```

---

## Scenario 4: Multi-Tenant Token-Bucket Rate Limiting (HTTP 429)

```bash
# 1. Flood requests from low-tier tenant (configured limit: 5 requests/sec, burst: 10)
for i in {1..15}; do
  curl -s -o /dev/null -w "%{http_code}\n" -X GET http://localhost:8080/api/v1/dashboard/telemetry \
    -H "Authorization: Bearer $TOKEN_LOW_TIER"
done

# Expected Output:
# 200
# 200
# ...
# 429
# 429

# Inspect rate limit response headers on throttled request
# HTTP/1.1 429 Too Many Requests
# X-RateLimit-Limit: 10
# X-RateLimit-Remaining: 0
# X-RateLimit-Reset: 840
# Retry-After: 1
```
