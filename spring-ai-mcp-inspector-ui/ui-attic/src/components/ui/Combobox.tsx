import { useEffect, useMemo, useRef, useState } from 'react';
import { ChevronDown } from 'lucide-react';
import clsx from 'clsx';

export interface ComboboxOption {
  value: string;
  label: string;
}

export interface ComboboxProps {
  value?: string;
  onValueChange?: (v: string) => void;
  options: ComboboxOption[];
  placeholder?: string;
  className?: string;
}

export function Combobox({ value, onValueChange, options, placeholder, className }: ComboboxProps) {
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState('');
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const onDoc = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener('mousedown', onDoc);
    return () => document.removeEventListener('mousedown', onDoc);
  }, [open]);

  const current = options.find((o) => o.value === value);
  const filtered = useMemo(() => {
    if (!query) return options;
    const q = query.toLowerCase();
    return options.filter(
      (o) => o.label.toLowerCase().includes(q) || o.value.toLowerCase().includes(q),
    );
  }, [options, query]);

  return (
    <div ref={ref} className={clsx('relative', className)}>
      <button
        type="button"
        onClick={() => setOpen((o) => !o)}
        aria-haspopup="listbox"
        aria-expanded={open}
        data-state={open ? 'open' : 'closed'}
        className={clsx(
          'flex h-10 w-full items-center justify-between rounded-md border border-input bg-background px-3 py-2 text-sm',
          'ring-offset-background placeholder:text-muted-foreground',
          'focus:outline-none focus:ring-2 focus:ring-ring focus:ring-offset-2',
          'disabled:cursor-not-allowed disabled:opacity-50',
        )}
      >
        <span className={clsx(!current && 'text-muted-foreground')}>
          {current?.label ?? placeholder ?? ''}
        </span>
        <ChevronDown className="h-4 w-4 opacity-50" />
      </button>
      {open && (
        <div
          data-state="open"
          className="absolute left-0 right-0 z-50 mt-1 rounded-md border bg-popover text-popover-foreground shadow-md"
        >
          <input
            autoFocus
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="Search…"
            className="flex h-10 w-full rounded-t-md border-b border-input bg-transparent px-3 py-2 text-sm outline-none placeholder:text-muted-foreground"
          />
          <div role="listbox" className="max-h-56 overflow-auto p-1">
            {filtered.length === 0 ? (
              <div className="px-3 py-2 text-sm text-muted-foreground">No results</div>
            ) : (
              filtered.map((o) => (
                <div
                  key={o.value}
                  role="option"
                  aria-selected={o.value === value}
                  data-state={o.value === value ? 'checked' : 'unchecked'}
                  onClick={() => {
                    onValueChange?.(o.value);
                    setOpen(false);
                    setQuery('');
                  }}
                  className={clsx(
                    'relative flex w-full cursor-default select-none items-center rounded-sm px-3 py-1.5 text-sm outline-none',
                    'hover:bg-accent hover:text-accent-foreground',
                    'data-[state=checked]:bg-accent data-[state=checked]:text-accent-foreground',
                  )}
                >
                  {o.label}
                </div>
              ))
            )}
          </div>
        </div>
      )}
    </div>
  );
}
