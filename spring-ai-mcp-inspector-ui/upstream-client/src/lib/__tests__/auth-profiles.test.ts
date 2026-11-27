/**
 * [spring-ai-mcp-inspector PATCH] Unit tests for the named auth-profiles
 * module (issue #54): editor validation + unique name (AC1), API client wire
 * shapes (inline / prefill reference / auth-code PENDING / exchange), PKCE
 * URL building (D9B), secret redaction boundaries and the legacy localStorage
 * migration (D10 / AC11).
 */
import {
  AuthProfileSummary,
  LEGACY_AUTH_KEYS,
  buildAuthorizationUrl,
  createAuthProfile,
  createAuthProfileFromPrefill,
  createPendingAuthCodeProfile,
  draftFromPrefill,
  exchangeAuthCode,
  generateCodeChallengePair,
  getAuthProfileApiBase,
  migrateLegacyAuthStorage,
  validateProfileDraft,
} from "../auth-profiles";
import { DEFAULT_INSPECTOR_CONFIG } from "../constants";
import { webcrypto } from "node:crypto";

const BASE64URL_RE = /^[A-Za-z0-9_-]+$/;

const CONFIG = {
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

describe("validateProfileDraft", () => {
  it("requires a name", () => {
    const draft = { name: "  ", type: "BEARER" as const, token: "tok" };
    expect(validateProfileDraft(draft, [])).toBe("Profile name is required");
  });

  it("rejects a duplicate name (case-insensitive)", () => {
    const draft = { name: "Prod", type: "BEARER" as const, token: "tok" };
    expect(validateProfileDraft(draft, ["prod"])).toBe(
      "Profile name already exists",
    );
  });

  it("accepts a name that is not taken", () => {
    const draft = { name: "Prod", type: "BEARER" as const, token: "tok" };
    expect(validateProfileDraft(draft, ["Staging"])).toBeNull();
  });

  it("requires a bearer token", () => {
    const draft = { name: "Prod", type: "BEARER" as const, token: " " };
    expect(validateProfileDraft(draft, [])).toBe("Bearer token is required");
  });

  it("requires token URL and client ID for OAuth2", () => {
    const draft = {
      name: "O",
      type: "OAUTH2" as const,
      grantMode: "CLIENT_CREDENTIALS" as const,
      tokenUrl: "",
      clientId: "",
      clientSecret: "s",
      scopes: "",
      authorizationUrl: "",
      redirectUri: "",
    };
    expect(validateProfileDraft(draft, [])).toBe("Token URL is required");
  });

  it("requires the client secret for client-credentials", () => {
    const draft = {
      name: "O",
      type: "OAUTH2" as const,
      grantMode: "CLIENT_CREDENTIALS" as const,
      tokenUrl: "https://idp/token",
      clientId: "cid",
      clientSecret: "",
      scopes: "",
      authorizationUrl: "",
      redirectUri: "",
    };
    expect(validateProfileDraft(draft, [])).toBe(
      "Client secret is required for client-credentials",
    );
  });

  it("requires authorization URL + redirect URI for authorization-code", () => {
    const draft = {
      name: "O",
      type: "OAUTH2" as const,
      grantMode: "AUTHORIZATION_CODE" as const,
      tokenUrl: "https://idp/token",
      clientId: "cid",
      clientSecret: "",
      scopes: "",
      authorizationUrl: "",
      redirectUri: "",
    };
    expect(validateProfileDraft(draft, [])).toBe(
      "Authorization URL is required",
    );
  });

  it("requires key name and value for API-key profiles", () => {
    const draft = {
      name: "K",
      type: "API_KEY" as const,
      keyName: "X-Key",
      keyValue: "",
      placement: "HEADER" as const,
    };
    expect(validateProfileDraft(draft, [])).toBe("API key value is required");
  });

  it("requires at least one enabled header for custom headers", () => {
    const draft = {
      name: "H",
      type: "CUSTOM_HEADERS" as const,
      headers: [{ name: "X", value: "v", enabled: false }],
    };
    expect(validateProfileDraft(draft, [])).toBe(
      "At least one enabled header is required",
    );
  });
});

describe("getAuthProfileApiBase", () => {
  it("derives the API namespace from the proxy address (-api suffix)", () => {
    expect(getAuthProfileApiBase(CONFIG)).toBe(
      "http://localhost:8080/mcp-inspector/api",
    );
  });
});

describe("createAuthProfile (inline)", () => {
  afterEach(() => {
    jest.restoreAllMocks();
  });

  it("posts the inline profile and returns the server-issued profileId", async () => {
    const fetchMock = jest
      .spyOn(globalThis, "fetch")
      .mockResolvedValue(
        new Response(JSON.stringify({ profileId: "p-1" }), { status: 200 }),
      );

    const result = await createAuthProfile(CONFIG, {
      name: "Prod",
      type: "BEARER",
      token: "secret-token",
    });

    expect(result.profileId).toBe("p-1");
    const [url, init] = fetchMock.mock.calls[0];
    expect(String(url)).toBe("http://localhost:8080/mcp-inspector/api/auth-profile");
    const headers = new Headers(init?.headers as HeadersInit);
    expect(headers.get("X-MCP-Inspector-Auth")).toBe("inspector-token");
    const body = JSON.parse(String(init?.body));
    expect(body.profile).toEqual({
      name: "Prod",
      type: "BEARER",
      token: "secret-token",
    });
  });

  it("surfaces a structured 502 DTO as AuthProfileApiError", async () => {
    jest
      .spyOn(globalThis, "fetch")
      .mockResolvedValue(
        new Response(
          JSON.stringify({
            status: 502,
            code: "token_exchange_failed",
            reason: "Upstream token exchange failed",
            guidance: "Check the token URL and credentials.",
          }),
          { status: 502 },
        ),
      );

    const error = await createAuthProfile(CONFIG, {
      name: "Prod",
      type: "BEARER",
      token: "t",
    }).catch((e: unknown) => e);

    expect(error).toBeInstanceOf(Error);
    expect((error as Error & { status?: number }).status).toBe(502);
    expect(
      (error as { dto?: { code?: string } }).dto?.code,
    ).toBe("token_exchange_failed");
  });
});

describe("createAuthProfileFromPrefill", () => {
  it("posts only {name, type} — secrets stay server-side", async () => {
    const fetchMock = jest
      .spyOn(globalThis, "fetch")
      .mockResolvedValue(
        new Response(JSON.stringify({ profileId: "p-2" }), { status: 200 }),
      );

    await createAuthProfileFromPrefill(CONFIG, "spring-profile", "API_KEY");

    const [, init] = fetchMock.mock.calls[0];
    const body = JSON.parse(String(init?.body));
    expect(body).toEqual({ name: "spring-profile", type: "API_KEY" });
    expect(Object.keys(body)).not.toContain("profile");
    jest.restoreAllMocks();
  });
});

describe("createPendingAuthCodeProfile (D9B phase 1)", () => {
  it("posts the PENDING shape with the PKCE challenge and server-issued state comes back", async () => {
    const fetchMock = jest
      .spyOn(globalThis, "fetch")
      .mockResolvedValue(
        new Response(
          JSON.stringify({
            profileId: "p-3",
            state: "server-state-1",
            authorizationUrl: "https://idp/authorize",
          }),
          { status: 200 },
        ),
      );

    const result = await createPendingAuthCodeProfile(
      CONFIG,
      {
        name: "Browser",
        type: "OAUTH2",
        grantMode: "AUTHORIZATION_CODE",
        tokenUrl: "https://idp/token",
        clientId: "cid",
        clientSecret: "",
        scopes: "read write",
        authorizationUrl: "https://idp/authorize",
        redirectUri: "https://inspector/oauth/callback",
      },
      "challenge-abc",
    );

    expect(result.state).toBe("server-state-1");
    const [, init] = fetchMock.mock.calls[0];
    const body = JSON.parse(String(init?.body));
    expect(body).toMatchObject({
      name: "Browser",
      type: "OAUTH2",
      grantMode: "AUTHORIZATION_CODE",
      tokenUrl: "https://idp/token",
      clientId: "cid",
      scopes: "read write",
      authorizationUrl: "https://idp/authorize",
      redirectUri: "https://inspector/oauth/callback",
      codeChallenge: "challenge-abc",
      codeChallengeMethod: "S256",
    });
    // The client secret never leaves the browser on the PENDING path.
    expect(body).not.toHaveProperty("clientSecret");
    jest.restoreAllMocks();
  });
});

describe("exchangeAuthCode (D9B phase 3)", () => {
  it("posts {code, codeVerifier, state} to the profile exchange endpoint", async () => {
    const fetchMock = jest
      .spyOn(globalThis, "fetch")
      .mockResolvedValue(
        new Response(JSON.stringify({ profileId: "p-3" }), { status: 200 }),
      );

    await exchangeAuthCode(CONFIG, "p-3", "auth-code-1", "verifier-abc", "server-state-1");

    const [url, init] = fetchMock.mock.calls[0];
    expect(String(url)).toBe(
      "http://localhost:8080/mcp-inspector/api/auth-profile/p-3/exchange",
    );
    expect(JSON.parse(String(init?.body))).toEqual({
      code: "auth-code-1",
      codeVerifier: "verifier-abc",
      state: "server-state-1",
    });
    jest.restoreAllMocks();
  });
});

describe("buildAuthorizationUrl (D9B phase 2)", () => {
  it("appends PKCE + server-issued state query params", () => {
    const url = buildAuthorizationUrl("https://idp/authorize?tenant=x", {
      clientId: "cid",
      redirectUri: "https://inspector/oauth/callback",
      scope: "read write",
      codeChallenge: "challenge-abc",
      state: "server-state-1",
    });
    const parsed = new URL(url);
    expect(parsed.searchParams.get("response_type")).toBe("code");
    expect(parsed.searchParams.get("client_id")).toBe("cid");
    expect(parsed.searchParams.get("redirect_uri")).toBe(
      "https://inspector/oauth/callback",
    );
    expect(parsed.searchParams.get("scope")).toBe("read write");
    expect(parsed.searchParams.get("code_challenge")).toBe("challenge-abc");
    expect(parsed.searchParams.get("code_challenge_method")).toBe("S256");
    expect(parsed.searchParams.get("state")).toBe("server-state-1");
    // Existing query params survive.
    expect(parsed.searchParams.get("tenant")).toBe("x");
  });
});

describe("generateCodeChallengePair", () => {
  beforeAll(() => {
    // jest-fixed-jsdom ships `crypto.getRandomValues` but not `crypto.subtle`;
    // the production code uses the Web Crypto API (node/browser both provide
    // it), so supply the node implementation for the digest assertions.
    if (typeof globalThis.crypto?.subtle === "undefined") {
      Object.defineProperty(globalThis, "crypto", { value: webcrypto });
    }
  });

  it("returns a PKCE S256 verifier/challenge pair (Web Crypto)", async () => {
    const pair = await generateCodeChallengePair();
    // Verifier: 64 random bytes → base64url, 86 chars (RFC 7636: 43–128).
    expect(pair.codeVerifier).toMatch(BASE64URL_RE);
    expect(pair.codeVerifier.length).toBeGreaterThanOrEqual(43);
    expect(pair.codeVerifier.length).toBeLessThanOrEqual(128);
    // Challenge: base64url(SHA-256(verifier)) — 43 chars, no padding.
    expect(pair.codeChallenge).toMatch(BASE64URL_RE);
    expect(pair.codeChallenge.length).toBe(43);
    const digest = await crypto.subtle.digest(
      "SHA-256",
      new TextEncoder().encode(pair.codeVerifier),
    );
    const expected = btoa(String.fromCharCode(...new Uint8Array(digest)))
      .replace(/\+/g, "-")
      .replace(/\//g, "_")
      .replace(/=+$/, "");
    expect(pair.codeChallenge).toBe(expected);
    // Two calls yield distinct verifiers.
    const second = await generateCodeChallengePair();
    expect(second.codeVerifier).not.toBe(pair.codeVerifier);
  });
});

describe("draftFromPrefill", () => {
  const summary: AuthProfileSummary = {
    name: "spring-api",
    type: "API_KEY",
    keyName: "X-API-Key",
    placement: "QUERY",
  };

  it("materialises a draft with non-secret fields only (secrets empty)", () => {
    const draft = draftFromPrefill(summary);
    expect(draft).toMatchObject({
      name: "spring-api",
      type: "API_KEY",
      keyName: "X-API-Key",
      placement: "QUERY",
      keyValue: "",
    });
    expect(JSON.stringify(draft)).not.toContain("secret");
  });
});

describe("migrateLegacyAuthStorage (D10)", () => {
  const SECRET_BEARER = "ghp_secretBearer123";
  const SECRET_CLIENT = "oauth-secret-client";

  beforeEach(() => {
    localStorage.clear();
  });

  it("seeds and removes each of the six legacy keys, exactly once", () => {
    localStorage.setItem("lastBearerToken", SECRET_BEARER);
    localStorage.setItem("lastHeaderName", "X-Custom-Auth");
    localStorage.setItem("lastCustomHeaders", JSON.stringify([{ name: "X", value: "v", enabled: true }]));
    localStorage.setItem("lastOauthClientId", "client-1");
    localStorage.setItem("lastOauthScope", "read");
    localStorage.setItem("lastOauthClientSecret", SECRET_CLIENT);

    const seed = migrateLegacyAuthStorage();

    expect(seed.bearerToken).toBe(SECRET_BEARER);
    expect(seed.headerName).toBe("X-Custom-Auth");
    expect(seed.customHeaders).toEqual([{ name: "X", value: "v", enabled: true }]);
    expect(seed.oauthClientId).toBe("client-1");
    expect(seed.oauthScope).toBe("read");
    expect(seed.oauthClientSecret).toBe(SECRET_CLIENT);

    for (const key of LEGACY_AUTH_KEYS) {
      expect(localStorage.getItem(key)).toBeNull();
    }

    // Idempotent: a second call is a no-op with an empty seed.
    const again = migrateLegacyAuthStorage();
    expect(again.bearerToken).toBe("");
    expect(again.oauthClientSecret).toBe("");
  });

  it("falls back to the bearer-token header migration when lastCustomHeaders is absent", () => {
    localStorage.setItem("lastBearerToken", SECRET_BEARER);
    localStorage.setItem("lastHeaderName", "Authorization");

    const seed = migrateLegacyAuthStorage();
    expect(seed.customHeaders).toEqual([
      { name: "Authorization", value: `Bearer ${SECRET_BEARER}`, enabled: true },
    ]);
  });

  it("never leaves a secret-bearing value in localStorage after migration", () => {
    localStorage.setItem("lastBearerToken", SECRET_BEARER);
    localStorage.setItem("lastOauthClientSecret", SECRET_CLIENT);

    migrateLegacyAuthStorage();

    const sweep = Object.entries(localStorage).map(([, v]) => v).join("\n");
    expect(sweep).not.toContain(SECRET_BEARER);
    expect(sweep).not.toContain(SECRET_CLIENT);
  });
});
