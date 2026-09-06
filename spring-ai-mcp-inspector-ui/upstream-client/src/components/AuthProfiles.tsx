/**
 * [spring-ai-mcp-inspector PATCH] Named auth profiles panel (issue #54, plan
 * v15 D1/D2/D7/D9B).
 *
 * Local, non-upstream component: profile editor/selector for the four profile
 * kinds (OAuth2 client-credentials + authorization-code, Bearer, API-key,
 * Custom-headers), each with a required unique `name` (AC1); owner-scoped
 * list/rename/delete via `GET/PUT/DELETE /mcp-inspector/api/auth-profile`
 * (AC2); Spring-config prefill selection (AC3); and the browser-driven
 * authorization-code two-phase flow — pending create with PKCE challenge →
 * redirect with the SERVER-issued state → `/exchange` (AC8).
 *
 * Secrets live in React state only and travel backend-ward via the
 * `/auth-profile` handoff; nothing secret is persisted to localStorage.
 */
import { useCallback, useEffect, useMemo, useState, useRef } from "react";
import { AlertCircle, Loader2, Pencil, Trash2 } from "lucide-react";
import { useToast } from "@/lib/hooks/useToast";
import { InspectorConfig } from "@/lib/configurationTypes";
import { CustomHeaders as CustomHeadersType } from "@/lib/types/customHeaders";
import CustomHeaders from "./CustomHeaders";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  AuthProfileSummary,
  AuthProfileType,
  ApiKeyPlacement,
  OAuth2GrantMode,
  ProfileDraft,
  buildAuthorizationUrl,
  createAuthProfile,
  createAuthProfileFromPrefill,
  createPendingAuthCodeProfile,
  deleteAuthProfile,
  draftFromPrefill,
  generateCodeChallengePair,
  listAuthProfiles,
  listPrefillAuthProfiles,
  savePendingAuthCodeFlow,
  updateAuthProfile,
  validateProfileDraft,
} from "@/lib/auth-profiles";

interface AuthProfilesProps {
  config: InspectorConfig;
  /** Owner-scoped saved profiles (server summaries, no secrets). */
  profiles: AuthProfileSummary[];
  onProfilesChange: (profiles: AuthProfileSummary[]) => void;
  activeProfileId: string | null;
  onActiveProfileChange: (profileId: string | null) => void;
}

const draftTemplate = (type: AuthProfileType): ProfileDraft => {
  switch (type) {
    case "OAUTH2":
      return {
        name: "",
        type: "OAUTH2",
        grantMode: "CLIENT_CREDENTIALS",
        tokenUrl: "",
        clientId: "",
        clientSecret: "",
        scopes: "",
        authorizationUrl: "",
        redirectUri: "",
      };
    case "BEARER":
      return { name: "", type: "BEARER", token: "" };
    case "API_KEY":
      return { name: "", type: "API_KEY", keyName: "", keyValue: "", placement: "HEADER" };
    case "CUSTOM_HEADERS":
      return {
        name: "",
        type: "CUSTOM_HEADERS",
        headers: [{ name: "Authorization", value: "Bearer ", enabled: false }],
      };
  }
};

const AUTH_CODE_DEFAULT_REDIRECT_URI = () =>
  `${window.location.origin}/oauth/callback`;

const AuthProfiles = ({
  config,
  profiles,
  onProfilesChange,
  activeProfileId,
  onActiveProfileChange,
}: AuthProfilesProps) => {
  const { toast } = useToast();
  const [draft, setDraft] = useState<ProfileDraft>(() => draftTemplate("BEARER"));
  const [validationError, setValidationError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [authCodeBusy, setAuthCodeBusy] = useState(false);
  const [loading, setLoading] = useState(true);
  const [prefill, setPrefill] = useState<AuthProfileSummary[]>([]);
  const [editingProfileId, setEditingProfileId] = useState<string | null>(null);
  /**
   * Session-memory drafts of profiles created/updated in this tab. The backend
   * never returns secrets, so PUT (which replaces the profile wholesale) can
   * only keep a secret when the UI still holds it. This map makes the common
   * create → rename/update path work without re-entering secrets.
   */
  const [lastDraftByProfileId, setLastDraftByProfileId] = useState<
    Record<string, ProfileDraft>
  >({});
  const nameInputRef = useRef<HTMLInputElement>(null);

  const notifyError = useCallback(
    (description: string) => {
      const friendly = description
        .replace("Failed to fetch", "Unable to connect to the server. Check that the server is running.")
        .replace("NetworkError", "Network error. Please check your connection.")
        .replace("timeout", "Request timed out. Try again.");
      toast({ title: "Auth Profile Error", description: friendly, variant: "destructive" });
    },
    [toast],
  );

  const refreshProfiles = useCallback(async () => {
    setLoading(true);
    try {
      onProfilesChange(await listAuthProfiles(config));
    } catch (error) {
      notifyError(error instanceof Error ? error.message : String(error));
    } finally {
      setLoading(false);
    }
  }, [config, onProfilesChange, notifyError]);

  useEffect(() => {
    void refreshProfiles();
    void listPrefillAuthProfiles(config)
      .then(setPrefill)
      .catch((error) => {
        notifyError(error instanceof Error ? error.message : String(error));
      });
    // [spring-ai-mcp-inspector PATCH] One-shot mount refresh; the panel is
    // mounted once per App lifetime.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const otherNames = useMemo(
    () =>
      profiles
        .filter((p) => p.profileId !== editingProfileId)
        .map((p) => p.name),
    [profiles, editingProfileId],
  );

  const editorError = useMemo(
    () => validateProfileDraft(draft, otherNames),
    [draft, otherNames],
  );

  /**
   * Typed field updater for the discriminated-union draft. The spread with a
   * computed patch is intentionally widened and re-narrowed to ProfileDraft —
   * each call site passes a patch whose keys belong to the current variant.
   */
  const updateDraft = (patch: Record<string, unknown>) => {
    setDraft((prev) => ({ ...prev, ...patch }) as ProfileDraft);
  };

  const switchType = (type: AuthProfileType) => {
    setDraft(draftTemplate(type));
    setEditingProfileId(null);
    setValidationError(null);
  };

  const handleSaved = async (
    profileId: string,
    savedDraft: ProfileDraft,
    message: string,
  ) => {
    setLastDraftByProfileId((prev) => ({ ...prev, [profileId]: savedDraft }));
    setValidationError(null);
    setEditingProfileId(null);
    setDraft(draftTemplate(savedDraft.type));
    onActiveProfileChange(profileId);
    await refreshProfiles();
    toast({ title: "Auth Profile", description: message, variant: "default" });
  };

  const handleSave = async () => {
    const error = validateProfileDraft(draft, otherNames);
    setValidationError(error);
    if (error) {
      return;
    }
    setSaving(true);
    try {
      const { profileId } = await createAuthProfile(config, draft);
      await handleSaved(profileId, draft, `Profile "${draft.name}" saved`);
    } catch (err) {
      notifyError(err instanceof Error ? err.message : String(err));
    } finally {
      setSaving(false);
    }
  };

  /** D9B phase 1+2: pending create → PKCE redirect with server-issued state. */
  const handleAuthorize = async () => {
    const error = validateProfileDraft(draft, otherNames);
    setValidationError(error);
    if (error) {
      return;
    }
    if (draft.type !== "OAUTH2" || draft.grantMode !== "AUTHORIZATION_CODE") {
      return;
    }
    // The runtime guard above narrows the discriminant; re-cast so the
    // createPendingAuthCodeProfile parameter type (grantMode literal) lines up.
    const authCodeDraft = draft as ProfileDraft & {
      type: "OAUTH2";
      grantMode: "AUTHORIZATION_CODE";
    };
    setAuthCodeBusy(true);
    try {
      const { codeVerifier, codeChallenge } = await generateCodeChallengePair();
      const response = await createPendingAuthCodeProfile(
        config,
        authCodeDraft,
        codeChallenge,
      );
      if (!response.state || !response.authorizationUrl) {
        throw new Error(
          "The server did not return an authorization URL for the pending profile",
        );
      }
      savePendingAuthCodeFlow({
        profileId: response.profileId,
        state: response.state,
        codeVerifier,
        redirectUri: draft.redirectUri,
      });
      const redirectUrl = buildAuthorizationUrl(response.authorizationUrl, {
        clientId: draft.clientId,
        redirectUri: draft.redirectUri,
        scope: draft.scopes,
        codeChallenge,
        state: response.state,
      });
      // Navigate away to the IdP; the callback page performs /exchange.
      window.location.href = redirectUrl;
    } catch (err) {
      notifyError(err instanceof Error ? err.message : String(err));
      setAuthCodeBusy(false);
    }
  };

  // Focus name input when editing starts (UI/UX fix, owner review round 2)
  useEffect(() => {
    if (editingProfileId && nameInputRef.current) {
      nameInputRef.current.focus();
    }
  }, [editingProfileId]);

  /** Loads a saved profile into the editor for rename / field update (PUT). */
  const handleEdit = (summary: AuthProfileSummary) => {
    setEditingProfileId(summary.profileId ?? null);
    const known = summary.profileId
      ? lastDraftByProfileId[summary.profileId]
      : undefined;
    setDraft(known ?? draftFromPrefill(summary));
    setValidationError(null);
  };

  const handleUpdate = async () => {
    if (!editingProfileId) {
      return;
    }
    const error = validateProfileDraft(draft, otherNames);
    setValidationError(error);
    if (error) {
      return;
    }
    setSaving(true);
    try {
      await updateAuthProfile(config, editingProfileId, draft);
      await handleSaved(editingProfileId, draft, `Profile "${draft.name}" updated`);
    } catch (err) {
      notifyError(err instanceof Error ? err.message : String(err));
    } finally {
      setSaving(false);
    }
  };

  const handleDelete = async (summary: AuthProfileSummary) => {
    if (!summary.profileId) {
      return;
    }
    try {
      await deleteAuthProfile(config, summary.profileId);
      setLastDraftByProfileId((prev) => {
        const next = { ...prev };
        delete next[summary.profileId as string];
        return next;
      });
      if (activeProfileId === summary.profileId) {
        onActiveProfileChange(null);
      }
      await refreshProfiles();
    } catch (err) {
      notifyError(err instanceof Error ? err.message : String(err));
    }
  };

  /** D7 prefill selection: POST {name, type} reference → server resolves. */
  const handlePrefillPick = async (summary: AuthProfileSummary) => {
    try {
      const { profileId } = await createAuthProfileFromPrefill(
        config,
        summary.name,
        summary.type,
      );
      setLastDraftByProfileId((prev) => ({
        ...prev,
        [profileId]: draftFromPrefill(summary),
      }));
      onActiveProfileChange(profileId);
      await refreshProfiles();
      toast({
        title: "Auth Profile",
        description: `Prefill profile "${summary.name}" activated`,
        variant: "default",
      });
    } catch (err) {
      notifyError(err instanceof Error ? err.message : String(err));
    }
  };

  const isOAuth2AuthCode =
    draft.type === "OAUTH2" && draft.grantMode === "AUTHORIZATION_CODE";

  // Hide panel for stdio transport where no profile type applies (UI/UX fix, owner review round 2)
  if (config.transport?.type === "stdio") {
    return null;
  }

  return (
    <div className="space-y-3" data-testid="auth-profiles-panel">
      <div className="flex items-center justify-between">
        <h4 className="text-sm font-semibold">Auth Profiles</h4>
        <Button
          variant="ghost"
          size="sm"
          className="h-7 px-2 text-xs"
          onClick={() => void refreshProfiles()}
          data-testid="auth-profiles-refresh"
        >
          Refresh
        </Button>
      </div>

      {/* Saved profile selector */}
      {loading && profiles.length === 0 ? (
        <div className="space-y-1" data-testid="auth-profiles-loading">
          <Label className="text-xs text-muted-foreground">Loading profiles...</Label>
        </div>
      ) : profiles.length === 0 ? (
        <div className="space-y-1" data-testid="auth-profiles-empty">
          <Label className="text-xs text-muted-foreground">No saved profiles</Label>
          <p className="text-xs text-muted-foreground">Create a profile below to get started.</p>
        </div>
      ) : (
        <div className="space-y-1" data-testid="auth-profiles-list" role="radiogroup" aria-label="Saved profiles">
          <Label className="text-xs text-muted-foreground">Saved profiles</Label>
          {profiles.map((profile) => (
            <div
              key={profile.profileId}
              className="flex items-center gap-2 rounded border px-2 py-1.5"
              data-testid={`auth-profile-row-${profile.profileId}`}
            >
              <input
                type="radio"
                name="auth-profile-select"
                data-testid={`auth-profile-select-${profile.profileId}`}
                checked={activeProfileId === profile.profileId}
                onChange={() => onActiveProfileChange(profile.profileId ?? null)}
                className="h-3.5 w-3.5"
                aria-label={`Select profile ${profile.name}`}
              />
              <span className="flex-1 truncate text-sm" title={profile.name}>
                {profile.name}
                <span className="ml-2 text-xs text-muted-foreground">
                  {profile.type.replace("_", " ").toLowerCase()}
                </span>
              </span>
              <Button
                variant="ghost"
                size="icon"
                className="h-6 w-6"
                aria-label={`Edit profile ${profile.name}`}
                data-testid={`auth-profile-edit-${profile.profileId}`}
                onClick={() => handleEdit(profile)}
              >
                <Pencil className="h-3.5 w-3.5" />
              </Button>
              <Button
                variant="ghost"
                size="icon"
                className="h-6 w-6"
                aria-label={`Delete profile ${profile.name}`}
                data-testid={`auth-profile-delete-${profile.profileId}`}
                onClick={() => void handleDelete(profile)}
              >
                <Trash2 className="h-3.5 w-3.5" />
              </Button>
            </div>
          ))}
        </div>
      )}

      {/* Spring-config prefill selection (D7) */}
      {prefill.length > 0 && (
        <div className="space-y-1" data-testid="auth-profiles-prefill">
          <Label className="text-xs text-muted-foreground">
            Prefill from server config
          </Label>
          {prefill.map((summary) => (
            <Button
              key={summary.name}
              variant="outline"
              size="sm"
              className="w-full justify-start text-xs"
              data-testid={`auth-profile-prefill-${summary.name}`}
              onClick={() => void handlePrefillPick(summary)}
            >
              {summary.name} ({summary.type.replace("_", " ").toLowerCase()})
            </Button>
          ))}
        </div>
      )}

      {/* Editor */}
      <div className="space-y-2 rounded border p-3" data-testid="auth-profile-editor">
        <div className="flex items-center gap-2">
          <Label htmlFor="auth-profile-type" className="text-xs text-muted-foreground shrink-0">Type</Label>
          <select
            id="auth-profile-type"
            value={draft.type}
            onChange={(e) => switchType(e.target.value as AuthProfileType)}
            className="h-8 flex-1 rounded border bg-transparent px-2 text-sm"
            data-testid="auth-profile-type"
          >
            <option value="OAUTH2">OAuth2</option>
            <option value="BEARER">Bearer token</option>
            <option value="API_KEY">API key</option>
            <option value="CUSTOM_HEADERS">Custom headers</option>
          </select>
        </div>

        <div className="space-y-1">
          <Label htmlFor="auth-profile-name" className="text-xs">
            Name
          </Label>
          <Input
            ref={nameInputRef}
            id="auth-profile-name"
            placeholder="Unique profile name"
            value={draft.name}
            onChange={(e) => updateDraft({ name: e.target.value })}
            data-testid="auth-profile-name"
            className="font-mono"
          />
        </div>

        {draft.type === "OAUTH2" && (
          <>
            <div className="flex items-center gap-2">
              <Label htmlFor="auth-profile-grant-mode" className="text-xs text-muted-foreground shrink-0">
                Grant
              </Label>
              <select
                id="auth-profile-grant-mode"
                value={draft.grantMode}
                onChange={(e) =>
                  updateDraft({ grantMode: e.target.value as OAuth2GrantMode })
                }
                className="h-8 flex-1 rounded border bg-transparent px-2 text-sm"
                data-testid="auth-profile-grant-mode"
              >
                <option value="CLIENT_CREDENTIALS">Client credentials</option>
                <option value="AUTHORIZATION_CODE">Authorization code (PKCE)</option>
              </select>
            </div>
            <div className="space-y-1">
              <Label htmlFor="auth-profile-token-url" className="text-xs">
                Token URL
              </Label>
              <Input
                id="auth-profile-token-url"
                placeholder="https://idp.example.com/oauth/token"
                value={draft.tokenUrl}
                onChange={(e) => updateDraft({ tokenUrl: e.target.value })}
                data-testid="auth-profile-token-url"
                className="font-mono"
              />
            </div>
            <div className="space-y-1">
              <Label htmlFor="auth-profile-client-id" className="text-xs">
                Client ID
              </Label>
              <Input
                id="auth-profile-client-id"
                placeholder="Client ID"
                value={draft.clientId}
                onChange={(e) => updateDraft({ clientId: e.target.value })}
                data-testid="auth-profile-client-id"
                className="font-mono"
              />
            </div>
            {draft.grantMode === "CLIENT_CREDENTIALS" && (
              <div className="space-y-1">
                <Label htmlFor="auth-profile-client-secret" className="text-xs">
                  Client secret
                </Label>
                <Input
                  id="auth-profile-client-secret"
                  type="password"
                  placeholder={
                    editingProfileId && !draft.clientSecret
                      ? "Re-enter to replace (kept server-side)"
                      : "Client secret"
                  }
                  value={draft.clientSecret}
                  onChange={(e) =>
                    updateDraft({ clientSecret: e.target.value })
                  }
                  data-testid="auth-profile-client-secret"
                  className="font-mono"
                />
              </div>
            )}
            <div className="space-y-1">
              <Label htmlFor="auth-profile-scopes" className="text-xs">
                Scopes
              </Label>
              <Input
                id="auth-profile-scopes"
                placeholder="Space-separated scopes (optional)"
                value={draft.scopes}
                onChange={(e) => updateDraft({ scopes: e.target.value })}
                data-testid="auth-profile-scopes"
                className="font-mono"
              />
            </div>
            {draft.grantMode === "AUTHORIZATION_CODE" && (
              <>
                <div className="space-y-1">
                  <Label htmlFor="auth-profile-authorization-url" className="text-xs">
                    Authorization URL
                  </Label>
                  <Input
                    id="auth-profile-authorization-url"
                    placeholder="https://idp.example.com/oauth/authorize"
                    value={draft.authorizationUrl}
                    onChange={(e) =>
                      updateDraft({ authorizationUrl: e.target.value })
                    }
                    data-testid="auth-profile-authorization-url"
                    className="font-mono"
                  />
                </div>
                <div className="space-y-1">
                  <Label htmlFor="auth-profile-redirect-uri" className="text-xs">
                    Redirect URI
                  </Label>
                  <Input
                    id="auth-profile-redirect-uri"
                    placeholder="https://…/oauth/callback"
                    value={draft.redirectUri}
                    onChange={(e) => updateDraft({ redirectUri: e.target.value })}
                    data-testid="auth-profile-redirect-uri"
                    className="font-mono"
                  />
                  <Button
                    variant="link"
                    size="sm"
                    className="h-5 px-0 text-xs"
                    onClick={() =>
                      updateDraft({ redirectUri: AUTH_CODE_DEFAULT_REDIRECT_URI() })
                    }
                  >
                    Use this inspector&apos;s callback URL
                  </Button>
                </div>
              </>
            )}
          </>
        )}

        {draft.type === "BEARER" && (
          <div className="space-y-1">
            <Label htmlFor="auth-profile-bearer-token" className="text-xs">
              Bearer token
            </Label>
            <Input
              id="auth-profile-bearer-token"
              type="password"
              placeholder={
                editingProfileId && !draft.token
                  ? "Re-enter to replace (kept server-side)"
                  : "Token"
              }
              value={draft.token}
              onChange={(e) => updateDraft({ token: e.target.value })}
              data-testid="auth-profile-bearer-token"
              className="font-mono"
            />
          </div>
        )}

        {draft.type === "API_KEY" && (
          <>
            <div className="space-y-1">
              <Label htmlFor="auth-profile-key-name" className="text-xs">
                Key name
              </Label>
              <Input
                id="auth-profile-key-name"
                placeholder="X-API-Key"
                value={draft.keyName}
                onChange={(e) => updateDraft({ keyName: e.target.value })}
                data-testid="auth-profile-key-name"
                className="font-mono"
              />
            </div>
            <div className="space-y-1">
              <Label htmlFor="auth-profile-key-value" className="text-xs">
                Key value
              </Label>
              <Input
                id="auth-profile-key-value"
                type="password"
                placeholder={
                  editingProfileId && !draft.keyValue
                    ? "Re-enter to replace (kept server-side)"
                    : "Value"
                }
                value={draft.keyValue}
                onChange={(e) => updateDraft({ keyValue: e.target.value })}
                data-testid="auth-profile-key-value"
                className="font-mono"
              />
            </div>
            <div className="flex items-center gap-2">
              <Label htmlFor="auth-profile-key-placement" className="text-xs text-muted-foreground shrink-0">
                Placement
              </Label>
              <select
                id="auth-profile-key-placement"
                value={draft.placement}
                onChange={(e) =>
                  updateDraft({ placement: e.target.value as ApiKeyPlacement })
                }
                className="h-8 flex-1 rounded border bg-transparent px-2 text-sm"
                data-testid="auth-profile-key-placement"
              >
                <option value="HEADER">Header</option>
                <option value="QUERY">Query parameter</option>
              </select>
            </div>
          </>
        )}

        {draft.type === "CUSTOM_HEADERS" && (
          <div className="space-y-1">
            <Label htmlFor="auth-profile-custom-headers" className="text-xs">Headers</Label>
            <div className="max-h-40 overflow-y-auto rounded border p-1">
              <CustomHeaders
                headers={draft.headers}
                onChange={(headers: CustomHeadersType) =>
                  updateDraft({ headers })
                }
              />
            </div>
          </div>
        )}

        {(validationError || editorError) && (
          <div
            className="flex items-center gap-1.5 text-xs text-red-600"
            data-testid="auth-profile-validation"
            role="alert"
          >
            <AlertCircle className="h-3.5 w-3.5 shrink-0" />
            {validationError ?? editorError}
          </div>
        )}

        {editingProfileId ? (
          <div className="flex gap-2">
            <Button
              size="sm"
              className="flex-1"
              onClick={() => void handleUpdate()}
              disabled={saving || editorError !== null}
              data-testid="auth-profile-update"
            >
              {saving ? <Loader2 className="h-4 w-4 animate-spin" /> : "Update"}
            </Button>
            <Button
              variant="outline"
              size="sm"
              onClick={() => {
                setEditingProfileId(null);
                setDraft(draftTemplate(draft.type));
                setValidationError(null);
              }}
              data-testid="auth-profile-cancel-edit"
            >
              Cancel
            </Button>
          </div>
        ) : isOAuth2AuthCode ? (
          <Button
            size="sm"
            className="w-full"
            onClick={() => void handleAuthorize()}
            disabled={authCodeBusy || editorError !== null}
            data-testid="auth-profile-authorize"
          >
            {authCodeBusy ? (
              <Loader2 className="h-4 w-4 animate-spin" />
            ) : (
              "Create & Authorize (PKCE)"
            )}
          </Button>
        ) : (
          <Button
            size="sm"
            className="w-full"
            onClick={() => void handleSave()}
            disabled={saving || editorError !== null}
            data-testid="auth-profile-save"
          >
            {saving ? <Loader2 className="h-4 w-4 animate-spin" /> : "Save profile"}
          </Button>
        )}
        {editingProfileId && (
          <p className="text-xs text-muted-foreground" data-testid="auth-profile-edit-hint">
            Editing an existing profile. Secret fields are kept server-side;
            leave them empty to keep the stored value (re-enter to replace).
          </p>
        )}
      </div>
    </div>
  );
};

export default AuthProfiles;