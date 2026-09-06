/**
 * [spring-ai-mcp-inspector PATCH] Component tests for the auth-profiles panel
 * (issue #54): editor validation + unique name (AC1), save → server profileId
 * handoff, prefill selection (AC3), list/rename/delete (AC2), secret
 * redaction (AC5) and the authorization-code pending → redirect handoff
 * fields (AC8 / D9B).
 */
import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import "@testing-library/jest-dom";
import AuthProfiles from "../AuthProfiles";
import { DEFAULT_INSPECTOR_CONFIG } from "@/lib/constants";
import { InspectorConfig } from "@/lib/configurationTypes";
import { getPendingAuthCodeFlow } from "@/lib/auth-profiles";

// [spring-ai-mcp-inspector PATCH] PKCE is generated with the Web Crypto API
// (see auth-profiles.ts) — stub it so the redirect assertions see fixed values.
jest.mock("@/lib/auth-profiles", () => ({
  ...jest.requireActual("@/lib/auth-profiles"),
  generateCodeChallengePair: jest.fn(async () => ({
    codeVerifier: "test-verifier-0123456789",
    codeChallenge: "test-challenge-0123456789",
  })),
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

const API_BASE = "http://localhost:8080/mcp-inspector/api";

const okJson = (body: unknown, status = 200) =>
  new Response(body === null ? null : JSON.stringify(body), { status });

function renderPanel(overrides: {
  profiles?: Parameters<typeof AuthProfiles>[0]["profiles"];
  activeProfileId?: string | null;
} = {}) {
  const props = {
    config: CONFIG,
    profiles: overrides.profiles ?? [],
    onProfilesChange: jest.fn(),
    activeProfileId: overrides.activeProfileId ?? null,
    onActiveProfileChange: jest.fn(),
  };
  const utils = render(<AuthProfiles {...props} />);
  return { ...utils, props };
}

describe("AuthProfiles editor validation (AC1)", () => {
  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
    // Mount-time GET list + GET prefill return empty lists.
    global.fetch = jest.fn().mockResolvedValue(okJson([])) as jest.Mock;
  });

  afterEach(() => {
    jest.restoreAllMocks();
    delete (window as { location?: unknown }).location;
  });

  it("blocks save on an empty name and shows the validation error", async () => {
    renderPanel();
    const save = await screen.findByTestId("auth-profile-save");
    expect(save).toBeDisabled();
    fireEvent.click(save);
    expect(await screen.findByTestId("auth-profile-validation")).toHaveTextContent(
      "Profile name is required",
    );
  });

  it("blocks save on a duplicate name", async () => {
    renderPanel({
      profiles: [
        {
          profileId: "p-1",
          name: "Prod",
          type: "BEARER",
        },
      ],
    });
    const save = await screen.findByTestId("auth-profile-save");
    fireEvent.change(screen.getByTestId("auth-profile-name"), {
      target: { value: "prod" },
    });
    fireEvent.change(screen.getByTestId("auth-profile-bearer-token"), {
      target: { value: "tok" },
    });
    expect(save).toBeDisabled();
    expect(
      await screen.findByTestId("auth-profile-validation"),
    ).toHaveTextContent("Profile name already exists");
  });

  it("blocks save on an empty bearer token", async () => {
    renderPanel();
    fireEvent.change(screen.getByTestId("auth-profile-name"), {
      target: { value: "Prod" },
    });
    const save = screen.getByTestId("auth-profile-save");
    expect(save).toBeDisabled();
    fireEvent.change(screen.getByTestId("auth-profile-bearer-token"), {
      target: { value: "token-123" },
    });
    expect(save).toBeEnabled();
  });

  it("saves a bearer profile via POST and activates the server profileId", async () => {
    const fetchMock = global.fetch as jest.Mock;
    fetchMock.mockImplementation(async (url: string) => {
      if (url === `${API_BASE}/auth-profile/prefill`) {
        return okJson([]);
      }
      if (url === `${API_BASE}/auth-profile`) {
        return okJson({ profileId: "p-new-1" });
      }
      if (url === `${API_BASE}/auth-profile` && url.includes("auth-profile")) {
        return okJson({ profileId: "p-new-1" });
      }
      return okJson([]);
    });

    const { props } = renderPanel();
    fireEvent.change(screen.getByTestId("auth-profile-name"), {
      target: { value: "Prod" },
    });
    fireEvent.change(screen.getByTestId("auth-profile-bearer-token"), {
      target: { value: "secret-token" },
    });
    await act(async () => {
      fireEvent.click(screen.getByTestId("auth-profile-save"));
      await new Promise((r) => setTimeout(r, 0));
    });

    await waitFor(() => {
      expect(props.onActiveProfileChange).toHaveBeenCalledWith("p-new-1");
    });
    const postCall = fetchMock.mock.calls.find(
      ([url, init]: [string, RequestInit]) =>
        url === `${API_BASE}/auth-profile` && init?.method === "POST",
    );
    expect(postCall).toBeDefined();
    const body = JSON.parse(String(postCall[1].body));
    expect(body.profile).toEqual({ name: "Prod", type: "BEARER", token: "secret-token" });
    expect(props.onProfilesChange).toHaveBeenCalled();
  });
});

describe("AuthProfiles list / rename / delete (AC2)", () => {
  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
    global.fetch = jest.fn().mockResolvedValue(okJson([])) as jest.Mock;
  });

  afterEach(() => {
    jest.restoreAllMocks();
  });

  it("selects an existing profile as active", async () => {
    const { props } = renderPanel({
      profiles: [
        { profileId: "p-1", name: "Prod", type: "BEARER" },
        { profileId: "p-2", name: "Staging", type: "API_KEY" },
      ],
    });
    await act(async () => {
      fireEvent.click(await screen.findByTestId("auth-profile-select-p-2"));
    });
    expect(props.onActiveProfileChange).toHaveBeenCalledWith("p-2");
  });

  it("renames a profile (PUT with the in-session draft keeps the secret)", async () => {
    const fetchMock = global.fetch as jest.Mock;
    fetchMock.mockImplementation(async (url: string, init?: RequestInit) => {
      if (url === `${API_BASE}/auth-profile/prefill`) {
        return okJson([]);
      }
      if (init?.method === "PUT") {
        return okJson(null, 204);
      }
      return okJson([{ profileId: "p-1", name: "Renamed", type: "BEARER" }]);
    });

    const { props } = renderPanel({
      profiles: [{ profileId: "p-1", name: "Prod", type: "BEARER" }],
    });

    await act(async () => {
      fireEvent.click(await screen.findByTestId("auth-profile-edit-p-1"));
    });
    // The in-session draft is not available (fresh mount) — the summary draft
    // has an empty secret, so a rename must re-enter it (never sent blank).
    fireEvent.change(screen.getByTestId("auth-profile-bearer-token"), {
      target: { value: "re-entered-token" },
    });
    fireEvent.change(screen.getByTestId("auth-profile-name"), {
      target: { value: "Renamed" },
    });
    await act(async () => {
      fireEvent.click(screen.getByTestId("auth-profile-update"));
      await new Promise((r) => setTimeout(r, 0));
    });

    await waitFor(() => {
      const putCall = fetchMock.mock.calls.find(
        ([, init]: [string, RequestInit]) => init?.method === "PUT",
      );
      expect(putCall).toBeDefined();
      const body = JSON.parse(String(putCall[1].body));
      expect(body.profile).toMatchObject({
        name: "Renamed",
        type: "BEARER",
        token: "re-entered-token",
      });
    });
    await waitFor(() => {
      expect(props.onActiveProfileChange).toHaveBeenCalledWith("p-1");
    });
  });

  it("deletes a profile via DELETE (path variable) and clears the active id", async () => {
    const fetchMock = global.fetch as jest.Mock;
    fetchMock.mockImplementation(async (url: string, init?: RequestInit) => {
      if (url === `${API_BASE}/auth-profile/prefill`) {
        return okJson([]);
      }
      if (init?.method === "DELETE") {
        return okJson(null, 204);
      }
      return okJson([{ profileId: "p-1", name: "Prod", type: "BEARER" }]);
    });

    const { props } = renderPanel({
      profiles: [{ profileId: "p-1", name: "Prod", type: "BEARER" }],
      activeProfileId: "p-1",
    });

    await act(async () => {
      fireEvent.click(await screen.findByTestId("auth-profile-delete-p-1"));
      await new Promise((r) => setTimeout(r, 0));
    });

    await waitFor(() => {
      const deleteCall = fetchMock.mock.calls.find(
        ([url, init]: [string, RequestInit]) =>
          url === `${API_BASE}/auth-profile/p-1` && init?.method === "DELETE",
      );
      expect(deleteCall).toBeDefined();
    });
    await waitFor(() => {
      expect(props.onActiveProfileChange).toHaveBeenCalledWith(null);
    });
  });
});

describe("AuthProfiles prefill selection (AC3)", () => {
  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
    global.fetch = jest.fn().mockResolvedValue(okJson([])) as jest.Mock;
  });

  afterEach(() => {
    jest.restoreAllMocks();
  });

  it("lists prefill summaries (no secrets) and activates a picked profile via {name,type}", async () => {
    const fetchMock = global.fetch as jest.Mock;
    fetchMock.mockImplementation(async (url: string) => {
      if (url === `${API_BASE}/auth-profile/prefill`) {
        return okJson([
          { name: "spring-bearer", type: "BEARER" },
          { name: "spring-oauth", type: "OAUTH2", grantMode: "CLIENT_CREDENTIALS", tokenUrl: "https://idp/token" },
        ]);
      }
      if (url === `${API_BASE}/auth-profile`) {
        return okJson({ profileId: "p-prefill-1" });
      }
      return okJson([]);
    });

    const { props } = renderPanel();
    const pick = await screen.findByTestId("auth-profile-prefill-spring-bearer");
    expect(pick).toHaveTextContent("spring-bearer");
    // Summaries never carry secret values.
    expect(document.body.textContent).not.toContain("Bearer secret");
    expect(document.body.textContent).not.toContain("clientSecret");

    await act(async () => {
      fireEvent.click(pick);
      await new Promise((r) => setTimeout(r, 0));
    });

    await waitFor(() => {
      expect(props.onActiveProfileChange).toHaveBeenCalledWith("p-prefill-1");
    });
    const postCall = fetchMock.mock.calls.find(
      ([url, init]: [string, RequestInit]) =>
        url === `${API_BASE}/auth-profile` && init?.method === "POST",
    );
    expect(JSON.parse(String(postCall[1].body))).toEqual({
      name: "spring-bearer",
      type: "BEARER",
    });
  });
});

describe("AuthProfiles authorization-code flow (AC8 / D9B)", () => {
  const originalLocation = window.location;

  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
    global.fetch = jest.fn().mockResolvedValue(okJson([])) as jest.Mock;
    Object.defineProperty(window, "location", {
      configurable: true,
      writable: true,
      value: { ...originalLocation, href: "http://localhost/" },
    });
  });

  afterEach(() => {
    jest.restoreAllMocks();
    Object.defineProperty(window, "location", {
      configurable: true,
      writable: true,
      value: originalLocation,
    });
  });

  it("creates the PENDING profile, saves the flow record and redirects with the server-issued state", async () => {
    const fetchMock = global.fetch as jest.Mock;
    fetchMock.mockImplementation(async (url: string) => {
      if (url === `${API_BASE}/auth-profile/prefill`) {
        return okJson([]);
      }
      if (url === `${API_BASE}/auth-profile`) {
        return okJson({
          profileId: "p-pending-1",
          state: "server-state-42",
          authorizationUrl: "https://idp/authorize",
        });
      }
      return okJson([]);
    });

    renderPanel();

    await screen.findByTestId("auth-profile-save");
    await act(async () => {
      fireEvent.change(screen.getByTestId("auth-profile-type"), {
        target: { value: "OAUTH2" },
      });
    });
    await act(async () => {
      fireEvent.change(screen.getByTestId("auth-profile-grant-mode"), {
        target: { value: "AUTHORIZATION_CODE" },
      });
    });
    fireEvent.change(screen.getByTestId("auth-profile-name"), {
      target: { value: "Browser" },
    });
    fireEvent.change(screen.getByTestId("auth-profile-token-url"), {
      target: { value: "https://idp/token" },
    });
    fireEvent.change(screen.getByTestId("auth-profile-client-id"), {
      target: { value: "client-1" },
    });
    fireEvent.change(screen.getByTestId("auth-profile-scopes"), {
      target: { value: "read write" },
    });
    fireEvent.change(screen.getByTestId("auth-profile-authorization-url"), {
      target: { value: "https://idp/authorize" },
    });
    fireEvent.change(screen.getByTestId("auth-profile-redirect-uri"), {
      target: { value: "https://inspector/oauth/callback" },
    });

    const authorize = screen.getByTestId("auth-profile-authorize");
    expect(authorize).toBeEnabled();
    await act(async () => {
      fireEvent.click(authorize);
      await new Promise((r) => setTimeout(r, 0));
    });

    // Pending POST carries the PKCE challenge; the record keeps verifier+state.
    const pendingPost = fetchMock.mock.calls.find(
      ([url, init]: [string, RequestInit]) =>
        url === `${API_BASE}/auth-profile` && init?.method === "POST",
    );
    expect(pendingPost).toBeDefined();
    expect(JSON.parse(String(pendingPost[1].body))).toMatchObject({
      name: "Browser",
      type: "OAUTH2",
      grantMode: "AUTHORIZATION_CODE",
      codeChallenge: "test-challenge-0123456789",
      codeChallengeMethod: "S256",
    });

    const pending = getPendingAuthCodeFlow();
    expect(pending).toEqual({
      profileId: "p-pending-1",
      state: "server-state-42",
      codeVerifier: "test-verifier-0123456789",
      redirectUri: "https://inspector/oauth/callback",
    });

    // Redirect URL carries client_id / redirect_uri / scope / challenge / state.
    const redirected = (window.location as { href: string }).href;
    const parsed = new URL(redirected);
    expect(parsed.origin + parsed.pathname).toBe("https://idp/authorize");
    expect(parsed.searchParams.get("client_id")).toBe("client-1");
    expect(parsed.searchParams.get("redirect_uri")).toBe(
      "https://inspector/oauth/callback",
    );
    expect(parsed.searchParams.get("scope")).toBe("read write");
    expect(parsed.searchParams.get("code_challenge")).toBe(
      "test-challenge-0123456789",
    );
    expect(parsed.searchParams.get("code_challenge_method")).toBe("S256");
    expect(parsed.searchParams.get("state")).toBe("server-state-42");
  });
});