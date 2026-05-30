// Portions adapted from modelcontextprotocol/inspector (Apache-2.0)
// https://github.com/modelcontextprotocol/inspector

import { useState } from 'react';
import { Button } from '../components/ui/Button';
import { Input } from '../components/ui/Input';
import { Label } from '../components/ui/Label';
import { Checkbox } from '../components/ui/Checkbox';
import { OAuthFlowProgress, type OAuthStep } from '../components/OAuthFlowProgress';
import { OAuthCallback } from '../components/OAuthCallback';
import { oauthCallback, oauthInitiate } from '../api/client';
import { useToast } from '../lib/hooks/useToast';
import type { OAuthInitiateRequest, OAuthTokenResponse } from '../lib/types';

function maskToken(token: string): string {
  if (token.length <= 8) return '••••';
  return `${token.slice(0, 4)}…${token.slice(-4)}`;
}

/**
 * OAuth-debugger tab: walks the user through an OAuth 2.1 authorization-code
 * flow against the connected MCP server.
 */
export function AuthDebuggerTab() {
  const { toast } = useToast();
  const [step, setStep] = useState<OAuthStep>('idle');
  const [error, setError] = useState<string | null>(null);
  const [tokens, setTokens] = useState<OAuthTokenResponse | null>(null);

  const [form, setForm] = useState<OAuthInitiateRequest>({
    authorizationEndpoint: '',
    tokenEndpoint: '',
    clientId: '',
    redirectUri: typeof window !== 'undefined' ? `${window.location.origin}/oauth/callback` : '',
    scope: '',
    pkce: true,
  });
  const [authUrl, setAuthUrl] = useState<string | null>(null);
  const [stateToken, setStateToken] = useState<string | null>(null);

  const update = <K extends keyof OAuthInitiateRequest>(k: K, v: OAuthInitiateRequest[K]) =>
    setForm((prev) => ({ ...prev, [k]: v }));

  const handleStart = async () => {
    setError(null);
    setStep('configuring');
    if (!form.authorizationEndpoint || !form.tokenEndpoint || !form.clientId || !form.redirectUri) {
      setError('All endpoints, clientId, and redirectUri are required.');
      setStep('error');
      return;
    }
    try {
      const res = await oauthInitiate(form);
      setAuthUrl(res.authUrl);
      setStateToken(res.state);
      setStep('authorizing');
      window.open(res.authUrl, '_blank', 'noopener,noreferrer');
      setStep('awaiting_callback');
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      setError(msg);
      setStep('error');
      toast({ title: 'OAuth start failed', description: msg, variant: 'danger' });
    }
  };

  const handleCallback = async ({ code, state }: { code: string; state: string }) => {
    setError(null);
    setStep('exchanging');
    try {
      const result = await oauthCallback(code, state);
      setTokens(result);
      setStep('authorized');
      toast({ title: 'OAuth complete', description: 'Access token acquired.', variant: 'success' });
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      setError(msg);
      setStep('error');
      toast({ title: 'Token exchange failed', description: msg, variant: 'danger' });
    }
  };

  const reset = () => {
    setStep('idle');
    setError(null);
    setTokens(null);
    setAuthUrl(null);
    setStateToken(null);
  };

  const canEditForm = step === 'idle' || step === 'error';

  return (
    <section className="space-y-4">
      <header>
        <h2 className="text-xl font-semibold text-foreground">Auth Debugger</h2>
        <p className="text-sm text-muted-foreground">Drive an OAuth 2.1 authorization-code flow.</p>
      </header>

      <div className="space-y-3 rounded-md border border-border bg-background p-3">
        <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
          <div className="space-y-1">
            <Label htmlFor="auth-ep">Authorization endpoint</Label>
            <Input
              id="auth-ep"
              value={form.authorizationEndpoint}
              onChange={(e) => update('authorizationEndpoint', e.target.value)}
              placeholder="https://example.com/oauth/authorize"
              disabled={!canEditForm}
            />
          </div>
          <div className="space-y-1">
            <Label htmlFor="token-ep">Token endpoint</Label>
            <Input
              id="token-ep"
              value={form.tokenEndpoint}
              onChange={(e) => update('tokenEndpoint', e.target.value)}
              placeholder="https://example.com/oauth/token"
              disabled={!canEditForm}
            />
          </div>
          <div className="space-y-1">
            <Label htmlFor="client-id">Client ID</Label>
            <Input
              id="client-id"
              value={form.clientId}
              onChange={(e) => update('clientId', e.target.value)}
              disabled={!canEditForm}
            />
          </div>
          <div className="space-y-1">
            <Label htmlFor="redirect-uri">Redirect URI</Label>
            <Input
              id="redirect-uri"
              value={form.redirectUri}
              onChange={(e) => update('redirectUri', e.target.value)}
              disabled={!canEditForm}
            />
          </div>
          <div className="space-y-1">
            <Label htmlFor="scope">Scope</Label>
            <Input
              id="scope"
              value={form.scope ?? ''}
              onChange={(e) => update('scope', e.target.value)}
              placeholder="openid profile"
              disabled={!canEditForm}
            />
          </div>
          <div className="flex items-end gap-2">
            <Checkbox
              id="pkce"
              checked={form.pkce ?? false}
              onCheckedChange={(v) => update('pkce', v)}
              disabled={!canEditForm}
            />
            <Label htmlFor="pkce">Use PKCE</Label>
          </div>
        </div>

        <div className="flex gap-2">
          <Button type="button" variant="primary" size="sm" onClick={handleStart} disabled={!canEditForm}>
            Start OAuth Flow
          </Button>
          {step !== 'idle' && (
            <Button type="button" variant="ghost" size="sm" onClick={reset}>
              Reset
            </Button>
          )}
        </div>
      </div>

      <OAuthFlowProgress step={step} error={error} />

      {authUrl && step === 'awaiting_callback' && (
        <div className="space-y-2">
          <p className="text-xs text-muted-foreground">
            If the new window didn't open, click{' '}
            <a
              href={authUrl}
              target="_blank"
              rel="noopener noreferrer"
              className="text-primary underline"
            >
              this link
            </a>{' '}
            to authorize.
          </p>
          {stateToken && <p className="font-mono text-xs text-muted-foreground">state: {stateToken}</p>}
          <OAuthCallback onSubmit={handleCallback} disabled={step !== 'awaiting_callback'} />
        </div>
      )}

      {tokens && step === 'authorized' && (
        <div className="space-y-1 rounded-md border border-emerald-300 bg-emerald-50 p-3 text-sm dark:border-emerald-700 dark:bg-emerald-900/30">
          <p className="font-medium">Access token acquired</p>
          <p className="font-mono text-xs">{maskToken(tokens.accessToken)}</p>
          <p className="text-xs text-muted-foreground">
            type: {tokens.tokenType}
            {tokens.expiresIn !== undefined && ` · expires in ${tokens.expiresIn}s`}
            {tokens.scope && ` · scope: ${tokens.scope}`}
          </p>
        </div>
      )}
    </section>
  );
}

export default AuthDebuggerTab;
