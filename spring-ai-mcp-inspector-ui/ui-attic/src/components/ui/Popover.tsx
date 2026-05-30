import {
  cloneElement,
  createContext,
  isValidElement,
  useCallback,
  useContext,
  useEffect,
  useRef,
  useState,
  type ReactElement,
  type ReactNode,
} from 'react';
import clsx from 'clsx';

interface PopoverCtx {
  open: boolean;
  setOpen: (v: boolean) => void;
  anchor: HTMLElement | null;
  setAnchor: (el: HTMLElement | null) => void;
}
const Ctx = createContext<PopoverCtx | null>(null);
function usePop() {
  const ctx = useContext(Ctx);
  if (!ctx) throw new Error('Popover subcomponents must be inside <Popover>');
  return ctx;
}

export interface PopoverProps {
  open?: boolean;
  defaultOpen?: boolean;
  onOpenChange?: (v: boolean) => void;
  children: ReactNode;
}

export function Popover({ open, defaultOpen, onOpenChange, children }: PopoverProps) {
  const [internal, setInternal] = useState(defaultOpen ?? false);
  const current = open ?? internal;
  const [anchor, setAnchor] = useState<HTMLElement | null>(null);
  const setOpen = useCallback(
    (v: boolean) => {
      if (open === undefined) setInternal(v);
      onOpenChange?.(v);
    },
    [open, onOpenChange],
  );
  return (
    <Ctx.Provider value={{ open: current, setOpen, anchor, setAnchor }}>{children}</Ctx.Provider>
  );
}

export function PopoverTrigger({ children }: { children: ReactElement }) {
  const ctx = usePop();
  if (!isValidElement(children)) return children;
  const childProps = children.props as { onClick?: (e: unknown) => void };
  return cloneElement(children as ReactElement<Record<string, unknown>>, {
    ref: (el: HTMLElement | null) => ctx.setAnchor(el),
    onClick: (e: unknown) => {
      childProps.onClick?.(e);
      ctx.setOpen(!ctx.open);
    },
  });
}

export function PopoverContent({
  children,
  className,
}: {
  children: ReactNode;
  className?: string;
}) {
  const ctx = usePop();
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!ctx.open) return;
    const onDoc = (e: MouseEvent) => {
      if (!ref.current) return;
      if (!ref.current.contains(e.target as Node) && !ctx.anchor?.contains(e.target as Node))
        ctx.setOpen(false);
    };
    document.addEventListener('mousedown', onDoc);
    return () => document.removeEventListener('mousedown', onDoc);
  }, [ctx]);
  if (!ctx.open) return null;
  return (
    <div className="relative">
      <div
        ref={ref}
        data-state="open"
        className={clsx(
          'absolute left-0 z-50 mt-1 w-72 rounded-md border bg-popover p-4 text-popover-foreground shadow-md outline-none',
          className,
        )}
      >
        {children}
      </div>
    </div>
  );
}
