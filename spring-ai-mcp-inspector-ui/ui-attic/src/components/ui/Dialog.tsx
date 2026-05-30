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
import { createPortal } from 'react-dom';
import clsx from 'clsx';

interface DialogCtx {
  open: boolean;
  setOpen: (v: boolean) => void;
}
const Ctx = createContext<DialogCtx | null>(null);
function useDialog() {
  const ctx = useContext(Ctx);
  if (!ctx) throw new Error('Dialog subcomponents must be inside <Dialog>');
  return ctx;
}

export interface DialogProps {
  open?: boolean;
  defaultOpen?: boolean;
  onOpenChange?: (v: boolean) => void;
  children: ReactNode;
}

export function Dialog({ open, defaultOpen, onOpenChange, children }: DialogProps) {
  const [internal, setInternal] = useState(defaultOpen ?? false);
  const current = open ?? internal;
  const setOpen = useCallback(
    (v: boolean) => {
      if (open === undefined) setInternal(v);
      onOpenChange?.(v);
    },
    [open, onOpenChange],
  );
  return <Ctx.Provider value={{ open: current, setOpen }}>{children}</Ctx.Provider>;
}

export function DialogTrigger({ children }: { children: ReactElement }) {
  const ctx = useDialog();
  if (!isValidElement(children)) return children;
  const childProps = children.props as { onClick?: (e: unknown) => void };
  return cloneElement(children as ReactElement<Record<string, unknown>>, {
    onClick: (e: unknown) => {
      childProps.onClick?.(e);
      ctx.setOpen(true);
    },
  });
}

export function DialogContent({
  children,
  className,
}: {
  children: ReactNode;
  className?: string;
}) {
  const ctx = useDialog();
  const ref = useRef<HTMLDivElement>(null);
  const prevActive = useRef<HTMLElement | null>(null);

  useEffect(() => {
    if (!ctx.open) return;
    prevActive.current = document.activeElement as HTMLElement | null;
    const focusable = ref.current?.querySelector<HTMLElement>(
      'button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])',
    );
    focusable?.focus();
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') ctx.setOpen(false);
    };
    window.addEventListener('keydown', onKey);
    return () => {
      window.removeEventListener('keydown', onKey);
      prevActive.current?.focus?.();
    };
  }, [ctx]);

  if (!ctx.open) return null;
  return createPortal(
    <>
      <div
        data-state="open"
        className="fixed inset-0 z-50 bg-background/80 backdrop-blur-sm"
        onClick={() => ctx.setOpen(false)}
      />
      <div
        ref={ref}
        role="dialog"
        aria-modal="true"
        data-state="open"
        onClick={(e) => e.stopPropagation()}
        className={clsx(
          'fixed left-[50%] top-[50%] z-50 grid w-full max-w-lg translate-x-[-50%] translate-y-[-50%] gap-4',
          'border bg-background p-6 shadow-lg duration-200 sm:rounded-lg',
          className,
        )}
      >
        {children}
      </div>
    </>,
    document.body,
  );
}

export function DialogTitle({
  children,
  className,
}: {
  children: ReactNode;
  className?: string;
}) {
  return (
    <h2 className={clsx('text-lg font-semibold leading-none tracking-tight', className)}>
      {children}
    </h2>
  );
}

export function DialogDescription({
  children,
  className,
}: {
  children: ReactNode;
  className?: string;
}) {
  return <p className={clsx('text-sm text-muted-foreground', className)}>{children}</p>;
}

export function DialogFooter({
  children,
  className,
}: {
  children: ReactNode;
  className?: string;
}) {
  return (
    <div
      className={clsx(
        'flex flex-col-reverse sm:flex-row sm:justify-end sm:space-x-2',
        className,
      )}
    >
      {children}
    </div>
  );
}

export function DialogHeader({
  children,
  className,
}: {
  children: ReactNode;
  className?: string;
}) {
  return (
    <div className={clsx('flex flex-col space-y-1.5 text-center sm:text-left', className)}>
      {children}
    </div>
  );
}
