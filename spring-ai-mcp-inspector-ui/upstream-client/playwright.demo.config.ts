import { defineConfig } from "@playwright/test";

// Runner for the demo-targeted Tasks-tab regression (e2e/tasks-tab-disabled.spec.ts).
// Unlike the default playwright.config.ts (which boots the vite dev server on :6274),
// this points Playwright at a packaged demo-webmvc app that serves the inspector UI
// AND a real MCP server that does not advertise the tasks capability (SEP-1686).
// Start the demo first, e.g.:
//   java -jar spring-ai-mcp-inspector-demo-webmvc/target/*-exec.jar \
//     --server.port=18080 --spring.ai.mcp.server.protocol=SSE \
//     --spring.ai.mcp.inspector.auth-enabled=false
// then run ONLY this spec:
//   npx playwright test e2e/tasks-tab-disabled.spec.ts --config=playwright.demo.config.ts
export default defineConfig({
  testDir: "./e2e",
  timeout: 120_000,
  workers: 1,
  fullyParallel: false,
  use: { baseURL: "http://localhost:18080/" },
  projects: [{ name: "chromium" }],
  reporter: [["line"]],
});
