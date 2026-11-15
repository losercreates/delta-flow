import React from 'react';
import {
  Activity,
  CheckCircle2,
  RotateCcw,
  AlertOctagon,
  Send,
  Radio,
  ShieldCheck,
  ShieldAlert,
} from 'lucide-react';
import { TelemetrySummaryDto } from '../../types/dashboard';

interface TelemetryHeaderProps {
  telemetry: TelemetrySummaryDto | null;
  isLiveConnected: boolean;
}

export const TelemetryHeader: React.FC<TelemetryHeaderProps> = ({
  telemetry,
  isLiveConnected,
}) => {
  return (
    <header className="bg-slate-900 border-b border-slate-800 px-6 py-4 shadow-md">
      <div className="flex flex-col xl:flex-row xl:items-center justify-between gap-4">
        {/* Brand & Connection State */}
        <div className="flex items-center gap-3">
          <div className="w-9 h-9 rounded-xl bg-gradient-to-br from-primary-500 to-indigo-600 flex items-center justify-center shadow-lg shadow-primary-500/20 text-white font-black text-lg">
            Δ
          </div>
          <div>
            <div className="flex items-center gap-2">
              <h1 className="text-base font-bold text-slate-100">DeltaFlow</h1>
              <span className="text-[10px] font-mono px-2 py-0.5 rounded-full bg-slate-800 text-slate-300 border border-slate-700">
                v1.0.0
              </span>
              <span
                className={`flex items-center gap-1 text-[10px] font-bold px-2 py-0.5 rounded-full ${
                  isLiveConnected
                    ? 'bg-emerald-500/20 text-emerald-300 border border-emerald-500/40'
                    : 'bg-amber-500/20 text-amber-300 border border-amber-500/40'
                }`}
              >
                <Radio
                  className={`w-3 h-3 ${isLiveConnected ? 'animate-pulse text-emerald-400' : ''}`}
                />
                {isLiveConnected ? 'LIVE STREAM' : 'OFFLINE'}
              </span>
            </div>
            <p className="text-[11px] text-slate-400">
              Distributed Idempotency & Saga Orchestrator Console
            </p>
          </div>
        </div>

        {/* Real-Time Metrics Badges */}
        <div className="flex flex-wrap items-center gap-2">
          {/* Active Sagas */}
          <div className="flex items-center gap-1.5 px-3 py-1.5 rounded-xl bg-sky-950/40 border border-sky-600/30 text-sky-200">
            <Activity className="w-3.5 h-3.5 text-sky-400" />
            <span className="text-xs text-sky-300">Active:</span>
            <span className="text-xs font-mono font-bold text-sky-100">
              {telemetry?.activeSagas ?? 0}
            </span>
          </div>

          {/* Completed Sagas */}
          <div className="flex items-center gap-1.5 px-3 py-1.5 rounded-xl bg-emerald-950/40 border border-emerald-600/30 text-emerald-200">
            <CheckCircle2 className="w-3.5 h-3.5 text-emerald-400" />
            <span className="text-xs text-emerald-300">Done:</span>
            <span className="text-xs font-mono font-bold text-emerald-100">
              {telemetry?.completedSagas ?? 0}
            </span>
          </div>

          {/* Compensated Sagas */}
          <div className="flex items-center gap-1.5 px-3 py-1.5 rounded-xl bg-amber-950/40 border border-amber-600/30 text-amber-200">
            <RotateCcw className="w-3.5 h-3.5 text-amber-400" />
            <span className="text-xs text-amber-300">Compensated:</span>
            <span className="text-xs font-mono font-bold text-amber-100">
              {telemetry?.compensatedSagas ?? 0}
            </span>
          </div>

          {/* Failed Sagas */}
          <div className="flex items-center gap-1.5 px-3 py-1.5 rounded-xl bg-rose-950/40 border border-rose-600/30 text-rose-200">
            <AlertOctagon className="w-3.5 h-3.5 text-rose-400" />
            <span className="text-xs text-rose-300">Failed:</span>
            <span className="text-xs font-mono font-bold text-rose-100">
              {telemetry?.failedSagas ?? 0}
            </span>
          </div>

          {/* Pending Outbox */}
          <div className="flex items-center gap-1.5 px-3 py-1.5 rounded-xl bg-indigo-950/40 border border-indigo-600/30 text-indigo-200">
            <Send className="w-3.5 h-3.5 text-indigo-400" />
            <span className="text-xs text-indigo-300">Outbox:</span>
            <span className="text-xs font-mono font-bold text-indigo-100">
              {telemetry?.pendingOutboxEvents ?? 0}
            </span>
          </div>

          {/* Poison Pill DLQ Alert */}
          {(telemetry?.deadLetterEvents ?? 0) > 0 ? (
            <div className="flex items-center gap-1.5 px-3 py-1.5 rounded-xl bg-rose-600 text-white font-bold animate-pulse shadow-lg shadow-rose-900/50">
              <ShieldAlert className="w-4 h-4 text-white" />
              <span className="text-xs">DLQ Poison Pills: {telemetry?.deadLetterEvents}</span>
            </div>
          ) : (
            <div className="flex items-center gap-1.5 px-2.5 py-1.5 rounded-xl bg-slate-800/60 border border-slate-700/60 text-slate-400">
              <ShieldCheck className="w-3.5 h-3.5 text-slate-500" />
              <span className="text-xs">DLQ: 0</span>
            </div>
          )}
        </div>
      </div>

      {/* Circuit Breakers Row (if any) */}
      {telemetry?.circuitBreakers && telemetry.circuitBreakers.length > 0 && (
        <div className="flex items-center gap-2 mt-3 pt-3 border-t border-slate-800/60 overflow-x-auto text-[11px]">
          <span className="text-slate-500 font-semibold uppercase tracking-wider text-[10px] shrink-0">
            Circuit Breakers:
          </span>
          {telemetry.circuitBreakers.map((cb) => {
            const isOpen = cb.state === 'OPEN';
            const isHalf = cb.state === 'HALF_OPEN';
            return (
              <div
                key={cb.name}
                className={`flex items-center gap-1 px-2.5 py-0.5 rounded-full border font-mono shrink-0 ${
                  isOpen
                    ? 'border-rose-500 bg-rose-950/40 text-rose-300 font-bold animate-pulse'
                    : isHalf
                    ? 'border-amber-500 bg-amber-950/40 text-amber-300'
                    : 'border-slate-800 bg-slate-950 text-slate-300'
                }`}
              >
                <span>{cb.name}:</span>
                <span
                  className={
                    isOpen ? 'text-rose-400 font-bold' : isHalf ? 'text-amber-400' : 'text-emerald-400'
                  }
                >
                  {cb.state}
                </span>
                {cb.failureRate !== undefined && cb.failureRate >= 0 && (
                  <span className="text-slate-500 text-[10px]">({cb.failureRate.toFixed(1)}%)</span>
                )}
              </div>
            );
          })}
        </div>
      )}
    </header>
  );
};
