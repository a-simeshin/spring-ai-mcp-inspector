import { describe, it, expect } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { Sidebar } from '../Sidebar';
import { ConnectionProvider } from '../../state/connection';
import { InspectorContext } from '../../state/store';
import { ToastProvider } from '../ui/Toast';
import type { ConfigDto } from '../../api/client';

function renderSidebar(initialTransport: 'stdio' | 'sse' | 'streamable-http') {
  const inspectorConfig: ConfigDto = {
    transport: 'UNKNOWN',
    endpoint: null,
    stack: 'NONE',
    serverName: 'test',
    version: '0.0.0',
    authToken: 'test-token',
    capabilities: {},
  };
  return render(
    <InspectorContext.Provider
      value={{
        config: inspectorConfig,
        configError: null,
        configLoading: false,
        refreshConfig: () => {},
      }}
    >
      <ToastProvider>
        <ConnectionProvider initialConfig={{ transport: initialTransport }}>
          <Sidebar />
        </ConnectionProvider>
      </ToastProvider>
    </InspectorContext.Provider>,
  );
}

describe('Sidebar', () => {
  it('switches transport', () => {
    renderSidebar('stdio');

    // STDIO shows command, hides URL.
    expect(screen.getByLabelText('Command')).toBeInTheDocument();
    expect(screen.queryByLabelText('URL')).not.toBeInTheDocument();

    // Open transport select (the trigger labelled "Select transport").
    const transportTrigger = screen.getByRole('button', { name: /Select transport/ });
    fireEvent.click(transportTrigger);

    // Pick SSE.
    const sseOption = screen.getByRole('option', { name: 'SSE' });
    fireEvent.click(sseOption);

    // Now URL visible, Command hidden.
    expect(screen.getByLabelText('URL')).toBeInTheDocument();
    expect(screen.queryByLabelText('Command')).not.toBeInTheDocument();
  });
});
