import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import type { ReactNode } from 'react';
import { RootsTab } from './RootsTab';
import { ConnectionProvider } from '../state/connection';
import { ToastProvider } from '../components/ui/Toast';

vi.mock('../api/client', () => ({
  getRoots: vi.fn(async () => ({ roots: [] })),
  putRoots: vi.fn(async () => undefined),
  connectExternal: vi.fn(),
  disconnectSession: vi.fn(),
}));

vi.mock('../api/sse', () => ({
  subscribeEvents: () => ({ close: () => undefined }),
}));

function Harness({ children }: { children: ReactNode }) {
  return (
    <ToastProvider>
      <ConnectionProvider initialConfig={{ transport: 'stdio' }}>{children}</ConnectionProvider>
    </ToastProvider>
  );
}

describe('RootsTab', () => {
  beforeEach(async () => {
    const mod = await import('../state/connection');
    vi.spyOn(mod, 'useConnection').mockReturnValue({
      sessionId: 'sess1',
      status: 'connected',
      config: { transport: 'stdio' },
      setConfig: vi.fn(),
      setTransport: vi.fn(),
      connect: vi.fn(),
      disconnect: vi.fn(),
    });
  });

  it('adds and removes root', async () => {
    const { putRoots } = await import('../api/client');

    render(
      <Harness>
        <RootsTab />
      </Harness>,
    );

    // Wait for "No roots configured." to appear (initial load finished).
    await waitFor(() => {
      expect(screen.getByText(/No roots configured/i)).toBeInTheDocument();
    });

    // Type URI + name and click Add.
    const inputs = screen.getAllByRole('textbox');
    fireEvent.change(inputs[0], { target: { value: 'file:///tmp' } });
    fireEvent.change(inputs[1], { target: { value: 'tmp' } });

    fireEvent.click(screen.getByRole('button', { name: /add root/i }));

    await waitFor(() => {
      expect(vi.mocked(putRoots)).toHaveBeenCalled();
    });

    const addCall = vi.mocked(putRoots).mock.calls[0];
    expect(addCall[0]).toBe('sess1');
    expect(addCall[1]).toEqual([{ uri: 'file:///tmp', name: 'tmp' }]);

    // The new row should be visible — click its Remove button.
    await waitFor(() => {
      expect(screen.getByText('file:///tmp')).toBeInTheDocument();
    });

    vi.mocked(putRoots).mockClear();

    const removeBtn = screen.getByRole('button', { name: /remove root file:\/\/\/tmp/i });
    fireEvent.click(removeBtn);

    await waitFor(() => {
      expect(vi.mocked(putRoots)).toHaveBeenCalled();
    });

    const removeCall = vi.mocked(putRoots).mock.calls[0];
    expect(removeCall[0]).toBe('sess1');
    expect(removeCall[1]).toEqual([]);
  });
});
