import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { TasksTab } from './TasksTab';
import { ToastProvider } from '../components/ui/Toast';

vi.mock('../api/client', () => ({
  jsonRpc: vi.fn(),
}));

import { jsonRpc } from '../api/client';

function Harness({ children }: { children: ReactNode }) {
  return <ToastProvider>{children}</ToastProvider>;
}

describe('TasksTab', () => {
  beforeEach(() => {
    vi.mocked(jsonRpc).mockReset();
  });

  it('lists tasks', async () => {
    vi.mocked(jsonRpc).mockImplementation(async (method: string) => {
      if (method === 'tasks/list') {
        return {
          jsonrpc: '2.0',
          id: 1,
          result: {
            tasks: [
              { id: 't1', status: 'running', name: 'task one' },
              { id: 't2', status: 'completed', name: 'task two' },
            ],
          },
        } as unknown as Awaited<ReturnType<typeof jsonRpc>>;
      }
      throw new Error('unexpected method ' + method);
    });

    render(
      <Harness>
        <TasksTab />
      </Harness>,
    );

    await waitFor(() => {
      expect(screen.getByText('task one')).toBeInTheDocument();
    });
    expect(screen.getByText('task two')).toBeInTheDocument();
    expect(screen.getByText('running')).toBeInTheDocument();
    expect(screen.getByText('completed')).toBeInTheDocument();
  });
});
