// Portions adapted from modelcontextprotocol/inspector (Apache-2.0)
// https://github.com/modelcontextprotocol/inspector

import { useEffect, useState } from 'react';
import { jsonRpc } from '../api/client';
import { subscribeEvents, type InspectorEvent } from '../api/sse';
import { Label } from '../components/ui/Label';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '../components/ui/Select';

type LogLevel =
  | 'debug'
  | 'info'
  | 'notice'
  | 'warning'
  | 'error'
  | 'critical'
  | 'alert'
  | 'emergency';

const LEVELS: ReadonlyArray<LogLevel> = [
  'debug',
  'info',
  'notice',
  'warning',
  'error',
  'critical',
  'alert',
  'emergency',
];

const LEVEL_CLASS: Record<string, string> = {
  debug: 'text-muted-foreground',
  info: 'text-foreground',
  notice: 'text-blue-600 dark:text-blue-400',
  warning: 'text-amber-600 dark:text-amber-400',
  error: 'text-destructive',
  critical: 'text-destructive',
  alert: 'text-destructive',
  emergency: 'text-destructive',
};

interface LogEntry {
  level?: string;
  logger?: string;
  data?: unknown;
  timestamp: number;
}

/** Logs tab: adjusts MCP log level and displays `notifications/message`. */
export function LogsTab() {
  const [level, setLevel] = useState<LogLevel>('info');
  const [entries, setEntries] = useState<LogEntry[]>([]);
  const [applying, setApplying] = useState(false);
  const [setError, setSetError] = useState<string | null>(null);

  useEffect(() => {
    const subscription = subscribeEvents((event: InspectorEvent) => {
      if (event.type === 'notifications/message') {
        const payload = event.payload as { level?: string; logger?: string; data?: unknown };
        setEntries((prev) =>
          [
            {
              level: payload.level,
              logger: payload.logger,
              data: payload.data,
              timestamp: event.timestamp,
            },
            ...prev,
          ].slice(0, 200),
        );
      }
    });
    return () => subscription.close();
  }, []);

  async function applyLevel(newLevel: LogLevel) {
    setApplying(true);
    setSetError(null);
    try {
      const response = await jsonRpc('logging/setLevel', { level: newLevel });
      if (response.error) {
        setSetError(`${response.error.code}: ${response.error.message}`);
      } else {
        setLevel(newLevel);
      }
    } catch (err) {
      setSetError(err instanceof Error ? err.message : String(err));
    } finally {
      setApplying(false);
    }
  }

  return (
    <section className="space-y-4">
      <header>
        <h2 className="text-xl font-semibold text-foreground">Logs</h2>
        <p className="text-sm text-muted-foreground">Adjust MCP log level and view streamed messages.</p>
      </header>

      <div className="flex items-center gap-3">
        <Label className="text-sm">Level:</Label>
        <Select value={level} onValueChange={(v) => applyLevel(v as LogLevel)}>
          <SelectTrigger className="w-40">
            <SelectValue placeholder="Select level" />
          </SelectTrigger>
          <SelectContent>
            {LEVELS.map((opt) => (
              <SelectItem key={opt} value={opt}>
                {opt}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
        {applying && <span className="text-xs text-muted-foreground">Applying…</span>}
      </div>

      {setError && (
        <div className="rounded-md border border-destructive bg-destructive/5 px-3 py-2 text-sm text-destructive">
          {setError}
        </div>
      )}

      {entries.length === 0 ? (
        <p className="text-sm italic text-muted-foreground">No log messages yet.</p>
      ) : (
        <ul className="space-y-1 font-mono text-xs">
          {entries.map((entry, idx) => (
            <li
              key={`${entry.timestamp}-${idx}`}
              className="rounded-md border border-border bg-background px-3 py-2"
            >
              <div className="flex items-baseline justify-between gap-2">
                <span className={`font-semibold uppercase ${LEVEL_CLASS[entry.level ?? ''] ?? 'text-foreground'}`}>
                  {entry.level ?? 'log'}
                </span>
                <span className="text-muted-foreground">
                  {new Date(entry.timestamp).toLocaleTimeString()}
                </span>
              </div>
              {entry.logger && <div className="text-muted-foreground">{entry.logger}</div>}
              <pre className="mt-1 overflow-auto whitespace-pre-wrap text-foreground">
                {typeof entry.data === 'string' ? entry.data : JSON.stringify(entry.data, null, 2)}
              </pre>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
