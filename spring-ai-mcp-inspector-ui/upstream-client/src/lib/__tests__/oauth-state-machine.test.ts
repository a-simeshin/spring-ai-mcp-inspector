// [spring-ai-mcp-inspector PATCH] Regression guard for
// https://github.com/a-simeshin/spring-ai-mcp-inspector/issues/26.
//
// The inspector advertises its MCP endpoint as a same-origin relative path
// ("/sse" or "/mcp"), so the Auth Debugger receives a relative serverUrl. The
// SDK's selectResourceURL feeds it straight into `new URL(...)`, which throws
// "Failed to construct 'URL': Invalid URL" on a relative string — outside the
// try/catch — so executeStep died before any request left the browser and the
// Quick OAuth Flow banner read "Failed to start OAuth flow: Invalid URL".
//
// selectResourceURL is stubbed with the SDK's own resourceUrlFromServerUrl (the
// function that actually threw) rather than a bare jest.fn(), so the assertion
// still exercises the real failure.
import { OAuthStateMachine } from "../oauth-state-machine";
import { EMPTY_DEBUGGER_STATE, AuthDebuggerState } from "../auth-types";

const authorizationServerMetadata = {
  issuer: "https://auth.example.com",
  authorization_endpoint: "https://auth.example.com/authorize",
  token_endpoint: "https://auth.example.com/token",
  response_types_supported: ["code"],
  grant_types_supported: ["authorization_code"],
};

jest.mock("@modelcontextprotocol/sdk/client/auth.js", () => {
  const { resourceUrlFromServerUrl: realResourceUrlFromServerUrl } =
    jest.requireActual("@modelcontextprotocol/sdk/shared/auth-utils.js");
  return {
    discoverAuthorizationServerMetadata: jest.fn(),
    discoverOAuthProtectedResourceMetadata: jest.fn(),
    selectResourceURL: jest.fn(async (serverUrl: string) =>
      realResourceUrlFromServerUrl(serverUrl),
    ),
    registerClient: jest.fn(),
    startAuthorization: jest.fn(),
    exchangeAuthorization: jest.fn(),
  };
});

import {
  discoverAuthorizationServerMetadata,
  discoverOAuthProtectedResourceMetadata,
  selectResourceURL,
} from "@modelcontextprotocol/sdk/client/auth.js";

const mockDiscoverAuthServer =
  discoverAuthorizationServerMetadata as jest.MockedFunction<
    typeof discoverAuthorizationServerMetadata
  >;
const mockDiscoverResource =
  discoverOAuthProtectedResourceMetadata as jest.MockedFunction<
    typeof discoverOAuthProtectedResourceMetadata
  >;
const mockSelectResourceURL = selectResourceURL as jest.MockedFunction<
  typeof selectResourceURL
>;

describe("OAuthStateMachine with a relative server URL", () => {
  beforeEach(() => {
    sessionStorage.clear();
    mockDiscoverAuthServer.mockResolvedValue(authorizationServerMetadata);
    // The demo stub serves no protected-resource metadata, like most servers.
    mockDiscoverResource.mockRejectedValue(new Error("404"));
    mockSelectResourceURL.mockClear();
  });

  it("discovers metadata instead of throwing Invalid URL", async () => {
    const updates: Partial<AuthDebuggerState>[] = [];
    const machine = new OAuthStateMachine("/sse", (u) => updates.push(u));

    await expect(
      machine.executeStep(EMPTY_DEBUGGER_STATE),
    ).resolves.toBeUndefined();

    expect(updates).toHaveLength(1);
    expect(updates[0].oauthStep).toBe("client_registration");
  });

  it("hands the SDK an absolute server URL, not the raw relative path", async () => {
    const machine = new OAuthStateMachine("/sse", () => {});
    await machine.executeStep(EMPTY_DEBUGGER_STATE);

    expect(mockSelectResourceURL).toHaveBeenCalledWith(
      `${window.location.origin}/sse`,
      expect.anything(),
      undefined,
    );
    expect(mockDiscoverResource).toHaveBeenCalledWith(
      `${window.location.origin}/sse`,
      {},
      undefined,
    );
  });
});
