// Portions adapted from modelcontextprotocol/inspector (Apache-2.0)
// https://github.com/modelcontextprotocol/inspector

import { useCallback, useEffect, useState } from 'react';
import { jsonRpc } from '../api/client';
import { Button } from '../components/ui/Button';
import { Dialog, DialogContent, DialogTitle } from '../components/ui/Dialog';
import { JsonView } from '../components/JsonView';
import { useToast } from '../lib/hooks/useToast';

interface TaskInfo {
  id: string;
  status?: string;
  createdAt?: string | number;
  name?: string;
  [key: string]: unknown;
}

interface TasksListResult {
  tasks?: TaskInfo[];
}

/**
 * Tasks tab: lists MCP tasks via `tasks/list`. Gracefully degrades to a
 * "Not supported" message when the server returns JSON-RPC error -32601.
 */
export function TasksTab() {
  const { toast } = useToast();
  const [tasks, setTasks] = useState<TaskInfo[]>([]);
  const [loading, setLoading] = useState(true);
  const [supported, setSupported] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);
  const [selected, setSelected] = useState<TaskInfo | null>(null);
  const [cancelling, setCancelling] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const response = await jsonRpc<TasksListResult>('tasks/list');
      if (response.error) {
        if (response.error.code === -32601) {
          setSupported(false);
          setTasks([]);
        } else {
          setError(`${response.error.code}: ${response.error.message}`);
        }
        return;
      }
      setSupported(true);
      setTasks(response.result?.tasks ?? []);
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  const handleCancel = async (taskId: string) => {
    setCancelling(taskId);
    try {
      const response = await jsonRpc('tasks/cancel', { taskId });
      if (response.error) {
        toast({
          title: 'Cancel failed',
          description: `${response.error.code}: ${response.error.message}`,
          variant: 'danger',
        });
      } else {
        toast({ title: 'Task cancelled', variant: 'success' });
        void refresh();
      }
    } catch (err) {
      toast({
        title: 'Cancel failed',
        description: err instanceof Error ? err.message : String(err),
        variant: 'danger',
      });
    } finally {
      setCancelling(null);
    }
  };

  if (!supported) {
    return (
      <section className="space-y-2">
        <h2 className="text-xl font-semibold text-foreground">Tasks</h2>
        <p className="text-sm italic text-muted-foreground">Tasks not supported by this server.</p>
      </section>
    );
  }

  return (
    <section className="space-y-4">
      <header className="flex items-center justify-between">
        <div>
          <h2 className="text-xl font-semibold text-foreground">Tasks</h2>
          <p className="text-sm text-muted-foreground">List and manage long-running MCP tasks.</p>
        </div>
        <Button type="button" variant="outline" size="sm" onClick={() => void refresh()}>
          Refresh
        </Button>
      </header>

      {error && (
        <div className="rounded-md border border-destructive/30 bg-destructive/10 p-2 text-sm text-destructive">{error}</div>
      )}

      {loading ? (
        <p className="text-sm italic text-muted-foreground">Loading…</p>
      ) : tasks.length === 0 ? (
        <p className="text-sm italic text-muted-foreground">No tasks.</p>
      ) : (
        <table className="w-full table-auto border-separate border-spacing-y-1 text-sm">
          <thead>
            <tr className="text-left text-xs uppercase tracking-wide text-muted-foreground">
              <th className="pb-1">ID</th>
              <th className="pb-1">Name</th>
              <th className="pb-1">Status</th>
              <th className="pb-1">Created</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {tasks.map((t) => (
              <tr key={t.id} className="bg-background">
                <td className="rounded-l-md border border-r-0 border-border px-2 py-1 font-mono text-xs">
                  {t.id}
                </td>
                <td className="border border-x-0 border-border px-2 py-1">{t.name ?? ''}</td>
                <td className="border border-x-0 border-border px-2 py-1">{t.status ?? ''}</td>
                <td className="border border-x-0 border-border px-2 py-1 text-xs text-muted-foreground">
                  {t.createdAt ? new Date(t.createdAt).toLocaleString() : ''}
                </td>
                <td className="rounded-r-md border border-l-0 border-border px-2 py-1 text-right">
                  <Button type="button" variant="ghost" size="sm" onClick={() => setSelected(t)}>
                    View
                  </Button>
                  <Button
                    type="button"
                    variant="ghost"
                    size="sm"
                    disabled={cancelling === t.id}
                    onClick={() => void handleCancel(t.id)}
                  >
                    Cancel
                  </Button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      <Dialog open={selected !== null} onOpenChange={(v) => !v && setSelected(null)}>
        <DialogContent>
          <DialogTitle>Task {selected?.id}</DialogTitle>
          <div className="mt-3 max-h-96 overflow-auto">
            {selected && <JsonView value={selected} />}
          </div>
        </DialogContent>
      </Dialog>
    </section>
  );
}

export default TasksTab;
