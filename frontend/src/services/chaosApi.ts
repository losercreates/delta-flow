import {
  ChaosInjectionRequest,
  ChaosStatusDto,
  CircuitBreakerTransitionRequest,
  InvariantVerificationReport,
  ProxyStatusDto,
} from '../types/chaos';
import { CircuitBreakerStatusDto } from '../types/dashboard';
import { getAuthHeaders } from './authApi';

const BASE_URL = '/api/v1';

export async function fetchChaosStatus(): Promise<ChaosStatusDto> {
  const response = await fetch(`${BASE_URL}/chaos/status`, {
    headers: { ...getAuthHeaders() },
  });
  if (!response.ok) {
    throw new Error(`Failed to fetch chaos status: ${response.status} ${response.statusText}`);
  }
  return response.json();
}

export async function injectChaos(request: ChaosInjectionRequest): Promise<ProxyStatusDto> {
  const response = await fetch(`${BASE_URL}/chaos/inject`, {
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

export async function healChaos(
  proxyName?: string,
  operatorId = 'OPERATOR',
  justification = 'Heal all active chaos conditions'
): Promise<void> {
  const response = await fetch(`${BASE_URL}/chaos/heal`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...getAuthHeaders(),
    },
    body: JSON.stringify({ proxyName, operatorId, justification }),
  });
  if (!response.ok) {
    throw new Error(`Failed to heal chaos: ${response.status} ${response.statusText}`);
  }
}

export async function transitionCircuitBreaker(
  name: string,
  request: CircuitBreakerTransitionRequest
): Promise<CircuitBreakerStatusDto> {
  const response = await fetch(`${BASE_URL}/chaos/circuit-breakers/${encodeURIComponent(name)}/transition`, {
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

export async function verifyFinancialInvariants(tenantId?: string): Promise<InvariantVerificationReport> {
  const url = tenantId
    ? `${BASE_URL}/invariants/verify?tenantId=${encodeURIComponent(tenantId)}`
    : `${BASE_URL}/invariants/verify`;
  const response = await fetch(url, {
    headers: { ...getAuthHeaders() },
  });
  if (!response.ok) {
    throw new Error(`Failed to verify invariants: ${response.status} ${response.statusText}`);
  }
  return response.json();
}
