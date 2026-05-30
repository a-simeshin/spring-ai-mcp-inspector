// Portions adapted from modelcontextprotocol/inspector (Apache-2.0)
// https://github.com/modelcontextprotocol/inspector

import { useCallback, useEffect, useState } from 'react';
import { Plus, X } from 'lucide-react';
import { Button } from '../components/ui/Button';
import { Input } from '../components/ui/Input';
import { Label } from '../components/ui/Label';
import { getRoots, putRoots } from '../api/client';
import { subscribeEvents } from '../api/sse';
import { useConnection } from '../state/connection';
import { useToast } from '../lib/hooks/useToast';
import { isValidHttpUrl } from '../utils/urlValidation';
import type { Root } from '../lib/types';

function isValidRootUri(uri: string): boolean {
  const trimmed = uri.trim();
  if (trimmed === '') return false;
  if (trimmed.startsWith('file://')) return true;
  return isValidHttpUrl(trimmed);
}

/**
 * Roots tab: manages the filesystem roots the inspector advertises to the
 * connected MCP server. Persists every change via {@code PUT /api/roots}.
 */
export function RootsTab() {
  const { sessionId } = useConnection();
  const { toast } = useToast();
  const [roots, setRoots] = useState<Root[]>([]);
  const [loading, setLoading] = useState(false);
  const [draftUri, setDraftUri] = useState('file://');
  const [draftName, setDraftName] = useState('');
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    if (!sessionId) return;
    setLoading(true);
    try {
      const data = await getRoots(sessionId);
      setRoots(data.roots ?? []);
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setLoading(false);
    }
  }, [sessionId]);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  useEffect(() => {
    const sub = subscribeEvents({
      onRootsListChanged: () => {
        void refresh();
      },
    });
    return () => sub.close();
  }, [refresh]);

  if (!sessionId) {
    return (
      <section className="space-y-2">
        <h2 className="text-xl font-semibold text-foreground">Roots</h2>
        <p className="text-sm italic text-muted-foreground">Not connected.</p>
      </section>
    );
  }

  const persist = async (next: Root[]) => {
    setRoots(next);
    try {
      await putRoots(sessionId, next);
    } catch (err) {
      toast({
        title: 'Save failed',
        description: err instanceof Error ? err.message : String(err),
        variant: 'danger',
      });
    }
  };

  const handleAdd = () => {
    if (!isValidRootUri(draftUri)) {
      setError('URI must be a valid http(s):// or file:// URL.');
      return;
    }
    setError(null);
    const next = [...roots, { uri: draftUri.trim(), name: draftName.trim() || undefined }];
    setDraftUri('file://');
    setDraftName('');
    void persist(next);
  };

  const handleRemove = (idx: number) => {
    const next = roots.filter((_, i) => i !== idx);
    void persist(next);
  };

  return (
    <section className="space-y-4">
      <header>
        <h2 className="text-xl font-semibold text-foreground">Roots</h2>
        <p className="text-sm text-muted-foreground">
          Configure the root directories advertised to the MCP server.
        </p>
      </header>

      {loading ? (
        <p className="text-sm italic text-muted-foreground">Loading…</p>
      ) : roots.length === 0 ? (
        <p className="text-sm italic text-muted-foreground">No roots configured.</p>
      ) : (
        <table className="w-full table-auto border-separate border-spacing-y-1 text-sm">
          <thead>
            <tr className="text-left text-xs uppercase tracking-wide text-muted-foreground">
              <th className="pb-1">URI</th>
              <th className="pb-1">Name</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {roots.map((r, i) => (
              <tr key={`${r.uri}-${i}`} className="rounded-md bg-background">
                <td className="rounded-l-md border border-r-0 border-border px-2 py-1 font-mono text-xs">
                  {r.uri}
                </td>
                <td className="border border-x-0 border-border px-2 py-1">{r.name ?? ''}</td>
                <td className="rounded-r-md border border-l-0 border-border px-2 py-1 text-right">
                  <Button
                    type="button"
                    variant="ghost"
                    size="sm"
                    aria-label={`Remove root ${r.uri}`}
                    onClick={() => handleRemove(i)}
                  >
                    <X size={12} />
                  </Button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      <div className="space-y-2 rounded-md border border-border bg-muted p-3">
        <Label className="text-xs uppercase tracking-wide text-muted-foreground">Add root</Label>
        <div className="grid grid-cols-1 gap-2 md:grid-cols-2">
          <Input
            value={draftUri}
            onChange={(e) => setDraftUri(e.target.value)}
            placeholder="file:///path/to/dir or https://…"
          />
          <Input
            value={draftName}
            onChange={(e) => setDraftName(e.target.value)}
            placeholder="Name (optional)"
          />
        </div>
        {error && <p className="text-xs text-destructive">{error}</p>}
        <Button type="button" variant="primary" size="sm" onClick={handleAdd}>
          <Plus size={12} /> Add root
        </Button>
      </div>
    </section>
  );
}

export default RootsTab;
