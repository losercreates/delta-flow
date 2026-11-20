import React, { useState } from 'react';
import {
  ShieldCheck,
  RefreshCw,
  Coins,
  CheckCircle2,
  AlertTriangle,
  Search,
} from 'lucide-react';
import { InvariantVerificationReport } from '../../types/chaos';
import { verifyFinancialInvariants } from '../../services/chaosApi';

interface InvariantAuditPanelProps {
  report: InvariantVerificationReport | null;
  onAuditCompleted: (report: InvariantVerificationReport) => void;
}

export const InvariantAuditPanel: React.FC<InvariantAuditPanelProps> = ({
  report,
  onAuditCompleted,
}) => {
  const [tenantFilter, setTenantFilter] = useState('');
  const [loading, setLoading] = useState(false);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);

  const handleRunAudit = async () => {
    setLoading(true);
    setErrorMsg(null);
    try {
      const data = await verifyFinancialInvariants(tenantFilter.trim() || undefined);
      onAuditCompleted(data);
    } catch (err: unknown) {
      setErrorMsg(err instanceof Error ? err.message : 'Audit scan failed');
    } finally {
      setLoading(false);
    }
  };

  const isPassed = report?.passed ?? true;
  const debits = report?.totalDebits ?? 0;
  const credits = report?.totalCredits ?? 0;
  const refunds = report?.totalRefunds ?? 0;
  const doubleSpends = report?.doubleSpendCount ?? 0;
  const orphaned = report?.orphanedDebitCount ?? 0;

  return (
    <div className="bg-slate-900/90 border border-slate-800 rounded-xl p-5 shadow-lg">
      <div className="flex flex-col sm:flex-row sm:items-center justify-between pb-4 border-b border-slate-800 mb-4 gap-3">
        <div className="flex items-center gap-2.5">
          <div className="p-2 rounded-lg bg-emerald-500/10 text-emerald-400 border border-emerald-500/20">
            <Coins className="w-5 h-5" />
          </div>
          <div>
            <h3 className="text-sm font-bold text-slate-100 flex items-center gap-2">
              Financial Invariant Verifier
              {report && (
                <span
                  className={`text-[10px] px-2 py-0.5 rounded-full font-bold flex items-center gap-1 ${
                    isPassed
                      ? 'bg-emerald-500/20 text-emerald-300 border border-emerald-500/40'
                      : 'bg-rose-500/20 text-rose-300 border border-rose-500/40 animate-pulse'
                  }`}
                >
                  {isPassed ? <CheckCircle2 className="w-3 h-3" /> : <AlertTriangle className="w-3 h-3" />}
                  {isPassed ? 'INVARIANTS CONSERVED' : 'VIOLATION DETECTED'}
                </span>
              )}
            </h3>
            <p className="text-[11px] text-slate-400">
              Mathematical proof of zero double-spend and exact debit/credit conservation
            </p>
          </div>
        </div>

        <div className="flex items-center gap-2">
          <div className="relative">
            <Search className="w-3.5 h-3.5 absolute left-2.5 top-2 text-slate-500" />
            <input
              type="text"
              value={tenantFilter}
              onChange={(e) => setTenantFilter(e.target.value)}
              placeholder="Filter by tenant ID..."
              className="pl-8 pr-2.5 py-1 rounded-lg bg-slate-950 border border-slate-800 text-xs text-slate-200 placeholder:text-slate-600 focus:outline-none focus:border-primary-500"
            />
          </div>

          <button
            onClick={handleRunAudit}
            disabled={loading}
            className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-emerald-600 hover:bg-emerald-500 text-white text-xs font-semibold shadow-lg shadow-emerald-600/20 transition-all disabled:opacity-50"
          >
            {loading ? <RefreshCw className="w-3.5 h-3.5 animate-spin" /> : <ShieldCheck className="w-3.5 h-3.5" />}
            Run Invariant Audit
          </button>
        </div>
      </div>

      {errorMsg && (
        <div className="mb-4 px-3.5 py-2 rounded-lg text-xs bg-rose-950/50 text-rose-300 border border-rose-800 flex items-center gap-2">
          <AlertTriangle className="w-4 h-4 shrink-0" />
          <span>{errorMsg}</span>
        </div>
      )}

      {/* Metrics Grid */}
      <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-6 gap-3 mb-4">
        {/* Audited Volume */}
        <div className="p-3 rounded-lg bg-slate-950/60 border border-slate-800">
          <span className="text-[10px] font-semibold text-slate-400">Total Audited</span>
          <div className="text-base font-bold font-mono text-slate-100 mt-0.5">
            {report?.totalTransactionsAudited ?? 0}
          </div>
        </div>

        {/* Total Debited */}
        <div className="p-3 rounded-lg bg-slate-950/60 border border-slate-800">
          <span className="text-[10px] font-semibold text-slate-400">Total Debited</span>
          <div className="text-base font-bold font-mono text-sky-300 mt-0.5">
            ${debits.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}
          </div>
        </div>

        {/* Total Credited */}
        <div className="p-3 rounded-lg bg-slate-950/60 border border-slate-800">
          <span className="text-[10px] font-semibold text-slate-400">Total Credited</span>
          <div className="text-base font-bold font-mono text-emerald-300 mt-0.5">
            ${credits.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}
          </div>
        </div>

        {/* Total Refunded */}
        <div className="p-3 rounded-lg bg-slate-950/60 border border-slate-800">
          <span className="text-[10px] font-semibold text-slate-400">Total Refunded</span>
          <div className="text-base font-bold font-mono text-amber-300 mt-0.5">
            ${refunds.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}
          </div>
        </div>

        {/* Double Spends */}
        <div className="p-3 rounded-lg bg-slate-950/60 border border-slate-800">
          <span className="text-[10px] font-semibold text-slate-400">Double Spends</span>
          <div
            className={`text-base font-bold font-mono mt-0.5 ${
              doubleSpends === 0 ? 'text-emerald-400' : 'text-rose-400'
            }`}
          >
            {doubleSpends}
          </div>
        </div>

        {/* Orphaned Debits */}
        <div className="p-3 rounded-lg bg-slate-950/60 border border-slate-800">
          <span className="text-[10px] font-semibold text-slate-400">Orphaned Debits</span>
          <div
            className={`text-base font-bold font-mono mt-0.5 ${
              orphaned === 0 ? 'text-emerald-400' : 'text-rose-400'
            }`}
          >
            {orphaned}
          </div>
        </div>
      </div>

      {/* Balance Invariant Equation Card */}
      <div className="p-3.5 bg-slate-950/40 rounded-lg border border-slate-800 flex items-center justify-between text-xs">
        <div className="flex items-center gap-2">
          <span className="font-semibold text-slate-400">Conservation Formula:</span>
          <code className="bg-slate-900 px-2 py-0.5 rounded font-mono text-[11px] text-slate-300 border border-slate-800">
            Σ Debits ({debits.toFixed(2)}) == Σ Credits ({credits.toFixed(2)}) + Σ Refunds ({refunds.toFixed(2)})
          </code>
        </div>
        <div className="text-[11px] font-mono text-slate-500">
          Variance: ${(debits - (credits + refunds)).toFixed(2)}
        </div>
      </div>

      {/* Violations List */}
      {report && report.violations && report.violations.length > 0 && (
        <div className="mt-4 p-4 rounded-lg bg-rose-950/30 border border-rose-800">
          <h4 className="text-xs font-bold text-rose-300 mb-2 flex items-center gap-1.5">
            <AlertTriangle className="w-4 h-4 text-rose-400" />
            Detected Invariant Violations ({report.violations.length})
          </h4>
          <div className="space-y-2">
            {report.violations.map((v, i) => (
              <div key={i} className="text-xs bg-slate-950/80 p-2.5 rounded border border-rose-900/50">
                <div className="flex items-center justify-between font-mono text-[11px] text-rose-400 mb-1">
                  <span>{v.violationType}</span>
                  <span>Amount: ${v.amount ?? 0}</span>
                </div>
                <div className="text-slate-300 text-[11px]">{v.details}</div>
                <div className="text-[10px] text-slate-500 font-mono mt-1">Saga: {v.sagaId ?? 'N/A'}</div>
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
};
