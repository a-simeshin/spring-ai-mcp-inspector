// Portions adapted from modelcontextprotocol/inspector (Apache-2.0)
// https://github.com/modelcontextprotocol/inspector

import { useState } from 'react';
import { Button } from './ui/Button';
import { Label } from './ui/Label';
import { JsonView } from './JsonView';

export interface SamplingRequestProps {
  requestId: string;
  params: unknown;
  status: 'pending' | 'approved' | 'rejected';
  onApprove: (requestId: string, text: string) => void;
  onReject: (requestId: string) => void;
}

interface SamplingParams {
  messages?: unknown;
  modelPreferences?: unknown;
  systemPrompt?: string;
  maxTokens?: number;
}

/** Single sampling-request card (messages preview + response form). */
export function SamplingRequest({
  requestId,
  params,
  status,
  onApprove,
  onReject,
}: SamplingRequestProps) {
  const [text, setText] = useState('');
  const p = (params as SamplingParams) ?? {};

  return (
    <div
      data-testid="sampling-request"
      className="space-y-3 rounded-lg border border-border bg-background p-4"
    >
      <header className="flex items-center justify-between">
        <span className="font-mono text-xs text-muted-foreground">#{requestId}</span>
        <span
          className={`rounded px-2 py-0.5 text-xs ${
            status === 'pending'
              ? 'bg-amber-100 text-amber-900 dark:bg-amber-900/30 dark:text-amber-100'
              : status === 'approved'
                ? 'bg-emerald-100 text-emerald-900 dark:bg-emerald-900/30 dark:text-emerald-100'
                : 'bg-red-100 text-red-900 dark:bg-red-900/30 dark:text-red-100'
          }`}
        >
          {status}
        </span>
      </header>

      <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
        <section className="space-y-2 rounded-md border border-border bg-muted p-3">
          <Label className="text-xs uppercase tracking-wide text-muted-foreground">Messages</Label>
          {p.messages !== undefined ? (
            <JsonView value={p.messages} />
          ) : (
            <p className="text-xs italic text-muted-foreground">No messages.</p>
          )}
          {p.modelPreferences !== undefined && (
            <details className="text-xs">
              <summary className="cursor-pointer text-muted-foreground">Model preferences</summary>
              <div className="mt-1">
                <JsonView value={p.modelPreferences} />
              </div>
            </details>
          )}
          {p.systemPrompt && (
            <details className="text-xs">
              <summary className="cursor-pointer text-muted-foreground">System prompt</summary>
              <pre className="mt-1 whitespace-pre-wrap text-xs">{p.systemPrompt}</pre>
            </details>
          )}
        </section>

        <section className="space-y-2">
          <Label htmlFor={`resp-${requestId}`} className="text-xs uppercase tracking-wide text-muted-foreground">
            Response (assistant text)
          </Label>
          <textarea
            id={`resp-${requestId}`}
            value={text}
            onChange={(e) => setText(e.target.value)}
            placeholder="Type the assistant's response…"
            disabled={status !== 'pending'}
            className="h-32 w-full resize-none rounded-md border border-border bg-background p-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent/50 disabled:opacity-50"
          />
          <div className="flex gap-2">
            <Button
              type="button"
              variant="primary"
              size="sm"
              disabled={status !== 'pending' || text.trim() === ''}
              onClick={() => onApprove(requestId, text)}
            >
              Approve
            </Button>
            <Button
              type="button"
              variant="outline"
              size="sm"
              disabled={status !== 'pending'}
              onClick={() => onReject(requestId)}
            >
              Reject
            </Button>
          </div>
        </section>
      </div>
    </div>
  );
}

export default SamplingRequest;
