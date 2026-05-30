// Portions adapted from modelcontextprotocol/inspector (Apache-2.0)
// https://github.com/modelcontextprotocol/inspector

import type { ReactNode } from 'react';
import clsx from 'clsx';

export interface ListPaneProps<T> {
  items: ReadonlyArray<T>;
  renderItem: (item: T) => ReactNode;
  /** Key derivation for list items (defaults to JSON.stringify). */
  itemKey?: (item: T) => string;
  selected?: T;
  onSelect?: (item: T) => void;
  isSelected?: (item: T) => boolean;
  loading?: boolean;
  empty?: ReactNode;
  className?: string;
}

function defaultKey(v: unknown): string {
  try {
    return JSON.stringify(v);
  } catch {
    return String(v);
  }
}

/**
 * Generic scrollable list with selection highlight.
 *
 * Use `isSelected` for callers whose items are not referentially stable
 * across re-renders.
 */
export function ListPane<T>({
  items,
  renderItem,
  itemKey = defaultKey,
  selected,
  onSelect,
  isSelected,
  loading,
  empty,
  className,
}: ListPaneProps<T>) {
  if (loading) {
    return (
      <div className={clsx('rounded-md border border-border bg-background p-3 text-sm text-muted-foreground', className)}>
        Loading…
      </div>
    );
  }
  if (items.length === 0) {
    return (
      <div className={clsx('rounded-md border border-border bg-background p-3 text-sm italic text-muted-foreground', className)}>
        {empty ?? 'No items.'}
      </div>
    );
  }
  return (
    <ul
      className={clsx(
        'flex max-h-full flex-col gap-0.5 overflow-y-auto rounded-md border border-border bg-background p-1',
        className,
      )}
      role="listbox"
    >
      {items.map((item) => {
        const sel = isSelected ? isSelected(item) : item === selected;
        return (
          <li key={itemKey(item)}>
            <button
              type="button"
              onClick={() => onSelect?.(item)}
              role="option"
              aria-selected={sel}
              className={clsx(
                'w-full rounded-md px-2.5 py-1.5 text-left text-sm transition-colors',
                'border-l-2',
                sel ? 'border-primary bg-accent text-accent-foreground' : 'border-transparent text-muted-foreground hover:bg-accent hover:text-accent-foreground',
              )}
            >
              {renderItem(item)}
            </button>
          </li>
        );
      })}
    </ul>
  );
}

export default ListPane;
