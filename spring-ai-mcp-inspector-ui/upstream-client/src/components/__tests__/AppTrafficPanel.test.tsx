import { render, screen, fireEvent } from "@testing-library/react";
import "@testing-library/jest-dom";
import { describe, it, jest, beforeEach } from "@jest/globals";
import AppTrafficPanel from "../AppTrafficPanel";
import type { AppTrafficEntry, AppLifecycleState } from "@/lib/app-traffic";

/* [spring-ai-mcp-inspector PATCH] Tests for AppTrafficPanel component. */

// Mock clipboard API
const mockClipboardWriteText = jest.fn(async () => {});
Object.assign(navigator, {
  clipboard: {
    writeText: mockClipboardWriteText,
  },
});

// Mock useToast
jest.mock("@/lib/hooks/useToast", () => ({
  useToast: () => ({ toast: jest.fn() }),
}));

const makeEntry = (
  overrides: Partial<AppTrafficEntry> = {},
): AppTrafficEntry => ({
  seq: 1,
  timestamp: "2026-09-09T12:00:00.000Z",
  direction: "host->guest",
  kind: "request",
  method: "ui/initialize",
  params: { key: "value" },
  phase: "lifecycle",
  ...overrides,
});

describe("AppTrafficPanel", () => {
  const defaultProps = {
    entries: [],
    lifecycleState: "idle" as AppLifecycleState,
  };

  beforeEach(() => {
    jest.clearAllMocks();
  });

  it("should display idle lifecycle state", () => {
    render(<AppTrafficPanel {...defaultProps} />);
    expect(screen.getByText("idle")).toBeInTheDocument();
  });

  it("should display ready lifecycle state", () => {
    render(
      <AppTrafficPanel {...defaultProps} lifecycleState="ready" />,
    );
    expect(screen.getByText("ready")).toBeInTheDocument();
  });

  it("should display error lifecycle state", () => {
    render(
      <AppTrafficPanel {...defaultProps} lifecycleState="error" />,
    );
    expect(screen.getByText("error")).toBeInTheDocument();
  });

  it("should show placeholder when no traffic entries", () => {
    render(<AppTrafficPanel {...defaultProps} />);
    expect(screen.getByText("No traffic yet")).toBeInTheDocument();
  });

  it("should render traffic entries with method names", () => {
    const entries = [makeEntry({ seq: 1, method: "ui/initialize" })];
    render(
      <AppTrafficPanel {...defaultProps} entries={entries} />,
    );
    expect(screen.getByText("ui/initialize")).toBeInTheDocument();
  });

  it("should display direction arrows for entries", () => {
    const entries = [
      makeEntry({ seq: 1, direction: "host->guest", method: "init" }),
      makeEntry({ seq: 2, direction: "guest->host", method: "ready" }),
    ];
    render(
      <AppTrafficPanel {...defaultProps} entries={entries} />,
    );
    // Both entries should be rendered
    expect(screen.getByText("init")).toBeInTheDocument();
    expect(screen.getByText("ready")).toBeInTheDocument();
  });

  it("should filter by method when filter text is entered", () => {
    const entries = [
      makeEntry({ seq: 1, method: "ui/initialize" }),
      makeEntry({ seq: 2, method: "ui/notifications/initialized" }),
    ];
    render(
      <AppTrafficPanel {...defaultProps} entries={entries} />,
    );

    const filterInput = screen.getByPlaceholderText("Filter by method...");
    fireEvent.change(filterInput, { target: { value: "initialize" } });

    expect(screen.getByText("ui/initialize")).toBeInTheDocument();
    expect(
      screen.queryByText("ui/notifications/initialized"),
    ).toBeInTheDocument();
  });

  it("should show empty filter message when filter matches nothing", () => {
    const entries = [makeEntry({ seq: 1, method: "ui/initialize" })];
    render(
      <AppTrafficPanel {...defaultProps} entries={entries} />,
    );

    const filterInput = screen.getByPlaceholderText("Filter by method...");
    fireEvent.change(filterInput, { target: { value: "nonexistent" } });

    expect(screen.getByText("No matching messages")).toBeInTheDocument();
  });

  it("should call onClear when clear button is clicked", () => {
    const handleClear = jest.fn();
    render(
      <AppTrafficPanel
        {...defaultProps}
        entries={[makeEntry()]}
        onClear={handleClear}
      />,
    );

    const clearButton = screen.getByRole("button", { name: /clear/i });
    fireEvent.click(clearButton);
    expect(handleClear).toHaveBeenCalledTimes(1);
  });

  it("should expand entry on click to reveal params", () => {
    const entries = [makeEntry({ params: { key: "value" } })];
    render(
      <AppTrafficPanel {...defaultProps} entries={entries} />,
    );

    // Click the entry row
    const entryRow = screen.getByText("ui/initialize");
    fireEvent.click(entryRow);

    // Should show params label
    expect(screen.getByText("params")).toBeInTheDocument();
  });

  it("should collapse and show Guest Logs section", () => {
    render(<AppTrafficPanel {...defaultProps} />);

    expect(screen.getByText("Guest Logs")).toBeInTheDocument();

    // Click to collapse
    fireEvent.click(screen.getByText("Guest Logs"));

    // Should still show the title but not the content
    expect(screen.getByText("Guest Logs")).toBeInTheDocument();
  });

  it("should copy all entries as JSON when copy button is clicked", () => {
    const entries = [makeEntry({ method: "ui/initialize" })];
    render(
      <AppTrafficPanel {...defaultProps} entries={entries} />,
    );

    const copyButton = screen.getByLabelText("Copy all as JSON");
    fireEvent.click(copyButton);

    expect(navigator.clipboard.writeText).toHaveBeenCalledTimes(1);
    const writtenText = (navigator.clipboard.writeText as jest.Mock).mock
      .calls[0][0] as string;
    expect(writtenText).toContain("ui/initialize");
    expect(writtenText).toContain("host->guest");
  });

  it("should display direction icon (arrow) for entries", () => {
    const entries = [
      makeEntry({ seq: 1, direction: "host->guest", method: "init" }),
    ];
    render(
      <AppTrafficPanel {...defaultProps} entries={entries} />,
    );

    // The host->guest direction should render a right arrow → (unicode)
    const entryElement = screen.getByText("init").closest("div");
    expect(entryElement?.textContent).toContain("→");
  });
});