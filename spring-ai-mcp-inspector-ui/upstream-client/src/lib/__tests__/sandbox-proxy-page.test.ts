/* [spring-ai-mcp-inspector PATCH] Sandbox proxy page tests (t_81f6d85b,
   t_5f7281e4, t_e6c2a15d): the page must ignore a parent-supplied inner-iframe
   sandbox value, must drop host messages whose event.origin differs from the
   page's own origin, must give the srcdoc guest ONE effective CSP policy (single
   connect-src with the App domains, object-src 'none', no duplicated directives),
   must validate App domains as strict host-source expressions, must replace (not
   stack) the inner iframe, and must relay postMessage to exact origins. This is
   the single home of the TypeScript CSP serializer coverage: the page's inline
   serializer is the only production TS copy (csp-serializer.ts was deleted as a
   test-only duplicate). */

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
    for (const count of counts.values()) {
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

describe("sandbox proxy page guest CSP domain validation", () => {
  it("drops a domain containing a policy separator from the guest policy", () => {
    postHostMessage(window.location.origin, {
      jsonrpc: "2.0",
      method: RESOURCE_READY,
      params: {
        html: "<p>x</p>",
        csp: {
          connectDomains: [
            "https://api.example.com",
            "https://evil.com; script-src 'unsafe-eval'",
          ],
        },
      },
    });

    const effective = guestMetaCsp(innerIframeSrcdoc());
    expect(effective.get("connect-src")).toBe("https://api.example.com");
  });

  it("drops a domain containing whitespace and a javascript: source", () => {
    postHostMessage(window.location.origin, {
      jsonrpc: "2.0",
      method: RESOURCE_READY,
      params: {
        html: "<p>x</p>",
        csp: {
          connectDomains: ["https://good.example.com"],
          resourceDomains: ["https://cdn.example.com javascript:alert(1)"],
          frameDomains: ["javascript:alert(1)"],
        },
      },
    });

    const effective = guestMetaCsp(innerIframeSrcdoc());
    expect(effective.get("connect-src")).toBe("https://good.example.com");
    // resourceDomains had no valid entry: baseline 'none' applies
    expect(effective.get("img-src")).toBe("'none'");
    expect(effective.get("frame-src")).toBe("'none'");
  });

  it("rejects 'unsafe-eval' and other quoted keywords as domains", () => {
    postHostMessage(window.location.origin, {
      jsonrpc: "2.0",
      method: RESOURCE_READY,
      params: {
        html: "<p>x</p>",
        csp: { connectDomains: ["'unsafe-eval'", "https://api.example.com"] },
      },
    });

    const effective = guestMetaCsp(innerIframeSrcdoc());
    expect(effective.get("connect-src")).toBe("https://api.example.com");
  });

  it("drops every domain of an all-invalid list, keeping the baseline", () => {
    postHostMessage(window.location.origin, {
      jsonrpc: "2.0",
      method: RESOURCE_READY,
      params: {
        html: "<p>x</p>",
        csp: { connectDomains: ["data:text/html,x", "*, 'unsafe-eval'"] },
      },
    });

    expect(guestMetaCsp(innerIframeSrcdoc()).get("connect-src")).toBe("'none'");
  });

  it("accepts scheme, port, path, localhost and IP-literal host sources", () => {
    postHostMessage(window.location.origin, {
      jsonrpc: "2.0",
      method: RESOURCE_READY,
      params: {
        html: "<p>x</p>",
        csp: {
          connectDomains: [
            "api.example.com",
            "https://api.example.com:8443",
            "http://api.example.com/v1/api",
            "localhost",
            "127.0.0.1",
            "[::1]",
          ],
        },
      },
    });

    const effective = guestMetaCsp(innerIframeSrcdoc());
    expect(effective.get("connect-src")).toBe(
      "api.example.com https://api.example.com:8443 http://api.example.com/v1/api localhost 127.0.0.1 [::1]",
    );
  });

  it("maps resourceDomains into the same img/script/style/font/media directives", () => {
    postHostMessage(window.location.origin, {
      jsonrpc: "2.0",
      method: RESOURCE_READY,
      params: {
        html: "<p>x</p>",
        csp: {
          resourceDomains: ["https://cdn.example.com"],
          frameDomains: ["https://frames.example.com"],
          baseUriDomains: ["https://base.example.com"],
        },
      },
    });

    const effective = guestMetaCsp(innerIframeSrcdoc());
    expect(effective.get("img-src")).toBe("https://cdn.example.com");
    expect(effective.get("script-src")).toBe(
      "'unsafe-inline' https://cdn.example.com",
    );
    expect(effective.get("style-src")).toBe(
      "'unsafe-inline' https://cdn.example.com",
    );
    expect(effective.get("font-src")).toBe("https://cdn.example.com");
    expect(effective.get("media-src")).toBe("https://cdn.example.com");
    expect(effective.get("frame-src")).toBe("https://frames.example.com");
    expect(effective.get("base-uri")).toBe("https://base.example.com");
  });

  it("produces the baseline for an all-'none' input with multiple App domains", () => {
    postHostMessage(window.location.origin, {
      jsonrpc: "2.0",
      method: RESOURCE_READY,
      params: {
        html: "<p>x</p>",
        csp: {
          connectDomains: ["https://api.example.com", "https://ws.example.com"],
        },
      },
    });

    const effective = guestMetaCsp(innerIframeSrcdoc());
    expect(effective.get("connect-src")).toBe(
      "https://api.example.com https://ws.example.com",
    );
    expect(effective.get("default-src")).toBe("'none'");
  });
});

describe("sandbox proxy page inner iframe replacement", () => {
  it("replaces the previous inner iframe instead of stacking another one", () => {
    postHostMessage(window.location.origin, {
      jsonrpc: "2.0",
      method: RESOURCE_READY,
      params: { html: "<p>first</p>" },
    });
    postHostMessage(window.location.origin, {
      jsonrpc: "2.0",
      method: RESOURCE_READY,
      params: { html: "<p>second</p>" },
    });

    const iframes = document.querySelectorAll("#container iframe");
    expect(iframes.length).toBe(1);
    expect(innerIframeSrcdoc()).toContain("second");
  });

  it("drops non-object csp values to the baseline without throwing", () => {
    postHostMessage(window.location.origin, {
      jsonrpc: "2.0",
      method: RESOURCE_READY,
      params: { html: "<p>x</p>", csp: "not-json{" },
    });

    const effective = guestMetaCsp(innerIframeSrcdoc());
    expect(effective.get("connect-src")).toBe("'none'");
  });
});

describe("sandbox proxy page postMessage target origins", () => {
  it("sends sandbox-proxy-ready to the parent with location.origin, not '*'", () => {
    const calls: Array<{ data: unknown; origin: string }> = [];
    const parentPost = window.parent.postMessage.bind(window.parent);
    window.parent.postMessage = jest.fn((data: unknown, origin: string) => {
      calls.push({ data, origin });
      return parentPost(data, origin);
    }) as unknown as typeof window.parent.postMessage;

    loadPage();

    expect(calls.length).toBeGreaterThanOrEqual(1);
    for (const call of calls) {
      expect(call.origin).toBe(window.location.origin);
    }
    window.parent.postMessage = parentPost;
  });

  it("relays guest frames to the parent with location.origin", () => {
    postHostMessage(window.location.origin, {
      jsonrpc: "2.0",
      method: RESOURCE_READY,
      params: { html: "<p>x</p>" },
    });

    const inner = document.querySelector<HTMLIFrameElement>("#container iframe");
    expect(inner).not.toBeNull();

    const calls: Array<{ data: unknown; origin: string }> = [];
    const parentPost = window.parent.postMessage.bind(window.parent);
    window.parent.postMessage = jest.fn((data: unknown, origin: string) => {
      calls.push({ data, origin });
      return parentPost(data, origin);
    }) as unknown as typeof window.parent.postMessage;

    const guestEvent = new MessageEvent("message", {
      data: { jsonrpc: "2.0", method: "ui/initialize" },
      origin: "null",
      source: inner!.contentWindow,
    });
    window.dispatchEvent(guestEvent);

    expect(calls.length).toBe(1);
    expect(calls[0].origin).toBe(window.location.origin);
    window.parent.postMessage = parentPost;
  });

  it("keeps no dead forwardToHost path: a guest frame is relayed exactly once", () => {
    postHostMessage(window.location.origin, {
      jsonrpc: "2.0",
      method: RESOURCE_READY,
      params: { html: "<p>x</p>" },
    });

    const inner = document.querySelector<HTMLIFrameElement>("#container iframe");
    const calls: Array<unknown> = [];
    const parentPost = window.parent.postMessage.bind(window.parent);
    window.parent.postMessage = jest.fn((data: unknown) => {
      calls.push(data);
      return parentPost(data, window.location.origin);
    }) as unknown as typeof window.parent.postMessage;

    window.dispatchEvent(
      new MessageEvent("message", {
        data: { jsonrpc: "2.0", method: "ui/notifications/initialized" },
        origin: "null",
        source: inner!.contentWindow,
      }),
    );

    expect(calls.length).toBe(1);
    window.parent.postMessage = parentPost;
  });
});
