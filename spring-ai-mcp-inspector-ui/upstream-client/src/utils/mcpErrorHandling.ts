// [spring-ai-mcp-inspector PATCH] Keep fire-and-forget MCP calls from
// becoming unhandledrejection (see NOTICE.d/mcp-error-handling.txt, issue
// #276). sendMCPRequest() in App.tsx already records the failure under the
// tab's error key (rendered as the destructive alert) before rethrowing,
// and the tab props that trigger requests are typed `() => void`, so the
// promise they receive has no reader: its rejection used to surface again
// in the browser console as an unhandledrejection. swallowLoggedMcpError()
// attaches the missing .catch for those call sites.
export function swallowLoggedMcpError(request: Promise<unknown>): void {
  request.catch(() => {
    // The error is already surfaced through errors[tabKey]; nothing to do.
  });
}
