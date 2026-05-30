import {
  cloneElement,
  createContext,
  isValidElement,
  useCallback,
  useContext,
  useRef,
  useState,
  type ReactElement,
  type ReactNode,
} from 'react';
import clsx from 'clsx';

interface TooltipCtx {
  open: boolean;
  show: () => void;
  hide: () => void;
}
const Ctx = createContext<TooltipCtx | null>(null);
function useTip() {
  const ctx = useContext(Ctx);
  if (!ctx) throw new Error('Tooltip subcomponents must be inside <Tooltip>');
  return ctx;
}

export interface TooltipProps {
  delay?: number;
  children: ReactNode;
}

export function Tooltip({ delay = 300, children }: TooltipProps) {
  const [open, setOpen] = useState(false);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const show = useCallback(() => {
    if (timer.current) clearTimeout(timer.current);
    timer.current = setTimeout(() => setOpen(true), delay);
  }, [delay]);
  const hide = useCallback(() => {
    if (timer.current) clearTimeout(timer.current);
    setOpen(false);
  }, []);
  return <Ctx.Provider value={{ open, show, hide }}>{children}</Ctx.Provider>;
}

export function TooltipTrigger({ children }: { children: ReactElement }) {
  const ctx = useTip();
  if (!isValidElement(children)) return children;
  const childProps = children.props as {
    onMouseEnter?: (e: unknown) => void;
    onMouseLeave?: (e: unknown) => void;
    onFocus?: (e: unknown) => void;
    onBlur?: (e: unknown) => void;
  };
  return cloneElement(children as ReactElement<Record<string, unknown>>, {
    onMouseEnter: (e: unknown) => {
      childProps.onMouseEnter?.(e);
      ctx.show();
    },
    onMouseLeave: (e: unknown) => {
      childProps.onMouseLeave?.(e);
      ctx.hide();
    },
    onFocus: (e: unknown) => {
      childProps.onFocus?.(e);
      ctx.show();
    },
    onBlur: (e: unknown) => {
      childProps.onBlur?.(e);
      ctx.hide();
    },
  });
}

export function TooltipContent({
  children,
  className,
}: {
  children: ReactNode;
  className?: string;
}) {
  const ctx = useTip();
  if (!ctx.open) return null;
  return (
    <div className="relative">
      <div
        role="tooltip"
        data-state="open"
        className={clsx(
          'absolute z-50 mt-1 overflow-hidden rounded-md border bg-popover px-3 py-1.5 text-sm text-popover-foreground shadow-md whitespace-nowrap pointer-events-none',
          className,
        )}
      >
        {children}
      </div>
    </div>
  );
}
