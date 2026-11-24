# Tasks: Enterprise Security, Multi-Tenancy RBAC & Distributed Rate Limiting

**Input**: Design documents from `specs/011-security-rbac-ratelimit/` (`plan.md`, `spec.md`, `research.md`, `data-model.md`, `contracts/security-rbac-api.yaml`, `quickstart.md`)  
**Prerequisites**: `plan.md`, `spec.md`, `data-model.md`, `contracts/`  
**Constitution Alignment**: Principle I (Zero Double-Spend), Principle II (Strict Backward Compensation), Principle III (Compile-Time Exhaustion & Type Safety), Principle IV (TDD & Testcontainers Verification), Principle V (Immutable Audit Trail).  

## Format: `- [ ] [TaskID] [P?] [Story?] Description with file path`

- **[P]**: Can run in parallel (independent files and dependencies)
- **[Story]**: User Story label ([US1], [US2], [US3], [US4])

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Establish security dependencies in Maven build, Flyway migration for API keys, and shared TypeScript interfaces.

- [X] T001 [P] Add Spring Security and JJWT dependencies (`spring-boot-starter-security`, `io.jsonwebtoken:jjwt-api:0.12.6`, `jjwt-impl:0.12.6`, `jjwt-jackson:0.12.6`, `spring-security-test`) in `pom.xml`
- [X] T002 [P] Create Flyway migration `V7__tenant_api_keys.sql` in `src/main/resources/db/migration/V7__tenant_api_keys.sql` creating `tenant_api_keys` table, partial index on `hashed_key`, and default test machine credentials
- [X] T003 [P] Create frontend TypeScript definitions (`AuthLoginResponse`, `CurrentUserProfileDto`, `TenantRateLimitStatusDto`) in `frontend/src/types/security.ts`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core domain entities, repositories, and DTO structures required before user story security logic can execute.

- [X] T004 Create security domain records and enums (`SecurityPrincipal.java`, `AuthenticationType.java`) in `src/main/java/io/deltaflow/security/domain/`
- [X] T005 [P] Create JPA entity `TenantApiKey.java` in `src/main/java/io/deltaflow/security/domain/TenantApiKey.java` mapping to `tenant_api_keys`
- [X] T006 [P] Create `TenantApiKeyRepository.java` in `src/main/java/io/deltaflow/security/repository/TenantApiKeyRepository.java` with `findByHashedKeyAndEnabledTrue`
- [X] T007 [P] Create authentication and rate limit DTO records (`AuthLoginRequest.java`, `AuthLoginResponse.java`, `CurrentUserProfileDto.java`, `TenantRateLimitStatusDto.java`) in `src/main/java/io/deltaflow/security/dto/`

**Checkpoint**: Foundational entities and dependencies ready — user story implementation can begin.

---

## Phase 3: User Story 1 - Stateless Authentication & Dual-Mode Credentials (Priority: P1) 🎯 MVP

**Goal**: Authenticate requests via stateless JWT bearer tokens for operators and constant-time API keys (`X-API-Key`) for machine services; reject unauthenticated calls with HTTP 401.

**Independent Test**: Submit requests with no token, expired token, valid JWT, and valid API key. Verify that unauthenticated requests receive HTTP 401 Unauthorized, while valid credentials establish identity and tenant context.

### Tests for User Story 1 ⚠️
- [X] T008 [P] [US1] Create integration test `AuthenticationIntegrationTest.java` in `src/test/java/io/deltaflow/security/AuthenticationIntegrationTest.java` verifying 401 on missing/expired tokens, valid JWT authentication, and constant-time API-key verification

### Implementation for User Story 1
- [X] T009 [US1] Implement `JwtTokenService.java` in `src/main/java/io/deltaflow/security/service/JwtTokenService.java` for signing and validating HMAC-SHA256 tokens with claims (`sub`, `tenantId`, `roles`) and clock skew tolerance
- [X] T010 [US1] Implement `ApiKeyAuthenticationService.java` in `src/main/java/io/deltaflow/security/service/ApiKeyAuthenticationService.java` using constant-time `MessageDigest.isEqual` comparison against `TenantApiKeyRepository`
- [X] T011 [US1] Implement `JwtAuthenticationFilter.java` and `ApiKeyAuthenticationFilter.java` in `src/main/java/io/deltaflow/security/filter/` establishing Spring `SecurityContext`
- [X] T012 [US1] Implement `SecurityConfig.java` in `src/main/java/io/deltaflow/security/config/SecurityConfig.java` with stateless `SecurityFilterChain`, custom `AuthenticationEntryPoint` returning structured 401 JSON, and filter ordering
- [X] T013 [US1] Implement `AuthApiController.java` in `src/main/java/io/deltaflow/security/api/AuthApiController.java` exposing `/api/v1/auth/login` and `/api/v1/auth/me`

**Checkpoint**: User Story 1 is fully functional and testable independently (MVP ready).

---

## Phase 4: User Story 2 - Role-Based Access Control (RBAC) & Privilege Separation (Priority: P1) 🎯 MVP

**Goal**: Enforce role hierarchy (`ROLE_ADMIN` > `ROLE_OPERATOR` > `ROLE_VIEWER`, plus `ROLE_SERVICE`) across all endpoints; reject unauthorized operations with HTTP 403 and audit logs.

**Independent Test**: Authenticate as `ROLE_VIEWER`, `ROLE_OPERATOR`, `ROLE_SERVICE`, and `ROLE_ADMIN`. Attempt operational actions across each role tier; verify that `ROLE_VIEWER` cannot intervene in sagas or inject chaos, `ROLE_OPERATOR` can intervene but cannot inject chaos, and `ROLE_ADMIN` can access all functions.

### Tests for User Story 2 ⚠️
- [X] T014 [P] [US2] Create integration test `RbacAuthorizationIntegrationTest.java` in `src/test/java/io/deltaflow/security/RbacAuthorizationIntegrationTest.java` verifying HTTP 403 Forbidden on role violations and audit event emission

### Implementation for User Story 2
- [X] T015 [US2] Configure endpoint authorization matchers and `@EnableMethodSecurity` in `SecurityConfig.java` in `src/main/java/io/deltaflow/security/config/SecurityConfig.java` protecting `/api/v1/chaos/**` (ADMIN), `/api/v1/cluster/retention/trigger` (ADMIN), `/api/v1/dashboard/sagas/{id}/intervene` (OPERATOR/ADMIN), `/api/v1/transfers` (SERVICE/ADMIN)
- [X] T016 [US2] Implement custom `AccessDeniedHandler` in `SecurityConfig.java` that logs `SECURITY_ACCESS_DENIED` via `AuditLogger` and returns structured 403 JSON

**Checkpoint**: User Story 2 is fully functional and testable independently.

---

## Phase 5: User Story 3 - Multi-Tenant Distributed Token-Bucket Rate Limiting (Priority: P2)

**Goal**: Prevent noisy-neighbor starvation by enforcing Redis-backed token-bucket rate limits per `tenantId` via Redisson; return HTTP 429 with standard headers upon quota breach.

**Independent Test**: Flood requests from a tenant exceeding their quota; verify HTTP 429 Too Many Requests with `X-RateLimit-*` and `Retry-After` headers; concurrently verify that a co-hosted tenant proceeds with HTTP 200 without degradation.

### Tests for User Story 3 ⚠️
- [X] T017 [P] [US3] Create integration test `TenantRateLimitingIntegrationTest.java` in `src/test/java/io/deltaflow/security/TenantRateLimitingIntegrationTest.java` verifying 429 on quota exhaustion, RFC headers, and zero cross-tenant quota bleed

### Implementation for User Story 3
- [X] T018 [US3] Implement `TenantRateLimiterService.java` in `src/main/java/io/deltaflow/security/service/TenantRateLimiterService.java` using Redisson `RRateLimiter` per tenant with configurable burst rates
- [X] T019 [US3] Implement `TenantRateLimitingFilter.java` in `src/main/java/io/deltaflow/security/filter/TenantRateLimitingFilter.java` intercepting requests, returning 429 with `X-RateLimit-*` headers, and emitting `TENANT_RATE_LIMITED` audit logs
- [X] T020 [US3] Expose `/api/v1/auth/rate-limit/status` endpoint in `AuthApiController.java` returning current tenant token quota

**Checkpoint**: User Story 3 is fully functional and testable independently.

---

## Phase 6: User Story 4 - Operator Dashboard Session Handling & Dynamic Role-Based UI Controls (Priority: P2)

**Goal**: Expose operator session management in the React dashboard, render active role badges, and dynamically lock or hide destructive controls based on granted roles.

**Independent Test**: Log into the Operator Dashboard as a Viewer; verify that Chaos Injection forms and DLQ Replay buttons are locked with permission tooltips. Switch sessions to an Admin; verify that all controls become active.

### Implementation for User Story 4
- [X] T021 [P] [US4] Create frontend API client in `frontend/src/services/authApi.ts` for login, current user profile, and rate limit status
- [X] T022 [P] [US4] Implement `AuthContext.tsx` in `frontend/src/context/AuthContext.tsx` providing authentication state, token persistence in `sessionStorage`, request authorization headers interceptor, and `hasRole()` helper
- [X] T023 [P] [US4] Implement `OperatorLoginModal.tsx` in `frontend/src/components/auth/OperatorLoginModal.tsx` for operator credential login, role selection, and session logout
- [X] T024 [US4] Update `frontend/src/App.tsx` with `AuthProvider`, login trigger modal, and session status indicator
- [X] T025 [US4] Update `frontend/src/components/chaos/ChaosControlDashboard.tsx`, `frontend/src/components/cluster/ClusterTopologyDashboard.tsx`, and `frontend/src/components/dag/SagaFlowDag.tsx` to disable/lock destructive controls (Chaos Inject, DLQ Replay, Retention Purge) for users lacking required roles

**Checkpoint**: User Story 4 is fully functional and testable independently.

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: Unify dashboard header indicators, execute end-to-end verification, and run full regression tests.

- [X] T026 Update `frontend/src/components/telemetry/TelemetryHeader.tsx` to display authenticated user badge, active role, and tenant rate limit indicator
- [X] T027 Execute end-to-end verification scenario per `quickstart.md` and run full regression test suite `mvn test -B`
- [X] T028 Verify frontend production build with `npm run build` in `frontend/` directory

---

## Dependencies & Execution Order

```
[Phase 1: Setup (T001-T003)]
       │
       ▼
[Phase 2: Foundational (T004-T007)]
       │
       ├────────────────────────┬────────────────────────┐
       ▼                        ▼                        ▼
[Phase 3: US1 Auth MVP]   [Phase 4: US2 RBAC MVP]   [Phase 5: US3 Rate Limit]
(T008-T013)               (T014-T016)               (T017-T020)
       │                        │                        │
       └────────────────────────┼────────────────────────┘
                                │
                                ▼
                     [Phase 6: US4 Dashboard Security]
                     (T021-T025)
                                │
                                ▼
                     [Phase 7: Polish & Regression]
                     (T026-T028)
```

---

## Implementation Strategy

### MVP First (User Stories 1 & 2)
1. Complete Phase 1: Setup (`pom.xml`, Flyway `V7`, TypeScript interfaces)
2. Complete Phase 2: Foundational (`SecurityPrincipal`, `TenantApiKey`, DTOs)
3. Complete Phase 3: User Story 1 (Stateless JWT + API-Key Authentication)
4. Complete Phase 4: User Story 2 (RBAC Role Hierarchy & 403 Protection)
5. **Checkpoint**: DeltaFlow is fully protected by enterprise authentication and RBAC.

### Incremental Delivery (User Stories 3 & 4)
6. Add User Story 3 (Distributed Redis-backed Rate Limiter & HTTP 429 response)
7. Add User Story 4 (Operator Dashboard Session Modal & Dynamic UI Locks)
8. Run full regression test suite `mvn test -B` and frontend build `npm run build`
