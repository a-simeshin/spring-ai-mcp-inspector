// [spring-ai-mcp-inspector PATCH] UI-level tests for saved connections interactions.
// See NOTICE.d/saved-connections.txt for details.

import { render, screen, fireEvent } from "@testing-library/react";
import "@testing-library/jest-dom";
import { describe, it, beforeEach, jest } from "@jest/globals";
import Sidebar from "../Sidebar";
import { DEFAULT_INSPECTOR_CONFIG } from "@/lib/constants";
import { TooltipProvider } from "@/components/ui/tooltip";
import type { SavedConnection } from "@/lib/types/savedConnection";

// Mock theme hook
jest.mock("../../lib/hooks/useTheme", () => ({
  __esModule: true,
  default: () => ["light", jest.fn()],
}));

// Mock toast hook
const mockToast = jest.fn();
jest.mock("@/lib/hooks/useToast", () => ({
  useToast: () => ({
    toast: mockToast,
  }),
}));

// Mock navigator clipboard
const mockClipboardWrite = jest.fn(() => Promise.resolve());
Object.defineProperty(navigator, "clipboard", {
  value: {
    writeText: mockClipboardWrite,
  },
});

const makeConnection = (
  overrides?: Partial<SavedConnection>,
): SavedConnection => ({
  id: "conn-1",
  name: "Test Server",
  transport: "sse",
  connectionType: "proxy",
  url: "http://localhost:8080/sse",
  customHeaders: [],
  createdAt: 1000,
  lastUsedAt: 2000,
  ...overrides,
});

describe("Sidebar saved connections UI", () => {
  const defaultProps = {
    connectionStatus: "disconnected" as const,
    transportType: "sse" as const,
    setTransportType: jest.fn(),
    command: "",
    setCommand: jest.fn(),
    args: "",
    setArgs: jest.fn(),
    sseUrl: "http://localhost:8080/sse",
    setSseUrl: jest.fn(),
    oauthClientId: "",
    setOauthClientId: jest.fn(),
    oauthClientSecret: "",
    setOauthClientSecret: jest.fn(),
    oauthScope: "",
    setOauthScope: jest.fn(),
    env: {},
    setEnv: jest.fn(),
    customHeaders: [],
    setCustomHeaders: jest.fn(),
    onConnect: jest.fn(),
    onDisconnect: jest.fn(),
    stdErrNotifications: [],
    clearStdErrNotifications: jest.fn(),
    logLevel: "info" as const,
    sendLogLevelRequest: jest.fn(),
    loggingSupported: true,
    config: DEFAULT_INSPECTOR_CONFIG,
    setConfig: jest.fn(),
    connectionType: "proxy" as const,
    setConnectionType: jest.fn(),
    savedConnections: [] as SavedConnection[],
    activeConnectionId: undefined as string | undefined,
    onSaveConnection: jest.fn() as (name: string) => SavedConnection,
    onDeleteConnection: jest.fn(),
    onSelectConnection: jest.fn(),
  };

  const renderSidebar = (props = {}) => {
    return render(
      <TooltipProvider>
        <Sidebar {...defaultProps} {...props} />
      </TooltipProvider>,
    );
  };

  beforeEach(() => {
    jest.clearAllMocks();
  });

  describe("Save Current button", () => {
    it("renders Save Current button", () => {
      renderSidebar();
      expect(screen.getByTestId("save-current-connection")).toBeInTheDocument();
    });

    it("opens save dialog when Save Current is clicked", () => {
      renderSidebar();
      fireEvent.click(screen.getByTestId("save-current-connection"));
      expect(
        screen.getByTestId("save-connection-name-input"),
      ).toBeInTheDocument();
    });

    it("calls onSaveConnection with trimmed name on Save click", () => {
      renderSidebar();
      fireEvent.click(screen.getByTestId("save-current-connection"));
      fireEvent.change(screen.getByTestId("save-connection-name-input"), {
        target: { value: "  My Server  " },
      });
      fireEvent.click(screen.getByTestId("confirm-save-connection"));
      expect(defaultProps.onSaveConnection).toHaveBeenCalledWith("My Server");
    });

    it("calls onSaveConnection on Enter key", () => {
      renderSidebar();
      fireEvent.click(screen.getByTestId("save-current-connection"));
      fireEvent.change(screen.getByTestId("save-connection-name-input"), {
        target: { value: "Enter Server" },
      });
      fireEvent.keyDown(screen.getByTestId("save-connection-name-input"), {
        key: "Enter",
      });
      expect(defaultProps.onSaveConnection).toHaveBeenCalledWith(
        "Enter Server",
      );
    });

    it("closes dialog on Escape key", () => {
      renderSidebar();
      fireEvent.click(screen.getByTestId("save-current-connection"));
      fireEvent.change(screen.getByTestId("save-connection-name-input"), {
        target: { value: "Escape Server" },
      });
      fireEvent.keyDown(screen.getByTestId("save-connection-name-input"), {
        key: "Escape",
      });
      // Dialog should be closed, input should be gone
      expect(
        screen.queryByTestId("save-connection-name-input"),
      ).not.toBeInTheDocument();
      expect(defaultProps.onSaveConnection).not.toHaveBeenCalled();
    });

    it("closes dialog on Cancel click", () => {
      renderSidebar();
      fireEvent.click(screen.getByTestId("save-current-connection"));
      fireEvent.change(screen.getByTestId("save-connection-name-input"), {
        target: { value: "Cancel Server" },
      });
      fireEvent.click(screen.getByText("Cancel"));
      expect(
        screen.queryByTestId("save-connection-name-input"),
      ).not.toBeInTheDocument();
      expect(defaultProps.onSaveConnection).not.toHaveBeenCalled();
    });

    it("disables Save button when name is empty", () => {
      renderSidebar();
      fireEvent.click(screen.getByTestId("save-current-connection"));
      const saveButton = screen.getByTestId("confirm-save-connection");
      expect(saveButton).toBeDisabled();
    });
  });

  describe("Overwrite confirm dialog", () => {
    it("shows confirm dialog when saving with duplicate name and different id", () => {
      const existing = makeConnection({ id: "existing-1", name: "Existing" });
      const onSaveConnection = jest.fn() as jest.MockedFunction<
        (name: string) => SavedConnection
      >;
      // First call: no activeConnectionId set, findConnectionByName returns existing
      onSaveConnection.mockReturnValue(existing);

      // Mock window.confirm to return true (overwrite)
      const mockConfirm = jest.spyOn(window, "confirm");
      mockConfirm.mockReturnValue(true);

      renderSidebar({
        savedConnections: [existing],
        onSaveConnection,
      });

      fireEvent.click(screen.getByTestId("save-current-connection"));
      fireEvent.change(screen.getByTestId("save-connection-name-input"), {
        target: { value: "Existing" },
      });
      fireEvent.click(screen.getByTestId("confirm-save-connection"));

      expect(onSaveConnection).toHaveBeenCalledWith("Existing");
      mockConfirm.mockRestore();
    });

    it("keeps dialog open when overwrite is cancelled", () => {
      const existing = makeConnection({ id: "existing-1", name: "Existing" });
      const onSaveConnection = jest.fn() as jest.MockedFunction<
        (name: string) => SavedConnection
      >;
      // Return undefined to signal cancel
      onSaveConnection.mockReturnValue(undefined);

      // Mock window.confirm to return false (cancel)
      const mockConfirm = jest.spyOn(window, "confirm");
      mockConfirm.mockReturnValue(false);

      renderSidebar({
        savedConnections: [existing],
        onSaveConnection,
      });

      fireEvent.click(screen.getByTestId("save-current-connection"));
      fireEvent.change(screen.getByTestId("save-connection-name-input"), {
        target: { value: "Existing" },
      });
      fireEvent.click(screen.getByTestId("confirm-save-connection"));

      // Dialog should stay open (input still visible)
      expect(
        screen.getByTestId("save-connection-name-input"),
      ).toBeInTheDocument();
      mockConfirm.mockRestore();
    });
  });

  describe("Saved connection entries", () => {
    it("renders saved connection entries", () => {
      const connections = [
        makeConnection({ id: "c1", name: "Server One" }),
        makeConnection({ id: "c2", name: "Server Two" }),
      ];
      renderSidebar({ savedConnections: connections });
      expect(screen.getByText("Server One")).toBeInTheDocument();
      expect(screen.getByText("Server Two")).toBeInTheDocument();
    });

    it("calls onSelectConnection when entry is clicked", () => {
      const connections = [makeConnection({ id: "c1", name: "Server One" })];
      const onSelectConnection = jest.fn();
      renderSidebar({ savedConnections: connections, onSelectConnection });
      fireEvent.click(screen.getByTestId("saved-connection-c1"));
      expect(onSelectConnection).toHaveBeenCalledWith(
        expect.objectContaining({ id: "c1" }),
      );
    });

    it("calls onDeleteConnection when delete button is clicked", () => {
      const connections = [makeConnection({ id: "c1", name: "Server One" })];
      const onDeleteConnection = jest.fn();
      renderSidebar({ savedConnections: connections, onDeleteConnection });
      fireEvent.click(screen.getByTestId("delete-saved-connection-c1"));
      expect(onDeleteConnection).toHaveBeenCalledWith("c1");
    });

    it("highlights active connection", () => {
      const connections = [
        makeConnection({ id: "c1", name: "Server One" }),
        makeConnection({ id: "c2", name: "Server Two" }),
      ];
      renderSidebar({
        savedConnections: connections,
        activeConnectionId: "c1",
      });
      const activeEntry = screen.getByTestId("saved-connection-c1");
      expect(activeEntry.className).toContain("bg-accent");
      expect(activeEntry.className).toContain("border-border");
      const inactiveEntry = screen.getByTestId("saved-connection-c2");
      expect(inactiveEntry.className).toContain("border-transparent");
    });

    it("shows 'No saved connections yet' when list is empty", () => {
      renderSidebar({ savedConnections: [] });
      expect(screen.getByText("No saved connections yet.")).toBeInTheDocument();
    });
  });

  describe("Saved connections collapse/expand", () => {
    it("collapses and expands saved connections section", () => {
      renderSidebar();
      const toggleButton = screen.getByRole("button", {
        name: /saved connections/i,
      });
      // Initially expanded (default state)
      expect(screen.getByTestId("save-current-connection")).toBeInTheDocument();
      // Collapse
      fireEvent.click(toggleButton);
      expect(
        screen.queryByTestId("save-current-connection"),
      ).not.toBeInTheDocument();
      // Expand
      fireEvent.click(toggleButton);
      expect(screen.getByTestId("save-current-connection")).toBeInTheDocument();
    });
  });
});
