import { renderHook, act } from "@testing-library/react";
import { useConnection } from "../useConnection";
import { z } from "zod/v3";
import {
  ClientRequest,
  CreateTaskResultSchema,
  JSONRPCMessage,
  McpError,
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
import { auth } from "@modelcontextprotocol/sdk/client/auth.js";
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
  close: jest.fn(),
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
  onclose?: () => void;
  onerror?: (error: unknown) => void;
} = {
  start: jest.fn(),
  url: undefined,
  options: undefined,
  onmessage: undefined,
  onclose: undefined,
  onerror: undefined,
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
            elicitation: {
              form: {},
              url: {},
            },
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
  });

  describe("Auto-retry with exponential backoff", () => {
    beforeEach(() => {
      jest.clearAllMocks();
      jest.useFakeTimers();
      mockClient.connect.mockResolvedValue(undefined);
      mockClient.getServerCapabilities.mockReturnValue({
        tools: {},
      });
      mockClient.getServerVersion.mockReturnValue({
        name: "test",
        version: "1.0",
      });
    });

    afterEach(() => {
      jest.useRealTimers();
    });

    // [spring-ai-mcp-inspector PATCH] Regression: auto-retry must continue
    // the full 1s/2s/4s/8s/16s chain after a remote disconnect, not stop
    // after the first retry (#121).
    it("fires all five backoff delays when auto-retry is enabled and server keeps disconnecting", async () => {
      const { result } = renderHook(() => useConnection(defaultProps));

      // Connect successfully
      await act(async () => {
        await result.current.connect();
      });
      expect(result.current.connectionStatus).toBe("connected");

      // Enable auto-retry
      act(() => {
        result.current.setAutoReconnect(true);
      });

      // Simulate remote disconnect: call transport.onclose
      await act(async () => {
        mockSSETransport.onclose?.();
      });

      expect(result.current.connectionStatus).toBe("disconnected-remote");

      // Verify each delay fires and connect is called each time
      const expectedDelays = [1000, 2000, 4000, 8000, 16000];
      const connectSpy = jest.spyOn(result.current, "connect");

      for (let i = 0; i < expectedDelays.length; i++) {
        // Reset the mock to track just this call
        mockClient.connect.mockClear();
        connectSpy.mockClear();

        await act(async () => {
          jest.advanceTimersByTime(expectedDelays[i]);
        });

        // Wait for the async connect() to resolve
        await act(async () => {
          await Promise.resolve();
        });

        expect(mockClient.connect).toHaveBeenCalledTimes(1);

        // Simulate another disconnect to trigger the next retry
        await act(async () => {
          mockSSETransport.onclose?.();
        });
      }

      // After 5 attempts, no more retries should fire
      connectSpy.mockClear();
      await act(async () => {
        jest.advanceTimersByTime(32000);
      });
      expect(connectSpy).not.toHaveBeenCalled();
    });

    it("stops retrying when auto-retry is toggled off after banner appears", async () => {
      const { result } = renderHook(() => useConnection(defaultProps));

      await act(async () => {
        await result.current.connect();
      });

      act(() => {
        result.current.setAutoReconnect(true);
      });

      await act(async () => {
        mockSSETransport.onclose?.();
      });

      expect(result.current.connectionStatus).toBe("disconnected-remote");

      // Toggle auto-retry off
      act(() => {
        result.current.setAutoReconnect(false);
      });

      const connectSpy = jest.spyOn(result.current, "connect");
      connectSpy.mockClear();
      mockClient.connect.mockClear();

      await act(async () => {
        jest.advanceTimersByTime(1000);
      });

      expect(connectSpy).not.toHaveBeenCalled();
    });

    it("stops retrying on auth error and does not continue backoff", async () => {
      const mockErrorEvent = new ErrorEvent("error", {
        message: "Mock error event",
      });
      mockAuth.mockResolvedValue("REDIRECT" as never);

      const { result } = renderHook(() => useConnection(defaultProps));

      // Initial connect succeeds
      await act(async () => {
        await result.current.connect();
      });
      expect(result.current.connectionStatus).toBe("connected");

      act(() => {
        result.current.setAutoReconnect(true);
      });

      await act(async () => {
        mockSSETransport.onclose?.();
      });

      expect(result.current.connectionStatus).toBe("disconnected-remote");

      // The retry connect will fail with 401 auth error
      mockClient.connect.mockClear();
      mockClient.connect.mockRejectedValueOnce(
        new SseError(401, "Unauthorized", mockErrorEvent),
      );

      // First retry fires after 1s
      await act(async () => {
        jest.advanceTimersByTime(1000);
      });
      // Flush all microtasks from the async connect()
      await act(async () => {
        await jest.requireActual("timers").setImmediate(() => {});
      });

      // Auth error path sets status to "error" (not disconnected-remote),
      // so the reconnect effect should not schedule another timer.
      // Verify no further connect calls happen after advancing timers.
      mockClient.connect.mockClear();
      await act(async () => {
        jest.advanceTimersByTime(2000);
      });
      await act(async () => {
        jest.advanceTimersByTime(4000);
      });
      await act(async () => {
        jest.advanceTimersByTime(8000);
      });

      expect(mockClient.connect).not.toHaveBeenCalled();
    });

    // [spring-ai-mcp-inspector PATCH] Regression: a failed non-auth
    // connect attempt must continue the backoff chain, not stop after
    // the first attempt (#121).
    it("continues retry chain after a failed non-auth connect attempt", async () => {
      const { result } = renderHook(() => useConnection(defaultProps));

      // Connect successfully
      await act(async () => {
        await result.current.connect();
      });
      expect(result.current.connectionStatus).toBe("connected");

      // Enable auto-retry
      act(() => {
        result.current.setAutoReconnect(true);
      });

      // Simulate remote disconnect
      await act(async () => {
        mockSSETransport.onclose?.();
      });
      expect(result.current.connectionStatus).toBe("disconnected-remote");

      // Make the next connect() calls fail (generic network error,
      // not auth)
      mockClient.connect.mockRejectedValue(
        new Error("network down"),
      );

      // Advance through all 5 delays; each should attempt a connect
      const expectedDelays = [1000, 2000, 4000, 8000, 16000];
      for (let i = 0; i < expectedDelays.length; i++) {
        mockClient.connect.mockClear();

        await act(async () => {
          jest.advanceTimersByTime(expectedDelays[i]);
        });
        // Flush microtasks from the async connect() and its .then()
        await act(async () => {
          await Promise.resolve();
        });

        // Each failed attempt should have called connect
        expect(mockClient.connect).toHaveBeenCalledTimes(1);
      }

      // After 5 attempts, no more retries should fire
      mockClient.connect.mockClear();
      await act(async () => {
        jest.advanceTimersByTime(32000);
      });
      expect(mockClient.connect).not.toHaveBeenCalled();
    });
  });

  describe("SDK callback lifecycle regression", () => {
    beforeEach(() => {
      jest.useFakeTimers();
      jest.clearAllMocks();
      mockClient.connect.mockResolvedValue(undefined);
      mockClient.getServerCapabilities.mockReturnValue({
        tools: {},
      });
      mockClient.getServerVersion.mockReturnValue({
        name: "test",
        version: "1.0",
      });
    });

    afterEach(() => {
      jest.useRealTimers();
    });

    // [spring-ai-mcp-inspector PATCH] Regression: transport onclose/onerror
    // composition after client.connect, with pending request rejection (#121).
    it("sets transport.onclose and transport.onerror after successful connect", async () => {
      const { result } = renderHook(() => useConnection(defaultProps));

      await act(async () => {
        await result.current.connect();
      });

      expect(mockSSETransport.onclose).toBeDefined();
      expect(typeof mockSSETransport.onclose).toBe("function");
      // onerror is set via a closure, not directly on the transport, but
      // the disconnect callback is wired through handleDisconnect.
      // Verify that calling onclose transitions to disconnected-remote
      await act(async () => {
        mockSSETransport.onclose?.();
      });
      expect(result.current.connectionStatus).toBe("disconnected-remote");
    });

    it("onclose after onerror does not throw (composition)", async () => {
      const { result } = renderHook(() => useConnection(defaultProps));

      await act(async () => {
        await result.current.connect();
      });

      // Both onclose and onerror should be callable without throwing
      expect(() => {
        mockSSETransport.onclose?.();
      }).not.toThrow();
    });

    it("clears transport callbacks on disconnect", async () => {
      const { result } = renderHook(() => useConnection(defaultProps));

      await act(async () => {
        await result.current.connect();
      });

      await act(async () => {
        await result.current.disconnect();
      });

      // After disconnect, transport callbacks should be cleared
      // so intentional disconnect does not trigger disconnected-remote
      expect(mockSSETransport.onclose).toBeUndefined();
    });

    // [spring-ai-mcp-inspector PATCH] Regression: remote close rejects
    // pending requests and clears the SDK client transport state (#121).
    it("remote close rejects pending requests and clears client transport state", async () => {
      // Override mockClient.request to return a deferred promise
      // so we can observe rejection on close.
      const originalRequest = mockClient.request;
      const originalClose = mockClient.close;
      let rejectRequest: (reason?: unknown) => void = () => undefined;
      const deferredRequest = new Promise((_resolve, reject) => {
        rejectRequest = reject;
      });
      mockClient.request = jest.fn().mockReturnValue(deferredRequest);

      // Simulate the SDK installing a close handler on the transport
      // during client.connect(). The production code's SDK callback
      // composition preserves this handler and calls it first.
      // Set this BEFORE connect so the production code saves it as
      // sdkOnClose.
      mockClient.close = jest.fn().mockImplementation(() => {
        rejectRequest(new Error("Connection closed"));
        return Promise.resolve();
      });
      const sdkCloseHandler = () => {
        mockClient.close();
      };
      mockSSETransport.onclose = sdkCloseHandler;

      const { result } = renderHook(() => useConnection(defaultProps));

      await act(async () => {
        await result.current.connect();
      });
      expect(result.current.connectionStatus).toBe("connected");
      // After connect, the transport.onclose should be the composed
      // handler (calls sdkCloseHandler then handleDisconnect)
      expect(mockSSETransport.onclose).not.toBe(sdkCloseHandler);

      // Start a pending request by calling makeRequest.
      // Catch the rejection inside act() to avoid unhandled rejection.
      let pendingError: unknown;
      await act(async () => {
        const pendingRequest = result.current.makeRequest(
          { method: "tools/list", params: {} },
          {} as AnySchema,
        );
        pendingRequest.catch((err: unknown) => {
          pendingError = err;
        });
      });

      // Fire transport.onclose: this triggers the SDK callback
      // composition which calls the SDK handler (client.close() via
      // sdkCloseHandler) and then handleDisconnect().
      await act(async () => {
        mockSSETransport.onclose?.();
      });

      // Restore original mocks to prevent leaking to other tests
      mockClient.request = originalRequest;
      mockClient.close = originalClose;

      // The pending request should be rejected (the SDK close
      // callback rejects pending work via the mock)
      expect(pendingError).toBeDefined();
      expect(String(pendingError)).toContain("Connection closed");
      expect(result.current.connectionStatus).toBe("disconnected-remote");
    });

    // [spring-ai-mcp-inspector PATCH] Regression: unmount cleanup cancels
    // pending reconnect timers (#121). Uses fake timers throughout so the
    // timer is observable.
    it("cleans up reconnect timer on unmount", async () => {
      const { result, unmount } = renderHook(() =>
        useConnection(defaultProps),
      );

      await act(async () => {
        await result.current.connect();
      });

      act(() => {
        result.current.setAutoReconnect(true);
      });

      await act(async () => {
        mockSSETransport.onclose?.();
      });

      // Timer is now pending (1s delay) under fake timers.
      // Unmount should clean it up.
      expect(() => {
        unmount();
      }).not.toThrow();

      // Advancing timers after unmount should not cause any connect calls
      mockClient.connect.mockClear();
      jest.advanceTimersByTime(5000);
      expect(mockClient.connect).not.toHaveBeenCalled();
    });
  });
});
