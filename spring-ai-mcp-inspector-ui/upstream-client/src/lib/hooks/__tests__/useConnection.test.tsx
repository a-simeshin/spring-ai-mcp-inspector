import { renderHook, act } from "@testing-library/react";
import { useConnection } from "../useConnection";
import { z } from "zod/v3";

// [spring-ai-mcp-inspector PATCH] history-error-styling: hook-level tests for
// isError producer-path in requestHistory (#195).
import {
  ClientRequest,
  CreateTaskResultSchema,
  ErrorCode,
  JSONRPCMessage,
  McpError,
  ServerCapabilities,
} from "@modelcontextprotocol/sdk/types.js";
import type {
  AnySchema,
  SchemaOutput,
} from "@modelcontextprotocol/sdk/server/zod-compat.js";
import {
  DEFAULT_INSPECTOR_CONFIG,
  CLIENT_IDENTITY,
  MCP_PROXY_TRANSPORT_ERROR_CODE,
} from "../../constants";
import {
  SSEClientTransportOptions,
  SseError,
} from "@modelcontextprotocol/sdk/client/sse.js";
import {
  ElicitResult,
  ElicitRequest,
} from "@modelcontextprotocol/sdk/types.js";
// [spring-ai-mcp-inspector PATCH] Regression tests for connect state
// hygiene (NOTICE.d/stale-connect-status-fix.txt).
import {
  auth,
  UnauthorizedError,
} from "@modelcontextprotocol/sdk/client/auth.js";
import { discoverScopes } from "../../auth";
import { CustomHeaders } from "../../types/customHeaders";

// Mock fetch
global.fetch = jest.fn().mockResolvedValue({
  json: () => Promise.resolve({ status: "ok" }),
  headers: {
    get: jest.fn().mockReturnValue(null),
  },
});

// Mock the SDK dependencies
const mockRequest = jest.fn().mockResolvedValue({ test: "response" });
const mockClient = {
  request: mockRequest,
  notification: jest.fn(),
  connect: jest.fn().mockResolvedValue(undefined),
  close: jest.fn().mockResolvedValue(undefined),
  getServerCapabilities: jest.fn(),
  getServerVersion: jest.fn(),
  getInstructions: jest.fn(),
  setNotificationHandler: jest.fn(),
  setRequestHandler: jest.fn(),
};

// Mock transport instances
const mockSSETransport: {
  start: jest.Mock;
  url: URL | undefined;
  options: SSEClientTransportOptions | undefined;
  onmessage?: (message: JSONRPCMessage) => void;
} = {
  start: jest.fn(),
  url: undefined,
  options: undefined,
  onmessage: undefined,
};

const mockStreamableHTTPTransport: {
  start: jest.Mock;
  url: URL | undefined;
  options: SSEClientTransportOptions | undefined;
} = {
  start: jest.fn(),
  url: undefined,
  options: undefined,
};

jest.mock("@modelcontextprotocol/sdk/client/index.js", () => ({
  Client: jest.fn().mockImplementation(() => mockClient),
}));

jest.mock("@modelcontextprotocol/sdk/client/sse.js", () => {
  // Minimal mock class that supports instanceof checks
  class SseError extends Error {
    code: number;
    event: ErrorEvent;
    constructor(code: number, message: string, event: ErrorEvent) {
      super(message);
      this.code = code;
      this.event = event;
    }
  }

  return {
    SSEClientTransport: jest.fn((url, options) => {
      mockSSETransport.url = url;
      mockSSETransport.options = options;
      return mockSSETransport;
    }),
    SseError,
  };
});

jest.mock("@modelcontextprotocol/sdk/client/streamableHttp.js", () => {
  class StreamableHTTPError extends Error {
    code: number;
    constructor(code: number, message: string) {
      super(`Streamable HTTP error: ${message}`);
      this.code = code;
    }
  }

  return {
    StreamableHTTPError,
    StreamableHTTPClientTransport: jest.fn((url, options) => {
      mockStreamableHTTPTransport.url = url;
      mockStreamableHTTPTransport.options = options;
      mockStreamableHTTPTransport.terminateSession = jest
        .fn()
        .mockResolvedValue(undefined);
      return mockStreamableHTTPTransport;
    }),
  };
});

jest.mock("@modelcontextprotocol/sdk/client/auth.js", () => {
  class UnauthorizedError extends Error {
    constructor(message?: string) {
      super(message ?? "Unauthorized");
      this.name = "UnauthorizedError";
    }
  }

  return {
    UnauthorizedError,
    auth: jest.fn().mockResolvedValue("AUTHORIZED"),
  };
});

// Mock the toast hook
const mockToast = jest.fn();
jest.mock("@/lib/hooks/useToast", () => ({
  useToast: () => ({
    toast: mockToast,
  }),
}));

// Mock the auth provider
jest.mock("../../auth", () => ({
  InspectorOAuthClientProvider: jest.fn().mockImplementation(() => ({
    tokens: jest.fn().mockResolvedValue({ access_token: "mock-token" }),
    redirectUrl: "http://localhost:3000/oauth/callback",
    clear: jest.fn(),
  })),
  clearClientInformationFromSessionStorage: jest.fn(),
  saveClientInformationToSessionStorage: jest.fn(),
  saveScopeToSessionStorage: jest.fn(),
  clearScopeFromSessionStorage: jest.fn(),
  discoverScopes: jest.fn(),
  // Deliberately the real implementation: stubbing it to undefined made every
  // `toAbsoluteServerUrl(sseUrl)` call throw, which the surrounding try/catch
  // swallowed, so the resource-metadata discovery branch was never actually
  // exercised by these tests.
  toAbsoluteServerUrl: jest.requireActual("../../auth").toAbsoluteServerUrl,
}));

const mockAuth = auth as jest.MockedFunction<typeof auth>;
const mockDiscoverScopes = discoverScopes as jest.MockedFunction<
  typeof discoverScopes
>;

describe("useConnection", () => {
  // [spring-ai-mcp-inspector PATCH] Some PR #166 regression tests assign
  // mockClient.setLoggingLevel directly; jest.clearAllMocks() does not remove
  // own properties added to the shared mock instance, which poisoned later
  // tests that rely on setLoggingLevel being absent (randomize: true runs
  // them in any order). Remove it after every test.
  afterEach(() => {
    delete (mockClient as { setLoggingLevel?: unknown }).setLoggingLevel;
  });

  const defaultProps: Parameters<typeof useConnection>[0] = {
    transportType: "sse" as const,
    command: "",
    args: "",
    sseUrl: "http://localhost:8080",
    env: {},
    config: DEFAULT_INSPECTOR_CONFIG,
  };

  describe("Request Configuration", () => {
    beforeEach(() => {
      jest.clearAllMocks();
    });

    test("uses the default config values in makeRequest", async () => {
      const { result } = renderHook(() => useConnection(defaultProps));

      // Connect the client
      await act(async () => {
        await result.current.connect();
      });

      // Wait for state update
      await act(async () => {
        await new Promise((resolve) => setTimeout(resolve, 0));
      });

      const mockRequest: ClientRequest = {
        method: "ping",
        params: {},
      };

      const mockSchema = z.object({
        test: z.string(),
      });

      const mockSchemaAny: AnySchema = mockSchema as unknown as AnySchema;

      await act(async () => {
        await result.current.makeRequest(mockRequest, mockSchemaAny);
      });

      expect(mockClient.request).toHaveBeenCalledWith(
        mockRequest,
        mockSchema,
        expect.objectContaining({
          timeout: DEFAULT_INSPECTOR_CONFIG.MCP_SERVER_REQUEST_TIMEOUT.value,
          maxTotalTimeout:
            DEFAULT_INSPECTOR_CONFIG.MCP_REQUEST_MAX_TOTAL_TIMEOUT.value,
          resetTimeoutOnProgress:
            DEFAULT_INSPECTOR_CONFIG.MCP_REQUEST_TIMEOUT_RESET_ON_PROGRESS
              .value,
        }),
      );
    });

    test("overrides the default config values when passed in options in makeRequest", async () => {
      const { result } = renderHook(() => useConnection(defaultProps));

      // Connect the client
      await act(async () => {
        await result.current.connect();
      });

      // Wait for state update
      await act(async () => {
        await new Promise((resolve) => setTimeout(resolve, 0));
      });

      const mockRequest: ClientRequest = {
        method: "ping",
        params: {},
      };

      const mockSchema = z.object({
        test: z.string(),
      });

      const mockSchemaAny: AnySchema = mockSchema as unknown as AnySchema;

      await act(async () => {
        await result.current.makeRequest(mockRequest, mockSchemaAny, {
          timeout: 1000,
          maxTotalTimeout: 2000,
          resetTimeoutOnProgress: false,
        });
      });

      expect(mockClient.request).toHaveBeenCalledWith(
        mockRequest,
        mockSchema,
        expect.objectContaining({
          timeout: 1000,
          maxTotalTimeout: 2000,
          resetTimeoutOnProgress: false,
        }),
      );
    });
  });

  describe("Receiver-side Tasks (task-augmented incoming requests)", () => {
    beforeEach(() => {
      jest.clearAllMocks();
    });

    test("declares tasks.requests.sampling.createMessage when onPendingRequest is provided", async () => {
      const Client = jest.requireMock(
        "@modelcontextprotocol/sdk/client/index.js",
      ).Client;

      const propsWithPending = {
        ...defaultProps,
        onPendingRequest: jest.fn(),
      };

      const { result } = renderHook(() => useConnection(propsWithPending));

      await act(async () => {
        await result.current.connect();
      });

      expect(Client).toHaveBeenCalledWith(
        expect.any(Object),
        expect.objectContaining({
          capabilities: expect.objectContaining({
            tasks: expect.objectContaining({
              requests: expect.objectContaining({
                sampling: expect.objectContaining({
                  createMessage: {},
                }),
              }),
            }),
          }),
        }),
      );
    });

    test("task-augmented sampling/createMessage returns { task } and tasks/result blocks until resolved", async () => {
      let pendingResolve: ((value: unknown) => void) | undefined;
      let pendingReject: ((reason?: unknown) => void) | undefined;

      const mockOnPendingRequest = jest.fn((_request, resolve, reject) => {
        pendingResolve = resolve;
        pendingReject = reject;
      });

      const propsWithPending = {
        ...defaultProps,
        onPendingRequest: mockOnPendingRequest,
      };

      const { result } = renderHook(() => useConnection(propsWithPending));

      await act(async () => {
        await result.current.connect();
      });

      const samplingRequest = {
        method: "sampling/createMessage",
        params: {
          task: { ttl: 0 },
          messages: [
            {
              role: "user",
              content: { type: "text", text: "hello" },
            },
          ],
          maxTokens: 1,
        },
      };

      // Locate the sampling/createMessage handler
      const samplingHandlerCall = mockClient.setRequestHandler.mock.calls.find(
        (call) => {
          try {
            const schema = call[0];
            const parseResult =
              schema.safeParse && schema.safeParse(samplingRequest);
            return parseResult?.success;
          } catch {
            return false;
          }
        },
      );

      expect(samplingHandlerCall).toBeDefined();
      const [, samplingHandler] = samplingHandlerCall;

      // Invoke handler; should return a CreateTaskResult immediately
      let createTaskResult!: SchemaOutput<typeof CreateTaskResultSchema>;
      await act(async () => {
        createTaskResult = await samplingHandler(samplingRequest);
      });

      expect(createTaskResult).toHaveProperty("task");
      expect(createTaskResult.task).toEqual(
        expect.objectContaining({
          taskId: expect.any(String),
          status: "input_required",
          ttl: 0,
          createdAt: expect.any(String),
          lastUpdatedAt: expect.any(String),
        }),
      );

      expect(mockOnPendingRequest).toHaveBeenCalledTimes(1);
      expect(pendingResolve).toBeDefined();
      expect(pendingReject).toBeDefined();

      const taskId = createTaskResult.task.taskId as string;

      // Locate tasks/get and tasks/result handlers
      const taskGetRequest = { method: "tasks/get", params: { taskId } };
      const taskResultRequest = { method: "tasks/result", params: { taskId } };

      const taskGetHandlerCall = mockClient.setRequestHandler.mock.calls.find(
        (call) => {
          try {
            const schema = call[0];
            const parseResult =
              schema.safeParse && schema.safeParse(taskGetRequest);
            return parseResult?.success;
          } catch {
            return false;
          }
        },
      );
      const taskResultHandlerCall =
        mockClient.setRequestHandler.mock.calls.find((call) => {
          try {
            const schema = call[0];
            const parseResult =
              schema.safeParse && schema.safeParse(taskResultRequest);
            return parseResult?.success;
          } catch {
            return false;
          }
        });

      expect(taskGetHandlerCall).toBeDefined();
      expect(taskResultHandlerCall).toBeDefined();

      const [, taskGetHandler] = taskGetHandlerCall;
      const [, taskResultHandler] = taskResultHandlerCall;

      // Verify tasks/get sees the in-progress task
      const getBefore = await taskGetHandler(taskGetRequest);
      expect(getBefore.status).toBe("input_required");

      // tasks/result should block until user flow resolves
      const payloadPromise = taskResultHandler(taskResultRequest);
      const race = await Promise.race([
        payloadPromise.then(() => "resolved"),
        new Promise((r) => setTimeout(() => r("timeout"), 10)),
      ]);
      expect(race).toBe("timeout");

      const mockPayload = {
        model: "test-model",
        role: "assistant",
        content: { type: "text", text: "ok" },
      };

      await act(async () => {
        pendingResolve!(mockPayload);
        // Let the background updater run
        await new Promise((r) => setTimeout(r, 0));
      });

      await expect(payloadPromise).resolves.toEqual(mockPayload);

      const getAfter = await taskGetHandler(taskGetRequest);
      expect(getAfter.status).toBe("completed");
    });

    test("task-augmented elicitation/create returns { task } immediately", async () => {
      const mockOnElicitationRequest = jest.fn();
      const propsWithElicitation = {
        ...defaultProps,
        onElicitationRequest: mockOnElicitationRequest,
      };

      const { result } = renderHook(() => useConnection(propsWithElicitation));

      await act(async () => {
        await result.current.connect();
      });

      const elicitationRequest = {
        method: "elicitation/create",
        params: {
          task: { ttl: 0 },
          message: "Please provide your name",
          requestedSchema: {
            type: "object",
            properties: {
              name: { type: "string" },
            },
            required: ["name"],
          },
        },
      };

      const elicitRequestHandlerCall =
        mockClient.setRequestHandler.mock.calls.find((call) => {
          try {
            const schema = call[0];
            const parseResult =
              schema.safeParse && schema.safeParse(elicitationRequest);
            return parseResult?.success;
          } catch {
            return false;
          }
        });

      expect(elicitRequestHandlerCall).toBeDefined();
      const [, handler] = elicitRequestHandlerCall!;

      mockOnElicitationRequest.mockImplementation((_request, resolve) => {
        resolve({ action: "accept", content: { name: "test" } });
      });

      const resultValue = await handler(elicitationRequest);

      expect(resultValue).toHaveProperty("task");
      expect(resultValue.task).toEqual(
        expect.objectContaining({
          taskId: expect.any(String),
          status: "input_required",
          ttl: 0,
        }),
      );
    });
  });

  test("throws error when mcpClient is not connected", async () => {
    const { result } = renderHook(() => {
      const { makeRequest } = useConnection(defaultProps) as unknown as {
        makeRequest: (
          request: ClientRequest,
          schema: AnySchema,
        ) => Promise<unknown>;
      };
      return { makeRequest };
    });

    const mockRequest: ClientRequest = {
      method: "ping",
      params: {},
    };

    const mockSchema = z.object({
      test: z.string(),
    });

    const mockSchemaAny: AnySchema = mockSchema as unknown as AnySchema;

    await expect(
      result.current.makeRequest(mockRequest, mockSchemaAny),
    ).rejects.toThrow("MCP client not connected");
  });

  describe("Elicitation Support", () => {
    beforeEach(() => {
      jest.clearAllMocks();
    });

    test("declares elicitation capability during client initialization", async () => {
      const Client = jest.requireMock(
        "@modelcontextprotocol/sdk/client/index.js",
      ).Client;

      const { result } = renderHook(() => useConnection(defaultProps));

      await act(async () => {
        await result.current.connect();
      });

      expect(Client).toHaveBeenCalledWith(
        expect.objectContaining({
          name: CLIENT_IDENTITY.name,
          version: CLIENT_IDENTITY.version,
        }),
        expect.objectContaining({
          capabilities: expect.objectContaining({
            elicitation: {},
          }),
        }),
      );
    });

    test("sets up elicitation request handler when onElicitationRequest is provided", async () => {
      const mockOnElicitationRequest = jest.fn();
      const propsWithElicitation = {
        ...defaultProps,
        onElicitationRequest: mockOnElicitationRequest,
      };

      const { result } = renderHook(() => useConnection(propsWithElicitation));

      await act(async () => {
        await result.current.connect();
      });

      const elicitRequestHandlerCall =
        mockClient.setRequestHandler.mock.calls.find((call) => {
          try {
            const schema = call[0];
            const testRequest = {
              method: "elicitation/create",
              params: {
                message: "test message",
                requestedSchema: {
                  type: "object",
                  properties: {
                    name: { type: "string" },
                  },
                },
              },
            };
            const parseResult =
              schema.safeParse && schema.safeParse(testRequest);
            return parseResult?.success;
          } catch {
            return false;
          }
        });

      expect(elicitRequestHandlerCall).toBeDefined();
      expect(mockClient.setRequestHandler).toHaveBeenCalledWith(
        expect.any(Object),
        expect.any(Function),
      );
    });

    test("does not set up elicitation request handler when onElicitationRequest is not provided", async () => {
      const { result } = renderHook(() => useConnection(defaultProps));

      await act(async () => {
        await result.current.connect();
      });

      const elicitRequestHandlerCall =
        mockClient.setRequestHandler.mock.calls.find((call) => {
          try {
            const schema = call[0];
            const testRequest = {
              method: "elicitation/create",
              params: {
                message: "test message",
                requestedSchema: {
                  type: "object",
                  properties: {
                    name: { type: "string" },
                  },
                },
              },
            };
            const parseResult =
              schema.safeParse && schema.safeParse(testRequest);
            return parseResult?.success;
          } catch {
            return false;
          }
        });

      expect(elicitRequestHandlerCall).toBeUndefined();
    });

    test("elicitation request handler calls onElicitationRequest callback", async () => {
      const mockOnElicitationRequest = jest.fn();
      const propsWithElicitation = {
        ...defaultProps,
        onElicitationRequest: mockOnElicitationRequest,
      };

      const { result } = renderHook(() => useConnection(propsWithElicitation));

      await act(async () => {
        await result.current.connect();
      });

      const elicitRequestHandlerCall =
        mockClient.setRequestHandler.mock.calls.find((call) => {
          try {
            const schema = call[0];
            const testRequest = {
              method: "elicitation/create",
              params: {
                message: "test message",
                requestedSchema: {
                  type: "object",
                  properties: {
                    name: { type: "string" },
                  },
                },
              },
            };
            const parseResult =
              schema.safeParse && schema.safeParse(testRequest);
            return parseResult?.success;
          } catch {
            return false;
          }
        });

      expect(elicitRequestHandlerCall).toBeDefined();
      const [, handler] = elicitRequestHandlerCall!;

      const mockElicitationRequest: ElicitRequest = {
        method: "elicitation/create",
        params: {
          message: "Please provide your name",
          requestedSchema: {
            type: "object",
            properties: {
              name: { type: "string" },
            },
            required: ["name"],
          },
        },
      };

      mockOnElicitationRequest.mockImplementation((_request, resolve) => {
        resolve({ action: "accept", content: { name: "test" } });
      });

      await act(async () => {
        await handler(mockElicitationRequest);
      });

      expect(mockOnElicitationRequest).toHaveBeenCalledWith(
        mockElicitationRequest,
        expect.any(Function),
      );
    });

    test("elicitation request handler returns a promise that resolves with the callback result", async () => {
      const mockOnElicitationRequest = jest.fn();
      const propsWithElicitation = {
        ...defaultProps,
        onElicitationRequest: mockOnElicitationRequest,
      };

      const { result } = renderHook(() => useConnection(propsWithElicitation));

      await act(async () => {
        await result.current.connect();
      });

      const elicitRequestHandlerCall =
        mockClient.setRequestHandler.mock.calls.find((call) => {
          try {
            const schema = call[0];
            const testRequest = {
              method: "elicitation/create",
              params: {
                message: "test message",
                requestedSchema: {
                  type: "object",
                  properties: {
                    name: { type: "string" },
                  },
                },
              },
            };
            const parseResult =
              schema.safeParse && schema.safeParse(testRequest);
            return parseResult?.success;
          } catch {
            return false;
          }
        });

      expect(elicitRequestHandlerCall).toBeDefined();
      const [, handler] = elicitRequestHandlerCall!;

      const mockElicitationRequest: ElicitRequest = {
        method: "elicitation/create",
        params: {
          message: "Please provide your name",
          requestedSchema: {
            type: "object",
            properties: {
              name: { type: "string" },
            },
            required: ["name"],
          },
        },
      };

      const mockResponse: ElicitResult = {
        action: "accept",
        content: { name: "John Doe" },
      };

      mockOnElicitationRequest.mockImplementation((_request, resolve) => {
        resolve(mockResponse);
      });

      let handlerResult!: ElicitResult;
      await act(async () => {
        handlerResult = await handler(mockElicitationRequest);
      });

      expect(handlerResult).toEqual(mockResponse);
    });
  });

  describe("Ref Resolution", () => {
    beforeEach(() => {
      jest.clearAllMocks();
    });

    test("resolves $ref references in requestedSchema properties before validation", async () => {
      const mockProtocolOnMessage = jest.fn();

      mockSSETransport.onmessage = mockProtocolOnMessage;

      const { result } = renderHook(() => useConnection(defaultProps));

      await act(async () => {
        await result.current.connect();
      });

      const mockRequestWithRef: JSONRPCMessage = {
        jsonrpc: "2.0",
        id: 1,
        method: "elicitation/create",
        params: {
          message: "Please provide your information",
          requestedSchema: {
            type: "object",
            properties: {
              source: {
                type: "string",
                minLength: 1,
                title: "A Connectable Node",
              },
              target: {
                $ref: "#/properties/source",
              },
            },
          },
        },
      };

      await act(async () => {
        mockSSETransport.onmessage!(mockRequestWithRef);
      });

      expect(mockProtocolOnMessage).toHaveBeenCalledTimes(1);

      const message = mockProtocolOnMessage.mock.calls[0][0];
      expect(message.params.requestedSchema.properties.target).toEqual({
        type: "string",
        minLength: 1,
        title: "A Connectable Node",
      });
    });

    test("resolves $ref references to $defs in requestedSchema", async () => {
      const mockProtocolOnMessage = jest.fn();

      mockSSETransport.onmessage = mockProtocolOnMessage;

      const { result } = renderHook(() => useConnection(defaultProps));

      await act(async () => {
        await result.current.connect();
      });

      const mockRequestWithDefs: JSONRPCMessage = {
        jsonrpc: "2.0",
        id: 1,
        method: "elicitation/create",
        params: {
          message: "Please provide your information",
          requestedSchema: {
            type: "object",
            properties: {
              user: {
                $ref: "#/$defs/UserInput",
              },
            },
            $defs: {
              UserInput: {
                type: "object",
                properties: {
                  name: {
                    type: "string",
                    title: "Name",
                  },
                  age: {
                    type: "integer",
                    title: "Age",
                    minimum: 0,
                  },
                },
                required: ["name"],
              },
            },
          },
        },
      };

      await act(async () => {
        mockSSETransport.onmessage!(mockRequestWithDefs);
      });

      expect(mockProtocolOnMessage).toHaveBeenCalledTimes(1);

      const message = mockProtocolOnMessage.mock.calls[0][0];
      // The $ref should be resolved to the actual UserInput definition
      expect(message.params.requestedSchema.properties.user).toEqual({
        type: "object",
        properties: {
          name: {
            type: "string",
            title: "Name",
          },
          age: {
            type: "integer",
            title: "Age",
            minimum: 0,
          },
        },
        required: ["name"],
      });
    });
  });

  describe("URL Port Handling", () => {
    const SSEClientTransport = jest.requireMock(
      "@modelcontextprotocol/sdk/client/sse.js",
    ).SSEClientTransport;
    const StreamableHTTPClientTransport = jest.requireMock(
      "@modelcontextprotocol/sdk/client/streamableHttp.js",
    ).StreamableHTTPClientTransport;

    beforeEach(() => {
      jest.clearAllMocks();
    });

    test("preserves HTTPS port number when connecting", async () => {
      const props = {
        ...defaultProps,
        sseUrl: "https://example.com:8443/api",
        transportType: "sse" as const,
      };

      const { result } = renderHook(() => useConnection(props));

      await act(async () => {
        await result.current.connect();
      });

      const call = SSEClientTransport.mock.calls[0][0];
      expect(call.toString()).toContain(
        "url=https%3A%2F%2Fexample.com%3A8443%2Fapi",
      );
    });

    test("preserves HTTP port number when connecting", async () => {
      const props = {
        ...defaultProps,
        sseUrl: "http://localhost:3000/api",
        transportType: "sse" as const,
      };

      const { result } = renderHook(() => useConnection(props));

      await act(async () => {
        await result.current.connect();
      });

      const call = SSEClientTransport.mock.calls[0][0];
      expect(call.toString()).toContain(
        "url=http%3A%2F%2Flocalhost%3A3000%2Fapi",
      );
    });

    test("uses default port for HTTPS when not specified", async () => {
      const props = {
        ...defaultProps,
        sseUrl: "https://example.com/api",
        transportType: "sse" as const,
      };

      const { result } = renderHook(() => useConnection(props));

      await act(async () => {
        await result.current.connect();
      });

      const call = SSEClientTransport.mock.calls[0][0];
      expect(call.toString()).toContain("url=https%3A%2F%2Fexample.com%2Fapi");
      expect(call.toString()).not.toContain("%3A443");
    });

    test("preserves port number in streamable-http transport", async () => {
      const props = {
        ...defaultProps,
        sseUrl: "https://example.com:8443/api",
        transportType: "streamable-http" as const,
      };

      const { result } = renderHook(() => useConnection(props));

      await act(async () => {
        await result.current.connect();
      });

      const call = StreamableHTTPClientTransport.mock.calls[0][0];
      expect(call.toString()).toContain(
        "url=https%3A%2F%2Fexample.com%3A8443%2Fapi",
      );
    });
  });

  describe("Proxy Authentication Headers", () => {
    beforeEach(() => {
      jest.clearAllMocks();
      // Reset the mock transport objects
      mockSSETransport.url = undefined;
      mockSSETransport.options = undefined;
      mockStreamableHTTPTransport.url = undefined;
      mockStreamableHTTPTransport.options = undefined;
    });

    test("sends X-MCP-Proxy-Auth header when proxy auth token is configured for proxy connectionType", async () => {
      const propsWithProxyAuth = {
        ...defaultProps,
        connectionType: "proxy" as const,
        config: {
          ...DEFAULT_INSPECTOR_CONFIG,
          MCP_PROXY_AUTH_TOKEN: {
            ...DEFAULT_INSPECTOR_CONFIG.MCP_PROXY_AUTH_TOKEN,
            value: "test-proxy-token",
          },
        },
      };

      const { result } = renderHook(() => useConnection(propsWithProxyAuth));

      await act(async () => {
        await result.current.connect();
      });

      // Check that the transport was created with the correct headers
      expect(mockSSETransport.options).toBeDefined();
      expect(mockSSETransport.options?.requestInit).toBeDefined();

      expect(mockSSETransport.options?.requestInit?.headers).toHaveProperty(
        "X-MCP-Proxy-Auth",
        "Bearer test-proxy-token",
      );
      expect(mockSSETransport?.options?.eventSourceInit?.fetch).toBeDefined();

      // Verify the fetch function includes the proxy auth header
      const mockFetch = mockSSETransport.options?.eventSourceInit?.fetch;
      const testUrl = "http://test.com";
      await mockFetch?.(testUrl, {
        headers: {
          Accept: "text/event-stream",
        },
        cache: "no-store",
        mode: "cors",
        signal: new AbortController().signal,
        redirect: "follow",
        credentials: "include",
      });

      expect(global.fetch).toHaveBeenCalledTimes(2);
      expect(
        (global.fetch as jest.Mock).mock.calls[0][1].headers,
      ).toHaveProperty("X-MCP-Proxy-Auth", "Bearer test-proxy-token");
      expect((global.fetch as jest.Mock).mock.calls[1][0]).toBe(testUrl);
      expect(
        (global.fetch as jest.Mock).mock.calls[1][1].headers,
      ).toHaveProperty("X-MCP-Proxy-Auth", "Bearer test-proxy-token");
    });

    test("does NOT send X-MCP-Proxy-Auth header when proxy auth token is configured for direct connectionType", async () => {
      const propsWithProxyAuth = {
        ...defaultProps,
        connectionType: "direct" as const,
        config: {
          ...DEFAULT_INSPECTOR_CONFIG,
          MCP_PROXY_AUTH_TOKEN: {
            ...DEFAULT_INSPECTOR_CONFIG.MCP_PROXY_AUTH_TOKEN,
            value: "test-proxy-token",
          },
        },
      };

      const { result } = renderHook(() => useConnection(propsWithProxyAuth));

      await act(async () => {
        await result.current.connect();
      });

      // Check that the transport was created with the correct headers
      expect(mockSSETransport.options).toBeDefined();
      expect(mockSSETransport.options?.requestInit).toBeDefined();

      // Verify that X-MCP-Proxy-Auth header is NOT present for direct connections
      expect(mockSSETransport.options?.requestInit?.headers).not.toHaveProperty(
        "X-MCP-Proxy-Auth",
      );
      expect(mockSSETransport?.options?.fetch).toBeDefined();

      // Verify the fetch function does NOT include the proxy auth header
      const mockFetch = mockSSETransport.options?.fetch;
      const testUrl = "http://test.com";
      await mockFetch?.(testUrl, {
        headers: {
          Accept: "text/event-stream",
        },
        cache: "no-store",
        mode: "cors",
        signal: new AbortController().signal,
        redirect: "follow",
        credentials: "include",
      });

      expect(global.fetch).toHaveBeenCalledTimes(1);
      expect((global.fetch as jest.Mock).mock.calls[0][0]).toBe(testUrl);
      expect(
        (global.fetch as jest.Mock).mock.calls[0][1].headers,
      ).not.toHaveProperty("X-MCP-Proxy-Auth");
    });

    test("does NOT send Authorization header for proxy auth", async () => {
      const propsWithProxyAuth = {
        ...defaultProps,
        config: {
          ...DEFAULT_INSPECTOR_CONFIG,
          proxyAuthToken: "test-proxy-token",
        },
      };

      const { result } = renderHook(() => useConnection(propsWithProxyAuth));

      await act(async () => {
        await result.current.connect();
      });

      // Check that Authorization header is NOT used for proxy auth
      expect(mockSSETransport.options?.requestInit?.headers).not.toHaveProperty(
        "Authorization",
        "Bearer test-proxy-token",
      );
    });

    test("preserves server Authorization header when proxy auth is configured", async () => {
      const customHeaders: CustomHeaders = [
        {
          name: "Authorization",
          value: "Bearer server-auth-token",
          enabled: true,
        },
      ];

      const propsWithBothAuth = {
        ...defaultProps,
        customHeaders,
        config: {
          ...DEFAULT_INSPECTOR_CONFIG,
          MCP_PROXY_AUTH_TOKEN: {
            ...DEFAULT_INSPECTOR_CONFIG.MCP_PROXY_AUTH_TOKEN,
            value: "test-proxy-token",
          },
        },
      };

      const { result } = renderHook(() => useConnection(propsWithBothAuth));

      await act(async () => {
        await result.current.connect();
      });

      // Check that both headers are present and distinct
      const headers = mockSSETransport.options?.requestInit?.headers;
      expect(headers).toHaveProperty(
        "Authorization",
        "Bearer server-auth-token",
      );
      expect(headers).toHaveProperty(
        "X-MCP-Proxy-Auth",
        "Bearer test-proxy-token",
      );
    });

    test("sends X-MCP-Proxy-Auth in health check requests", async () => {
      const fetchMock = global.fetch as jest.Mock;
      fetchMock.mockClear();

      const propsWithProxyAuth = {
        ...defaultProps,
        config: {
          ...DEFAULT_INSPECTOR_CONFIG,
          MCP_PROXY_AUTH_TOKEN: {
            ...DEFAULT_INSPECTOR_CONFIG.MCP_PROXY_AUTH_TOKEN,
            value: "test-proxy-token",
          },
        },
      };

      const { result } = renderHook(() => useConnection(propsWithProxyAuth));

      await act(async () => {
        await result.current.connect();
      });

      // Find the health check call
      const healthCheckCall = fetchMock.mock.calls.find(
        (call) => call[0].pathname === "/health",
      );

      expect(healthCheckCall).toBeDefined();
      expect(healthCheckCall[1].headers).toHaveProperty(
        "X-MCP-Proxy-Auth",
        "Bearer test-proxy-token",
      );
    });

    test("works correctly with streamable-http transport", async () => {
      const propsWithStreamableHttp = {
        ...defaultProps,
        transportType: "streamable-http" as const,
        config: {
          ...DEFAULT_INSPECTOR_CONFIG,
          MCP_PROXY_AUTH_TOKEN: {
            ...DEFAULT_INSPECTOR_CONFIG.MCP_PROXY_AUTH_TOKEN,
            value: "test-proxy-token",
          },
        },
      };

      const { result } = renderHook(() =>
        useConnection(propsWithStreamableHttp),
      );

      await act(async () => {
        await result.current.connect();
      });

      // Check that the streamable HTTP transport was created with the correct headers
      expect(mockStreamableHTTPTransport.options).toBeDefined();
      expect(
        mockStreamableHTTPTransport.options?.requestInit?.headers,
      ).toHaveProperty("X-MCP-Proxy-Auth", "Bearer test-proxy-token");
    });
  });

  describe("Custom Headers", () => {
    beforeEach(() => {
      jest.clearAllMocks();
      // Reset the mock transport objects
      mockSSETransport.url = undefined;
      mockSSETransport.options = undefined;
      mockStreamableHTTPTransport.url = undefined;
      mockStreamableHTTPTransport.options = undefined;
    });

    test("sends multiple custom headers correctly", async () => {
      const customHeaders: CustomHeaders = [
        { name: "Authorization", value: "Bearer token123", enabled: true },
        { name: "X-Tenant-ID", value: "acme-inc", enabled: true },
        { name: "X-Environment", value: "staging", enabled: true },
      ];

      const propsWithCustomHeaders = {
        ...defaultProps,
        customHeaders,
      };

      const { result } = renderHook(() =>
        useConnection(propsWithCustomHeaders),
      );

      await act(async () => {
        await result.current.connect();
      });

      // Check that the transport was created with the correct headers
      expect(mockSSETransport.options).toBeDefined();
      expect(mockSSETransport.options?.requestInit?.headers).toBeDefined();

      const headers = mockSSETransport.options?.requestInit?.headers;
      expect(headers).toHaveProperty("Authorization", "Bearer token123");
      expect(headers).toHaveProperty("X-Tenant-ID", "acme-inc");
      expect(headers).toHaveProperty("X-Environment", "staging");
      expect(headers).toHaveProperty(
        "x-custom-auth-headers",
        JSON.stringify(["X-Tenant-ID", "X-Environment"]),
      );
    });

    test("ignores disabled custom headers", async () => {
      const customHeaders: CustomHeaders = [
        { name: "Authorization", value: "Bearer token123", enabled: true },
        { name: "X-Disabled", value: "should-not-appear", enabled: false },
        { name: "X-Enabled", value: "should-appear", enabled: true },
      ];

      const propsWithCustomHeaders = {
        ...defaultProps,
        customHeaders,
      };

      const { result } = renderHook(() =>
        useConnection(propsWithCustomHeaders),
      );

      await act(async () => {
        await result.current.connect();
      });

      const headers = mockSSETransport.options?.requestInit?.headers;
      expect(headers).toHaveProperty("Authorization", "Bearer token123");
      expect(headers).toHaveProperty("X-Enabled", "should-appear");
      expect(headers).not.toHaveProperty("X-Disabled");
    });

    test("handles migrated legacy auth via custom headers", async () => {
      // Simulate what App.tsx would do - migrate legacy auth to custom headers
      const customHeaders: CustomHeaders = [
        { name: "X-Custom-Auth", value: "legacy-token", enabled: true },
      ];

      const propsWithMigratedAuth = {
        ...defaultProps,
        customHeaders,
      };

      const { result } = renderHook(() => useConnection(propsWithMigratedAuth));

      await act(async () => {
        await result.current.connect();
      });

      const headers = mockSSETransport.options?.requestInit?.headers;
      expect(headers).toHaveProperty("X-Custom-Auth", "legacy-token");
      expect(headers).toHaveProperty(
        "x-custom-auth-headers",
        JSON.stringify(["X-Custom-Auth"]),
      );
    });

    test("uses OAuth token when no custom headers or legacy auth provided", async () => {
      const propsWithoutAuth = {
        ...defaultProps,
      };

      const { result } = renderHook(() => useConnection(propsWithoutAuth));

      await act(async () => {
        await result.current.connect();
      });

      const headers = mockSSETransport.options?.requestInit?.headers;
      expect(headers).toHaveProperty("Authorization", "Bearer mock-token");
    });

    test("warns of enabled empty Bearer token", async () => {
      // This test prevents regression of the bug where default "Bearer " header
      // prevented OAuth token injection, causing infinite auth loops
      const customHeaders: CustomHeaders = [
        {
          name: "Authorization",
          value: "Bearer ", // Empty Bearer token placeholder
          enabled: true, // enabled
        },
      ];

      const propsWithEmptyBearer = {
        ...defaultProps,
        customHeaders,
      };

      const { result } = renderHook(() => useConnection(propsWithEmptyBearer));

      await act(async () => {
        await result.current.connect();
      });

      const headers = mockSSETransport.options?.requestInit?.headers;

      expect(headers).toHaveProperty("Authorization", "Bearer");
      // Should not have the x-custom-auth-headers since Authorization is standard
      expect(headers).not.toHaveProperty("x-custom-auth-headers");

      // Should show toast notification for empty Authorization header
      expect(mockToast).toHaveBeenCalledWith({
        title: "Invalid Authorization Header",
        description: expect.any(String),
        variant: "destructive",
      });
    });

    test("prioritizes custom headers over legacy auth", async () => {
      const customHeaders: CustomHeaders = [
        { name: "Authorization", value: "Bearer custom-token", enabled: true },
      ];

      const propsWithBothAuth = {
        ...defaultProps,
        customHeaders,
        bearerToken: "legacy-token",
        headerName: "Authorization",
      };

      const { result } = renderHook(() => useConnection(propsWithBothAuth));

      await act(async () => {
        await result.current.connect();
      });

      const headers = mockSSETransport.options?.requestInit?.headers;
      expect(headers).toHaveProperty("Authorization", "Bearer custom-token");
    });
  });

  describe("Connection URL Verification", () => {
    beforeEach(() => {
      jest.clearAllMocks();
      // Reset the mock transport objects
      mockSSETransport.url = undefined;
      mockSSETransport.options = undefined;
      mockStreamableHTTPTransport.url = undefined;
      mockStreamableHTTPTransport.options = undefined;
    });

    test("uses server URL directly when connectionType is 'direct'", async () => {
      const directProps = {
        ...defaultProps,
        connectionType: "direct" as const,
      };

      const { result } = renderHook(() => useConnection(directProps));

      await act(async () => {
        await result.current.connect();
      });

      // Verify the transport was created with the direct server URL
      expect(mockSSETransport.url).toBeDefined();
      expect(mockSSETransport.url?.toString()).toBe("http://localhost:8080/");
    });

    test("uses proxy server URL when connectionType is 'proxy'", async () => {
      const proxyProps = {
        ...defaultProps,
        connectionType: "proxy" as const,
      };

      const { result } = renderHook(() => useConnection(proxyProps));

      await act(async () => {
        await result.current.connect();
      });

      // Verify the transport was created with a proxy server URL
      expect(mockSSETransport.url).toBeDefined();
      expect(mockSSETransport.url?.pathname).toBe("/sse");
      expect(mockSSETransport.url?.searchParams.get("url")).toBe(
        "http://localhost:8080",
      );
      expect(mockSSETransport.url?.searchParams.get("transportType")).toBe(
        "sse",
      );
    });
  });

  describe("OAuth Error Handling with Scope Discovery", () => {
    beforeEach(() => {
      jest.clearAllMocks();
      mockAuth.mockResolvedValue("AUTHORIZED");
      mockDiscoverScopes.mockResolvedValue(undefined);
    });

    const setup401Error = () => {
      const mockErrorEvent = new ErrorEvent("error", {
        message: "Mock error event",
      });
      mockClient.connect.mockRejectedValueOnce(
        new SseError(401, "Unauthorized", mockErrorEvent),
      );
    };

    const attemptConnection = async (props = defaultProps) => {
      const { result } = renderHook(() => useConnection(props));
      await act(async () => {
        try {
          await result.current.connect();
        } catch {
          // Expected error from auth handling
        }
      });
    };

    const testCases = [
      [
        "discovers and includes scopes in auth call",
        {
          discoveredScope: "read write admin",
          oauthScope: undefined,
          expectScopeCall: true,
          expectedAuthScope: "read write admin",
          authResult: "AUTHORIZED",
        },
      ],
      [
        "handles scope discovery failure gracefully",
        {
          discoveredScope: undefined,
          oauthScope: undefined,
          expectScopeCall: true,
          expectedAuthScope: undefined,
          authResult: "AUTHORIZED",
        },
      ],
      [
        "uses manual oauthScope override instead of discovered scopes",
        {
          discoveredScope: "discovered:scope",
          oauthScope: "manual:scope",
          expectScopeCall: false,
          expectedAuthScope: "manual:scope",
          authResult: "AUTHORIZED",
        },
      ],
      [
        "triggers scope discovery when oauthScope is whitespace",
        {
          discoveredScope: "discovered:scope",
          oauthScope: "   ",
          expectScopeCall: true,
          expectedAuthScope: "discovered:scope",
          authResult: "AUTHORIZED",
        },
      ],
      [
        "handles auth failure after scope discovery",
        {
          discoveredScope: "read write",
          oauthScope: undefined,
          expectScopeCall: true,
          expectedAuthScope: "read write",
          authResult: "UNAUTHORIZED",
        },
      ],
    ] as const;

    test.each(testCases)(
      "should %s",
      async (
        _,
        {
          discoveredScope,
          oauthScope,
          expectScopeCall,
          expectedAuthScope,
          authResult = "AUTHORIZED",
        },
      ) => {
        mockDiscoverScopes.mockResolvedValue(discoveredScope);
        mockAuth.mockResolvedValue(authResult as never);
        setup401Error();

        const props =
          oauthScope !== undefined
            ? { ...defaultProps, oauthScope }
            : defaultProps;
        await attemptConnection(props);

        if (expectScopeCall) {
          expect(mockDiscoverScopes).toHaveBeenCalledWith(
            defaultProps.sseUrl,
            undefined,
            expect.any(Function), // fetchFn when connectionType is proxy
          );
        } else {
          expect(mockDiscoverScopes).not.toHaveBeenCalled();
        }

        expect(mockAuth).toHaveBeenCalledWith(
          expect.any(Object),
          expect.objectContaining({
            serverUrl: defaultProps.sseUrl,
            scope: expectedAuthScope,
          }),
        );
      },
    );

    it("should handle slow scope discovery gracefully", async () => {
      mockDiscoverScopes.mockImplementation(
        () =>
          new Promise((resolve) => setTimeout(() => resolve(undefined), 100)),
      );

      setup401Error();
      await attemptConnection();

      expect(mockDiscoverScopes).toHaveBeenCalledWith(
        defaultProps.sseUrl,
        undefined,
        expect.any(Function), // fetchFn when connectionType is proxy
      );
      expect(mockAuth).toHaveBeenCalledWith(
        expect.any(Object),
        expect.objectContaining({
          serverUrl: defaultProps.sseUrl,
          scope: undefined,
        }),
      );
    });

    it("passes undefined fetchFn when connectionType is direct", async () => {
      mockDiscoverScopes.mockResolvedValue("read write");
      setup401Error();

      const directProps = {
        ...defaultProps,
        connectionType: "direct" as const,
      };
      await attemptConnection(directProps);

      expect(mockDiscoverScopes).toHaveBeenCalledWith(
        defaultProps.sseUrl,
        undefined,
        undefined, // fetchFn is undefined for direct
      );
      expect(mockAuth).toHaveBeenCalledWith(
        expect.any(Object),
        expect.not.objectContaining({ fetchFn: expect.anything() }),
      );
    });

    // The inspector advertises its endpoint as a relative same-origin path, so
    // sseUrl is routinely "/sse". The SDK's auth() feeds serverUrl to
    // resourceUrlFromServerUrl -> new URL(serverUrl), which throws
    // "Invalid URL" on a relative string and leaves the user with a toast
    // instead of a redirect to the IdP.
    it("absolutizes a relative sseUrl before handing it to the SDK's auth()", async () => {
      mockDiscoverScopes.mockResolvedValue("read");
      setup401Error();

      await attemptConnection({ ...defaultProps, sseUrl: "/sse" });

      expect(mockAuth).toHaveBeenCalledWith(
        expect.any(Object),
        expect.objectContaining({
          serverUrl: `${window.location.origin}/sse`,
        }),
      );
    });
  });

  describe("Inspector proxy McpError auth recovery", () => {
    beforeEach(() => {
      jest.clearAllMocks();
      mockAuth.mockResolvedValue("AUTHORIZED");
      mockDiscoverScopes.mockResolvedValue(undefined);
      mockClient.connect.mockResolvedValue(undefined);
    });

    const attemptConnect = async (
      props: Parameters<typeof useConnection>[0] = defaultProps,
    ) => {
      const { result } = renderHook(() => useConnection(props));
      await act(async () => {
        try {
          await result.current.connect();
        } catch {
          // connect may throw when auth recovery does not retry
        }
      });
    };

    it("invokes auth when connect fails with inspector proxy transport McpError and upstream401 data", async () => {
      mockClient.connect.mockRejectedValueOnce(
        new McpError(MCP_PROXY_TRANSPORT_ERROR_CODE, "proxy transport", {
          upstream401: { body: "{}", contentType: "application/json" },
        }),
      );
      await attemptConnect();
      expect(mockAuth).toHaveBeenCalledWith(
        expect.any(Object),
        expect.objectContaining({
          serverUrl: defaultProps.sseUrl,
        }),
      );
    });

    it("invokes auth when connect fails with inspector proxy transport McpError and httpStatus 401", async () => {
      mockClient.connect.mockRejectedValueOnce(
        new McpError(MCP_PROXY_TRANSPORT_ERROR_CODE, "proxy transport", {
          httpStatus: 401,
        }),
      );
      await attemptConnect();
      expect(mockAuth).toHaveBeenCalled();
    });

    it("does not invoke auth for inspector proxy McpError without auth payload", async () => {
      mockClient.connect.mockRejectedValueOnce(
        new McpError(MCP_PROXY_TRANSPORT_ERROR_CODE, "proxy transport", {
          message: "upstream failure",
        }),
      );
      await attemptConnect();
      expect(mockAuth).not.toHaveBeenCalled();
    });

    it("does not invoke auth when httpStatus is 401 but JSON-RPC code is not inspector proxy", async () => {
      mockClient.connect.mockRejectedValueOnce(
        new McpError(-32603, "Internal error", { httpStatus: 401 }),
      );
      await attemptConnect();
      expect(mockAuth).not.toHaveBeenCalled();
    });
  });

  describe("MCP_PROXY_FULL_ADDRESS Configuration", () => {
    beforeEach(() => {
      jest.clearAllMocks();
      // Reset the mock transport objects
      mockSSETransport.url = undefined;
      mockSSETransport.options = undefined;
      mockStreamableHTTPTransport.url = undefined;
      mockStreamableHTTPTransport.options = undefined;
    });

    test("sends proxyFullAddress query parameter for stdio transport when configured", async () => {
      const propsWithProxyFullAddress = {
        ...defaultProps,
        transportType: "stdio" as const,
        command: "test-command",
        args: "test-args",
        env: {},
        config: {
          ...DEFAULT_INSPECTOR_CONFIG,
          MCP_PROXY_FULL_ADDRESS: {
            ...DEFAULT_INSPECTOR_CONFIG.MCP_PROXY_FULL_ADDRESS,
            value: "https://example.com/inspector/mcp_proxy",
          },
        },
      };

      const { result } = renderHook(() =>
        useConnection(propsWithProxyFullAddress),
      );

      await act(async () => {
        await result.current.connect();
      });

      // Check that the URL contains the proxyFullAddress parameter
      expect(mockSSETransport.url?.searchParams.get("proxyFullAddress")).toBe(
        "https://example.com/inspector/mcp_proxy",
      );
    });

    test("sends proxyFullAddress query parameter for sse transport when configured", async () => {
      const propsWithProxyFullAddress = {
        ...defaultProps,
        transportType: "sse" as const,
        sseUrl: "http://localhost:8080",
        config: {
          ...DEFAULT_INSPECTOR_CONFIG,
          MCP_PROXY_FULL_ADDRESS: {
            ...DEFAULT_INSPECTOR_CONFIG.MCP_PROXY_FULL_ADDRESS,
            value: "https://example.com/inspector/mcp_proxy",
          },
        },
      };

      const { result } = renderHook(() =>
        useConnection(propsWithProxyFullAddress),
      );

      await act(async () => {
        await result.current.connect();
      });

      // Check that the URL contains the proxyFullAddress parameter
      expect(mockSSETransport.url?.searchParams.get("proxyFullAddress")).toBe(
        "https://example.com/inspector/mcp_proxy",
      );
    });

    test("does not send proxyFullAddress parameter when MCP_PROXY_FULL_ADDRESS is empty", async () => {
      const propsWithEmptyProxy = {
        ...defaultProps,
        transportType: "stdio" as const,
        command: "test-command",
        args: "test-args",
        env: {},
        config: {
          ...DEFAULT_INSPECTOR_CONFIG,
          MCP_PROXY_FULL_ADDRESS: {
            ...DEFAULT_INSPECTOR_CONFIG.MCP_PROXY_FULL_ADDRESS,
            value: "",
          },
        },
      };

      const { result } = renderHook(() => useConnection(propsWithEmptyProxy));

      await act(async () => {
        await result.current.connect();
      });

      // Check that the URL does not contain the proxyFullAddress parameter
      expect(
        mockSSETransport.url?.searchParams.get("proxyFullAddress"),
      ).toBeNull();
    });

    test("does not send proxyFullAddress parameter for streamable-http transport", async () => {
      const propsWithStreamableHttp = {
        ...defaultProps,
        transportType: "streamable-http" as const,
        sseUrl: "http://localhost:8080",
        config: {
          ...DEFAULT_INSPECTOR_CONFIG,
          MCP_PROXY_FULL_ADDRESS: {
            ...DEFAULT_INSPECTOR_CONFIG.MCP_PROXY_FULL_ADDRESS,
            value: "https://example.com/inspector/mcp_proxy",
          },
        },
      };

      const { result } = renderHook(() =>
        useConnection(propsWithStreamableHttp),
      );

      await act(async () => {
        await result.current.connect();
      });

      // Check that streamable-http transport doesn't get proxyFullAddress parameter
      expect(
        mockStreamableHTTPTransport.url?.searchParams.get("proxyFullAddress"),
      ).toBeNull();
    });
  });

  describe("Connect failure state", () => {
    beforeEach(() => {
      jest.clearAllMocks();
      mockClient.connect.mockResolvedValue(undefined);
    });

    afterAll(() => {
      mockClient.connect.mockResolvedValue(undefined);
    });

    // The sidebar paints its status dot and label off connectionStatus alone:
    // "error" is the red dot plus "Connection Error ...", anything else falls
    // through to a grey dot and "Disconnected". A failed connect that leaves
    // the status at "disconnected" therefore reproduces the exact symptom of
    // issue #56 (an unreachable server looks idle) even while the alert is up.
    test("a failed connect reports the error status, not disconnected", async () => {
      mockClient.connect.mockRejectedValueOnce(
        new Error("connection to the MCP server was refused"),
      );

      const { result } = renderHook(() => useConnection(defaultProps));

      await act(async () => {
        await result.current.connect();
      });

      expect(result.current.connectionStatus).toBe("error");
    });

    test("a failed connect keeps the structured failure for the alert", async () => {
      mockClient.connect.mockRejectedValueOnce(
        new Error("connection to the MCP server was refused"),
      );

      const { result } = renderHook(() => useConnection(defaultProps));

      await act(async () => {
        await result.current.connect();
      });

      expect(result.current.connectionError).toEqual(
        expect.objectContaining({
          reason: "unknown",
          message: "connection to the MCP server was refused",
        }),
      );
    });

    test("a failed connect does NOT show a toast (the sidebar alert replaces it)", async () => {
      mockClient.connect.mockRejectedValueOnce(
        new Error("connection to the MCP server was refused"),
      );

      const { result } = renderHook(() => useConnection(defaultProps));

      await act(async () => {
        await result.current.connect();
      });

      expect(mockToast).not.toHaveBeenCalled();
    });

    test("a post-connect error shows a toast with the normalized message, not Error: Error: duplication", async () => {
      // Make connect succeed so the code reaches the outer catch
      mockClient.connect.mockResolvedValue(undefined);
      // Make getServerCapabilities return logging=true so the code tries
      // client.setLoggingLevel, which is undefined on the mock and throws.
      mockClient.getServerCapabilities.mockReturnValue({
        logging: {},
      } as ServerCapabilities);

      const propsWithLogging = {
        ...defaultProps,
        config: {
          ...DEFAULT_INSPECTOR_CONFIG,
          MCP_SERVER_REQUEST_TIMEOUT: {
            ...DEFAULT_INSPECTOR_CONFIG.MCP_SERVER_REQUEST_TIMEOUT,
            value: 5000,
          },
        },
        defaultLoggingLevel: "debug" as const,
      };

      const { result } = renderHook(() => useConnection(propsWithLogging));

      await act(async () => {
        await result.current.connect();
      });

      expect(mockToast).toHaveBeenCalledWith(
        expect.objectContaining({
          title: "Connection error",
          description:
            "Connection failed: client.setLoggingLevel is not a function",
          variant: "destructive",
        }),
      );
    });
    // Regression for NOTICE.d/stale-connect-status-fix.txt. No disconnect in
    // between: on develop/1.x a failed reconnect left serverImplementation
    // from the previous successful session in state.
    test("a failed connect after a successful one clears serverImplementation without a disconnect in between", async () => {
      mockClient.getServerVersion.mockReturnValue({
        name: "test-server",
        version: "1.0.0",
      });
      // Non-empty capabilities so the test also catches a removed
      // setServerCapabilities(null) in the catch paths.
      mockClient.getServerCapabilities.mockReturnValue({
        completions: {},
      });

      const { result } = renderHook(() => useConnection(defaultProps));

      // Successful connect: serverImplementation is set
      await act(async () => {
        await result.current.connect();
      });

      expect(result.current.connectionStatus).toBe("connected");
      expect(result.current.serverImplementation).toEqual({
        name: "test-server",
        version: "1.0.0",
      });

      // Failed reconnect, no disconnect: the stale values must be gone.
      // getServerVersion/Capabilities only answer for the first (successful)
      // attempt; the second client.connect() call rejects before they run.
      mockClient.getServerVersion.mockReturnValue(undefined);
      mockClient.getServerCapabilities.mockReturnValue(undefined);
      mockClient.connect.mockRejectedValueOnce(
        new Error("connection to the MCP server was refused"),
      );

      await act(async () => {
        await result.current.connect();
      });

      expect(result.current.connectionStatus).toBe("error");
      expect(result.current.serverImplementation).toBeNull();
      expect(result.current.serverCapabilities).toBeNull();
    });

    // Outer catch leg of the fix: the transport connect succeeds and
    // serverImplementation is already set, then a later step in the same
    // connect() throws. Without the clear in the outer catch the stale server
    // info from THIS attempt would survive the "error" status.
    test("a failure after the server info was set clears serverImplementation", async () => {
      mockClient.getServerVersion.mockReturnValue({
        name: "test-server",
        version: "1.0.0",
      });
      // capabilities.logging + defaultLoggingLevel drive connect() into
      // client.setLoggingLevel, which the mock client does not implement:
      // the resulting TypeError reaches the outer catch after
      // serverImplementation was already set for this attempt.
      mockClient.getServerCapabilities.mockReturnValue({
        logging: {},
      });

      const { result } = renderHook(() =>
        useConnection({
          ...defaultProps,
          defaultLoggingLevel: "info",
        }),
      );

      await act(async () => {
        await result.current.connect();
      });

      expect(result.current.connectionStatus).toBe("error");
      expect(result.current.serverImplementation).toBeNull();
      expect(result.current.serverCapabilities).toBeNull();
    });

    // Inner catch, auth branch leg of the fix. The transport connect
    // succeeds and serverImplementation is already set for THIS attempt, then
    // getInstructions() throws UnauthorizedError while building the history
    // entry. OAuth recovery gives up (auth() returns a non-AUTHORIZED
    // result), so the auth branch of the inner catch must clear the server
    // info set moments earlier by this same attempt. Unlike the develop/2.x
    // twin, on this line the auth branch sets no banner and no status (there
    // is no connect-401-banner patch here), so connectionError stays null;
    // connectionStatus does not stay as the previous session left it, because
    // the connect entry (Refs #157) already set the intermediate
    // "disconnected" before the attempt and this leg sets nothing over it.
    // The assertions below pin that line difference.
    test("a late unauthorized failure clears serverImplementation", async () => {
      mockClient.getServerVersion.mockReturnValue({
        name: "test-server",
        version: "1.0.0",
      });
      mockClient.getServerCapabilities.mockReturnValue({
        completions: {},
      });

      const { result } = renderHook(() =>
        useConnection({
          ...defaultProps,
          oauthScope: "read",
        }),
      );

      await act(async () => {
        await result.current.connect();
      });
      expect(result.current.connectionStatus).toBe("connected");

      mockAuth.mockReset();
      mockAuth.mockResolvedValue("EMAIL_REQUIRED");
      mockClient.getInstructions.mockImplementationOnce(() => {
        throw new UnauthorizedError("auth required");
      });

      await act(async () => {
        await result.current.connect();
      });

      // The auth branch is the one that ran: auth() was consulted, and the
      // general branch (which would set a connectionError) did not.
      expect(mockAuth).toHaveBeenCalled();
      expect(result.current.connectionError).toBeNull();
      expect(result.current.connectionStatus).toBe("disconnected");
      expect(result.current.serverImplementation).toBeNull();
      expect(result.current.serverCapabilities).toBeNull();
    });

    // Inner catch, general branch leg of the fix. Same setup as above, but
    // the late failure is a plain error, so the general branch of the inner
    // catch (not the auth branch) must clear the server info.
    test("a late failure after the server info was set clears serverImplementation", async () => {
      mockClient.getServerVersion.mockReturnValue({
        name: "test-server",
        version: "1.0.0",
      });
      mockClient.getServerCapabilities.mockReturnValue({
        completions: {},
      });

      const { result } = renderHook(() => useConnection(defaultProps));

      await act(async () => {
        await result.current.connect();
      });
      expect(result.current.connectionStatus).toBe("connected");

      mockClient.getInstructions.mockImplementationOnce(() => {
        throw new Error("instructions failed");
      });

      await act(async () => {
        await result.current.connect();
      });

      expect(result.current.connectionStatus).toBe("error");
      expect(result.current.connectionError).toEqual(
        expect.objectContaining({ message: "instructions failed" }),
      );
      expect(result.current.serverImplementation).toBeNull();
      expect(result.current.serverCapabilities).toBeNull();
    });

    // The failed-then-failed variant only bites when the second attempt
    // fails LATE, after this attempt's server info was set: the transport
    // connects, then getInstructions() throws while building the history
    // entry. On the base the general branch of the inner catch does not
    // clear that server info, so it survives the "error" status.
    test("a late-failing second connect clears server info it just set after an earlier failed attempt", async () => {
      mockClient.getServerCapabilities.mockReturnValue({ completions: {} });
      mockClient.connect.mockRejectedValueOnce(
        new Error("first attempt refused"),
      );

      const { result } = renderHook(() => useConnection(defaultProps));

      // First attempt: fails inside client.connect(), nothing set.
      await act(async () => {
        await result.current.connect();
      });
      expect(result.current.connectionStatus).toBe("error");
      expect(result.current.connectionError).toEqual(
        expect.objectContaining({ message: "first attempt refused" }),
      );
      expect(result.current.serverImplementation).toBeNull();

      // Second attempt: the transport connect succeeds and the server info
      // of THIS attempt is set, then getInstructions() throws. The server
      // info of THIS attempt must not survive the error.
      mockClient.getServerVersion.mockReturnValue({
        name: "test-server",
        version: "1.0.0",
      });
      mockClient.getInstructions.mockImplementationOnce(() => {
        throw new Error("instructions failed");
      });

      await act(async () => {
        await result.current.connect();
      });

      expect(result.current.connectionStatus).toBe("error");
      expect(result.current.connectionError).toEqual(
        expect.objectContaining({ message: "instructions failed" }),
      );
      expect(result.current.serverImplementation).toBeNull();
      expect(result.current.serverCapabilities).toBeNull();
    });

    // Start-of-connect clears leg of the fix. The proxy health check fails
    // before any catch path runs, so without the clears at the start of
    // connect() the previous session's serverImplementation and
    // serverCapabilities would survive an attempt that never reached the
    // transport.
    test("a proxy health failure clears the stale server info", async () => {
      mockClient.getServerVersion.mockReturnValue({
        name: "test-server",
        version: "1.0.0",
      });
      mockClient.getServerCapabilities.mockReturnValue({ completions: {} });

      const { result } = renderHook(() =>
        useConnection({ ...defaultProps, connectionType: "proxy" as const }),
      );

      await act(async () => {
        await result.current.connect();
      });

      expect(result.current.connectionStatus).toBe("connected");
      expect(result.current.serverImplementation).toEqual({
        name: "test-server",
        version: "1.0.0",
      });
      expect(result.current.serverCapabilities).toEqual({ completions: {} });

      // Next attempt: the proxy health endpoint is unreachable.
      (global.fetch as jest.Mock).mockRejectedValueOnce(
        new Error("proxy is down"),
      );

      await act(async () => {
        await result.current.connect();
      });

      expect(result.current.connectionStatus).toBe(
        "error-connecting-to-proxy",
      );
      expect(result.current.serverImplementation).toBeNull();
      expect(result.current.serverCapabilities).toBeNull();
    });

    // Start-of-connect clears again, second early-return path: the proxy
    // auth branch (toast + "error" status + return) never reaches a catch
    // clear either, so the previous session's server info must already be
    // gone by the time connect() started.
    test("a proxy auth failure clears the stale server info", async () => {
      mockClient.getServerVersion.mockReturnValue({
        name: "test-server",
        version: "1.0.0",
      });
      mockClient.getServerCapabilities.mockReturnValue({ completions: {} });

      const { result } = renderHook(() =>
        useConnection({ ...defaultProps, connectionType: "proxy" as const }),
      );

      await act(async () => {
        await result.current.connect();
      });
      expect(result.current.connectionStatus).toBe("connected");

      // Next attempt: the proxy rejects the call with the session-token
      // error.
      mockClient.connect.mockRejectedValueOnce(
        new Error(
          "Authentication required. Use the session token from the proxy server console",
        ),
      );

      await act(async () => {
        await result.current.connect();
      });

      expect(result.current.connectionStatus).toBe("error");
      expect(mockToast).toHaveBeenCalledWith(
        expect.objectContaining({ title: "Proxy Authentication Required" }),
      );
      expect(result.current.serverImplementation).toBeNull();
      expect(result.current.serverCapabilities).toBeNull();
    });

    // disconnect() clear leg of the fix: on develop/1.x disconnect() left
    // serverImplementation from the previous session in state
    // (serverCapabilities was already cleared on the base).
    test("a successful connect then disconnect clears serverImplementation", async () => {
      mockClient.getServerVersion.mockReturnValue({
        name: "test-server",
        version: "1.0.0",
      });
      mockClient.getServerCapabilities.mockReturnValue({
        completions: {},
      });

      const { result } = renderHook(() => useConnection(defaultProps));

      await act(async () => {
        await result.current.connect();
      });
      expect(result.current.connectionStatus).toBe("connected");

      await act(async () => {
        await result.current.disconnect();
      });

      expect(result.current.connectionStatus).toBe("disconnected");
      expect(result.current.serverImplementation).toBeNull();
      expect(result.current.serverCapabilities).toBeNull();
    });

    // Line difference from the develop/2.x twin, pinned: there the issue #54
    // patch resets the failure banner synchronously at the top of
    // disconnect(), so the banner does not survive a Disconnect. develop/1.x
    // has no banner reset in disconnect() at all, so the failure banner
    // SURVIVES an explicit Disconnect here. A future port of the issue #54
    // banner reset to this line must update this test. Nothing after the
    // awaits may clear the banner either: the next two tests pin why.
    test("a disconnect after a failed connect keeps the failure banner (no banner reset on this line)", async () => {
      mockClient.getServerVersion.mockReturnValue(undefined);
      mockClient.connect.mockRejectedValueOnce(
        new Error("connection to the MCP server was refused"),
      );

      const { result } = renderHook(() => useConnection(defaultProps));

      await act(async () => {
        await result.current.connect();
      });
      expect(result.current.connectionStatus).toBe("error");
      expect(result.current.connectionError).not.toBeNull();

      await act(async () => {
        await result.current.disconnect();
      });

      expect(result.current.connectionStatus).toBe("disconnected");
      expect(result.current.connectionError).not.toBeNull();
      expect(result.current.serverImplementation).toBeNull();
    });

    // The sidebar Reconnect button calls onDisconnect(); onConnect() without
    // awaiting, so the two run interleaved. Two orderings exist and both
    // must keep the failure: (a) the failure lands LAST, after the
    // disconnect tail (this test, gated on the connect rejection), and (b)
    // the failure lands FIRST and the disconnect tail lands last (the next
    // test, gated on mcpClient.close()). A post-await
    // setConnectionError(null) in disconnect() wipes the failure in
    // ordering (b); the 2.x review of the sibling fix removed exactly that
    // clear, and this line never adds one.
    test("a failing reconnect is not wiped by the interleaved disconnect", async () => {
      mockClient.getServerVersion.mockReturnValue({
        name: "test-server",
        version: "1.0.0",
      });

      const { result } = renderHook(() => useConnection(defaultProps));

      await act(async () => {
        await result.current.connect();
      });
      expect(result.current.connectionStatus).toBe("connected");

      // The reconnect attempt parks inside client.connect() until released.
      let releaseReconnect: () => void = () => {};
      const gate = new Promise<void>((resolve) => {
        releaseReconnect = resolve;
      });
      mockClient.connect.mockImplementationOnce(
        () =>
          new Promise((_resolve, reject) => {
            gate.then(() =>
              reject(new Error("connection to the MCP server was refused")),
            );
          }),
      );

      let reconnect: Promise<void> | undefined;
      await act(async () => {
        // Reconnect button: disconnect first, connect second, not awaited.
        const d = result.current.disconnect();
        reconnect = result.current.connect();
        releaseReconnect();
        await Promise.all([
          d,
          reconnect.catch(() => undefined),
        ]);
      });

      expect(result.current.connectionStatus).toBe("error");
      expect(result.current.connectionError).toEqual(
        expect.objectContaining({
          message: "connection to the MCP server was refused",
        }),
      );
      expect(result.current.serverImplementation).toBeNull();
    });

    // Ordering (b) from the comment above: the REVERSE interleaving,
    // failure first, disconnect tail last. The pre-review form of the 2.x
    // fix cleared connectionError at the END of disconnect(), after the
    // awaits; with the new connect failing fast, that late clear landed
    // after the failure and wiped it, so the sidebar alert (driven by
    // connectionError alone) disappeared while the base kept it (owner
    // review BLOCK on PR #205). Here mcpClient.close() is parked on a
    // deferred promise, the reconnect rejects and fully settles while the
    // disconnect is stuck, and only then is the disconnect tail released.
    // With a post-await clear (re)introduced, this test fails with
    // connectionError: null; without one it passes.
    test("a failing reconnect keeps its error when the failure lands before the disconnect finishes", async () => {
      mockClient.getServerVersion.mockReturnValue({
        name: "test-server",
        version: "1.0.0",
      });

      const { result } = renderHook(() => useConnection(defaultProps));

      await act(async () => {
        await result.current.connect();
      });
      expect(result.current.connectionStatus).toBe("connected");

      // Park the disconnect mid-flight: for the SSE transport the first
      // await in disconnect() is mcpClient.close(), so the tail (status
      // change, server info clears) has not run while it is parked.
      let resumeDisconnect: () => void = () => {};
      const disconnectGate = new Promise<void>((resolve) => {
        resumeDisconnect = resolve;
      });
      mockClient.close.mockImplementationOnce(() => disconnectGate);

      // The reconnect fails fast, while the disconnect is parked.
      mockClient.connect.mockRejectedValueOnce(
        new Error("connection to the MCP server was refused"),
      );

      await act(async () => {
        // Reconnect button: disconnect first, connect second, not awaited.
        const d = result.current.disconnect();
        const c = result.current.connect();
        // The failure lands first: the connect fully settles, catch
        // included.
        await c.catch(() => undefined);
        // Only now does the disconnect tail run, after the failure.
        resumeDisconnect();
        await d;
      });

      // The failure must survive the disconnect tail: with a post-await
      // clear introduced, connectionError would be null here.
      expect(result.current.connectionError).toEqual(
        expect.objectContaining({
          message: "connection to the MCP server was refused",
        }),
      );
      expect(result.current.serverImplementation).toBeNull();
      expect(result.current.serverCapabilities).toBeNull();
    });
  });

  describe("requestHistory isError flag", () => {
    beforeEach(() => {
      jest.clearAllMocks();
      mockRequest.mockResolvedValue({ test: "response" });
    });

    const setupAndConnect = async () => {
      const { result } = renderHook(() => useConnection(defaultProps));
      await act(async () => {
        await result.current.connect();
      });
      await act(async () => {
        await new Promise((resolve) => setTimeout(resolve, 0));
      });
      return result;
    };

    const pingRequest: ClientRequest = {
      method: "ping",
      params: {},
    };

    const pingSchema = z.object({
      test: z.string(),
    });
    const pingSchemaAny: AnySchema = pingSchema as unknown as AnySchema;

    it("sets isError=true when response contains isError: true", async () => {
      mockRequest.mockResolvedValueOnce({ isError: true });
      const result = await setupAndConnect();

      await act(async () => {
        await result.current.makeRequest(pingRequest, pingSchemaAny);
      });

      const history = result.current.requestHistory;
      expect(history.length).toBeGreaterThan(0);
      const lastEntry = history[history.length - 1];
      expect(lastEntry.isError).toBe(true);
      expect(JSON.parse(lastEntry.request)).toEqual({
        method: "ping",
        params: {},
      });
      expect(JSON.parse(lastEntry.response!)).toEqual({ isError: true });
    });

    it("sets isError=true when response contains error property (JSON-RPC error)", async () => {
      const errorResponse = {
        error: { code: ErrorCode.MethodNotFound, message: "Method not found" },
      };
      mockRequest.mockResolvedValueOnce(errorResponse);
      const result = await setupAndConnect();

      await act(async () => {
        await result.current.makeRequest(pingRequest, pingSchemaAny);
      });

      const history = result.current.requestHistory;
      const lastEntry = history[history.length - 1];
      expect(lastEntry.isError).toBe(true);
      expect(JSON.parse(lastEntry.response!)).toEqual(errorResponse);
    });

    it("sets isError=true when request throws (transport/protocol error)", async () => {
      const error = new McpError(ErrorCode.InternalError, "transport failure");
      mockRequest.mockRejectedValueOnce(error);
      const result = await setupAndConnect();

      await act(async () => {
        try {
          await result.current.makeRequest(pingRequest, pingSchemaAny);
        } catch {
          // expected
        }
      });

      const history = result.current.requestHistory;
      const lastEntry = history[history.length - 1];
      expect(lastEntry.isError).toBe(true);
      expect(JSON.parse(lastEntry.response!)).toEqual({
        error: "MCP error -32603: transport failure",
      });
    });

    it("sets isError=false on successful response", async () => {
      const successResponse = { test: "response" };
      mockRequest.mockResolvedValueOnce(successResponse);
      const result = await setupAndConnect();

      await act(async () => {
        await result.current.makeRequest(pingRequest, pingSchemaAny);
      });

      const history = result.current.requestHistory;
      const lastEntry = history[history.length - 1];
      expect(lastEntry.isError).toBe(false);
      expect(JSON.parse(lastEntry.response!)).toEqual(successResponse);
    });

    it("sets isError=false for notifications without response", async () => {
      const result = await setupAndConnect();

      await act(async () => {
        await result.current.sendNotification({
          method: "notifications/initialized",
        });
      });

      const history = result.current.requestHistory;
      const lastEntry = history[history.length - 1];
      expect(lastEntry.isError).toBe(false);
      expect(JSON.parse(lastEntry.request)).toEqual({
        method: "notifications/initialized",
      });
      expect(lastEntry.response).toBeUndefined();
    });
  });
});


// [spring-ai-mcp-inspector PATCH] Regression tests for PR #166 round 2.
// Blocker 1: connect() must NOT clear() the OAuth provider at the start.
// Before the fix, clear() ran in connect(), wiping tokens saved by the
// OAuth callback before the transport was built, so the first authenticated
// connect went out without a token and looped back to authorization.
describe("OAuth clear regression (PR #166)", () => {
  const defaultProps: Parameters<typeof useConnection>[0] = {
    transportType: "sse" as const,
    command: "",
    args: "",
    sseUrl: "http://localhost:8080",
    env: {},
    config: DEFAULT_INSPECTOR_CONFIG,
  };
  let mockClear: jest.Mock;

  beforeEach(() => {
    jest.clearAllMocks();
    mockClient.connect.mockResolvedValue(undefined);
    mockClear = jest.fn();
    // Provide a provider whose tokens() resolves to a real access_token,
    // so the code path that injects the Authorization header is exercised.
    const { InspectorOAuthClientProvider } = jest.requireMock("../../auth");
    InspectorOAuthClientProvider.mockImplementation(() => ({
      tokens: jest.fn().mockResolvedValue({ access_token: "test-token" }),
      redirectUrl: "http://localhost:3000/oauth/callback",
      clear: mockClear,
    }));
  });

  afterEach(() => {
    // Restore the default mock so other describe blocks see "mock-token".
    const { InspectorOAuthClientProvider } = jest.requireMock("../../auth");
    InspectorOAuthClientProvider.mockImplementation(() => ({
      tokens: jest.fn().mockResolvedValue({ access_token: "mock-token" }),
      redirectUrl: "http://localhost:3000/oauth/callback",
      clear: jest.fn(),
    }));
  });

  test("connect() does not call clear() on the OAuth provider", async () => {
    const { result } = renderHook(() => useConnection(defaultProps));

    await act(async () => {
      await result.current.connect();
    });

    expect(mockClear).not.toHaveBeenCalled();
  });

  test("disconnect() does call clear() on the OAuth provider", async () => {
    const { result } = renderHook(() => useConnection(defaultProps));

    await act(async () => {
      await result.current.connect();
    });
    mockClear.mockClear();
    await act(async () => {
      await result.current.disconnect();
    });

    expect(mockClear).toHaveBeenCalledTimes(1);
  });
});

// Blocker 2: disconnect() must not throw when terminateSession() rejects
// with 404 (session already gone after a silent drop). Before the fix,
// the uncaught throw aborted the Reconnect handler before onConnect().
describe("disconnect swallows terminateSession 404 (PR #166)", () => {
  const defaultProps: Parameters<typeof useConnection>[0] = {
    transportType: "sse" as const,
    command: "",
    args: "",
    sseUrl: "http://localhost:8080",
    env: {},
    config: DEFAULT_INSPECTOR_CONFIG,
  };
  beforeEach(() => {
    jest.clearAllMocks();
    mockClient.connect.mockResolvedValue(undefined);
    // The default module-factory provider mock has no clear(); disconnect()
    // calls authProvider.clear(), so provide it explicitly (order-independent
    // under randomize: true).
    const { InspectorOAuthClientProvider } = jest.requireMock("../../auth");
    InspectorOAuthClientProvider.mockImplementation(() => ({
      tokens: jest.fn().mockResolvedValue({ access_token: "test-token" }),
      redirectUrl: "http://localhost:3000/oauth/callback",
      clear: jest.fn(),
    }));
  });

  afterEach(() => {
    const { InspectorOAuthClientProvider } = jest.requireMock("../../auth");
    InspectorOAuthClientProvider.mockImplementation(() => ({
      tokens: jest.fn().mockResolvedValue({ access_token: "mock-token" }),
      redirectUrl: "http://localhost:3000/oauth/callback",
      clear: jest.fn(),
    }));
  });
  test("disconnect resolves cleanly when terminateSession rejects", async () => {
    // Set up a streamable-http connection so disconnect() calls terminateSession
    const propsStreamable = {
      ...defaultProps,
      transportType: "streamable-http" as const,
      config: {
        ...DEFAULT_INSPECTOR_CONFIG,
        MCP_PROXY_FULL_ADDRESS: {
          ...DEFAULT_INSPECTOR_CONFIG.MCP_PROXY_FULL_ADDRESS,
          value: "http://localhost:8080/mcp-inspector/mcp_proxy",
        },
      },
    };

    const { result } = renderHook(() => useConnection(propsStreamable));

    await act(async () => {
      await result.current.connect();
    });

    // Override terminateSession to reject (simulating 404/410)
    mockStreamableHTTPTransport.terminateSession = jest
      .fn()
      .mockRejectedValue(new Error("404 Not Found"));

    // disconnect must resolve, not throw
    await act(async () => {
      await expect(result.current.disconnect()).resolves.not.toThrow();
    });

    expect(result.current.connectionStatus).toBe("disconnected");
  });
});

// [spring-ai-mcp-inspector PATCH] PR #166 / #177: reconnect must not send a
// stale mcp-session-id. The StreamableHTTPClientTransport constructor is
// always given `sessionId: undefined` so the handshake starts a fresh session;
// the server assigns the new session id in its response headers.
describe("reconnect must not send a stale mcp-session-id (PR #166)", () => {
  const StreamableHTTPClientTransport = jest.requireMock(
    "@modelcontextprotocol/sdk/client/streamableHttp.js",
  ).StreamableHTTPClientTransport as jest.Mock;

  const streamableProps: Parameters<typeof useConnection>[0] = {
    transportType: "streamable-http" as const,
    connectionType: "direct",
    command: "",
    args: "",
    sseUrl: "http://localhost:8080",
    env: {},
    config: DEFAULT_INSPECTOR_CONFIG,
  };

  beforeEach(() => {
    jest.clearAllMocks();
    mockClient.connect.mockResolvedValue(undefined);
    // The custom fetch inside transportOptions captures the mcp-session-id
    // response header, so a real session id is in state after the first
    // handshake. Without this, the stale-id guard has nothing to be stale.
    (global.fetch as jest.Mock).mockImplementation(async () =>
      new Response(null, {
        status: 200,
        headers: { "mcp-session-id": "session-1" },
      }),
    );
  });

  // Restore the file-level default fetch mock: jest.clearAllMocks() in other
  // describes clears calls but NOT implementations, so a leaked
  // mockImplementation breaks every later test that relies on the default
  // shape (proxy checks read json().status).
  afterEach(() => {
    (global.fetch as jest.Mock).mockImplementation(async () => ({
      json: () => Promise.resolve({ status: "ok" }),
      headers: { get: () => null },
    }));
  });

  test("every handshake constructs the transport with sessionId: undefined", async () => {
    const { result } = renderHook(() => useConnection(streamableProps));

    await act(async () => {
      await result.current.connect();
    });

    await act(async () => {
      await result.current.resetSessionAndConnect();
    });

    expect(StreamableHTTPClientTransport.mock.calls.length).toBe(2);
    for (const call of StreamableHTTPClientTransport.mock.calls) {
      expect(call[1].sessionId).toBeUndefined();
      expect(call[1].requestInit.headers).not.toHaveProperty("mcp-session-id");
    }
  });
});

// [spring-ai-mcp-inspector PATCH] Regression tests for PR #166 round 4.
// Blocker 1: stale completion after setLoggingLevel must not append history.
// Blocker 2: stale attempt must NOT clear connectingRef (A/B/C race).
// Blocker 3: OAuth failure must produce only ONE toast (Sidebar, not handleAuthError).
describe("PR #166 round 4 regression tests", () => {
  const defaultProps: Parameters<typeof useConnection>[0] = {
    transportType: "sse" as const,
    command: "",
    args: "",
    sseUrl: "http://localhost:8080",
    env: {},
    config: DEFAULT_INSPECTOR_CONFIG,
  };

  let mockClear: jest.Mock;

  // This is a top-level describe: the setLoggingLevel cleanup that
  // describe("useConnection") runs in its own afterEach does not cover these
  // tests. Without the delete here, an assigned setLoggingLevel leaks into
  // later tests that require it to be absent (randomize: true runs them in
  // any order) and a "post-connect error" toast assertion fails.
  afterEach(() => {
    delete (mockClient as { setLoggingLevel?: unknown }).setLoggingLevel;
  });

  beforeEach(() => {
    jest.clearAllMocks();
    mockClient.connect.mockResolvedValue(undefined);
    mockClear = jest.fn();
    const { InspectorOAuthClientProvider } = jest.requireMock("../../auth");
    InspectorOAuthClientProvider.mockImplementation(() => ({
      tokens: jest.fn().mockResolvedValue({ access_token: "test-token" }),
      redirectUrl: "http://localhost:3000/oauth/callback",
      clear: mockClear,
    }));
  });

  afterEach(() => {
    const { InspectorOAuthClientProvider } = jest.requireMock("../../auth");
    InspectorOAuthClientProvider.mockImplementation(() => ({
      tokens: jest.fn().mockResolvedValue({ access_token: "mock-token" }),
      redirectUrl: "http://localhost:3000/oauth/callback",
      clear: jest.fn(),
    }));
  });

  test("stale setLoggingLevel completion does not append history (exact count)", async () => {
    // Regression (PR #166): a stale attempt whose setLoggingLevel resolves
    // after a newer attempt started must not push a history entry.
    // Strengthened from toBeLessThanOrEqual(1): with the guard the count is
    // exactly A + B (2); without the guard at useConnection.ts the stale
    // attempt appends a third entry.
    const props = { ...defaultProps, defaultLoggingLevel: "info" as const };
    const { result } = renderHook(() => useConnection(props));

    mockClient.getServerCapabilities.mockReturnValue({ logging: {} });
    mockClient.getServerVersion.mockReturnValue({
      name: "test-server",
      version: "1.0",
    });
    mockClient.setLoggingLevel = jest.fn().mockResolvedValue(undefined);

    // Attempt A: connects, logging branch runs, one logging/setLevel entry.
    await act(async () => {
      await result.current.connect();
    });
    expect(
      result.current.requestHistory.filter((e) =>
        e.request.includes("logging/setLevel"),
      ).length,
    ).toBe(1);

    // Attempt A2: same hook, setLoggingLevel now hangs.
    let resolveLogging: () => void;
    const loggingPromise = new Promise<void>((resolve) => {
      resolveLogging = resolve;
    });
    mockClient.setLoggingLevel = jest.fn().mockReturnValue(loggingPromise);
    const a2Promise = result.current.connect();
    await act(async () => {
      await new Promise((r) => setTimeout(r, 10));
    });

    // Attempt B via reset: bumps the generation; its own setLoggingLevel
    // call resolves the same deferred promise below.
    const bPromise = result.current.resetSessionAndConnect();
    await act(async () => {
      await new Promise((r) => setTimeout(r, 10));
    });

    resolveLogging!();
    await act(async () => {
      await a2Promise;
      await bPromise;
      await new Promise((r) => setTimeout(r, 0));
    });

    // Exactly A's and B's entries; the stale A2 appended nothing.
    expect(
      result.current.requestHistory.filter((e) =>
        e.request.includes("logging/setLevel"),
      ).length,
    ).toBe(2);
  });

  test("stale OAuth failure does not overwrite the live session (generation guard)", async () => {
    // Regression (PR #166): attempt A fails with 401 and awaits auth();
    // while it waits, attempt B (reset) connects successfully. When A's
    // auth() then REJECTS, A must not write error state over B's session.
    const mockErrorEvent = new ErrorEvent("error", {
      message: "Mock error event",
    });
    mockClient.connect.mockRejectedValueOnce(
      new SseError(401, "Unauthorized", mockErrorEvent),
    );

    const { result } = renderHook(() => useConnection(defaultProps));

    // Make auth() hang so attempt A waits inside handleAuthError.
    let rejectAuth: (reason?: unknown) => void;
    const authPromise = new Promise<string>((_resolve, reject) => {
      rejectAuth = reject;
    });
    // Record the moment attempt A actually enters auth(). Rejecting
    // before A attached its `await` handler turns the rejection into a
    // one-macrotask unhandled rejection (jest fails the test on that
    // event even though the hook handles it a tick later); observed as
    // a CI-only flake, so gate deterministically instead of on a timer.
    let enteredAuth = false;
    mockAuth.mockImplementation(() => {
      enteredAuth = true;
      return authPromise;
    });

    const aPromise = result.current.connect();
    await act(async () => {
      const deadline = Date.now() + 1000;
      while (!enteredAuth && Date.now() < deadline) {
        await new Promise((r) => setTimeout(r, 5));
      }
      expect(enteredAuth).toBe(true);
      // One more turn so A attaches its await handler to authPromise.
      await new Promise((r) => setTimeout(r, 0));
    });

    // Attempt B: reset and let it fully connect.
    mockClient.connect.mockResolvedValue(undefined);
    await act(async () => {
      await result.current.resetSessionAndConnect();
    });
    expect(result.current.connectionStatus).toBe("connected");

    // Now the stale attempt A's auth() rejects.
    rejectAuth!(new Error("OAuth server error"));
    await act(async () => {
      await aPromise;
      await new Promise((r) => setTimeout(r, 0));
    });

    // B's session survived the stale OAuth failure.
    expect(result.current.connectionStatus).toBe("connected");
    expect(result.current.connectionError).toBeNull();
  });

  test("stale OAuth success does not restart a retry over the live session (post-auth generation guard)", async () => {
    // Regression (PR #166 rework): attempt A fails with 401 and waits inside
    // auth(); while it waits, attempt B (reset) connects successfully. When
    // A's auth() then RESOLVES AUTHORIZED, handleAuthError returns true and,
    // without the generation re-check after the await, A would run
    // connect(undefined, retryCount + 1): the retry bumps a new generation,
    // clears B's session state and builds a third client over B's live
    // session. The guard must stop the stale attempt before the retry.
    const { Client } = jest.requireMock(
      "@modelcontextprotocol/sdk/client/index.js",
    );
    const mockErrorEvent = new ErrorEvent("error", {
      message: "Mock error event",
    });
    mockClient.connect.mockRejectedValueOnce(
      new SseError(401, "Unauthorized", mockErrorEvent),
    );

    const { result } = renderHook(() => useConnection(defaultProps));

    // Make auth() hang so attempt A waits inside handleAuthError.
    let resolveAuth: (value: string) => void;
    const authPromise = new Promise<string>((resolve) => {
      resolveAuth = resolve;
    });
    // Gate on A actually entering auth() (resolving is not an unhandled
    // rejection risk, but starting B before A is inside auth() would race
    // the very ordering under test).
    let enteredAuth = false;
    mockAuth.mockImplementationOnce(() => {
      enteredAuth = true;
      return authPromise;
    });

    const aPromise = result.current.connect();
    await act(async () => {
      const deadline = Date.now() + 1000;
      while (!enteredAuth && Date.now() < deadline) {
        await new Promise((r) => setTimeout(r, 5));
      }
      expect(enteredAuth).toBe(true);
    });

    // Attempt B: reset and let it fully connect. Two clients exist now:
    // A's (rejected handshake) and B's.
    mockClient.connect.mockResolvedValue(undefined);
    await act(async () => {
      await result.current.resetSessionAndConnect();
    });
    expect(result.current.connectionStatus).toBe("connected");
    expect(Client.mock.calls.length).toBe(2);

    // Now the stale attempt A's auth() resolves AUTHORIZED.
    resolveAuth!("AUTHORIZED");
    await act(async () => {
      await aPromise;
      await new Promise((r) => setTimeout(r, 0));
    });

    // B's session survived: no retry generation was started, so no third
    // client was built and no third handshake ran.
    expect(Client.mock.calls.length).toBe(2);
    expect(mockClient.connect).toHaveBeenCalledTimes(2);
    expect(result.current.connectionStatus).toBe("connected");
    expect(result.current.connectionError).toBeNull();
  });

  test("stale attempt does not release connectingRef (A/B/C sequence)", async () => {
    // Regression (PR #166): attempt A2 hangs mid-handshake (inside the
    // try whose finally owns the guard release), reset starts B, then A2
    // completes as stale. Its finally must NOT release connectingRef while
    // B is still handshaking, or a third connect() (C) slips past the
    // double-click guard. Observable: clients constructed before B is
    // allowed to finish. With the guard C is blocked (1 client); without
    // it C runs a full handshake (2 clients).
    const { Client } = jest.requireMock(
      "@modelcontextprotocol/sdk/client/index.js",
    );
    const propsStreamable = {
      ...defaultProps,
      transportType: "streamable-http" as const,
    };
    const { result } = renderHook(() => useConnection(propsStreamable));

    // Attempt A: completes.
    await act(async () => {
      await result.current.connect();
    });
    expect(result.current.connectionStatus).toBe("connected");
    expect(Client.mock.calls.length).toBe(1);

    // Attempt A2: hangs inside the handshake (past the cleanup, inside
    // the try/finally), so its finally runs when it completes as stale.
    let resolveHsA2: () => void;
    const hsA2 = new Promise<void>((resolve) => {
      resolveHsA2 = resolve;
    });
    mockClient.connect.mockReturnValueOnce(hsA2);
    const a2Promise = result.current.connect();
    await act(async () => {
      await new Promise((r) => setTimeout(r, 10));
    });

    // Attempt B (reset): also hangs inside its handshake.
    let resolveHsB: () => void;
    const hsB = new Promise<void>((resolve) => {
      resolveHsB = resolve;
    });
    mockClient.connect.mockReturnValueOnce(hsB);
    const bPromise = result.current.resetSessionAndConnect();
    await act(async () => {
      await new Promise((r) => setTimeout(r, 10));
    });

    // A2's handshake completes; A2 is stale and its finally runs now.
    resolveHsA2!();
    await act(async () => {
      await a2Promise;
    });

    // Attempt C while B is still handshaking: the guard must hold.
    // Every attempt constructs its own Client before its handshake, so by
    // now A, A2 and B have built three; C must not add a fourth.
    mockClient.connect.mockResolvedValue(undefined);
    await act(async () => {
      await result.current.connect();
    });
    expect(Client.mock.calls.length).toBe(3);

    // Let B finish; it is the current attempt and completes without
    // building another client.
    resolveHsB!();
    await act(async () => {
      await bPromise;
      await new Promise((r) => setTimeout(r, 0));
    });
    expect(Client.mock.calls.length).toBe(3);
    expect(result.current.connectionStatus).toBe("connected");
  });

  test("stale handshake closes its client (leak fix)", async () => {
    // Regression (PR #166): when a stale handshake completes after a newer
    // attempt started, the stale client must be closed. Removing the
    // client.close() in the stale guard makes this test fail.
    let resolveHandshake: () => void;
    const handshake = new Promise<void>((resolve) => {
      resolveHandshake = resolve;
    });
    mockClient.connect.mockReturnValueOnce(handshake);

    const { result } = renderHook(() => useConnection(defaultProps));

    // Attempt A: hangs inside client.connect.
    const aPromise = result.current.connect();
    await act(async () => {
      await new Promise((r) => setTimeout(r, 10));
    });
    expect(mockClient.close).not.toHaveBeenCalled();

    // Attempt B (reset) connects successfully.
    mockClient.connect.mockResolvedValue(undefined);
    await act(async () => {
      await result.current.resetSessionAndConnect();
    });
    expect(result.current.connectionStatus).toBe("connected");

    // A's handshake completes; A is stale and must close its client.
    resolveHandshake!();
    await act(async () => {
      await aPromise;
      await new Promise((r) => setTimeout(r, 0));
    });

    expect(mockClient.close).toHaveBeenCalledTimes(1);
  });

  test("resetSessionAndConnect keeps the OAuth grant (no authProvider.clear())", async () => {
    // Regression (PR #166): Reset targets the stale MCP session, not the
    // OAuth grant; restoring authProvider.clear() here forces full
    // re-authorization on every Reset and fails this test.
    const { result } = renderHook(() => useConnection(defaultProps));

    await act(async () => {
      await result.current.connect();
    });
    mockClear.mockClear();

    await act(async () => {
      await result.current.resetSessionAndConnect();
    });

    expect(mockClear).not.toHaveBeenCalled();
    // The OAuth tokens themselves stay available (kept for the next connect).
    const { InspectorOAuthClientProvider } = jest.requireMock("../../auth");
    expect(InspectorOAuthClientProvider).toHaveBeenCalled();
  });

  test("post-handshake failure fires exactly one toast (outer catch)", async () => {
    // Regression (PR #166): errors after the handshake (outer catch) never
    // set connectionError, so the Sidebar toast cannot fire for them; the
    // hook must toast directly, exactly once, instead of silently setting
    // status=error with no visible feedback.
    const props = { ...defaultProps, defaultLoggingLevel: "info" as const };
    const { result } = renderHook(() => useConnection(props));

    mockClient.getServerCapabilities.mockReturnValue({ logging: {} });
    mockClient.getServerVersion.mockReturnValue({
      name: "test-server",
      version: "1.0",
    });
    mockClient.setLoggingLevel = jest
      .fn()
      .mockRejectedValue(new Error("post-handshake boom"));

    await act(async () => {
      await result.current.connect();
    });
    await act(async () => {
      await new Promise((r) => setTimeout(r, 0));
    });

    expect(mockToast).toHaveBeenCalledTimes(1);
    expect(mockToast.mock.calls[0][0]?.title).toBe("Connection error");
    expect(result.current.connectionStatus).toBe("error");
    expect(result.current.connectionError).toBeNull();
  });

  test("OAuth failure produces only one toast", async () => {
    const mockErrorEvent = new ErrorEvent("error", {
      message: "Mock error event",
    });

    // First connect fails with 401
    mockClient.connect.mockRejectedValueOnce(
      new SseError(401, "Unauthorized", mockErrorEvent),
    );

    // Make auth() reject (OAuth failure)
    mockAuth.mockRejectedValueOnce(new Error("OAuth server error"));

    const { result } = renderHook(() => useConnection(defaultProps));

    await act(async () => {
      await result.current.connect();
    });

    // Wait for state updates
    await act(async () => {
      await new Promise((r) => setTimeout(r, 0));
    });

    // Only ONE toast should have been called (from Sidebar effect, not handleAuthError)
    // The handleAuthError no longer calls toast; the Sidebar effect calls toast
    // when connectionError is set.
    // Note: the Sidebar component is not rendered here, so mockToast from
    // the useToast mock in useConnection.test.tsx is the only one called.
    // We expect 0 or 1 calls from handleAuthError (now 0 after the fix).
    // The Sidebar test would show 1 call. Here we verify handleAuthError
    // does not call toast directly.
    const oauthToastCalls = mockToast.mock.calls.filter(
      (call) => call[0]?.title === "OAuth Authentication Failed",
    );
    expect(oauthToastCalls.length).toBe(0);
  });
});

// [spring-ai-mcp-inspector PATCH] Regression test for the PR #166 round 3
// review finding: the OAuth retry runs as its own connect attempt
// (retryCount = 1) and, when it completes, owns the current generation.
// The finally used to release connectingRef only for retryCount === 0,
// and the outer attempt had already become stale by then, so a successful
// OAuth retry left the guard latched and every later connect() was
// silently swallowed until a page reload. Refs #157.
describe("PR #166 OAuth retry guard regression", () => {
  const defaultProps: Parameters<typeof useConnection>[0] = {
    transportType: "sse" as const,
    command: "",
    args: "",
    sseUrl: "http://localhost:8080",
    env: {},
    config: DEFAULT_INSPECTOR_CONFIG,
  };

  beforeEach(() => {
    jest.clearAllMocks();
    mockClient.connect.mockResolvedValue(undefined);
    mockAuth.mockResolvedValue("AUTHORIZED");
    // The default module-factory provider mock has no clear(); disconnect()
    // calls authProvider.clear(), so provide it explicitly (order-independent
    // under randomize: true).
    const { InspectorOAuthClientProvider } = jest.requireMock("../../auth");
    InspectorOAuthClientProvider.mockImplementation(() => ({
      tokens: jest.fn().mockResolvedValue({ access_token: "test-token" }),
      redirectUrl: "http://localhost:3000/oauth/callback",
      clear: jest.fn(),
    }));
  });

  afterEach(() => {
    const { InspectorOAuthClientProvider } = jest.requireMock("../../auth");
    InspectorOAuthClientProvider.mockImplementation(() => ({
      tokens: jest.fn().mockResolvedValue({ access_token: "mock-token" }),
      redirectUrl: "http://localhost:3000/oauth/callback",
      clear: jest.fn(),
    }));
  });
  test("connect works again after a successful OAuth retry (guard released)", async () => {
    const { Client } = jest.requireMock(
      "@modelcontextprotocol/sdk/client/index.js",
    );
    const mockErrorEvent = new ErrorEvent("error", {
      message: "Mock error event",
    });

    // First attempt: 401, auth() returns AUTHORIZED, the recursive retry
    // (retryCount = 1) completes the handshake. Two Clients are built.
    mockClient.connect.mockRejectedValueOnce(
      new SseError(401, "Unauthorized", mockErrorEvent),
    );
    const { result } = renderHook(() => useConnection(defaultProps));
    await act(async () => {
      await result.current.connect();
    });
    expect(result.current.connectionStatus).toBe("connected");
    expect(mockAuth).toHaveBeenCalledTimes(1);
    expect(Client.mock.calls.length).toBe(2);

    await act(async () => {
      await result.current.disconnect();
    });
    expect(result.current.connectionStatus).toBe("disconnected");

    // The completing retry must have released connectingRef: this connect()
    // runs a fresh handshake (a third Client, status connected). With the
    // guard still latched it is swallowed at the top of connect() and
    // constructs nothing.
    await act(async () => {
      await result.current.connect();
    });
    expect(Client.mock.calls.length).toBe(3);
    expect(result.current.connectionStatus).toBe("connected");
  });
});
