import React, { useEffect, useState } from 'react';
import {
  X,
  RotateCcw,
  AlertOctagon,
  RefreshCw,
  Clock,
  CheckCircle2,
  AlertTriangle,
} from 'lucide-react';
import { DeadLetterOutboxEventDto } from '../../types/dashboard';
import { fetchDeadLetterEvents, replayDeadLetterEvent } from '../../services/dashboardApi';

interface DlqInspectorModalProps {
  isOpen: boolean;
  onClose: () => void;
  onReplaySuccess?: () => void;
}

export const DlqInspectorModal: React.FC<DlqInspectorModalProps> = ({
  isOpen,
  onClose,
  onReplaySuccess,
}) => {
  const [events, setEvents] = useState<DeadLetterOutboxEventDto[]>([]);
  const [loading, setLoading] = useState(false);
  const [selectedEvent, setSelectedEvent] = useState<DeadLetterOutboxEventDto | null>(null);

  // Replay inputs
  const [operatorId, setOperatorId] = useState('ops-lead');
  const [justification, setJustification] = useState('Broker recovered; manual replay');
  const [replaying, setReplaying] = useState(false);
  const [message, setMessage] = useState<{ type: 'success' | 'error'; text: string } | null>(null);

  const loadDlqEvents = async () => {
    setLoading(true);
    try {
      const data = await fetchDeadLetterEvents();
      setEvents(data);
      if (data.length > 0 && !selectedEvent) {
        setSelectedEvent(data[0]);
      }
    } catch {
      // ignore
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    if (isOpen) {
      loadDlqEvents();
    }
  }, [isOpen]);

  if (!isOpen) return null;

  const handleReplay = async () => {
    if (!selectedEvent) return;
    if (!operatorId.trim() || !justification.trim()) {
      setMessage({ type: 'error', text: 'Operator ID and justification are required' });
      return;
    }

    setReplaying(true);
    setMessage(null);
    try {
      await replayDeadLetterEvent(selectedEvent.eventId, {
        operatorId,
        justification,
      });
      setMessage({ type: 'success', text: `Event ${selectedEvent.eventId.substring(0, 8)}... replayed!` });
      await loadDlqEvents();
      setSelectedEvent(null);
      if (onReplaySuccess) onReplaySuccess();
    } catch (err: unknown) {
      setMessage({ type: 'error', text: err instanceof Error ? err.message : 'Replay failed' });
    } finally {
      setReplaying(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/80 backdrop-blur-sm p-4">
      <div className="bg-slate-900 border border-slate-800 rounded-2xl w-full max-w-4xl shadow-2xl flex flex-col max-h-[85vh] overflow-hidden animate-in fade-in zoom-in-95 duration-150">
        {/* Header */}
        <div className="flex items-center justify-between px-6 py-4 border-b border-slate-800">
          <div className="flex items-center gap-2.5">
            <div className="p-2 rounded-lg bg-rose-500/10 text-rose-400 border border-rose-500/20">
              <AlertOctagon className="w-5 h-5" />
            </div>
            <div>
              <h2 className="text-base font-bold text-slate-100 flex items-center gap-2">
                Dead-Letter Queue (DLQ) Forensics
                <span className="text-[10px] font-mono px-2 py-0.5 rounded-full bg-rose-500/20 text-rose-300 border border-rose-500/30">
                  {events.length} POISON MESSAGES
                </span>
              </h2>
              <p className="text-xs text-slate-400">
                Inspect failed outbox dispatches and trigger manual re-publication
              </p>
            </div>
          </div>

          <div className="flex items-center gap-2">
            <button
              onClick={loadDlqEvents}
              disabled={loading}
              className="p-1.5 rounded-lg text-slate-400 hover:text-slate-200 hover:bg-slate-800 transition-colors"
            >
              <RefreshCw className={`w-4 h-4 ${loading ? 'animate-spin' : ''}`} />
            </button>
            <button
              onClick={onClose}
              className="p-1.5 rounded-lg text-slate-400 hover:text-slate-200 hover:bg-slate-800 transition-colors"
            >
              <X className="w-5 h-5" />
            </button>
          </div>
        </div>

        {/* Content Body */}
        <div className="flex-1 flex overflow-hidden">
          {/* Left Event List */}
          <div className="w-1/2 border-r border-slate-800 overflow-y-auto divide-y divide-slate-800/60 p-2">
            {events.length === 0 ? (
              <div className="p-8 text-center text-xs text-slate-500">
                <CheckCircle2 className="w-8 h-8 text-emerald-500/40 mx-auto mb-2" />
                No poisoned events in Dead-Letter Queue
              </div>
            ) : (
              events.map((ev) => {
                const isSelected = selectedEvent?.eventId === ev.eventId;
                return (
                  <button
                    key={ev.eventId}
                    onClick={() => setSelectedEvent(ev)}
                    className={`w-full text-left p-3 rounded-lg transition-all ${
                      isSelected
                        ? 'bg-slate-800 border-l-2 border-rose-500'
                        : 'hover:bg-slate-800/40'
                    }`}
                  >
                    <div className="flex items-center justify-between mb-1">
                      <span className="font-semibold text-xs text-slate-200 truncate">
                        {ev.eventType}
                      </span>
                      <span className="text-[9px] font-mono font-bold px-1.5 py-0.5 rounded bg-rose-500/20 text-rose-300">
                        {ev.retryCount} retries
                      </span>
                    </div>
                    <div className="font-mono text-[10px] text-slate-400 truncate mb-1">
                      ID: {ev.eventId}
                    </div>
                    <div className="flex items-center justify-between text-[10px] text-slate-500">
                      <span>Key: {ev.partitionKey || 'none'}</span>
                      <span className="flex items-center gap-1">
                        <Clock className="w-2.5 h-2.5" />
                        {new Date(ev.createdAt).toLocaleTimeString()}
                      </span>
                    </div>
                  </button>
                );
              })
            )}
          </div>

          {/* Right Event Details & Replay Form */}
          <div className="w-1/2 flex flex-col overflow-y-auto p-4 space-y-4">
            {selectedEvent ? (
              <>
                {message && (
                  <div
                    className={`px-3 py-2 rounded-lg text-xs flex items-center gap-2 ${
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

                <div>
                  <h4 className="text-xs font-bold text-slate-300 mb-1">Error Diagnostics</h4>
                  <pre className="p-3 bg-slate-950 rounded-lg border border-slate-800 text-[10px] font-mono text-rose-400 overflow-x-auto whitespace-pre-wrap max-h-32">
                    {selectedEvent.errorDetails || 'No stack trace recorded'}
                  </pre>
                </div>

                <div>
                  <h4 className="text-xs font-bold text-slate-300 mb-1">Message Payload</h4>
                  <pre className="p-3 bg-slate-950 rounded-lg border border-slate-800 text-[10px] font-mono text-slate-300 overflow-x-auto whitespace-pre-wrap max-h-36">
                    {(() => {
                      try {
                        return JSON.stringify(JSON.parse(selectedEvent.payload), null, 2);
                      } catch {
                        return selectedEvent.payload;
                      }
                    })()}
                  </pre>
                </div>

                <div className="pt-3 border-t border-slate-800 space-y-3">
                  <h4 className="text-xs font-bold text-slate-200">Replay Message to Outbox</h4>
                  <div>
                    <label className="block text-[10px] font-semibold text-slate-400 mb-1">
                      Operator ID
                    </label>
                    <input
                      type="text"
                      value={operatorId}
                      onChange={(e) => setOperatorId(e.target.value)}
                      className="w-full px-2.5 py-1.5 rounded-lg bg-slate-950 border border-slate-800 text-xs text-slate-200 focus:outline-none focus:border-primary-500"
                    />
                  </div>
                  <div>
                    <label className="block text-[10px] font-semibold text-slate-400 mb-1">
                      Justification
                    </label>
                    <input
                      type="text"
                      value={justification}
                      onChange={(e) => setJustification(e.target.value)}
                      className="w-full px-2.5 py-1.5 rounded-lg bg-slate-950 border border-slate-800 text-xs text-slate-200 focus:outline-none focus:border-primary-500"
                    />
                  </div>

                  <button
                    onClick={handleReplay}
                    disabled={replaying}
                    className="flex items-center justify-center gap-2 w-full py-2 px-4 rounded-lg bg-indigo-600 hover:bg-indigo-500 text-white font-semibold text-xs shadow-lg shadow-indigo-600/20 transition-all disabled:opacity-50"
                  >
                    {replaying ? (
                      <RefreshCw className="w-4 h-4 animate-spin" />
                    ) : (
                      <RotateCcw className="w-4 h-4" />
                    )}
                    Replay Event (Reset to PENDING)
                  </button>
                </div>
              </>
            ) : (
              <div className="m-auto text-center text-xs text-slate-500">
                Select an event from the list to inspect details and replay
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  );
};
