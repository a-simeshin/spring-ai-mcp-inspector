import { render, screen, fireEvent, act } from "@testing-library/react";
import "@testing-library/jest-dom";
import { describe, it, jest } from "@jest/globals";
import ResourceLinkView from "../ResourceLinkView";

// Mock MediaContentView to capture its props per invocation
const mockMediaContentView = jest.fn<
  ReturnType<typeof MediaContentView>,
  Parameters<typeof MediaContentView>
>(() => <div data-testid="media-content-view" />);
jest.mock("../MediaContentView", () => ({
  __esModule: true,
  default: (props: Record<string, unknown>) => {
    mockMediaContentView(props);
    return <div data-testid="media-content-view" />;
  },
}));

// Mock JsonView
jest.mock("../JsonView", () => {
  return function MockJsonView({ data }: { data: string }) {
    return <div data-testid="json-view">{String(data)}</div>;
  };
});

describe("ResourceLinkView — multi-item ReadResourceResult", () => {
  beforeEach(() => {
    mockMediaContentView.mockClear();
  });

  const uri = "demo://multi-item";
  const mimeType = "text/plain";
  const name = "Multi-item resource";
  const description = "A resource with two contents";

  it("renders both items from a parsed ReadResourceResult with 2 contents", async () => {
    const resourceContent = {
      contents: [
        {
          uri: "demo://multi-item/1",
          mimeType: "text/plain",
          text: "First item content",
        },
        {
          uri: "demo://multi-item/2.png",
          mimeType: "image/png",
          blob: "iVBORw0KGgo=",
        },
      ],
    };

    const onReadResource = jest.fn();

    render(
      <ResourceLinkView
        uri={uri}
        name={name}
        description={description}
        mimeType={mimeType}
        resourceContent={resourceContent}
        onReadResource={onReadResource}
      />,
    );

    // The resource link should be visible with its URI, name, description
    expect(screen.getByText(uri)).toBeInTheDocument();
    expect(screen.getByText(name)).toBeInTheDocument();
    expect(screen.getByText(description)).toBeInTheDocument();

    // The expand button should exist and be collapsed
    const expandButton = screen.getByRole("button", {
      name: /expand resource/i,
    });
    expect(expandButton).toHaveAttribute("aria-expanded", "false");

    // No content should be visible yet (not expanded)
    expect(screen.queryByTestId("media-content-view")).not.toBeInTheDocument();

    // Expand the resource
    await act(async () => {
      fireEvent.click(expandButton);
    });

    // onReadResource should have been called
    expect(onReadResource).toHaveBeenCalledWith(uri);

    // The header should show "Resource (2 items):"
    expect(screen.getByText("Resource (2 items):")).toBeInTheDocument();

    // Both items should be rendered via MediaContentView
    const mediaContentViews = screen.getAllByTestId("media-content-view");
    expect(mediaContentViews).toHaveLength(2);

    // Verify the first item was rendered with text/plain props
    expect(mockMediaContentView).toHaveBeenNthCalledWith(
      1,
      expect.objectContaining({
        mimeType: "text/plain",
        text: "First item content",
        filename: "1",
      }),
    );

    // Verify the second item was rendered with image/png props
    expect(mockMediaContentView).toHaveBeenNthCalledWith(
      2,
      expect.objectContaining({
        mimeType: "image/png",
        base64Data: "iVBORw0KGgo=",
        filename: "2.png",
      }),
    );

    // Item labels should be visible for multi-item resources
    expect(screen.getByText("Item 1")).toBeInTheDocument();
    expect(screen.getByText("Item 2")).toBeInTheDocument();

    // Collapse
    await act(async () => {
      fireEvent.click(expandButton);
    });

    // Content should be gone
    expect(screen.queryByTestId("media-content-view")).not.toBeInTheDocument();
    expect(screen.queryByText("Resource (2 items):")).not.toBeInTheDocument();
    expect(expandButton).toHaveAttribute("aria-expanded", "false");
  });

  it("renders JsonView fallback when resourceContent has no contents array", async () => {
    const resourceContent = { someKey: "someValue" };

    const onReadResource = jest.fn();

    render(
      <ResourceLinkView
        uri={uri}
        name={name}
        description={description}
        mimeType={mimeType}
        resourceContent={resourceContent}
        onReadResource={onReadResource}
      />,
    );

    const expandButton = screen.getByRole("button", {
      name: /expand resource/i,
    });

    await act(async () => {
      fireEvent.click(expandButton);
    });

    // Should render JsonView fallback, not MediaContentView
    expect(screen.getByTestId("json-view")).toBeInTheDocument();
    expect(screen.queryByTestId("media-content-view")).not.toBeInTheDocument();
    // Header should say "Resource:" (no item count)
    expect(screen.getByText("Resource:")).toBeInTheDocument();
  });

  it("renders single item without item labels", async () => {
    const resourceContent = {
      contents: [
        {
          uri: "demo://single-item",
          mimeType: "text/plain",
          text: "Single item",
        },
      ],
    };

    const onReadResource = jest.fn();

    render(
      <ResourceLinkView
        uri="demo://single-item"
        name="Single"
        description="Single item resource"
        mimeType="text/plain"
        resourceContent={resourceContent}
        onReadResource={onReadResource}
      />,
    );

    const expandButton = screen.getByRole("button", {
      name: /expand resource/i,
    });

    await act(async () => {
      fireEvent.click(expandButton);
    });

    // Header should say "Resource:" (no item count for single item)
    expect(screen.getByText("Resource:")).toBeInTheDocument();
    // No item labels for single item
    expect(screen.queryByText("Item 1")).not.toBeInTheDocument();
    // One MediaContentView
    expect(screen.getAllByTestId("media-content-view")).toHaveLength(1);
  });

  it("does not call onReadResource when no handler is provided", async () => {
    const resourceContent = {
      contents: [
        {
          uri: "demo://no-handler",
          mimeType: "text/plain",
          text: "No handler",
        },
      ],
    };

    render(
      <ResourceLinkView
        uri="demo://no-handler"
        resourceContent={resourceContent}
      />,
    );

    // The button exists but is non-interactive (tabIndex -1, no onClick)
    const expandButton = screen.getByRole("button", {
      name: /expand resource/i,
    });
    expect(expandButton).toHaveAttribute("tabindex", "-1");

    // Clicking should not crash and should not expand
    await act(async () => {
      fireEvent.click(expandButton);
    });

    // No MediaContentView should appear (not expanded)
    expect(screen.queryByTestId("media-content-view")).not.toBeInTheDocument();
  });
});