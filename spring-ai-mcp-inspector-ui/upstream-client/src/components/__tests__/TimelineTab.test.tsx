// [spring-ai-mcp-inspector PATCH] TimelineTab test: schema alignment, APP_LOG rendering, empty state (#130).
// [spring-ai-mcp-inspector PATCH] Protocol negotiation badge test: _protocolNegotiation enrichment (#129, #130).
import { render, screen, waitFor, fireEvent, cleanup, act } from "@testing-library/react";
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
    result: { protocolVersion: "2099-01-01" },
    _protocolNegotiation: {
      requested: "2099-01-01",
      negotiated: "2099-01-01",
      severity: "UNKNOWN",
      affectedMethods: [],
      summary:
        "Both revisions are unknown: requested=2099-01-01, negotiated=2099-01-01",
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

// [spring-ai-mcp-inspector PATCH] Timeline auth header tests (#316): ok/status
// options let tests exercise non-OK timeline responses.
function mockFetch(events: WireEvent[], options?: { ok?: boolean; status?: number }) {
  const fetchMock = jest.fn().mockResolvedValue({
    ok: options?.ok ?? true,
    status: options?.status ?? 200,
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

  // [spring-ai-mcp-inspector PATCH] Timeline auth header tests (#316): the
  // inspector guard on ${path}/api/* reads a raw X-MCP-Inspector-Auth value
  // (InspectorAuthFilter / InspectorAuthWebFilter), never Authorization: Bearer.
  it("sends the boot token as a raw X-MCP-Inspector-Auth header", async () => {
    (window as unknown as Record<string, unknown>)[
      "__MCP_INSPECTOR_BOOTSTRAP"
    ] = { inspectorPath: "/mcp-inspector", authToken: "tok-316-secret" };
    const fetchMock = mockFetch([]);
    renderTab();

    await waitFor(() =>
      expect(screen.getByText("No timeline events yet")).toBeInTheDocument(),
    );
    const init = fetchMock.mock.calls[0][1] as {
      headers: Record<string, string>;
    };
    expect(init.headers["X-MCP-Inspector-Auth"]).toBe("tok-316-secret");
    expect(init.headers["Authorization"]).toBeUndefined();
  });

  it("sends no auth header when no token is stored", async () => {
    const fetchMock = mockFetch([]);
    renderTab();

    await waitFor(() =>
      expect(screen.getByText("No timeline events yet")).toBeInTheDocument(),
    );
    const init = fetchMock.mock.calls[0][1] as {
      headers: Record<string, string>;
    };
    expect(init.headers).toEqual({});
  });

  it("renders an explicit error state on a non-OK timeline response", async () => {
    mockFetch([], { ok: false, status: 401 });
    renderTab();

    await waitFor(() =>
      expect(screen.getByRole("alert")).toHaveTextContent(
        "Timeline request failed: HTTP 401",
      ),
    );
    expect(screen.queryByText("No timeline events yet")).not.toBeInTheDocument();
  });

  it("keeps the regular empty state on a 200 with no events", async () => {
    mockFetch([]);
    renderTab();

    await waitFor(() =>
      expect(screen.getByText("No timeline events yet")).toBeInTheDocument(),
    );
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  // [spring-ai-mcp-inspector PATCH] Error recovery (#316): auto-refresh keeps
  // polling after a failed poll, so the next successful poll must clear the
  // banner again; otherwise a recovered timeline stays stuck on the error
  // state and never shows events again until reload.
  it("clears the error banner and restores the events once a poll succeeds", async () => {
    jest.useFakeTimers();
    try {
      const fetchMock = jest
        .fn()
        .mockResolvedValueOnce({ ok: false, status: 401, json: async () => [] })
        .mockResolvedValue({
          ok: true,
          status: 200,
          json: async () => [APP_LOG_EVENT],
        });
      (global as unknown as { fetch: jest.Mock }).fetch = fetchMock;
      renderTab();

      // The mount poll fails (401: stand restart or token rotation) and the
      // banner surfaces instead of an empty log.
      await act(async () => {
        await Promise.resolve();
      });
      expect(screen.getByRole("alert")).toHaveTextContent(
        "Timeline request failed: HTTP 401",
      );

      // The next auto-refresh tick (3s) succeeds: the banner is cleared and
      // the fetched events render again.
      await act(async () => {
        jest.advanceTimersByTime(3000);
      });
      expect(screen.queryByRole("alert")).not.toBeInTheDocument();
      expect(screen.getByText("tool executed")).toBeInTheDocument();
    } finally {
      jest.useRealTimers();
    }
  });

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
        screen.getByText(/protocol: 2099-01-01 \? \(unknown\)/),
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

  // [spring-ai-mcp-inspector PATCH] Pitfalls panel wiring (PR #203 review):
  // the isError=true entry activates only when the timeline holds a
  // CallToolResult response with isError=true.
  it("activates the pitfalls isError entry when a tool-error response exists", async () => {
    mockFetch([VALIDATION_ERROR_RESPONSE_EVENT]);
    renderTab();

    await waitFor(() =>
      expect(screen.getByText("1 event")).toBeInTheDocument(),
    );
    fireEvent.click(screen.getByText("Common Spring AI 2.0 pitfalls"));
    expect(
      screen.getByText(/Detector active: check Timeline for isError=true/),
    ).toBeInTheDocument();
  });

  it("keeps the pitfalls isError entry static when all responses succeed", async () => {
    mockFetch([SUCCESS_CALL_RESPONSE_EVENT]);
    renderTab();

    await waitFor(() =>
      expect(screen.getByText("1 event")).toBeInTheDocument(),
    );
    fireEvent.click(screen.getByText("Common Spring AI 2.0 pitfalls"));
    expect(screen.queryByText(/Detector active:/)).not.toBeInTheDocument();
  });

  // [spring-ai-mcp-inspector PATCH] Pitfalls panel reachable from Timeline
  // (t_406cfdce).
  it("renders the Common Spring AI 2.0 pitfalls panel from the Timeline view", async () => {
    mockFetch([]);
    renderTab();

    await waitFor(() =>
      expect(
        screen.getByText("Common Spring AI 2.0 pitfalls"),
      ).toBeInTheDocument(),
    );

    fireEvent.click(screen.getByText("Common Spring AI 2.0 pitfalls"));
    expect(
      screen.getByText(
        /Historical, Spring AI 1\.1\.x only: recursive parameter types emitted unresolvable \$ref/,
      ),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("link", { name: /spring-projects\/spring-ai#5888/ }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("link", { name: /spring-projects\/spring-ai#6773/ }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("link", { name: /Upgrade Notes/ }),
    ).toBeInTheDocument();
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


// [spring-ai-mcp-inspector PATCH] SEP-2322 (spec revision 2026-07-28)
// Timeline chain rendering (#253): the original tools/call, its input_required
// result, the re-send, and the final result render as ONE collapsed chain,
// not scattered dead-end frames.
describe("TimelineTab SEP-2322 input_required chain", () => {
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

  const renderTab = (props: { sessionId?: string | null } = {}) =>
    render(
      <Tabs defaultValue="timeline">
        <TimelineTab {...props} />
      </Tabs>,
    );

  const chainEvents: WireEvent[] = [
    {
      id: "c-1",
      correlationId: "pair-1",
      sessionId: null,
      type: "MCP_JSONRPC_REQUEST",
      timestamp: "2026-08-30T12:00:01.000Z",
      payload: {
        jsonrpc: "2.0",
        id: 1,
        method: "tools/call",
        params: { name: "book_seat", arguments: { seat: "12A" } },
      },
    },
    {
      id: "c-2",
      correlationId: "pair-1",
      sessionId: null,
      type: "MCP_JSONRPC_RESPONSE",
      timestamp: "2026-08-30T12:00:02.000Z",
      payload: {
        jsonrpc: "2.0",
        id: 1,
        result: {
          resultType: "input_required",
          inputRequests: {
            "req-1": {
              method: "elicitation/create",
              params: { message: "Which seat?" },
            },
          },
          requestState: "opaque-1",
        },
      },
    },
    {
      id: "c-3",
      correlationId: "pair-2",
      sessionId: null,
      type: "MCP_JSONRPC_REQUEST",
      timestamp: "2026-08-30T12:00:05.000Z",
      payload: {
        jsonrpc: "2.0",
        id: 2,
        method: "tools/call",
        params: {
          name: "book_seat",
          arguments: { seat: "12A" },
          inputResponses: { "req-1": { action: "accept", content: {} } },
          requestState: "opaque-1",
        },
      },
    },
    {
      id: "c-4",
      correlationId: "pair-2",
      sessionId: null,
      type: "MCP_JSONRPC_RESPONSE",
      timestamp: "2026-08-30T12:00:06.000Z",
      payload: {
        jsonrpc: "2.0",
        id: 2,
        result: { content: [{ type: "text", text: "Booked." }] },
      },
    },
  ];

  it("renders the whole round-trip as ONE collapsed chain row", async () => {
    mockFetch(chainEvents);
    renderTab();

    await waitFor(() =>
      expect(screen.getByTestId("timeline-chain")).toBeInTheDocument(),
    );
    expect(
      screen.getByText("tools/call chain: book_seat"),
    ).toBeInTheDocument();
    expect(screen.getByTestId("timeline-chain-rounds").textContent).toBe(
      "2 rounds",
    );
    expect(screen.getByTestId("timeline-chain-state").textContent).toBe(
      "final result",
    );
    // Collapsed: the four member frames do not render standalone.
    expect(screen.queryAllByText("tools/call")).toHaveLength(0);
  });

  it("expands the chain to its chronological member frames", async () => {
    mockFetch(chainEvents);
    renderTab();

    await waitFor(() =>
      expect(screen.getByTestId("timeline-chain-header")).toBeInTheDocument(),
    );
    fireEvent.click(screen.getByTestId("timeline-chain-header"));

    // Both tools/call frames (original + re-send) are inside the chain.
    expect(screen.queryAllByText("tools/call")).toHaveLength(2);
  });

  it("badges a standalone input_required answer as actionable", async () => {
    mockFetch([chainEvents[1]]);
    renderTab();

    await waitFor(() =>
      expect(
        screen.getByTestId("timeline-input-required-badge"),
      ).toBeInTheDocument(),
    );
    expect(screen.queryByTestId("timeline-chain")).toBeNull();
  });

  it("shows awaiting-input on a chain whose last answer needs more input", async () => {
    // The re-send went out but its answer never arrived (or answered with
    // another input_required round that is not recorded yet): the chain's
    // chronologically last response is still the input_required frame.
    mockFetch(chainEvents.slice(0, 3));
    renderTab();

    await waitFor(() =>
      expect(screen.getByTestId("timeline-chain")).toBeInTheDocument(),
    );
    expect(screen.getByTestId("timeline-chain-state").textContent).toBe(
      "awaiting input",
    );
  });

  // [spring-ai-mcp-inspector PATCH] Keep-alive ping rendering tests (#235).

  const KEEP_ALIVE_EVENT: WireEvent = {
    id: "keepalive-1",
    correlationId: null,
    sessionId: "s-1",
    type: "KEEP_ALIVE",
    timestamp: "2026-09-20T12:00:05.000Z",
    payload: { method: "ping" },
  };

  it("renders KEEP_ALIVE events with distinct styling", async () => {
    mockFetch([KEEP_ALIVE_EVENT]);
    renderTab();

    await waitFor(() =>
      expect(screen.getByText("KEEP ALIVE")).toBeInTheDocument(),
    );
    // The row should have cyan styling - go up to the border-l-2 container
    const label = screen.getByText("KEEP ALIVE");
    const row = label.closest("div.border-l-2");
    expect(row).toHaveClass("text-cyan-400");
    expect(row).toHaveClass("bg-cyan-950/30");
  });

  it("renders KEEP_ALIVE events with timestamp and method", async () => {
    mockFetch([KEEP_ALIVE_EVENT]);
    renderTab();

    await waitFor(() =>
      expect(screen.getByText("KEEP ALIVE")).toBeInTheDocument(),
    );
    // The label should show the method from payload
    expect(screen.getByText("ping")).toBeInTheDocument();
  });

  // [spring-ai-mcp-inspector PATCH] The client-side merge is the only source
  // of KEEP_ALIVE rows: /api/timeline never returns them (#235), so the
  // fixtures above pin row styling only. These tests render TimelineTab with
  // a real useKeepAlive fed by the snapshot endpoint and a stubbed
  // EventSource, pinning the merge into fetched events and its timestamp
  // dedup (round-36 review blocker F1).

  class KeepAliveEventSourceStub {
    url: string;
    private listeners: Map<string, ((event: MessageEvent) => void)[]> = new Map();
    static instances: KeepAliveEventSourceStub[] = [];
    constructor(url: string) {
      this.url = url;
      KeepAliveEventSourceStub.instances.push(this);
    }
    addEventListener(type: string, listener: (event: MessageEvent) => void) {
      const listeners = this.listeners.get(type) ?? [];
      listeners.push(listener);
      this.listeners.set(type, listeners);
    }
    close() {}
    simulateEvent(type: string, data: string) {
      const event = { data } as MessageEvent;
      for (const listener of this.listeners.get(type) ?? []) {
        listener(event);
      }
    }
    static reset() {
      KeepAliveEventSourceStub.instances = [];
    }
  }

  const PING_T1 = "2026-08-30T12:00:03.000Z";
  const PING_T2 = "2026-08-30T12:00:05.000Z";
  const PING_T3 = "2026-08-30T12:00:07.000Z";

  const keepaliveSnapshot = (recentPings: string[]) => ({
    observed: true,
    lastPingAt: recentPings[recentPings.length - 1] ?? null,
    recentPings,
    estimatedIntervalMillis: 10000,
    isStale: false,
  });

  function mockFetchByPath(timeline: WireEvent[], keepalive: unknown) {
    const fetchMock = jest.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes("/api/keepalive")) {
        return { ok: true, json: async () => keepalive };
      }
      if (url.includes("/api/timeline")) {
        return { ok: true, json: async () => timeline };
      }
      throw new Error(`unexpected fetch: ${url}`);
    });
    (global as unknown as { fetch: jest.Mock }).fetch = fetchMock;
    return fetchMock;
  }

  const keepAliveRowIndexes = (container: HTMLElement) =>
    Array.from(container.querySelectorAll("div.border-l-2"))
      .map((row, index) => ({
        index,
        isKeepAlive: row.textContent?.includes("KEEP ALIVE") ?? false,
      }))
      .filter((row) => row.isKeepAlive)
      .map((row) => row.index);

  function stubEventSource() {
    KeepAliveEventSourceStub.reset();
    (global as unknown as { EventSource: typeof KeepAliveEventSourceStub })
      .EventSource = KeepAliveEventSourceStub;
  }

  it("merges hook pings into fetched events as KEEP_ALIVE rows", async () => {
    stubEventSource();
    mockFetchByPath(
      [APP_LOG_EVENT, REQUEST_EVENT],
      keepaliveSnapshot([PING_T1, PING_T2]),
    );
    const { container } = renderTab({ sessionId: "s-1" });

    // 2 fetched events + 2 merged pings.
    await waitFor(() =>
      expect(screen.getByText("4 events")).toBeInTheDocument(),
    );

    // Merged rows sit in chronological position between the fetched events
    // (APP_LOG 12:00:00, request 12:00:01, pings 12:00:03 and 12:00:05):
    // the first two rows are the fetched ones, the last two are the pings.
    const rows = Array.from(container.querySelectorAll("div.border-l-2"));
    expect(rows).toHaveLength(4);
    expect(rows[0].textContent).toContain("APP LOG");
    expect(rows[1].textContent).toContain("tools/list");
    expect(keepAliveRowIndexes(container)).toEqual([2, 3]);
    expect(screen.getAllByText("KEEP ALIVE")).toHaveLength(2);
  });

  it("deduplicates repeated ping timestamps across snapshot and live SSE", async () => {
    stubEventSource();
    mockFetchByPath([REQUEST_EVENT], keepaliveSnapshot([PING_T1, PING_T2]));
    const { container } = renderTab({ sessionId: "s-1" });

    await waitFor(() =>
      expect(screen.getByText("3 events")).toBeInTheDocument(),
    );

    await waitFor(() =>
      expect(KeepAliveEventSourceStub.instances).toHaveLength(1),
    );

    // A replay of an already-merged ping (snapshot refresh, SSE reconnect
    // backfill) must not duplicate the row.
    await act(async () => {
      KeepAliveEventSourceStub.instances[0].simulateEvent(
        "mcp:keepalive-ping",
        JSON.stringify({ at: PING_T1 }),
      );
    });
    expect(screen.getByText("3 events")).toBeInTheDocument();
    expect(keepAliveRowIndexes(container)).toEqual([1, 2]);

    // A genuinely new ping still merges.
    await act(async () => {
      KeepAliveEventSourceStub.instances[0].simulateEvent(
        "mcp:keepalive-ping",
        JSON.stringify({ at: PING_T3 }),
      );
    });
    expect(screen.getByText("4 events")).toBeInTheDocument();
    expect(screen.getAllByText("KEEP ALIVE")).toHaveLength(3);
  });
});

// [spring-ai-mcp-inspector PATCH] Client-traffic badge and side filter (#120):
// frames captured from the configured MCP clients carry side=client and must
// be visually distinct in the log and filterable client-side.
const CLIENT_REQUEST_EVENT: WireEvent = {
  id: "evt-c1",
  correlationId: "client-corr-1",
  sessionId: null,
  type: "MCP_JSONRPC_REQUEST",
  timestamp: "2026-08-30T12:00:06.000Z",
  payload: {
    side: "client",
    clientName: "demo",
    direction: "out",
    transportType: "streamable-http",
    target: "https://***@mcp.example.com/mcp",
    jsonrpc: "2.0",
    id: 10,
    method: "tools/list",
    params: {},
  },
};

describe("TimelineTab client traffic", () => {
  const originalFetch = global.fetch;
  const originalBootstrap = (window as unknown as Record<string, unknown>)[
    "__MCP_INSPECTOR_BOOTSTRAP"
  ];

  afterEach(() => {
    global.fetch = originalFetch;
    (window as unknown as Record<string, unknown>)["__MCP_INSPECTOR_BOOTSTRAP"] =
      originalBootstrap;
  });

  const renderTab = () =>
    render(
      <Tabs defaultValue="timeline">
        <TimelineTab />
      </Tabs>,
    );

  it(
    "badges side=client rows with the client name and leaves server rows unbadged",
    async () => {
      mockFetch([REQUEST_EVENT, CLIENT_REQUEST_EVENT]);
      renderTab();

      await waitFor(() =>
        expect(screen.getByText("2 events")).toBeInTheDocument(),
      );

      // Only the side=client row carries the provenance badge; the proxied
      // server row stays unbadged.
      const badges = screen.getAllByTestId("timeline-side-badge");
      expect(badges).toHaveLength(1);
      expect(badges[0]).toHaveTextContent("client: demo");
      expect(badges[0]).toHaveTextContent("→");
    },
  );

  it(
    "side filter chips come from the unfiltered event set and both sides stay selectable",
    async () => {
      mockFetch([REQUEST_EVENT, CLIENT_REQUEST_EVENT]);
      renderTab();

      await waitFor(() =>
        expect(screen.getByText("2 events")).toBeInTheDocument(),
      );

      // "all" is the default: both rows visible, both side chips present.
      expect(screen.getByTestId("timeline-side-filter-all")).toBeInTheDocument();
      expect(screen.getByTestId("timeline-side-filter-client")).toBeInTheDocument();
      expect(screen.getByTestId("timeline-side-filter-server")).toBeInTheDocument();
      expect(screen.getAllByText("tools/list")).toHaveLength(2);

      // client: the server row leaves the log, the badged client row stays.
      fireEvent.click(screen.getByTestId("timeline-side-filter-client"));
      expect(screen.getAllByTestId("timeline-side-badge")).toHaveLength(1);
      expect(screen.getAllByText("tools/list")).toHaveLength(1);

      // The server chip must still be present after selecting client:
      // options derive from the UNFILTERED event set (#142 lesson 4).
      fireEvent.click(screen.getByTestId("timeline-side-filter-server"));
      expect(screen.queryByTestId("timeline-side-badge")).not.toBeInTheDocument();
      expect(screen.getAllByText("tools/list")).toHaveLength(1);

      // back to all: both rows return.
      fireEvent.click(screen.getByTestId("timeline-side-filter-all"));
      expect(screen.getAllByText("tools/list")).toHaveLength(2);
      expect(screen.getAllByTestId("timeline-side-badge")).toHaveLength(1);
    },
  );
});
