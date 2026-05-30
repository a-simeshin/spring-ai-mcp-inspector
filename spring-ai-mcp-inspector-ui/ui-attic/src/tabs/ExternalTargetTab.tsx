import { useState } from 'react';
import { connectExternal, disconnect, type SessionDto } from '../api/client';

/** External Target tab — connects inspector to an external stdio MCP server. */
export function ExternalTargetTab() {
  const [command, setCommand] = useState('java -jar demo-stdio.jar');
  const [session, setSession] = useState<SessionDto | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function handleConnect() {
    setBusy(true);
    setError(null);
    try {
      const args = command
        .trim()
        .split(/\s+/)
        .filter((token) => token.length > 0);
      if (args.length === 0) {
        setError('Command is empty');
        return;
      }
      const result = await connectExternal(args);
      setSession(result);
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setBusy(false);
    }
  }

  async function handleDisconnect() {
    if (!session) return;
    setBusy(true);
    setError(null);
    try {
      await disconnect(session.sessionId);
      setSession(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="space-y-4">
      <header>
        <h2 className="text-xl font-semibold text-slate-900">External Target</h2>
        <p className="text-sm text-slate-500">
          Spawn an external stdio MCP server process and inspect it.
        </p>
      </header>

      <label className="flex flex-col gap-1">
        <span className="text-xs font-medium uppercase tracking-wide text-slate-500">
          Command
        </span>
        <input
          type="text"
          value={command}
          onChange={(event) => setCommand(event.target.value)}
          disabled={busy || session !== null}
          className="rounded-md border border-slate-300 px-3 py-1.5 font-mono text-sm focus:border-brand-500 focus:outline-none disabled:opacity-50"
        />
      </label>

      <div className="flex gap-2">
        <button
          type="button"
          onClick={handleConnect}
          disabled={busy || session !== null}
          className="rounded-md bg-brand-600 px-3 py-1.5 text-sm font-medium text-white hover:bg-brand-700 disabled:opacity-50"
        >
          {busy && !session ? 'Connecting…' : 'Connect external'}
        </button>
        <button
          type="button"
          onClick={handleDisconnect}
          disabled={busy || session === null}
          className="rounded-md border border-slate-300 bg-white px-3 py-1.5 text-sm font-medium text-slate-700 hover:bg-slate-50 disabled:opacity-50"
        >
          Disconnect
        </button>
      </div>

      {error && (
        <div className="rounded-md border border-rose-200 bg-rose-50 px-3 py-2 text-sm text-rose-800">
          {error}
        </div>
      )}

      {session && (
        <dl className="rounded-md border border-slate-200 bg-white">
          <div className="grid grid-cols-3 gap-4 border-b border-slate-100 px-4 py-3">
            <dt className="text-xs font-medium uppercase tracking-wide text-slate-500">Session</dt>
            <dd className="col-span-2 font-mono text-sm text-slate-900">{session.sessionId}</dd>
          </div>
          <div className="grid grid-cols-3 gap-4 border-b border-slate-100 px-4 py-3">
            <dt className="text-xs font-medium uppercase tracking-wide text-slate-500">Transport</dt>
            <dd className="col-span-2 font-mono text-sm text-slate-900">{session.transport}</dd>
          </div>
          <div className="grid grid-cols-3 gap-4 border-b border-slate-100 px-4 py-3">
            <dt className="text-xs font-medium uppercase tracking-wide text-slate-500">Server</dt>
            <dd className="col-span-2 font-mono text-sm text-slate-900">
              {session.serverName} {session.serverVersion}
            </dd>
          </div>
        </dl>
      )}
    </section>
  );
}
