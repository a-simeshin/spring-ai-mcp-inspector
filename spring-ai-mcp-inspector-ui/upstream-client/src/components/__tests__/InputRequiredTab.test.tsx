// [spring-ai-mcp-inspector PATCH] SEP-2322 (spec revision 2026-07-28) tests
// for the InputRequired panel (#253): per-type controls for pending
// inputRequests, round indicator, Submit (inputResponses for every request
// id) and Cancel. The legacy ElicitationTab flow stays untouched and is
// covered by its own tests.
import { fireEvent, render, screen, within } from "@testing-library/react";
import { Tabs } from "@/components/ui/tabs";
import InputRequiredTab, {
  PendingInputChain,
} from "../InputRequiredTab";

const mockOnSubmit = jest.fn();
const mockOnCancel = jest.fn();

const schemaRequest = {
  method: "elicitation/create",
  params: {
    message: "Which seat?",
    requestedSchema: {
      type: "object" as const,
      properties: {
        seat: { type: "string" as const, title: "Seat" },
        cabin: {
          title: "Cabin",
          enum: ["economy", "business"],
          enumNames: ["Economy", "Business"],
        },
        bags: { type: "integer" as const, title: "Bags" },
        priority: { type: "boolean" as const, title: "Priority" },
      },
      required: ["seat"],
    },
  },
};

const schemaChain: PendingInputChain = {
  correlationId: 42,
  round: 2,
  requests: { "req-1": schemaRequest },
  requestState: "opaque-state-7",
  originalParams: { name: "book_seat", arguments: { seat: "12A" } },
};

const fallbackChain: PendingInputChain = {
  correlationId: 43,
  round: 1,
  requests: {
    "req-raw": {
      method: "custom/input",
      params: { message: "No schema here" },
    },
  },
  requestState: "state-9",
  originalParams: { name: "book_seat", arguments: {} },
};

const renderTab = (pendingChains: PendingInputChain[]) =>
  render(
    <Tabs defaultValue="input-required">
      <InputRequiredTab
        pendingChains={pendingChains}
        onSubmit={mockOnSubmit}
        onCancel={mockOnCancel}
      />
    </Tabs>,
  );

const inputInside = (testId: string): HTMLInputElement => {
  const scope = screen.getByTestId(testId);
  const input = scope.querySelector("input");
  if (!input) {
    throw new Error(`no input inside ${testId}`);
  }
  return input as HTMLInputElement;
};

const selectInside = (testId: string): HTMLSelectElement => {
  const scope = screen.getByTestId(testId);
  const select = scope.querySelector("select");
  if (!select) {
    throw new Error(`no select inside ${testId}`);
  }
  return select as HTMLSelectElement;
};

describe("InputRequiredTab (SEP-2322 panel)", () => {
  beforeEach(() => {
    mockOnSubmit.mockClear();
    mockOnCancel.mockClear();
  });

  it("renders the empty state when no chains are pending", () => {
    renderTab([]);
    expect(screen.getByText("No pending input requests")).toBeTruthy();
    expect(screen.queryByTestId("input-required-chain")).toBeNull();
  });

  it("renders the round indicator, requestState and per-request facts", () => {
    renderTab([schemaChain]);
    expect(screen.getByTestId("input-required-round").textContent).toBe(
      "Round 2",
    );
    expect(
      screen.getByTestId("input-required-request-state").getAttribute("title"),
    ).toBe("opaque-state-7");
    expect(screen.getByText("tools/call: book_seat")).toBeTruthy();
    const request = within(screen.getByTestId("input-request-req-1"));
    expect(request.getByText("elicitation/create")).toBeTruthy();
    expect(request.getByText("Which seat?")).toBeTruthy();
  });

  it("gathers per-type controls into inputResponses on Submit", () => {
    renderTab([schemaChain]);
    fireEvent.change(inputInside("input-request-req-1-field-seat"), {
      target: { value: "12A" },
    });
    fireEvent.change(selectInside("input-request-req-1-field-cabin"), {
      target: { value: "business" },
    });
    fireEvent.change(inputInside("input-request-req-1-field-bags"), {
      target: { value: "2" },
    });
    fireEvent.click(screen.getByTestId("input-required-submit"));
    expect(mockOnSubmit).toHaveBeenCalledTimes(1);
    const [chain, responses] = mockOnSubmit.mock.calls[0];
    expect(chain.correlationId).toBe(42);
    expect(responses).toEqual({
      "req-1": {
        action: "accept",
        content: { seat: "12A", cabin: "business", bags: 2 },
      },
    });
  });

  it("blocks Submit while a required field is empty and shows why", () => {
    renderTab([schemaChain]);
    fireEvent.click(screen.getByTestId("input-required-submit"));
    expect(mockOnSubmit).not.toHaveBeenCalled();
    expect(
      screen.getByTestId("input-required-validation-error").textContent,
    ).toBe("Required field missing: req-1/seat");
  });

  it("answers requests without a schema through the raw JSON editor", () => {
    renderTab([fallbackChain]);
    fireEvent.change(screen.getByTestId("input-request-req-raw-json"), {
      target: { value: '{"action":"decline"}' },
    });
    fireEvent.click(screen.getByTestId("input-required-submit"));
    expect(mockOnSubmit).toHaveBeenCalledTimes(1);
    const [, responses] = mockOnSubmit.mock.calls[0];
    expect(responses).toEqual({ "req-raw": { action: "decline" } });
  });

  it("rejects invalid JSON in the fallback editor", () => {
    renderTab([fallbackChain]);
    fireEvent.change(screen.getByTestId("input-request-req-raw-json"), {
      target: { value: "{not json" },
    });
    fireEvent.click(screen.getByTestId("input-required-submit"));
    expect(mockOnSubmit).not.toHaveBeenCalled();
    expect(
      screen.getByTestId("input-required-validation-error"),
    ).toBeTruthy();
  });

  it("gathers responses for every pending request id", () => {
    renderTab([
      {
        ...schemaChain,
        requests: {
          "req-1": schemaRequest,
          "req-2": {
            method: "custom/input",
            params: { message: "Anything else?" },
          },
        },
      },
    ]);
    fireEvent.change(inputInside("input-request-req-1-field-seat"), {
      target: { value: "1B" },
    });
    fireEvent.change(screen.getByTestId("input-request-req-2-json"), {
      target: { value: "null" },
    });
    fireEvent.click(screen.getByTestId("input-required-submit"));
    const [, responses] = mockOnSubmit.mock.calls[0];
    expect(Object.keys(responses).sort()).toEqual(["req-1", "req-2"]);
  });

  it("cancels the chain instead of answering it", () => {
    renderTab([schemaChain]);
    fireEvent.click(screen.getByTestId("input-required-cancel"));
    expect(mockOnCancel).toHaveBeenCalledTimes(1);
    expect(mockOnCancel.mock.calls[0][0].correlationId).toBe(42);
    expect(mockOnSubmit).not.toHaveBeenCalled();
  });
});
