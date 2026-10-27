/* [spring-ai-mcp-inspector PATCH] Sandbox proxy page tests (t_81f6d85b,
   t_5f7281e4): the page must ignore a parent-supplied inner-iframe sandbox value,
   must drop host messages whose event.origin differs from the page's own origin,
   and must give the srcdoc guest ONE effective CSP policy (single connect-src with
   the App domains, object-src 'none', no duplicated directives). */

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

function innerIframeSrcdoc(): string | null {
  const iframe = document.querySelector("#container iframe");
  return iframe ? iframe.getAttribute("srcdoc") : null;
}

function guestMetaCsp(srcdoc: string | null): Map<string, string> {
  expect(srcdoc).not.toBeNull();
  const match = srcdoc!.match(
    /<meta http-equiv="Content-Security-Policy" content="([^"]*)">/
  );
  if (!match) {
    throw new Error("srcdoc: no CSP meta tag found");
  }
  const decoded = match[1].replace(/&quot;/g, '"');
  // CSP3 parsing: the FIRST occurrence of a repeated directive wins.
  const directives = new Map<string, string>();
  for (const token of decoded.split(";")) {
    const trimmed = token.trim();
    if (!trimmed) continue;
    const [name, ...rest] = trimmed.split(/\s+/);
    if (!directives.has(name)) directives.set(name, rest.join(" "));
  }
  return directives;
}

describe("sandbox proxy page guest CSP", () => {
  it("gives the srcdoc guest one effective connect-src with the App domains", () => {
    postHostMessage(window.location.origin, {
      jsonrpc: "2.0",
      method: RESOURCE_READY,
      params: {
        html: "<p>hello</p>",
        csp: { connectDomains: ["https://api.example.com"] },
      },
    });

    const effective = guestMetaCsp(innerIframeSrcdoc());
    expect(effective.get("connect-src")).toBe("https://api.example.com");
    expect(effective.get("default-src")).toBe("'none'");
  });

  it("adds object-src 'none' to the guest policy (SEP-1865 draft, item 5)", () => {
    postHostMessage(window.location.origin, {
      jsonrpc: "2.0",
      method: RESOURCE_READY,
      params: {
        html: "<p>hello</p>",
        csp: { connectDomains: ["https://api.example.com"] },
      },
    });

    expect(guestMetaCsp(innerIframeSrcdoc()).get("object-src")).toBe("'none'");
  });

  it("emits no directive twice in the guest policy", () => {
    postHostMessage(window.location.origin, {
      jsonrpc: "2.0",
      method: RESOURCE_READY,
      params: {
        html: "<p>hello</p>",
        csp: {
          connectDomains: ["https://api.example.com"],
          resourceDomains: ["https://cdn.example.com"],
          frameDomains: ["https://frames.example.com"],
          baseUriDomains: ["https://base.example.com"],
        },
      },
    });

    const srcdoc = innerIframeSrcdoc()!;
    const decoded = srcdoc
      .match(/<meta http-equiv="Content-Security-Policy" content="([^"]*)">/)![1]
      .replace(/&quot;/g, '"');
    const counts = new Map<string, number>();
    for (const token of decoded.split(";")) {
      const trimmed = token.trim();
      if (!trimmed) continue;
      const name = trimmed.split(/\s+/)[0];
      counts.set(name, (counts.get(name) ?? 0) + 1);
    }
    for (const [name, count] of counts) {
      expect(count).toBe(1);
    }
  });

  it("falls back to the all-'none' baseline for a guest without csp", () => {
    postHostMessage(window.location.origin, {
      jsonrpc: "2.0",
      method: RESOURCE_READY,
      params: { html: "<p>hello</p>" },
    });

    const effective = guestMetaCsp(innerIframeSrcdoc());
    expect(effective.get("connect-src")).toBe("'none'");
    expect(effective.get("object-src")).toBe("'none'");
  });
});
