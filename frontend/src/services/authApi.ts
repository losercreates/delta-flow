import {
  AuthLoginRequest,
  AuthLoginResponse,
  CurrentUserProfileDto,
  TenantRateLimitStatusDto,
} from '../types/security';

const BASE_URL = '/api/v1/auth';
const TOKEN_KEY = 'deltaflow_auth_token';

export function getAuthToken(): string | null {
  try {
    return sessionStorage.getItem(TOKEN_KEY);
  } catch {
    return null;
  }
}

export function setAuthToken(token: string | null): void {
  try {
    if (token) {
      sessionStorage.setItem(TOKEN_KEY, token);
    } else {
      sessionStorage.removeItem(TOKEN_KEY);
    }
  } catch {
    // ignore sessionStorage errors
  }
}

export function getAuthHeaders(): Record<string, string> {
  const token = getAuthToken();
  if (token) {
    return {
      Authorization: `Bearer ${token}`,
    };
  }
  return {};
}

export async function login(request: AuthLoginRequest): Promise<AuthLoginResponse> {
  const response = await fetch(`${BASE_URL}/login`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(request),
  });

  if (!response.ok) {
    let errorMsg = `Authentication failed (${response.status} ${response.statusText})`;
    try {
      const err = await response.json();
      if (err.message) errorMsg = err.message;
    } catch {
      // ignore JSON parse error
    }
    throw new Error(errorMsg);
  }

  const data: AuthLoginResponse = await response.json();
  setAuthToken(data.token);
  return data;
}

export async function fetchCurrentUser(): Promise<CurrentUserProfileDto> {
  const response = await fetch(`${BASE_URL}/me`, {
    headers: {
      ...getAuthHeaders(),
    },
  });

  if (!response.ok) {
    throw new Error(`Failed to fetch user profile: ${response.status} ${response.statusText}`);
  }

  return response.json();
}

export async function fetchTenantRateLimit(tenantId?: string): Promise<TenantRateLimitStatusDto> {
  const params = new URLSearchParams();
  if (tenantId) {
    params.set('tenantId', tenantId);
  }

  const url = `${BASE_URL}/rate-limit/status${params.toString() ? `?${params.toString()}` : ''}`;
  const response = await fetch(url, {
    headers: {
      ...getAuthHeaders(),
    },
  });

  if (!response.ok) {
    throw new Error(`Failed to fetch rate limit status: ${response.status} ${response.statusText}`);
  }

  return response.json();
}
