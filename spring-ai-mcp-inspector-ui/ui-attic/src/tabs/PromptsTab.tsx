// Portions adapted from modelcontextprotocol/inspector (Apache-2.0)
// https://github.com/modelcontextprotocol/inspector

import { useEffect, useMemo, useState } from 'react';
import { jsonRpc } from '../api/client';
import { ListPane } from '../components/ListPane';
import { Button } from '../components/ui/Button';
import { DynamicJsonForm } from '../components/DynamicJsonForm';
import { IconDisplay } from '../components/IconDisplay';
import type { JsonSchema } from '../utils/schemaUtils';
import { isPlainObject } from '../utils/jsonUtils';

interface PromptArgument {
  name: string;
  description?: string;
  required?: boolean;
}

interface PromptInfo {
  name: string;
  title?: string;
  description?: string;
  arguments?: PromptArgument[];
}

interface PromptListResult {
  prompts: PromptInfo[];
}

interface PromptMessage {
  role: string;
  content: { type: string; text?: string };
}

interface PromptGetResult {
  description?: string;
  messages: PromptMessage[];
}

function argSchemaFor(prompt: PromptInfo): JsonSchema {
  const properties: Record<string, JsonSchema> = {};
  const required: string[] = [];
  for (const arg of prompt.arguments ?? []) {
    properties[arg.name] = { type: 'string', description: arg.description };
    if (arg.required) required.push(arg.name);
  }
  return { type: 'object', properties, required };
}

function PromptForm({ prompt }: { prompt: PromptInfo }) {
  const schema = useMemo(() => argSchemaFor(prompt), [prompt]);
  const [values, setValues] = useState<unknown>({});
  const [rendered, setRendered] = useState<PromptGetResult | null>(null);
  const [renderError, setRenderError] = useState<string | null>(null);
  const [rendering, setRendering] = useState(false);

  useEffect(() => {
    setValues({});
    setRendered(null);
    setRenderError(null);
  }, [prompt.name]);

  async function handleSubmit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setRenderError(null);
    setRendered(null);
    setRendering(true);
    try {
      const response = await jsonRpc<PromptGetResult>('prompts/get', {
        name: prompt.name,
        arguments: isPlainObject(values) ? values : {},
      });
      if (response.error) {
        setRenderError(`${response.error.code}: ${response.error.message}`);
      } else {
        setRendered(response.result ?? null);
      }
    } catch (err) {
      setRenderError(err instanceof Error ? err.message : String(err));
    } finally {
      setRendering(false);
    }
  }

  return (
    <form onSubmit={handleSubmit} className="space-y-3">
      <header>
        <h3 className="font-mono text-sm font-semibold text-foreground">{prompt.title ?? prompt.name}</h3>
        {prompt.description && <p className="mt-1 text-sm text-muted-foreground">{prompt.description}</p>}
      </header>
      {(prompt.arguments?.length ?? 0) > 0 ? (
        <DynamicJsonForm schema={schema} value={values} onChange={setValues} />
      ) : (
        <p className="text-xs italic text-muted-foreground">No arguments.</p>
      )}
      <Button type="submit" variant="primary" disabled={rendering}>
        {rendering ? 'Rendering…' : 'Render prompt'}
      </Button>
      {renderError && (
        <div className="rounded-md border border-destructive bg-destructive/5 px-3 py-2 text-sm text-destructive">
          {renderError}
        </div>
      )}
      {rendered && (
        <div className="space-y-2">
          {rendered.description && <p className="text-sm text-muted-foreground">{rendered.description}</p>}
          <ul className="space-y-2">
            {rendered.messages.map((message, idx) => (
              <li
                key={idx}
                className="rounded-md border border-border bg-muted p-3"
              >
                <div className="text-xs font-semibold uppercase text-muted-foreground">{message.role}</div>
                <div className="mt-1 whitespace-pre-wrap font-mono text-sm text-foreground">
                  {message.content.text ?? JSON.stringify(message.content)}
                </div>
              </li>
            ))}
          </ul>
        </div>
      )}
    </form>
  );
}

/** Prompts tab: lists prompts and renders templates with arguments. */
export function PromptsTab() {
  const [prompts, setPrompts] = useState<PromptInfo[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [selected, setSelected] = useState<PromptInfo | null>(null);

  useEffect(() => {
    let ignore = false;
    jsonRpc<PromptListResult>('prompts/list')
      .then((response) => {
        if (ignore) return;
        if (response.error) {
          setError(`${response.error.code}: ${response.error.message}`);
        } else {
          setPrompts(response.result?.prompts ?? []);
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
        <h2 className="text-xl font-semibold text-foreground">Prompts</h2>
        <p className="text-sm text-muted-foreground">Render MCP prompt templates with arguments.</p>
      </header>

      {error && (
        <div className="rounded-md border border-amber-300 bg-amber-50 px-4 py-3 text-sm text-amber-900 dark:bg-amber-900/30 dark:text-amber-100">
          Not connected: {error}
        </div>
      )}

      {!error && (
        <div className="grid grid-cols-1 gap-4 md:grid-cols-[260px,1fr]">
          <ListPane<PromptInfo>
            items={prompts}
            loading={loading}
            empty="No prompts registered."
            itemKey={(p) => p.name}
            isSelected={(p) => p.name === selected?.name}
            onSelect={(p) => setSelected(p)}
            renderItem={(p) => (
              <div className="flex items-start gap-2">
                <IconDisplay kind="prompt" />
                <div className="min-w-0">
                  <div className="truncate font-mono text-sm">{p.name}</div>
                  {p.description && (
                    <div className="truncate text-xs text-muted-foreground">{p.description}</div>
                  )}
                </div>
              </div>
            )}
          />
          <div className="rounded-md border border-border bg-background p-3">
            {selected ? (
              <PromptForm prompt={selected} />
            ) : (
              <p className="text-sm italic text-muted-foreground">Select a prompt to render it.</p>
            )}
          </div>
        </div>
      )}
    </section>
  );
}
