// [spring-ai-mcp-inspector PATCH] Ping inline feedback regression coverage (#232)
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import "@testing-library/jest-dom";
import { describe, it, jest, beforeEach } from "@jest/globals";
import { Tabs } from "@/components/ui/tabs";
import PingTab from "../PingTab";

const renderPingTab = (onPingClick: () => Promise<unknown>) =>
  render(
    <Tabs defaultValue="ping">
      <PingTab onPingClick={onPingClick} />
    </Tabs>,
  );

describe("PingTab", () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it("shows pending state with disabled button while ping is in flight", () => {
    const onPingClick = jest.fn(
      () => new Promise<unknown>(() => {}),
    );
    renderPingTab(onPingClick);

    fireEvent.click(screen.getByRole("button", { name: /ping server/i }));

    expect(
      screen.getByRole("button", { name: /ping server/i }),
    ).toBeDisabled();
    expect(screen.getByText(/pinging/i)).toBeInTheDocument();
  });

  it("shows success with round-trip latency when ping resolves", async () => {
    const onPingClick = jest.fn(() => Promise.resolve());
    renderPingTab(onPingClick);

    fireEvent.click(screen.getByRole("button", { name: /ping server/i }));

    await waitFor(() => {
      expect(screen.getByText(/Pong! Round-trip:/)).toBeInTheDocument();
    });
    expect(
      screen.getByRole("button", { name: /ping server/i }),
    ).not.toBeDisabled();
  });

  it("shows transport failure text when ping rejects with an Error", async () => {
    const onPingClick = jest.fn(() =>
      Promise.reject(new Error("Failed to fetch")),
    );
    renderPingTab(onPingClick);

    fireEvent.click(screen.getByRole("button", { name: /ping server/i }));

    await waitFor(() => {
      expect(
        screen.getByText("Ping failed: Failed to fetch"),
      ).toBeInTheDocument();
    });
    expect(
      screen.getByRole("button", { name: /ping server/i }),
    ).not.toBeDisabled();
  });

  it("updates result on subsequent click after a previous success", async () => {
    const onPingClick = jest.fn().mockResolvedValue(undefined);
    renderPingTab(onPingClick);

    fireEvent.click(screen.getByRole("button", { name: /ping server/i }));
    await waitFor(() => {
      expect(screen.getByText(/Pong! Round-trip:/)).toBeInTheDocument();
    });

    // Second click should produce a new result
    fireEvent.click(screen.getByRole("button", { name: /ping server/i }));
    await waitFor(() => {
      expect(screen.getByText(/Pong! Round-trip:/)).toBeInTheDocument();
    });
  });
});
