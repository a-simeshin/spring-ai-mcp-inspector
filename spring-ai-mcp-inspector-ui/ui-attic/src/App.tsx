import { useEffect, useMemo, useState, type ReactNode } from 'react';
import {
  AppWindow,
  Bell,
  FolderTree,
  Files,
  Hammer,
  Hash,
  Key,
  ListTodo,
  MessageSquare,
  Network,
  ScrollText,
  Settings,
  Terminal,
} from 'lucide-react';
import { InspectorContext, useInspectorState } from './state/store';
import { ConnectionProvider } from './state/connection';
import { ToastProvider } from './components/ui/Toast';
import { Sidebar } from './components/Sidebar';
import { Tabs, TabsContent, TabsList, TabsTrigger } from './components/ui/Tabs';
import { useDraggablePane } from './lib/hooks/useDraggablePane';
import { MetadataTab } from './tabs/MetadataTab';
import { ToolsTab } from './tabs/ToolsTab';
import { ResourcesTab } from './tabs/ResourcesTab';
import { PromptsTab } from './tabs/PromptsTab';
import { NotificationsTab } from './tabs/NotificationsTab';
import { PingTab } from './tabs/PingTab';
import { LogsTab } from './tabs/LogsTab';
import { ExternalTargetTab } from './tabs/ExternalTargetTab';
import { SamplingTab } from './tabs/SamplingTab';
import { ElicitationTab } from './tabs/ElicitationTab';
import { RootsTab } from './tabs/RootsTab';
import { AuthDebuggerTab } from './tabs/AuthDebuggerTab';
import { TasksTab } from './tabs/TasksTab';
import { AppsTab } from './tabs/AppsTab';
import { ConsoleTab } from './tabs/ConsoleTab';
import type { ConnectionConfig, TransportType } from './lib/types';
import type { ConfigDto } from './api/client';

interface TabSpec {
  value: string;
  label: string;
  icon: ReactNode;
  node: ReactNode;
  /** Optional predicate against the server config; tab is hidden when false. */
  visible?: (config: ConfigDto | null) => boolean;
}

const iconCls = 'mr-2 h-4 w-4';

const TABS: ReadonlyArray<TabSpec> = [
  { value: 'resources', label: 'Resources', icon: <Files className={iconCls} />, node: <ResourcesTab /> },
  { value: 'prompts', label: 'Prompts', icon: <MessageSquare className={iconCls} />, node: <PromptsTab /> },
  { value: 'tools', label: 'Tools', icon: <Hammer className={iconCls} />, node: <ToolsTab /> },
  {
    value: 'tasks',
    label: 'Tasks',
    icon: <ListTodo className={iconCls} />,
    node: <TasksTab />,
    visible: (cfg) => Boolean(cfg?.capabilities && 'tasks' in cfg.capabilities),
  },
  { value: 'apps', label: 'Apps', icon: <AppWindow className={iconCls} />, node: <AppsTab /> },
  { value: 'ping', label: 'Ping', icon: <Bell className={iconCls} />, node: <PingTab /> },
  { value: 'sampling', label: 'Sampling', icon: <Hash className={iconCls} />, node: <SamplingTab /> },
  { value: 'elicitation', label: 'Elicitation', icon: <MessageSquare className={iconCls} />, node: <ElicitationTab /> },
  { value: 'roots', label: 'Roots', icon: <FolderTree className={iconCls} />, node: <RootsTab /> },
  { value: 'auth', label: 'Auth', icon: <Key className={iconCls} />, node: <AuthDebuggerTab /> },
  { value: 'notifications', label: 'Notifications', icon: <Bell className={iconCls} />, node: <NotificationsTab /> },
  { value: 'logs', label: 'Logs', icon: <ScrollText className={iconCls} />, node: <LogsTab /> },
  { value: 'console', label: 'Console', icon: <Terminal className={iconCls} />, node: <ConsoleTab /> },
  { value: 'metadata', label: 'Metadata', icon: <Settings className={iconCls} />, node: <MetadataTab /> },
  { value: 'external', label: 'External', icon: <Network className={iconCls} />, node: <ExternalTargetTab /> },
];

function deriveInitialConnection(cfg: ConfigDto | null): Partial<ConnectionConfig> {
  if (!cfg) return {};
  const origin = typeof window !== 'undefined' ? window.location.origin : '';
  const url = `${origin}${cfg.endpoint ?? ''}`;
  if (cfg.transport === 'SSE') return { transport: 'sse' as TransportType, url };
  if (cfg.transport === 'STREAMABLE' || cfg.transport === 'STATELESS')
    return { transport: 'streamable-http' as TransportType, url };
  return { transport: 'stdio' as TransportType };
}

function AppShell({ config }: { config: ConfigDto | null }) {
  const [active, setActive] = useState('resources');
  const { width, handleProps, isDragging } = useDraggablePane({
    defaultWidth: 320,
    min: 240,
    max: 600,
    storageKey: 'mcp-inspector.sidebar.width',
  });

  const visibleTabs = TABS.filter((t) => !t.visible || t.visible(config));

  return (
    <div className="flex h-full flex-col bg-background text-foreground">
      <header className="flex h-14 shrink-0 items-center justify-between border-b bg-card px-4">
        <div className="flex items-center gap-2">
          <span className="text-base font-semibold tracking-tight">MCP Inspector</span>
        </div>
        <button
          type="button"
          aria-label="Settings"
          className="inline-flex h-9 w-9 items-center justify-center rounded-md text-muted-foreground transition-colors hover:bg-accent hover:text-accent-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
        >
          <Settings className="h-4 w-4" />
        </button>
      </header>
      <div className="flex min-h-0 flex-1">
        <aside style={{ width }} className="shrink-0">
          <Sidebar />
        </aside>
        <div
          {...handleProps}
          role="separator"
          aria-orientation="vertical"
          className={`w-1 shrink-0 cursor-col-resize bg-border hover:bg-primary/50 ${
            isDragging ? 'bg-primary/50' : ''
          }`}
        />
        <main className="flex min-w-0 flex-1 flex-col overflow-hidden bg-background">
          <Tabs value={active} onValueChange={setActive} className="flex h-full flex-col">
            <div className="border-b bg-card px-4 py-2">
              <div className="overflow-x-auto">
                <TabsList className="flex w-max gap-1">
                  {visibleTabs.map((t) => (
                    <TabsTrigger key={t.value} value={t.value}>
                      {t.icon}
                      {t.label}
                    </TabsTrigger>
                  ))}
                </TabsList>
              </div>
            </div>
            <div className="min-h-0 flex-1 overflow-auto p-4">
              {visibleTabs.map((t) => (
                <TabsContent key={t.value} value={t.value} className="space-y-4">
                  {t.node}
                </TabsContent>
              ))}
            </div>
          </Tabs>
        </main>
      </div>
    </div>
  );
}

function App() {
  const state = useInspectorState();
  const initialConnection = useMemo(() => deriveInitialConnection(state.config), [state.config]);
  const [ready, setReady] = useState(false);

  useEffect(() => {
    if (!state.configLoading) setReady(true);
  }, [state.configLoading]);

  return (
    <InspectorContext.Provider value={state}>
      <ToastProvider>
        {ready ? (
          <ConnectionProvider initialConfig={initialConnection}>
            <AppShell config={state.config} />
          </ConnectionProvider>
        ) : (
          <div className="flex h-full items-center justify-center text-muted-foreground">
            Loading…
          </div>
        )}
      </ToastProvider>
    </InspectorContext.Provider>
  );
}

export default App;
