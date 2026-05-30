import { describe, it, expect, vi } from 'vitest';
import { useState } from 'react';
import { render, screen, fireEvent } from '@testing-library/react';
import { DynamicJsonForm, validateAgainstSchema } from '../DynamicJsonForm';
import type { JsonSchema } from '../../utils/schemaUtils';

function Harness({ schema, initial = {} }: { schema: JsonSchema; initial?: unknown }) {
  const [value, setValue] = useState<unknown>(initial);
  const issues = validateAgainstSchema(schema, value) ?? undefined;
  return (
    <DynamicJsonForm schema={schema} value={value} onChange={setValue} errors={issues} />
  );
}

describe('DynamicJsonForm', () => {
  it('renders nested object schema', () => {
    const schema: JsonSchema = {
      type: 'object',
      properties: {
        outer: {
          type: 'object',
          properties: {
            inner: { type: 'string', title: 'inner' },
          },
        },
      },
    };
    render(<Harness schema={schema} />);
    expect(screen.getByText(/outer/i)).toBeInTheDocument();
    expect(screen.getByText(/inner/i)).toBeInTheDocument();
  });

  it('validates required field', () => {
    const schema: JsonSchema = {
      type: 'object',
      required: ['name'],
      properties: { name: { type: 'string' } },
    };
    // Initial empty value should yield AJV "required" error from Harness wrapper.
    render(<Harness schema={schema} initial={{}} />);
    // AJV error message for required missing property.
    expect(screen.getByText(/required property/i)).toBeInTheDocument();
  });

  it('handles array of strings via +Add', () => {
    const schema: JsonSchema = {
      type: 'object',
      properties: { tags: { type: 'array', items: { type: 'string' }, title: 'tags' } },
    };
    const onChange = vi.fn();
    function Wrapper() {
      const [v, setV] = useState<unknown>({ tags: [] });
      return (
        <DynamicJsonForm
          schema={schema}
          value={v}
          onChange={(next) => {
            onChange(next);
            setV(next);
          }}
        />
      );
    }
    render(<Wrapper />);
    const addBtn = screen.getByRole('button', { name: /add/i });
    fireEvent.click(addBtn);
    // After +Add, a textbox for the new item should appear.
    expect(screen.getAllByRole('textbox').length).toBeGreaterThanOrEqual(1);
    expect(onChange).toHaveBeenCalled();
  });
});
