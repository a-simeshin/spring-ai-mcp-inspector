import { useCallback, useEffect, useRef, useState } from 'react';

export interface UseCopyResult {
  copied: boolean;
  copy: (text: string) => void;
}

/**
 * Clipboard hook. Resets `copied` to false after 1500ms.
 */
export function useCopy(): UseCopyResult {
  const [copied, setCopied] = useState(false);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(
    () => () => {
      if (timer.current) clearTimeout(timer.current);
    },
    [],
  );

  const copy = useCallback((text: string) => {
    void navigator.clipboard
      .writeText(text)
      .then(() => {
        setCopied(true);
        if (timer.current) clearTimeout(timer.current);
        timer.current = setTimeout(() => setCopied(false), 1500);
      })
      .catch(() => {
        setCopied(false);
      });
  }, []);

  return { copied, copy };
}

export default useCopy;
