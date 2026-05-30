// Portions adapted from modelcontextprotocol/inspector (Apache-2.0)
// https://github.com/modelcontextprotocol/inspector

import { useEffect, useMemo, useState } from 'react';
import { ChevronDown, ChevronRight } from 'lucide-react';
import clsx from 'clsx';
import { onRpcEvent, type RpcEvent } from '../api/client';
import { subscribeEvents, type InspectorEvent } from '../api/sse';
import { Button } from '../components/ui/Button';
import { JsonView } from '../components/JsonView';

type Filter = 'all' | 'requests' | 'responses' | 'notifications' | 'errors';

interface ConsoleEntry {
  id: number;
  timestamp: number;
  kind: 'request' | 'response' | 'notification' | 'error';
  method?: string;
  payload: unknown;
}

let counter = 1;

function isErrorPayload(payload: unknown): boolean {
  if (!payload || typeof payload !== 'object') return false;
  return 'error' in (payload as Record<string, unknown>);
}

/**
 * Console tab: streams raw JSON-RPC traffic + server notifications, with
 * filter chips and per-row collapsible JSON.
 */
export function ConsoleTab() {
  const [entries, setEntries] = useState<ConsoleEntry[]>([]);
  const [filter, setFilter] = useState<Filter>('all');

  useEffect(() => {
    const unsubscribe = onRpcEvent((event: RpcEvent) => {
      const isError = event.direction === 'in' && isErrorPayload(event.payload);
      const kind: ConsoleEntry['kind'] = isError
        ? 'error'
        : event.direction === 'out'
          ? 'request'
          : 'response';
      const entry: ConsoleEntry = {
        id: counter++,
        timestamp: event.timestamp,
        kind,
        method: event.method,
        payload: event.payload,
      };
      setEntries((prev) => [entry, ...prev].slice(0, 500));
    });
    return unsubscribe;
  }, []);

  useEffect(() => {
    const sub = subscribeEvents({
      onNotification: (event: InspectorEvent) => {
        // Skip rpc-request / rpc-response — those are also surfaced via onRpcEvent
        // when initiated by us; here we want server-originated notifications.
        if (event.type === 'rpc-request' || event.type === 'rpc-response') return;
        setEntries((prev) =>
          [
            {
              id: counter++,
              timestamp: event.timestamp,
              kind: 'notification' as const,
              method: event.type,
              payload: event.payload,
            },
            ...prev,
          ].slice(0, 500),
        );
      },
    });
    return () => sub.close();
  }, []);

  const filtered = useMemo(() => {
    switch (filter) {
      case 'requests':
        return entries.filter((e) => e.kind === 'request');
      case 'responses':
        return entries.filter((e) => e.kind === 'response');
      case 'notifications':
        return entries.filter((e) => e.kind === 'notification');
      case 'errors':
        return entries.filter((e) => e.kind === 'error');
      default:
        return entries;
    }
  }, [entries, filter]);

  return (
    <section className="space-y-3">
      <header className="flex items-center justify-between">
        <div>
          <h2 className="text-xl font-semibold text-foreground">Console</h2>
          <p className="text-sm text-muted-foreground">Live JSON-RPC traffic.</p>
        </div>
        <Button type="button" size="sm" variant="ghost" onClick={() => setEntries([])}>
          Clear
        </Button>
      </header>

      <div className="flex gap-1">
        {(['all', 'requests', 'responses', 'notifications', 'errors'] as Filter[]).map((f) => (
          <Button
            key={f}
            type="button"
            size="sm"
            variant={filter === f ? 'primary' : 'outline'}
            onClick={() => setFilter(f)}
          >
            {f}
          </Button>
        ))}
      </div>

      {filtered.length === 0 ? (
        <p className="text-sm italic text-muted-foreground">No traffic yet.</p>
      ) : (
        <ul className="space-y-1">
          {filtered.map((entry) => (
            <ConsoleRow key={entry.id} entry={entry} />
          ))}
        </ul>
      )}
    </section>
  );
}

function ConsoleRow({ entry }: { entry: ConsoleEntry }) {
  const [open, setOpen] = useState(false);
  const arrow = entry.kind === 'request' ? '▶' : '◀';
  const accent =
    entry.kind === 'request'
      ? 'text-emerald-600 dark:text-emerald-400'
      : entry.kind === 'response'
        ? 'text-indigo-600 dark:text-indigo-400'
        : entry.kind === 'notification'
          ? 'text-amber-600 dark:text-amber-400'
          : 'text-destructive';
  return (
    <li className="rounded-md border border-border bg-background">
      <button
        type="button"
        onClick={() => setOpen((o) => !o)}
        className="flex w-full items-center justify-between gap-2 px-2 py-1 text-left text-xs hover:bg-muted"
        aria-expanded={open}
      >
        <span className={clsx('inline-flex items-center gap-1 font-mono', accent)}>
          {open ? <ChevronDown size={12} /> : <ChevronRight size={12} />}
          <span>{arrow}</span>
          <span>{entry.method ?? entry.kind}</span>
        </span>
        <span className="font-mono text-muted-foreground">{new Date(entry.timestamp).toLocaleTimeString()}</span>
      </button>
      {open && (
        <div className="border-t border-border px-2 py-1">
          <JsonView value={entry.payload} />
        </div>
      )}
    </li>
  );
}

export default ConsoleTab;
