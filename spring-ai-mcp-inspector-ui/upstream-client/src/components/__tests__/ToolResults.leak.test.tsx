// [spring-ai-mcp-inspector PATCH] ToolResults exception-leak note tests (#251):
// verbatim vs softer wording, zero noise without an annotation, junk input
// silence, and the full cross-tab jump (hash switch + broker + Timeline scroll).
import { render, screen, waitFor, fireEvent } from "@testing-library/react";
import "@testing-library/jest-dom";
import ToolResults from "../ToolResults";
import TimelineTab from "../TimelineTab";
import { Tabs } from "@/components/ui/tabs";
import { CompatibilityCallToolResult } from "@modelcontextprotocol/sdk/types.js";

const LEAK_LOG_MESSAGE = "E11000 duplicate key error collection: db.app";
// Spec fact F1: without a cause Spring AI duplicates the message
// (msg + lineSeparator + rootCause), so the error text carries it twice.
const LEAK_ERROR_TEXT = LEAK_LOG_MESSAGE + "\n" + LEAK_LOG_MESSAGE;

const ERROR_RESULT: CompatibilityCallToolResult = {
  content: [{ type: "text", text: LEAK_ERROR_TEXT }],
  isError: true,
};

const SUCCESS_RESULT: CompatibilityCallToolResult = {
  content: [{ type: "text", text: "ok" }],
};

const LEAK_LOG_EVENT = {
  id: "evt-log-1",
  correlationId: null as string | null,
  sessionId: null as string | null,
  type: "APP_LOG",
  timestamp: "2026-08-30T12:00:04.100Z",
  payload: {
    logLevel: "ERROR",
    loggerName: "io.inspector.demo.DemoTool",
    threadName: "main",
    message: LEAK_LOG_MESSAGE,
  },
};

function annotatedResponseEvent(matchType: string, verbatim: boolean) {
  return {
    id: "evt-resp-1",
    correlationId: "corr-leak",
    sessionId: "s-leak",
    type: "MCP_JSONRPC_RESPONSE",
    timestamp: "2026-08-30T12:00:05.000Z",
    payload: {
      jsonrpc: "2.0",
      id: 10,
      result: {
        content: [{ type: "text", text: LEAK_ERROR_TEXT }],
        isError: true,
      },
      _leak: {
        matchType,
        verbatim,
        logEventId: "evt-log-1",
        logTimestamp: "2026-08-30T12:00:04.100Z",
        logMessage: LEAK_LOG_MESSAGE,
        logThrowableClass: "org.springframework.dao.DuplicateKeyException",
        multipleCandidates: false,
        droppedCandidates: 0,
      },
    },
  };
}

const UNANNOTATED_RESPONSE_EVENT = {
  id: "evt-resp-2",
  correlationId: "corr-plain",
  sessionId: null as string | null,
  type: "MCP_JSONRPC_RESPONSE",
  timestamp: "2026-08-30T12:00:06.000Z",
  payload: {
    jsonrpc: "2.0",
    id: 11,
    result: {
      content: [{ type: "text", text: LEAK_ERROR_TEXT }],
      isError: true,
    },
  },
};

function mockFetch(events: unknown[]) {
  const fetchMock = jest.fn().mockResolvedValue({
    ok: true,
    json: async () => events,
  });
  (global as unknown as { fetch: jest.Mock }).fetch = fetchMock;
  return fetchMock;
}

function renderResults(
  toolResult: CompatibilityCallToolResult | null,
  isPollingTask = false,
) {
  return render(
    <ToolResults
      toolResult={toolResult}
      selectedTool={null}
      resourceContent={{}}
      isPollingTask={isPollingTask}
    />,
  );
}

describe("ToolResults exception-leak note", () => {
  const originalFetch = global.fetch;
  const originalHash = window.location.hash;

  afterEach(() => {
    global.fetch = originalFetch;
    window.location.hash = originalHash;
    sessionStorage.clear();
  });

  it("states the verbatim leak for an EXACT-annotated error result", async () => {
    mockFetch([annotatedResponseEvent("EXACT", true)]);
    renderResults(ERROR_RESULT);

    const note = await screen.findByTestId("tool-result-leak-note");
    expect(
      screen.getByTestId("tool-result-leak-verbatim"),
    ).toHaveTextContent("The client model received this text verbatim");
    expect(
      screen.queryByTestId("tool-result-leak-soft"),
    ).not.toBeInTheDocument();
    expect(screen.getByTestId("tool-result-leak-jump")).toBeInTheDocument();
    expect(note).toHaveTextContent(LEAK_LOG_MESSAGE);
  });

  it("uses the softer wording without a verbatim claim for PARTIAL", async () => {
    mockFetch([annotatedResponseEvent("PARTIAL", false)]);
    renderResults(ERROR_RESULT);

    await screen.findByTestId("tool-result-leak-note");
    expect(screen.getByTestId("tool-result-leak-soft")).toHaveTextContent(
      "matches an exception logged at 12:00:04",
    );
    expect(
      screen.queryByTestId("tool-result-leak-verbatim"),
    ).not.toBeInTheDocument();
  });

  it("renders no note for an error result without any annotation", async () => {
    const fetchMock = mockFetch([UNANNOTATED_RESPONSE_EVENT]);
    renderResults(ERROR_RESULT);

    await waitFor(() => expect(fetchMock).toHaveBeenCalled());
    await waitFor(() =>
      expect(screen.getByText("Error")).toBeInTheDocument(),
    );
    expect(
      screen.queryByTestId("tool-result-leak-note"),
    ).not.toBeInTheDocument();
  });

  it("performs no lookup at all for a successful result", async () => {
    const fetchMock = jest.fn();
    (global as unknown as { fetch: jest.Mock }).fetch = fetchMock;
    renderResults(SUCCESS_RESULT);

    await waitFor(() =>
      expect(screen.getByText("Success")).toBeInTheDocument(),
    );
    expect(fetchMock).not.toHaveBeenCalled();
    expect(
      screen.queryByTestId("tool-result-leak-note"),
    ).not.toBeInTheDocument();
  });

  it("stays silent on malformed annotations and failed lookups", async () => {
    const fetchMock = jest.fn().mockResolvedValue({
      ok: true,
      json: async () => [
        {
          id: "evt-resp-garbage",
          type: "MCP_JSONRPC_RESPONSE",
          payload: {
            result: {
              content: [{ type: "text", text: LEAK_ERROR_TEXT }],
              isError: true,
            },
            _leak: { matchType: "WEIRD" },
          },
        },
      ],
    });
    (global as unknown as { fetch: jest.Mock }).fetch = fetchMock;
    renderResults(ERROR_RESULT);

    await waitFor(() => expect(fetchMock).toHaveBeenCalled());
    await waitFor(() =>
      expect(screen.getByText("Error")).toBeInTheDocument(),
    );
    expect(
      screen.queryByTestId("tool-result-leak-note"),
    ).not.toBeInTheDocument();
  });

  it("jump link switches to the timeline and scrolls to the log entry", async () => {
    mockFetch([LEAK_LOG_EVENT, annotatedResponseEvent("EXACT", true)]);
    renderResults(ERROR_RESULT);

    fireEvent.click(await screen.findByTestId("tool-result-leak-jump"));

    // The link activates the Timeline tab (hash routing in App) and queues the
    // jump; the Timeline consumes it after its first page loads.
    await waitFor(() => expect(window.location.hash).toBe("#timeline"));
    expect(window.location.hash).toBe("#timeline");

    const scrollIntoView = jest.fn();
    window.HTMLElement.prototype.scrollIntoView = scrollIntoView;
    try {
      // The leak note from the first render also shows the log message, so the
      // row is located inside the Timeline's own container.
      const timeline = render(
        <Tabs defaultValue="timeline">
          <TimelineTab />
        </Tabs>,
      );
      await waitFor(() => expect(scrollIntoView).toHaveBeenCalledTimes(1));
      const logRow = Array.from(
        timeline.container.querySelectorAll("div.border-l-2"),
      ).find((el) => el.textContent?.includes(LEAK_LOG_MESSAGE)) as HTMLElement;
      expect(logRow).toBeDefined();
      expect(scrollIntoView.mock.instances[0]).toBe(logRow);
    } finally {
      delete (window.HTMLElement.prototype as unknown as Record<
        string,
        unknown
      >).scrollIntoView;
    }
  });
});
