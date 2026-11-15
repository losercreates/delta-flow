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
          'SAGA_COMPLETED',
          'SAGA_COMPENSATED',
          'OUTBOX_PUBLISHED',
          'OUTBOX_DEAD_LETTER',
          'CIRCUIT_BREAKER_TRIPPED',
          'OPERATOR_INTERVENTION',
        ];

        eventNames.forEach((name) => {
          eventSource?.addEventListener(name, handleIncomingMessage);
        });

        eventSource.onerror = () => {
          if (!isCancelled) {
            setConnected(false);
            setError('Stream disconnected, attempting reconnection...');
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
