// Portions adapted from modelcontextprotocol/inspector (Apache-2.0)
// https://github.com/modelcontextprotocol/inspector

import clsx from 'clsx';
import { JsonView } from './JsonView';
import { ResourceLinkView } from './ResourceLinkView';
import { parseJsonSafe } from '../utils';

interface TextContent {
  type: 'text';
  text: string;
}

interface ImageContent {
  type: 'image';
  data: string;
  mimeType: string;
}

interface ResourceLinkContent {
  type: 'resource_link' | 'resource';
  uri?: string;
  resource?: {
    uri: string;
    mimeType?: string;
    name?: string;
    description?: string;
    text?: string;
  };
  name?: string;
  description?: string;
  mimeType?: string;
}

type Content = TextContent | ImageContent | ResourceLinkContent | Record<string, unknown>;

export interface CallToolResult {
  content?: Content[];
  isError?: boolean;
  structuredContent?: unknown;
}

export interface ToolResultsProps {
  result: CallToolResult | null;
}

function renderContent(item: Content, idx: number) {
  const type = (item as { type?: string }).type;

  if (type === 'text') {
    const text = (item as TextContent).text ?? '';
    const parsed = parseJsonSafe(text);
    if (parsed !== undefined) {
      return (
        <div key={idx} className="rounded-md border border-border bg-muted p-2">
          <JsonView value={parsed} />
        </div>
      );
    }
    return (
      <pre
        key={idx}
        className="whitespace-pre-wrap rounded-md border border-border bg-muted p-2 text-xs text-foreground"
      >
        {text}
      </pre>
    );
  }

  if (type === 'image') {
    const img = item as ImageContent;
    return (
      <img
        key={idx}
        src={`data:${img.mimeType};base64,${img.data}`}
        alt="tool output"
        className="max-w-full rounded-md border border-border"
      />
    );
  }

  if (type === 'resource_link' || type === 'resource') {
    const link = item as ResourceLinkContent;
    const uri = link.uri ?? link.resource?.uri ?? '';
    if (!uri) {
      return (
        <pre key={idx} className="rounded-md border border-border bg-muted p-2 text-xs">
          {JSON.stringify(item, null, 2)}
        </pre>
      );
    }
    return (
      <ResourceLinkView
        key={idx}
        uri={uri}
        name={link.name ?? link.resource?.name}
        description={link.description ?? link.resource?.description}
        mimeType={link.mimeType ?? link.resource?.mimeType}
      />
    );
  }

  return (
    <pre key={idx} className="rounded-md border border-border bg-muted p-2 text-xs">
      {JSON.stringify(item, null, 2)}
    </pre>
  );
}

/** Renders an MCP `CallToolResult` (text/image/resource) with error styling. */
export function ToolResults({ result }: ToolResultsProps) {
  if (!result) return null;
  const items = result.content ?? [];
  return (
    <div
      className={clsx(
        'space-y-2 rounded-md border p-3',
        result.isError ? 'border-destructive bg-destructive/5' : 'border-border bg-background',
      )}
    >
      {result.isError && (
        <div className="text-xs font-semibold uppercase text-destructive">Error</div>
      )}
      {items.length === 0 ? (
        <p className="text-xs italic text-muted-foreground">No content returned.</p>
      ) : (
        items.map((item, idx) => renderContent(item, idx))
      )}
      {result.structuredContent !== undefined && (
        <div>
          <div className="mb-1 text-xs font-semibold uppercase text-muted-foreground">Structured</div>
          <JsonView value={result.structuredContent} />
        </div>
      )}
    </div>
  );
}

export default ToolResults;
