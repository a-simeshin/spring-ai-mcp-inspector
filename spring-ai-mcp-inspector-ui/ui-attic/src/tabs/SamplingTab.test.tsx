import { describe, it, expect, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { SamplingTab } from './SamplingTab';
import { ConnectionProvider } from '../state/connection';
import { ToastProvider } from '../components/ui/Toast';

vi.mock('../api/client', () => ({
  respondToServerRequest: vi.fn(),
  connectExternal: vi.fn(),
  disconnectSession: vi.fn(),
}));

// Provide a fake subscribeEvents that immediately delivers a sampling request.
vi.mock('../api/sse', () => ({
  subscribeEvents: (handlers: {
    onSamplingRequest?: (e: { requestId: string; params: unknown }) => void;
  }) => {
    setTimeout(() => {
      handlers.onSamplingRequest?.({
        requestId: 'req-1',
        params: {
          messages: [{ role: 'user', content: { type: 'text', text: 'Hello world from the server' } }],
        },
      });
    }, 0);
    return { close: () => undefined };
  },
}));

function Harness({ children }: { children: ReactNode }) {
  return (
    <ToastProvider>
      <ConnectionProvider initialConfig={{ transport: 'stdio' }}>{children}</ConnectionProvider>
    </ToastProvider>
  );
}

describe('SamplingTab', () => {
  it('displays incoming sampling request', async () => {
    // The ConnectionProvider starts with sessionId=null, so the tab will say
    // "Not connected". We bypass that by mocking useConnection? Simpler: set
    // the sessionId by triggering the provider via mock. Instead we mount and
    // assert the placeholder still shows messages once connected via state.
    // For simplicity here, we mock useConnection.
    const mod = await import('../state/connection');
    vi.spyOn(mod, 'useConnection').mockReturnValue({
      sessionId: 'sess-1',
      status: 'connected',
      config: { transport: 'stdio' },
      setConfig: vi.fn(),
      setTransport: vi.fn(),
      connect: vi.fn(),
      disconnect: vi.fn(),
    });

    render(
      <Harness>
        <SamplingTab />
      </Harness>,
    );

    await waitFor(() => {
      expect(screen.getByTestId('sampling-request')).toBeInTheDocument();
    });
    expect(screen.getByText(/#req-1/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /approve/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /reject/i })).toBeInTheDocument();
  });
});
