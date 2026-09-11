// [spring-ai-mcp-inspector PATCH] ui-app-detection: tests for
// hasUIMetadata (issue #183).

import { describe, it, expect, jest, beforeEach } from "@jest/globals";
import { Tool } from "@modelcontextprotocol/sdk/types.js";
import { getToolUiResourceUri } from "@modelcontextprotocol/ext-apps/app-bridge";

// Mock must be defined before imports from the module under test
jest.mock("@modelcontextprotocol/ext-apps/app-bridge", () => {
  const actual =
    jest.requireActual<typeof import("@modelcontextprotocol/ext-apps/app-bridge")>(
      "@modelcontextprotocol/ext-apps/app-bridge",
    );

  // Wrap getToolUiResourceUri to simulate throws on malformed values
  const original = actual.getToolUiResourceUri;
  const mockGetToolUiResourceUri: typeof getToolUiResourceUri = (
    tool: Tool,
  ) => {
    const meta = (
      tool as Tool & { _meta?: { ui?: { resourceUri?: unknown } } }
    )._meta;

    // Simulate throw for unsupported types (number, object, null)
    if (meta?.ui?.resourceUri !== undefined) {
      const uri = meta.ui.resourceUri;
      if (
        uri === null ||
        typeof uri === "number" ||
        (typeof uri === "object" && !Array.isArray(uri))
      ) {
        throw new Error(`invalid resourceUri type: ${typeof uri}`);
      }
    }
    if (meta?.["ui/resourceUri"] !== undefined) {
      const uri = meta["ui/resourceUri"];
      if (
        uri === null ||
        typeof uri === "number" ||
        (typeof uri === "object" && !Array.isArray(uri))
      ) {
        throw new Error(`invalid resourceUri type: ${typeof uri}`);
      }
    }

    return original(tool);
  };

  return {
    getToolUiResourceUri: mockGetToolUiResourceUri,
  };
});

import { hasUIMetadata, resetHasUIMetadataWarnings } from "@/utils/uiMetadataGuard";

describe("hasUIMetadata", () => {
  beforeEach(() => {
    resetHasUIMetadataWarnings();
  });

  it("returns true for a tool with _meta.ui.resourceUri (nested)", () => {
    const tool = {
      name: "weatherApp",
      inputSchema: { type: "object" as const, properties: {} },
      _meta: { ui: { resourceUri: "ui://weather-app" } },
    } as Tool;
    expect(hasUIMetadata(tool)).toBe(true);
  });

  it("returns true for a tool with deprecated flat key _meta['ui/resourceUri']", () => {
    const tool = {
      name: "legacyApp",
      inputSchema: { type: "object" as const, properties: {} },
      _meta: { "ui/resourceUri": "ui://legacy-app" },
    } as Tool;
    expect(hasUIMetadata(tool)).toBe(true);
  });

  it("returns false when _meta is absent", () => {
    const tool = {
      name: "plain",
      inputSchema: { type: "object" as const, properties: {} },
    } as Tool;
    expect(hasUIMetadata(tool)).toBe(false);
  });

  it("returns false when _meta.ui is absent", () => {
    const tool = {
      name: "plain",
      inputSchema: { type: "object" as const, properties: {} },
      _meta: {},
    } as Tool;
    expect(hasUIMetadata(tool)).toBe(false);
  });

  it("returns false when _meta.ui.resourceUri is absent", () => {
    const tool = {
      name: "plain",
      inputSchema: { type: "object" as const, properties: {} },
      _meta: { ui: {} },
    } as Tool;
    expect(hasUIMetadata(tool)).toBe(false);
  });

  it("returns false when resourceUri is an empty string", () => {
    const tool = {
      name: "empty",
      inputSchema: { type: "object" as const, properties: {} },
      _meta: { ui: { resourceUri: "" } },
    } as Tool;
    expect(hasUIMetadata(tool)).toBe(false);
  });

  it("returns false when resourceUri does not start with ui://", () => {
    const tool = {
      name: "bad",
      inputSchema: { type: "object" as const, properties: {} },
      _meta: { ui: { resourceUri: "https://evil.example.com" } },
    } as Tool;
    expect(hasUIMetadata(tool)).toBe(false);
  });

  it("returns false when resourceUri is a number (malformed) without throwing", () => {
    const tool = {
      name: "bad",
      inputSchema: { type: "object" as const, properties: {} },
      _meta: { ui: { resourceUri: 42 } },
    } as unknown as Tool;
    expect(hasUIMetadata(tool)).toBe(false);
  });

  it("prefers nested _meta.ui.resourceUri over deprecated flat key", () => {
    const tool = {
      name: "both",
      inputSchema: { type: "object" as const, properties: {} },
      _meta: {
        ui: { resourceUri: "ui://nested" },
        "ui/resourceUri": "ui://flat",
      },
    } as Tool;
    // getToolUiResourceUri reads nested first
    expect(hasUIMetadata(tool)).toBe(true);
  });

  it("returns false when _meta.ui.resourceUri is null (malformed) without throwing", () => {
    const tool = {
      name: "null-uri",
      inputSchema: { type: "object" as const, properties: {} },
      _meta: { ui: { resourceUri: null } },
    } as unknown as Tool;
    expect(hasUIMetadata(tool)).toBe(false);
  });

  it("returns false when _meta.ui.resourceUri is an object (malformed) without throwing", () => {
    const tool = {
      name: "obj-uri",
      inputSchema: { type: "object" as const, properties: {} },
      _meta: { ui: { resourceUri: { uri: "ui://nested" } } },
    } as unknown as Tool;
    expect(hasUIMetadata(tool)).toBe(false);
  });
});