import { describe, it, expect, vi } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import type { ReactNode } from 'react';
import { ElicitationTab } from './ElicitationTab';
import { ConnectionProvider } from '../state/connection';
import { ToastProvider } from '../components/ui/Toast';

vi.mock('../api/client', () => ({
  respondToServerRequest: vi.fn(async () => undefined),
  connectExternal: vi.fn(),
  disconnectSession: vi.fn(),
}));

vi.mock('../api/sse', () => ({
  subscribeEvents: (handlers: {
    onElicitationRequest?: (e: { requestId: string; params: unknown }) => void;
  }) => {
    setTimeout(() => {
      handlers.onElicitationRequest?.({
        requestId: 'elic-1',
        params: {
          message: 'Please provide your name',
          requestedSchema: {
            type: 'object',
            properties: { name: { type: 'string', title: 'name' } },
            required: ['name'],
          },
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

describe('ElicitationTab', () => {
  it('submits form value', async () => {
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

    const { respondToServerRequest } = await import('../api/client');

    render(
      <Harness>
        <ElicitationTab />
      </Harness>,
    );

    await waitFor(() => {
      expect(screen.getByTestId('elicitation-request')).toBeInTheDocument();
    });

    const input = await screen.findByRole('textbox');
    fireEvent.change(input, { target: { value: 'Alice' } });

    fireEvent.click(screen.getByRole('button', { name: /submit/i }));

    await waitFor(() => {
      expect(vi.mocked(respondToServerRequest)).toHaveBeenCalled();
    });
    const call = vi.mocked(respondToServerRequest).mock.calls[0];
    expect(call[0]).toBe('sess-1');
    expect(call[1]).toBe('elic-1');
    expect(call[2]).toEqual({ result: { action: 'accept', content: { name: 'Alice' } } });
  });
});
