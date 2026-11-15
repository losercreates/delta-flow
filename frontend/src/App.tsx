import React, { useEffect, useState, useCallback } from 'react';
import {
  Layers,
  Lock,
  Search,
  RefreshCw,
  Clock,
  Filter,
} from 'lucide-react';
import { SagaGraphDto, SagaSummaryDto, TelemetrySummaryDto } from './types/dashboard';
import { fetchSagaGraph, fetchSagas, fetchTelemetry } from './services/dashboardApi';
import { useDashboardStream } from './hooks/useDashboardStream';
import { TelemetryHeader } from './components/telemetry/TelemetryHeader';
import { SagaFlowDag } from './components/dag/SagaFlowDag';
import { IdempotencyInspector } from './components/forensics/IdempotencyInspector';

type ActiveTab = 'dag' | 'idempotency';

export const App: React.FC = () => {
  const [activeTab, setActiveTab] = useState<ActiveTab>('dag');

  // Telemetry
  const [telemetry, setTelemetry] = useState<TelemetrySummaryDto | null>(null);

  // Sagas List
  const [sagas, setSagas] = useState<SagaSummaryDto[]>([]);
  const [sagasLoading, setSagasLoading] = useState(false);
  const [statusFilter, setStatusFilter] = useState('');
  const [searchQuery, setSearchQuery] = useState('');
  const [selectedSagaId, setSelectedSagaId] = useState<string | null>(null);

  // Active Saga Graph
  const [activeGraph, setActiveGraph] = useState<SagaGraphDto | null>(null);
  const [graphLoading, setGraphLoading] = useState(false);

  // Load telemetry
  const loadTelemetryData = useCallback(async () => {
    try {
      const data = await fetchTelemetry();
      setTelemetry(data);
    } catch {
      // ignore telemetry fetch errors in background
    }
  }, []);

  // Load sagas list
  const loadSagasList = useCallback(async () => {
    setSagasLoading(true);
    try {
      const data = await fetchSagas(statusFilter || undefined, undefined, 0, 50);
      setSagas(data);
      if (!selectedSagaId && data.length > 0) {
        setSelectedSagaId(data[0].sagaId);
      }
    } catch {
      // ignore
    } finally {
      setSagasLoading(false);
    }
  }, [statusFilter, selectedSagaId]);

  // Load active saga graph
  const loadActiveGraph = useCallback(async (sagaId: string) => {
    setGraphLoading(true);
    try {
      const graphData = await fetchSagaGraph(sagaId);
      setActiveGraph(graphData);
    } catch {
      setActiveGraph(null);
    } finally {
      setGraphLoading(false);
    }
  }, []);

  // SSE Stream
  const { connected: isLiveConnected } = useDashboardStream({
    onEvent: (event) => {
      loadTelemetryData();
      loadSagasList();
      if (selectedSagaId && event.sagaId === selectedSagaId) {
        loadActiveGraph(selectedSagaId);
      }
    },
  });

  useEffect(() => {
    loadTelemetryData();
    loadSagasList();
  }, [loadTelemetryData, loadSagasList]);

  useEffect(() => {
    if (selectedSagaId) {
      loadActiveGraph(selectedSagaId);
    }
  }, [selectedSagaId, loadActiveGraph]);

  const filteredSagas = sagas.filter((s) => {
    if (!searchQuery.trim()) return true;
    const q = searchQuery.toLowerCase();
    return (
      s.sagaId.toLowerCase().includes(q) ||
      s.sagaType.toLowerCase().includes(q) ||
      s.tenantId.toLowerCase().includes(q) ||
      s.idempotencyKey.toLowerCase().includes(q)
    );
  });

  return (
    <div className="flex flex-col h-screen w-screen bg-slate-950 text-slate-100 overflow-hidden font-sans">
      {/* Top Header */}
      <TelemetryHeader telemetry={telemetry} isLiveConnected={isLiveConnected} />

      {/* Main Tab Bar */}
      <div className="flex items-center justify-between px-6 bg-slate-900/80 border-b border-slate-800">
        <div className="flex items-center gap-1">
          <button
            onClick={() => setActiveTab('dag')}
            className={`flex items-center gap-2 px-4 py-3 text-xs font-semibold border-b-2 transition-all ${
              activeTab === 'dag'
                ? 'border-primary-500 text-primary-400'
                : 'border-transparent text-slate-400 hover:text-slate-200'
            }`}
          >
            <Layers className="w-4 h-4" />
            Saga Flow Visualizer
          </button>

          <button
            onClick={() => setActiveTab('idempotency')}
            className={`flex items-center gap-2 px-4 py-3 text-xs font-semibold border-b-2 transition-all ${
              activeTab === 'idempotency'
                ? 'border-primary-500 text-primary-400'
                : 'border-transparent text-slate-400 hover:text-slate-200'
            }`}
          >
            <Lock className="w-4 h-4" />
            Idempotency Inspector & Forensics
          </button>
        </div>

        <div className="text-[11px] font-mono text-slate-500">
          Selected: {selectedSagaId ? selectedSagaId.substring(0, 8) + '...' : 'None'}
        </div>
      </div>

      {/* Main Body */}
      <div className="flex-1 flex overflow-hidden">
        {activeTab === 'dag' ? (
          <div className="flex-1 flex h-full overflow-hidden">
            {/* Left Saga Selector Sidebar */}
            <aside
              className="w-80 border-r border-slate-800 bg-slate-900/50 flex flex-col h-full"
              aria-label="Sagas Selection"
            >
              {/* Filter controls */}
              <div className="p-3 border-b border-slate-800 space-y-2">
                <div className="relative">
                  <Search className="w-3.5 h-3.5 absolute left-2.5 top-2.5 text-slate-500" />
                  <input
                    type="text"
                    value={searchQuery}
                    onChange={(e) => setSearchQuery(e.target.value)}
                    placeholder="Search saga ID or key..."
                    className="w-full pl-8 pr-2.5 py-1.5 rounded-lg bg-slate-950 border border-slate-800 text-xs text-slate-200 placeholder:text-slate-600 focus:outline-none focus:border-primary-500"
                  />
                </div>

                <div className="flex items-center justify-between gap-2">
                  <div className="flex items-center gap-1 text-[11px] text-slate-400">
                    <Filter className="w-3 h-3 text-slate-500" />
                    <span>Status:</span>
                  </div>
                  <select
                    value={statusFilter}
                    onChange={(e) => setStatusFilter(e.target.value)}
                    className="px-2 py-1 rounded bg-slate-950 border border-slate-800 text-xs text-slate-300 focus:outline-none"
                  >
                    <option value="">ALL</option>
                    <option value="EXECUTING">EXECUTING</option>
                    <option value="COMPLETED">COMPLETED</option>
                    <option value="COMPENSATING">COMPENSATING</option>
                    <option value="COMPENSATED">COMPENSATED</option>
                    <option value="FAILED">FAILED</option>
                  </select>
                </div>
              </div>

              {/* Sagas List items */}
              <div className="flex-1 overflow-y-auto divide-y divide-slate-800/60">
                {sagasLoading && sagas.length === 0 ? (
                  <div className="p-6 text-center text-xs text-slate-500">Loading sagas...</div>
                ) : filteredSagas.length === 0 ? (
                  <div className="p-6 text-center text-xs text-slate-500">No sagas found</div>
                ) : (
                  filteredSagas.map((saga) => {
                    const isSelected = saga.sagaId === selectedSagaId;
                    return (
                      <button
                        key={saga.sagaId}
                        onClick={() => setSelectedSagaId(saga.sagaId)}
                        className={`w-full text-left p-3 transition-colors ${
                          isSelected
                            ? 'bg-slate-800/80 border-l-2 border-primary-500'
                            : 'hover:bg-slate-800/40'
                        }`}
                      >
                        <div className="flex items-center justify-between gap-2 mb-1">
                          <span className="font-semibold text-xs text-slate-200 truncate">
                            {saga.sagaType}
                          </span>
                          <span
                            className={`text-[9px] px-1.5 py-0.2 rounded-full font-bold ${
                              saga.status === 'COMPLETED'
                                ? 'bg-emerald-500/20 text-emerald-300'
                                : saga.status === 'EXECUTING'
                                ? 'bg-sky-500/20 text-sky-300 animate-pulse'
                                : saga.status === 'FAILED'
                                ? 'bg-rose-500/20 text-rose-300'
                                : 'bg-amber-500/20 text-amber-300'
                            }`}
                          >
                            {saga.status}
                          </span>
                        </div>

                        <div className="font-mono text-[10px] text-slate-400 truncate mb-1">
                          ID: {saga.sagaId}
                        </div>

                        <div className="flex items-center justify-between text-[10px] text-slate-500">
                          <span>Tenant: {saga.tenantId}</span>
                          <span className="flex items-center gap-1">
                            <Clock className="w-2.5 h-2.5" />
                            {new Date(saga.createdAt).toLocaleTimeString()}
                          </span>
                        </div>
                      </button>
                    );
                  })
                )}
              </div>
            </aside>

            {/* Right Main Flow Canvas */}
            <main className="flex-1 relative h-full">
              {graphLoading && (
                <div className="absolute inset-0 z-20 flex items-center justify-center bg-slate-950/60 backdrop-blur-sm">
                  <RefreshCw className="w-6 h-6 animate-spin text-primary-400" />
                </div>
              )}
              <SagaFlowDag
                graph={activeGraph}
                onRefresh={() => {
                  if (selectedSagaId) loadActiveGraph(selectedSagaId);
                }}
                onInterveneSuccess={() => {
                  loadSagasList();
                  loadTelemetryData();
                  if (selectedSagaId) loadActiveGraph(selectedSagaId);
                }}
              />
            </main>
          </div>
        ) : (
          <main className="flex-1 h-full overflow-hidden">
            <IdempotencyInspector />
          </main>
        )}
      </div>
    </div>
  );
};

export default App;
