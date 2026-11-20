import { useEffect, useRef, useState } from 'react';
import { DashboardLiveEvent } from '../types/dashboard';
import { getDashboardStreamUrl } from '../services/dashboardApi';

interface UseDashboardStreamOptions {
  onEvent?: (event: DashboardLiveEvent) => void;
  maxBuffer?: number;
}

export function useDashboardStream(options?: UseDashboardStreamOptions) {
  const [connected, setConnected] = useState(false);
  const [lastEvent, setLastEvent] = useState<DashboardLiveEvent | null>(null);
  const [events, setEvents] = useState<DashboardLiveEvent[]>([]);
  const [error, setError] = useState<string | null>(null);

  const onEventRef = useRef(options?.onEvent);
  onEventRef.current = options?.onEvent;
  const reconnectDelayRef = useRef(1000);
  const reconnectTimerRef = useRef<number | null>(null);

  const maxBuffer = options?.maxBuffer ?? 50;

  useEffect(() => {
    let eventSource: EventSource | null = null;
    let isCancelled = false;

    const connect = () => {
      if (isCancelled) return;

      try {
        eventSource = new EventSource(getDashboardStreamUrl());

        eventSource.onopen = () => {
          if (!isCancelled) {
            setConnected(true);
            setError(null);
            reconnectDelayRef.current = 1000;
          }
        };

        const handleIncomingMessage = (event: MessageEvent) => {
          if (isCancelled) return;
          try {
            const parsed: DashboardLiveEvent = JSON.parse(event.data);
            setLastEvent(parsed);
            setEvents((prev) => [parsed, ...prev].slice(0, maxBuffer));
            if (onEventRef.current) {
              onEventRef.current(parsed);
            }
          } catch {
            // Heartbeat or plain text ping
          }
        };

        // Listen to default message and named events
        eventSource.onmessage = handleIncomingMessage;

        const eventNames = [
          'SAGA_STARTED',
          'SAGA_STATE_CHANGED',
          'STEP_STATE_CHANGED',
          'SAGA_SUSPENDED',
          'SAGA_RESUMED',
          'SAGA_TIMED_OUT',
          'SAGA_COMPLETED',
          'SAGA_COMPENSATED',
          'OUTBOX_PUBLISHED',
          'OUTBOX_DEAD_LETTER',
          'OUTBOX_REPLAYED',
          'CIRCUIT_BREAKER_TRIPPED',
          'CIRCUIT_BREAKER_RESET',
          'CIRCUIT_BREAKER_TRANSITIONED',
          'CHAOS_INJECTED',
          'CHAOS_HEALED',
          'INVARIANT_CHECKED',
          'OPERATOR_INTERVENTION',
        ];

        eventNames.forEach((name) => {
          eventSource?.addEventListener(name, handleIncomingMessage);
        });

        eventSource.onerror = () => {
          if (!isCancelled) {
            setConnected(false);
            setError('Stream disconnected, attempting reconnection...');
            eventSource?.close();
            // Exponential backoff reconnect
            const nextDelay = Math.min(reconnectDelayRef.current * 1.5, 30000);
            reconnectDelayRef.current = nextDelay;
            reconnectTimerRef.current = window.setTimeout(connect, nextDelay);
          }
        };
      } catch (err: unknown) {
        if (!isCancelled) {
          setConnected(false);
          setError(err instanceof Error ? err.message : 'Failed to connect to SSE stream');
        }
      }
    };

    connect();

    return () => {
      isCancelled = true;
      if (reconnectTimerRef.current) {
        window.clearTimeout(reconnectTimerRef.current);
      }
      if (eventSource) {
        eventSource.close();
      }
    };
  }, [maxBuffer]);

  return {
    connected,
    lastEvent,
    events,
    error,
  };
}
