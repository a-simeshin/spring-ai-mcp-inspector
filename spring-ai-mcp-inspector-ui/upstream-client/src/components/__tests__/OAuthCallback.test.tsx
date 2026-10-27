import { render, screen } from "@testing-library/react";
import OAuthCallback from "../OAuthCallback";
import {
  exchangeAuthCode,
  getPendingAuthCodeFlow,
  clearPendingAuthCodeFlow,
} from "@/lib/auth-profiles";

// [spring-ai-mcp-inspector PATCH] D9B callback-to-wire regression: a successful
// auth-code exchange must surface the returned profileId through
// onProfileAuthorized, so App can hand it to the very first connect. The
// useConnection test separately proves that override reaches the proxy URL.
jest.mock("@/lib/auth-profiles", () => ({
  exchangeAuthCode: jest.fn(),
  getPendingAuthCodeFlow: jest.fn(),
  clearPendingAuthCodeFlow: jest.fn(),
}));

jest.mock("@/lib/auth", () => ({
  InspectorOAuthClientProvider: jest.fn(),
}));

// The SDK's client/auth.js requires ESM-only pkce-challenge under jest's CJS
// resolver — mock it so the profile-exchange branch can load.
jest.mock("@modelcontextprotocol/sdk/client/auth.js", () => ({
  auth: jest.fn(),
}));

jest.mock("@/utils/oauthUtils.ts", () => ({
  parseOAuthCallbackParams: jest.fn(),
  generateOAuthErrorDescription: jest.fn((p: unknown) => String(p)),
}));

jest.mock("@/utils/configUtils", () => ({
  initializeInspectorConfig: jest.fn(() => ({})),
}));

jest.mock("@/lib/hooks/useToast", () => ({
  useToast: () => ({ toast: jest.fn() }),
}));

const mockedExchange = exchangeAuthCode as jest.Mock;
const mockedGetPending = getPendingAuthCodeFlow as jest.Mock;
const mockedClear = clearPendingAuthCodeFlow as jest.Mock;
const mockParseParams = jest.requireMock(
  "@/utils/oauthUtils.ts",
).parseOAuthCallbackParams as jest.Mock;

describe("OAuthCallback — auth-code profile exchange (D9B)", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    window.history.replaceState(
      {},
      document.title,
      "/oauth/callback?code=c&state=s",
    );
    mockedGetPending.mockReturnValue({
      profileId: "pid-1",
      state: "s",
      codeVerifier: "verifier",
      redirectUri: "http://localhost/oauth/callback",
    });
    mockedExchange.mockResolvedValue({ profileId: "pid-returned" });
    mockParseParams.mockReturnValue({
      successful: true,
      code: "c",
    });
  });

  test("onProfileAuthorized receives the exchanged profileId (callback-to-first-wire)", async () => {
    const onProfileAuthorized = jest.fn();
    const onConnect = jest.fn();

    render(
      <OAuthCallback
        onConnect={onConnect}
        onProfileAuthorized={onProfileAuthorized}
      />,
    );

    // the async handleCallback resolves on a later microtask
    await screen.findByText("Processing OAuth callback...");

    expect(mockedExchange).toHaveBeenCalledWith(
      {},
      "pid-1",
      "c",
      "verifier",
      "s",
    );
    expect(onProfileAuthorized).toHaveBeenCalledWith("pid-returned");
    expect(onConnect).not.toHaveBeenCalled();
    expect(mockedClear).toHaveBeenCalled();
  });

  test("state mismatch does not authorize and clears the pending flow", async () => {
    const onProfileAuthorized = jest.fn();
    window.history.replaceState(
      {},
      document.title,
      "/oauth/callback?code=c&state=WRONG",
    );
    mockParseParams.mockReturnValue({
      successful: true,
      code: "c",
    });

    render(
      <OAuthCallback
        onConnect={jest.fn()}
        onProfileAuthorized={onProfileAuthorized}
      />,
    );

    await screen.findByText("Processing OAuth callback...");

    expect(mockedExchange).not.toHaveBeenCalled();
    expect(onProfileAuthorized).not.toHaveBeenCalled();
    expect(mockedClear).toHaveBeenCalled();
  });
});
