import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { ToolsTab } from './ToolsTab';

vi.mock('../api/client', () => {
  return {
    jsonRpc: vi.fn(),
  };
});

import { jsonRpc } from '../api/client';

describe('ToolsTab', () => {
  beforeEach(() => {
    vi.mocked(jsonRpc).mockReset();
  });

  it('shows DynamicJsonForm for selected tool', async () => {
    vi.mocked(jsonRpc).mockImplementation(async (method: string) => {
      if (method === 'tools/list') {
        return {
          jsonrpc: '2.0',
          id: 1,
          result: {
            tools: [
              {
                name: 'echo',
                description: 'echo input back',
                inputSchema: {
                  type: 'object',
                  properties: { text: { type: 'string', title: 'text' } },
                  required: ['text'],
                },
              },
            ],
          },
        } as unknown as Awaited<ReturnType<typeof jsonRpc>>;
      }
      if (method === 'tools/call') {
        return {
          jsonrpc: '2.0',
          id: 2,
          result: { content: [{ type: 'text', text: 'ok' }] },
        } as unknown as Awaited<ReturnType<typeof jsonRpc>>;
      }
      throw new Error('unexpected method ' + method);
    });

    render(<ToolsTab />);

    // Wait for the tool list to render.
    await waitFor(() => {
      expect(screen.getByText('echo')).toBeInTheDocument();
    });

    // Selecting the tool should mount the DynamicJsonForm.
    fireEvent.click(screen.getByText('echo'));

    // The schema field "text" must be rendered (label text title).
    await waitFor(() => {
      const labels = screen.getAllByText(/text/);
      expect(labels.length).toBeGreaterThan(0);
    });

    // The form must surface the Call tool button.
    expect(screen.getByRole('button', { name: /call tool/i })).toBeInTheDocument();

    // Filling and submitting should issue tools/call with structured args.
    const inputs = await screen.findAllByRole('textbox');
    fireEvent.change(inputs[0], { target: { value: 'hello' } });
    fireEvent.click(screen.getByRole('button', { name: /call tool/i }));

    await waitFor(() => {
      const calls = vi.mocked(jsonRpc).mock.calls;
      const last = calls[calls.length - 1];
      expect(last[0]).toBe('tools/call');
      expect(last[1]).toEqual({ name: 'echo', arguments: { text: 'hello' } });
    });
  });
});
