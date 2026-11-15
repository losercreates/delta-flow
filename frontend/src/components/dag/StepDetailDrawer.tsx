import React, { useState } from 'react';
import { X, Copy, Check, AlertOctagon, Clock, Shield, Database } from 'lucide-react';
import { SagaNodeDto } from '../../types/dashboard';

interface StepDetailDrawerProps {
  node: SagaNodeDto | null;
  onClose: () => void;
}

export const StepDetailDrawer: React.FC<StepDetailDrawerProps> = ({ node, onClose }) => {
  const [copiedKey, setCopiedKey] = useState<string | null>(null);

  if (!node) return null;

  const handleCopy = (key: string, text: string) => {
    navigator.clipboard.writeText(text);
    setCopiedKey(key);
    setTimeout(() => setCopiedKey(null), 2000);
  };

  const formatJson = (raw: string | null) => {
    if (!raw) return 'null';
    try {
      return JSON.stringify(JSON.parse(raw), null, 2);
    } catch {
      return raw;
    }
  };

  return (
    <aside
      className="fixed inset-y-0 right-0 w-96 max-w-full bg-slate-900/95 backdrop-blur-xl border-l border-slate-800 shadow-2xl z-50 flex flex-col transition-transform duration-300 animate-in slide-in-from-right"
      aria-label="Step Inspection Drawer"
    >
      {/* Header */}
      <div className="flex items-center justify-between px-6 py-4 border-b border-slate-800">
        <div>
          <div className="flex items-center gap-2">
            <span className="font-mono text-xs px-2 py-0.5 rounded bg-slate-800 text-slate-300 font-bold">
              Step #{node.stepIndex}
            </span>
            <span className="text-xs px-2 py-0.5 rounded-full font-medium bg-slate-800 text-slate-300 border border-slate-700">
              {node.status}
            </span>
          </div>
          <h2 className="text-lg font-bold text-slate-100 mt-1 truncate" title={node.stepName}>
            {node.stepName}
          </h2>
        </div>
        <button
          onClick={onClose}
          className="p-1.5 rounded-lg text-slate-400 hover:text-slate-100 hover:bg-slate-800 transition-colors"
          aria-label="Close drawer"
        >
          <X className="w-5 h-5" />
        </button>
      </div>

      {/* Body */}
      <div className="flex-1 overflow-y-auto p-6 space-y-6 text-sm">
        {/* Error Callout if Failed */}
        {node.errorDetails && (
          <div className="rounded-xl border border-rose-500/50 bg-rose-950/30 p-4">
            <div className="flex items-center gap-2 text-rose-400 font-semibold mb-2">
              <AlertOctagon className="w-4 h-4 shrink-0" />
              <span>Step Execution Failure</span>
            </div>
            <pre className="text-xs font-mono text-rose-200 whitespace-pre-wrap break-all bg-black/30 p-2.5 rounded-lg border border-rose-900/40">
              {node.errorDetails}
            </pre>
          </div>
        )}

        {/* Execution Metadata Grid */}
        <div className="grid grid-cols-2 gap-3">
          <div className="bg-slate-800/40 p-3 rounded-lg border border-slate-800">
            <div className="flex items-center gap-1.5 text-xs text-slate-400 mb-1">
              <Shield className="w-3.5 h-3.5 text-sky-400" />
              <span>Fencing Token</span>
            </div>
            <div className="font-mono font-bold text-slate-200 text-sm">
              #{node.fencingToken ?? 1}
            </div>
          </div>

          <div className="bg-slate-800/40 p-3 rounded-lg border border-slate-800">
            <div className="flex items-center gap-1.5 text-xs text-slate-400 mb-1">
              <Clock className="w-3.5 h-3.5 text-amber-400" />
              <span>Execution State</span>
            </div>
            <div className="font-mono font-bold text-slate-200 text-sm truncate">
              {node.status}
            </div>
          </div>
        </div>

        {/* Timestamps */}
        <div className="space-y-2 bg-slate-800/30 p-3.5 rounded-lg border border-slate-800">
          <div className="flex justify-between text-xs">
            <span className="text-slate-400">Started:</span>
            <span className="font-mono text-slate-200">
              {node.startedAt ? new Date(node.startedAt).toLocaleTimeString() : 'N/A'}
            </span>
          </div>
          <div className="flex justify-between text-xs">
            <span className="text-slate-400">Completed:</span>
            <span className="font-mono text-slate-200">
              {node.completedAt ? new Date(node.completedAt).toLocaleTimeString() : 'N/A'}
            </span>
          </div>
        </div>

        {/* Request Payload */}
        <div>
          <div className="flex items-center justify-between mb-2">
            <div className="flex items-center gap-1.5 text-xs font-semibold text-slate-300">
              <Database className="w-3.5 h-3.5 text-emerald-400" />
              <span>Request Input</span>
            </div>
            {node.requestPayload && (
              <button
                onClick={() => handleCopy('req', formatJson(node.requestPayload))}
                className="flex items-center gap-1 text-[11px] text-slate-400 hover:text-slate-200"
              >
                {copiedKey === 'req' ? (
                  <>
                    <Check className="w-3 h-3 text-emerald-400" /> Copied
                  </>
                ) : (
                  <>
                    <Copy className="w-3 h-3" /> Copy
                  </>
                )}
              </button>
            )}
          </div>
          <pre className="text-xs font-mono bg-slate-950 p-3 rounded-lg border border-slate-800 overflow-x-auto text-emerald-300 max-h-48">
            {formatJson(node.requestPayload)}
          </pre>
        </div>

        {/* Response Payload */}
        <div>
          <div className="flex items-center justify-between mb-2">
            <div className="flex items-center gap-1.5 text-xs font-semibold text-slate-300">
              <Database className="w-3.5 h-3.5 text-sky-400" />
              <span>Response Output</span>
            </div>
            {node.responsePayload && (
              <button
                onClick={() => handleCopy('res', formatJson(node.responsePayload))}
                className="flex items-center gap-1 text-[11px] text-slate-400 hover:text-slate-200"
              >
                {copiedKey === 'res' ? (
                  <>
                    <Check className="w-3 h-3 text-emerald-400" /> Copied
                  </>
                ) : (
                  <>
                    <Copy className="w-3 h-3" /> Copy
                  </>
                )}
              </button>
            )}
          </div>
          <pre className="text-xs font-mono bg-slate-950 p-3 rounded-lg border border-slate-800 overflow-x-auto text-sky-300 max-h-48">
            {formatJson(node.responsePayload)}
          </pre>
        </div>
      </div>
    </aside>
  );
};
