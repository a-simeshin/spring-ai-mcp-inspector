/**
 * [spring-ai-mcp-inspector PATCH] Regression tests for issue #268: pristine
 * load of the auth-profiles panel. On a fresh mount with empty persisted
 * state the panel renders zero validation messages anywhere in the auth
 * profile editor, the editor section starts collapsed and expands only via
 * the create-profile action, and the "Profile name is required" error
 * surfaces only after the user interacts (blurring the empty Name field, or
 * a save attempt that the disabled save button blocks while the draft is
 * invalid), then clears once valid values are entered.
 *
 * Kept in a separate spec from AuthProfiles.test.tsx so these regression
 * guarantees land independently of the reworked editor tests in that file.
 */
import { fireEvent, render, screen } from "@testing-library/react";
import "@testing-library/jest-dom";
import AuthProfiles from "../AuthProfiles";
import { DEFAULT_INSPECTOR_CONFIG } from "@/lib/constants";
import { InspectorConfig } from "@/lib/configurationTypes";

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

function renderPanel() {
  render(
    <AuthProfiles
      config={CONFIG}
      profiles={[]}
      onProfilesChange={jest.fn()}
      activeProfileId={null}
      onActiveProfileChange={jest.fn()}
    />,
  );
}

describe("AuthProfiles pristine-load validation (issue #268)", () => {
  beforeEach(() => {
    // Pristine load: no persisted editor/list state leaks between cases.
    localStorage.clear();
    sessionStorage.clear();
    // Mount-time GET list + GET prefill return empty lists.
    global.fetch = jest.fn().mockResolvedValue(okJson([])) as jest.Mock;
  });

  afterEach(() => {
    jest.restoreAllMocks();
  });

  it("renders no validation messages anywhere on a pristine load", async () => {
    renderPanel();
    // Wait for the mount fetches to settle before judging the rendered state.
    await screen.findByTestId("auth-profiles-empty");
    expect(
      screen.queryByTestId("auth-profile-validation"),
    ).not.toBeInTheDocument();
    expect(screen.queryAllByRole("alert")).toHaveLength(0);
    expect(
      screen.queryByText("Profile name is required"),
    ).not.toBeInTheDocument();
  });

  it("renders the editor section collapsed on initial render", async () => {
    renderPanel();
    await screen.findByTestId("auth-profiles-empty");
    expect(
      screen.queryByTestId("auth-profile-editor"),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByTestId("auth-profile-save"),
    ).not.toBeInTheDocument();
    expect(screen.getByTestId("auth-profile-editor-toggle")).toBeEnabled();
  });

  it("shows 'Profile name is required' after blurring the empty Name field", async () => {
    renderPanel();
    fireEvent.click(await screen.findByTestId("auth-profile-editor-toggle"));
    // Expanding the editor alone reveals no error.
    expect(
      screen.queryByTestId("auth-profile-validation"),
    ).not.toBeInTheDocument();
    fireEvent.blur(screen.getByTestId("auth-profile-name"));
    expect(screen.getByTestId("auth-profile-validation")).toHaveTextContent(
      "Profile name is required",
    );
  });

  // Issue #268 names two reveal paths: a save attempt on an empty required
  // field or a field blur. The shipped UX keeps the save button disabled
  // while the draft is invalid (validateProfileDraft), so a save attempt on
  // an empty required field is blocked outright and reveals no error: the
  // blur interaction is the path that surfaces it.
  it("blocks a save attempt on an empty required field and reveals the error via field blur", async () => {
    renderPanel();
    fireEvent.click(await screen.findByTestId("auth-profile-editor-toggle"));
    expect(
      screen.queryByTestId("auth-profile-validation"),
    ).not.toBeInTheDocument();
    const save = screen.getByTestId("auth-profile-save");
    fireEvent.click(save);
    // The disabled button cannot start a save, so the attempt reveals no error.
    expect(save).toBeDisabled();
    expect(
      screen.queryByTestId("auth-profile-validation"),
    ).not.toBeInTheDocument();
    // The blur that precedes further interaction reveals the error.
    fireEvent.blur(screen.getByTestId("auth-profile-name"));
    expect(screen.getByTestId("auth-profile-validation")).toHaveTextContent(
      "Profile name is required",
    );
  });

  it("clears the error once valid values are entered", async () => {
    renderPanel();
    fireEvent.click(await screen.findByTestId("auth-profile-editor-toggle"));
    fireEvent.blur(screen.getByTestId("auth-profile-name"));
    expect(screen.getByTestId("auth-profile-validation")).toHaveTextContent(
      "Profile name is required",
    );
    // The name error clears as soon as the name becomes valid...
    fireEvent.change(screen.getByTestId("auth-profile-name"), {
      target: { value: "Pristine Regression" },
    });
    expect(
      screen.getByTestId("auth-profile-validation"),
    ).not.toHaveTextContent("Profile name is required");
    // ...and the default bearer draft needs its token before the error
    // element disappears entirely.
    fireEvent.change(screen.getByTestId("auth-profile-bearer-token"), {
      target: { value: "bearer-token-value" },
    });
    expect(
      screen.queryByTestId("auth-profile-validation"),
    ).not.toBeInTheDocument();
  });

  it("expands the editor on the create-profile action", async () => {
    renderPanel();
    await screen.findByTestId("auth-profiles-empty");
    expect(
      screen.queryByTestId("auth-profile-editor"),
    ).not.toBeInTheDocument();
    fireEvent.click(screen.getByTestId("auth-profile-editor-toggle"));
    expect(screen.getByTestId("auth-profile-editor")).toBeInTheDocument();
    expect(screen.getByTestId("auth-profile-name")).toBeInTheDocument();
    // A fresh create draft starts untouched: no error on expansion.
    expect(
      screen.queryByTestId("auth-profile-validation"),
    ).not.toBeInTheDocument();
  });
});
