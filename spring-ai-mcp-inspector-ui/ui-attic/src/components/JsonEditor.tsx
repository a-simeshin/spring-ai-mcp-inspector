// Portions adapted from modelcontextprotocol/inspector (Apache-2.0)
// https://github.com/modelcontextprotocol/inspector

import { useEffect, useState, type ChangeEvent, type FocusEvent } from 'react';
import clsx from 'clsx';

export interface JsonEditorProps {
  value: string;
  onChange: (next: string) => void;
  placeholder?: string;
  rows?: number;
  /** External error to display under the editor (e.g. server validation). */
  error?: string;
}

/**
 * Textarea-backed JSON editor with live blur-time validation.
 *
 * The editor never blocks input; instead, on blur it tries `JSON.parse` and
 * displays a red border + error message when the content is not valid JSON.
 */
export function JsonEditor({ value, onChange, placeholder, rows = 8, error }: JsonEditorProps) {
  const [text, setText] = useState(value ?? '');
  const [internalError, setInternalError] = useState<string | undefined>(undefined);

  useEffect(() => {
    setText(value ?? '');
  }, [value]);

  const handleChange = (e: ChangeEvent<HTMLTextAreaElement>) => {
    const next = e.target.value;
    setText(next);
    setInternalError(undefined);
    onChange(next);
  };

  const handleBlur = (_e: FocusEvent<HTMLTextAreaElement>) => {
    if (text.trim() === '') {
      setInternalError(undefined);
      return;
    }
    try {
      JSON.parse(text);
      setInternalError(undefined);
    } catch (err) {
      setInternalError(err instanceof Error ? err.message : 'Invalid JSON');
    }
  };

  const displayError = internalError ?? error;

  return (
    <div className="space-y-1">
      <textarea
        value={text}
        onChange={handleChange}
        onBlur={handleBlur}
        placeholder={placeholder}
        rows={rows}
        spellCheck={false}
        className={clsx(
          'w-full font-mono text-xs px-3 py-2 rounded-md border bg-background text-foreground placeholder:text-muted-foreground',
          'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent/50',
          displayError ? 'border-destructive' : 'border-border',
        )}
      />
      {displayError && <p className="text-xs text-destructive">{displayError}</p>}
    </div>
  );
}

export default JsonEditor;
