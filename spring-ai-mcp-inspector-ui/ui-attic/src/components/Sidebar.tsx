import { Plug, Settings } from 'lucide-react';
import clsx from 'clsx';
import { Button } from './ui/Button';
import { Input } from './ui/Input';
import { Label } from './ui/Label';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from './ui/Select';
import { KvAdder } from './KvAdder';
import { useConnection } from '../state/connection';
import { useTheme, type Theme } from '../lib/hooks/useTheme';
import { useInspector } from '../state/store';
import type { TransportType } from '../lib/types';

function statusDotClass(s: string): string {
  if (s === 'connected') return 'bg-success';
  if (s === 'error') return 'bg-destructive';
  if (s === 'connecting') return 'bg-primary animate-pulse';
  return 'bg-muted-foreground';
}

function statusLabel(status: string, transport: string, error?: string): string {
  if (status === 'connected') return `Connected (${transport})`;
  if (status === 'error') return `Error${error ? `: ${error}` : ''}`;
  if (status === 'connecting') return 'Connecting…';
  return 'Disconnected';
}

/** Inspector sidebar — connection form, status, theme switcher. */
export function Sidebar() {
  const {
    config,
    status,
    error,
    sessionId,
    setConfig,
    setTransport,
    connect,
    disconnect,
  } = useConnection();
  const { theme, setTheme } = useTheme();
  const { config: inspectorConfig } = useInspector();

  const isStdio = config.transport === 'stdio';

  return (
    <div
      className="flex h-full flex-col overflow-y-auto border-r bg-card text-card-foreground"
      data-testid="sidebar"
    >
      {/* Header */}
      <div className="flex items-center justify-between border-b px-4 py-3">
        <div className="flex items-center gap-2">
          <h1 className="text-base font-semibold tracking-tight">MCP Inspector</h1>
        </div>
        <Button variant="ghost" size="icon" aria-label="Settings" title="Settings">
          <Settings className="h-4 w-4" />
        </Button>
      </div>

      {/* Connection section */}
      <div className="flex-1 space-y-4 p-4 text-sm">
        <section className="space-y-3">
          <h2 className="text-sm font-medium">Connection</h2>

          <div className="space-y-2">
            <Label htmlFor="transport-select">Transport Type</Label>
            <Select
              value={config.transport}
              onValueChange={(v: string) => setTransport(v as TransportType)}
            >
              <SelectTrigger className="w-full">
                <SelectValue placeholder="Select transport" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="stdio">STDIO</SelectItem>
                <SelectItem value="sse">SSE</SelectItem>
                <SelectItem value="streamable-http">Streamable HTTP</SelectItem>
              </SelectContent>
            </Select>
          </div>

          {isStdio ? (
            <>
              <div className="space-y-2">
                <Label htmlFor="command-input">Command</Label>
                <Input
                  id="command-input"
                  placeholder="Command"
                  value={config.command ?? ''}
                  onChange={(e) => setConfig({ command: e.target.value })}
                  className="font-mono"
                />
              </div>
              <div className="space-y-2">
                <Label htmlFor="args-input">Args</Label>
                <Input
                  id="args-input"
                  placeholder="Args (space separated)"
                  value={config.args ?? ''}
                  onChange={(e) => setConfig({ args: e.target.value })}
                  className="font-mono"
                />
              </div>
              <KvAdder
                label="Env vars"
                addAriaLabel="Add environment variable"
                existing={config.env ?? {}}
                onChange={(env) => setConfig({ env })}
              />
            </>
          ) : (
            <>
              <div className="space-y-2">
                <Label htmlFor="url-input">URL</Label>
                <Input
                  id="url-input"
                  placeholder="https://…"
                  value={config.url ?? ''}
                  onChange={(e) => setConfig({ url: e.target.value })}
                  className="font-mono"
                />
              </div>
              <KvAdder
                label="Headers"
                addAriaLabel="Add header"
                existing={config.headers ?? {}}
                onChange={(headers) => setConfig({ headers })}
              />
            </>
          )}

          {status === 'connected' ? (
            <Button variant="outline" className="w-full" onClick={() => void disconnect()}>
              Disconnect
            </Button>
          ) : (
            <Button
              variant="default"
              className="w-full"
              disabled={status === 'connecting'}
              onClick={() => void connect()}
            >
              <Plug className="h-4 w-4" />
              {status === 'connecting' ? 'Connecting…' : 'Connect'}
            </Button>
          )}

          <div className="flex items-center gap-2 text-xs" data-testid="connection-status">
            <span className={clsx('h-2 w-2 rounded-full', statusDotClass(status))} />
            <span className="text-muted-foreground">
              {statusLabel(status, config.transport, error)}
            </span>
          </div>
          {sessionId && (
            <div className="break-all font-mono text-[10px] text-muted-foreground">
              id: {sessionId}
            </div>
          )}
        </section>

        {/* Inspector Proxy section */}
        <section className="space-y-3 border-t pt-4">
          <h2 className="text-sm font-medium">Inspector Proxy</h2>
          <div className="text-xs text-muted-foreground">
            Auth:{' '}
            {inspectorConfig?.authToken ? (
              <span className="font-medium text-foreground">token loaded</span>
            ) : (
              <span className="font-medium text-destructive">missing</span>
            )}
          </div>
          <div className="space-y-2">
            <Label htmlFor="theme-select">Theme</Label>
            <Select value={theme} onValueChange={(v: string) => setTheme(v as Theme)}>
              <SelectTrigger className="w-full">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="light">Light</SelectItem>
                <SelectItem value="dark">Dark</SelectItem>
                <SelectItem value="system">System</SelectItem>
              </SelectContent>
            </Select>
          </div>
        </section>
      </div>
    </div>
  );
}

export default Sidebar;
