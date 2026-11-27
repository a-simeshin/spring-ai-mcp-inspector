// [spring-ai-mcp-inspector PATCH] missing-output-schema-warning (#6773):
// rendering coverage for the inline #6773 warning. The previous suite had no
// ToolResults rendering test, so disabling the render branch kept all tests
// green; these cases fail if the warning markup stops rendering.
import { render, screen } from "@testing-library/react";
import "@testing-library/jest-dom";
import ToolResults from "../ToolResults";
import type {
  Tool,
  CompatibilityCallToolResult,
} from "@modelcontextprotocol/sdk/types.js";

const UPSTREAM_ISSUE =
  "https://github.com/spring-projects/spring-ai/issues/6773";

const schemalessTool = {
  name: "sum",
  description: "adds two numbers",
  inputSchema: { type: "object", properties: {} },
} as unknown as Tool;

const toolWithSchema = {
  name: "sum",
  description: "adds two numbers",
  inputSchema: { type: "object", properties: {} },
  outputSchema: { type: "object", properties: { total: { type: "number" } } },
} as unknown as Tool;

const textResult = (text: string): CompatibilityCallToolResult =>
  ({ content: [{ type: "text", text }] }) as CompatibilityCallToolResult;

describe("ToolResults - #6773 missing output schema warning", () => {
  test("renders the warning for a JSON primitive result on a schema-less tool", () => {
    render(
      <ToolResults
        toolResult={textResult("42")}
        selectedTool={schemalessTool}
        resourceContent={{}}
      />,
    );

    const warning = screen.getByTestId("missing-output-schema-warning");
    expect(warning).toBeInTheDocument();
    expect(warning).toHaveTextContent("sum");
    expect(warning).toHaveTextContent("JSON primitive");

    const link = screen.getByRole("link", { name: /spring-ai#6773/ });
    expect(link).toHaveAttribute("href", UPSTREAM_ISSUE);
  });

  test("renders the warning for a JSON-encoded string result", () => {
    render(
      <ToolResults
        toolResult={textResult('"hello"')}
        selectedTool={schemalessTool}
        resourceContent={{}}
      />,
    );

    expect(
      screen.getByTestId("missing-output-schema-warning"),
    ).toBeInTheDocument();
  });

  test("does not render the warning for a plain-text result", () => {
    render(
      <ToolResults
        toolResult={textResult("hello primitive world")}
        selectedTool={schemalessTool}
        resourceContent={{}}
      />,
    );

    expect(
      screen.queryByTestId("missing-output-schema-warning"),
    ).not.toBeInTheDocument();
  });

  test("does not render the warning when the tool advertises an outputSchema", () => {
    render(
      <ToolResults
        toolResult={textResult("42")}
        selectedTool={toolWithSchema}
        resourceContent={{}}
      />,
    );

    expect(
      screen.queryByTestId("missing-output-schema-warning"),
    ).not.toBeInTheDocument();
  });

  test("does not render the warning for an error result", () => {
    render(
      <ToolResults
        toolResult={
          {
            content: [{ type: "text", text: '"boom"' }],
            isError: true,
          } as CompatibilityCallToolResult
        }
        selectedTool={schemalessTool}
        resourceContent={{}}
      />,
    );

    expect(
      screen.queryByTestId("missing-output-schema-warning"),
    ).not.toBeInTheDocument();
    expect(screen.getByText(/error/i)).toBeInTheDocument();
  });
});
