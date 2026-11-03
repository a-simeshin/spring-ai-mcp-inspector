/**
 * [spring-ai-mcp-inspector PATCH] Tests for the suppressed-404 path
 * (issue #267): a 404 on the mount-time list/prefill fetches (backend without
 * the auth-profile API, or its in-memory store restarted) must resolve to an
 * empty profile set WITHOUT a destructive toast, while non-404 failures keep
 * the existing error toast.
 */
import { act, render, waitFor } from "@testing-library/react";
import "@testing-library/jest-dom";
import AuthProfiles from "../AuthProfiles";
import { DEFAULT_INSPECTOR_CONFIG } from "@/lib/constants";
import { InspectorConfig } from "@/lib/configurationTypes";

const mockToast = jest.fn();
jest.mock("@/lib/hooks/useToast", () => ({
  useToast: () => ({ toast: mockToast }),
}));

const CONFIG: InspectorConfig = {
  ...DEFAULT_INSPECTOR_CONFIG,
  MCP_PROXY_FULL_ADDRESS: {
    ...DEFAULT_INSPECTOR_CONFIG.MCP_PROXY_FULL_ADDRESS,
    value: "http://localhost:8080/mcp-inspector-api",
  },
  MCP_PROXY_AUTH_TOKEN: {
    ...DEFAULT_INSPECTOR_CONFIG.MCP_PROXY_AUTH_TOKEN,
    value: "inspector-token",
  },
};

const okJson = (body: unknown, status = 200) =>
  new Response(body === null ? null : JSON.stringify(body), { status });

const notFound = () => okJson({}, 404);
const serverError = () => okJson({}, 500);

function renderPanel() {
  const props = {
    config: CONFIG,
    profiles: [] as Parameters<typeof AuthProfiles>[0]["profiles"],
    onProfilesChange: jest.fn(),
    activeProfileId: null,
    onActiveProfileChange: jest.fn(),
  };
  const utils = render(<AuthProfiles {...props} />);
  return { ...utils, props };
}

describe("AuthProfiles 404 handling (issue #267)", () => {
  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
    mockToast.mockClear();
    // Mount-time GET list + GET prefill: both 404 by default in this suite.
    global.fetch = jest.fn().mockResolvedValue(notFound()) as jest.Mock;
  });

  afterEach(() => {
    jest.restoreAllMocks();
  });

  it("suppresses the toast and resolves an empty list on 404", async () => {
    const { props } = renderPanel();
    await waitFor(() => {
      expect(props.onProfilesChange).toHaveBeenCalledWith([]);
    });
    // Give the parallel prefill promise a tick to settle.
    await act(async () => {
      await new Promise((r) => setTimeout(r, 0));
    });
    expect(mockToast).not.toHaveBeenCalled();
  });

  it("suppresses the toast when only prefill 404s", async () => {
    global.fetch = jest.fn(async (url: string) =>
      String(url).includes("/prefill") ? notFound() : okJson([]),
    ) as jest.Mock;
    renderPanel();
    await act(async () => {
      await new Promise((r) => setTimeout(r, 0));
    });
    expect(mockToast).not.toHaveBeenCalled();
  });

  it("keeps the destructive toast for a non-404 list failure", async () => {
    global.fetch = jest.fn(async (url: string) =>
      String(url).includes("/prefill") ? okJson([]) : serverError(),
    ) as jest.Mock;
    renderPanel();
    await waitFor(() => {
      expect(mockToast).toHaveBeenCalled();
    });
    expect(mockToast).toHaveBeenCalledWith(
      expect.objectContaining({ variant: "destructive" }),
    );
  });

  it("keeps the destructive toast for a non-404 prefill failure", async () => {
    global.fetch = jest.fn(async (url: string) =>
      String(url).includes("/prefill") ? serverError() : okJson([]),
    ) as jest.Mock;
    renderPanel();
    await waitFor(() => {
      expect(mockToast).toHaveBeenCalled();
    });
  });
});
