// Portions adapted from modelcontextprotocol/inspector (Apache-2.0)
// https://github.com/modelcontextprotocol/inspector

import { useInspector } from '../state/store';
import { Button } from '../components/ui/Button';
import { JsonView } from '../components/JsonView';

interface RowProps {
  label: string;
  value: string;
}

function Row({ label, value }: RowProps) {
  return (
    <div className="grid grid-cols-3 gap-4 border-b border-border px-3 py-2 last:border-b-0">
      <dt className="text-xs font-semibold uppercase text-muted-foreground">{label}</dt>
      <dd className="col-span-2 font-mono text-sm text-foreground">{value}</dd>
    </div>
  );
}

/** Metadata tab: renders server config from `/api/config`. */
export function MetadataTab() {
  const { config, configError, configLoading, refreshConfig } = useInspector();

  return (
    <section className="space-y-4">
      <header className="flex items-center justify-between">
        <div>
          <h2 className="text-xl font-semibold text-foreground">Metadata</h2>
          <p className="text-sm text-muted-foreground">Detected transport, stack, and server info.</p>
        </div>
        <Button type="button" variant="outline" onClick={refreshConfig}>
          Refresh
        </Button>
      </header>

      {configLoading && (
        <div className="rounded-md border border-border bg-background px-3 py-4 text-sm text-muted-foreground">
          Loading configuration…
        </div>
      )}

      {!configLoading && configError && (
        <div className="rounded-md border border-amber-300 bg-amber-50 px-4 py-3 text-sm text-amber-900 dark:bg-amber-900/30 dark:text-amber-100">
          Not connected: {configError}
        </div>
      )}

      {!configLoading && !configError && config && (
        <>
          <dl className="rounded-md border border-border bg-background">
            <Row label="Transport" value={config.transport} />
            <Row label="Stack" value={config.stack} />
            <Row label="Endpoint" value={config.endpoint ?? '—'} />
            {config.messageEndpoint && (
              <Row label="Message endpoint" value={config.messageEndpoint} />
            )}
            <Row label="Server name" value={config.serverName} />
            <Row label="Version" value={config.version} />
            <Row
              label="Auth token"
              value={config.authToken ? `${config.authToken.slice(0, 6)}…` : '—'}
            />
          </dl>
          {config.capabilities && (
            <div>
              <h3 className="mb-2 text-sm font-semibold text-foreground">Capabilities</h3>
              <div className="rounded-md border border-border bg-muted p-3">
                <JsonView value={config.capabilities} />
              </div>
            </div>
          )}
        </>
      )}
    </section>
  );
}
