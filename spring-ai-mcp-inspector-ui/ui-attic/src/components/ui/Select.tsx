import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react';
import { Check, ChevronDown } from 'lucide-react';
import clsx from 'clsx';

interface SelectCtx {
  value: string | undefined;
  setValue: (v: string) => void;
  open: boolean;
  setOpen: (v: boolean) => void;
  labels: Map<string, ReactNode>;
  registerLabel: (v: string, l: ReactNode) => void;
}
const Ctx = createContext<SelectCtx | null>(null);
function useSel() {
  const ctx = useContext(Ctx);
  if (!ctx) throw new Error('Select subcomponents must be inside <Select>');
  return ctx;
}

export interface SelectProps {
  value?: string;
  onValueChange?: (v: string) => void;
  children: ReactNode;
}

export function Select({ value, onValueChange, children }: SelectProps) {
  const [open, setOpen] = useState(false);
  const labels = useRef(new Map<string, ReactNode>());
  const [, force] = useState(0);
  const registerLabel = useCallback((v: string, l: ReactNode) => {
    labels.current.set(v, l);
    force((n) => n + 1);
  }, []);
  const setValue = useCallback(
    (v: string) => {
      onValueChange?.(v);
      setOpen(false);
    },
    [onValueChange],
  );
  const ctx = useMemo(
    () => ({ value, setValue, open, setOpen, labels: labels.current, registerLabel }),
    [value, setValue, open, registerLabel],
  );
  return <Ctx.Provider value={ctx}>{children}</Ctx.Provider>;
}

export function SelectTrigger({
  children,
  className,
}: {
  children?: ReactNode;
  className?: string;
}) {
  const ctx = useSel();
  const ref = useRef<HTMLButtonElement>(null);
  useEffect(() => {
    if (!ctx.open) return;
    const onDoc = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) ctx.setOpen(false);
    };
    document.addEventListener('mousedown', onDoc);
    return () => document.removeEventListener('mousedown', onDoc);
  }, [ctx]);
  return (
    <button
      ref={ref}
      type="button"
      onClick={() => ctx.setOpen(!ctx.open)}
      aria-haspopup="listbox"
      aria-expanded={ctx.open}
      data-state={ctx.open ? 'open' : 'closed'}
      className={clsx(
        'flex h-10 w-full items-center justify-between rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background',
        'placeholder:text-muted-foreground',
        'focus:outline-none focus:ring-2 focus:ring-ring focus:ring-offset-2',
        'disabled:cursor-not-allowed disabled:opacity-50',
        '[&>span]:line-clamp-1',
        className,
      )}
    >
      {children}
      <ChevronDown className="h-4 w-4 opacity-50" />
    </button>
  );
}

export function SelectValue({ placeholder }: { placeholder?: string }) {
  const ctx = useSel();
  const label = ctx.value !== undefined ? ctx.labels.get(ctx.value) : undefined;
  return (
    <span className={clsx(!label && 'text-muted-foreground')}>{label ?? placeholder ?? ''}</span>
  );
}

export function SelectContent({
  children,
  className,
}: {
  children: ReactNode;
  className?: string;
}) {
  const ctx = useSel();
  if (!ctx.open) return <div className="hidden">{children}</div>;
  return (
    <div className="relative">
      <div
        role="listbox"
        data-state="open"
        className={clsx(
          'absolute left-0 right-0 z-50 mt-1 max-h-60 min-w-[8rem] overflow-y-auto overflow-x-hidden',
          'rounded-md border bg-popover text-popover-foreground shadow-md',
          'p-1',
          className,
        )}
      >
        {children}
      </div>
    </div>
  );
}

export function SelectItem({
  value,
  children,
  className,
}: {
  value: string;
  children: ReactNode;
  className?: string;
}) {
  const ctx = useSel();
  useEffect(() => {
    ctx.registerLabel(value, children);
  }, [ctx, value, children]);
  const selected = ctx.value === value;
  return (
    <div
      role="option"
      aria-selected={selected}
      data-state={selected ? 'checked' : 'unchecked'}
      onClick={() => ctx.setValue(value)}
      className={clsx(
        'relative flex w-full cursor-default select-none items-center rounded-sm py-1.5 pl-8 pr-2 text-sm outline-none',
        'hover:bg-accent hover:text-accent-foreground',
        'focus:bg-accent focus:text-accent-foreground',
        'data-[state=checked]:bg-accent data-[state=checked]:text-accent-foreground',
        className,
      )}
    >
      <span className="absolute left-2 flex h-3.5 w-3.5 items-center justify-center">
        {selected && <Check className="h-4 w-4" />}
      </span>
      {children}
    </div>
  );
}
