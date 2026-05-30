// Portions adapted from modelcontextprotocol/inspector (Apache-2.0)
// https://github.com/modelcontextprotocol/inspector

import { useEffect, useMemo, useState } from 'react';
import type { ErrorObject } from 'ajv';
import { jsonRpc } from '../api/client';
import { Button } from '../components/ui/Button';
import { ListPane } from '../components/ListPane';
import { DynamicJsonForm, validateAgainstSchema } from '../components/DynamicJsonForm';
import { ToolResults, type CallToolResult } from '../components/ToolResults';
import { IconDisplay } from '../components/IconDisplay';
import type { JsonSchema } from '../utils/schemaUtils';
import { getSchemaDefaults } from '../utils/schemaUtils';
import { isPlainObject } from '../utils/jsonUtils';

interface ToolInfo {
  name: string;
  title?: string;
  description?: string;
  inputSchema?: JsonSchema;
}

interface ToolsListResult {
  tools: ToolInfo[];
}

function ToolForm({ tool }: { tool: ToolInfo }) {
  const schema: JsonSchema = useMemo(
    () => tool.inputSchema ?? { type: 'object', properties: {} },
    [tool.inputSchema],
  );
  const [value, setValue] = useState<unknown>(() => getSchemaDefaults(schema) ?? {});
  const [errors, setErrors] = useState<ErrorObject[] | undefined>(undefined);
  const [result, setResult] = useState<CallToolResult | null>(null);
  const [callError, setCallError] = useState<string | null>(null);
  const [calling, setCalling] = useState(false);

  useEffect(() => {
    setValue(getSchemaDefaults(schema) ?? {});
    setResult(null);
    setErrors(undefined);
    setCallError(null);
  }, [tool.name, schema]);

  async function handleSubmit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setCallError(null);
    setResult(null);
    const issues = validateAgainstSchema(schema, isPlainObject(value) ? value : {});
    if (issues) {
      setErrors(issues);
      return;
    }
    setErrors(undefined);
    setCalling(true);
    try {
      const response = await jsonRpc<CallToolResult>('tools/call', {
        name: tool.name,
        arguments: isPlainObject(value) ? value : {},
      });
      if (response.error) {
        setCallError(`${response.error.code}: ${response.error.message}`);
      } else {
        setResult(response.result ?? null);
      }
    } catch (err) {
      setCallError(err instanceof Error ? err.message : String(err));
    } finally {
      setCalling(false);
    }
  }

  return (
    <form onSubmit={handleSubmit} className="space-y-3">
      <header>
        <h3 className="font-mono text-sm font-semibold text-foreground">{tool.title ?? tool.name}</h3>
        {tool.description && <p className="mt-1 text-sm text-muted-foreground">{tool.description}</p>}
      </header>
      <DynamicJsonForm schema={schema} value={value} onChange={setValue} errors={errors} />
      <Button type="submit" variant="primary" disabled={calling}>
        {calling ? 'Calling…' : 'Call tool'}
      </Button>
      {callError && (
        <div className="rounded-md border border-destructive bg-destructive/5 px-3 py-2 text-sm text-destructive">
          {callError}
        </div>
      )}
      <ToolResults result={result} />
    </form>
  );
}

/** Tools tab: lists tools and lets users invoke them with a schema-driven form. */
export function ToolsTab() {
  const [tools, setTools] = useState<ToolInfo[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [selected, setSelected] = useState<ToolInfo | null>(null);

  useEffect(() => {
    let ignore = false;
    jsonRpc<ToolsListResult>('tools/list')
      .then((response) => {
        if (ignore) return;
        if (response.error) {
          setError(`${response.error.code}: ${response.error.message}`);
        } else {
          setTools(response.result?.tools ?? []);
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

  return (
    <section className="space-y-4">
      <header>
        <h2 className="text-xl font-semibold text-foreground">Tools</h2>
        <p className="text-sm text-muted-foreground">Discover and invoke MCP tools exposed by the server.</p>
      </header>

      {error && (
        <div className="rounded-md border border-amber-300 bg-amber-50 px-4 py-3 text-sm text-amber-900 dark:bg-amber-900/30 dark:text-amber-100">
          Not connected: {error}
        </div>
      )}

      {!error && (
        <div className="grid grid-cols-1 gap-4 md:grid-cols-[260px,1fr]">
          <ListPane<ToolInfo>
            items={tools}
            loading={loading}
            empty="No tools registered."
            itemKey={(t) => t.name}
            isSelected={(t) => t.name === selected?.name}
            onSelect={(t) => setSelected(t)}
            renderItem={(t) => (
              <div className="flex items-start gap-2">
                <IconDisplay kind="tool" />
                <div className="min-w-0">
                  <div className="truncate font-mono text-sm">{t.name}</div>
                  {t.description && (
                    <div className="truncate text-xs text-muted-foreground">{t.description}</div>
                  )}
                </div>
              </div>
            )}
          />
          <div className="rounded-md border border-border bg-background p-3">
            {selected ? (
              <ToolForm tool={selected} />
            ) : (
              <p className="text-sm italic text-muted-foreground">Select a tool to invoke it.</p>
            )}
          </div>
        </div>
      )}
    </section>
  );
}
