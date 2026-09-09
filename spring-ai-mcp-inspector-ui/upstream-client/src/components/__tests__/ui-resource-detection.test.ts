import { describe, it, expect } from "@jest/globals";
import { Tool } from "@modelcontextprotocol/sdk/types.js";
import { hasUIMetadata } from "../AppsTab";

describe("hasUIMetadata", () => {
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

  it("throws when resourceUri is an empty string", () => {
    const tool = {
      name: "empty",
      inputSchema: { type: "object" as const, properties: {} },
      _meta: { ui: { resourceUri: "" } },
    } as Tool;
    // upstream throws on empty string (treats it as invalid URI)
    expect(() => hasUIMetadata(tool)).toThrow(/Invalid UI resource URI/);
  });

  it("throws when resourceUri does not start with ui://", () => {
    const tool = {
      name: "bad",
      inputSchema: { type: "object" as const, properties: {} },
      _meta: { ui: { resourceUri: "https://evil.example.com" } },
    } as Tool;
    expect(() => hasUIMetadata(tool)).toThrow(/Invalid UI resource URI/);
  });

  it("throws when resourceUri is a number", () => {
    const tool = {
      name: "bad",
      inputSchema: { type: "object" as const, properties: {} },
      _meta: { ui: { resourceUri: 42 } },
    } as unknown as Tool;
    expect(() => hasUIMetadata(tool)).toThrow(/Invalid UI resource URI/);
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

  it("throws when _meta.ui.resourceUri is null", () => {
    const tool = {
      name: "null-uri",
      inputSchema: { type: "object" as const, properties: {} },
      _meta: { ui: { resourceUri: null } },
    } as unknown as Tool;
    // upstream treats null as an invalid URI (throws), not as absent
    expect(() => hasUIMetadata(tool)).toThrow(/Invalid UI resource URI/);
  });
});
