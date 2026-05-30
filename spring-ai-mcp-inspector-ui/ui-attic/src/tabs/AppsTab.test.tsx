import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import type { ReactNode } from 'react';
import { AppsTab } from './AppsTab';
import { ToastProvider } from '../components/ui/Toast';

function Harness({ children }: { children: ReactNode }) {
  return <ToastProvider>{children}</ToastProvider>;
}

describe('AppsTab', () => {
  it('renders empty state', () => {
    render(
      <Harness>
        <AppsTab />
      </Harness>,
    );

    expect(
      screen.getByText(/Connect to an MCP server with apps capability/i),
    ).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: /^Apps$/i })).toBeInTheDocument();
  });
});
