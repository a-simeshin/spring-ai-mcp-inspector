// Portions adapted from modelcontextprotocol/inspector (Apache-2.0)
// https://github.com/modelcontextprotocol/inspector

import { useMemo } from 'react';
import Ajv, { type ErrorObject } from 'ajv';
import { Plus, X } from 'lucide-react';
import clsx from 'clsx';
import { Input } from './ui/Input';
import { Checkbox } from './ui/Checkbox';
import { Label } from './ui/Label';
import { Button } from './ui/Button';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from './ui/Select';
import type { JsonSchema } from '../utils/schemaUtils';
import { getSchemaDefaults } from '../utils/schemaUtils';
import { isPlainObject } from '../utils/jsonUtils';

const ajvInstance = new Ajv({ allErrors: true, strict: false });

export interface DynamicJsonFormProps {
  schema: JsonSchema;
  value: unknown;
  onChange: (next: unknown) => void;
  /** Pre-computed validation errors from a parent form submit. */
  errors?: ErrorObject[];
}

function errorsAt(errors: ErrorObject[] | undefined, path: string): string[] {
  if (!errors) return [];
  return errors
    .filter((err) => (err.instancePath || '') === path || err.instancePath === path + '/')
    .map((err) => err.message ?? 'invalid');
}

interface FieldProps {
  schema: JsonSchema;
  value: unknown;
  onChange: (next: unknown) => void;
  path: string;
  errors?: ErrorObject[];
  required?: boolean;
  label?: string;
}

function typeOf(schema: JsonSchema | undefined): string {
  if (!schema) return 'string';
  const t = Array.isArray(schema.type) ? schema.type[0] : schema.type;
  return t ?? (schema.enum ? 'string' : schema.properties ? 'object' : schema.items ? 'array' : 'string');
}

function StringField({ schema, value, onChange }: FieldProps) {
  if (Array.isArray(schema.enum)) {
    const current = value === undefined || value === null ? '' : String(value);
    return (
      <Select value={current || undefined} onValueChange={(v) => onChange(v)}>
        <SelectTrigger className="w-full">
          <SelectValue placeholder="Select…" />
        </SelectTrigger>
        <SelectContent>
          {schema.enum.map((opt, idx) => {
            const label = schema.enumNames?.[idx] ?? String(opt);
            return (
              <SelectItem key={String(opt)} value={String(opt)}>
                {label}
              </SelectItem>
            );
          })}
        </SelectContent>
      </Select>
    );
  }
  return (
    <Input
      value={value === undefined || value === null ? '' : String(value)}
      onChange={(e) => onChange(e.target.value === '' ? undefined : e.target.value)}
      placeholder={schema.description}
    />
  );
}

function NumberField({ schema, value, onChange }: FieldProps) {
  return (
    <Input
      type="number"
      value={value === undefined || value === null ? '' : String(value)}
      onChange={(e) => {
        const raw = e.target.value;
        if (raw === '') {
          onChange(undefined);
          return;
        }
        const n = typeOf(schema) === 'integer' ? Number.parseInt(raw, 10) : Number(raw);
        onChange(Number.isNaN(n) ? raw : n);
      }}
    />
  );
}

function BooleanField({ value, onChange }: FieldProps) {
  return <Checkbox checked={!!value} onCheckedChange={(v) => onChange(v)} />;
}

function ArrayField({ schema, value, onChange, path, errors }: FieldProps) {
  const arr = Array.isArray(value) ? value : [];
  const itemSchema = schema.items ?? { type: 'string' };
  return (
    <div className="space-y-1">
      {arr.map((item, idx) => (
        <div key={idx} className="flex items-start gap-1">
          <div className="flex-1">
            <Field
              schema={itemSchema}
              value={item}
              onChange={(next) => {
                const copy = arr.slice();
                copy[idx] = next;
                onChange(copy);
              }}
              path={`${path}/${idx}`}
              errors={errors}
            />
          </div>
          <Button
            type="button"
            size="sm"
            variant="ghost"
            aria-label={`Remove item ${idx}`}
            onClick={() => onChange(arr.filter((_, i) => i !== idx))}
          >
            <X size={12} />
          </Button>
        </div>
      ))}
      <Button
        type="button"
        size="sm"
        variant="outline"
        onClick={() => onChange([...arr, getSchemaDefaults(itemSchema) ?? ''])}
      >
        <Plus size={12} /> Add
      </Button>
    </div>
  );
}

function ObjectField({ schema, value, onChange, path, errors }: FieldProps) {
  const obj = isPlainObject(value) ? value : {};
  const properties = schema.properties ?? {};
  const required = new Set(schema.required ?? []);
  return (
    <div className="space-y-3 rounded-md border border-border bg-muted p-3">
      {Object.entries(properties).map(([key, sub]) => (
        <Field
          key={key}
          schema={sub}
          value={obj[key]}
          onChange={(next) => {
            const copy = { ...obj };
            if (next === undefined) {
              delete copy[key];
            } else {
              copy[key] = next;
            }
            onChange(copy);
          }}
          path={`${path}/${key}`}
          errors={errors}
          required={required.has(key)}
          label={sub.title ?? key}
        />
      ))}
      {Object.keys(properties).length === 0 && (
        <p className="text-xs italic text-muted-foreground">No properties defined.</p>
      )}
    </div>
  );
}

function UnionField({ schema, value, onChange, path, errors }: FieldProps) {
  const branches = (schema.oneOf ?? schema.anyOf ?? []).filter(Boolean);
  const idxKey = `__union:${path}`;
  // Heuristic: pick the first branch matching the value's type, otherwise 0.
  const initialIdx = (() => {
    if (value === undefined) return 0;
    const t = Array.isArray(value) ? 'array' : value === null ? 'null' : typeof value;
    const found = branches.findIndex((b) => typeOf(b) === t);
    return found < 0 ? 0 : found;
  })();
  return (
    <div className="space-y-2">
      <Select
        value={String(initialIdx)}
        onValueChange={(v) => {
          const i = Number.parseInt(v, 10);
          onChange(getSchemaDefaults(branches[i]));
        }}
      >
        <SelectTrigger className="w-full">
          <SelectValue placeholder={idxKey} />
        </SelectTrigger>
        <SelectContent>
          {branches.map((b, i) => (
            <SelectItem key={i} value={String(i)}>
              {b.title ?? `Option ${i + 1}`}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
      <Field schema={branches[initialIdx] ?? {}} value={value} onChange={onChange} path={path} errors={errors} />
    </div>
  );
}

function Field(props: FieldProps) {
  const { schema, label, required, path, errors } = props;
  const issues = errorsAt(errors, path);
  if (schema.oneOf || schema.anyOf) {
    return (
      <div className="space-y-1">
        {label && (
          <Label>
            {label}
            {required && <span className="ml-1 text-destructive">*</span>}
          </Label>
        )}
        <UnionField {...props} />
        {issues.map((m, i) => (
          <p key={i} className="text-xs text-destructive">
            {m}
          </p>
        ))}
      </div>
    );
  }
  if (typeOf(schema) === 'null') return null;

  const type = typeOf(schema);
  let inner;
  if (type === 'object') inner = <ObjectField {...props} />;
  else if (type === 'array') inner = <ArrayField {...props} />;
  else if (type === 'boolean') inner = <BooleanField {...props} />;
  else if (type === 'number' || type === 'integer') inner = <NumberField {...props} />;
  else inner = <StringField {...props} />;

  return (
    <div className={clsx('space-y-1')}>
      {label && (
        <Label className="block text-xs uppercase tracking-wide text-muted-foreground">
          {label}
          {required && <span className="ml-1 text-destructive">*</span>}
          {schema.description && (
            <span className="ml-2 normal-case text-muted-foreground">— {schema.description}</span>
          )}
        </Label>
      )}
      {inner}
      {issues.map((m, i) => (
        <p key={i} className="text-xs text-destructive">
          {m}
        </p>
      ))}
    </div>
  );
}

/** Schema-driven recursive form renderer with AJV-backed validation. */
export function DynamicJsonForm({ schema, value, onChange, errors }: DynamicJsonFormProps) {
  // Pre-compile schema so caller can re-use the AJV validator via `validate`.
  // We expose it through useMemo so the form re-renders when the schema swaps.
  useMemo(() => {
    try {
      ajvInstance.compile(schema);
    } catch {
      // Schema may be partially well-formed; ignore — validation occurs on submit.
    }
    return null;
  }, [schema]);
  return (
    <Field
      schema={schema}
      value={value}
      onChange={onChange}
      path=""
      errors={errors}
      label={schema.title}
    />
  );
}

/** Validates a value against a schema and returns AJV errors (or null on success). */
export function validateAgainstSchema(schema: JsonSchema, value: unknown): ErrorObject[] | null {
  try {
    const validate = ajvInstance.compile(schema);
    return validate(value) ? null : (validate.errors ?? []);
  } catch (err) {
    return [{ instancePath: '', schemaPath: '', keyword: 'schema', params: {}, message: err instanceof Error ? err.message : 'schema error' } as ErrorObject];
  }
}

export default DynamicJsonForm;
