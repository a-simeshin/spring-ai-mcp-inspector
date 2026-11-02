import { useEffect, useState } from "react";
import { Button } from "@/components/ui/button";
import {
  InputRequests,
  InputResponses,
  ToolCallParams,
} from "@/lib/inputRequired";
import { JsonSchemaType } from "@/utils/jsonUtils";

// [spring-ai-mcp-inspector PATCH] SEP-2322 (spec revision 2026-07-28)
// input_required panel, per-chain card (#253). Sibling of the legacy
// ElicitationRequest.tsx flow (2025-06-18 SSE elicitation), which stays
// untouched: this card answers inputRequests that arrived INSIDE a tools/call
// result, not server-initiated elicitation/create requests. The answer for a
// request id is the natural result object of the request's method: for
// elicitation/create-shaped requests (params.message + params.requestedSchema)
// that is { action: "accept", content: {...} }, mirroring the legacy
// ElicitationResponse shape; requests without a usable schema fall back to a
// raw JSON editor so no pending request is ever unanswerable.

export interface PendingInputChain {
  /** Client-side correlation id, stable across all rounds of the chain. */
  correlationId: number;
  /** 1-based round number within the chain. */
  round: number;
  /** Requests to answer now, keyed by server request id. */
  requests: InputRequests;
  /** Opaque server state to echo byte-exact on the next resend, if sent. */
  requestState: string | undefined;
  /** Params of the ORIGINAL tools/call that started this chain. */
  originalParams: ToolCallParams;
}

export type InputRequiredChainProps = {
  chain: PendingInputChain;
  onSubmit: (chain: PendingInputChain, responses: InputResponses) => void;
  onCancel: (chain: PendingInputChain) => void;
};

const asRecord = (value: unknown): Record<string, unknown> | null =>
  value !== null && typeof value === "object"
    ? (value as Record<string, unknown>)
    : null;

/** A JSON-schema-ish form schema usable for per-type input controls. */
const schemaOfRequest = (
  params: Record<string, unknown> | undefined,
): JsonSchemaType | null => {
  const schema = asRecord(params?.["requestedSchema"]);
  if (!schema) {
    return null;
  }
  if (!asRecord(schema["properties"])) {
    return null;
  }
  return schema as unknown as JsonSchemaType;
};

interface FieldControlProps {
  requestId: string;
  name: string;
  fieldSchema: JsonSchemaType;
  required: boolean;
  value: unknown;
  onChange: (value: unknown) => void;
}

/**
 * One per-type control: enum renders a select, number/integer a number input,
 * boolean a checkbox, everything else a text input (the per-type control set
 * SEP-2322 inputRequests need).
 */
const FieldControl = ({
  requestId,
  name,
  fieldSchema,
  required,
  value,
  onChange,
}: FieldControlProps) => {
  const testId = `input-request-${requestId}-field-${name}`;
  const label = fieldSchema.title ?? name;
  if (Array.isArray(fieldSchema.enum) && fieldSchema.enum.length > 0) {
    const options = fieldSchema.enum;
    const names = Array.isArray(fieldSchema.enumNames)
      ? fieldSchema.enumNames
      : options;
    return (
      <label className="block text-sm" data-testid={testId}>
        <span className="block mb-1 font-medium">
          {label}
          {required ? " *" : ""}
        </span>
        <select
          className="w-full p-2 border rounded bg-background"
          value={typeof value === "string" ? value : ""}
          onChange={(e) => onChange(e.target.value)}
        >
          <option value="">Select...</option>
          {options.map((option, idx) => (
            <option key={option} value={option}>
              {names[idx] ?? option}
            </option>
          ))}
        </select>
      </label>
    );
  }
  if (fieldSchema.type === "boolean") {
    return (
      <label className="flex items-center gap-2 text-sm" data-testid={testId}>
        <input
          type="checkbox"
          className="w-4 h-4"
          checked={value === true}
          onChange={(e) => onChange(e.target.checked)}
        />
        <span className="font-medium">
          {label}
          {required ? " *" : ""}
        </span>
      </label>
    );
  }
  if (fieldSchema.type === "number" || fieldSchema.type === "integer") {
    return (
      <label className="block text-sm" data-testid={testId}>
        <span className="block mb-1 font-medium">
          {label}
          {required ? " *" : ""}
        </span>
        <input
          type="number"
          className="w-full p-2 border rounded bg-background"
          value={value === undefined || value === null ? "" : String(value)}
          onChange={(e) =>
            onChange(e.target.value === "" ? undefined : Number(e.target.value))
          }
        />
      </label>
    );
  }
  return (
    <label className="block text-sm" data-testid={testId}>
      <span className="block mb-1 font-medium">
        {label}
        {required ? " *" : ""}
      </span>
      <input
        type="text"
        className="w-full p-2 border rounded bg-background"
        value={typeof value === "string" ? value : ""}
        onChange={(e) => onChange(e.target.value)}
      />
    </label>
  );
};

/** One pending inputRequest inside a chain, fully controlled by the parent. */
const InputRequestForm = ({
  requestId,
  request,
  schema,
  values,
  rawJson,
  onValueChange,
  onRawJsonChange,
}: {
  requestId: string;
  request: { method: string; params?: Record<string, unknown> };
  schema: JsonSchemaType | null;
  values: Record<string, unknown>;
  rawJson: string;
  onValueChange: (name: string, value: unknown) => void;
  onRawJsonChange: (text: string) => void;
}) => {
  const params = asRecord(request.params);
  const message =
    params && typeof params["message"] === "string" ? params["message"] : null;
  const properties = schema?.properties ?? {};
  const required = new Set(schema?.required ?? []);

  return (
    <div
      data-testid={`input-request-${requestId}`}
      className="border rounded p-3 space-y-2"
    >
      <div className="flex items-center gap-2 text-sm">
        <span className="font-mono font-semibold">{requestId}</span>
        <span className="px-1.5 py-0.5 rounded bg-muted text-muted-foreground font-mono text-xs">
          {request.method}
        </span>
      </div>
      {message && <p className="text-sm">{message}</p>}
      {schema ? (
        <div className="space-y-2">
          {Object.entries(properties).map(([name, fieldSchema]) => (
            <FieldControl
              key={name}
              requestId={requestId}
              name={name}
              fieldSchema={fieldSchema}
              required={required.has(name)}
              value={values[name]}
              onChange={(value) => onValueChange(name, value)}
            />
          ))}
        </div>
      ) : (
        <label className="block text-sm">
          <span className="block mb-1 font-medium">
            Response JSON (this request has no usable schema)
          </span>
          <textarea
            data-testid={`input-request-${requestId}-json`}
            className="w-full h-24 p-2 border rounded bg-background font-mono text-xs"
            value={rawJson}
            onChange={(e) => onRawJsonChange(e.target.value)}
          />
        </label>
      )}
      {request.params !== undefined && (
        <details className="text-xs text-muted-foreground">
          <summary className="cursor-pointer">Request params</summary>
          <pre className="mt-1 whitespace-pre-wrap">
            {JSON.stringify(request.params, null, 2)}
          </pre>
        </details>
      )}
    </div>
  );
};

/** Per-request draft state (form values or raw JSON fallback text). */
interface RequestDraft {
  schema: JsonSchemaType | null;
  values: Record<string, unknown>;
  rawJson: string;
}

const draftOf = (
  request: { method: string; params?: Record<string, unknown> } | undefined,
  previous?: RequestDraft,
): RequestDraft => {
  const params = asRecord(request?.params);
  return {
    schema: params ? schemaOfRequest(params) : null,
    values: previous?.values ?? {},
    rawJson: previous?.rawJson ?? "{}",
  };
};

const InputRequiredChainCard = ({
  chain,
  onSubmit,
  onCancel,
}: InputRequiredChainProps) => {
  const [drafts, setDrafts] = useState<Record<string, RequestDraft>>({});
  const [validationError, setValidationError] = useState<string | null>(null);

  // A new round of the same chain can reuse request ids with fresh params:
  // re-derive the drafts (schemas included) whenever the round changes, while
  // keeping what the user already typed for ids that survived the round.
  useEffect(() => {
    setDrafts((prev) => {
      const next: Record<string, RequestDraft> = {};
      for (const [requestId, request] of Object.entries(chain.requests)) {
        next[requestId] = draftOf(request, prev[requestId]);
      }
      return next;
    });
  }, [chain.requests]);

  const setDraft = (requestId: string, patch: Partial<RequestDraft>) => {
    setDrafts((prev) => {
      const base: RequestDraft =
        prev[requestId] ?? draftOf(chain.requests[requestId]);
      return { ...prev, [requestId]: { ...base, ...patch } };
    });
  };

  const handleSubmit = () => {
    const responses: InputResponses = {};
    for (const [requestId, request] of Object.entries(chain.requests)) {
      const draft = drafts[requestId] ?? draftOf(request);
      if (draft.schema) {
        const required = new Set(draft.schema.required ?? []);
        for (const field of required) {
          const value = draft.values[field];
          if (value === undefined || value === null || value === "") {
            setValidationError(`Required field missing: ${requestId}/${field}`);
            return;
          }
        }
        responses[requestId] = { action: "accept", content: draft.values };
      } else {
        try {
          responses[requestId] = JSON.parse(draft.rawJson) as unknown;
        } catch {
          setValidationError(
            `Invalid JSON for request ${requestId}: expected a JSON value`,
          );
          return;
        }
      }
    }
    setValidationError(null);
    onSubmit(chain, responses);
  };

  const toolName =
    typeof chain.originalParams.name === "string"
      ? chain.originalParams.name
      : null;

  return (
    <div
      data-testid="input-required-chain"
      className="flex flex-col gap-3 p-4 border rounded-lg"
    >
      <div className="flex items-center gap-2 flex-wrap">
        <h4 className="font-semibold">
          {toolName ? `tools/call: ${toolName}` : "tools/call input required"}
        </h4>
        <span
          data-testid="input-required-round"
          className="px-1.5 py-0.5 rounded bg-amber-100 text-amber-900 dark:bg-amber-900 dark:text-amber-100 text-xs font-mono"
        >
          Round {chain.round}
        </span>
        <span className="text-xs text-muted-foreground font-mono">
          chain #{chain.correlationId}
        </span>
        {chain.requestState !== undefined && (
          <span
            data-testid="input-required-request-state"
            title={chain.requestState}
            className="max-w-40 truncate text-xs text-muted-foreground font-mono"
          >
            state: {chain.requestState}
          </span>
        )}
      </div>
      <div className="space-y-2">
        {Object.entries(chain.requests).map(([requestId, request]) => {
          const draft = drafts[requestId] ?? draftOf(request);
          return (
            <InputRequestForm
              key={`${chain.correlationId}-${chain.round}-${requestId}`}
              requestId={requestId}
              request={request}
              schema={draft.schema}
              values={draft.values}
              rawJson={draft.rawJson}
              onValueChange={(name, value) =>
                setDraft(requestId, {
                  values: { ...draft.values, [name]: value },
                })
              }
              onRawJsonChange={(text) => setDraft(requestId, { rawJson: text })}
            />
          );
        })}
        {Object.keys(chain.requests).length === 0 && (
          <p className="text-sm text-muted-foreground">
            The server asked for input but sent no inputRequests; submit echoes
            the requestState back to it.
          </p>
        )}
      </div>
      {validationError && (
        <div
          data-testid="input-required-validation-error"
          className="p-3 bg-red-50 dark:bg-red-900/20 border border-red-200 dark:border-red-800 rounded-md text-sm text-red-600 dark:text-red-400"
        >
          {validationError}
        </div>
      )}
      <div className="flex space-x-2">
        <Button
          type="button"
          data-testid="input-required-submit"
          onClick={handleSubmit}
        >
          Submit
        </Button>
        <Button
          type="button"
          variant="outline"
          data-testid="input-required-cancel"
          onClick={() => onCancel(chain)}
        >
          Cancel
        </Button>
      </div>
    </div>
  );
};

export default InputRequiredChainCard;
