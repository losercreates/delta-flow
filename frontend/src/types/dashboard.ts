export type SagaStatus =
  | 'PENDING'
  | 'EXECUTING'
  | 'COMPLETED'
  | 'COMPENSATING'
  | 'COMPENSATED'
  | 'FAILED';

export type StepStatus =
  | 'NOT_STARTED'
  | 'EXECUTING'
  | 'COMPLETED'
  | 'COMPENSATING'
  | 'COMPENSATED'
  | 'FAILED';

export type InterventionAction = 'FORCE_COMPENSATE' | 'FORCE_RETRY';

export type EdgeType = 'FORWARD' | 'PARALLEL_FORK' | 'PARALLEL_JOIN' | 'COMPENSATION';

export type CircuitBreakerState = 'CLOSED' | 'HALF_OPEN' | 'OPEN';

export type LiveEventType =
  | 'SAGA_STARTED'
  | 'SAGA_STATE_CHANGED'
  | 'STEP_STATE_CHANGED'
  | 'SAGA_COMPENSATED'
  | 'OUTBOX_PUBLISHED'
  | 'OUTBOX_DEAD_LETTER'
  | 'CIRCUIT_BREAKER_TRIPPED'
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
