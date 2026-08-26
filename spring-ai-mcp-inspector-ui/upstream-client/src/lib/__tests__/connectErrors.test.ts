import {
  ConnectFailedError,
  CONNECT_FAILED_ERROR_CODE,
  connectionFailureFromError,
  humanReadableReason,
  isConnectFailedError,
  parseConnectFailureResponse,
  type ConnectFailure,
} from "../connectErrors";

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
});

describe("humanReadableReason", () => {
  it("maps every reason to a human-readable label", () => {
    expect(humanReadableReason("timeout")).toContain("timed out");
    expect(humanReadableReason("connection_refused")).toContain("refused");
    expect(humanReadableReason("dns")).toContain("DNS");
    expect(humanReadableReason("unknown")).toContain("Unknown");
  });
});
