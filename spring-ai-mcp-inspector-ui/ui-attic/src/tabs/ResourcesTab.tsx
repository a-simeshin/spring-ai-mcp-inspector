// Portions adapted from modelcontextprotocol/inspector (Apache-2.0)
// https://github.com/modelcontextprotocol/inspector

import { useEffect, useState } from 'react';
import { jsonRpc } from '../api/client';
import { ListPane } from '../components/ListPane';
import { Button } from '../components/ui/Button';
import { JsonView } from '../components/JsonView';
import { IconDisplay } from '../components/IconDisplay';
import { parseJsonSafe } from '../utils';

interface ResourceInfo {
  uri: string;
  name?: string;
  title?: string;
  description?: string;
  mimeType?: string;
}

interface ResourceContent {
  uri: string;
  mimeType?: string;
  text?: string;
  blob?: string;
}

interface ResourceListResult {
  resources: ResourceInfo[];
}

interface ResourceReadResult {
  contents: ResourceContent[];
}

/** Resources tab: lists MCP resources and reads selected resource contents. */
export function ResourcesTab() {
  const [resources, setResources] = useState<ResourceInfo[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [selected, setSelected] = useState<ResourceInfo | null>(null);
  const [content, setContent] = useState<ResourceContent | null>(null);
  const [reading, setReading] = useState(false);
  const [readError, setReadError] = useState<string | null>(null);

  useEffect(() => {
    let ignore = false;
    jsonRpc<ResourceListResult>('resources/list')
      .then((response) => {
        if (ignore) return;
        if (response.error) {
          setError(`${response.error.code}: ${response.error.message}`);
        } else {
          setResources(response.result?.resources ?? []);
        }
      })
      .catch((err: unknown) => {
        if (!ignore) setError(err instanceof Error ? err.message : String(err));
      })
      .finally(() => {
        if (!ignore) setLoading(false);
      });
    return () => {
      ignore = true;
    };
  }, []);

  async function handleRead(resource: ResourceInfo) {
    setReading(true);
    setReadError(null);
    setContent(null);
    try {
      const response = await jsonRpc<ResourceReadResult>('resources/read', { uri: resource.uri });
      if (response.error) {
        setReadError(`${response.error.code}: ${response.error.message}`);
      } else {
        setContent(response.result?.contents?.[0] ?? null);
      }
    } catch (err) {
      setReadError(err instanceof Error ? err.message : String(err));
    } finally {
      setReading(false);
    }
  }

  return (
    <section className="space-y-4">
      <header>
        <h2 className="text-xl font-semibold text-foreground">Resources</h2>
        <p className="text-sm text-muted-foreground">Browse and read MCP resources.</p>
      </header>

      {error && (
        <div className="rounded-md border border-amber-300 bg-amber-50 px-4 py-3 text-sm text-amber-900 dark:bg-amber-900/30 dark:text-amber-100">
          Not connected: {error}
        </div>
      )}

      {!error && (
        <div className="grid grid-cols-1 gap-4 md:grid-cols-[280px,1fr]">
          <ListPane<ResourceInfo>
            items={resources}
            loading={loading}
            empty="No resources registered."
            itemKey={(r) => r.uri}
            isSelected={(r) => r.uri === selected?.uri}
            onSelect={(r) => {
              setSelected(r);
              setContent(null);
              setReadError(null);
            }}
            renderItem={(r) => (
              <div className="flex items-start gap-2">
                <IconDisplay kind="resource" />
                <div className="min-w-0">
                  <div className="truncate font-mono text-sm">{r.name ?? r.uri}</div>
                  <div className="truncate text-xs text-muted-foreground">{r.uri}</div>
                </div>
              </div>
            )}
          />
          <div className="space-y-3 rounded-md border border-border bg-background p-3">
            {selected ? (
              <>
                <div>
                  <h3 className="font-mono text-sm font-semibold text-foreground">
                    {selected.title ?? selected.name ?? selected.uri}
                  </h3>
                  <div className="text-xs text-muted-foreground">{selected.uri}</div>
                  {selected.mimeType && (
                    <div className="text-xs text-muted-foreground">{selected.mimeType}</div>
                  )}
                  {selected.description && (
                    <p className="mt-1 text-sm text-muted-foreground">{selected.description}</p>
                  )}
                </div>
                <Button type="button" variant="primary" onClick={() => handleRead(selected)} disabled={reading}>
                  {reading ? 'Reading…' : 'Read'}
                </Button>
                {readError && (
                  <div className="rounded-md border border-destructive bg-destructive/5 px-3 py-2 text-sm text-destructive">
                    {readError}
                  </div>
                )}
                {content && (
                  <ResourceContentView content={content} />
                )}
              </>
            ) : (
              <p className="text-sm italic text-muted-foreground">Select a resource to read it.</p>
            )}
          </div>
        </div>
      )}
    </section>
  );
}

function ResourceContentView({ content }: { content: ResourceContent }) {
  const isJsonLike =
    content.mimeType?.includes('json') ||
    (content.text != null && /^\s*[[{]/.test(content.text));
  const parsed = content.text != null && isJsonLike ? parseJsonSafe(content.text) : undefined;

  return (
    <div className="rounded-md border border-border bg-muted p-3">
      <div className="text-xs text-muted-foreground">
        {content.uri} · {content.mimeType ?? 'unknown'}
      </div>
      <div className="mt-2">
        {parsed !== undefined ? (
          <JsonView value={parsed} />
        ) : (
          <pre className="overflow-auto whitespace-pre-wrap text-xs text-foreground">
            {content.text ?? content.blob ?? '(empty)'}
          </pre>
        )}
      </div>
    </div>
  );
}
