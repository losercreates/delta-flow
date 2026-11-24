import React, { useState } from 'react';
import {
  Shield,
  Lock,
  User,
  Key,
  LogOut,
  X,
  CheckCircle2,
  AlertCircle,
  Building2,
  Sparkles,
} from 'lucide-react';
import { useAuth } from '../../context/AuthContext';

interface OperatorLoginModalProps {
  isOpen: boolean;
  onClose: () => void;
}

export const OperatorLoginModal: React.FC<OperatorLoginModalProps> = ({ isOpen, onClose }) => {
  const { profile, isAuthenticated, login, logout, isLoading } = useAuth();

  const [username, setUsername] = useState('admin');
  const [password, setPassword] = useState('password123');
  const [tenantId, setTenantId] = useState('tenant-delta');
  const [error, setError] = useState<string | null>(null);

  if (!isOpen) return null;

  const handleLogin = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    try {
      await login({
        username,
        password,
        tenantId,
      });
      onClose();
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : 'Authentication failed';
      setError(msg);
    }
  };

  const setPreset = (user: string, pass: string, tenant: string) => {
    setUsername(user);
    setPassword(pass);
    setTenantId(tenant);
    setError(null);
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-slate-950/80 backdrop-blur-sm animate-fade-in">
      <div className="relative w-full max-w-md bg-slate-900 border border-slate-800 rounded-2xl shadow-2xl overflow-hidden">
        {/* Header */}
        <div className="flex items-center justify-between px-6 py-4 bg-slate-850 border-b border-slate-800">
          <div className="flex items-center gap-2.5">
            <div className="w-8 h-8 rounded-lg bg-primary-500/20 border border-primary-500/30 flex items-center justify-center text-primary-400">
              <Shield className="w-4 h-4" />
            </div>
            <div>
              <h3 className="text-sm font-bold text-slate-100">Operator Authentication</h3>
              <p className="text-[11px] text-slate-400">Enterprise RBAC & Session Management</p>
            </div>
          </div>
          <button
            onClick={onClose}
            className="text-slate-400 hover:text-slate-200 p-1 rounded-lg hover:bg-slate-800 transition-colors"
          >
            <X className="w-4 h-4" />
          </button>
        </div>

        {/* Content */}
        <div className="p-6 space-y-6">
          {isAuthenticated && profile ? (
            <div className="space-y-4">
              <div className="p-4 rounded-xl bg-slate-850 border border-slate-800 space-y-3">
                <div className="flex items-center justify-between">
                  <span className="text-xs text-slate-400">Current Session</span>
                  <span className="flex items-center gap-1 text-[11px] font-bold text-emerald-400 bg-emerald-500/10 px-2 py-0.5 rounded-full border border-emerald-500/30">
                    <CheckCircle2 className="w-3 h-3" />
                    Authenticated
                  </span>
                </div>

                <div className="space-y-1.5 text-xs">
                  <div className="flex items-center justify-between">
                    <span className="text-slate-400">Principal ID:</span>
                    <span className="font-mono font-bold text-slate-200">{profile.principalId}</span>
                  </div>
                  <div className="flex items-center justify-between">
                    <span className="text-slate-400">Tenant:</span>
                    <span className="font-mono text-primary-400">{profile.tenantId}</span>
                  </div>
                  <div className="flex items-center justify-between">
                    <span className="text-slate-400">Auth Method:</span>
                    <span className="font-mono text-slate-300">{profile.authType}</span>
                  </div>
                </div>

                <div className="pt-2 border-t border-slate-800">
                  <div className="text-[11px] text-slate-400 mb-1.5">Granted Roles:</div>
                  <div className="flex flex-wrap gap-1.5">
                    {profile.roles.map((r) => (
                      <span
                        key={r}
                        className={`text-[10px] font-mono font-bold px-2 py-0.5 rounded-md border ${
                          r === 'ROLE_ADMIN'
                            ? 'bg-rose-500/20 text-rose-300 border-rose-500/40'
                            : r === 'ROLE_OPERATOR'
                            ? 'bg-amber-500/20 text-amber-300 border-amber-500/40'
                            : 'bg-sky-500/20 text-sky-300 border-sky-500/40'
                        }`}
                      >
                        {r}
                      </span>
                    ))}
                  </div>
                </div>
              </div>

              <div className="flex gap-2">
                <button
                  type="button"
                  onClick={logout}
                  className="flex-1 flex items-center justify-center gap-2 py-2 px-4 rounded-xl bg-rose-600/20 hover:bg-rose-600/30 border border-rose-500/40 text-rose-300 text-xs font-semibold transition-all"
                >
                  <LogOut className="w-3.5 h-3.5" />
                  Sign Out Session
                </button>
              </div>
            </div>
          ) : (
            <form onSubmit={handleLogin} className="space-y-4">
              {/* Quick Role Selection Presets */}
              <div className="space-y-1.5">
                <label className="text-[11px] font-medium text-slate-400 flex items-center gap-1">
                  <Sparkles className="w-3 h-3 text-primary-400" />
                  Quick Role Presets
                </label>
                <div className="grid grid-cols-3 gap-2">
                  <button
                    type="button"
                    onClick={() => setPreset('admin', 'password123', 'tenant-delta')}
                    className={`py-1.5 px-2 rounded-lg border text-center transition-all ${
                      username === 'admin'
                        ? 'bg-rose-950/50 border-rose-500/60 text-rose-200 font-bold'
                        : 'bg-slate-850 border-slate-700/60 text-slate-400 hover:text-slate-200'
                    }`}
                  >
                    <div className="text-[11px]">Admin</div>
                    <div className="text-[9px] text-slate-500">Full Control</div>
                  </button>

                  <button
                    type="button"
                    onClick={() => setPreset('operator', 'password123', 'tenant-delta')}
                    className={`py-1.5 px-2 rounded-lg border text-center transition-all ${
                      username === 'operator'
                        ? 'bg-amber-950/50 border-amber-500/60 text-amber-200 font-bold'
                        : 'bg-slate-850 border-slate-700/60 text-slate-400 hover:text-slate-200'
                    }`}
                  >
                    <div className="text-[11px]">Operator</div>
                    <div className="text-[9px] text-slate-500">Replay & Fix</div>
                  </button>

                  <button
                    type="button"
                    onClick={() => setPreset('viewer', 'password123', 'tenant-delta')}
                    className={`py-1.5 px-2 rounded-lg border text-center transition-all ${
                      username === 'viewer'
                        ? 'bg-sky-950/50 border-sky-500/60 text-sky-200 font-bold'
                        : 'bg-slate-850 border-slate-700/60 text-slate-400 hover:text-slate-200'
                    }`}
                  >
                    <div className="text-[11px]">Viewer</div>
                    <div className="text-[9px] text-slate-500">Read-Only</div>
                  </button>
                </div>
              </div>

              {/* Form inputs */}
              <div className="space-y-3">
                <div>
                  <label className="text-[11px] font-medium text-slate-400 mb-1 flex items-center gap-1.5">
                    <User className="w-3 h-3 text-slate-400" />
                    Operator Username
                  </label>
                  <input
                    type="text"
                    value={username}
                    onChange={(e) => setUsername(e.target.value)}
                    required
                    className="w-full px-3 py-2 rounded-xl bg-slate-950 border border-slate-800 focus:border-primary-500 focus:ring-1 focus:ring-primary-500 text-xs text-slate-100 placeholder-slate-600 outline-none"
                    placeholder="e.g. admin, operator, viewer"
                  />
                </div>

                <div>
                  <label className="text-[11px] font-medium text-slate-400 mb-1 flex items-center gap-1.5">
                    <Key className="w-3 h-3 text-slate-400" />
                    Password
                  </label>
                  <input
                    type="password"
                    value={password}
                    onChange={(e) => setPassword(e.target.value)}
                    required
                    className="w-full px-3 py-2 rounded-xl bg-slate-950 border border-slate-800 focus:border-primary-500 focus:ring-1 focus:ring-primary-500 text-xs text-slate-100 placeholder-slate-600 outline-none"
                    placeholder="••••••••••••"
                  />
                </div>

                <div>
                  <label className="text-[11px] font-medium text-slate-400 mb-1 flex items-center gap-1.5">
                    <Building2 className="w-3 h-3 text-slate-400" />
                    Tenant Partition ID
                  </label>
                  <input
                    type="text"
                    value={tenantId}
                    onChange={(e) => setTenantId(e.target.value)}
                    required
                    className="w-full px-3 py-2 rounded-xl bg-slate-950 border border-slate-800 focus:border-primary-500 focus:ring-1 focus:ring-primary-500 text-xs text-slate-100 placeholder-slate-600 outline-none font-mono"
                    placeholder="e.g. tenant-delta"
                  />
                </div>
              </div>

              {error && (
                <div className="flex items-center gap-2 p-2.5 rounded-lg bg-rose-500/10 border border-rose-500/30 text-rose-300 text-xs">
                  <AlertCircle className="w-4 h-4 shrink-0" />
                  <span>{error}</span>
                </div>
              )}

              <button
                type="submit"
                disabled={isLoading}
                className="w-full flex items-center justify-center gap-2 py-2.5 px-4 rounded-xl bg-primary-600 hover:bg-primary-500 text-white text-xs font-semibold shadow-lg shadow-primary-600/20 transition-all disabled:opacity-50"
              >
                <Lock className="w-3.5 h-3.5" />
                {isLoading ? 'Authenticating...' : 'Sign In as Operator'}
              </button>
            </form>
          )}
        </div>
      </div>
    </div>
  );
};
