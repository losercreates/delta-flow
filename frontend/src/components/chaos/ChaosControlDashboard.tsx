import React, { useCallback, useEffect, useState } from 'react';
import {
  RefreshCw,
  AlertOctagon,
} from 'lucide-react';
import { ChaosStatusDto, InvariantVerificationReport } from '../../types/chaos';
import { fetchChaosStatus, verifyFinancialInvariants } from '../../services/chaosApi';
import { ChaosInjectorPanel } from './ChaosInjectorPanel';
import { CircuitBreakerConsole } from './CircuitBreakerConsole';
import { InvariantAuditPanel } from './InvariantAuditPanel';
import { DlqInspectorModal } from './DlqInspectorModal';

export const ChaosControlDashboard: React.FC = () => {
  const [chaosStatus, setChaosStatus] = useState<ChaosStatusDto | null>(null);
  const [invariantReport, setInvariantReport] = useState<InvariantVerificationReport | null>(null);
  const [isDlqModalOpen, setIsDlqModalOpen] = useState(false);
  const [loading, setLoading] = useState(false);

  const loadData = useCallback(async () => {
    setLoading(true);
    try {
      const [status, report] = await Promise.allSettled([
        fetchChaosStatus(),
        verifyFinancialInvariants(),
      ]);

      if (status.status === 'fulfilled') {
        setChaosStatus(status.value);
      }
      if (report.status === 'fulfilled') {
        setInvariantReport(report.value);
      }
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadData();
  }, [loadData]);

  return (
    <div className="flex-1 flex flex-col h-full bg-slate-950 text-slate-100 overflow-y-auto p-6 space-y-6">
      {/* Top Banner */}
      <div className="flex flex-col md:flex-row md:items-center justify-between pb-4 border-b border-slate-800 gap-4">
        <div>
          <h2 className="text-lg font-bold text-slate-100 flex items-center gap-2">
            Chaos Engineering & Invariant Telemetry
          </h2>
          <p className="text-xs text-slate-400">
            Simulate adverse network conditions, toggle circuit breaker policies, audit financial invariants, and replay poison DLQ events
          </p>
        </div>

        <div className="flex items-center gap-2.5">
          <button
            onClick={() => setIsDlqModalOpen(true)}
            className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-slate-900 hover:bg-slate-850 text-slate-300 border border-slate-700 hover:border-slate-600 text-xs font-semibold shadow transition-all"
          >
            <AlertOctagon className="w-3.5 h-3.5 text-rose-400" />
            Dead-Letter Queue
          </button>

          <button
            onClick={loadData}
            disabled={loading}
            className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-primary-600 hover:bg-primary-500 text-white text-xs font-semibold shadow-lg shadow-primary-600/20 transition-all disabled:opacity-50"
          >
            <RefreshCw className={`w-3.5 h-3.5 ${loading ? 'animate-spin' : ''}`} />
            Refresh Telemetry
          </button>
        </div>
      </div>

      {/* Row 1: Financial Invariant Verifier */}
      <InvariantAuditPanel
        report={invariantReport}
        onAuditCompleted={(rep) => setInvariantReport(rep)}
      />

      {/* Row 2: Chaos Fault Injector */}
      <ChaosInjectorPanel status={chaosStatus} onRefresh={loadData} />

      {/* Row 3: Circuit Breakers Console */}
      <CircuitBreakerConsole
        circuitBreakers={chaosStatus?.circuitBreakers || []}
        onRefresh={loadData}
      />

      {/* DLQ Modal */}
      <DlqInspectorModal
        isOpen={isDlqModalOpen}
        onClose={() => setIsDlqModalOpen(false)}
        onReplaySuccess={loadData}
      />
    </div>
  );
};
