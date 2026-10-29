/* [spring-ai-mcp-inspector PATCH] Tests for the connect-zone VersionBadge.
   The component is pure: no fetching, no session-id lookup. The snapshot
   arrives as a prop from useConnection's handshake capture, so these tests
   cover the rendering decisions only. */
import { render, screen, fireEvent } from "@testing-library/react";
import VersionBadge from "../VersionBadge";
import { InitializeSnapshot } from "@/lib/protocolRevision";

function snapshot(
  overrides: Partial<InitializeSnapshot> = {},
): InitializeSnapshot {
  return {
    clientRequestedVersion: "2025-11-25",
    negotiatedVersion: "2025-11-25",
    serverName: "demo-server",
    serverVersion: "1.2.3",
    capabilities: { tools: { listChanged: true }, logging: {} },
    ...overrides,
  };
}

function badge(): HTMLElement {
  return screen.getByTestId("version-badge");
}

describe("VersionBadge", () => {
  it("renders a green OK badge with the negotiated revision", () => {
    render(<VersionBadge snapshot={snapshot()} />);

    expect(badge().textContent).toContain("2025-11-25");
    expect(badge().getAttribute("data-severity")).toBe("OK");
    expect(badge().className).toContain("bg-green-100");
  });

  it("renders an amber DOWNGRADE badge when the server negotiated an older revision", () => {
    render(
      <VersionBadge
        snapshot={snapshot({
          clientRequestedVersion: "2026-07-28",
          negotiatedVersion: "2025-11-25",
        })}
      />,
    );

    expect(badge().getAttribute("data-severity")).toBe("DOWNGRADE");
    expect(badge().className).toContain("bg-amber-100");
    expect(badge().textContent).toContain("2025-11-25");
  });

  it("renders a red INCOMPATIBLE badge with the affected methods", () => {
    render(
      <VersionBadge
        snapshot={snapshot({
          clientRequestedVersion: "2025-11-25",
          negotiatedVersion: "2026-07-28",
        })}
      />,
    );

    expect(badge().getAttribute("data-severity")).toBe("INCOMPATIBLE");
    expect(badge().className).toContain("bg-red-100");

    // Expand the details: the notice names both revisions and the methods
    // the newer server removed.
    fireEvent.click(badge().querySelector("[class*=cursor-pointer]")!);
    expect(screen.getAllByText(/Downgraded|Incompatible/i).length).toBeGreaterThan(0);
    expect(screen.getAllByText(/ping/).length).toBeGreaterThan(0);
    expect(screen.getAllByText(/MethodNotFound/).length).toBeGreaterThan(0);
  });

  it("renders a blue UNKNOWN badge for revisions the matrix does not recognise", () => {
    render(
      <VersionBadge
        snapshot={snapshot({
          clientRequestedVersion: "not-a-date",
          negotiatedVersion: "2025-11-25",
        })}
      />,
    );

    expect(badge().getAttribute("data-severity")).toBe("UNKNOWN");
    expect(badge().className).toContain("bg-blue-100");
  });

  describe("without a snapshot", () => {
    it("renders an unknown badge, never a green one", () => {
      render(<VersionBadge snapshot={null} />);

      // The regression this guards against: a missing snapshot used to fall
      // through to the green "OK" styling. It must read as unknown instead.
      expect(badge().getAttribute("data-severity")).toBe("NO_SNAPSHOT");
      expect(badge().textContent).toContain("unknown");
      expect(badge().className).not.toContain("bg-green-100");
      expect(badge().className).toContain("bg-gray-100");
    });

    it("explains the missing handshake when expanded", () => {
      render(<VersionBadge snapshot={null} />);

      fireEvent.click(badge().querySelector("[class*=cursor-pointer]")!);
      expect(
        screen.getByText(/initialize handshake was not observed/i),
      ).toBeTruthy();
    });
  });

  it("shows server info, both revisions and capabilities when expanded", () => {
    render(<VersionBadge snapshot={snapshot()} />);

    fireEvent.click(badge().querySelector("[class*=cursor-pointer]")!);

    expect(screen.getByText("demo-server")).toBeTruthy();
    expect(screen.getByText("Version: 1.2.3")).toBeTruthy();
    expect(screen.getAllByText(/2025-11-25/).length).toBeGreaterThanOrEqual(2);
    expect(screen.getByText("tools")).toBeTruthy();
    expect(screen.getByText("logging")).toBeTruthy();
  });
});
