// Portions adapted from modelcontextprotocol/inspector (Apache-2.0)
// https://github.com/modelcontextprotocol/inspector

import { CheckCircle2, Circle, Loader2 } from 'lucide-react';
import clsx from 'clsx';

export type OAuthStep =
  | 'idle'
  | 'configuring'
  | 'authorizing'
  | 'awaiting_callback'
  | 'exchanging'
  | 'authorized'
  | 'error';

export interface OAuthFlowProgressProps {
  step: OAuthStep;
  error?: string | null;
}

const ORDERED: ReadonlyArray<{ key: OAuthStep; label: string }> = [
  { key: 'configuring', label: 'Configure OAuth client' },
  { key: 'authorizing', label: 'Open authorization URL' },
  { key: 'awaiting_callback', label: 'Awaiting authorization code' },
  { key: 'exchanging', label: 'Exchanging code for token' },
  { key: 'authorized', label: 'Authorized' },
];

function rank(step: OAuthStep): number {
  const idx = ORDERED.findIndex((s) => s.key === step);
  return idx < 0 ? -1 : idx;
}

/** Vertical step-by-step OAuth flow indicator with current state highlight. */
export function OAuthFlowProgress({ step, error }: OAuthFlowProgressProps) {
  const currentRank = rank(step);
  return (
    <ol className="space-y-2">
      {ORDERED.map((entry, i) => {
        const isCurrent = entry.key === step;
        const isComplete = currentRank > i || step === 'authorized';
        return (
          <li
            key={entry.key}
            className={clsx(
              'flex items-center gap-2 rounded-md px-2 py-1.5 text-sm',
              isCurrent && 'bg-muted font-medium',
            )}
          >
            {isComplete ? (
              <CheckCircle2 size={16} className="text-emerald-600 dark:text-emerald-400" />
            ) : isCurrent && step !== 'error' ? (
              <Loader2 size={16} className="animate-spin text-primary" />
            ) : (
              <Circle size={16} className="text-muted-foreground" />
            )}
            <span className={isCurrent ? 'text-foreground' : 'text-muted-foreground'}>{entry.label}</span>
          </li>
        );
      })}
      {step === 'error' && error && (
        <li className="rounded-md border border-destructive/30 bg-destructive/10 p-2 text-xs text-destructive">
          {error}
        </li>
      )}
    </ol>
  );
}

export default OAuthFlowProgress;
