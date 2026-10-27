/* [spring-ai-mcp-inspector PATCH] Sandbox proxy page origin-trust tests (t_81f6d85b):
   the page must ignore a parent-supplied inner-iframe sandbox value and must drop
   host messages whose event.origin differs from the page's own origin. */

import { readFileSync } from "fs";
import path from "path";

const PAGE_HTML = readFileSync(
  path.join(__dirname, "..", "..", "..", "public", "sandbox-proxy.html"),
  "utf-8",
);

function extractPageScript(html: string): string {
  const match = html.match(/<script>([\s\S]*)<\/script>/);
  if (!match) {
    throw new Error("sandbox-proxy.html: no inline <script> block found");
  }
  return match[1];
}

/**
 * Load the sandbox proxy page script into the current jsdom document so its
 * window message handler can be exercised. The page's own <div id="container">
 * is recreated because the script looks it up at load time.
 */
function loadPage(): void {
  document.body.innerHTML = '<div id="container"></div>';
  window.location.href = "http://localhost/";
  // The script is an IIFE registering a window "message" listener; indirect
  // eval runs it against the jsdom globals.
  (0, eval)(extractPageScript(PAGE_HTML));
}

function postHostMessage(origin: string, data: unknown): void {
  const event = new MessageEvent("message", {
    data,
    origin,
    source: window,
  });
  window.dispatchEvent(event);
}

function innerIframeSandbox(): string | null {
  const iframe = document.querySelector("#container iframe");
  return iframe ? iframe.getAttribute("sandbox") : null;
}

const RESOURCE_READY = "ui/notifications/sandbox-resource-ready";

beforeEach(() => {
  loadPage();
});

describe("sandbox proxy page inner iframe sandbox", () => {
  it("applies the hardcoded allow-scripts token set to the inner iframe", () => {
    postHostMessage(window.location.origin, {
      jsonrpc: "2.0",
      method: RESOURCE_READY,
      params: { html: "<p>hello</p>" },
    });

    expect(innerIframeSandbox()).toBe("allow-scripts");
  });

  it("ignores a parent-supplied sandbox containing allow-same-origin", () => {
    postHostMessage(window.location.origin, {
      jsonrpc: "2.0",
      method: RESOURCE_READY,
      params: {
        html: "<p>evil</p>",
        sandbox: "allow-scripts allow-same-origin",
      },
    });

    const sandbox = innerIframeSandbox() ?? "";
    expect(sandbox).not.toContain("allow-same-origin");
    expect(sandbox).toBe("allow-scripts");
  });

  it("ignores any parent-supplied sandbox value, benign or not", () => {
    postHostMessage(window.location.origin, {
      jsonrpc: "2.0",
      method: RESOURCE_READY,
      params: { html: "<p>x</p>", sandbox: "allow-forms allow-popups" },
    });

    expect(innerIframeSandbox()).toBe("allow-scripts");
  });
});

describe("sandbox proxy page host message origin check", () => {
  it("drops sandbox-resource-ready from a cross-origin framing parent", () => {
    postHostMessage("https://evil.example", {
      jsonrpc: "2.0",
      method: RESOURCE_READY,
      params: { html: "<p>evil</p>", sandbox: "allow-scripts allow-same-origin" },
    });

    expect(document.querySelector("#container iframe")).toBeNull();
  });

  it("accepts sandbox-resource-ready from the same-origin host", () => {
    postHostMessage(window.location.origin, {
      jsonrpc: "2.0",
      method: RESOURCE_READY,
      params: { html: "<p>ok</p>" },
    });

    expect(document.querySelector("#container iframe")).not.toBeNull();
  });
});
