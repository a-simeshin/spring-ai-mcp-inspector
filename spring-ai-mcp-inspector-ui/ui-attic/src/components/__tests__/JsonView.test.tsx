import { describe, it, expect } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { JsonView } from '../JsonView';

describe('JsonView', () => {
  it('expands nested object on click', () => {
    render(<JsonView value={{ a: { b: 42 } }} defaultExpandedDepth={0} />);
    // Initially collapsed at the root — child key `a` is not yet visible.
    expect(screen.queryByText(/^a:$/)).toBeNull();
    // Click root toggle.
    const buttons = screen.getAllByRole('button');
    fireEvent.click(buttons[0]);
    // Now nested key `a:` should be visible.
    expect(screen.getByText(/^a:$/)).toBeInTheDocument();
    // Click `a` button to expand its children.
    const expandedButtons = screen.getAllByRole('button');
    fireEvent.click(expandedButtons[1]);
    // Now the leaf value 42 must be visible.
    expect(screen.getByText('42')).toBeInTheDocument();
  });
});
