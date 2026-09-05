// [spring-ai-mcp-inspector PATCH] App-level integration tests for saved
// connections save/overwrite/select flow with real callbacks and storage.
// See NOTICE.d/saved-connections.txt for details.

import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import "@testing-library/jest-dom";
import App from "../App";
import { DEFAULT_INSPECTOR_CONFIG } from "../lib/constants";
import { InspectorConfig } from "../lib/configurationTypes";
import {
  SAVED_CONNECTIONS_KEY,
  saveConnection,
  stripSecrets,
} from "../lib/savedConnections";
import type { SavedConnection } from "../lib/types/savedConnection";
import { TooltipProvider } from "@/components/ui/tooltip";

// Mock auth dependencies
jest.mock("@modelcontextprotocol/sdk/client/auth.js", () => ({
  auth: jest.fn(),
}));

jest.mock("../lib/oauth-state-machine", () => ({
  OAuthStateMachine: jest.fn(),
}));

jest.mock("../lib/auth", () => ({
  InspectorOAuthClientProvider: jest.fn().mockImplementation(() => ({
    tokens: jest.fn().mockResolvedValue(null),
    clear: jest.fn(),
  })),
  DebugInspectorOAuthClientProvider: jest.fn(),
}));

// Mock the config utils
jest.mock("../utils/configUtils", () => ({
  ...jest.requireActual("../utils/configUtils"),
  getMCPProxyAddress: jest.fn(() => "http://localhost:6277"),
  getMCPProxyAuthToken: jest.fn((config: InspectorConfig) => ({
    token: config.MCP_PROXY_AUTH_TOKEN.value,
    header: "X-MCP-Proxy-Auth",
  })),
  getInitialTransportType: jest.fn(() => "sse"),
  getInitialSseUrl: jest.fn(() => "http://localhost:3001/sse"),
  getInitialCommand: jest.fn(() => ""),
  getInitialArgs: jest.fn(() => ""),
  initializeInspectorConfig: jest.fn(() => DEFAULT_INSPECTOR_CONFIG),
  saveInspectorConfig: jest.fn(),
}));

// Mock other dependencies
jest.mock("../lib/hooks/useConnection", () => ({
  useConnection: () => ({
    connectionStatus: "disconnected",
    serverCapabilities: null,
    mcpClient: null,
    requestHistory: [],
    clearRequestHistory: jest.fn(),
    makeRequest: jest.fn(),
    sendNotification: jest.fn(),
    handleCompletion: jest.fn(),
    completionsSupported: false,
    connect: jest.fn(),
    disconnect: jest.fn(),
  }),
}));

jest.mock("../lib/hooks/useDraggablePane", () => ({
  useDraggablePane: () => ({
    height: 300,
    handleDragStart: jest.fn(),
  }),
  useDraggableSidebar: () => ({
    width: 320,
    isDragging: false,
    handleDragStart: jest.fn(),
  }),
}));

// Mock theme hook
jest.mock("../lib/hooks/useTheme", () => ({
  __esModule: true,
  default: () => ["light", jest.fn()],
}));

// Mock fetch
global.fetch = jest.fn();

function seedConnection(): SavedConnection {
  const draft = {
    name: "first",
    transport: "sse" as const,
    connectionType: "proxy" as const,
    url: "http://localhost:3001/sse",
    customHeaders: [
      { name: "X-Custom", value: "ok", enabled: true },
    ],
    env: { FOO: "bar" },
  };
  // stripSecrets clears all header values, so the saved connection
  // has empty header values (just like real save flow)
  const stripped = stripSecrets(draft);
  return saveConnection(stripped);
}

describe("App saved connections integration", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    localStorage.clear();
    (global.fetch as jest.Mock).mockResolvedValue({
      json: () =>
        Promise.resolve({
          defaultEnvironment: { TEST_ENV: "test" },
          defaultCommand: "test-command",
          defaultArgs: "test-args",
        }),
    });
  });

  afterEach(() => {
    jest.clearAllMocks();
  });

  it("saves a connection and verifies it appears in localStorage", async () => {
    render(
      <TooltipProvider>
        <App />
      </TooltipProvider>,
    );

    // Wait for App to render and fetch config
    await waitFor(() => {
      expect(global.fetch).toHaveBeenCalled();
    });

    // Open the save dialog
    const saveButton = screen.getByTestId("save-current-connection");
    fireEvent.click(saveButton);

    // Type a name
    const nameInput = screen.getByTestId("save-connection-name-input");
    fireEvent.change(nameInput, { target: { value: "second" } });

    // Click Save
    const confirmButton = screen.getByTestId("confirm-save-connection");
    fireEvent.click(confirmButton);

    // Verify localStorage has the saved connection
    const stored = localStorage.getItem(SAVED_CONNECTIONS_KEY);
    expect(stored).toBeTruthy();
    const parsed = JSON.parse(stored!);
    expect(parsed.schemaVersion).toBe(1);
    expect(parsed.connections).toHaveLength(1);
    expect(parsed.connections[0].name).toBe("second");
    // Header values must be empty (stripped by stripSecrets)
    parsed.connections[0].customHeaders.forEach(
      (h: { name: string; value: string }) => {
        expect(h.value).toBe("");
      },
    );
    // Env values must be empty (stripped by stripSecrets)
    if (parsed.connections[0].env) {
      Object.values(parsed.connections[0].env as Record<string, string>).forEach(
        (v: string) => {
          expect(v).toBe("");
        },
      );
    }
  });

  function seedTwoConnections(): { first: SavedConnection; second: SavedConnection } {
    const first = seedConnection();
    const second = saveConnection(
      stripSecrets({
        name: "second",
        transport: "sse" as const,
        connectionType: "proxy" as const,
        url: "http://other-server:8080/sse",
        customHeaders: [
          { name: "X-Other", value: "other-val", enabled: true },
        ],
        env: { BAR: "baz" },
      }),
    );
    return { first, second };
  }

  it("shows confirm dialog when saving selected entry under duplicate name and cancel keeps both entries with original ids", async () => {
    const { first, second } = seedTwoConnections();

    render(
      <TooltipProvider>
        <App />
      </TooltipProvider>,
    );

    await waitFor(() => {
      expect(global.fetch).toHaveBeenCalled();
    });

    // Select the first entry
    fireEvent.click(screen.getByTestId(`saved-connection-${first.id}`));

    // Mock window.confirm to return false (cancel)
    const mockConfirm = jest.spyOn(window, "confirm");
    mockConfirm.mockReturnValue(false);

    // Open the save dialog
    fireEvent.click(screen.getByTestId("save-current-connection"));

    // Type the name of the second entry (duplicate)
    const nameInput = screen.getByTestId("save-connection-name-input");
    fireEvent.change(nameInput, { target: { value: "second" } });

    // Click Save
    fireEvent.click(screen.getByTestId("confirm-save-connection"));

    // window.confirm should have been called with the overwrite message
    expect(mockConfirm).toHaveBeenCalledWith(
      'Connection "second" already exists. Overwrite?',
    );

    // The dialog should stay open (input still visible)
    expect(
      screen.getByTestId("save-connection-name-input"),
    ).toBeInTheDocument();

    // Both entries must still exist with their original ids
    const stored = JSON.parse(localStorage.getItem(SAVED_CONNECTIONS_KEY)!);
    expect(stored.connections).toHaveLength(2);
    const ids = stored.connections.map((c: { id: string }) => c.id);
    expect(ids).toContain(first.id);
    expect(ids).toContain(second.id);
    // Original names unchanged
    const names = stored.connections.map((c: { name: string }) => c.name);
    expect(names).toContain("first");
    expect(names).toContain("second");

    mockConfirm.mockRestore();
  });

  it("overwrites existing entry when confirm is accepted and does not create a silent duplicate", async () => {
    const { first, second } = seedTwoConnections();

    render(
      <TooltipProvider>
        <App />
      </TooltipProvider>,
    );

    await waitFor(() => {
      expect(global.fetch).toHaveBeenCalled();
    });

    // Select the first entry
    fireEvent.click(screen.getByTestId(`saved-connection-${first.id}`));

    // Mock window.confirm to return true (confirm overwrite)
    const mockConfirm = jest.spyOn(window, "confirm");
    mockConfirm.mockReturnValue(true);

    // Open the save dialog
    fireEvent.click(screen.getByTestId("save-current-connection"));

    // Type the name of the second entry (duplicate)
    const nameInput = screen.getByTestId("save-connection-name-input");
    fireEvent.change(nameInput, { target: { value: "second" } });

    // Click Save
    fireEvent.click(screen.getByTestId("confirm-save-connection"));

    // window.confirm should have been called
    expect(mockConfirm).toHaveBeenCalledWith(
      'Connection "second" already exists. Overwrite?',
    );

    // The dialog should close (input no longer visible)
    await waitFor(() => {
      expect(
        screen.queryByTestId("save-connection-name-input"),
      ).not.toBeInTheDocument();
    });

    // Exactly 2 entries: the second entry was overwritten, not duplicated
    const stored = JSON.parse(localStorage.getItem(SAVED_CONNECTIONS_KEY)!);
    expect(stored.connections).toHaveLength(2);
    const ids = stored.connections.map((c: { id: string }) => c.id);
    // The second entry's id is preserved (overwritten, not replaced)
    expect(ids).toContain(first.id);
    expect(ids).toContain(second.id);
    // The overwritten entry has the name "second" (from the dialog)
    const overwritten = stored.connections.find(
      (c: { id: string }) => c.id === second.id,
    );
    expect(overwritten.name).toBe("second");
    // Header values are empty (stripped by stripSecrets)
    overwritten.customHeaders.forEach(
      (h: { name: string; value: string }) => {
        expect(h.value).toBe("");
      },
    );

    mockConfirm.mockRestore();
  });

  it("selects a saved connection and populates all form fields", async () => {
    // Seed a connection "first" with specific headers and env
    const draft = {
      name: "first",
      transport: "sse" as const,
      connectionType: "proxy" as const,
      url: "http://my-server:8080/sse",
      customHeaders: [
        { name: "X-Custom", value: "should-be-empty", enabled: true },
      ],
      env: { MY_VAR: "should-be-empty" },
    };
    const stripped = stripSecrets(draft);
    const saved = saveConnection(stripped);

    render(
      <TooltipProvider>
        <App />
      </TooltipProvider>,
    );

    await waitFor(() => {
      expect(global.fetch).toHaveBeenCalled();
    });

    // The saved connection should be visible in the sidebar
    expect(screen.getByText("first")).toBeInTheDocument();

    // Click the saved connection
    fireEvent.click(screen.getByTestId(`saved-connection-${saved.id}`));

    // Transport Type should be SSE
    await waitFor(() => {
      const transportTrigger = screen.getByRole("combobox", {
        name: /transport type/i,
      });
      expect(transportTrigger).toHaveTextContent("SSE");
    });

    // Connection Type should be visible (non-STDIO) and set to "Via Proxy"
    await waitFor(() => {
      const connTypeTrigger = screen.getByRole("combobox", {
        name: /connection type/i,
      });
      expect(connTypeTrigger).toHaveTextContent("Via Proxy");
    });

    // The URL should be populated
    await waitFor(() => {
      const urlInput = screen.getByLabelText("URL") as HTMLInputElement;
      expect(urlInput.value).toBe("http://my-server:8080/sse");
    });

    // Command should NOT be in the document (stdio-only)
    expect(screen.queryByLabelText("Command")).not.toBeInTheDocument();

    // Arguments should NOT be in the document (stdio-only)
    expect(screen.queryByLabelText("Arguments")).not.toBeInTheDocument();

    // Env vars button should NOT be in the document (stdio-only)
    expect(screen.queryByTestId("env-vars-button")).not.toBeInTheDocument();

    // Open the auth section and check custom headers
    fireEvent.click(screen.getByTestId("auth-button"));
    await waitFor(() => {
      const headerNameInput = screen.getByTestId(
        "header-name-input-0",
      ) as HTMLInputElement;
      expect(headerNameInput.value).toBe("X-Custom");
      const headerValueInput = screen.getByTestId(
        "header-value-input-0",
      ) as HTMLInputElement;
      // Header values are stripped by stripSecrets
      expect(headerValueInput.value).toBe("");
    });
  });

  it("selects a stdio saved connection and populates all fields", async () => {
    // Seed a stdio connection with command, args, env, custom headers
    const stdioDraft = {
      name: "my-stdio",
      transport: "stdio" as const,
      connectionType: "proxy" as const,
      command: "node",
      args: "server.js",
      env: { FOO: "bar" },
      customHeaders: [
        { name: "X-Header", value: "should-be-empty", enabled: true },
      ],
    };
    const stripped = stripSecrets(stdioDraft);
    const saved = saveConnection(stripped);

    render(
      <TooltipProvider>
        <App />
      </TooltipProvider>,
    );

    await waitFor(() => {
      expect(global.fetch).toHaveBeenCalled();
    });

    // The saved connection should be visible
    expect(screen.getByText("my-stdio")).toBeInTheDocument();

    // Click the saved connection
    fireEvent.click(screen.getByTestId(`saved-connection-${saved.id}`));

    // Transport Type should be STDIO
    await waitFor(() => {
      const transportTrigger = screen.getByRole("combobox", {
        name: /transport type/i,
      });
      expect(transportTrigger).toHaveTextContent("STDIO");
    });

    // URL should NOT be in the document (non-STDIO only)
    expect(screen.queryByLabelText("URL")).not.toBeInTheDocument();

    // Connection Type should NOT be in the document (non-STDIO only)
    expect(screen.queryByRole("combobox", { name: /connection type/i }))
      .not.toBeInTheDocument();

    // Command should be populated
    await waitFor(() => {
      const commandInput = screen.getByLabelText(
        "Command",
      ) as HTMLInputElement;
      expect(commandInput.value).toBe("node");
    });

    // Arguments should be populated
    await waitFor(() => {
      const argsInput = screen.getByLabelText(
        "Arguments",
      ) as HTMLInputElement;
      expect(argsInput.value).toBe("server.js");
    });

    // Open the env vars section and check env values
    fireEvent.click(screen.getByTestId("env-vars-button"));
    await waitFor(() => {
      const envKeyInput = screen.getByLabelText(
        "Environment variable key 1",
      ) as HTMLInputElement;
      expect(envKeyInput.value).toBe("FOO");
      const envValueInput = screen.getByLabelText(
        "Environment variable value 1",
      ) as HTMLInputElement;
      // Env values are stripped by stripSecrets
      expect(envValueInput.value).toBe("");
    });

    // Open the auth section and check custom headers
    fireEvent.click(screen.getByTestId("auth-button"));
    await waitFor(() => {
      const headerNameInput = screen.getByTestId(
        "header-name-input-0",
      ) as HTMLInputElement;
      expect(headerNameInput.value).toBe("X-Header");
      const headerValueInput = screen.getByTestId(
        "header-value-input-0",
      ) as HTMLInputElement;
      // Header values are stripped by stripSecrets
      expect(headerValueInput.value).toBe("");
    });
  });

  it("switches between stdio and sse entries and resets absent fields", async () => {
    // Seed a stdio entry with command/args/env
    const stdioDraft = {
      name: "stdio-server",
      transport: "stdio" as const,
      connectionType: "proxy" as const,
      command: "python",
      args: "main.py --port 8080",
      env: { MODE: "prod" },
      customHeaders: [],
    };
    const stdio = saveConnection(stripSecrets(stdioDraft));

    // Seed an SSE entry (no command/args/env)
    const sseDraft = {
      name: "sse-server",
      transport: "sse" as const,
      connectionType: "direct" as const,
      url: "http://localhost:9999/sse",
      customHeaders: [
        { name: "X-SSE", value: "should-be-empty", enabled: true },
      ],
      env: {},
    };
    const sse = saveConnection(stripSecrets(sseDraft));

    // Seed an empty stdio entry (no command/args/env) to prove reset
    const emptyStdioDraft = {
      name: "empty-stdio",
      transport: "stdio" as const,
      connectionType: "proxy" as const,
      // command, args, env are intentionally undefined
      customHeaders: [],
    };
    const emptyStdio = saveConnection(stripSecrets(emptyStdioDraft));

    render(
      <TooltipProvider>
        <App />
      </TooltipProvider>,
    );

    await waitFor(() => {
      expect(global.fetch).toHaveBeenCalled();
    });

    // Step 1: Select the stdio entry
    fireEvent.click(screen.getByTestId(`saved-connection-${stdio.id}`));

    // Verify transport type changed to STDIO
    await waitFor(() => {
      expect(
        screen.getByRole("combobox", { name: /transport type/i }),
      ).toHaveTextContent("STDIO");
    });
    await waitFor(() => {
      const commandInput = screen.getByLabelText(
        "Command",
      ) as HTMLInputElement;
      expect(commandInput.value).toBe("python");
    });
    await waitFor(() => {
      const argsInput = screen.getByLabelText(
        "Arguments",
      ) as HTMLInputElement;
      expect(argsInput.value).toBe("main.py --port 8080");
    });

    // Step 2: Select the SSE entry (no command/args/env)
    fireEvent.click(screen.getByTestId(`saved-connection-${sse.id}`));

    // Verify transport type changed to SSE
    await waitFor(() => {
      expect(
        screen.getByRole("combobox", { name: /transport type/i }),
      ).toHaveTextContent("SSE");
    });

    // Verify stdio fields are absent (non-STDIO only)
    expect(screen.queryByLabelText("Command")).not.toBeInTheDocument();
    expect(screen.queryByLabelText("Arguments")).not.toBeInTheDocument();

    // Verify SSE fields are populated
    await waitFor(() => {
      const urlInput = screen.getByLabelText("URL") as HTMLInputElement;
      expect(urlInput.value).toBe("http://localhost:9999/sse");
    });

    // Verify connection type is set to "Direct"
    await waitFor(() => {
      expect(
        screen.getByRole("combobox", { name: /connection type/i }),
      ).toHaveTextContent("Direct");
    });

    // Open auth section and verify custom headers
    fireEvent.click(screen.getByTestId("auth-button"));
    await waitFor(() => {
      const headerNameInput = screen.getByTestId(
        "header-name-input-0",
      ) as HTMLInputElement;
      expect(headerNameInput.value).toBe("X-SSE");
    });

    // Step 3: Select the empty stdio entry to prove command/args/env
    // were reset to empty after SSE select (not stale from step 1)
    fireEvent.click(screen.getByTestId(`saved-connection-${emptyStdio.id}`));

    // Verify transport type changed to STDIO
    await waitFor(() => {
      expect(
        screen.getByRole("combobox", { name: /transport type/i }),
      ).toHaveTextContent("STDIO");
    });

    // Verify SSE fields are absent (inverse reset)
    expect(screen.queryByLabelText("URL")).not.toBeInTheDocument();
    expect(
      screen.queryByRole("combobox", { name: /connection type/i }),
    ).not.toBeInTheDocument();

    // Verify command is empty (proves reset: stale "python" would show
    // if setCommand("") at SSE select had been a no-op)
    await waitFor(() => {
      expect(
        screen.getByLabelText("Command") as HTMLInputElement,
      ).toHaveValue("");
    });
    // Verify args is empty (proves setArgs("") reset)
    await waitFor(() => {
      expect(
        screen.getByLabelText("Arguments") as HTMLInputElement,
      ).toHaveValue("");
    });
    // Verify env vars are empty: open the env section and check no rows
    fireEvent.click(screen.getByTestId("env-vars-button"));
    // An empty env object renders no env key/value inputs; check that
    // the only env-related label is the section header (not key 1)
    expect(
      screen.queryByLabelText("Environment variable key 1"),
    ).not.toBeInTheDocument();
  });
});