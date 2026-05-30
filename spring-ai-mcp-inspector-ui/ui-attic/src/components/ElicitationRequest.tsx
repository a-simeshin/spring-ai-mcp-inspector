// Portions adapted from modelcontextprotocol/inspector (Apache-2.0)
// https://github.com/modelcontextprotocol/inspector

import { useEffect, useState } from 'react';
import { Button } from './ui/Button';
import { Label } from './ui/Label';
import { DynamicJsonForm, validateAgainstSchema } from './DynamicJsonForm';
import type { JsonSchema } from '../utils/schemaUtils';
import { getSchemaDefaults } from '../utils/schemaUtils';

export interface ElicitationRequestProps {
  requestId: string;
  message?: string;
  schema: JsonSchema;
  status: 'pending' | 'accepted' | 'declined' | 'cancelled';
  onAccept: (requestId: string, content: Record<string, unknown>) => void;
  onDecline: (requestId: string) => void;
  onCancel: (requestId: string) => void;
}

/** Single elicitation-request card (schema-driven form + action buttons). */
export function ElicitationRequest({
  requestId,
  message,
  schema,
  status,
  onAccept,
  onDecline,
  onCancel,
}: ElicitationRequestProps) {
  const [value, setValue] = useState<unknown>(() => getSchemaDefaults(schema) ?? {});
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    setValue(getSchemaDefaults(schema) ?? {});
    setError(null);
  }, [schema]);

  const handleSubmit = () => {
    const errors = validateAgainstSchema(schema, value);
    if (errors && errors.length > 0) {
      setError(errors.map((e) => `${e.instancePath || '/'} ${e.message}`).join('; '));
      return;
    }
    setError(null);
    onAccept(requestId, (value as Record<string, unknown>) ?? {});
  };

  return (
    <div
      data-testid="elicitation-request"
      className="space-y-3 rounded-lg border border-border bg-background p-4"
    >
      <header className="flex items-center justify-between">
        <span className="font-mono text-xs text-muted-foreground">#{requestId}</span>
        <span
          className={`rounded px-2 py-0.5 text-xs ${
            status === 'pending'
              ? 'bg-amber-100 text-amber-900 dark:bg-amber-900/30 dark:text-amber-100'
              : 'bg-emerald-100 text-emerald-900 dark:bg-emerald-900/30 dark:text-emerald-100'
          }`}
        >
          {status}
        </span>
      </header>

      {message && (
        <p className="rounded-md border border-border bg-muted p-2 text-sm">{message}</p>
      )}

      <div className="space-y-2">
        <Label className="text-xs uppercase tracking-wide text-muted-foreground">Response</Label>
        <DynamicJsonForm schema={schema} value={value} onChange={setValue} />
        {error && <p className="text-xs text-destructive">{error}</p>}
      </div>

      <div className="flex gap-2">
        <Button
          type="button"
          variant="primary"
          size="sm"
          disabled={status !== 'pending'}
          onClick={handleSubmit}
        >
          Submit
        </Button>
        <Button
          type="button"
          variant="outline"
          size="sm"
          disabled={status !== 'pending'}
          onClick={() => onDecline(requestId)}
        >
          Decline
        </Button>
        <Button
          type="button"
          variant="ghost"
          size="sm"
          disabled={status !== 'pending'}
          onClick={() => onCancel(requestId)}
        >
          Cancel
        </Button>
      </div>
    </div>
  );
}

export default ElicitationRequest;
