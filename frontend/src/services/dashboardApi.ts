import {
  IdempotencyForensicDto,
  OperatorInterventionRequest,
  OperatorInterventionResult,
  SagaGraphDto,
  SagaSummaryDto,
  TelemetrySummaryDto,
} from '../types/dashboard';

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

  const response = await fetch(`${BASE_URL}/sagas?${params.toString()}`);
  if (!response.ok) {
    throw new Error(`Failed to fetch sagas: ${response.status} ${response.statusText}`);
  }
  return response.json();
}

export async function fetchSagaGraph(sagaId: string): Promise<SagaGraphDto> {
  const response = await fetch(`${BASE_URL}/sagas/${sagaId}/graph`);
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

  const response = await fetch(`${BASE_URL}/idempotency?${query.toString()}`);
  if (!response.ok) {
    throw new Error(`Failed to search idempotency records: ${response.status} ${response.statusText}`);
  }
  return response.json();
}

export async function fetchTelemetry(): Promise<TelemetrySummaryDto> {
  const response = await fetch(`${BASE_URL}/telemetry`);
  if (!response.ok) {
    throw new Error(`Failed to fetch telemetry: ${response.status} ${response.statusText}`);
  }
  return response.json();
}

export function getDashboardStreamUrl(): string {
  return `${BASE_URL}/stream`;
}
