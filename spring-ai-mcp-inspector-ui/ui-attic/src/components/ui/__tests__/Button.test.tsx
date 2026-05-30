import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { Button } from '../Button';

describe('Button', () => {
  it('renders primary variant with primary background', () => {
    render(<Button variant="primary">Click</Button>);
    const btn = screen.getByRole('button', { name: /click/i });
    expect(btn.className).toMatch(/bg-primary/);
  });

  it('renders default variant with primary background (shadcn default)', () => {
    render(<Button>Default</Button>);
    const btn = screen.getByRole('button', { name: /default/i });
    expect(btn.className).toMatch(/bg-primary/);
  });

  it('renders outline variant with border class', () => {
    render(<Button variant="outline">Outline</Button>);
    const btn = screen.getByRole('button', { name: /outline/i });
    expect(btn.className).toMatch(/border-input/);
  });
});
