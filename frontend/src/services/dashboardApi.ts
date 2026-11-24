import {
  DeadLetterOutboxEventDto,
  DlqReplayRequest,
  IdempotencyForensicDto,
  OperatorInterventionRequest,
  OperatorInterventionResult,
  SagaGraphDto,
  SagaSummaryDto,
  TelemetrySummaryDto,
} from '../types/dashboard';
import { getAuthHeaders } from './authApi';

const BASE_URL = '/api/v1/dashboard';

export async function fetchSagas(
  status?: string,
  tenantId?: string,
  page = 0,
  size = 20
): Promise<SagaSummaryDto[]> {
  const params = new URLSearchParams();
  if (status) params.set('status', status);
  if (tenantId) params.set('tenantId', tenantId);
  params.set('page', page.toString());
  params.set('size', size.toString());

  const response = await fetch(`${BASE_URL}/sagas?${params.toString()}`, {
    headers: { ...getAuthHeaders() },
  });
  if (!response.ok) {
    throw new Error(`Failed to fetch sagas: ${response.status} ${response.statusText}`);
  }
  return response.json();
}

export async function fetchSagaGraph(sagaId: string): Promise<SagaGraphDto> {
  const response = await fetch(`${BASE_URL}/sagas/${sagaId}/graph`, {
    headers: { ...getAuthHeaders() },
  });
  if (!response.ok) {
    throw new Error(`Failed to fetch saga graph for ${sagaId}: ${response.status} ${response.statusText}`);
  }
  return response.json();
}

export async function executeIntervention(
  sagaId: string,
  request: OperatorInterventionRequest
): Promise<OperatorInterventionResult> {
  const response = await fetch(`${BASE_URL}/sagas/${sagaId}/intervene`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...getAuthHeaders(),
    },
    body: JSON.stringify(request),
  });

  if (!response.ok) {
    let errorMsg = `${response.status} ${response.statusText}`;
    try {
      const errorJson = await response.json();
      if (errorJson.message) {
        errorMsg = errorJson.message;
      }
    } catch {
      // ignored
    }
    throw new Error(errorMsg);
  }
  return response.json();
}

export async function searchIdempotency(params: {
  key?: string;
  payloadHash?: string;
  tenantId?: string;
  status?: string;
}): Promise<IdempotencyForensicDto[]> {
  const query = new URLSearchParams();
  if (params.key) query.set('key', params.key);
  if (params.payloadHash) query.set('payloadHash', params.payloadHash);
  if (params.tenantId) query.set('tenantId', params.tenantId);
  if (params.status) query.set('status', params.status);

  const response = await fetch(`${BASE_URL}/idempotency?${query.toString()}`, {
    headers: { ...getAuthHeaders() },
  });
  if (!response.ok) {
    throw new Error(`Failed to search idempotency records: ${response.status} ${response.statusText}`);
  }
  return response.json();
}

export async function fetchTelemetry(): Promise<TelemetrySummaryDto> {
  const response = await fetch(`${BASE_URL}/telemetry`, {
    headers: { ...getAuthHeaders() },
  });
  if (!response.ok) {
    throw new Error(`Failed to fetch telemetry: ${response.status} ${response.statusText}`);
  }
  return response.json();
}

export function getDashboardStreamUrl(): string {
  return `${BASE_URL}/stream`;
}

export async function fetchDeadLetterEvents(page = 0, size = 20): Promise<DeadLetterOutboxEventDto[]> {
  const response = await fetch(`${BASE_URL}/dlq?page=${page}&size=${size}`, {
    headers: { ...getAuthHeaders() },
  });
  if (!response.ok) {
    throw new Error(`Failed to fetch DLQ events: ${response.status} ${response.statusText}`);
  }
  return response.json();
}

export async function replayDeadLetterEvent(
  eventId: string,
  request: DlqReplayRequest
): Promise<DeadLetterOutboxEventDto> {
  const response = await fetch(`${BASE_URL}/dlq/${eventId}/replay`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...getAuthHeaders(),
    },
    body: JSON.stringify(request),
  });
  if (!response.ok) {
    let errorMsg = `${response.status} ${response.statusText}`;
    try {
      const err = await response.json();
      if (err.message) errorMsg = err.message;
    } catch {}
    throw new Error(errorMsg);
  }
  return response.json();
}
