import React, { useState, useEffect, useCallback } from 'react';
import {
  Server,
  Crown,
  Database,
  Trash2,
  RefreshCw,
  CheckCircle2,
  AlertTriangle,
  Play,
  RotateCcw,
  Zap,
} from 'lucide-react';
import {
  ClusterNodeDto,
  LeaderStatusDto,
  RetentionSummaryDto,
  OutboxLeaseReclaimResult,
} from '../../types/cluster';
import {
  fetchClusterNodes,
  fetchLeaderStatus,
  fetchRetentionStatus,
  triggerRetentionPruning,
  triggerOutboxLeaseReclaim,
} from '../../services/clusterApi';

interface ClusterTopologyDashboardProps {
  lastEventTimestamp?: string;
}

export const ClusterTopologyDashboard: React.FC<ClusterTopologyDashboardProps> = ({
  lastEventTimestamp,
}) => {
  const [nodes, setNodes] = useState<ClusterNodeDto[]>([]);
  const [leader, setLeader] = useState<LeaderStatusDto | null>(null);
  const [retention, setRetention] = useState<RetentionSummaryDto | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Retention Trigger Modal / State
  const [isPruning, setIsPruning] = useState(false);
  const [retentionDays, setRetentionDays] = useState(30);
  const [batchSize, setBatchSize] = useState(500);
  const [operatorId, setOperatorId] = useState('operator-ui');
  const [justification, setJustification] = useState('Manual SLA cleanup sweep');
  const [retentionFeedback, setRetentionFeedback] = useState<string | null>(null);

  // Outbox Lease Reclaim State
  const [isReclaiming, setIsReclaiming] = useState(false);
  const [reclaimResult, setReclaimResult] = useState<OutboxLeaseReclaimResult | null>(null);

  const loadClusterState = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [nodesData, leaderData, retentionData] = await Promise.all([
        fetchClusterNodes(),
        fetchLeaderStatus(),
        fetchRetentionStatus(),
      ]);
      setNodes(nodesData);
      setLeader(leaderData);
      setRetention(retentionData);
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : 'Failed to fetch cluster state';
      setError(message);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadClusterState();
  }, [loadClusterState, lastEventTimestamp]);

  const handleTriggerRetention = async (e: React.FormEvent) => {
    e.preventDefault();
    setIsPruning(true);
    setRetentionFeedback(null);
    try {
      const result = await triggerRetentionPruning({
        retentionDays,
        batchSize,
        operatorId,
        justification,
      });
      setRetention(result);
      setRetentionFeedback(`Pruning completed! Total pruned records: ${result.totalPrunedRecords}`);
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : 'Retention trigger failed';
      setRetentionFeedback(`Error: ${message}`);
    } finally {
      setIsPruning(false);
    }
  };

  const handleReclaimLeases = async () => {
    setIsReclaiming(true);
    try {
      const res = await triggerOutboxLeaseReclaim();
      setReclaimResult(res);
      await loadClusterState();
    } catch (err: unknown) {
      console.error('Failed to reclaim leases:', err);
    } finally {
      setIsReclaiming(false);
    }
  };

  const calculateHeartbeatAge = (lastHeartbeatAt: string) => {
    const diffMs = Date.now() - new Date(lastHeartbeatAt).getTime();
    const seconds = Math.max(0, Math.floor(diffMs / 1000));
    return `${seconds}s ago`;
  };

  return (
    <div className="space-y-6">
      {/* Top Bar: Cluster Status & Refresh */}
      <div className="flex flex-col sm:flex-row items-start sm:items-center justify-between gap-4 bg-slate-900 border border-slate-800 p-6 rounded-2xl shadow-xl">
        <div className="flex items-center gap-3">
          <div className="w-12 h-12 rounded-2xl bg-amber-500/10 border border-amber-500/30 flex items-center justify-center text-amber-400">
            <Crown className="w-6 h-6 animate-pulse" />
          </div>
          <div>
            <div className="flex items-center gap-2">
              <h2 className="text-xl font-bold text-slate-100">Cluster Topology & Resilience</h2>
              <span className="px-2.5 py-0.5 text-xs font-mono rounded-full bg-emerald-500/20 text-emerald-300 border border-emerald-500/30 font-semibold">
                {nodes.length} Nodes Online
              </span>
            </div>
            <p className="text-xs text-slate-400 mt-1">
              Active Coordinator: <span className="font-mono text-amber-300 font-bold">{leader?.leaderNodeId ?? 'ELECTING...'}</span>
              {leader?.isCurrentNodeLeader && (
                <span className="ml-2 text-[10px] px-2 py-0.5 rounded bg-amber-500/20 text-amber-300 border border-amber-500/40 font-bold">
                  LOCAL INSTANCE
                </span>
              )}
            </p>
          </div>
        </div>

        <div className="flex items-center gap-3">
          <button
            onClick={handleReclaimLeases}
            disabled={isReclaiming}
            className="flex items-center gap-2 px-4 py-2 rounded-xl bg-indigo-600/20 hover:bg-indigo-600/30 border border-indigo-500/40 text-indigo-300 text-xs font-bold transition disabled:opacity-50"
          >
            <RotateCcw className={`w-3.5 h-3.5 ${isReclaiming ? 'animate-spin' : ''}`} />
            Reclaim In-Flight Leases
          </button>
          <button
            onClick={loadClusterState}
            disabled={loading}
            className="flex items-center gap-2 px-4 py-2 rounded-xl bg-slate-800 hover:bg-slate-700 border border-slate-700 text-slate-300 text-xs font-bold transition disabled:opacity-50"
          >
            <RefreshCw className={`w-3.5 h-3.5 ${loading ? 'animate-spin text-primary-400' : ''}`} />
            Refresh
          </button>
        </div>
      </div>

      {error && (
        <div className="p-4 rounded-xl bg-rose-500/10 border border-rose-500/30 text-rose-300 text-xs flex items-center gap-2">
          <AlertTriangle className="w-4 h-4 text-rose-400 shrink-0" />
          <span>{error}</span>
        </div>
      )}

      {/* Outbox Reclamation Banner (if run) */}
      {reclaimResult && (
        <div className="p-4 rounded-xl bg-indigo-950/40 border border-indigo-500/30 flex items-center justify-between gap-4 text-xs">
          <div className="flex items-center gap-2 text-indigo-200">
            <Zap className="w-4 h-4 text-indigo-400" />
            <span>
              Lease Reaper Sweep: <strong>{reclaimResult.scannedCount}</strong> scanned,{' '}
              <strong className="text-emerald-400">{reclaimResult.reclaimedCount}</strong> reclaimed to PENDING,{' '}
              <strong className="text-rose-400">{reclaimResult.deadLetteredCount}</strong> moved to DEAD_LETTER.
            </span>
          </div>
          <span className="text-[10px] text-slate-500 font-mono">
            {new Date(reclaimResult.timestamp).toLocaleTimeString()}
          </span>
        </div>
      )}

      {/* Cluster Nodes Grid */}
      <div>
        <h3 className="text-sm font-bold text-slate-300 uppercase tracking-wider mb-3 flex items-center gap-2">
          <Server className="w-4 h-4 text-primary-400" />
          Discovered Cluster Nodes
        </h3>

        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
          {nodes.map((node) => {
            const isNodeLeader = node.role === 'LEADER';
            return (
              <div
                key={node.nodeId}
                className={`p-5 rounded-2xl border transition-all ${
                  isNodeLeader
                    ? 'bg-gradient-to-br from-slate-900 to-amber-950/20 border-amber-500/40 shadow-lg shadow-amber-950/20'
                    : 'bg-slate-900 border-slate-800'
                }`}
              >
                <div className="flex items-start justify-between">
                  <div className="flex items-center gap-2.5">
                    <div
                      className={`w-9 h-9 rounded-xl flex items-center justify-center ${
                        isNodeLeader
                          ? 'bg-amber-500/20 text-amber-400 border border-amber-500/40'
                          : 'bg-slate-800 text-slate-400 border border-slate-700'
                      }`}
                    >
                      {isNodeLeader ? <Crown className="w-5 h-5" /> : <Server className="w-5 h-5" />}
                    </div>
                    <div>
                      <h4 className="text-sm font-bold text-slate-100 font-mono">{node.nodeId}</h4>
                      <p className="text-[11px] text-slate-400">
                        {node.hostname}:{node.port}
                      </p>
                    </div>
                  </div>

                  <span
                    className={`text-[10px] font-bold px-2 py-0.5 rounded-full border ${
                      isNodeLeader
                        ? 'bg-amber-500/20 text-amber-300 border-amber-500/40'
                        : 'bg-slate-800 text-slate-400 border-slate-700'
                    }`}
                  >
                    {node.role}
                  </span>
                </div>

                <div className="mt-4 pt-3 border-t border-slate-800/80 grid grid-cols-2 gap-2 text-[11px]">
                  <div>
                    <span className="text-slate-500 block">Heartbeat</span>
                    <span className="text-slate-200 font-mono flex items-center gap-1 mt-0.5">
                      <span
                        className={`w-2 h-2 rounded-full ${
                          node.isHealthy ? 'bg-emerald-400 animate-ping' : 'bg-rose-500'
                        }`}
                      />
                      {calculateHeartbeatAge(node.lastHeartbeatAt)}
                    </span>
                  </div>
                  <div>
                    <span className="text-slate-500 block">Version</span>
                    <span className="text-slate-300 font-mono mt-0.5 block">{node.version}</span>
                  </div>
                </div>
              </div>
            );
          })}
        </div>
      </div>

      {/* Storage Tiering & Idempotency Retention Manager */}
      <div className="bg-slate-900 border border-slate-800 rounded-2xl p-6 shadow-xl">
        <div className="flex items-center justify-between mb-4 pb-3 border-b border-slate-800">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 rounded-xl bg-indigo-500/10 border border-indigo-500/30 flex items-center justify-center text-indigo-400">
              <Database className="w-5 h-5" />
            </div>
            <div>
              <h3 className="text-base font-bold text-slate-100">
                Idempotency Storage Tiering & Retention Manager
              </h3>
              <p className="text-xs text-slate-400">
                Automated bounded batch pruning of terminal RESOLVED idempotency records (SLA Window: {retention?.retentionDays ?? 30} days)
              </p>
            </div>
          </div>

          <span
            className={`text-xs font-bold px-3 py-1 rounded-full border ${
              retention?.enabled
                ? 'bg-emerald-500/20 text-emerald-300 border-emerald-500/30'
                : 'bg-slate-800 text-slate-400 border-slate-700'
            }`}
          >
            {retention?.enabled ? 'RETENTION ACTIVE' : 'DISABLED'}
          </span>
        </div>

        {/* Retention Statistics Grid */}
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4 mb-6">
          <div className="p-4 rounded-xl bg-slate-950/60 border border-slate-800">
            <span className="text-slate-500 text-xs block mb-1">Retention Window</span>
            <span className="text-lg font-mono font-bold text-slate-200">
              {retention?.retentionDays ?? 30} Days
            </span>
          </div>

          <div className="p-4 rounded-xl bg-slate-950/60 border border-slate-800">
            <span className="text-slate-500 text-xs block mb-1">Batch Size</span>
            <span className="text-lg font-mono font-bold text-slate-200">
              {retention?.batchSize ?? 500} Rows
            </span>
          </div>

          <div className="p-4 rounded-xl bg-slate-950/60 border border-slate-800">
            <span className="text-slate-500 text-xs block mb-1">Total Pruned Records</span>
            <span className="text-lg font-mono font-bold text-emerald-400">
              {retention?.totalPrunedRecords ?? 0}
            </span>
          </div>

          <div className="p-4 rounded-xl bg-slate-950/60 border border-slate-800">
            <span className="text-slate-500 text-xs block mb-1">Oldest Remaining Record</span>
            <span className="text-xs font-mono text-slate-300 mt-1 block truncate">
              {retention?.oldestRemainingResolvedRecord
                ? new Date(retention.oldestRemainingResolvedRecord).toLocaleString()
                : 'None'}
            </span>
          </div>
        </div>

        {/* On-Demand Trigger Form */}
        <div className="p-5 rounded-xl bg-slate-950 border border-slate-800/80">
          <h4 className="text-xs font-bold text-slate-300 uppercase tracking-wider mb-3 flex items-center gap-2">
            <Play className="w-3.5 h-3.5 text-primary-400" />
            Trigger On-Demand Retention Sweep
          </h4>

          <form onSubmit={handleTriggerRetention} className="space-y-4">
            <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-3 text-xs">
              <div>
                <label className="text-slate-400 block mb-1">Retention SLA (Days)</label>
                <input
                  type="number"
                  min="1"
                  max="365"
                  value={retentionDays}
                  onChange={(e) => setRetentionDays(Number(e.target.value))}
                  className="w-full bg-slate-900 border border-slate-700 rounded-lg px-3 py-1.5 text-slate-200 font-mono focus:outline-none focus:border-primary-500"
                />
              </div>

              <div>
                <label className="text-slate-400 block mb-1">Batch Limit</label>
                <input
                  type="number"
                  min="50"
                  max="5000"
                  value={batchSize}
                  onChange={(e) => setBatchSize(Number(e.target.value))}
                  className="w-full bg-slate-900 border border-slate-700 rounded-lg px-3 py-1.5 text-slate-200 font-mono focus:outline-none focus:border-primary-500"
                />
              </div>

              <div>
                <label className="text-slate-400 block mb-1">Operator ID</label>
                <input
                  type="text"
                  value={operatorId}
                  onChange={(e) => setOperatorId(e.target.value)}
                  className="w-full bg-slate-900 border border-slate-700 rounded-lg px-3 py-1.5 text-slate-200 font-mono focus:outline-none focus:border-primary-500"
                  required
                />
              </div>

              <div>
                <label className="text-slate-400 block mb-1">Audit Justification</label>
                <input
                  type="text"
                  value={justification}
                  onChange={(e) => setJustification(e.target.value)}
                  className="w-full bg-slate-900 border border-slate-700 rounded-lg px-3 py-1.5 text-slate-200 focus:outline-none focus:border-primary-500"
                  required
                />
              </div>
            </div>

            <div className="flex items-center justify-between pt-2">
              <span className="text-[11px] text-slate-500 flex items-center gap-1">
                <CheckCircle2 className="w-3.5 h-3.5 text-emerald-500" />
                Active PROCESSING leases are strictly protected by Principle I
              </span>

              <button
                type="submit"
                disabled={isPruning}
                className="flex items-center gap-2 px-4 py-2 rounded-xl bg-primary-600 hover:bg-primary-500 text-white font-bold text-xs shadow-lg shadow-primary-600/30 transition disabled:opacity-50"
              >
                <Trash2 className={`w-3.5 h-3.5 ${isPruning ? 'animate-pulse' : ''}`} />
                {isPruning ? 'Pruning...' : 'Execute Retention Pruning'}
              </button>
            </div>
          </form>

          {retentionFeedback && (
            <div className="mt-3 p-3 rounded-lg bg-slate-900 border border-slate-700 text-xs font-mono text-slate-300">
              {retentionFeedback}
            </div>
          )}
        </div>
      </div>
    </div>
  );
};
