import React, { useState } from 'react';
import { AlertTriangle, ShieldAlert, X, CheckCircle, Loader2 } from 'lucide-react';
import { InterventionAction, OperatorInterventionResult } from '../../types/dashboard';
import { executeIntervention } from '../../services/dashboardApi';

interface InterventionModalProps {
  isOpen: boolean;
  sagaId: string;
  onClose: () => void;
  onSuccess: (result: OperatorInterventionResult) => void;
}

export const InterventionModal: React.FC<InterventionModalProps> = ({
  isOpen,
  sagaId,
  onClose,
  onSuccess,
}) => {
  const [action, setAction] = useState<InterventionAction>('FORCE_COMPENSATE');
  const [stepIndex, setStepIndex] = useState<number | ''>('');
  const [operatorId, setOperatorId] = useState('');
  const [justification, setJustification] = useState('');
  const [loading, setLoading] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  if (!isOpen) return null;

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setErrorMessage(null);

    if (operatorId.trim().length < 3) {
      setErrorMessage('Operator ID must be at least 3 characters.');
      return;
    }

    if (justification.trim().length < 10) {
      setErrorMessage('Justification is mandatory and must be at least 10 characters.');
      return;
    }

    setLoading(true);
    try {
      const result = await executeIntervention(sagaId, {
        action,
        stepIndex: stepIndex === '' ? null : Number(stepIndex),
        operatorId: operatorId.trim(),
        justification: justification.trim(),
      });
      onSuccess(result);
      onClose();
    } catch (err: unknown) {
      if (err instanceof Error) {
        setErrorMessage(err.message);
      } else {
        setErrorMessage('Failed to execute operator intervention.');
      }
    } finally {
      setLoading(false);
    }
  };

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/70 backdrop-blur-sm animate-in fade-in"
      role="dialog"
      aria-modal="true"
      aria-labelledby="intervention-modal-title"
    >
      <div className="relative w-full max-w-lg bg-slate-900 border border-slate-800 rounded-2xl shadow-2xl overflow-hidden">
        {/* Header */}
        <div className="flex items-center justify-between px-6 py-4 border-b border-slate-800 bg-slate-850">
          <div className="flex items-center gap-2.5">
            <div className="p-2 rounded-lg bg-amber-500/10 text-amber-400">
              <ShieldAlert className="w-5 h-5" />
            </div>
            <div>
              <h2 id="intervention-modal-title" className="text-base font-bold text-slate-100">
                Manual Operator Intervention
              </h2>
              <p className="text-xs font-mono text-slate-400">Target: {sagaId}</p>
            </div>
          </div>
          <button
            onClick={onClose}
            className="p-1 rounded-lg text-slate-400 hover:text-slate-100 hover:bg-slate-800"
            aria-label="Close dialog"
          >
            <X className="w-5 h-5" />
          </button>
        </div>

        {/* Form */}
        <form onSubmit={handleSubmit} className="p-6 space-y-4">
          {/* Safety Notice */}
          <div className="p-3 rounded-xl bg-amber-950/30 border border-amber-600/30 text-amber-300 text-xs flex gap-2.5">
            <AlertTriangle className="w-4 h-4 shrink-0 text-amber-400 mt-0.5" />
            <div>
              <strong className="font-semibold block mb-0.5">Zero Double-Spend Guard</strong>
              Active worker leases are protected. Attempting intervention while a background worker
              holds a valid lease will result in HTTP 409 Conflict.
            </div>
          </div>

          {errorMessage && (
            <div className="p-3 rounded-xl bg-rose-950/40 border border-rose-500/50 text-rose-300 text-xs">
              {errorMessage}
            </div>
          )}

          {/* Action Selection */}
          <div>
            <label className="block text-xs font-semibold text-slate-300 mb-1.5">
              Intervention Action
            </label>
            <div className="grid grid-cols-2 gap-3">
              <button
                type="button"
                onClick={() => setAction('FORCE_COMPENSATE')}
                className={`px-3 py-2.5 rounded-xl border text-xs font-medium text-left transition-all ${
                  action === 'FORCE_COMPENSATE'
                    ? 'border-amber-500 bg-amber-500/15 text-amber-200 shadow-sm'
                    : 'border-slate-800 bg-slate-800/40 text-slate-400 hover:border-slate-700'
                }`}
              >
                <div className="font-bold mb-0.5">Force Settle / Compensate</div>
                <div className="text-[11px] opacity-80">
                  Transition to terminal COMPENSATED with step fencing
                </div>
              </button>

              <button
                type="button"
                onClick={() => setAction('FORCE_RETRY')}
                className={`px-3 py-2.5 rounded-xl border text-xs font-medium text-left transition-all ${
                  action === 'FORCE_RETRY'
                    ? 'border-sky-500 bg-sky-500/15 text-sky-200 shadow-sm'
                    : 'border-slate-800 bg-slate-800/40 text-slate-400 hover:border-slate-700'
                }`}
              >
                <div className="font-bold mb-0.5">Bounded Force Retry</div>
                <div className="text-[11px] opacity-80">
                  Increment fencing token and reset execution status
                </div>
              </button>
            </div>
          </div>

          {/* Step Index for retry */}
          {action === 'FORCE_RETRY' && (
            <div>
              <label htmlFor="step-index-input" className="block text-xs font-semibold text-slate-300 mb-1">
                Target Step Index (Optional)
              </label>
              <input
                id="step-index-input"
                type="number"
                min="0"
                value={stepIndex}
                onChange={(e) => setStepIndex(e.target.value === '' ? '' : Number(e.target.value))}
                placeholder="Leave blank to retry from failed step"
                className="w-full px-3 py-2 rounded-lg bg-slate-950 border border-slate-800 text-xs text-slate-100 placeholder:text-slate-600 focus:outline-none focus:border-sky-500"
              />
            </div>
          )}

          {/* Operator ID */}
          <div>
            <label htmlFor="operator-id-input" className="block text-xs font-semibold text-slate-300 mb-1">
              Operator Identifier <span className="text-rose-400">*</span>
            </label>
            <input
              id="operator-id-input"
              type="text"
              required
              value={operatorId}
              onChange={(e) => setOperatorId(e.target.value)}
              placeholder="e.g. op_finance_mayukh"
              className="w-full px-3 py-2 rounded-lg bg-slate-950 border border-slate-800 text-xs text-slate-100 placeholder:text-slate-600 focus:outline-none focus:border-amber-500"
            />
          </div>

          {/* Justification */}
          <div>
            <label htmlFor="justification-input" className="block text-xs font-semibold text-slate-300 mb-1">
              Audit Justification <span className="text-rose-400">*</span>
            </label>
            <textarea
              id="justification-input"
              required
              rows={3}
              value={justification}
              onChange={(e) => setJustification(e.target.value)}
              placeholder="Provide a mandatory reason for this manual intervention (min 10 characters)..."
              className="w-full px-3 py-2 rounded-lg bg-slate-950 border border-slate-800 text-xs text-slate-100 placeholder:text-slate-600 focus:outline-none focus:border-amber-500 resize-none"
            />
            <div className="flex justify-end text-[10px] text-slate-500 mt-0.5">
              {justification.length}/10 min chars
            </div>
          </div>

          {/* Footer buttons */}
          <div className="flex items-center justify-end gap-3 pt-3 border-t border-slate-800">
            <button
              type="button"
              onClick={onClose}
              disabled={loading}
              className="px-4 py-2 rounded-lg text-xs font-medium text-slate-300 hover:bg-slate-800 transition-colors"
            >
              Cancel
            </button>
            <button
              type="submit"
              disabled={loading}
              className="flex items-center gap-1.5 px-4 py-2 rounded-lg text-xs font-semibold bg-amber-600 hover:bg-amber-500 text-white transition-all shadow-md disabled:opacity-50"
            >
              {loading ? (
                <>
                  <Loader2 className="w-3.5 h-3.5 animate-spin" />
                  Executing...
                </>
              ) : (
                <>
                  <CheckCircle className="w-3.5 h-3.5" />
                  Confirm Intervention
                </>
              )}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
};
