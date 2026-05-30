import { useCallback, useEffect, useRef, useState, type MouseEvent as ReactMouseEvent } from 'react';

export interface UseDraggablePaneOptions {
  defaultWidth: number;
  min: number;
  max: number;
  /** Optional localStorage key to persist width. */
  storageKey?: string;
}

export interface UseDraggablePaneResult {
  width: number;
  isDragging: boolean;
  handleProps: {
    onMouseDown: (e: ReactMouseEvent) => void;
  };
}

function clamp(v: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, v));
}

/**
 * Horizontal resizable pane hook. Drag a handle, get width state.
 * Persists in localStorage when `storageKey` is provided.
 */
export function useDraggablePane({
  defaultWidth,
  min,
  max,
  storageKey,
}: UseDraggablePaneOptions): UseDraggablePaneResult {
  const [width, setWidth] = useState<number>(() => {
    if (!storageKey || typeof window === 'undefined') return defaultWidth;
    const raw = window.localStorage.getItem(storageKey);
    if (!raw) return defaultWidth;
    const parsed = Number(raw);
    if (!Number.isFinite(parsed)) return defaultWidth;
    return clamp(parsed, min, max);
  });

  const [isDragging, setIsDragging] = useState(false);
  const startX = useRef(0);
  const startWidth = useRef(0);

  const onMouseDown = useCallback(
    (e: ReactMouseEvent) => {
      e.preventDefault();
      startX.current = e.clientX;
      startWidth.current = width;
      setIsDragging(true);
      document.body.style.userSelect = 'none';
    },
    [width],
  );

  useEffect(() => {
    if (!isDragging) return;
    const onMove = (e: MouseEvent) => {
      const delta = e.clientX - startX.current;
      setWidth(clamp(startWidth.current + delta, min, max));
    };
    const onUp = () => {
      setIsDragging(false);
      document.body.style.userSelect = '';
    };
    document.addEventListener('mousemove', onMove);
    document.addEventListener('mouseup', onUp);
    return () => {
      document.removeEventListener('mousemove', onMove);
      document.removeEventListener('mouseup', onUp);
    };
  }, [isDragging, min, max]);

  // Persist width.
  useEffect(() => {
    if (!storageKey || typeof window === 'undefined') return;
    window.localStorage.setItem(storageKey, String(width));
  }, [storageKey, width]);

  return {
    width,
    isDragging,
    handleProps: { onMouseDown },
  };
}

export default useDraggablePane;
