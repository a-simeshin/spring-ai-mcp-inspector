// [spring-ai-mcp-inspector PATCH] ReplayDiffModal test: side-by-side diff dialog
// for Replay & diff action on failed Timeline rows.
import { render, screen } from "@testing-library/react";
import "@testing-library/jest-dom";
import ReplayDiffModal from "../ReplayDiffModal";
import type { ReplayDiffInfo } from "../TimelineTab";

const BASE_DIFF_INFO: ReplayDiffInfo = {
  toolName: "echo",
  args: { message: "hello" },
  originalResponse: {
    jsonrpc: "2.0",
    id: 100,
    result: {
      content: [{ type: "text", text: "something broke" }],
      isError: true,
    },
  },
  originalTimestamp: "2026-09-13T10:00:00.500Z",
  correlationId: "corr-replay-1",
};

describe("ReplayDiffModal", () => {
  it("renders nothing when diffInfo is null", () => {
    const { container } = render(
      <ReplayDiffModal
        open={true}
        onOpenChange={() => {}}
        diffInfo={null}
        newResponse={null}
        newTimestamp={null}
        loading={false}
      />,
    );
    expect(container).toBeEmptyDOMElement();
  });

  it("shows loading state while replay is in flight", () => {
    render(
      <ReplayDiffModal
        open={true}
        onOpenChange={() => {}}
        diffInfo={BASE_DIFF_INFO}
        newResponse={null}
        newTimestamp={null}
        loading={true}
      />,
    );
    expect(screen.getByText("Replaying...")).toBeInTheDocument();
  });

  it("shows side-by-side summary with recorded timestamp", () => {
    render(
      <ReplayDiffModal
        open={true}
        onOpenChange={() => {}}
        diffInfo={BASE_DIFF_INFO}
        newResponse={null}
        newTimestamp={null}
        loading={false}
      />,
    );
    expect(screen.getByText("Recorded (failed)")).toBeInTheDocument();
    expect(screen.getByText("2026-09-13T10:00:00.500Z")).toBeInTheDocument();
  });

  it("highlights changed keys between old and new response", () => {
    const newResponse = {
      jsonrpc: "2.0",
      id: 0,
      result: {
        content: [{ type: "text", text: "fixed now" }],
        isError: false,
      },
    };
    render(
      <ReplayDiffModal
        open={true}
        onOpenChange={() => {}}
        diffInfo={BASE_DIFF_INFO}
        newResponse={newResponse}
        newTimestamp="2026-09-13T10:05:00.000Z"
        loading={false}
      />,
    );
    expect(screen.getByText("content[0].text")).toBeInTheDocument();
    expect(screen.getByText("isError")).toBeInTheDocument();
    expect(screen.getAllByText("changed").length).toBeGreaterThanOrEqual(2);
  });

  it("shows identical-error message when both responses are the same error", () => {
    const newResponse = {
      jsonrpc: "2.0",
      id: 0,
      result: {
        content: [{ type: "text", text: "something broke" }],
        isError: true,
      },
    };
    render(
      <ReplayDiffModal
        open={true}
        onOpenChange={() => {}}
        diffInfo={BASE_DIFF_INFO}
        newResponse={newResponse}
        newTimestamp="2026-09-13T10:05:00.000Z"
        loading={false}
      />,
    );
    expect(
      screen.getByText(/The new call failed with the exact same error/),
    ).toBeInTheDocument();
  });

  it("shows no-differences message when responses are identical but not errors", () => {
    const okDiffInfo: ReplayDiffInfo = {
      ...BASE_DIFF_INFO,
      originalResponse: {
        jsonrpc: "2.0",
        id: 100,
        result: { content: [{ type: "text", text: "ok" }], isError: false },
      },
    };
    const newResponse = {
      jsonrpc: "2.0",
      id: 0,
      result: { content: [{ type: "text", text: "ok" }], isError: false },
    };
    render(
      <ReplayDiffModal
        open={true}
        onOpenChange={() => {}}
        diffInfo={okDiffInfo}
        newResponse={newResponse}
        newTimestamp="2026-09-13T10:05:00.000Z"
        loading={false}
      />,
    );
    expect(screen.getByText(/No differences found/)).toBeInTheDocument();
  });
});
