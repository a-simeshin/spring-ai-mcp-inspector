// Portions adapted from modelcontextprotocol/inspector (Apache-2.0)
// https://github.com/modelcontextprotocol/inspector

import { KvAdder } from './KvAdder';

export interface CustomHeadersProps {
  headers: Record<string, string>;
  onChange: (next: Record<string, string>) => void;
  label?: string;
}

/**
 * Thin wrapper around `KvAdder` for HTTP transport header management.
 *
 * Kept as a named component so tab code can import it from a stable path
 * and so we can extend it (e.g. with redaction) without changing call sites.
 */
export function CustomHeaders({ headers, onChange, label = 'Headers' }: CustomHeadersProps) {
  return (
    <KvAdder
      label={label}
      existing={headers}
      onChange={onChange}
      addAriaLabel="Add header"
    />
  );
}

export default CustomHeaders;
