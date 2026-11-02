// [spring-ai-mcp-inspector PATCH] TimelineTab test: schema alignment, APP_LOG rendering, empty state (#130).
// [spring-ai-mcp-inspector PATCH] Protocol negotiation badge test: _protocolNegotiation enrichment (#129, #130).
import { render, screen, waitFor, fireEvent, cleanup } from "@testing-library/react";
import "@testing-library/jest-dom";
import TimelineTab from "../TimelineTab";
import { Tabs } from "@/components/ui/tabs";
import { requestTimelineScrollTo } from "@/lib/leakAnnotations";

/**
 * TimelineTab consumes the serialized io.inspector.mcp.core.timeline.TimelineEvent
 * record: {id, correlationId, sessionId, type, timestamp, payload}. These tests
 * pin that wire contract, including the APP_LOG payload shape and the deployed
 * API path advertised via window.__MCP_INSPECTOR_BOOTSTRAP.
 */

type WireEvent = {
  id: string;
  correlationId: string | null;
  sessionId: string | null;
  type: string;
  timestamp: string;
  payload: Record<string, unknown> | null;
};

const APP_LOG_EVENT: WireEvent = {
  id: "evt-1",
  correlationId: "corr-1",
  sessionId: null,
  type: "APP_LOG",
  timestamp: "2026-08-30T12:00:00.123Z",
  payload: {
    logLevel: "INFO",
    loggerName: "io.inspector.demo.DemoTool",
    threadName: "main",
    message: "tool executed",
  },
};

const REQUEST_EVENT: WireEvent = {
  id: "evt-2",
  correlationId: "corr-2",
  sessionId: "s-1",
  type: "MCP_JSONRPC_REQUEST",
  timestamp: "2026-08-30T12:00:01.456Z",
  payload: {
    jsonrpc: "2.0",
    id: 1,
    method: "tools/list",
    params: {},
  },
};

const DOWNGRADE_RESPONSE_EVENT: WireEvent = {
  id: "evt-3",
  correlationId: "corr-2",
  sessionId: "s-1",
  type: "MCP_JSONRPC_RESPONSE",
  timestamp: "2026-08-30T12:00:01.415Z",
  payload: {
    jsonrpc: "2.0",
    id: 1,
    result: { protocolVersion: "2025-11-25" },
    _protocolNegotiation: {
      requested: "2026-07-28",
      negotiated: "2025-11-25",
      severity: "DOWNGRADE",
      affectedMethods: [],
      summary:
        "Client requested revision 2026-07-28 but server negotiated older 2025-11-25. The server is running an older protocol; new features from 2026-07-28 are unavailable.",
    },
  },
};

const OK_RESPONSE_EVENT: WireEvent = {
  id: "evt-4",
  correlationId: "corr-3",
  sessionId: "s-2",
  type: "MCP_JSONRPC_RESPONSE",
  timestamp: "2026-08-30T12:00:02.000Z",
  payload: {
    jsonrpc: "2.0",
    id: 2,
    result: { protocolVersion: "2025-11-25" },
    _protocolNegotiation: {
      requested: "2025-11-25",
      negotiated: "2025-11-25",
      severity: "OK",
      affectedMethods: [],
      summary: "Client and server agreed on revision 2025-11-25.",
    },
  },
};

const UNKNOWN_RESPONSE_EVENT: WireEvent = {
  id: "evt-5",
  correlationId: "corr-4",
  sessionId: "s-3",
  type: "MCP_JSONRPC_RESPONSE",
  timestamp: "2026-08-30T12:00:03.000Z",
  payload: {
    jsonrpc: "2.0",
    id: 3,
    result: { protocolVersion: "2024-11-05" },
    _protocolNegotiation: {
      requested: "2024-11-05",
      negotiated: "2024-11-05",
      severity: "UNKNOWN",
      affectedMethods: [],
      summary:
        "Both revisions are unknown: requested=2024-11-05, negotiated=2024-11-05",
    },
  },
};

const INCOMPATIBLE_RESPONSE_EVENT: WireEvent = {
  id: "evt-6",
  correlationId: "corr-5",
  sessionId: "s-4",
  type: "MCP_JSONRPC_RESPONSE",
  timestamp: "2026-08-30T12:00:04.000Z",
  payload: {
    jsonrpc: "2.0",
    id: 4,
    result: { protocolVersion: "2026-07-28" },
    _protocolNegotiation: {
      requested: "2025-11-25",
      negotiated: "2026-07-28",
      severity: "INCOMPATIBLE",
      affectedMethods: [
        "initialize",
        "notifications/initialized",
        "ping",
        "logging/setLevel",
        "notifications/roots/list_changed",
        "tasks/list",
        "tasks/result",
        "notifications/elicitation/complete",
      ],
      summary:
        "Client requested revision 2025-11-25 but server negotiated newer 2026-07-28. The server removed methods: [initialize, notifications/initialized, ...]. Calls to these methods will fail with MethodNotFound.",
    },
  },
};

// [spring-ai-mcp-inspector PATCH] CallToolResult isError=true fixtures (#184).

const VALIDATION_ERROR_RESPONSE_EVENT: WireEvent = {
  id: "evt-7",
  correlationId: "corr-6",
  sessionId: "s-5",
  type: "MCP_JSONRPC_RESPONSE",
  timestamp: "2026-08-30T12:00:05.000Z",
  payload: {
    jsonrpc: "2.0",
    id: 5,
    result: {
      content: [
        {
          type: "text",
          text: "Parameter 'limit' must be a positive integer",
        },
      ],
      isError: true,
    },
  },
};

const SUCCESS_CALL_RESPONSE_EVENT: WireEvent = {
  id: "evt-8",
  correlationId: "corr-7",
  sessionId: "s-5",
  type: "MCP_JSONRPC_RESPONSE",
  timestamp: "2026-08-30T12:00:06.000Z",
  payload: {
    jsonrpc: "2.0",
    id: 6,
    result: {
      content: [{ type: "text", text: "ok" }],
      isError: false,
    },
  },
};

const NO_ISERROR_RESPONSE_EVENT: WireEvent = {
  id: "evt-9",
  correlationId: "corr-8",
  sessionId: "s-5",
  type: "MCP_JSONRPC_RESPONSE",
  timestamp: "2026-08-30T12:00:07.000Z",
  payload: {
    jsonrpc: "2.0",
    id: 7,
    result: {
      content: [{ type: "text", text: "ok" }],
    },
  },
};

function mockFetch(events: WireEvent[]) {
  const fetchMock = jest.fn().mockResolvedValue({
    ok: true,
    json: async () => events,
  });
  (global as unknown as { fetch: jest.Mock }).fetch = fetchMock;
  return fetchMock;
}

describe("TimelineTab", () => {
  const originalFetch = global.fetch;
  const originalBootstrap = (window as unknown as Record<string, unknown>)[
    "__MCP_INSPECTOR_BOOTSTRAP"
  ];

  afterEach(() => {
    global.fetch = originalFetch;
    (window as unknown as Record<string, unknown>)[
      "__MCP_INSPECTOR_BOOTSTRAP"
    ] = originalBootstrap;
  });

  const renderTab = () =>
    render(
      <Tabs defaultValue="timeline">
        <TimelineTab />
      </Tabs>,
    );

  it("renders APP_LOG events from the real wire schema without crashing", async () => {
    mockFetch([APP_LOG_EVENT]);
    const { container } = renderTab();

    await waitFor(() =>
      expect(screen.getByText("1 event")).toBeInTheDocument(),
    );
    // The log line shows its message and stays expandable.
    expect(screen.getByText("tool executed")).toBeInTheDocument();
    // A React render error would blank #root; assert DOM is intact.
    expect(container).not.toBeEmptyDOMElement();
  });

  it("renders MCP request events and expands payload JSON", async () => {
    mockFetch([REQUEST_EVENT]);
    renderTab();

    await waitFor(() =>
      expect(screen.getByText("tools/list")).toBeInTheDocument(),
    );
    fireEvent.click(screen.getByText("tools/list"));
    await waitFor(() =>
      expect(screen.getByText(/"method": "tools\/list"/)).toBeInTheDocument(),
    );
  });

  it("uses the inspector path advertised by the bootstrap", async () => {
    (window as unknown as Record<string, unknown>)[
      "__MCP_INSPECTOR_BOOTSTRAP"
    ] = { inspectorPath: "/app/inspector-custom" };
    const fetchMock = mockFetch([]);
    renderTab();

    await waitFor(() => expect(fetchMock).toHaveBeenCalled());
    const calledUrl = String(fetchMock.mock.calls[0][0]);
    expect(calledUrl).toBe("/app/inspector-custom/api/timeline?limit=200");
  });

  it("falls back to the default /mcp-inspector path without a bootstrap", async () => {
    (window as unknown as Record<string, unknown>)[
      "__MCP_INSPECTOR_BOOTSTRAP"
    ] = undefined;
    const fetchMock = mockFetch([]);
    renderTab();

    await waitFor(() => expect(fetchMock).toHaveBeenCalled());
    const calledUrl = String(fetchMock.mock.calls[0][0]);
    expect(calledUrl).toBe("/mcp-inspector/api/timeline?limit=200");
  });

  // [spring-ai-mcp-inspector PATCH] Protocol negotiation badge tests (#129, #130).

  it("renders a downgrade badge on initialize response with _protocolNegotiation", async () => {
    mockFetch([DOWNGRADE_RESPONSE_EVENT]);
    renderTab();

    await waitFor(() =>
      expect(
        screen.getByText(/protocol: 2025-11-25 v \(downgrade\)/),
      ).toBeInTheDocument(),
    );
  });

  it("renders an expanded protocol negotiation block when a downgrade row is clicked", async () => {
    mockFetch([DOWNGRADE_RESPONSE_EVENT]);
    renderTab();

    const badge = await waitFor(() =>
      screen.getByText(/protocol: 2025-11-25 v \(downgrade\)/),
    );
    // Click the row to expand it.
    fireEvent.click(badge);
    // The expanded block shows the negotiation details.
    await waitFor(() =>
      expect(screen.getByText("Protocol negotiation:")).toBeInTheDocument(),
    );
    expect(screen.getByText(/requested: 2026-07-28/)).toBeInTheDocument();
    expect(screen.getByText(/negotiated: 2025-11-25/)).toBeInTheDocument();
    expect(screen.getByText(/severity: DOWNGRADE/)).toBeInTheDocument();
    // Footer link to issue #129 is present.
    expect(
      screen.getByText("See compatibility matrix: issue #129"),
    ).toBeInTheDocument();
  });

  it("renders a plain badge without expanded block for OK severity", async () => {
    mockFetch([OK_RESPONSE_EVENT]);
    renderTab();

    const badge = await waitFor(() =>
      screen.getByText(/protocol: 2025-11-25/),
    );
    // Expand the row: no Protocol negotiation block for OK.
    fireEvent.click(badge);
    // The "Protocol negotiation:" heading should NOT appear for OK.
    expect(screen.queryByText("Protocol negotiation:")).not.toBeInTheDocument();
  });

  it("renders an unknown badge for UNKNOWN severity", async () => {
    mockFetch([UNKNOWN_RESPONSE_EVENT]);
    renderTab();

    await waitFor(() =>
      expect(
        screen.getByText(/protocol: 2024-11-05 \? \(unknown\)/),
      ).toBeInTheDocument(),
    );
  });

  it("renders an incompatible badge and expanded block for INCOMPATIBLE severity", async () => {
    mockFetch([INCOMPATIBLE_RESPONSE_EVENT]);
    renderTab();

    const badge = await waitFor(() =>
      screen.getByText(/protocol: 2026-07-28 ! \(incompatible\)/),
    );
    // Click the row to expand the details.
    fireEvent.click(badge);
    await waitFor(() =>
      expect(screen.getByText("Protocol negotiation:")).toBeInTheDocument(),
    );
    expect(screen.getByText(/severity: INCOMPATIBLE/)).toBeInTheDocument();
    // Affected methods appear in the expanded block.
    expect(screen.getByText(/affected: initialize/)).toBeInTheDocument();
  });

  // [spring-ai-mcp-inspector PATCH] CallToolResult isError=true tests (#184).

  // Each test locates the event row via its correlationId suffix and asserts the
  // full color class list, so reverting the error color scheme (TimelineTab.tsx
  // colorClass/bgClass) or the badge itself fails the suite.

  const rowByCorrelation = (suffix: string) => {
    const rows = screen.getAllByText(suffix).map((el) => el.closest("div.border-l-2"));
    expect(rows).toHaveLength(1);
    return rows[0] as HTMLElement;
  };

  it("renders an error row with tool error badge and tooltip for isError=true CallToolResult", async () => {
    mockFetch([VALIDATION_ERROR_RESPONSE_EVENT]);
    renderTab();

    const badge = await waitFor(() =>
      screen.getByTestId("timeline-tool-error-badge"),
    );
    expect(badge).toHaveTextContent("tool error");
    expect(badge).toHaveAttribute(
      "title",
      "Parameter 'limit' must be a positive integer",
    );

    const row = rowByCorrelation("corr-6");
    expect(row.className).toContain("text-red-400");
    expect(row.className).toContain("border-l-red-500");
    expect(row.className).toContain("bg-red-950/30");
  });

  it("shows the validator error message in the expanded row", async () => {
    mockFetch([VALIDATION_ERROR_RESPONSE_EVENT]);
    renderTab();

    fireEvent.click(await screen.findByTestId("timeline-tool-error-badge"));
    const message = await screen.findByTestId("timeline-tool-error-message");
    expect(message).toHaveTextContent(
      "Parameter 'limit' must be a positive integer",
    );
  });

  it("renders a successful CallToolResult row with the regular response colors", async () => {
    mockFetch([SUCCESS_CALL_RESPONSE_EVENT]);
    renderTab();

    await waitFor(() =>
      expect(screen.getByText("1 event")).toBeInTheDocument(),
    );
    expect(screen.queryByTestId("timeline-tool-error-badge")).not.toBeInTheDocument();

    const row = rowByCorrelation("corr-7");
    expect(row.className).toContain("text-green-400");
    expect(row.className).toContain("border-l-green-500");
    expect(row.className).toContain("bg-green-950/30");
    expect(row.className).not.toContain("red");
  });

  it("renders a response row without isError with the regular response colors", async () => {
    mockFetch([NO_ISERROR_RESPONSE_EVENT]);
    renderTab();

    await waitFor(() =>
      expect(screen.getByText("1 event")).toBeInTheDocument(),
    );
    expect(screen.queryByTestId("timeline-tool-error-badge")).not.toBeInTheDocument();

    const row = rowByCorrelation("corr-8");
    expect(row.className).toContain("text-green-400");
    expect(row.className).toContain("border-l-green-500");
    expect(row.className).toContain("bg-green-950/30");
    expect(row.className).not.toContain("red");
  });

  // [spring-ai-mcp-inspector PATCH] Exception-leak badge tests (#251). Fixtures
  // follow the D5 wire shape as serialized by
  // ExceptionLeakDetector#toLeakNode (verified on the engine branch).

  const LEAK_LOG_MESSAGE = "E11000 duplicate key error collection: db.app";

  // Spring AI concatenates message + lineSeparator + rootCause, so without a
  // cause the text is duplicated (spec fact F1): this is the exact error text.
  const LEAK_ERROR_TEXT =
    LEAK_LOG_MESSAGE + "\n" + LEAK_LOG_MESSAGE;

  const LEAK_LOG_EVENT: WireEvent = {
    id: "evt-log-1",
    correlationId: null,
    sessionId: null,
    type: "APP_LOG",
    timestamp: "2026-08-30T12:00:04.100Z",
    payload: {
      logLevel: "ERROR",
      loggerName: "io.inspector.demo.DemoTool",
      threadName: "main",
      message: LEAK_LOG_MESSAGE,
    },
  };

  function leakResponseEvent(
    matchType: string,
    verbatim: boolean,
    logMessage: string = LEAK_LOG_MESSAGE,
  ): WireEvent {
    return {
      id: "evt-resp-leak",
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
          logMessage,
          logThrowableClass: "org.springframework.dao.DuplicateKeyException",
          multipleCandidates: false,
          droppedCandidates: 0,
        },
      },
    };
  }

  const LEAK_EXACT_RESPONSE_EVENT = leakResponseEvent("EXACT", true);
  const LEAK_NORMALIZED_RESPONSE_EVENT = leakResponseEvent("NORMALIZED", false);
  const LEAK_PARTIAL_RESPONSE_EVENT = leakResponseEvent("PARTIAL", false);

  function mockScrollIntoView(): jest.Mock {
    const scrollIntoView = jest.fn();
    window.HTMLElement.prototype.scrollIntoView = scrollIntoView;
    return scrollIntoView;
  }

  function restoreScrollIntoView() {
    delete (window.HTMLElement.prototype as unknown as Record<string, unknown>)
      .scrollIntoView;
  }

  // The APP_LOG row, identified by its gray border: the message text also
  // appears inside the expanded leak detail block of the response row.
  function logRowOf(message: string): HTMLElement {
    return Array.from(document.querySelectorAll("div.border-l-2")).find(
      (el) =>
        el.className.includes("border-l-gray-500") &&
        el.textContent?.includes(message),
    ) as HTMLElement;
  }

  it("renders a leak: exact badge next to the tool error badge for an EXACT annotation", async () => {
    mockFetch([LEAK_LOG_EVENT, LEAK_EXACT_RESPONSE_EVENT]);
    renderTab();

    const badge = await screen.findByTestId("timeline-leak-badge-exact");
    expect(badge).toHaveTextContent("leak: exact");
    expect(badge.getAttribute("title")).toContain("verbatim");
    expect(badge.getAttribute("title")).toContain(LEAK_LOG_MESSAGE);

    // Next to the #201 tool-error badge: the leak badge renders right after it.
    const errorBadge = screen.getByTestId("timeline-tool-error-badge");
    expect(
      errorBadge.compareDocumentPosition(badge) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
  });

  it("scrolls the correlated APP_LOG row into view on badge click without expanding the row", async () => {
    mockFetch([LEAK_LOG_EVENT, LEAK_EXACT_RESPONSE_EVENT]);
    renderTab();
    const scrollIntoView = mockScrollIntoView();
    try {
      const badge = await screen.findByTestId("timeline-leak-badge-exact");
      fireEvent.click(badge);

      await waitFor(() => expect(scrollIntoView).toHaveBeenCalledTimes(1));
      // The scrolled element is the APP_LOG row carrying the log message, not
      // the response row the badge sits on.
      expect(scrollIntoView.mock.instances[0]).toBe(logRowOf(LEAK_LOG_MESSAGE));
      // stopPropagation: the badge click must not toggle the row expansion.
      expect(screen.queryByText(/"jsonrpc": "2.0"/)).not.toBeInTheDocument();
    } finally {
      restoreScrollIntoView();
    }
  });

  it("renders three visually distinct badge styles for EXACT, NORMALIZED and PARTIAL", async () => {
    for (const [event, testId, expectedClass, forbiddenClass] of [
      [LEAK_EXACT_RESPONSE_EVENT, "timeline-leak-badge-exact", "border-red-500", "dashed"],
      [LEAK_NORMALIZED_RESPONSE_EVENT, "timeline-leak-badge-normalized", "border-amber-500", "dashed"],
      [LEAK_PARTIAL_RESPONSE_EVENT, "timeline-leak-badge-partial", "border-dashed", "border-amber-500"],
    ] as const) {
      mockFetch([event]);
      renderTab();

      const badge = await screen.findByTestId(testId);
      expect(badge.className).toContain(expectedClass);
      expect(badge.className).not.toContain(forbiddenClass);

      cleanup();
    }
  });

  it("renders no leak badge for an error response without a _leak annotation", async () => {
    mockFetch([VALIDATION_ERROR_RESPONSE_EVENT]);
    renderTab();

    await waitFor(() =>
      expect(screen.getByText("1 event")).toBeInTheDocument(),
    );
    expect(
      screen.queryByTestId("timeline-leak-badge-exact"),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByTestId("timeline-leak-badge-normalized"),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByTestId("timeline-leak-badge-partial"),
    ).not.toBeInTheDocument();
  });

  it("renders no leak badge and does not crash on malformed _leak nodes", async () => {
    const malformed: WireEvent[] = [
      {
        id: "evt-resp-garbage-1",
        correlationId: "corr-g1",
        sessionId: null,
        type: "MCP_JSONRPC_RESPONSE",
        timestamp: "2026-08-30T12:00:06.000Z",
        payload: {
          jsonrpc: "2.0",
          id: 11,
          result: { content: [{ type: "text", text: "boom" }], isError: true },
          _leak: "not-an-object",
        },
      },
      {
        id: "evt-resp-garbage-2",
        correlationId: "corr-g2",
        sessionId: null,
        type: "MCP_JSONRPC_RESPONSE",
        timestamp: "2026-08-30T12:00:07.000Z",
        payload: {
          jsonrpc: "2.0",
          id: 12,
          result: { content: [{ type: "text", text: "boom" }], isError: true },
          _leak: { matchType: "WEIRD", verbatim: true, logEventId: "x" },
        },
      },
    ];
    mockFetch(malformed);
    const { container } = renderTab();

    await waitFor(() =>
      expect(screen.getByText("2 events")).toBeInTheDocument(),
    );
    expect(container).not.toBeEmptyDOMElement();
    expect(
      screen.queryByTestId("timeline-leak-badge-exact"),
    ).not.toBeInTheDocument();
  });
  it("caps the leak badge tooltip at 200 characters for long log messages", async () => {
    const longMessage = "x".repeat(400);
    mockFetch([leakResponseEvent("EXACT", true, longMessage)]);
    renderTab();

    const badge = await screen.findByTestId("timeline-leak-badge-exact");
    const title = badge.getAttribute("title") ?? "";
    expect(title.length).toBeLessThanOrEqual(200);
    expect(title.endsWith("...")).toBe(true);
  });

  it("renders the expanded leak detail block with a line-clamped message and jump link", async () => {
    mockFetch([LEAK_LOG_EVENT, LEAK_EXACT_RESPONSE_EVENT]);
    renderTab();

    const badge = await screen.findByTestId("timeline-leak-badge-exact");
    fireEvent.click(badge.closest("div.border-l-2") as HTMLElement);

    const message = await screen.findByTestId("timeline-leak-log-message");
    expect(message).toHaveTextContent(LEAK_LOG_MESSAGE);
    expect(message.className).toContain("line-clamp-2");
    expect(
      screen.getByText("exception: org.springframework.dao.DuplicateKeyException"),
    ).toBeInTheDocument();

    const scrollIntoView = mockScrollIntoView();
    try {
      fireEvent.click(screen.getByTestId("timeline-leak-jump-link"));
      await waitFor(() => expect(scrollIntoView).toHaveBeenCalledTimes(1));
      expect(scrollIntoView.mock.instances[0]).toBe(logRowOf(LEAK_LOG_MESSAGE));
    } finally {
      restoreScrollIntoView();
    }
  });

  it("consumes a pending jump requested while the Timeline was not mounted", async () => {
    // The Tool Result pane queues the jump before the tab switch mounts us.
    requestTimelineScrollTo("evt-log-1");
    mockFetch([LEAK_LOG_EVENT, LEAK_EXACT_RESPONSE_EVENT]);
    renderTab();

    const scrollIntoView = mockScrollIntoView();
    try {
      await waitFor(() => expect(scrollIntoView).toHaveBeenCalledTimes(1));
      expect(scrollIntoView.mock.instances[0]).toBe(logRowOf(LEAK_LOG_MESSAGE));
    } finally {
      restoreScrollIntoView();
    }
  });
});