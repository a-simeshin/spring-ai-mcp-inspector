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

  it("pins the mismatch notice on DOWNGRADE: names both revisions, no method list", () => {
    render(
      <VersionBadge
        snapshot={snapshot({
          clientRequestedVersion: "2026-07-28",
          negotiatedVersion: "2025-11-25",
        })}
      />,
    );

    fireEvent.click(badge().querySelector("[class*=cursor-pointer]")!);

    // Pinned to the notice element itself, not to strings the Compatibility
    // summary also produces: deleting the whole notice block must turn this
    // test red (issue #79 AC #2). A downgrade names both revisions and
    // carries no method list: the negotiated older revision still offers
    // every method the client requested.
    const notice = screen.getByTestId("version-mismatch-notice");
    expect(notice.textContent).toContain("Downgraded");
    expect(notice.textContent).toContain("2026-07-28");
    expect(notice.textContent).toContain("2025-11-25");
    expect(notice.querySelector("ul")).toBeNull();
  });

  it("pins the legacy-server downgrade seen on the wire: amber notice naming both revisions", () => {
    // The live old-server/new-client case (issue #79 AC #2): the inspector
    // client (2025-11-25) negotiating with a legacy server that answers
    // 2024-11-05, exactly what the demo's SSE flavour does.
    render(
      <VersionBadge
        snapshot={snapshot({
          clientRequestedVersion: "2025-11-25",
          negotiatedVersion: "2024-11-05",
        })}
      />,
    );

    expect(badge().getAttribute("data-severity")).toBe("DOWNGRADE");
    expect(badge().className).toContain("bg-amber-100");

    fireEvent.click(badge().querySelector("[class*=cursor-pointer]")!);

    const notice = screen.getByTestId("version-mismatch-notice");
    expect(notice.textContent).toContain("2025-11-25");
    expect(notice.textContent).toContain("2024-11-05");
    expect(notice.querySelector("ul")).toBeNull();
  });

  it("pins the mismatch notice on INCOMPATIBLE: names both revisions and the affected methods", () => {
    render(
      <VersionBadge
        snapshot={snapshot({
          clientRequestedVersion: "2025-11-25",
          negotiatedVersion: "2026-07-28",
        })}
      />,
    );

    fireEvent.click(badge().querySelector("[class*=cursor-pointer]")!);

    const notice = screen.getByTestId("version-mismatch-notice");
    expect(notice.textContent).toContain("Incompatible");
    expect(notice.textContent).toContain("2025-11-25");
    expect(notice.textContent).toContain("2026-07-28");
    expect(notice.textContent).toContain("MethodNotFound");
    const methods = Array.from(notice.querySelectorAll("li")).map(
      (li) => li.textContent,
    );
    expect(methods.length).toBeGreaterThan(0);
    expect(methods).toContain("ping");
    expect(methods).toContain("initialize");
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

  // [spring-ai-mcp-inspector PATCH] Keyboard operability (owner follow-up to
  // PR #416): the toggle chevron was a bare svg, so the details panel and the
  // mismatch notice were unreachable without a pointer. Pinned here: button
  // semantics on the svg, Enter/Space toggling, aria-expanded synced with the
  // panel on both the toggle and the badge container.
  describe("keyboard operability", () => {
    const toggle = (): HTMLElement =>
      screen.getByTestId("version-badge-toggle");

    it("exposes the chevron as a focusable button and toggles on Enter/Space", () => {
      render(<VersionBadge snapshot={snapshot()} />);

      expect(toggle().getAttribute("tabindex")).toBe("0");
      expect(toggle().getAttribute("role")).toBe("button");
      expect(toggle().getAttribute("aria-expanded")).toBe("false");
      expect(badge().getAttribute("aria-expanded")).toBe("false");
      expect(screen.queryByText("demo-server")).toBeNull();

      fireEvent.keyDown(toggle(), { key: "Enter" });

      expect(screen.getByText("demo-server")).toBeTruthy();
      expect(toggle().getAttribute("aria-expanded")).toBe("true");
      expect(badge().getAttribute("aria-expanded")).toBe("true");

      fireEvent.keyDown(toggle(), { key: " " });

      expect(screen.queryByText("demo-server")).toBeNull();
      expect(toggle().getAttribute("aria-expanded")).toBe("false");
      expect(badge().getAttribute("aria-expanded")).toBe("false");
    });

    it("reaches the mismatch notice from the keyboard on the live downgrade case", () => {
      render(
        <VersionBadge
          snapshot={snapshot({
            clientRequestedVersion: "2025-11-25",
            negotiatedVersion: "2024-11-05",
          })}
        />,
      );

      fireEvent.keyDown(toggle(), { key: "Enter" });

      const notice = screen.getByTestId("version-mismatch-notice");
      expect(notice.textContent).toContain("2025-11-25");
      expect(notice.textContent).toContain("2024-11-05");
    });

    it("leaves the panel closed for keys that are not Enter or Space", () => {
      render(<VersionBadge snapshot={snapshot()} />);

      fireEvent.keyDown(toggle(), { key: "Tab" });
      fireEvent.keyDown(toggle(), { key: "Escape" });

      expect(screen.queryByText("demo-server")).toBeNull();
    });
  });
});
