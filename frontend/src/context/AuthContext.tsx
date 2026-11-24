import React, { createContext, useContext, useState, useEffect, useCallback } from 'react';
import {
  AuthLoginRequest,
  CurrentUserProfileDto,
  TenantRateLimitStatusDto,
} from '../types/security';
import {
  getAuthToken,
  setAuthToken,
  login as apiLogin,
  fetchCurrentUser,
  fetchTenantRateLimit,
} from '../services/authApi';

interface AuthContextType {
  token: string | null;
  profile: CurrentUserProfileDto | null;
  rateLimit: TenantRateLimitStatusDto | null;
  isAuthenticated: boolean;
  isLoading: boolean;
  login: (request: AuthLoginRequest) => Promise<void>;
  logout: () => void;
  hasRole: (requiredRole: string) => boolean;
  refreshProfile: () => Promise<CurrentUserProfileDto | null>;
  refreshRateLimit: (tenantId?: string) => Promise<void>;
}

const AuthContext = createContext<AuthContextType | undefined>(undefined);

export const AuthProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [token, setTokenState] = useState<string | null>(getAuthToken());
  const [profile, setProfile] = useState<CurrentUserProfileDto | null>(null);
  const [rateLimit, setRateLimit] = useState<TenantRateLimitStatusDto | null>(null);
  const [isLoading, setIsLoading] = useState<boolean>(true);

  const refreshProfile = useCallback(async () => {
    try {
      const userProfile = await fetchCurrentUser();
      setProfile(userProfile);
      return userProfile;
    } catch {
      // Token may be invalid/expired
      setAuthToken(null);
      setTokenState(null);
      setProfile(null);
      return null;
    }
  }, []);

  const refreshRateLimit = useCallback(async (tenantId?: string) => {
    try {
      const tid = tenantId || profile?.tenantId;
      const status = await fetchTenantRateLimit(tid);
      setRateLimit(status);
    } catch {
      // ignore rate limit polling error
    }
  }, [profile?.tenantId]);

  useEffect(() => {
    let mounted = true;
    const initAuth = async () => {
      setIsLoading(true);
      const existingToken = getAuthToken();
      if (existingToken) {
        try {
          const userProfile = await fetchCurrentUser();
          if (mounted) {
            setProfile(userProfile);
            const status = await fetchTenantRateLimit(userProfile.tenantId);
            if (mounted) setRateLimit(status);
          }
        } catch {
          if (mounted) {
            setAuthToken(null);
            setTokenState(null);
            setProfile(null);
          }
        }
      }
      if (mounted) setIsLoading(false);
    };

    initAuth();
    return () => {
      mounted = false;
    };
  }, []);

  const login = async (request: AuthLoginRequest) => {
    setIsLoading(true);
    try {
      const loginRes = await apiLogin(request);
      setTokenState(loginRes.token);
      const userProfile = await fetchCurrentUser();
      setProfile(userProfile);
      try {
        const rateLimitRes = await fetchTenantRateLimit(userProfile.tenantId);
        setRateLimit(rateLimitRes);
      } catch {
        // ignore
      }
    } finally {
      setIsLoading(false);
    }
  };

  const logout = () => {
    setAuthToken(null);
    setTokenState(null);
    setProfile(null);
    setRateLimit(null);
  };

  const hasRole = (requiredRole: string): boolean => {
    if (!profile || !profile.roles) return false;
    const roles = profile.roles;

    if (roles.includes('ROLE_ADMIN')) {
      return true; // Admin has all permissions
    }

    if (roles.includes('ROLE_OPERATOR')) {
      if (requiredRole === 'ROLE_OPERATOR' || requiredRole === 'ROLE_VIEWER') {
        return true;
      }
    }

    if (roles.includes('ROLE_VIEWER')) {
      if (requiredRole === 'ROLE_VIEWER') {
        return true;
      }
    }

    return roles.includes(requiredRole);
  };

  return (
    <AuthContext.Provider
      value={{
        token,
        profile,
        rateLimit,
        isAuthenticated: !!token && !!profile,
        isLoading,
        login,
        logout,
        hasRole,
        refreshProfile,
        refreshRateLimit,
      }}
    >
      {children}
    </AuthContext.Provider>
  );
};

export const useAuth = (): AuthContextType => {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth must be used within an AuthProvider');
  }
  return context;
};
