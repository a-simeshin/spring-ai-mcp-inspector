// Portions adapted from modelcontextprotocol/inspector (Apache-2.0)
// https://github.com/modelcontextprotocol/inspector

import { useState } from 'react';
import { Button } from './ui/Button';
import { Input } from './ui/Input';
import { Label } from './ui/Label';

export interface OAuthCallbackParams {
  code: string;
  state: string;
}

export interface OAuthCallbackProps {
  onSubmit: (params: OAuthCallbackParams) => void;
  disabled?: boolean;
}

/**
 * Parses a redirect URL into `code` + `state` query parameters.
 *
 * @returns the parsed params, or null when either field is missing.
 */
export function parseCallbackUrl(input: string): OAuthCallbackParams | null {
  try {
    const url = new URL(input);
    const code = url.searchParams.get('code');
    const state = url.searchParams.get('state');
    if (!code || !state) return null;
    return { code, state };
  } catch {
    return null;
  }
}

/** Dev convenience: paste-callback-URL helper that extracts code+state. */
export function OAuthCallback({ onSubmit, disabled }: OAuthCallbackProps) {
  const [raw, setRaw] = useState('');
  const [error, setError] = useState<string | null>(null);

  const handle = () => {
    const parsed = parseCallbackUrl(raw.trim());
    if (!parsed) {
      setError('URL must include `code` and `state` query parameters.');
      return;
    }
    setError(null);
    onSubmit(parsed);
  };

  return (
    <div className="space-y-2 rounded-md border border-border bg-muted p-3">
      <Label htmlFor="oauth-callback-url" className="text-xs uppercase tracking-wide text-muted-foreground">
        Paste callback URL
      </Label>
      <Input
        id="oauth-callback-url"
        value={raw}
        onChange={(e) => setRaw(e.target.value)}
        placeholder="https://example.com/callback?code=…&state=…"
      />
      {error && <p className="text-xs text-destructive">{error}</p>}
      <Button type="button" size="sm" variant="primary" disabled={disabled} onClick={handle}>
        Submit code
      </Button>
    </div>
  );
}

export default OAuthCallback;
