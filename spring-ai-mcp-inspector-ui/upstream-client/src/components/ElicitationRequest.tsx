import { useState, useEffect } from "react";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
  DialogFooter,
} from "@/components/ui/dialog";
import DynamicJsonForm from "./DynamicJsonForm";
import JsonView from "./JsonView";
import { JsonSchemaType, JsonValue } from "@/utils/jsonUtils";
import { generateDefaultValue } from "@/utils/schemaUtils";
import {
  PendingElicitationRequest,
  ElicitationResponse,
  ElicitationFormRequestData,
} from "./ElicitationTab";
import Ajv from "ajv";

export type ElicitationRequestProps = {
  request: PendingElicitationRequest;
  onResolve: (id: number, response: ElicitationResponse) => void;
};

const ElicitationRequest = ({
  request,
  onResolve,
}: ElicitationRequestProps) => {
  const [formData, setFormData] = useState<JsonValue>({});
  const [validationError, setValidationError] = useState<string | null>(null);
  const [showUrlConfirm, setShowUrlConfirm] = useState(false);

  const requestData = request.request;
  const isUrlMode = requestData.mode === "url";
  // [spring-ai-mcp-inspector PATCH] Issue #298: once the correlated tools/call
  // has settled the server no longer waits for this answer, so the form is
  // locked and every action is refused instead of closing the card silently.
  const expired = request.expired === true;

  useEffect(() => {
    if (isUrlMode) return;
    const defaultValue = generateDefaultValue(
      (requestData as ElicitationFormRequestData).requestedSchema,
    );
    setFormData(defaultValue);
    setValidationError(null);
  }, [isUrlMode, requestData]);

  if (isUrlMode) {
    // Per MCP SEP-1036 security requirements, only HTTPS URLs may be opened.
    // Validate the server-supplied URL before exposing the "Open URL" affordance
    // so non-https schemes (http:, file:, javascript:, data:) and malformed URLs
    // are refused rather than handed to window.open.
    let parsedUrl: URL | null = null;
    let urlError: string | null = null;
    try {
      parsedUrl = new URL(requestData.url);
      if (parsedUrl.protocol !== "https:") {
        urlError = `Refusing to open this URL: only https: URLs are permitted for URL elicitation (received scheme "${parsedUrl.protocol}").`;
        parsedUrl = null;
      }
    } catch {
      urlError = `The server provided a malformed URL: ${requestData.url}`;
    }

    const handleConfirmOpen = () => {
      if (!parsedUrl) return;
      window.open(parsedUrl.href, "_blank", "noopener,noreferrer");
      setShowUrlConfirm(false);
    };

    return (
      <div
        data-testid="elicitation-request"
        className="flex flex-col gap-4 p-4 border rounded-lg"
      >
        <div className="bg-gray-50 dark:bg-gray-800 dark:text-gray-100 p-2 rounded">
          <div className="space-y-2">
            <h4 className="font-semibold">
              URL Request
              {/* [spring-ai-mcp-inspector PATCH] Issue #298 */}
              {expired && (
                <span
                  data-testid="elicitation-expired-badge"
                  className="ml-2 inline-flex items-center rounded bg-red-100 px-1.5 py-0.5 text-xs font-medium text-red-700 dark:bg-red-900/40 dark:text-red-300"
                >
                  Expired
                </span>
              )}
            </h4>
            <p className="text-sm">{requestData.message}</p>
          </div>
        </div>
        {/* [spring-ai-mcp-inspector PATCH] Issue #298: explicit terminal state */}
        {expired && (
          <div
            data-testid="elicitation-expired"
            className="p-3 bg-red-50 dark:bg-red-900/20 border border-red-200 dark:border-red-800 rounded-md text-sm text-red-600 dark:text-red-400"
          >
            <strong>Request expired:</strong> the tool call that asked for this
            information has already finished (for example, it timed out
            server-side). Your answer cannot be delivered.
          </div>
        )}
        {urlError && (
          <div
            data-testid="url-error"
            className="p-3 bg-red-50 dark:bg-red-900/20 border border-red-200 dark:border-red-800 rounded-md text-sm text-red-600 dark:text-red-400"
          >
            <strong>Invalid URL:</strong> {urlError}
          </div>
        )}
        <div className="flex space-x-2">
          <Button
            type="button"
            disabled={!!urlError || expired}
            onClick={() => setShowUrlConfirm(true)}
          >
            Open URL
          </Button>
          {/* [spring-ai-mcp-inspector PATCH] Issue #298: expired cards refuse */}
          {/* every action and stay open so the banner stays visible. */}
          <Button
            type="button"
            onClick={() => {
              if (expired) return;
              onResolve(request.id, { action: "accept" });
            }}
          >
            Accept
          </Button>
          <Button
            type="button"
            variant="outline"
            onClick={() => {
              if (expired) return;
              onResolve(request.id, { action: "decline" });
            }}
          >
            Decline
          </Button>
          <Button
            type="button"
            variant="outline"
            onClick={() => {
              if (expired) return;
              onResolve(request.id, { action: "cancel" });
            }}
          >
            Cancel
          </Button>
        </div>

        <Dialog open={showUrlConfirm} onOpenChange={setShowUrlConfirm}>
          <DialogContent>
            <DialogHeader>
              <DialogTitle>Open External URL</DialogTitle>
              <DialogDescription>
                The server is requesting you visit the following URL:
              </DialogDescription>
            </DialogHeader>
            {parsedUrl && (
              <p className="text-sm">
                Destination host:{" "}
                <span
                  data-testid="url-confirm-host"
                  className="font-mono font-semibold break-all"
                >
                  {parsedUrl.host}
                </span>
              </p>
            )}
            <p
              data-testid="url-confirm-text"
              className="text-sm font-mono bg-gray-100 dark:bg-gray-800 p-3 rounded break-all select-all"
            >
              {requestData.url}
            </p>
            <DialogFooter>
              <Button
                variant="outline"
                onClick={() => setShowUrlConfirm(false)}
              >
                Cancel
              </Button>
              <Button onClick={handleConfirmOpen}>Open</Button>
            </DialogFooter>
          </DialogContent>
        </Dialog>
      </div>
    );
  }

  // After URL-mode early return, requestData is guaranteed to be form mode
  const formRequest = requestData as ElicitationFormRequestData;

  const validateEmailFormat = (email: string): boolean => {
    const emailRegex = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
    return emailRegex.test(email);
  };

  const validateFormData = (
    data: JsonValue,
    schema: JsonSchemaType,
  ): boolean => {
    if (
      schema.type === "object" &&
      schema.properties &&
      typeof data === "object" &&
      data !== null
    ) {
      const dataObj = data as Record<string, unknown>;

      if (Array.isArray(schema.required)) {
        for (const field of schema.required) {
          const value = dataObj[field];
          if (value === undefined || value === null || value === "") {
            setValidationError(`Required field missing: ${field}`);
            return false;
          }
        }
      }

      for (const [fieldName, fieldValue] of Object.entries(dataObj)) {
        const fieldSchema = schema.properties[fieldName];
        if (
          fieldSchema &&
          fieldSchema.format === "email" &&
          typeof fieldValue === "string"
        ) {
          if (!validateEmailFormat(fieldValue)) {
            setValidationError(`Invalid email format: ${fieldName}`);
            return false;
          }
        }
      }
    }

    return true;
  };

  const handleAccept = () => {
    // [spring-ai-mcp-inspector PATCH] Issue #298: an expired form is never
    // submitted; the banner keeps telling the user why.
    if (expired) return;
    try {
      if (!validateFormData(formData, formRequest.requestedSchema)) {
        return;
      }

      const ajv = new Ajv();
      const validate = ajv.compile(formRequest.requestedSchema);
      const isValid = validate(formData);

      if (!isValid) {
        const errorMessage = ajv.errorsText(validate.errors);
        setValidationError(errorMessage);
        return;
      }

      onResolve(request.id, {
        action: "accept",
        content: formData as Record<string, unknown>,
      });
    } catch (error) {
      setValidationError(
        error instanceof Error ? error.message : "Validation failed",
      );
    }
  };

  const handleDecline = () => {
    if (expired) return; // [spring-ai-mcp-inspector PATCH] Issue #298

    onResolve(request.id, { action: "decline" });
  };

  const handleCancel = () => {
    if (expired) return; // [spring-ai-mcp-inspector PATCH] Issue #298
    onResolve(request.id, { action: "cancel" });
  };

  const schemaTitle =
    formRequest.requestedSchema.title || "Information Request";
  const schemaDescription = formRequest.requestedSchema.description;

  return (
    <div
      data-testid="elicitation-request"
      className="flex gap-4 p-4 border rounded-lg space-y-4"
    >
      <div className="flex-1 bg-gray-50 dark:bg-gray-800 dark:text-gray-100 p-2 rounded">
        <div className="space-y-2">
          <h4 className="font-semibold">
            {schemaTitle}
            {/* [spring-ai-mcp-inspector PATCH] Issue #298 */}
            {expired && (
              <span
                data-testid="elicitation-expired-badge"
                className="ml-2 inline-flex items-center rounded bg-red-100 px-1.5 py-0.5 text-xs font-medium text-red-700 dark:bg-red-900/40 dark:text-red-300"
              >
                Expired
              </span>
            )}
          </h4>
          <p className="text-sm">{formRequest.message}</p>
          {schemaDescription && (
            <p className="text-xs text-muted-foreground">{schemaDescription}</p>
          )}
          <div className="mt-2">
            <h5 className="text-xs font-medium mb-1">Request Schema:</h5>
            <JsonView
              data={JSON.stringify(formRequest.requestedSchema, null, 2)}
            />
          </div>
        </div>
      </div>

      <div className="flex-1 space-y-4">
        {/* [spring-ai-mcp-inspector PATCH] Issue #298: explicit terminal state */}
        {expired && (
          <div
            data-testid="elicitation-expired"
            className="p-3 bg-red-50 dark:bg-red-900/20 border border-red-200 dark:border-red-800 rounded-md text-sm text-red-600 dark:text-red-400"
          >
            <strong>Request expired:</strong> the tool call that asked for this
            information has already finished (for example, it timed out
            server-side). Your answer cannot be delivered.
          </div>
        )}
        <div className="space-y-2">
          <h4 className="font-medium">Response Form</h4>
          {/* [spring-ai-mcp-inspector PATCH] Issue #298: lock the input fields */}
          {/* once the request has expired so late input cannot be typed in. */}
          <div
            className={
              expired
                ? "pointer-events-none select-none opacity-60"
                : undefined
            }
            aria-disabled={expired}
          >
            <DynamicJsonForm
              schema={formRequest.requestedSchema}
              value={formData}
              onChange={(newValue: JsonValue) => {
                setFormData(newValue);
                setValidationError(null);
              }}
            />
          </div>

          {validationError && (
            <div className="mt-4 p-3 bg-red-50 dark:bg-red-900/20 border border-red-200 dark:border-red-800 rounded-md">
              <div className="text-sm text-red-600 dark:text-red-400">
                <strong>Validation Error:</strong> {validationError}
              </div>
            </div>
          )}
        </div>

        <div className="flex space-x-2 mt-1">
          <Button type="button" onClick={handleAccept}>
            Submit
          </Button>
          <Button type="button" variant="outline" onClick={handleDecline}>
            Decline
          </Button>
          <Button type="button" variant="outline" onClick={handleCancel}>
            Cancel
          </Button>
        </div>
      </div>
    </div>
  );
};

export default ElicitationRequest;
