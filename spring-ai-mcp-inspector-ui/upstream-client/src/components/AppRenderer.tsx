import { useMemo, useRef, useState, useCallback, useEffect } from "react";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import {
  Tool,
  ContentBlock,
  CompatibilityCallToolResult,
  CallToolResult,
  CallToolResultSchema,
  ServerNotification,
  LoggingMessageNotificationParams,
} from "@modelcontextprotocol/sdk/types.js";
import {
  AppRenderer as McpUiAppRenderer,
  type McpUiHostContext,
  type RequestHandlerExtra,
} from "@mcp-ui/client";
import {
  type McpUiMessageRequest,
  type McpUiMessageResult,
} from "@modelcontextprotocol/ext-apps/app-bridge";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { AlertCircle } from "lucide-react";
import { useToast } from "@/lib/hooks/useToast";
import {
  type AppTrafficEntry,
  type AppLifecycleState,
  derivePhase,
} from "@/lib/app-traffic";

interface AppRendererProps {
  sandboxPath: string;
  tool: Tool;
  tools: Tool[];
  mcpClient: Client | null;
  toolInput?: Record<string, unknown>;
  toolResult?: CompatibilityCallToolResult | null;
  onNotification?: (notification: ServerNotification) => void;
  onAppTraffic?: (entry: AppTrafficEntry) => void;
  onLifecycleChange?: (state: AppLifecycleState) => void;
}

/**
 * Check whether a tool call from an App is allowed based on the tool's
 * _meta.ui.visibility. The default visibility is ["model", "app"] per the
 * SEP-1865 McpUiToolVisibilitySchema. Tools with visibility that does not
 * include "app" are rejected. Unknown tools are rejected.
 *
 * Exported so that unit tests import the actual production function instead
 * of duplicating its logic.
 */
export function checkToolVisibility(
  tools: Tool[],
  toolName: string,
): { allowed: boolean; reason?: string } {
  const matchedTool = tools.find((t) => t.name === toolName);
  if (!matchedTool) {
    return { allowed: false, reason: "unknown tool" };
  }
  const meta = (matchedTool as Record<string, unknown>)._meta as
    | Record<string, unknown>
    | undefined;
  const ui = meta?.ui as Record<string, unknown> | undefined;
  // Guard the type with Array.isArray before treating visibility as a string[]:
  // a malformed _meta.ui.visibility must neither pass (a bare string like
  // "happy" would make includes("app") true, substring match) nor throw (an
  // object has no includes). A present-but-malformed value fails closed; only
  // an absent value gets the permissive ["model", "app"] default.
  const rawVisibility = ui?.visibility;
  if (rawVisibility === undefined || rawVisibility === null) {
    return { allowed: true };
  }
  if (!Array.isArray(rawVisibility) || !rawVisibility.includes("app")) {
    return { allowed: false, reason: "not available to apps" };
  }
  return { allowed: true };
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function cspFromMeta(meta: unknown): Record<string, unknown> | undefined {
  const ui = isRecord(meta) ? meta["ui"] : undefined;
  const csp = isRecord(ui) ? ui["csp"] : undefined;
  return isRecord(csp) ? csp : undefined;
}

/**
 * [spring-ai-mcp-inspector PATCH] Read the App's CSP from the UI resource, not
 * from the tool: in @modelcontextprotocol/ext-apps 1.7.2 McpUiToolMeta carries
 * only resourceUri and visibility (McpUiToolMeta.csp is `never`), and
 * McpUiResourceMeta.csp is the single source of truth. Per the ext-apps docs
 * the host reads it from the resources/read content item, with the
 * resources/list entry as the fallback; a tool that sets _meta.ui.csp anyway
 * is ignored, and a resource without csp gets the sandbox baseline policy
 * (owner review PR #199, round 2, BLOCK 2).
 */
export async function readResourceCsp(
  client: Client,
  resourceUri: string,
): Promise<Record<string, unknown> | undefined> {
  try {
    const result = await client.readResource({ uri: resourceUri });
    for (const content of result?.contents ?? []) {
      const csp = cspFromMeta((content as Record<string, unknown>)._meta);
      if (csp) {
        return csp;
      }
    }
  } catch {
    // fall through to the resources/list fallback below
  }
  try {
    const result = await client.listResources();
    for (const resource of result?.resources ?? []) {
      if (resource.uri === resourceUri) {
        return cspFromMeta((resource as Record<string, unknown>)._meta);
      }
    }
  } catch {
    // no resource meta available: the sandbox baseline policy applies
  }
  return undefined;
}

/**
 * [spring-ai-mcp-inspector PATCH] AppRenderer with SEP-1865 traffic tap
 * and lifecycle state machine.
 *
 * The tap observes guest<->host JSON-RPC traffic at the DOM boundary:
 * - guest->host: via window.addEventListener("message", ...) filtering by
 *   event.source === outerIframe.contentWindow
 * - host->guest: via wrapping the outer iframe's contentWindow.postMessage
 *
 * Both directions are emitted as AppTrafficEntry entries to the onAppTraffic
 * callback for the traffic panel (task 3 / t_4abaf1bb).
 */
const AppRenderer = ({
  sandboxPath,
  tool,
  tools,
  mcpClient,
  toolInput,
  toolResult,
  onNotification,
  onAppTraffic,
  onLifecycleChange,
}: AppRendererProps) => {
  const [error, setError] = useState<string | null>(null);
  const [resourceCsp, setResourceCsp] = useState<Record<string, unknown> | undefined>(undefined);
  const { toast } = useToast();
  const containerRef = useRef<HTMLDivElement>(null);
  const seqRef = useRef(0);
  const tapCleanupRef = useRef<(() => void) | null>(null);
  const stateRef = useRef<AppLifecycleState>("idle");

  const setLifecycleState = useCallback(
    (state: AppLifecycleState) => {
      if (stateRef.current === state) return;
      stateRef.current = state;
      onLifecycleChange?.(state);
    },
    [onLifecycleChange],
  );

  // CSP lives on the UI resource, not on the tool meta (see readResourceCsp):
  // resolve it per rendered tool and hand it to the sandbox frame.
  const toolUiMeta = ((tool as Record<string, unknown>)._meta as
    | Record<string, unknown>
    | undefined)?.["ui"] as Record<string, unknown> | undefined;
  const toolResourceUri = toolUiMeta?.["resourceUri"];
  useEffect(() => {
    let cancelled = false;
    setResourceCsp(undefined);
    if (!mcpClient || typeof toolResourceUri !== "string" || toolResourceUri === "") {
      return;
    }
    readResourceCsp(mcpClient, toolResourceUri).then((csp) => {
      if (!cancelled) {
        setResourceCsp(csp);
      }
    });
    return () => {
      cancelled = true;
    };
  }, [mcpClient, toolResourceUri]);

  const emitTraffic = useCallback(
    (
      direction: AppTrafficEntry["direction"],
      data: Record<string, unknown>,
    ) => {
      const method = data.method as string | undefined;
      const entry: AppTrafficEntry = {
        seq: seqRef.current++,
        timestamp: new Date().toISOString(),
        direction,
        kind:
          data.id !== undefined && data.method !== undefined
            ? "request"
            : data.id !== undefined && data.result !== undefined
              ? "response"
              : data.id !== undefined && data.error !== undefined
                ? "response"
                : "notification",
        method,
        id: data.id as string | number | undefined,
        params: data.params as unknown,
        result: data.result as unknown,
        error: data.error as { code: number; message: string } | undefined,
        phase: derivePhase(method),
      };
      onAppTraffic?.(entry);
    },
    [onAppTraffic],
  );

  // [spring-ai-mcp-inspector PATCH] Set up the DOM-level tap on the outer
  // sandbox iframe. The tap is installed after the iframe is mounted by the
  // @mcp-ui/client AppFrame (it creates the iframe inside a React effect).
  // We wait for the iframe element to appear in the container div, then:
  // 1. Add a window message listener for guest->host frames
  // 2. Wrap the outer iframe's contentWindow.postMessage to capture host->guest
  useEffect(() => {
    const container = containerRef.current;
    if (!container || !mcpClient) return;

    let disposed = false;
    const timeoutId = setTimeout(() => {
      if (disposed) return;
      // The @mcp-ui/client AppFrame has a 10s timeout internally; if we haven't
      // found the iframe by now, it's an error state.
      if (!container.querySelector("iframe")) {
        setError("Sandbox proxy iframe did not mount within timeout");
        setLifecycleState("error");
      }
    }, 10_000);

    // Poll for the iframe to appear (the library mounts it in its own effect)
    const pollInterval = setInterval(() => {
      if (disposed) {
        clearInterval(pollInterval);
        return;
      }
      const iframe = container.querySelector("iframe");
      if (!iframe) return;
      clearInterval(pollInterval);
      clearTimeout(timeoutId);

      const outerIframe = iframe as HTMLIFrameElement;
      const contentWindow = outerIframe.contentWindow;
      if (!contentWindow) return;

      setLifecycleState("sandbox-waiting");

      // --- guest->host direction ---
      // The sandbox proxy page relays guest messages to window.parent with
      // targetOrigin "*", so at host level the message arrives from the outer
      // iframe. Filter by source === outerIframe.contentWindow.
      const onMessage = (event: MessageEvent) => {
        if (disposed) return;
        // Only accept messages from our outer sandbox iframe.
        // This is the "wrong origins are rejected" acceptance criterion.
        if (event.source !== contentWindow) return;
        if (!event.data || typeof event.data !== "object") return;
        if (event.data.jsonrpc !== "2.0") return;

        emitTraffic("guest->host", event.data);

        // Lifecycle transitions from guest->host frames
        const method = event.data.method as string | undefined;
        if (method === "ui/notifications/sandbox-proxy-ready") {
          setLifecycleState("initializing");
        }
        if (method === "ui/notifications/initialized") {
          setLifecycleState("ready");
        }
        if (method === "ui/resource-teardown") {
          setLifecycleState("torn-down");
        }
      };
      window.addEventListener("message", onMessage);

      // --- host->guest direction ---
      // Wrap the outer iframe's contentWindow.postMessage to capture outbound
      // messages. The library calls contentWindow.postMessage on the iframe.
      // Using bind() to capture the original, then replacing.
      const origPostMessage = contentWindow.postMessage.bind(contentWindow);
      const wrappedPostMessage = (
        message: unknown,
        targetOrigin?: string,
      ) => {
        if (
          !disposed &&
          message &&
          typeof message === "object" &&
          (message as Record<string, unknown>).jsonrpc === "2.0"
        ) {
          emitTraffic(
            "host->guest",
            message as Record<string, unknown>,
          );
          const method = (message as Record<string, unknown>)
            .method as string | undefined;
          if (method === "ui/initialize") {
            setLifecycleState("initializing");
          }
        }
        return origPostMessage(message, targetOrigin ?? "*");
      };

      // Override contentWindow.postMessage. This is safe because the library
      // holds a reference to the contentWindow object, and we're replacing
      // the method on that object.
      try {
        Object.defineProperty(contentWindow, "postMessage", {
          value: wrappedPostMessage,
          writable: true,
          configurable: true,
        });
      } catch {
        // If we can't override postMessage (cross-origin restrictions),
        // we fall back to observing only guest->host traffic. This is a
        // degraded mode but not an error.
        console.warn(
          "Could not wrap iframe.postMessage; host->guest traffic tap degraded",
        );
      }

      tapCleanupRef.current = () => {
        window.removeEventListener("message", onMessage);
        try {
          Object.defineProperty(contentWindow, "postMessage", {
            value: origPostMessage,
            writable: true,
            configurable: true,
          });
        } catch {
          // Ignore restore errors
        }
      };
    }, 100);

    return () => {
      disposed = true;
      clearInterval(pollInterval);
      clearTimeout(timeoutId);
      tapCleanupRef.current?.();
      tapCleanupRef.current = null;
    };
  }, [mcpClient, emitTraffic, setLifecycleState]);

  const normalizedToolResult = useMemo<CallToolResult | undefined>(() => {
    if (!toolResult) {
      return undefined;
    }

    if ("content" in toolResult) {
      const parsedResult = CallToolResultSchema.safeParse(toolResult);
      return parsedResult.success ? parsedResult.data : undefined;
    }

    if ("toolResult" in toolResult) {
      const parsedResult = CallToolResultSchema.safeParse(
        toolResult.toolResult,
      );
      return parsedResult.success ? parsedResult.data : undefined;
    }

    return undefined;
  }, [toolResult]);

  const hostContext: McpUiHostContext = useMemo(
    () => ({
      theme: document.documentElement.classList.contains("dark")
        ? "dark"
        : "light",
    }),
    [],
  );

  const handleOpenLink = async ({ url }: { url: string }) => {
    let isError = true;
    if (url.startsWith("https://") || url.startsWith("http://")) {
      window.open(url, "_blank");
      isError = false;
    }
    return { isError };
  };

  const handleMessage = async (
    params: McpUiMessageRequest["params"],
    // eslint-disable-next-line @typescript-eslint/no-unused-vars
    _extra: RequestHandlerExtra,
  ): Promise<McpUiMessageResult> => {
    const message = params.content
      .filter((block): block is ContentBlock & { type: "text" } =>
        Boolean(block.type === "text"),
      )
      .map((block) => block.text)
      .join("\n");

    if (message) {
      toast({
        description: message,
      });
    }

    return {};
  };

  const handleLoggingMessage = (params: LoggingMessageNotificationParams) => {
    if (onNotification) {
      onNotification({
        method: "notifications/message",
        params,
      } as ServerNotification);
    }
  };

  // [spring-ai-mcp-inspector PATCH] onCallTool handler: reject tools/call
  // when the tool's ui.visibility excludes "app".
  const handleCallTool = useCallback(
    async (
      params: { name: string; arguments?: Record<string, unknown> },
      extra?: RequestHandlerExtra,
    ): Promise<CallToolResult> => {
      const toolName = params.name;
      const check = checkToolVisibility(tools, toolName);
      if (!check.allowed) {
        return {
          isError: true,
          content: [
            {
              type: "text",
              text: `Tool '${toolName}' is not available to apps`,
            },
          ],
        };
      }
      const result = await mcpClient!.request(
        {
          method: "tools/call",
          params: { name: toolName, arguments: params.arguments },
        },
        CallToolResultSchema,
        { signal: extra?.signal },
      );
      return result;
    },
    [mcpClient, tools],
  );

  if (!mcpClient) {
    return (
      <Alert>
        <AlertCircle className="h-4 w-4" />
        <AlertDescription>Waiting for MCP client...</AlertDescription>
      </Alert>
    );
  }

  return (
    <div className="flex flex-col h-full">
      {error && (
        <Alert variant="destructive" className="mb-4">
          <AlertCircle className="h-4 w-4" />
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      )}

      <div
        ref={containerRef}
        className="flex-1 border rounded overflow-hidden"
        style={{ minHeight: "400px" }}
      >
        <McpUiAppRenderer
          client={mcpClient}
          onOpenLink={handleOpenLink}
          onMessage={handleMessage}
          onLoggingMessage={handleLoggingMessage}
          onCallTool={handleCallTool}
          toolName={tool.name}
          hostContext={hostContext}
          toolInput={toolInput}
          toolResult={normalizedToolResult}
          sandbox={{
            url: new URL(sandboxPath, window.location.origin),
            // From the UI resource meta (readResourceCsp), never from the tool
            // meta: McpUiToolMeta.csp is `never` in ext-apps 1.7.2.
            csp: resourceCsp,
          }}
          onError={(err) => {
            setError(err.message);
            setLifecycleState("error");
          }}
        />
      </div>
    </div>
  );
};

export default AppRenderer;
