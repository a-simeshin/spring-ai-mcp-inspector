// [spring-ai-mcp-inspector PATCH] Tests for the MarkdownPreview local patch
// (issue #170): markdown rendering, the View source toggle and the
// untrusted-content security contract (no script elements, no event handler
// attributes, no javascript:/data: URLs reach the DOM).
import { fireEvent, render, screen } from "@testing-library/react";
import "@testing-library/jest-dom";
import MarkdownPreview from "../MarkdownPreview";

const MD = [
  "# Title",
  "",
  "Some **bold** and _italic_ and `inline code`.",
  "",
  "- item",
  "  - nested item",
  "",
  "[link](https://example.com)",
  "",
  "```java",
  'System.out.println("hello");',
  "```",
].join("\n");

describe("MarkdownPreview", () => {
  it("renders markdown as headings, emphasis, code and nested lists by default", () => {
    const { container } = render(<MarkdownPreview content={MD} />);
    const view = screen.getByTestId("markdown-rendered-view");
    expect(view).toBeInTheDocument();
    expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent("Title");
    expect(container.querySelector("strong")).toHaveTextContent("bold");
    expect(container.querySelector("em")).toHaveTextContent("italic");
    expect(container.querySelector("code")).toHaveTextContent("inline code");
    const outer = container.querySelector("ul > li");
    expect(outer).toHaveTextContent("item");
    expect(container.querySelector("ul > li > ul")).not.toBeNull();
    expect(container.querySelector("pre > code")).toHaveTextContent(
      "System.out.println",
    );
    expect(screen.queryByTestId("markdown-source-view")).not.toBeInTheDocument();
  });

  it("keeps regular links as anchors with the target URL", () => {
    render(<MarkdownPreview content={MD} />);
    const link = screen.getByRole("link");
    expect(link).toHaveAttribute("href", "https://example.com");
  });

  it("toggles to the raw markdown source and back", () => {
    render(<MarkdownPreview content={MD} />);
    const toggle = screen.getByTestId("markdown-view-toggle");
    expect(toggle).toHaveTextContent("View source");
    fireEvent.click(toggle);
    const source = screen.getByTestId("markdown-source-view");
    expect(source).toBeInTheDocument();
    expect(source).toHaveTextContent("# Title");
    expect(toggle).toHaveTextContent("View rendered");
    expect(screen.queryByTestId("markdown-rendered-view")).not.toBeInTheDocument();
    fireEvent.click(toggle);
    expect(screen.getByTestId("markdown-rendered-view")).toBeInTheDocument();
    expect(screen.queryByTestId("markdown-source-view")).not.toBeInTheDocument();
  });

  it("renders a script tag in the source escaped: no script element reaches the DOM", () => {
    const { container } = render(
      <MarkdownPreview
        content={"hello <script>window.__mdPwned = true</script> world"}
      />,
    );
    expect(container.querySelector("script")).toBeNull();
    expect((window as unknown as { __mdPwned?: boolean }).__mdPwned).toBeUndefined();
  });

  it("renders an img with an onerror handler without the handler attribute", () => {
    const { container } = render(
      <MarkdownPreview content={'<img src="x" onerror="window.__mdPwned = true">'} />,
    );
    const img = container.querySelector("img");
    expect(img).not.toBeNull();
    expect(img).not.toHaveAttribute("onerror");
    expect((window as unknown as { __mdPwned?: boolean }).__mdPwned).toBeUndefined();
  });

  it("does not render a javascript: link as a live href", () => {
    const { container } = render(
      <MarkdownPreview content={"[click](javascript:alert(1))"} />,
    );
    const link = container.querySelector("a");
    expect(link).not.toBeNull();
    const href = (link?.getAttribute("href") ?? "").toLowerCase();
    expect(href).not.toContain("javascript:");
  });

  it("does not render a data: URL link as a live href", () => {
    const { container } = render(
      <MarkdownPreview content={"[x](data:text/html;base64,PHNjcmlwdD4=)"} />,
    );
    const link = container.querySelector("a");
    expect(link).not.toBeNull();
    const href = (link?.getAttribute("href") ?? "").toLowerCase();
    expect(href).not.toContain("data:");
  });

  it("keeps data: image URIs as media sources (the one allowed data: exception)", () => {
    const { container } = render(
      <MarkdownPreview content={"![p](data:image/png;base64,iVBORw0KGgo=)"} />,
    );
    const img = container.querySelector("img");
    expect(img).not.toBeNull();
    expect(img?.getAttribute("src")).toContain("data:image/png");
  });

  it("strips data:text/html sources even on media elements (no data: passthrough)", () => {
    const { container } = render(
      <MarkdownPreview content={'<img src="data:text/html;base64,PHNjcmlwdD4=">'} />,
    );
    const img = container.querySelector("img");
    expect(img).not.toBeNull();
    const src = (img?.getAttribute("src") ?? "").toLowerCase();
    expect(src).not.toContain("data:text/html");
  });
});
