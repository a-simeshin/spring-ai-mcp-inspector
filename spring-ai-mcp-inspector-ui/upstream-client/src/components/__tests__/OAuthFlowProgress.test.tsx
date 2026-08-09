// [spring-ai-mcp-inspector PATCH] Regression guard for
// https://github.com/a-simeshin/spring-ai-mcp-inspector/issues/26.
//
// The server URL reaches this component as a same-origin relative path ("/sse"),
// and the resource-metadata links are built with
// `new URL("/.well-known/oauth-protected-resource", serverUrl)`, which throws
// "Invalid base URL" on a relative base. Thrown during render with no error
// boundary above it, React 19 unmounted the whole tree — the OAuth flow
// completed and the user was left with a blank page.
import { render, screen } from "@testing-library/react";
import "@testing-library/jest-dom";
import { OAuthFlowProgress } from "../OAuthFlowProgress";
import { EMPTY_DEBUGGER_STATE } from "../../lib/auth-types";

// The SDK's client/auth.js pulls in pkce-challenge, which does not resolve under
// the jest CJS resolver; nothing in this render path calls it.
jest.mock("@modelcontextprotocol/sdk/client/auth.js", () => ({
  auth: jest.fn(),
  discoverAuthorizationServerMetadata: jest.fn(),
  discoverOAuthProtectedResourceMetadata: jest.fn(),
  selectResourceURL: jest.fn(),
  registerClient: jest.fn(),
  startAuthorization: jest.fn(),
  exchangeAuthorization: jest.fn(),
}));

describe("OAuthFlowProgress with a relative server URL", () => {
  it("renders the resource-metadata links instead of throwing Invalid base URL", () => {
    render(
      <OAuthFlowProgress
        serverUrl="/sse"
        authState={{
          ...EMPTY_DEBUGGER_STATE,
          oauthStep: "client_registration",
          oauthMetadata: {
            issuer: "https://auth.example.com",
            authorization_endpoint: "https://auth.example.com/authorize",
            token_endpoint: "https://auth.example.com/token",
            response_types_supported: ["code"],
          },
          resourceMetadataError: new Error("404"),
        }}
        updateAuthState={() => {}}
        proceedToNextStep={async () => {}}
      />,
    );

    expect(screen.getByText("OAuth Flow Progress")).toBeInTheDocument();
    expect(
      screen.getByRole("link", {
        name: `${window.location.origin}/.well-known/oauth-protected-resource`,
      }),
    ).toBeInTheDocument();
  });
});
