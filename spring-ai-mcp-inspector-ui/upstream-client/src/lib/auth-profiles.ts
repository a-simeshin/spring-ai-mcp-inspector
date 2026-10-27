/**
 * [spring-ai-mcp-inspector PATCH] Named auth profiles (issue #54, plan v15 D1/D2/D7/D9B/D10).
 *
 * Local, non-upstream module: profile types mirroring the backend wire model
 * (`io.inspector.mcp.core.auth.*`), the `/mcp-inspector/api/auth-profile`
 * client, editor validation (required fields + unique name), PKCE helpers for
 * the browser-driven authorization-code two-phase flow (D9B), and the legacy
 * localStorage secret migration (D10).
 *
 * Secrets NEVER touch localStorage: they live in React state / sessionStorage
 * (transient OAuth flow state only) and travel backend-ward via the
 * `/auth-profile` handoff. The server owns tokens; the UI only ever holds
 * `profileId`.
 */
import { InspectorConfig } from "./configurationTypes";
import { getMCPProxyAddress, getMCPProxyAuthToken } from "@/utils/configUtils";
import { CustomHeaders } from "./types/customHeaders";

// ---------------------------------------------------------------------------
// Wire model (mirrors io.inspector.mcp.core.auth.*)
// ---------------------------------------------------------------------------

export type AuthProfileType = "OAUTH2" | "BEARER" | "API_KEY" | "CUSTOM_HEADERS";
export type OAuth2GrantMode = "CLIENT_CREDENTIALS" | "AUTHORIZATION_CODE";
export type ApiKeyPlacement = "HEADER" | "QUERY";

export const AUTH_PROFILE_TYPES: AuthProfileType[] = [
  "OAUTH2",
  "BEARER",
  "API_KEY",
  "CUSTOM_HEADERS",
];

/** Non-secret projection returned by GET /auth-profile and GET /auth-profile/prefill. */
export interface AuthProfileSummary {
  /** Server-issued opaque id; absent (null) on prefill summaries. */
  profileId?: string | null;
  name: string;
  type: AuthProfileType;
  grantMode?: OAuth2GrantMode;
  tokenUrl?: string;
  clientId?: string;
  scopes?: string;
  authorizationUrl?: string;
  redirectUri?: string;
  keyName?: string;
  placement?: ApiKeyPlacement;
  headerNames?: string[];
}

/** Registration response of POST /auth-profile (pending auth-code adds state + authorizationUrl). */
export interface AuthProfileRegistrationResponse {
  profileId: string;
  state?: string;
  authorizationUrl?: string;
}

/** Editor draft — every profile kind, secrets included (React state only). */
export type ProfileDraft =
  | {
      name: string;
      type: "OAUTH2";
      grantMode: OAuth2GrantMode;
      tokenUrl: string;
      clientId: string;
      clientSecret: string;
      scopes: string;
      authorizationUrl: string;
      redirectUri: string;
    }
  | { name: string; type: "BEARER"; token: string }
  | {
      name: string;
      type: "API_KEY";
      keyName: string;
      keyValue: string;
      placement: ApiKeyPlacement;
    }
  | { name: string; type: "CUSTOM_HEADERS"; headers: CustomHeaders };

export const EMPTY_PROFILE_DRAFT: ProfileDraft = {
  name: "",
  type: "BEARER",
  token: "",
};

/** Transient browser-side state of the pending authorization-code flow (D9B). */
export interface PendingAuthCodeFlow {
  profileId: string;
  /** Server-issued one-time CSRF state; returned by the backend at create time. */
  state: string;
  /** PKCE verifier; kept browser-side, sent to the backend at /exchange. */
  codeVerifier: string;
  /** Registered redirect URI the IdP will bounce back to. */
  redirectUri: string;
}

// ---------------------------------------------------------------------------
// Legacy localStorage secret migration (D10)
// ---------------------------------------------------------------------------

/** The six legacy secret-bearing localStorage keys (verified App.tsx on develop/2.x). */
export const LEGACY_AUTH_KEYS = [
  "lastBearerToken",
  "lastHeaderName",
  "lastCustomHeaders",
  "lastOauthClientId",
  "lastOauthScope",
  "lastOauthClientSecret",
] as const;

export type LegacyAuthSeed = {
  bearerToken: string;
  headerName: string;
  customHeaders: CustomHeaders;
  oauthClientId: string;
  oauthScope: string;
  oauthClientSecret: string;
};

/**
 * D10: read-before-delete migration of the legacy auth localStorage keys.
 *
 * Runs synchronously BEFORE any state initialization reads them (idempotent —
 * absent keys are a no-op; after the first call nothing remains). Every key is
 * removed immediately after being read so no secret persists; secrets continue
 * to live in React state only.
 */
export function migrateLegacyAuthStorage(): LegacyAuthSeed {
  const seed: LegacyAuthSeed = {
    bearerToken: localStorage.getItem("lastBearerToken") || "",
    headerName: localStorage.getItem("lastHeaderName") || "",
    customHeaders: [],
    oauthClientId: localStorage.getItem("lastOauthClientId") || "",
    oauthScope: localStorage.getItem("lastOauthScope") || "",
    oauthClientSecret: localStorage.getItem("lastOauthClientSecret") || "",
  };

  const savedHeaders = localStorage.getItem("lastCustomHeaders");
  if (savedHeaders) {
    try {
      const parsed = JSON.parse(savedHeaders);
      if (Array.isArray(parsed)) {
        seed.customHeaders = parsed;
      }
    } catch {
      // Malformed legacy value — fall through to the bearer-token migration.
    }
  }
  if (seed.customHeaders.length === 0 && seed.bearerToken) {
    seed.customHeaders = [
      {
        name: seed.headerName || "Authorization",
        value:
          !seed.headerName ||
          seed.headerName.toLowerCase() === "authorization"
            ? `Bearer ${seed.bearerToken}`
            : seed.bearerToken,
        enabled: true,
      },
    ];
  }

  for (const key of LEGACY_AUTH_KEYS) {
    localStorage.removeItem(key);
  }

  return seed;
}

// ---------------------------------------------------------------------------
// API client (`/mcp-inspector/api/auth-profile`, D2)
// ---------------------------------------------------------------------------

/**
 * Resolves the inspector API base (`${spring.ai.mcp.inspector.path}/api`).
 * The bootstrap seeds `MCP_PROXY_FULL_ADDRESS` as `${path}-api`, so the API
 * namespace is derived by stripping the `-api` suffix; a plain origin (the
 * upstream standalone proxy) falls back to the default `/mcp-inspector/api`.
 */
export function getAuthProfileApiBase(config: InspectorConfig): string {
  const proxyAddress = getMCPProxyAddress(config);
  if (proxyAddress.endsWith("-api")) {
    return `${proxyAddress.slice(0, -4)}/api`;
  }
  return `${window.location.origin}/mcp-inspector/api`;
}

export class AuthProfileApiError extends Error {
  readonly status: number;
  /** Structured error DTO when the backend returned one (D3 shape). */
  readonly dto?: { status: number; code: string; reason: string; guidance: string; url?: string };

  constructor(
    status: number,
    message: string,
    dto?: { status: number; code: string; reason: string; guidance: string; url?: string },
  ) {
    super(message);
    this.name = "AuthProfileApiError";
    this.status = status;
    this.dto = dto;
  }
}

function authProfileHeaders(config: InspectorConfig): HeadersInit {
  // The inspector guard on `${path}/api/*` expects the same boot token the
  // bootstrap seeds into `MCP_PROXY_AUTH_TOKEN` (InspectorAuthTokenProvider).
  const { token } = getMCPProxyAuthToken(config);
  const headers: HeadersInit = { "Content-Type": "application/json" };
  if (token) {
    headers["X-MCP-Inspector-Auth"] = token;
  }
  return headers;
}

async function parseAuthProfileError(
  response: Response,
): Promise<never> {
  let message = `Auth profile request failed (${response.status})`;
  let dto: AuthProfileApiError["dto"];
  try {
    const body: unknown = await response.json();
    if (body && typeof body === "object" && !Array.isArray(body)) {
      const rec = body as Record<string, unknown>;
      if (typeof rec.error === "string") {
        message = rec.error;
      } else if (
        typeof rec.status === "number" &&
        typeof rec.code === "string"
      ) {
        dto = {
          status: rec.status,
          code: rec.code,
          reason: typeof rec.reason === "string" ? rec.reason : "",
          guidance: typeof rec.guidance === "string" ? rec.guidance : "",
          url: typeof rec.url === "string" ? rec.url : undefined,
        };
        message = dto.reason || dto.code;
      }
    }
  } catch {
    // Non-JSON error body — keep the generic message.
  }
  throw new AuthProfileApiError(response.status, message, dto);
}

/** GET /auth-profile — owner-scoped named profiles, secrets omitted. */
export async function listAuthProfiles(
  config: InspectorConfig,
): Promise<AuthProfileSummary[]> {
  const response = await fetch(`${getAuthProfileApiBase(config)}/auth-profile`, {
    method: "GET",
    headers: authProfileHeaders(config),
  });
  if (!response.ok) {
    return parseAuthProfileError(response);
  }
  return (await response.json()) as AuthProfileSummary[];
}

/**
 * POST /auth-profile — inline profile registration. Returns the server-issued
 * profileId (plus `state`/`authorizationUrl` for an auth-code PENDING create).
 */
export async function createAuthProfile(
  config: InspectorConfig,
  draft: ProfileDraft,
): Promise<AuthProfileRegistrationResponse> {
  const response = await fetch(`${getAuthProfileApiBase(config)}/auth-profile`, {
    method: "POST",
    headers: authProfileHeaders(config),
    body: JSON.stringify({ profile: toWireProfile(draft) }),
  });
  if (!response.ok) {
    return parseAuthProfileError(response);
  }
  return (await response.json()) as AuthProfileRegistrationResponse;
}

/**
 * POST /auth-profile — prefill reference `{name, type}` (D7): the backend
 * resolves the profile from Spring config and returns the profileId.
 */
export async function createAuthProfileFromPrefill(
  config: InspectorConfig,
  name: string,
  type: AuthProfileType,
): Promise<AuthProfileRegistrationResponse> {
  const response = await fetch(`${getAuthProfileApiBase(config)}/auth-profile`, {
    method: "POST",
    headers: authProfileHeaders(config),
    body: JSON.stringify({ name, type }),
  });
  if (!response.ok) {
    return parseAuthProfileError(response);
  }
  return (await response.json()) as AuthProfileRegistrationResponse;
}

/**
 * POST /auth-profile — auth-code PENDING profile creation (D9B phase 1).
 * The browser supplies the PKCE challenge; the backend issues the one-time
 * `state` and returns the authorization endpoint to open.
 */
export async function createPendingAuthCodeProfile(
  config: InspectorConfig,
  draft: ProfileDraft & { type: "OAUTH2"; grantMode: "AUTHORIZATION_CODE" },
  codeChallenge: string,
): Promise<AuthProfileRegistrationResponse> {
  const response = await fetch(`${getAuthProfileApiBase(config)}/auth-profile`, {
    method: "POST",
    headers: authProfileHeaders(config),
    body: JSON.stringify({
      name: draft.name,
      type: "OAUTH2",
      grantMode: "AUTHORIZATION_CODE",
      tokenUrl: draft.tokenUrl,
      clientId: draft.clientId,
      scopes: draft.scopes,
      authorizationUrl: draft.authorizationUrl,
      redirectUri: draft.redirectUri,
      codeChallenge,
      codeChallengeMethod: "S256",
    }),
  });
  if (!response.ok) {
    return parseAuthProfileError(response);
  }
  return (await response.json()) as AuthProfileRegistrationResponse;
}

/** PUT /auth-profile/{profileId} — rename / field update (path variable). */
export async function updateAuthProfile(
  config: InspectorConfig,
  profileId: string,
  draft: ProfileDraft,
): Promise<void> {
  const response = await fetch(
    `${getAuthProfileApiBase(config)}/auth-profile/${encodeURIComponent(profileId)}`,
    {
      method: "PUT",
      headers: authProfileHeaders(config),
      body: JSON.stringify({ profile: toWireProfile(draft) }),
    },
  );
  if (!response.ok) {
    return parseAuthProfileError(response);
  }
}

/** DELETE /auth-profile/{profileId} — path variable, owner-scoped. */
export async function deleteAuthProfile(
  config: InspectorConfig,
  profileId: string,
): Promise<void> {
  const response = await fetch(
    `${getAuthProfileApiBase(config)}/auth-profile/${encodeURIComponent(profileId)}`,
    {
      method: "DELETE",
      headers: authProfileHeaders(config),
    },
  );
  if (!response.ok) {
    return parseAuthProfileError(response);
  }
}

/** GET /auth-profile/prefill — Spring-config summaries, no secrets (D7). */
export async function listPrefillAuthProfiles(
  config: InspectorConfig,
): Promise<AuthProfileSummary[]> {
  const response = await fetch(
    `${getAuthProfileApiBase(config)}/auth-profile/prefill`,
    {
      method: "GET",
      headers: authProfileHeaders(config),
    },
  );
  if (!response.ok) {
    return parseAuthProfileError(response);
  }
  return (await response.json()) as AuthProfileSummary[];
}

/** POST /auth-profile/{profileId}/exchange — auth-code phase 3 (D9B). */
export async function exchangeAuthCode(
  config: InspectorConfig,
  profileId: string,
  code: string,
  codeVerifier: string,
  state: string,
): Promise<AuthProfileRegistrationResponse> {
  const response = await fetch(
    `${getAuthProfileApiBase(config)}/auth-profile/${encodeURIComponent(profileId)}/exchange`,
    {
      method: "POST",
      headers: authProfileHeaders(config),
      body: JSON.stringify({ code, codeVerifier, state }),
    },
  );
  if (!response.ok) {
    return parseAuthProfileError(response);
  }
  return (await response.json()) as AuthProfileRegistrationResponse;
}

/** Serialises an editor draft into the backend inline-profile wire shape. */
function toWireProfile(draft: ProfileDraft): Record<string, unknown> {
  switch (draft.type) {
    case "OAUTH2":
      return {
        name: draft.name,
        type: "OAUTH2",
        grantMode: draft.grantMode,
        tokenUrl: draft.tokenUrl,
        clientId: draft.clientId,
        clientSecret: draft.clientSecret || null,
        scopes: draft.scopes,
        authorizationUrl: draft.authorizationUrl || null,
        redirectUri: draft.redirectUri || null,
      };
    case "BEARER":
      return { name: draft.name, type: "BEARER", token: draft.token };
    case "API_KEY":
      return {
        name: draft.name,
        type: "API_KEY",
        keyName: draft.keyName,
        keyValue: draft.keyValue,
        placement: draft.placement,
      };
    case "CUSTOM_HEADERS":
      return {
        name: draft.name,
        type: "CUSTOM_HEADERS",
        headers: draft.headers
          .filter((h) => h.enabled && h.name.trim() && h.value.trim())
          .map((h) => ({ name: h.name.trim(), value: h.value.trim() })),
      };
  }
}

// ---------------------------------------------------------------------------
// Editor validation (AC1: required fields + unique name)
// ---------------------------------------------------------------------------

/**
 * Returns the first validation error message, or null when the draft is valid.
 *
 * @param existingNames names of OTHER profiles in the owner's store (the
 * caller excludes the profile being edited, so a rename may keep its own name)
 */
export function validateProfileDraft(
  draft: ProfileDraft,
  existingNames: string[],
): string | null {
  const name = draft.name.trim();
  if (!name) {
    return "Profile name is required";
  }
  if (
    existingNames.some((n) => n.trim().toLowerCase() === name.toLowerCase())
  ) {
    return "Profile name already exists";
  }

  switch (draft.type) {
    case "OAUTH2":
      if (!draft.tokenUrl.trim()) {
        return "Token URL is required";
      }
      if (!draft.clientId.trim()) {
        return "Client ID is required";
      }
      if (draft.grantMode === "CLIENT_CREDENTIALS" && !draft.clientSecret.trim()) {
        return "Client secret is required for client-credentials";
      }
      if (draft.grantMode === "AUTHORIZATION_CODE") {
        if (!draft.authorizationUrl.trim()) {
          return "Authorization URL is required";
        }
        if (!draft.redirectUri.trim()) {
          return "Redirect URI is required";
        }
      }
      return null;
    case "BEARER":
      return draft.token.trim() ? null : "Bearer token is required";
    case "API_KEY":
      if (!draft.keyName.trim()) {
        return "API key name is required";
      }
      return draft.keyValue.trim() ? null : "API key value is required";
    case "CUSTOM_HEADERS": {
      const enabled = draft.headers.filter((h) => h.enabled);
      if (enabled.length === 0) {
        return "At least one enabled header is required";
      }
      const empty = enabled.find((h) => !h.name.trim() || !h.value.trim());
      return empty ? "Header name and value are required" : null;
    }
  }
}

/** Short uniqueness check used by the selector when renaming in place. */
export function isProfileNameTaken(
  name: string,
  summaries: AuthProfileSummary[],
  selfProfileId?: string | null,
): boolean {
  const normalized = name.trim().toLowerCase();
  return summaries.some(
    (s) =>
      s.profileId !== selfProfileId &&
      s.name.trim().toLowerCase() === normalized,
  );
}

// ---------------------------------------------------------------------------
// Authorization-code two-phase flow (D9B)
// ---------------------------------------------------------------------------

/** sessionStorage key for the transient pending auth-code record. */
export const AUTH_CODE_PENDING_SESSION_KEY = "mcp_auth_code_pending";

export function savePendingAuthCodeFlow(flow: PendingAuthCodeFlow): void {
  sessionStorage.setItem(AUTH_CODE_PENDING_SESSION_KEY, JSON.stringify(flow));
}

export function getPendingAuthCodeFlow(): PendingAuthCodeFlow | null {
  const raw = sessionStorage.getItem(AUTH_CODE_PENDING_SESSION_KEY);
  if (!raw) {
    return null;
  }
  try {
    const parsed = JSON.parse(raw) as PendingAuthCodeFlow;
    if (
      typeof parsed.profileId === "string" &&
      typeof parsed.state === "string" &&
      typeof parsed.codeVerifier === "string"
    ) {
      return parsed;
    }
  } catch {
    // Corrupt transient state — treat as absent.
  }
  return null;
}

export function clearPendingAuthCodeFlow(): void {
  sessionStorage.removeItem(AUTH_CODE_PENDING_SESSION_KEY);
}

/**
 * Generates a PKCE S256 verifier/challenge pair (browser-side, D9B) using the
 * Web Crypto API — no ESM-only dependency, so jest (ts-jest, CJS transform)
 * can load the module.
 */
export async function generateCodeChallengePair(): Promise<{
  codeVerifier: string;
  codeChallenge: string;
}> {
  const verifierBytes = new Uint8Array(64);
  crypto.getRandomValues(verifierBytes);
  const codeVerifier = base64UrlEncode(verifierBytes);
  const digest = await crypto.subtle.digest(
    "SHA-256",
    new TextEncoder().encode(codeVerifier),
  );
  const codeChallenge = base64UrlEncode(new Uint8Array(digest));
  return { codeVerifier, codeChallenge };
}

/** RFC 4648 base64url (no padding) — used for the PKCE verifier/challenge. */
function base64UrlEncode(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) {
    binary += String.fromCharCode(byte);
  }
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

/**
 * Builds the IdP authorization URL for the pending auth-code flow (D9B phase 2).
 * The CSRF `state` is the SERVER-issued value from the pending create response.
 */
export function buildAuthorizationUrl(
  authorizationUrl: string,
  params: {
    clientId: string;
    redirectUri: string;
    scope?: string;
    codeChallenge: string;
    state: string;
  },
): string {
  const url = new URL(authorizationUrl);
  url.searchParams.set("response_type", "code");
  url.searchParams.set("client_id", params.clientId);
  url.searchParams.set("redirect_uri", params.redirectUri);
  if (params.scope && params.scope.trim()) {
    url.searchParams.set("scope", params.scope.trim());
  }
  url.searchParams.set("code_challenge", params.codeChallenge);
  url.searchParams.set("code_challenge_method", "S256");
  url.searchParams.set("state", params.state);
  return url.toString();
}

/** Builds a profile draft from a prefill summary (secrets stay server-side). */
export function draftFromPrefill(summary: AuthProfileSummary): ProfileDraft {
  switch (summary.type) {
    case "OAUTH2":
      return {
        name: summary.name,
        type: "OAUTH2",
        grantMode: summary.grantMode ?? "CLIENT_CREDENTIALS",
        tokenUrl: summary.tokenUrl ?? "",
        clientId: summary.clientId ?? "",
        clientSecret: "",
        scopes: summary.scopes ?? "",
        authorizationUrl: summary.authorizationUrl ?? "",
        redirectUri: summary.redirectUri ?? "",
      };
    case "BEARER":
      return { name: summary.name, type: "BEARER", token: "" };
    case "API_KEY":
      return {
        name: summary.name,
        type: "API_KEY",
        keyName: summary.keyName ?? "",
        keyValue: "",
        placement: summary.placement ?? "HEADER",
      };
    case "CUSTOM_HEADERS":
      return {
        name: summary.name,
        type: "CUSTOM_HEADERS",
        headers: (summary.headerNames ?? []).map((name) => ({
          name,
          value: "",
          enabled: true,
        })),
      };
  }
}
