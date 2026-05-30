import {
  createContext,
  useCallback,
  useContext,
  useMemo,
  useState,
  type ReactNode,
} from 'react';
import clsx from 'clsx';

export type ToastVariant = 'default' | 'success' | 'danger' | 'destructive';

export interface ToastOptions {
  title: string;
  description?: string;
  variant?: ToastVariant;
  duration?: number;
}

interface ToastItem extends ToastOptions {
  id: number;
}

interface ToastCtx {
  toast: (opts: ToastOptions) => void;
}
const Ctx = createContext<ToastCtx | null>(null);

let _id = 0;

export function ToastProvider({ children }: { children: ReactNode }) {
  const [items, setItems] = useState<ToastItem[]>([]);

  const remove = useCallback((id: number) => {
    setItems((cur) => cur.filter((t) => t.id !== id));
  }, []);

  const toast = useCallback(
    (opts: ToastOptions) => {
      const id = ++_id;
      const item: ToastItem = { id, ...opts };
      setItems((cur) => [...cur, item]);
      const duration = opts.duration ?? 4000;
      setTimeout(() => remove(id), duration);
    },
    [remove],
  );

  const ctxValue = useMemo(() => ({ toast }), [toast]);
  return (
    <Ctx.Provider value={ctxValue}>
      {children}
      <div className="fixed bottom-0 right-0 z-[100] flex max-h-screen w-full flex-col-reverse gap-2 p-4 sm:bottom-0 sm:right-0 sm:top-auto sm:flex-col md:max-w-[420px]">
        {items.map((t) => {
          const isDestructive = t.variant === 'danger' || t.variant === 'destructive';
          const isSuccess = t.variant === 'success';
          return (
            <div
              key={t.id}
              role="status"
              data-state="open"
              className={clsx(
                'pointer-events-auto relative flex w-full items-center justify-between space-x-4 overflow-hidden rounded-md border p-6 pr-8 shadow-lg',
                isDestructive && 'border-destructive bg-destructive text-destructive-foreground',
                isSuccess && 'border-success/50 bg-background text-foreground',
                !isDestructive && !isSuccess && 'border bg-background text-foreground',
              )}
            >
              <div className="grid gap-1">
                <div className="text-sm font-semibold leading-none tracking-tight">{t.title}</div>
                {t.description && (
                  <div
                    className={clsx(
                      'text-sm opacity-90',
                      !isDestructive && 'text-muted-foreground',
                    )}
                  >
                    {t.description}
                  </div>
                )}
              </div>
            </div>
          );
        })}
      </div>
    </Ctx.Provider>
  );
}

export function useToast(): ToastCtx {
  const ctx = useContext(Ctx);
  if (!ctx) {
    // Fallback no-op for environments without provider (tests).
    return { toast: () => {} };
  }
  return ctx;
}
