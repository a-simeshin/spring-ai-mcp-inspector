/**
 * [spring-ai-mcp-inspector PATCH] Tests for the toast viewport offset
 * (issue #267): toasts stack bottom-right and used to cover the server
 * notifications pane. When the pane is on screen the viewport must raise
 * itself above the pane's top edge; without the pane the default position
 * (offset unset) is kept.
 */
import { act, render } from "@testing-library/react";
import "@testing-library/jest-dom";
import { Toaster } from "../ui/toaster";

function paneRect(top: number, height: number): DOMRect {
  return {
    top,
    height,
    bottom: top + height,
    left: 0,
    right: 1280,
    width: 1280,
    x: 0,
    y: top,
    toJSON: () => ({}),
  } as DOMRect;
}

function mountPane(top: number, height: number): HTMLElement {
  const pane = document.createElement("div");
  pane.setAttribute("data-testid", "notifications-pane");
  pane.getBoundingClientRect = () => paneRect(top, height);
  document.body.appendChild(pane);
  return pane;
}

function viewportStyle(): CSSStyleDeclaration | undefined {
  const viewport = document.querySelector("ol");
  return viewport?.style;
}

describe("Toaster viewport offset (issue #267)", () => {
  afterEach(() => {
    document.body.innerHTML = "";
  });

  it("raises the toast viewport above the notifications pane", () => {
    window.innerHeight = 800;
    mountPane(600, 200);

    render(<Toaster />);
    act(() => {
      window.dispatchEvent(new Event("resize"));
    });

    // 800 - 600 + 8px gap = 208px above the window bottom.
    expect(viewportStyle()?.getPropertyValue("--toast-viewport-offset")).toBe(
      "208px",
    );
  });

  it("keeps the default offset when the pane is absent", () => {
    render(<Toaster />);
    act(() => {
      window.dispatchEvent(new Event("resize"));
    });
    expect(viewportStyle()?.getPropertyValue("--toast-viewport-offset")).toBe(
      "",
    );
  });

  it("ignores a hidden (zero-height) pane", () => {
    window.innerHeight = 800;
    mountPane(0, 0);

    render(<Toaster />);
    act(() => {
      window.dispatchEvent(new Event("resize"));
    });
    expect(viewportStyle()?.getPropertyValue("--toast-viewport-offset")).toBe(
      "",
    );
  });
});
