import {
  createContext,
  useCallback,
  useContext,
  useId,
  useMemo,
  useRef,
  useState,
  type KeyboardEvent,
  type ReactNode,
} from 'react';
import clsx from 'clsx';

interface TabsCtx {
  value: string;
  setValue: (v: string) => void;
  baseId: string;
  registerTrigger: (v: string, el: HTMLButtonElement | null) => void;
  orderedValues: () => string[];
}
const Ctx = createContext<TabsCtx | null>(null);
function useTabs() {
  const ctx = useContext(Ctx);
  if (!ctx) throw new Error('Tabs subcomponents must be inside <Tabs>');
  return ctx;
}

export interface TabsProps {
  value?: string;
  defaultValue?: string;
  onValueChange?: (v: string) => void;
  children: ReactNode;
  className?: string;
}

export function Tabs({ value, defaultValue, onValueChange, children, className }: TabsProps) {
  const [internal, setInternal] = useState(defaultValue ?? '');
  const current = value ?? internal;
  const baseId = useId();
  const triggers = useRef(new Map<string, HTMLButtonElement | null>());
  const order = useRef<string[]>([]);

  const setValue = useCallback(
    (v: string) => {
      if (value === undefined) setInternal(v);
      onValueChange?.(v);
    },
    [value, onValueChange],
  );

  const registerTrigger = useCallback((v: string, el: HTMLButtonElement | null) => {
    triggers.current.set(v, el);
    if (!order.current.includes(v)) order.current.push(v);
  }, []);

  const orderedValues = useCallback(() => order.current.slice(), []);

  const ctx = useMemo(
    () => ({ value: current, setValue, baseId, registerTrigger, orderedValues }),
    [current, setValue, baseId, registerTrigger, orderedValues],
  );

  return (
    <Ctx.Provider value={ctx}>
      <div className={className}>{children}</div>
    </Ctx.Provider>
  );
}

export function TabsList({ children, className }: { children: ReactNode; className?: string }) {
  return (
    <div
      role="tablist"
      className={clsx(
        'inline-flex h-10 items-center justify-center rounded-md bg-muted p-1 text-muted-foreground',
        className,
      )}
    >
      {children}
    </div>
  );
}

export function TabsTrigger({
  value,
  children,
  className,
}: {
  value: string;
  children: ReactNode;
  className?: string;
}) {
  const ctx = useTabs();
  const selected = ctx.value === value;
  const onKeyDown = (e: KeyboardEvent<HTMLButtonElement>) => {
    if (e.key !== 'ArrowRight' && e.key !== 'ArrowLeft') return;
    e.preventDefault();
    const list = ctx.orderedValues();
    const idx = list.indexOf(value);
    if (idx < 0) return;
    const next =
      e.key === 'ArrowRight'
        ? list[(idx + 1) % list.length]
        : list[(idx - 1 + list.length) % list.length];
    ctx.setValue(next);
  };
  return (
    <button
      ref={(el) => ctx.registerTrigger(value, el)}
      type="button"
      role="tab"
      aria-selected={selected}
      data-state={selected ? 'active' : 'inactive'}
      aria-controls={`${ctx.baseId}-panel-${value}`}
      id={`${ctx.baseId}-tab-${value}`}
      tabIndex={selected ? 0 : -1}
      onKeyDown={onKeyDown}
      onClick={() => ctx.setValue(value)}
      className={clsx(
        'inline-flex items-center justify-center whitespace-nowrap rounded-sm px-3 py-1.5 text-sm font-medium',
        'ring-offset-background transition-all',
        'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2',
        'disabled:pointer-events-none disabled:opacity-50',
        'data-[state=active]:bg-background data-[state=active]:text-foreground data-[state=active]:shadow-sm',
        className,
      )}
    >
      {children}
    </button>
  );
}

export function TabsContent({
  value,
  children,
  className,
}: {
  value: string;
  children: ReactNode;
  className?: string;
}) {
  const ctx = useTabs();
  if (ctx.value !== value) return null;
  return (
    <div
      role="tabpanel"
      id={`${ctx.baseId}-panel-${value}`}
      aria-labelledby={`${ctx.baseId}-tab-${value}`}
      data-state="active"
      className={clsx(
        'mt-2 ring-offset-background focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2',
        className,
      )}
    >
      {children}
    </div>
  );
}
