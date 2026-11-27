// [spring-ai-mcp-inspector PATCH] Component specs for the Roots tab save
// flow: invalid root URIs block the save inline and never reach the
// notifications/roots/list_changed notification (see
// NOTICE.d/roots-uri-validation.txt). Message texts are queried directly
// because the tab-level hint Alert also carries role="alert".
import { fireEvent, render, screen } from "@testing-library/react";
import "@testing-library/jest-dom";
import { useState } from "react";
import { Root } from "@modelcontextprotocol/sdk/types.js";
import { Tabs } from "@/components/ui/tabs";
import RootsTab from "../RootsTab";

const onRootsChange = jest.fn();

const Harness = ({ initialRoots }: { initialRoots: Root[] }) => {
  const [roots, setRoots] = useState<Root[]>(initialRoots);
  return (
    <Tabs defaultValue="roots">
      <RootsTab
        roots={roots}
        setRoots={setRoots}
        onRootsChange={onRootsChange}
      />
    </Tabs>
  );
};

const rootInput = () =>
  screen.getByPlaceholderText("file:// URI") as HTMLInputElement;

const saveButton = () => screen.getByRole("button", { name: /save changes/i });

beforeEach(() => {
  jest.clearAllMocks();
});

describe("RootsTab save changes validation", () => {
  it("blocks the seeded file:// URI with an inline message and sends no notification", () => {
    render(<Harness initialRoots={[{ uri: "file://", name: "" }]} />);

    fireEvent.click(saveButton());

    const errorMessage = screen.getByText(/specify a path after the file/i);
    expect(errorMessage).toHaveAttribute("role", "alert");
    expect(errorMessage).toHaveAttribute("id");
    expect(rootInput()).toHaveAttribute("aria-invalid", "true");
    expect(rootInput()).toHaveAttribute(
      "aria-describedby",
      errorMessage.getAttribute("id") as string,
    );
    expect(onRootsChange).not.toHaveBeenCalled();
    expect(saveButton()).toBeEnabled();
    expect(rootInput()).toHaveValue("file://");
  });

  it("requires a non-empty root URI", () => {
    render(<Harness initialRoots={[{ uri: "   ", name: "" }]} />);

    fireEvent.click(saveButton());

    expect(screen.getByText(/root uri is required/i)).toBeInTheDocument();
    expect(onRootsChange).not.toHaveBeenCalled();
  });

  it("reports a missing scheme and then invalid characters inline while editing", () => {
    render(<Harness initialRoots={[{ uri: "not a uri", name: "" }]} />);

    fireEvent.click(saveButton());
    expect(screen.getByText(/add a uri scheme/i)).toBeInTheDocument();

    fireEvent.change(rootInput(), { target: { value: "file:///a b" } });
    expect(
      screen.getByText(/not allowed in a uri/i),
    ).toBeInTheDocument();
    expect(onRootsChange).not.toHaveBeenCalled();
  });

  it("clears the error live while the URI is being fixed and notifies on a valid save", () => {
    render(<Harness initialRoots={[{ uri: "file://", name: "" }]} />);

    fireEvent.click(saveButton());
    expect(screen.getByText(/specify a path after the file/i)).toBeInTheDocument();

    fireEvent.change(rootInput(), { target: { value: "file:///valid/path" } });
    expect(
      screen.queryByText(/specify a path after the file/i),
    ).not.toBeInTheDocument();

    fireEvent.click(saveButton());
    expect(onRootsChange).toHaveBeenCalledTimes(1);
  });

  it("does not notify when one of several roots is invalid", () => {
    render(
      <Harness
        initialRoots={[
          { uri: "file:///valid/path", name: "" },
          { uri: "not a uri", name: "" },
        ]}
      />,
    );

    fireEvent.click(saveButton());

    expect(screen.getByText(/add a uri scheme/i)).toBeInTheDocument();
    expect(screen.queryByText(/root uri is required/i)).not.toBeInTheDocument();
    expect(onRootsChange).not.toHaveBeenCalled();
  });
});
