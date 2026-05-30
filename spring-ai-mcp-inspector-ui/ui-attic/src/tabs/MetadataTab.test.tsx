import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MetadataTab } from './MetadataTab';
import { InspectorContext, useInspectorState } from '../state/store';
import type { ConfigDto } from '../api/client';

vi.mock('../api/client', () => {
  return {
    getConfig: vi.fn(),
  };
});

import { getConfig } from '../api/client';

function Harness() {
  const state = useInspectorState();
  return (
    <InspectorContext.Provider value={state}>
      <MetadataTab />
    </InspectorContext.Provider>
  );
}

describe('MetadataTab', () => {
  beforeEach(() => {
    vi.mocked(getConfig).mockReset();
  });

  it('shows detected transport and stack', async () => {
    const config: ConfigDto = {
      transport: 'SSE',
      stack: 'WEBMVC',
      endpoint: '/sse',
      messageEndpoint: '/mcp/message',
      serverName: 'demo',
      version: '0.1.0',
      authToken: 'x',
      capabilities: {},
    };
    vi.mocked(getConfig).mockResolvedValueOnce(config);

    render(<Harness />);

    await waitFor(() => {
      expect(screen.getByText(/SSE/)).toBeInTheDocument();
    });
    expect(screen.getByText(/WEBMVC/)).toBeInTheDocument();
    expect(screen.getByText('/sse')).toBeInTheDocument();
  });
});
