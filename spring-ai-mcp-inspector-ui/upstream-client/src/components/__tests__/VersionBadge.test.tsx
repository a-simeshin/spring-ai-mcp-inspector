import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import "@testing-library/jest-dom";
import { describe, it, beforeEach, jest } from "@jest/globals";
import VersionBadge from "../VersionBadge";
import { DEFAULT_INSPECTOR_CONFIG } from "@/lib/constants";
import { TooltipProvider } from "@/components/ui/tooltip";

// Mock configUtils to return predictable proxy address
jest.mock("@/utils/configUtils", () => ({
  getMCPProxyAuthToken: () => ({ token: "", header: "X-MCP-Proxy-Auth" }),
}));

// Mock fetch
const mockFetch = jest.fn() as jest.MockedFunction<typeof fetch>;
global.fetch = mockFetch;

const renderBadge = (props = {}) => {
  return render(
    <TooltipProvider>
      <VersionBadge
        mcpSessionId="test-session-id"
        config={DEFAULT_INSPECTOR_CONFIG}
        connected={true}
        {...props}
      />
    </TooltipProvider>,
  );
};

const okSnapshot = {
  clientRequestedVersion: "2025-11-25",
  negotiatedVersion: "2025-11-25",
  serverName: "test-server",
  serverVersion: "1.0.0",
  capabilities: { tools: true, logging: true },
  compatibility: {
    severity: "OK" as const,
    affectedMethods: [],
    summary: "ok",
  },
};

const downgradeSnapshot = {
  clientRequestedVersion: "2026-07-28",
  negotiatedVersion: "2025-11-25",
  serverName: "test-server",
  serverVersion: "1.0.0",
  capabilities: { tools: true },
  compatibility: {
    severity: "DOWNGRADE" as const,
    affectedMethods: [
      "ping",
      "logging/setLevel",
      "resources/subscribe",
      "resources/unsubscribe",
    ],
    summary: "downgrade",
  },
};

const unknownSnapshot = {
  clientRequestedVersion: "2025-11-25",
  negotiatedVersion: "9999-99-99",
  serverName: "test-server",
  serverVersion: "1.0.0",
  capabilities: {},
  compatibility: {
    severity: "UNKNOWN" as const,
    affectedMethods: [],
    summary: "unknown",
  },
};

// Click the chevron icon to expand details. The chevron is an SVG inside
// a div with role="status". We click the last SVG in the badge.
const clickChevron = () => {
  const badge = screen.getByRole("status");
  const chevron = badge.querySelector("svg:last-child") as SVGElement;
  expect(chevron).toBeTruthy();
  fireEvent.click(chevron);
};

describe("VersionBadge", () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it("renders nothing when not connected", () => {
    renderBadge({ connected: false });
    expect(screen.queryByText(/2025-11-25/)).not.toBeInTheDocument();
  });

  it("renders nothing when mcpSessionId is null", () => {
    renderBadge({ mcpSessionId: null });
    expect(screen.queryByText(/2025-11-25/)).not.toBeInTheDocument();
  });

  it("renders ok badge with negotiated version when severity is OK", async () => {
    mockFetch.mockResolvedValueOnce(
      new Response(JSON.stringify(okSnapshot), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      }),
    );

    renderBadge();

    await waitFor(() => {
      expect(screen.getByText("2025-11-25")).toBeInTheDocument();
    });
    expect(screen.queryByText(/Downgraded/)).not.toBeInTheDocument();
  });

  it("renders downgrade notice with affected methods when severity is DOWNGRADE", async () => {
    mockFetch.mockResolvedValueOnce(
      new Response(JSON.stringify(downgradeSnapshot), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      }),
    );

    renderBadge();

    await waitFor(() => {
      expect(screen.getByText("2025-11-25")).toBeInTheDocument();
    });

    clickChevron();

    await waitFor(() => {
      // The notice text contains both versions
      expect(screen.getAllByText(/2026-07-28/).length).toBeGreaterThan(0);
      // All 4 affected methods listed
      expect(screen.getByText("ping")).toBeInTheDocument();
      expect(screen.getByText("logging/setLevel")).toBeInTheDocument();
      expect(screen.getByText("resources/subscribe")).toBeInTheDocument();
      expect(screen.getByText("resources/unsubscribe")).toBeInTheDocument();
    });
  });

  it("renders unknown revision notice when severity is UNKNOWN", async () => {
    mockFetch.mockResolvedValueOnce(
      new Response(JSON.stringify(unknownSnapshot), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      }),
    );

    renderBadge();

    await waitFor(() => {
      expect(screen.getByText("9999-99-99")).toBeInTheDocument();
    });

    clickChevron();

    await waitFor(() => {
      expect(screen.getByText(/Unrecognized Revision/)).toBeInTheDocument();
    });
  });

  it("renders nothing when fetch returns 404 (no snapshot)", async () => {
    mockFetch.mockResolvedValueOnce(
      new Response(JSON.stringify({ error: "not found" }), { status: 404 }),
    );

    const { container } = renderBadge();

    await waitFor(() => {
      expect(container.firstChild).toBeNull();
    });
  });

  it("shows server name and version in details", async () => {
    mockFetch.mockResolvedValueOnce(
      new Response(JSON.stringify(okSnapshot), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      }),
    );

    renderBadge();

    await waitFor(() => {
      expect(screen.getByText("2025-11-25")).toBeInTheDocument();
    });

    clickChevron();

    await waitFor(() => {
      expect(screen.getByText("test-server")).toBeInTheDocument();
      expect(screen.getByText(/Version: 1.0.0/)).toBeInTheDocument();
    });
  });

  it("shows capabilities in details", async () => {
    mockFetch.mockResolvedValueOnce(
      new Response(JSON.stringify(okSnapshot), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      }),
    );

    renderBadge();

    await waitFor(() => {
      expect(screen.getByText("2025-11-25")).toBeInTheDocument();
    });

    clickChevron();

    await waitFor(() => {
      expect(screen.getByText("tools")).toBeInTheDocument();
      expect(screen.getByText("logging")).toBeInTheDocument();
    });
  });
});
