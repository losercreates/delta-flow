import React, { useState } from 'react';
import {
  Flame,
  ShieldCheck,
  AlertTriangle,
  Zap,
  CheckCircle2,
  RefreshCw,
} from 'lucide-react';
import { ChaosStatusDto, ToxicType } from '../../types/chaos';
import { healChaos, injectChaos } from '../../services/chaosApi';

interface ChaosInjectorPanelProps {
  status: ChaosStatusDto | null;
  onRefresh: () => void;
}

export const ChaosInjectorPanel: React.FC<ChaosInjectorPanelProps> = ({ status, onRefresh }) => {
  const [selectedProxy, setSelectedProxy] = useState<string>('postgres-proxy');
  const [toxicType, setToxicType] = useState<ToxicType>('LATENCY');
  const [latencyMs, setLatencyMs] = useState<number>(500);
  const [jitterMs, setJitterMs] = useState<number>(50);
  const [dropRate, setDropRate] = useState<number>(30); // percentage 30%
  const [operatorId, setOperatorId] = useState<string>('ops-lead');
  const [justification, setJustification] = useState<string>('Resilience and failover verification');

  const [loading, setLoading] = useState(false);
  const [message, setMessage] = useState<{ type: 'success' | 'error'; text: string } | null>(null);

  const handleInject = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!operatorId.trim() || !justification.trim()) {
      setMessage({ type: 'error', text: 'Operator ID and justification are mandatory' });
      return;
    }

    setLoading(true);
    setMessage(null);
    try {
      await injectChaos({
        proxyName: selectedProxy,
        toxicType,
        latencyMs: toxicType === 'LATENCY' ? latencyMs : undefined,
        jitterMs: toxicType === 'LATENCY' ? jitterMs : undefined,
        dropRate: toxicType === 'PACKET_LOSS' ? dropRate / 100 : undefined,
        operatorId,
        justification,
      });
      setMessage({ type: 'success', text: `Injected ${toxicType} on ${selectedProxy}` });
      onRefresh();
    } catch (err: unknown) {
      setMessage({ type: 'error', text: err instanceof Error ? err.message : 'Injection failed' });
    } finally {
      setLoading(false);
    }
  };

  const handleHeal = async () => {
    setLoading(true);
    setMessage(null);
    try {
      await healChaos(undefined, operatorId, 'Manual heal of all injected faults');
      setMessage({ type: 'success', text: 'All chaos faults healed successfully' });
      onRefresh();
    } catch (err: unknown) {
      setMessage({ type: 'error', text: err instanceof Error ? err.message : 'Healing failed' });
    } finally {
      setLoading(false);
    }
  };

  const totalActiveToxics = status?.activeToxicsCount ?? 0;

  return (
    <div className="bg-slate-900/90 border border-slate-800 rounded-xl p-5 shadow-lg">
      <div className="flex items-center justify-between pb-4 border-b border-slate-800 mb-4">
        <div className="flex items-center gap-2.5">
          <div className="p-2 rounded-lg bg-rose-500/10 text-rose-400 border border-rose-500/20">
            <Flame className="w-5 h-5" />
          </div>
          <div>
            <h3 className="text-sm font-bold text-slate-100 flex items-center gap-2">
              Chaos Fault Injector (Toxiproxy)
              <span
                className={`text-[10px] px-2 py-0.5 rounded-full font-bold ${
                  totalActiveToxics > 0
                    ? 'bg-rose-500/20 text-rose-300 border border-rose-500/30 animate-pulse'
                    : 'bg-emerald-500/20 text-emerald-300 border border-emerald-500/30'
                }`}
              >
                {totalActiveToxics > 0 ? `${totalActiveToxics} TOXICS ACTIVE` : 'HEALTHY'}
              </span>
            </h3>
            <p className="text-[11px] text-slate-400">
              Simulate network latency, packet slicer loss, and partitions across dependencies
            </p>
          </div>
        </div>

        <button
          onClick={handleHeal}
          disabled={loading || totalActiveToxics === 0}
          className={`flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-semibold transition-all ${
            totalActiveToxics > 0
              ? 'bg-emerald-600 hover:bg-emerald-500 text-white shadow-lg shadow-emerald-600/20'
              : 'bg-slate-800 text-slate-500 cursor-not-allowed'
          }`}
        >
          <ShieldCheck className="w-4 h-4" />
          Heal All Chaos
        </button>
      </div>

      {message && (
        <div
          className={`mb-4 px-3.5 py-2 rounded-lg text-xs flex items-center gap-2 ${
            message.type === 'success'
              ? 'bg-emerald-950/50 text-emerald-300 border border-emerald-800'
              : 'bg-rose-950/50 text-rose-300 border border-rose-800'
          }`}
        >
          {message.type === 'success' ? (
            <CheckCircle2 className="w-4 h-4 shrink-0" />
          ) : (
            <AlertTriangle className="w-4 h-4 shrink-0" />
          )}
          <span>{message.text}</span>
        </div>
      )}

      {/* Active Proxies Overview */}
      <div className="grid grid-cols-1 md:grid-cols-3 gap-3 mb-5">
        {status?.proxies.map((p) => {
          const hasToxics = p.activeToxics.length > 0;
          return (
            <div
              key={p.name}
              onClick={() => setSelectedProxy(p.name)}
              className={`p-3 rounded-lg border text-left cursor-pointer transition-all ${
                selectedProxy === p.name
                  ? 'border-primary-500 bg-slate-800/80 shadow-md'
                  : 'border-slate-800 bg-slate-950/50 hover:border-slate-700'
              }`}
            >
              <div className="flex items-center justify-between mb-1">
                <span className="text-xs font-bold text-slate-200">{p.name}</span>
                <span
                  className={`text-[9px] px-1.5 py-0.5 rounded font-mono font-semibold ${
                    hasToxics ? 'bg-rose-500/20 text-rose-300' : 'bg-slate-800 text-slate-400'
                  }`}
                >
                  {hasToxics ? `${p.activeToxics.length} active` : 'normal'}
                </span>
              </div>
              <div className="text-[10px] font-mono text-slate-500 truncate mb-1">
                {p.listenAddress}
              </div>
              {hasToxics && (
                <div className="flex flex-wrap gap-1 mt-1.5">
                  {p.activeToxics.map((t, idx) => (
                    <span
                      key={idx}
                      className="text-[9px] bg-rose-950 text-rose-400 border border-rose-800 px-1 py-0.2 rounded"
                    >
                      {t.type}
                    </span>
                  ))}
                </div>
              )}
            </div>
          );
        })}
      </div>

      {/* Form Controls */}
      <form onSubmit={handleInject} className="space-y-4">
        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
          <div>
            <label className="block text-[11px] font-semibold text-slate-400 mb-1">Target Proxy</label>
            <select
              value={selectedProxy}
              onChange={(e) => setSelectedProxy(e.target.value)}
              className="w-full px-3 py-1.5 rounded-lg bg-slate-950 border border-slate-800 text-xs text-slate-200 focus:outline-none focus:border-primary-500"
            >
              {status?.proxies.map((p) => (
                <option key={p.name} value={p.name}>
                  {p.name} ({p.listenAddress})
                </option>
              ))}
            </select>
          </div>

          <div>
            <label className="block text-[11px] font-semibold text-slate-400 mb-1">Fault Type</label>
            <select
              value={toxicType}
              onChange={(e) => setToxicType(e.target.value as ToxicType)}
              className="w-full px-3 py-1.5 rounded-lg bg-slate-950 border border-slate-800 text-xs text-slate-200 focus:outline-none focus:border-primary-500"
            >
              <option value="LATENCY">Latency & Jitter Delay</option>
              <option value="PACKET_LOSS">Packet Slicer Loss (%)</option>
              <option value="PARTITION">Full TCP Connection Drop</option>
            </select>
          </div>
        </div>

        {toxicType === 'LATENCY' && (
          <div className="grid grid-cols-1 md:grid-cols-2 gap-4 p-3 bg-slate-950/40 rounded-lg border border-slate-800">
            <div>
              <div className="flex justify-between text-xs mb-1">
                <span className="text-slate-400">Added Latency:</span>
                <span className="font-mono font-bold text-amber-300">{latencyMs} ms</span>
              </div>
              <input
                type="range"
                min="50"
                max="2000"
                step="50"
                value={latencyMs}
                onChange={(e) => setLatencyMs(Number(e.target.value))}
                className="w-full accent-amber-500"
              />
            </div>
            <div>
              <div className="flex justify-between text-xs mb-1">
                <span className="text-slate-400">Jitter Variance:</span>
                <span className="font-mono font-bold text-amber-300">± {jitterMs} ms</span>
              </div>
              <input
                type="range"
                min="0"
                max="200"
                step="10"
                value={jitterMs}
                onChange={(e) => setJitterMs(Number(e.target.value))}
                className="w-full accent-amber-500"
              />
            </div>
          </div>
        )}

        {toxicType === 'PACKET_LOSS' && (
          <div className="p-3 bg-slate-950/40 rounded-lg border border-slate-800">
            <div className="flex justify-between text-xs mb-1">
              <span className="text-slate-400">Packet Drop Rate:</span>
              <span className="font-mono font-bold text-rose-300">{dropRate}%</span>
            </div>
            <input
              type="range"
              min="5"
              max="90"
              step="5"
              value={dropRate}
              onChange={(e) => setDropRate(Number(e.target.value))}
              className="w-full accent-rose-500"
            />
          </div>
        )}

        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
          <div>
            <label className="block text-[11px] font-semibold text-slate-400 mb-1">Operator ID</label>
            <input
              type="text"
              value={operatorId}
              onChange={(e) => setOperatorId(e.target.value)}
              placeholder="e.g. srv-admin"
              className="w-full px-3 py-1.5 rounded-lg bg-slate-950 border border-slate-800 text-xs text-slate-200 focus:outline-none focus:border-primary-500"
            />
          </div>
          <div>
            <label className="block text-[11px] font-semibold text-slate-400 mb-1">Justification</label>
            <input
              type="text"
              value={justification}
              onChange={(e) => setJustification(e.target.value)}
              placeholder="Required justification for audit log"
              className="w-full px-3 py-1.5 rounded-lg bg-slate-950 border border-slate-800 text-xs text-slate-200 focus:outline-none focus:border-primary-500"
            />
          </div>
        </div>

        <button
          type="submit"
          disabled={loading}
          className="flex items-center justify-center gap-2 w-full py-2 px-4 rounded-lg bg-rose-600 hover:bg-rose-500 text-white font-semibold text-xs shadow-lg shadow-rose-600/20 transition-all disabled:bg-slate-800 disabled:text-slate-500"
        >
          {loading ? <RefreshCw className="w-4 h-4 animate-spin" /> : <Zap className="w-4 h-4" />}
          Inject Fault Condition
        </button>
      </form>
    </div>
  );
};
