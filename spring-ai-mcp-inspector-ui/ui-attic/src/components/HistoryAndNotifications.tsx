// Portions adapted from modelcontextprotocol/inspector (Apache-2.0)
// https://github.com/modelcontextprotocol/inspector

import { useState, type ReactNode } from 'react';
import { ChevronDown, ChevronRight } from 'lucide-react';
import clsx from 'clsx';
import { JsonView } from './JsonView';

/** Single request/response entry tracked by the inspector. */
export interface HistoryEntry {
  id: string | number;
  timestamp: number;
  direction: 'request' | 'response' | 'notification';
  method?: string;
  params?: unknown;
  result?: unknown;
  error?: { code: number; message: string; data?: unknown };
}

/** Single notification entry streamed from SSE. */
export interface NotificationEntry {
  id: string | number;
  timestamp: number;
  type: string;
  payload: unknown;
}

export interface HistoryAndNotificationsProps {
  history: ReadonlyArray<HistoryEntry>;
  notifications: ReadonlyArray<NotificationEntry>;
}

interface CollapsibleRowProps {
  title: ReactNode;
  meta?: ReactNode;
  children?: ReactNode;
  accent?: string;
}

function CollapsibleRow({ title, meta, children, accent }: CollapsibleRowProps) {
  const [open, setOpen] = useState(false);
  return (
    <li className="rounded-md border border-border bg-background">
      <button
        type="button"
        onClick={() => setOpen((o) => !o)}
        className="flex w-full items-baseline justify-between gap-2 px-3 py-2 text-left text-xs hover:bg-muted"
        aria-expanded={open}
      >
        <span className={clsx('inline-flex items-center gap-1 font-mono font-semibold', accent)}>
          {open ? <ChevronDown size={12} /> : <ChevronRight size={12} />}
          {title}
        </span>
        {meta && <span className="text-muted-foreground">{meta}</span>}
      </button>
      {open && children && <div className="border-t border-border px-3 py-2">{children}</div>}
    </li>
  );
}

/** Two-pane view: request/response history + server notifications timeline. */
export function HistoryAndNotifications({ history, notifications }: HistoryAndNotificationsProps) {
  return (
    <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
      <section className="min-w-0">
        <h3 className="mb-2 text-sm font-semibold text-foreground">History</h3>
        {history.length === 0 ? (
          <p className="text-sm italic text-muted-foreground">No requests recorded yet.</p>
        ) : (
          <ul className="space-y-2">
            {history.map((entry) => {
              const accent =
                entry.direction === 'request'
                  ? 'text-emerald-600 dark:text-emerald-400'
                  : entry.direction === 'response'
                    ? 'text-indigo-600 dark:text-indigo-400'
                    : 'text-amber-600 dark:text-amber-400';
              const title = (
                <>
                  <span>{entry.direction}</span>
                  {entry.method && <span className="text-muted-foreground">· {entry.method}</span>}
                  {entry.error && <span className="text-destructive">· error</span>}
                </>
              );
              return (
                <CollapsibleRow
                  key={entry.id}
                  accent={accent}
                  title={title}
                  meta={new Date(entry.timestamp).toLocaleTimeString()}
                >
                  {entry.params !== undefined && (
                    <div className="mb-2">
                      <div className="mb-1 text-xs font-semibold uppercase text-muted-foreground">params</div>
                      <JsonView value={entry.params} />
                    </div>
                  )}
                  {entry.result !== undefined && (
                    <div className="mb-2">
                      <div className="mb-1 text-xs font-semibold uppercase text-muted-foreground">result</div>
                      <JsonView value={entry.result} />
                    </div>
                  )}
                  {entry.error && (
                    <div>
                      <div className="mb-1 text-xs font-semibold uppercase text-destructive">error</div>
                      <JsonView value={entry.error} />
                    </div>
                  )}
                </CollapsibleRow>
              );
            })}
          </ul>
        )}
      </section>
      <section className="min-w-0">
        <h3 className="mb-2 text-sm font-semibold text-foreground">Notifications</h3>
        {notifications.length === 0 ? (
          <p className="text-sm italic text-muted-foreground">No events yet.</p>
        ) : (
          <ul className="space-y-2">
            {notifications.map((n) => (
              <CollapsibleRow
                key={n.id}
                accent="text-primary"
                title={<span>{n.type}</span>}
                meta={new Date(n.timestamp).toLocaleTimeString()}
              >
                <JsonView value={n.payload} />
              </CollapsibleRow>
            ))}
          </ul>
        )}
      </section>
    </div>
  );
}

export default HistoryAndNotifications;
