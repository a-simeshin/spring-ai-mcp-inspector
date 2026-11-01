import {
  ConnectFailedError,
  CONNECT_FAILED_ERROR_CODE,
  connectionFailureFromError,
  humanReadableReason,
  isConnectFailedError,
  parseConnectFailureResponse,
  parseProxyErrorEvent,
  parseTerminalReconnectMillis,
  type ConnectFailure,
} from "../connectErrors";
import { McpError, ErrorCode } from "@modelcontextprotocol/sdk/types.js";

/** Minimal Response-shaped object; the parser only touches clone().json(). */
const responseWithBody = (body: unknown): Response => {
  const clone = () => ({ json: async () => body });
  return { clone } as unknown as Response;
};

const refusedFailure: ConnectFailure = {
  code: CONNECT_FAILED_ERROR_CODE,
  reason: "connection_refused",
  message: "Connection refused",
  retryable: true,
};

describe("parseConnectFailureResponse", () => {
  it("parses a valid MCP_CONNECT_FAILED body", async () => {
    const failure = await parseConnectFailureResponse(
      responseWithBody({
        error: {
          code: "MCP_CONNECT_FAILED",
          reason: "connection_refused",
          message: "Connection refused: connect",
          retryable: true,
        },
      }),
    );

    expect(failure).toEqual({
      code: "MCP_CONNECT_FAILED",
      reason: "connection_refused",
      message: "Connection refused: connect",
      retryable: true,
    });
  });

  it("returns null for non-JSON bodies", async () => {
    const failure = await parseConnectFailureResponse(
      responseWithBody("not json"),
    );
    expect(failure).toBeNull();
  });

  it("returns null for bodies without the error contract", async () => {
    expect(
      await parseConnectFailureResponse(responseWithBody({ status: 502 })),
    ).toBeNull();
    expect(
      await parseConnectFailureResponse(
        responseWithBody({ error: { code: "SOMETHING_ELSE" } }),
      ),
    ).toBeNull();
  });

  it("returns null for unknown reasons", async () => {
    expect(
      await parseConnectFailureResponse(
        responseWithBody({
          error: { code: "MCP_CONNECT_FAILED", reason: "bogus" },
        }),
      ),
    ).toBeNull();
  });

  it("applies defaults for missing message/retryable", async () => {
    const failure = await parseConnectFailureResponse(
      responseWithBody({
        error: { code: "MCP_CONNECT_FAILED", reason: "timeout" },
      }),
    );

    expect(failure).toEqual({
      code: "MCP_CONNECT_FAILED",
      reason: "timeout",
      message: "Failed to connect to the MCP server",
      retryable: true,
    });
  });
});

describe("ConnectFailedError", () => {
  it("carries the structured failure fields", () => {
    const error = new ConnectFailedError(refusedFailure);

    expect(isConnectFailedError(error)).toBe(true);
    expect(error.code).toBe("MCP_CONNECT_FAILED");
    expect(error.reason).toBe("connection_refused");
    expect(error.retryable).toBe(true);
    expect(error.message).toBe("Connection refused");
  });

  it("rejects cross-type errors", () => {
    expect(isConnectFailedError(new Error("Connection refused"))).toBe(false);
    expect(isConnectFailedError(null)).toBe(false);
  });
});

describe("connectionFailureFromError", () => {
  it("keeps structured failures as-is", () => {
    expect(connectionFailureFromError(new ConnectFailedError(refusedFailure))).toEqual(
      refusedFailure,
    );
  });

  it("maps generic errors to unknown reason with their message", () => {
    expect(
      connectionFailureFromError(new Error("Failed to fetch")),
    ).toEqual({
      code: "MCP_CONNECT_FAILED",
      reason: "unknown",
      message: "Failed to fetch",
      retryable: true,
    });
  });

  it("stringifies non-Error throwables", () => {
    expect(connectionFailureFromError("boom")).toEqual({
      code: "MCP_CONNECT_FAILED",
      reason: "unknown",
      message: "boom",
      retryable: true,
    });
  });

  // [spring-ai-mcp-inspector PATCH] -32001 is the SDK request-timeout code;
  // classifying it as 'timeout' is what makes the Reset session button
  // appear. Refs #157.
  it("maps McpError with code -32001 to timeout reason", () => {
    const timeoutError = new McpError(ErrorCode.RequestTimeout, "Request timed out");
    const failure = connectionFailureFromError(timeoutError);
    expect(failure.reason).toBe("timeout");
    expect(failure.message).toContain("Request timed out");
    expect(failure.retryable).toBe(true);
  });

  it("keeps unknown reason for other McpError codes", () => {
    const otherError = new McpError(ErrorCode.InternalError, "Internal error");
    const failure = connectionFailureFromError(otherError);
    expect(failure.reason).toBe("unknown");
  });
});

describe("humanReadableReason", () => {
  it("maps every reason to a human-readable label", () => {
    expect(humanReadableReason("timeout")).toBe("POST /mcp request timed out");
    expect(humanReadableReason("connection_refused")).toContain("refused");
    expect(humanReadableReason("dns")).toContain("DNS");
    expect(humanReadableReason("unknown")).toContain("Unknown");
  });
});

describe("[spring-ai-mcp-inspector PATCH] parseTerminalReconnectMillis (block2 terminal SSE retry)", () => {
  it("parses the terminal retry the proxy sends for a dead upstream", () => {
    expect(parseTerminalReconnectMillis("retry:2147483000")).toBe(2147483000);
  });

  it("ignores ordinary short retry values (transport hiccups keep reconnecting)", () => {
    expect(parseTerminalReconnectMillis("retry:3000")).toBeNull();
    expect(parseTerminalReconnectMillis("retry:10000")).toBeNull();
    expect(parseTerminalReconnectMillis("retry:3599999")).toBeNull();
  });

  it("ignores malformed values and non-retry lines", () => {
    expect(parseTerminalReconnectMillis("retry:not-a-number")).toBeNull();
    expect(parseTerminalReconnectMillis("retry:")).toBeNull();
    expect(parseTerminalReconnectMillis("event: error")).toBeNull();
    expect(parseTerminalReconnectMillis("data: {}")).toBeNull();
    expect(parseTerminalReconnectMillis("")).toBeNull();
  });
});

describe("[spring-ai-mcp-inspector PATCH] parseProxyErrorEvent (block3 readable SSE error event)", () => {
  it("parses the simple message+reason payload of the proxy error event", () => {
    const failure = parseProxyErrorEvent(
      JSON.stringify({
        message: "The upstream server is no longer reachable",
        reason: "connection_refused",
      }),
    );
    expect(failure).toEqual({
      code: "MCP_CONNECT_FAILED",
      reason: "connection_refused",
      message: "The upstream server is no longer reachable",
      retryable: true,
    });
  });

  it("maps an unrecognized reason onto unknown but keeps the message", () => {
    const failure = parseProxyErrorEvent(
      JSON.stringify({ message: "stream died", reason: "on_fire" }),
    );
    expect(failure).toMatchObject({ reason: "unknown", message: "stream died" });
  });

  it("returns null for anything that is not a message+reason object", () => {
    expect(parseProxyErrorEvent("not json")).toBeNull();
    expect(parseProxyErrorEvent("[]")).toBeNull();
    expect(parseProxyErrorEvent('"a string"')).toBeNull();
    expect(parseProxyErrorEvent(JSON.stringify({ reason: "dns" }))).toBeNull();
    expect(parseProxyErrorEvent(JSON.stringify({ message: "" }))).toBeNull();
  });
});
