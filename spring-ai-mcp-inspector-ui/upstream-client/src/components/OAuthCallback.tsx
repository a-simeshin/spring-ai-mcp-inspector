import { useEffect, useRef } from "react";
import { InspectorOAuthClientProvider } from "../lib/auth";
import { SESSION_KEYS } from "../lib/constants";
import { auth } from "@modelcontextprotocol/sdk/client/auth.js";
import { useToast } from "@/lib/hooks/useToast";
import {
  generateOAuthErrorDescription,
  parseOAuthCallbackParams,
} from "@/utils/oauthUtils.ts";
// [spring-ai-mcp-inspector PATCH] Auth-code profile exchange (issue #54, D9B
// phase 3): the IdP bounces back to the redirect URI with `code` + the
// SERVER-issued `state`; the pending flow record (profileId/codeVerifier/state)
// lives in sessionStorage, and the exchange completes against
// `/auth-profile/{profileId}/exchange`.
import {
  clearPendingAuthCodeFlow,
  exchangeAuthCode,
  getPendingAuthCodeFlow,
} from "@/lib/auth-profiles";
import { initializeInspectorConfig } from "@/utils/configUtils";

interface OAuthCallbackProps {
  onConnect: (serverUrl: string) => void;
  /** [spring-ai-mcp-inspector PATCH] Called after an auth-code profile exchange succeeds. */
  onProfileAuthorized?: (profileId: string) => void;
}

const OAuthCallback = ({
  onConnect,
  onProfileAuthorized,
}: OAuthCallbackProps) => {
  const { toast } = useToast();
  const hasProcessedRef = useRef(false);

  useEffect(() => {
    const handleCallback = async () => {
      // Skip if we've already processed this callback
      if (hasProcessedRef.current) {
        return;
      }
      hasProcessedRef.current = true;

      const notifyError = (description: string) =>
        void toast({
          title: "OAuth Authorization Error",
          description,
          variant: "destructive",
        });

      const params = parseOAuthCallbackParams(window.location.search);
      if (!params.successful) {
        return notifyError(generateOAuthErrorDescription(params));
      }

      // [spring-ai-mcp-inspector PATCH] D9B phase 3: an auth-code PROFILE flow
      // carries the server-issued state — match it against the pending record
      // and exchange the code through the backend.
      const pending = getPendingAuthCodeFlow();
      if (pending && params.code) {
        const returnedState =
          new URLSearchParams(window.location.search).get("state") ?? "";
        if (pending.state !== returnedState) {
          clearPendingAuthCodeFlow();
          return notifyError(
            "OAuth state mismatch — the authorization request may have been replayed or tampered with.",
          );
        }
        try {
          const config = initializeInspectorConfig("inspectorConfig_v1");
          const { profileId } = await exchangeAuthCode(
            config,
            pending.profileId,
            params.code,
            pending.codeVerifier,
            pending.state,
          );
          clearPendingAuthCodeFlow();
          toast({
            title: "Success",
            description: "OAuth2 authorization-code profile activated",
            variant: "default",
          });
          onProfileAuthorized?.(profileId);
          return;
        } catch (error) {
          clearPendingAuthCodeFlow();
          console.error("Auth-code profile exchange error:", error);
          return notifyError(
            `Failed to exchange the authorization code: ${
              error instanceof Error ? error.message : String(error)
            }`,
          );
        }
      }

      const serverUrl = sessionStorage.getItem(SESSION_KEYS.SERVER_URL);
      if (!serverUrl) {
        return notifyError("Missing Server URL");
      }

      let result;
      try {
        // Create an auth provider with the current server URL
        const serverAuthProvider = new InspectorOAuthClientProvider(serverUrl);

        result = await auth(serverAuthProvider, {
          serverUrl,
          authorizationCode: params.code,
        });
      } catch (error) {
        console.error("OAuth callback error:", error);
        return notifyError(`Unexpected error occurred: ${error}`);
      }

      if (result !== "AUTHORIZED") {
        return notifyError(
          `Expected to be authorized after providing auth code, got: ${result}`,
        );
      }

      // Finally, trigger auto-connect
      toast({
        title: "Success",
        description: "Successfully authenticated with OAuth",
        variant: "default",
      });
      onConnect(serverUrl);
    };

    handleCallback().finally(() => {
      window.history.replaceState({}, document.title, "/");
    });
    // [spring-ai-mcp-inspector PATCH] onProfileAuthorized is consumed inside
    // handleCallback; hasProcessedRef keeps the callback single-shot.
  }, [toast, onConnect, onProfileAuthorized]);

  return (
    <div className="flex items-center justify-center h-screen">
      <p className="text-lg text-gray-500">Processing OAuth callback...</p>
    </div>
  );
};

export default OAuthCallback;
