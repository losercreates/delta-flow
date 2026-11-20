export type SagaStatus =
  | 'PENDING'
  | 'EXECUTING'
  | 'SUSPENDED'
  | 'COMPLETED'
  | 'COMPENSATING'
  | 'COMPENSATED'
  | 'FAILED'
  | 'TIMED_OUT';

export type StepStatus =
  | 'NOT_STARTED'
  | 'EXECUTING'
  | 'SUSPENDED'
  | 'COMPLETED'
  | 'COMPENSATING'
  | 'COMPENSATED'
  | 'FAILED'
  | 'TIMED_OUT';

export type InterventionAction = 'FORCE_COMPENSATE' | 'FORCE_RETRY';

export type EdgeType = 'FORWARD' | 'PARALLEL_FORK' | 'PARALLEL_JOIN' | 'COMPENSATION';

export type CircuitBreakerState = 'CLOSED' | 'HALF_OPEN' | 'OPEN';

export type LiveEventType =
  | 'SAGA_STARTED'
  | 'SAGA_STATE_CHANGED'
  | 'STEP_STATE_CHANGED'
  | 'SAGA_SUSPENDED'
  | 'SAGA_RESUMED'
  | 'SAGA_TIMED_OUT'
  | 'SAGA_COMPENSATED'
  | 'SAGA_COMPLETED'
  | 'OUTBOX_PUBLISHED'
  | 'OUTBOX_DEAD_LETTER'
  | 'OUTBOX_REPLAYED'
  | 'CIRCUIT_BREAKER_TRIPPED'
  | 'CIRCUIT_BREAKER_RESET'
  | 'CIRCUIT_BREAKER_TRANSITIONED'
  | 'CHAOS_INJECTED'
  | 'CHAOS_HEALED'
  | 'INVARIANT_CHECKED'
  | 'OPERATOR_INTERVENTION';

export interface SagaSummaryDto {
  sagaId: string;
  sagaType: string;
  status: SagaStatus;
  tenantId: string;
  idempotencyKey: string;
  currentStep: number;
  createdAt: string;
  updatedAt: string;
}

export interface SagaNodeDto {
  id: string;
  stepIndex: number;
  stepName: string;
  status: StepStatus;
  isParallel: boolean;
  branchName: string | null;
  fencingToken: number | null;
  requestPayload: string | null;
  responsePayload: string | null;
  errorDetails: string | null;
  startedAt: string | null;
  completedAt: string | null;
}

export interface SagaEdgeDto {
  id: string;
  source: string;
  target: string;
  type: EdgeType | string;
  animated: boolean;
}

export interface SagaGraphDto {
  sagaId: string;
  sagaType: string;
  status: SagaStatus;
  tenantId: string;
  idempotencyKey: string;
  payload: string;
  currentStep: number;
  createdAt: string;
  updatedAt: string;
  nodes: SagaNodeDto[];
  edges: SagaEdgeDto[];
}

export interface OperatorInterventionRequest {
  action: InterventionAction;
  stepIndex?: number | null;
  operatorId: string;
  justification: string;
}

export interface OperatorInterventionResult {
  sagaId: string;
  actionTaken: InterventionAction | string;
  previousStatus: string;
  newStatus: string;
  operatorId: string;
  auditEventId: string;
  timestamp: string;
}

export interface IdempotencyForensicDto {
  idempotencyKey: string;
  tenantId: string;
  status: 'PENDING' | 'PROCESSING' | 'RESOLVED' | 'FAILED' | string;
  payloadHash: string;
  cachedResponse: string | null;
  statusCode: number | null;
  leaseExpiresAt: string | null;
  recoveryAttempts: number;
  createdAt: string;
  updatedAt: string;
}

export interface CircuitBreakerStatusDto {
  name: string;
  state: CircuitBreakerState;
  failureRate: number;
  bufferedCalls: number;
}

export interface TelemetrySummaryDto {
  activeSagas: number;
  completedSagas: number;
  compensatedSagas: number;
  failedSagas: number;
  pendingOutboxEvents: number;
  deadLetterEvents: number;
  circuitBreakers: CircuitBreakerStatusDto[];
}

export interface DashboardLiveEvent {
  eventId: string;
  eventType: LiveEventType;
  sagaId: string;
  tenantId: string;
  timestamp: string;
  payload: Record<string, unknown>;
}

export interface DeadLetterOutboxEventDto {
  eventId: string;
  sagaId: string;
  tenantId: string;
  eventType: string;
  topic: string;
  partitionKey: string;
  payload: string;
  retryCount: number;
  errorDetails: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface DlqReplayRequest {
  operatorId: string;
  justification: string;
}
