import React, { useState } from 'react';
import {
  ShieldAlert,
  ShieldCheck,
  RotateCw,
  Power,
  AlertOctagon,
} from 'lucide-react';
import { CircuitBreakerStatusDto } from '../../types/dashboard';
import { transitionCircuitBreaker } from '../../services/chaosApi';

interface CircuitBreakerConsoleProps {
  circuitBreakers: CircuitBreakerStatusDto[];
  onRefresh: () => void;
}

export const CircuitBreakerConsole: React.FC<CircuitBreakerConsoleProps> = ({
  circuitBreakers,
  onRefresh,
}) => {
  const [loadingBreaker, setLoadingBreaker] = useState<string | null>(null);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);

  const handleTransition = async (name: string, targetState: 'OPEN' | 'CLOSED' | 'HALF_OPEN') => {
    setLoadingBreaker(name);
    setErrorMsg(null);
    try {
      await transitionCircuitBreaker(name, {
        targetState,
        operatorId: 'ops-lead',
        justification: `Manual transition to ${targetState} via Operator Dashboard`,
      });
      onRefresh();
    } catch (err: unknown) {
      setErrorMsg(err instanceof Error ? err.message : 'Transition failed');
    } finally {
      setLoadingBreaker(null);
    }
  };

  return (
    <div className="bg-slate-900/90 border border-slate-800 rounded-xl p-5 shadow-lg">
      <div className="flex items-center justify-between pb-4 border-b border-slate-800 mb-4">
        <div className="flex items-center gap-2.5">
          <div className="p-2 rounded-lg bg-sky-500/10 text-sky-400 border border-sky-500/20">
            <Power className="w-5 h-5" />
          </div>
          <div>
            <h3 className="text-sm font-bold text-slate-100 flex items-center gap-2">
              Circuit Breaker Overrides
            </h3>
            <p className="text-[11px] text-slate-400">
              Resilience4j trip states, failure rates, and manual shedding controls
            </p>
          </div>
        </div>
      </div>

      {errorMsg && (
        <div className="mb-4 px-3.5 py-2 rounded-lg text-xs bg-rose-950/50 text-rose-300 border border-rose-800 flex items-center gap-2">
          <AlertOctagon className="w-4 h-4 shrink-0" />
          <span>{errorMsg}</span>
        </div>
      )}

      {circuitBreakers.length === 0 ? (
        <div className="p-6 text-center text-xs text-slate-500">
          No circuit breakers registered in Resilience4j registry
        </div>
      ) : (
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-3">
          {circuitBreakers.map((cb) => {
            const isOpen = cb.state === 'OPEN';
            const isHalfOpen = cb.state === 'HALF_OPEN';
            const isClosed = cb.state === 'CLOSED';
            const isLoading = loadingBreaker === cb.name;

            return (
              <div
                key={cb.name}
                className={`p-4 rounded-xl border transition-all ${
                  isOpen
                    ? 'border-rose-500/50 bg-rose-950/20'
                    : isHalfOpen
                    ? 'border-amber-500/50 bg-amber-950/20'
                    : 'border-slate-800 bg-slate-950/60'
                }`}
              >
                <div className="flex items-center justify-between mb-2">
                  <span className="font-bold text-xs text-slate-200">{cb.name}</span>
                  <span
                    className={`text-[10px] font-mono px-2 py-0.5 rounded-full font-bold flex items-center gap-1 ${
                      isOpen
                        ? 'bg-rose-500/20 text-rose-300 border border-rose-500/40 animate-pulse'
                        : isHalfOpen
                        ? 'bg-amber-500/20 text-amber-300 border border-amber-500/40'
                        : 'bg-emerald-500/20 text-emerald-300 border border-emerald-500/40'
                    }`}
                  >
                    {isOpen ? (
                      <ShieldAlert className="w-3 h-3" />
                    ) : isHalfOpen ? (
                      <RotateCw className="w-3 h-3" />
                    ) : (
                      <ShieldCheck className="w-3 h-3" />
                    )}
                    {cb.state}
                  </span>
                </div>

                <div className="flex items-center justify-between text-[11px] text-slate-400 mb-3">
                  <span>Failure Rate: <b className="text-slate-200 font-mono">{cb.failureRate.toFixed(1)}%</b></span>
                  <span>Buffered: <b className="text-slate-200 font-mono">{cb.bufferedCalls}</b></span>
                </div>

                <div className="flex items-center gap-1.5 pt-2 border-t border-slate-800/80">
                  <button
                    disabled={isLoading || isClosed}
                    onClick={() => handleTransition(cb.name, 'CLOSED')}
                    className="flex-1 py-1 px-2 rounded bg-slate-800 hover:bg-emerald-700/60 text-slate-300 hover:text-emerald-200 text-[10px] font-semibold disabled:opacity-40 disabled:hover:bg-slate-800 transition-colors"
                  >
                    Close
                  </button>
                  <button
                    disabled={isLoading || isHalfOpen}
                    onClick={() => handleTransition(cb.name, 'HALF_OPEN')}
                    className="flex-1 py-1 px-2 rounded bg-slate-800 hover:bg-amber-700/60 text-slate-300 hover:text-amber-200 text-[10px] font-semibold disabled:opacity-40 disabled:hover:bg-slate-800 transition-colors"
                  >
                    Half-Open
                  </button>
                  <button
                    disabled={isLoading || isOpen}
                    onClick={() => handleTransition(cb.name, 'OPEN')}
                    className="flex-1 py-1 px-2 rounded bg-slate-800 hover:bg-rose-700/60 text-slate-300 hover:text-rose-200 text-[10px] font-semibold disabled:opacity-40 disabled:hover:bg-slate-800 transition-colors"
                  >
                    Trip Open
                  </button>
                </div>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
};
