import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type KeyboardEvent,
  type ReactNode,
} from 'react';
import clsx from 'clsx';

interface CommandCtx {
  query: string;
  setQuery: (v: string) => void;
  activeIndex: number;
  setActiveIndex: (n: number) => void;
  registerItem: (value: string, onSelect: () => void, text: string) => void;
  items: Array<{ value: string; onSelect: () => void; text: string }>;
}
const Ctx = createContext<CommandCtx | null>(null);
function useCmd() {
  const ctx = useContext(Ctx);
  if (!ctx) throw new Error('Command subcomponents must be inside <Command>');
  return ctx;
}

export function Command({ children, className }: { children: ReactNode; className?: string }) {
  const [query, setQuery] = useState('');
  const [activeIndex, setActiveIndex] = useState(0);
  const itemsRef = useRef<Array<{ value: string; onSelect: () => void; text: string }>>([]);
  const [, force] = useState(0);
  const registerItem = useCallback((value: string, onSelect: () => void, text: string) => {
    const existing = itemsRef.current.findIndex((i) => i.value === value);
    if (existing >= 0) itemsRef.current[existing] = { value, onSelect, text };
    else itemsRef.current.push({ value, onSelect, text });
    force((n) => n + 1);
  }, []);
  const ctxValue = useMemo(
    () => ({ query, setQuery, activeIndex, setActiveIndex, registerItem, items: itemsRef.current }),
    [query, activeIndex, registerItem],
  );
  return (
    <Ctx.Provider value={ctxValue}>
      <div
        className={clsx(
          'flex h-full w-full flex-col overflow-hidden rounded-md border bg-popover text-popover-foreground',
          className,
        )}
      >
        {children}
      </div>
    </Ctx.Provider>
  );
}

export function CommandInput({ placeholder }: { placeholder?: string }) {
  const ctx = useCmd();
  const filtered = ctx.items.filter((i) =>
    i.text.toLowerCase().includes(ctx.query.toLowerCase()),
  );
  const onKeyDown = (e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      ctx.setActiveIndex(Math.min(filtered.length - 1, ctx.activeIndex + 1));
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      ctx.setActiveIndex(Math.max(0, ctx.activeIndex - 1));
    } else if (e.key === 'Enter') {
      e.preventDefault();
      filtered[ctx.activeIndex]?.onSelect();
    }
  };
  return (
    <div className="flex items-center border-b px-3">
      <input
        autoFocus
        value={ctx.query}
        onChange={(e) => {
          ctx.setQuery(e.target.value);
          ctx.setActiveIndex(0);
        }}
        onKeyDown={onKeyDown}
        placeholder={placeholder ?? 'Type a command…'}
        className="flex h-11 w-full rounded-md bg-transparent py-3 text-sm outline-none placeholder:text-muted-foreground disabled:cursor-not-allowed disabled:opacity-50"
      />
    </div>
  );
}

export function CommandList({
  children,
  className,
}: {
  children: ReactNode;
  className?: string;
}) {
  return (
    <div className={clsx('max-h-[300px] overflow-y-auto overflow-x-hidden p-1', className)}>
      {children}
    </div>
  );
}

export function CommandItem({
  value,
  onSelect,
  children,
  className,
}: {
  value: string;
  onSelect: () => void;
  children: ReactNode;
  className?: string;
}) {
  const ctx = useCmd();
  const text = typeof children === 'string' ? children : value;
  useEffect(() => {
    ctx.registerItem(value, onSelect, text);
  }, [ctx, value, onSelect, text]);
  const filtered = ctx.items.filter((i) =>
    i.text.toLowerCase().includes(ctx.query.toLowerCase()),
  );
  const visible = filtered.some((i) => i.value === value);
  if (!visible) return null;
  const activeValue = filtered[ctx.activeIndex]?.value;
  const active = activeValue === value;
  return (
    <div
      role="option"
      aria-selected={active}
      data-state={active ? 'active' : 'inactive'}
      onClick={onSelect}
      onMouseEnter={() => {
        const idx = filtered.findIndex((i) => i.value === value);
        if (idx >= 0) ctx.setActiveIndex(idx);
      }}
      className={clsx(
        'relative flex cursor-default select-none items-center rounded-sm px-2 py-1.5 text-sm outline-none',
        'hover:bg-accent hover:text-accent-foreground',
        active && 'bg-accent text-accent-foreground',
        className,
      )}
    >
      {children}
    </div>
  );
}
