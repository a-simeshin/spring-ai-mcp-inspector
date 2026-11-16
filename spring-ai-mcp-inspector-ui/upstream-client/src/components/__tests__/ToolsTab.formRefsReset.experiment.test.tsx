// [spring-ai-mcp-inspector PATCH] Differential experiment for issue #294.
//
// This is NOT a regression test. The `formRefs.current = {}` reset in the
// selectedTool effect (src/components/ToolsTab.tsx) is an equivalent mutant
// on React 18.3.1: no component test can go red when the line is removed,
// because the refs map is equivalent with and without it (detached entries
// are nulled by React's detach-on-unmount, live entries are re-attached by
// the inline ref callbacks on the re-render that the same effect triggers
// via setParams, and checkValidationErrors skips null entries).
//
// The two scenarios below assert behavior that is identical with and without
// the reset. Reproducing the issue #294 evidence:
//   1. pure code:
//      npx jest src/components/__tests__/ToolsTab.formRefsReset.experiment.test.tsx
//      -> 2 scenarios green.
//   2. comment out `formRefs.current = {};` in ToolsTab.tsx and rerun:
//      -> still 2 scenarios green: the mutation is not detectable.
//   3. calibration: remove the `if (checkValidationErrors(true)) return;`
//      guard in the Run Tool onClick and rerun:
//      -> scenario A goes red, proving this harness does observe refs-driven
//         blocking; its blindness is specific to the reset line.
// Full analysis: issue #294 (a-simeshin/spring-ai-mcp-inspector).
import { render, screen, fireEvent, act } from "@testing-library/react";
import "@testing-library/jest-dom";
import { describe, it, jest, beforeEach } from "@jest/globals";
import ToolsTab from "../ToolsTab";
import { Tool } from "@modelcontextprotocol/sdk/types.js";
import { Tabs } from "../ui/tabs";
import { cacheToolOutputSchemas } from "../../utils/schemaUtils";

// An object property without properties/items renders DynamicJsonForm in
// JSON mode: a single textarea, same as toolWithJsonParams in ToolsTab.test.
const toolWithObjectCfg = (name: string): Tool => ({
  name,
  description: `${name} description`,
  inputSchema: {
    type: "object" as const,
    properties: {
      cfg: { type: "object" as const },
    },
    required: ["cfg"],
  },
});

const toolWithStringName = (name: string): Tool => ({
  name,
  description: `${name} description`,
  inputSchema: {
    type: "object" as const,
    properties: {
      name: { type: "string" as const },
    },
  },
});

describe("ToolsTab formRefs reset (issue #294 experiment)", () => {
  beforeEach(() => {
    cacheToolOutputSchemas([]);
  });

  const defaultProps = {
    tools: [] as Tool[],
    listTools: jest.fn(),
    clearTools: jest.fn(),
    callTool: jest.fn(async () => {}),
    selectedTool: null as Tool | null,
    setSelectedTool: jest.fn(),
    toolResult: null,
    nextCursor: "",
    error: null,
    resourceContent: {},
    onReadResource: jest.fn(),
    serverSupportsTaskRequests: true,
  };

  const renderToolsTab = (props = {}) => {
    return render(
      <Tabs defaultValue="tools">
        <ToolsTab {...defaultProps} {...props} />
      </Tabs>,
    );
  };

  const typeInvalidJson = () => {
    const textarea = screen.getAllByRole("textbox")[0];
    fireEvent.change(textarea, { target: { value: '{ "invalid": json }' } });
    return textarea;
  };

  const clickRun = async () => {
    const runButton = screen.getByRole("button", { name: /run tool/i });
    await act(async () => {
      fireEvent.click(runButton);
    });
  };

  it("scenario A (same field key, reused editor): Run stays blocked after switching tools, identically with and without the reset", async () => {
    const toolA = toolWithObjectCfg("toolA");
    const toolB = toolWithObjectCfg("toolB");
    const callTool = jest.fn(async () => {});
    const { rerender } = renderToolsTab({
      tools: [toolA, toolB],
      selectedTool: toolA,
      callTool,
    });

    typeInvalidJson();

    // Sanity on toolA: the Run onClick guard reads the refs map
    // (checkValidationErrors -> ref.validateJson) and blocks the call.
    // This is the only observable consumer of formRefs.
    await clickRun();
    expect(callTool).not.toHaveBeenCalled();

    // Switch to toolB. Both tools use the same field key "cfg", and the
    // property map is keyed by field name, so React reuses the
    // DynamicJsonForm instance. In JSON mode the editor keeps its raw text,
    // so Run on toolB is still blocked. The reset (present or not) is
    // followed by re-attach of the same instance, so both sides match.
    rerender(
      <Tabs defaultValue="tools">
        <ToolsTab
          {...defaultProps}
          tools={[toolA, toolB]}
          selectedTool={toolB}
          callTool={callTool}
        />
      </Tabs>,
    );

    await clickRun();
    expect(callTool).not.toHaveBeenCalled();
  });

  it("scenario B (different field keys, editor unmounts): Run proceeds after switching tools, identically with and without the reset", async () => {
    const toolA = toolWithObjectCfg("toolA");
    const toolB = toolWithStringName("toolB");
    const callTool = jest.fn(async () => {});
    const { rerender } = renderToolsTab({
      tools: [toolA, toolB],
      selectedTool: toolA,
      callTool,
    });

    typeInvalidJson();

    // Sanity on toolA, same channel as scenario A.
    await clickRun();
    expect(callTool).not.toHaveBeenCalled();

    // Switch to a tool whose only field has a different key and a different
    // widget: the object editor unmounts, and React detaches its ref callback
    // with null. A stale entry therefore cannot block toolB: with the reset,
    // the key is gone; without it, the entry is null and skipped by
    // `ref && ...`. Run proceeds on both sides.
    rerender(
      <Tabs defaultValue="tools">
        <ToolsTab
          {...defaultProps}
          tools={[toolA, toolB]}
          selectedTool={toolB}
          callTool={callTool}
        />
      </Tabs>,
    );

    await clickRun();
    expect(callTool).toHaveBeenCalledTimes(1);
    expect(callTool).toHaveBeenCalledWith(
      "toolB",
      {},
      undefined,
      false,
    );
  });
});
