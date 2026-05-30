// Portions adapted from modelcontextprotocol/inspector (Apache-2.0)
// https://github.com/modelcontextprotocol/inspector

import { useState, type ReactNode } from 'react';
import { ChevronRight, ChevronDown } from 'lucide-react';
import clsx from 'clsx';
import { isPlainObject } from '../utils';

export interface JsonViewProps {
  value: unknown;
  defaultExpandedDepth?: number;
}

interface NodeProps {
  value: unknown;
  depth: number;
  defaultExpandedDepth: number;
  label?: string;
}

function primitiveClass(value: unknown): string {
  if (value === null) return 'text-muted-foreground';
  switch (typeof value) {
    case 'string':
      return 'text-emerald-600 dark:text-emerald-400';
    case 'number':
    case 'bigint':
      return 'text-blue-600 dark:text-blue-400';
    case 'boolean':
      return 'text-purple-600 dark:text-purple-400';
    default:
      return 'text-foreground';
  }
}

function renderPrimitive(value: unknown): ReactNode {
  if (value === null) return <span className="text-muted-foreground">null</span>;
  if (typeof value === 'string') {
    return <span className={primitiveClass(value)}>"{value}"</span>;
  }
  return <span className={primitiveClass(value)}>{String(value)}</span>;
}

function Node({ value, depth, defaultExpandedDepth, label }: NodeProps) {
  const isObj = isPlainObject(value);
  const isArr = Array.isArray(value);
  const [open, setOpen] = useState(depth < defaultExpandedDepth);

  const indentPx = depth * 12;

  if (!isObj && !isArr) {
    return (
      <div className="font-mono text-xs leading-6" style={{ paddingLeft: indentPx }}>
        {label !== undefined && <span className="text-muted-foreground">{label}: </span>}
        {renderPrimitive(value)}
      </div>
    );
  }

  const entries: Array<[string, unknown]> = isArr
    ? (value as unknown[]).map((v, i) => [String(i), v])
    : Object.entries(value as Record<string, unknown>);

  const openBracket = isArr ? '[' : '{';
  const closeBracket = isArr ? ']' : '}';

  return (
    <div className="font-mono text-xs leading-6" style={{ paddingLeft: indentPx }}>
      <button
        type="button"
        onClick={() => setOpen((o) => !o)}
        className={clsx('inline-flex items-center gap-1 text-muted-foreground hover:text-foreground', 'select-none')}
        aria-expanded={open}
      >
        {open ? <ChevronDown size={12} /> : <ChevronRight size={12} />}
        {label !== undefined && <span>{label}:</span>}
        <span>
          {openBracket}
          {!open && <span className="ml-1 text-muted-foreground">{entries.length}</span>}
          {!open && closeBracket}
        </span>
      </button>
      {open && (
        <div>
          {entries.map(([k, v]) => (
            <Node
              key={k}
              value={v}
              depth={depth + 1}
              defaultExpandedDepth={defaultExpandedDepth}
              label={isArr ? undefined : k}
            />
          ))}
          <div style={{ paddingLeft: indentPx }} className="text-muted-foreground">
            {closeBracket}
          </div>
        </div>
      )}
    </div>
  );
}

/** Collapsible JSON tree view. */
export function JsonView({ value, defaultExpandedDepth = 2 }: JsonViewProps) {
  return <Node value={value} depth={0} defaultExpandedDepth={defaultExpandedDepth} />;
}

export default JsonView;
