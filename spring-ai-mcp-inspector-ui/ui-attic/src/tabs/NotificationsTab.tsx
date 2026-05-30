// Portions adapted from modelcontextprotocol/inspector (Apache-2.0)
// https://github.com/modelcontextprotocol/inspector

import { useEffect, useState } from 'react';
import { subscribeEvents, type InspectorEvent } from '../api/sse';
import {
  HistoryAndNotifications,
  type HistoryEntry,
  type NotificationEntry,
} from '../components/HistoryAndNotifications';

let seq = 1;

interface RpcRequestPayload {
  method?: string;
  params?: unknown;
}

interface RpcResponsePayload {
  method?: string;
  result?: unknown;
  error?: { code: number; message: string; data?: unknown };
}

/**
 * Notifications tab: subscribes to SSE events and renders the request/response
 * timeline next to a notifications stream.
 */
export function NotificationsTab() {
  const [history, setHistory] = useState<HistoryEntry[]>([]);
  const [notifications, setNotifications] = useState<NotificationEntry[]>([]);
  const [connectionError, setConnectionError] = useState<string | null>(null);

  useEffect(() => {
    setConnectionError(null);
    const subscription = subscribeEvents(
      (event: InspectorEvent) => {
        if (event.type === 'rpc-request') {
          const payload = (event.payload as RpcRequestPayload) ?? {};
          setHistory((prev) =>
            [
              {
                id: seq++,
                timestamp: event.timestamp,
                direction: 'request' as const,
                method: payload.method,
                params: payload.params,
              },
              ...prev,
            ].slice(0, 100),
          );
        } else if (event.type === 'rpc-response') {
          const payload = (event.payload as RpcResponsePayload) ?? {};
          setHistory((prev) =>
            [
              {
                id: seq++,
                timestamp: event.timestamp,
                direction: 'response' as const,
                method: payload.method,
                result: payload.result,
                error: payload.error,
              },
              ...prev,
            ].slice(0, 100),
          );
        } else {
          setNotifications((prev) =>
            [
              { id: seq++, timestamp: event.timestamp, type: event.type, payload: event.payload },
              ...prev,
            ].slice(0, 200),
          );
        }
      },
      () => setConnectionError('SSE connection failed'),
    );
    return () => subscription.close();
  }, []);

  return (
    <section className="space-y-4">
      <header>
        <h2 className="text-xl font-semibold text-foreground">Notifications</h2>
        <p className="text-sm text-muted-foreground">Live MCP server notifications and request history.</p>
      </header>
      {connectionError && (
        <div className="rounded-md border border-amber-300 bg-amber-50 px-4 py-3 text-sm text-amber-900 dark:bg-amber-900/30 dark:text-amber-100">
          {connectionError}
        </div>
      )}
      <HistoryAndNotifications history={history} notifications={notifications} />
    </section>
  );
}
