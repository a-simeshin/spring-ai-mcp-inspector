// [spring-ai-mcp-inspector PATCH] SpringAiPitfallsPanel test: panel renders,
// expands, links present, detector-note degrades gracefully (t_406cfdce).
import { render, screen, fireEvent } from "@testing-library/react";
import "@testing-library/jest-dom";
import { SpringAiPitfallsPanel } from "../SpringAiPitfallsPanel";

describe("SpringAiPitfallsPanel", () => {
  it("renders collapsed header with the panel title", () => {
    render(<SpringAiPitfallsPanel />);
    expect(
      screen.getByText("Common Spring AI 2.0 pitfalls"),
    ).toBeInTheDocument();
  });

  it("expands to show all three entries with upstream links", () => {
    render(<SpringAiPitfallsPanel />);
    fireEvent.click(screen.getByText("Common Spring AI 2.0 pitfalls"));

    expect(
      screen.getByText(/Server-side validation is on by default in 2\.0/),
    ).toBeInTheDocument();
    expect(
      screen.getByText(
        /Historical, Spring AI 1\.1\.x only: recursive parameter types emitted unresolvable \$ref/,
      ),
    ).toBeInTheDocument();
    expect(screen.getByText(/does not affect Spring AI 2\.0/)).toBeInTheDocument();
    expect(
      screen.getByText(
        /Schema generation silently skips primitive\/simple return types/,
      ),
    ).toBeInTheDocument();

    const upgradeNotesLink = screen.getByRole("link", {
      name: /Upgrade Notes \(Upgrading to 2\.0\.0\)/,
    });
    expect(upgradeNotesLink).toHaveAttribute(
      "href",
      "https://docs.spring.io/spring-ai/reference/upgrade-notes.html",
    );
    expect(upgradeNotesLink).toHaveAttribute("target", "_blank");
    expect(upgradeNotesLink).toHaveAttribute("rel", "noopener noreferrer");

    const refLink = screen.getByRole("link", {
      name: /spring-projects\/spring-ai#5888 \(fixed in 2\.0\.0-M7\)/,
    });
    expect(refLink).toHaveAttribute(
      "href",
      "https://github.com/spring-projects/spring-ai/issues/5888",
    );

    const primitiveLink = screen.getByRole("link", {
      name: /spring-projects\/spring-ai#6773/,
    });
    expect(primitiveLink).toHaveAttribute(
      "href",
      "https://github.com/spring-projects/spring-ai/issues/6773",
    );
  });

  it("degrades gracefully when no detector has fired (no active notes)", () => {
    render(<SpringAiPitfallsPanel />);
    fireEvent.click(screen.getByText("Common Spring AI 2.0 pitfalls"));

    // No "Detector active" notes should be present without flags.
    expect(screen.queryByText(/Detector active:/)).not.toBeInTheDocument();
  });

  it("surfaces a detector note when hasUnresolvedRefWarning is set", () => {
    render(<SpringAiPitfallsPanel hasUnresolvedRefWarning />);
    fireEvent.click(screen.getByText("Common Spring AI 2.0 pitfalls"));

    expect(
      screen.getByText(/Detector active: see Schema Warning above/),
    ).toBeInTheDocument();
  });

  it("surfaces a detector note when hasIsErrorWarning is set", () => {
    render(<SpringAiPitfallsPanel hasIsErrorWarning />);
    fireEvent.click(screen.getByText("Common Spring AI 2.0 pitfalls"));

    expect(
      screen.getByText(/Detector active: check Timeline for isError=true/),
    ).toBeInTheDocument();
  });

  it("collapses back when the header is clicked again", () => {
    render(<SpringAiPitfallsPanel />);
    const header = screen.getByText("Common Spring AI 2.0 pitfalls");
    fireEvent.click(header);
    expect(
      screen.getByText(
        /Historical, Spring AI 1\.1\.x only: recursive parameter types emitted unresolvable \$ref/,
      ),
    ).toBeInTheDocument();
    fireEvent.click(header);
    expect(
      screen.queryByText(
        /Historical, Spring AI 1\.1\.x only: recursive parameter types emitted unresolvable \$ref/,
      ),
    ).not.toBeInTheDocument();
  });
});
