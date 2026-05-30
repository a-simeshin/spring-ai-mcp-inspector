import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { ResourcesTab } from './ResourcesTab';

vi.mock('../api/client', () => {
  return {
    jsonRpc: vi.fn(),
  };
});

import { jsonRpc } from '../api/client';

describe('ResourcesTab', () => {
  beforeEach(() => {
    vi.mocked(jsonRpc).mockReset();
  });

  it('reads selected resource', async () => {
    vi.mocked(jsonRpc).mockImplementation(async (method: string) => {
      if (method === 'resources/list') {
        return {
          jsonrpc: '2.0',
          id: 1,
          result: {
            resources: [
              {
                uri: 'file:///hello.txt',
                name: 'hello',
                mimeType: 'text/plain',
              },
            ],
          },
        } as unknown as Awaited<ReturnType<typeof jsonRpc>>;
      }
      if (method === 'resources/read') {
        return {
          jsonrpc: '2.0',
          id: 2,
          result: {
            contents: [
              {
                uri: 'file:///hello.txt',
                mimeType: 'text/plain',
                text: 'Hello, world!',
              },
            ],
          },
        } as unknown as Awaited<ReturnType<typeof jsonRpc>>;
      }
      throw new Error('unexpected method ' + method);
    });

    render(<ResourcesTab />);

    await waitFor(() => {
      expect(screen.getByText('hello')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByText('hello'));

    const readBtn = await screen.findByRole('button', { name: /^read$/i });
    fireEvent.click(readBtn);

    await waitFor(() => {
      expect(screen.getByText(/Hello, world!/)).toBeInTheDocument();
    });
  });
});
