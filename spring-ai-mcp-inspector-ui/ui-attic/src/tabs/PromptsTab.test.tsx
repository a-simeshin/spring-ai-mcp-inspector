import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { PromptsTab } from './PromptsTab';

vi.mock('../api/client', () => {
  return {
    jsonRpc: vi.fn(),
  };
});

import { jsonRpc } from '../api/client';

describe('PromptsTab', () => {
  beforeEach(() => {
    vi.mocked(jsonRpc).mockReset();
  });

  it('substitutes argument into template preview', async () => {
    vi.mocked(jsonRpc).mockImplementation(async (method: string) => {
      if (method === 'prompts/list') {
        return {
          jsonrpc: '2.0',
          id: 1,
          result: {
            prompts: [
              {
                name: 'greeting',
                description: 'greets the user',
                arguments: [{ name: 'who', required: true }],
              },
            ],
          },
        } as any;
      }
      if (method === 'prompts/get') {
        return {
          jsonrpc: '2.0',
          id: 2,
          result: {
            messages: [
              {
                role: 'user',
                content: { type: 'text', text: 'Hello, World!' },
              },
            ],
          },
        } as any;
      }
      throw new Error('unexpected method: ' + method);
    });

    render(<PromptsTab />);

    await waitFor(() => {
      expect(screen.getByText('greeting')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByText('greeting'));

    const inputs = await screen.findAllByRole('textbox');
    fireEvent.change(inputs[0], { target: { value: 'World' } });

    fireEvent.click(screen.getByRole('button', { name: /render prompt/i }));

    await waitFor(() => {
      expect(screen.getByText('Hello, World!')).toBeInTheDocument();
    });
  });
});
