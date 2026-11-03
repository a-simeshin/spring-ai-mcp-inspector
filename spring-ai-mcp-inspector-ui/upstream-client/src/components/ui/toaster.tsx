import type { CSSProperties } from "react";
import { useEffect, useState } from "react";

import { useToast } from "@/lib/hooks/useToast";
import {
  Toast,
  ToastClose,
  ToastDescription,
  ToastProvider,
  ToastTitle,
  ToastViewport,
} from "@/components/ui/toast";

// [spring-ai-mcp-inspector PATCH] Toast placement vs. the notifications pane
// (issue #267): toasts stack in the bottom-right corner and covered the server
// notifications pane rendered along the bottom of the right column. The
// viewport reads the pane's on-screen top edge and raises itself above it;
// when the pane is absent or hidden the offset is unset and toasts keep their
// original position.
const NOTIFICATIONS_PANE_GAP_PX = 8;

function measureToastViewportOffset(): number | null {
  if (typeof document === "undefined") {
    return null;
  }
  const pane = document.querySelector<HTMLElement>(
    '[data-testid="notifications-pane"]',
  );
  if (!pane) {
    return null;
  }
  const rect = pane.getBoundingClientRect();
  if (rect.height <= 0 || rect.top <= 0) {
    return null;
  }
  return Math.max(window.innerHeight - rect.top + NOTIFICATIONS_PANE_GAP_PX, 0);
}

export function Toaster() {
  const { toasts } = useToast();
  const [offsetPx, setOffsetPx] = useState<number | null>(null);

  useEffect(() => {
    const measure = () => setOffsetPx(measureToastViewportOffset());
    measure();
    window.addEventListener("resize", measure);
    let observer: ResizeObserver | undefined;
    if (typeof ResizeObserver !== "undefined") {
      observer = new ResizeObserver(measure);
      if (document.documentElement) {
        observer.observe(document.documentElement);
      }
      if (document.body) {
        observer.observe(document.body);
      }
    }
    return () => {
      window.removeEventListener("resize", measure);
      observer?.disconnect();
    };
  }, []);

  return (
    <ToastProvider>
      {toasts.map(function ({ id, title, description, action, ...props }) {
        return (
          <Toast key={id} {...props}>
            <div className="grid gap-1">
              {title && <ToastTitle>{title}</ToastTitle>}
              {description && (
                <ToastDescription>{description}</ToastDescription>
              )}
            </div>
            {action}
            <ToastClose />
          </Toast>
        );
      })}
      <ToastViewport
        style={
          offsetPx === null
            ? undefined
            : ({
                "--toast-viewport-offset": `${offsetPx}px`,
              } as CSSProperties)
        }
      />
    </ToastProvider>
  );
}
