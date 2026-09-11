// [spring-ai-mcp-inspector PATCH] ui-app-detection: shared non-throwing
// boundary for getToolUiResourceUri (issue #183). The upstream helper throws
// on absent or malformed `_meta.ui.resourceUri` values, so every render/filter
// path goes through this guard instead. A malformed value from one tool must
// not crash the whole inspector UI.
//
// Bounded warning: one console.warn per unique tool name per session, so a
// chatty malicious server cannot flood the console.

import { Tool } from "@modelcontextprotocol/sdk/types.js";
import { getToolUiResourceUri } from "@modelcontextprotocol/ext-apps/app-bridge";

const warnedToolNames = new Set<string>();

export const hasUIMetadata = (tool: Tool): boolean => {
  try {
    return Boolean(getToolUiResourceUri(tool));
  } catch (error) {
    if (!warnedToolNames.has(tool.name)) {
      warnedToolNames.add(tool.name);
      console.warn(
        `[ui-app-detection] malformed _meta.ui.resourceUri on tool "${tool.name}":`,
        error instanceof Error ? error.message : String(error),
      );
    }
    return false;
  }
};

// Test-only escape hatch: resets the deduplication set so each test can
// observe the warning for its own malformed tool.
export const resetHasUIMetadataWarnings = (): void => {
  warnedToolNames.clear();
};