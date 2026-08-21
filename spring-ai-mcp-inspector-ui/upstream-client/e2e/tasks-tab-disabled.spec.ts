import { test, expect } from "@playwright/test";

// The demo-webmvc app serves the inspector UI plus a real MCP server that does
// NOT advertise the MCP Tasks capability (SEP-1686), so the Tasks tab is
// rendered disabled and — per the fix — wrapped in a tooltip explaining why,
// with a link to the proposal.
//
// Run the demo first, e.g.:
//   java -jar spring-ai-mcp-inspector-demo-webmvc/target/*-exec.jar \
//     --server.port=18080 --spring.ai.mcp.server.protocol=SSE \
//     --spring.ai.mcp.inspector.auth-enabled=false
// then:
//   npx playwright test e2e/tasks-tab-disabled.spec.ts --config=playwright.demo.config.ts
const appUrl = process.env.INSPECTOR_DEMO_URL ?? "http://localhost:18080/";

test.describe("Disabled Tasks tab (server without tasks capability)", () => {
  test("connects and shows a disabled Tasks tab with an explaining tooltip and SEP-1686 docs link", async ({
    page,
  }) => {
    // Open the inspector UI served by the demo app and connect.
    await page.goto(`${appUrl}mcp-inspector/index.html`);

    const connect = page.getByRole("button", { name: "Connect", exact: true });
    await expect(connect).toBeVisible({ timeout: 15_000 });
    await connect.click();

    // The [data-testid=connect-button] (Restart/Reconnect) only mounts in the
    // post-connect branch, so its presence proves the connection succeeded.
    await expect(page.locator("[data-testid=connect-button]")).toBeVisible({
      timeout: 40_000,
    });

    // The Radix tablist appears once the client resolves the server's
    // capabilities after connect.
    await expect(page.locator("[role=tablist]")).toBeVisible({
      timeout: 15_000,
    });

    // The demo server does not advertise the tasks capability, so the Tasks
    // tab must be disabled.
    const tasksTab = page.locator("[role=tab][id$='-trigger-tasks']");
    await expect(tasksTab).toBeVisible();
    await expect(tasksTab).toBeDisabled();

    // No tooltip before hover.
    await expect(
      page.getByText(/does not support MCP Tasks/i),
    ).toHaveCount(0);

    // The disabled trigger has pointer-events:none (tabs.tsx), so the tooltip
    // attaches to the live wrapper span around it. Hover that span.
    await tasksTab.locator("xpath=..").hover();

    // The tooltip explains the reason and links to the SEP-1686 proposal.
    const tooltip = page
      .getByText(/This server does not support MCP Tasks/i)
      .first();
    await expect(tooltip).toBeVisible({ timeout: 5_000 });

    const docsLink = page
      .getByRole("link", { name: "MCP Tasks documentation" })
      .first();
    await expect(docsLink).toBeVisible();
    await expect(docsLink).toHaveAttribute(
      "href",
      "https://modelcontextprotocol.io/seps/1686-tasks",
    );
  });
});
