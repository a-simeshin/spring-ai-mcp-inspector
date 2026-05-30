import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import type { ReactNode } from 'react';
import { AuthDebuggerTab } from './AuthDebuggerTab';
import { ToastProvider } from '../components/ui/Toast';

vi.mock('../api/client', () => ({
  oauthInitiate: vi.fn(),
  oauthCallback: vi.fn(),
}));

function Harness({ children }: { children: ReactNode }) {
  return <ToastProvider>{children}</ToastProvider>;
}

describe('AuthDebuggerTab', () => {
  it('shows initial state', () => {
    render(
      <Harness>
        <AuthDebuggerTab />
      </Harness>,
    );

    // Initial step is "idle" — the Start OAuth Flow button must be visible and enabled.
    const startBtn = screen.getByRole('button', { name: /start oauth flow/i });
    expect(startBtn).toBeInTheDocument();
    expect(startBtn).not.toBeDisabled();

    // Reset button should NOT be visible in idle state.
    expect(screen.queryByRole('button', { name: /^reset$/i })).not.toBeInTheDocument();

    // The OAuth flow progress should expose the configure step label.
    expect(screen.getByText(/configure oauth client/i)).toBeInTheDocument();
  });
});
