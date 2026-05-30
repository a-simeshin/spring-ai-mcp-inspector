// Portions adapted from modelcontextprotocol/inspector (Apache-2.0)
// https://github.com/modelcontextprotocol/inspector

import { useState } from 'react';
import { jsonRpc } from '../api/client';
import { Button } from '../components/ui/Button';

interface PingSample {
  latencyMs: number;
  ok: boolean;
  timestamp: number;
  error?: string;
}

/** Ping tab: measures round-trip latency to the MCP server with last-10 history. */
export function PingTab() {
  const [samples, setSamples] = useState<PingSample[]>([]);
  const [busy, setBusy] = useState(false);

  async function handlePing() {
    setBusy(true);
    const start = performance.now();
    let ok = false;
    let error: string | undefined;
    try {
      const response = await jsonRpc('ping');
      if (response.error) {
        error = `${response.error.code}: ${response.error.message}`;
      } else {
        ok = true;
      }
    } catch (err) {
      error = err instanceof Error ? err.message : String(err);
    }
    const latencyMs = Math.round(performance.now() - start);
    setSamples((prev) => [{ latencyMs, ok, timestamp: Date.now(), error }, ...prev].slice(0, 10));
    setBusy(false);
  }

  const last = samples[0];

  return (
    <section className="space-y-4">
      <header>
        <h2 className="text-xl font-semibold text-foreground">Ping</h2>
        <p className="text-sm text-muted-foreground">Send a JSON-RPC ping and measure latency.</p>
      </header>
      <div className="flex items-center gap-3">
        <Button type="button" variant="primary" onClick={handlePing} disabled={busy}>
          {busy ? 'Pinging…' : 'Send Ping'}
        </Button>
        {last && (
          <span
            className={`font-mono text-sm ${last.ok ? 'text-emerald-600 dark:text-emerald-400' : 'text-destructive'}`}
          >
            {last.ok ? 'OK' : 'ERR'} · {last.latencyMs} ms
          </span>
        )}
      </div>
      {samples.length === 0 ? (
        <p className="text-sm italic text-muted-foreground">No pings yet.</p>
      ) : (
        <ul className="space-y-1">
          {samples.map((sample) => (
            <li
              key={sample.timestamp}
              className="flex items-center justify-between rounded-md border border-border bg-background px-3 py-1.5 text-xs"
            >
              <span
                className={`font-mono ${sample.ok ? 'text-emerald-600 dark:text-emerald-400' : 'text-destructive'}`}
              >
                {sample.ok ? 'OK' : 'ERR'} {sample.latencyMs} ms
              </span>
              <span className="text-muted-foreground">{new Date(sample.timestamp).toLocaleTimeString()}</span>
              {sample.error && <span className="text-destructive">{sample.error}</span>}
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
