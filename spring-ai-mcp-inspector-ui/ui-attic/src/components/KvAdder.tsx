import { useState } from 'react';
import { X } from 'lucide-react';
import { Button } from './ui/Button';
import { Input } from './ui/Input';
import { Label } from './ui/Label';

export interface KvAdderProps {
  /** Field label, displayed in uppercase. */
  label: string;
  existing: Record<string, string>;
  onChange: (next: Record<string, string>) => void;
  /** ARIA label for the add button. */
  addAriaLabel: string;
}

/**
 * Add/remove key=value pairs in a small inline popover.
 *
 * Used by the Sidebar for environment variables and HTTP headers.
 */
export function KvAdder({ label, existing, onChange, addAriaLabel }: KvAdderProps) {
  const [open, setOpen] = useState(false);
  const [k, setK] = useState('');
  const [v, setV] = useState('');

  const submit = () => {
    if (!k.trim()) return;
    onChange({ ...existing, [k.trim()]: v });
    setK('');
    setV('');
    setOpen(false);
  };

  const remove = (key: string) => {
    const next = { ...existing };
    delete next[key];
    onChange(next);
  };

  const entries = Object.entries(existing);

  return (
    <div className="space-y-2">
      <div className="flex items-center justify-between">
        <Label className="text-xs uppercase tracking-wide text-muted-foreground">{label}</Label>
        <Button
          type="button"
          size="sm"
          variant="outline"
          onClick={() => setOpen((o) => !o)}
          aria-label={addAriaLabel}
        >
          +
        </Button>
      </div>
      {open && (
        <div className="space-y-1 rounded-md border border-border bg-muted p-2">
          <Input placeholder="key" value={k} onChange={(e) => setK(e.target.value)} />
          <Input placeholder="value" value={v} onChange={(e) => setV(e.target.value)} />
          <div className="flex gap-1">
            <Button type="button" size="sm" variant="primary" onClick={submit}>
              Add
            </Button>
            <Button type="button" size="sm" variant="ghost" onClick={() => setOpen(false)}>
              Cancel
            </Button>
          </div>
        </div>
      )}
      {entries.length > 0 && (
        <ul className="space-y-1">
          {entries.map(([key, val]) => (
            <li
              key={key}
              className="flex items-center justify-between gap-2 rounded border border-border bg-background px-2 py-1 text-xs font-mono"
            >
              <span className="truncate">
                <span className="text-muted-foreground">{key}</span>={val}
              </span>
              <button
                type="button"
                onClick={() => remove(key)}
                className="text-muted-foreground hover:text-destructive"
                aria-label={`Remove ${key}`}
              >
                <X size={12} />
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

export default KvAdder;
