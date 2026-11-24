export interface AuthLoginRequest {
  username: string;
  password?: string;
  tenantId?: string;
  requestedRole?: string;
}

export interface AuthLoginResponse {
  token: string;
  tokenType: string;
  expiresInSeconds: number;
  tenantId: string;
  roles: string[];
}

export interface CurrentUserProfileDto {
  principalId: string;
  tenantId: string;
  roles: string[];
  authType: 'JWT_BEARER' | 'API_KEY' | 'ANONYMOUS';
}

export interface TenantRateLimitStatusDto {
  tenantId: string;
  limit: number;
  remaining: number;
  resetMs: number;
  rateLimited: boolean;
}
