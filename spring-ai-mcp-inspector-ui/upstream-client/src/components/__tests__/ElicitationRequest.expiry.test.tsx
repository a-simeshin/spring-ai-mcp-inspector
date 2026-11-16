import { render, screen, fireEvent, act } from "@testing-library/react";
import "@testing-library/jest-dom";
import { describe, it, jest, beforeEach, afterEach } from "@jest/globals";
import ElicitationRequest from "../ElicitationRequest";
import { PendingElicitationRequest } from "../ElicitationTab";

jest.mock("../DynamicJsonForm", () => {
  return function MockDynamicJsonForm({
    value,
    onChange,
  }: {
    value: unknown;
    onChange: (value: unknown) => void;
  }) {
    return (
      <div data-testid="dynamic-json-form">
        <input
          data-testid="form-input"
          value={
            typeof value === "object" && value !== null
              ? JSON.stringify(value)
              : String(value || "")
          }
          onChange={(e) => {
            try {
              const parsed = JSON.parse(e.target.value);
              onChange(parsed);
            } catch {
              onChange(e.target.value);
            }
          }}
        />
      </div>
    );
  };
});

// [spring-ai-mcp-inspector PATCH] Issue #298: an elicitation card whose
// correlated tools/call already settled must not behave like a live form.
// The banner and the Expired badge state the terminal condition, the input
// fields are locked, and every action is refused instead of silently
// resolving (or silently closing) the card with an undeliverable answer.
describe("ElicitationRequest expiry (Issue #298)", () => {
  const mockOnResolve = jest.fn();

  beforeEach(() => {
    jest.clearAllMocks();
  });

  afterEach(() => {
    jest.clearAllMocks();
  });

  const createMockRequest = (
    overrides: Partial<PendingElicitationRequest> = {},
  ): PendingElicitationRequest => ({
    id: 1,
    request: {
      id: 1,
      message: "Please provide your information",
      requestedSchema: {
        type: "object",
        properties: {
          name: { type: "string", description: "Your name" },
        },
        required: ["name"],
      },
    },
    ...overrides,
  });

  const renderElicitationRequest = (
    request: PendingElicitationRequest = createMockRequest(),
  ) => render(<ElicitationRequest request={request} onResolve={mockOnResolve} />);

  describe("form mode", () => {
    it("marks an expired card with a banner and an Expired badge", () => {
      renderElicitationRequest(createMockRequest({ expired: true }));

      expect(screen.getByTestId("elicitation-expired")).toBeInTheDocument();
      expect(screen.getByTestId("elicitation-expired-badge")).toHaveTextContent(
        "Expired",
      );
      expect(screen.getByText(/cannot be delivered/i)).toBeInTheDocument();
    });

    it("does not show expiry UI on a live card", () => {
      renderElicitationRequest();

      expect(
        screen.queryByTestId("elicitation-expired"),
      ).not.toBeInTheDocument();
      expect(
        screen.queryByTestId("elicitation-expired-badge"),
      ).not.toBeInTheDocument();
    });

    it("locks the input fields of an expired card", () => {
      renderElicitationRequest(createMockRequest({ expired: true }));

      const container = screen.getByTestId("elicitation-request");
      const lockedFields = container.querySelector('[aria-disabled="true"]');
      expect(lockedFields).not.toBeNull();
      expect(
        lockedFields?.contains(screen.getByTestId("dynamic-json-form")),
      ).toBe(true);
    });

    it("refuses Submit on an expired card and keeps it open", async () => {
      renderElicitationRequest(createMockRequest({ expired: true }));

      const input = screen.getByTestId("form-input");
      await act(async () => {
        fireEvent.change(input, { target: { value: '{"name": "Jane"}' } });
      });
      fireEvent.click(screen.getByRole("button", { name: /submit/i }));

      expect(mockOnResolve).not.toHaveBeenCalled();
      expect(screen.getByTestId("elicitation-request")).toBeInTheDocument();
      expect(screen.getByTestId("elicitation-expired")).toBeInTheDocument();
    });

    it("refuses Decline and Cancel on an expired card", () => {
      renderElicitationRequest(createMockRequest({ expired: true }));

      fireEvent.click(screen.getByRole("button", { name: /decline/i }));
      fireEvent.click(screen.getByRole("button", { name: /cancel/i }));

      expect(mockOnResolve).not.toHaveBeenCalled();
      expect(screen.getByTestId("elicitation-request")).toBeInTheDocument();
    });

    it("still resolves a live card normally", async () => {
      renderElicitationRequest();

      const input = screen.getByTestId("form-input");
      await act(async () => {
        fireEvent.change(input, { target: { value: '{"name": "Jane"}' } });
      });
      fireEvent.click(screen.getByRole("button", { name: /submit/i }));
      expect(mockOnResolve).toHaveBeenCalledTimes(1);
      expect(mockOnResolve).toHaveBeenCalledWith(
        1,
        expect.objectContaining({ action: "accept" }),
      );
    });
  });

  describe("url mode", () => {
    const createUrlRequest = (
      overrides: Partial<PendingElicitationRequest> = {},
    ): PendingElicitationRequest => ({
      id: 2,
      request: {
        id: 2,
        mode: "url" as const,
        message: "Please open this URL",
        url: "https://example.com/consent",
        elicitationId: "elicit-1",
      },
      ...overrides,
    });

    it("marks an expired URL card with a banner and an Expired badge", () => {
      renderElicitationRequest(createUrlRequest({ expired: true }));

      expect(screen.getByTestId("elicitation-expired")).toBeInTheDocument();
      expect(screen.getByTestId("elicitation-expired-badge")).toHaveTextContent(
        "Expired",
      );
    });

    it("refuses every URL-mode action on an expired card", () => {
      renderElicitationRequest(createUrlRequest({ expired: true }));

      const openUrl = screen.getByRole("button", { name: /open url/i });
      expect(openUrl).toBeDisabled();
      fireEvent.click(screen.getByRole("button", { name: /^accept$/i }));
      fireEvent.click(screen.getByRole("button", { name: /decline/i }));
      fireEvent.click(screen.getByRole("button", { name: /cancel/i }));

      expect(mockOnResolve).not.toHaveBeenCalled();
      expect(screen.getByTestId("elicitation-request")).toBeInTheDocument();
    });
  });
});
