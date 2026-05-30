// Portions adapted from modelcontextprotocol/inspector (Apache-2.0)
// https://github.com/modelcontextprotocol/inspector

/**
 * Parameter coercion helpers for tool/prompt argument forms.
 *
 * The form layer always stores raw strings; these utilities convert them to
 * the typed values required by the underlying JSON schema before submission.
 */

/**
 * Converts a raw string entered in a form into a typed JSON value.
 *
 * Empty input yields `undefined` (so the field can be omitted entirely). For
 * numeric types, parse failures fall back to returning the raw string so the
 * AJV validator can surface a clear error.
 */
export function coerceValue(raw: string, type: string): unknown {
  if (raw === '') return undefined;
  switch (type) {
    case 'integer': {
      const parsed = Number.parseInt(raw, 10);
      return Number.isNaN(parsed) ? raw : parsed;
    }
    case 'number': {
      const parsed = Number(raw);
      return Number.isNaN(parsed) ? raw : parsed;
    }
    case 'boolean':
      return raw === 'true';
    case 'null':
      return null;
    default:
      return raw;
  }
}

/**
 * Splits a CLI-style argument string into individual tokens. Supports simple
 * double-quoted segments so values containing whitespace survive a round-trip.
 */
export function parseArgList(text: string): string[] {
  if (!text || !text.trim()) return [];
  const out: string[] = [];
  const re = /"([^"]*)"|(\S+)/g;
  let match: RegExpExecArray | null;
  while ((match = re.exec(text)) !== null) {
    out.push(match[1] ?? match[2] ?? '');
  }
  return out;
}

/**
 * Joins argument tokens back into a single CLI-style string. Tokens containing
 * whitespace are wrapped in double quotes to round-trip with `parseArgList`.
 */
export function formatArgList(args: ReadonlyArray<string>): string {
  return args
    .map((a) => (/\s/.test(a) ? `"${a}"` : a))
    .join(' ');
}
