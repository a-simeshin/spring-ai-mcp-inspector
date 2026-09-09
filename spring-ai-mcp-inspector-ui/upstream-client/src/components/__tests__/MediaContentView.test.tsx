import { render, screen } from "@testing-library/react";
import "@testing-library/jest-dom";
import MediaContentView from "../MediaContentView";

// Mock JsonView to capture its data prop
jest.mock("../JsonView", () => {
  return function MockJsonView({ data }: { data: string }) {
    return <div data-testid="json-view">{data}</div>;
  };
});

describe("MediaContentView", () => {
  // ---- image/* ----
  describe("image/* MIME type", () => {
    it("renders an <img> with the correct data URI", () => {
      render(
        <MediaContentView
          mimeType="image/png"
          base64Data="iVBORw0KGgo="
          alt="Test image"
        />,
      );
      const img = screen.getByAltText("Test image");
      expect(img).toBeInTheDocument();
      expect(img).toHaveAttribute("src", "data:image/png;base64,iVBORw0KGgo=");
    });

    it("renders a download link with the image filename", () => {
      render(
        <MediaContentView
          mimeType="image/png"
          base64Data="iVBORw0KGgo="
          filename="screenshot.png"
        />,
      );
      const downloadLink = screen.getByText("Download screenshot.png");
      expect(downloadLink).toBeInTheDocument();
      expect(downloadLink.closest("a")).toHaveAttribute(
        "download",
        "screenshot.png",
      );
    });
  });

  // ---- audio/* ----
  describe("audio/* MIME type", () => {
    it("renders an <audio> element with controls", () => {
      render(
        <MediaContentView
          mimeType="audio/mpeg"
          base64Data="SUQzBAAAAA=="
        />,
      );
      const audio = document.querySelector("audio");
      expect(audio).toBeInTheDocument();
      expect(audio).toHaveAttribute("controls");
      expect(audio).toHaveAttribute(
        "src",
        "data:audio/mpeg;base64,SUQzBAAAAA==",
      );
    });
  });

  // ---- generic binary ----
  describe("generic binary content", () => {
    it("renders a download button", () => {
      render(
        <MediaContentView
          mimeType="application/octet-stream"
          base64Data="AAAA"
          filename="data.bin"
        />,
      );
      expect(screen.getByText("Download data.bin")).toBeInTheDocument();
    });

    it("renders 'Open in new tab' for a safe inert MIME type (text/plain)", () => {
      render(
        <MediaContentView
          mimeType="text/plain"
          base64Data="SGVsbG8="
          filename="readme.txt"
        />,
      );
      expect(screen.getByText("Open in new tab")).toBeInTheDocument();
    });

    it("does NOT render 'Open in new tab' for executable MIME (text/html)", () => {
      render(
        <MediaContentView
          mimeType="text/html"
          base64Data="PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg=="
          filename="page.html"
        />,
      );
      expect(screen.queryByText("Open in new tab")).not.toBeInTheDocument();
    });

    it("does NOT render 'Open in new tab' for image/svg+xml", () => {
      render(
        <MediaContentView
          mimeType="image/svg+xml"
          base64Data="PHN2Zy8+"
          filename="graphic.svg"
        />,
      );
      expect(screen.queryByText("Open in new tab")).not.toBeInTheDocument();
    });

    it("does NOT render 'Open in new tab' for application/xml", () => {
      render(
        <MediaContentView
          mimeType="application/xml"
          base64Data="PHhtbD4"
          filename="data.xml"
        />,
      );
      expect(screen.queryByText("Open in new tab")).not.toBeInTheDocument();
    });

    it("renders 'Open in new tab' for application/pdf", () => {
      render(
        <MediaContentView
          mimeType="application/pdf"
          base64Data="JVBERi0xLg=="
          filename="doc.pdf"
        />,
      );
      expect(screen.getByText("Open in new tab")).toBeInTheDocument();
    });

    it("renders 'Open in new tab' for application/json (inert, non-image)", () => {
      render(
        <MediaContentView
          mimeType="application/json"
          base64Data="eyJrZXkiOiAidmFsdWUifQ=="
          filename="data.json"
        />,
      );
      expect(screen.getByText("Open in new tab")).toBeInTheDocument();
    });
  });

  // ---- empty blob ----
  describe("empty blob (blob: \"\")", () => {
    it("renders binary download affordance instead of empty JsonView", () => {
      render(
        <MediaContentView
          mimeType="application/octet-stream"
          base64Data=""
          filename="empty.bin"
        />,
      );
      // Should show a download button, not a JsonView
      expect(screen.getByText("Download empty.bin")).toBeInTheDocument();
      expect(screen.queryByTestId("json-view")).not.toBeInTheDocument();
    });
  });

  // ---- no binary data ----
  describe("no base64Data", () => {
    it("renders JsonView with text content", () => {
      render(
        <MediaContentView
          mimeType="text/plain"
          text="Hello, world!"
        />,
      );
      const jsonView = screen.getByTestId("json-view");
      expect(jsonView).toBeInTheDocument();
      expect(jsonView).toHaveTextContent("Hello, world!");
    });
  });

  // ---- case-insensitive MIME dispatch ----
  describe("case-insensitive MIME dispatch", () => {
    it("matches Image/PNG (uppercase first letter) as image/*", () => {
      render(
        <MediaContentView
          mimeType="Image/PNG"
          base64Data="iVBORw0KGgo="
          alt="Case test"
        />,
      );
      const img = screen.getByAltText("Case test");
      expect(img).toBeInTheDocument();
      expect(img).toHaveAttribute("src", "data:image/png;base64,iVBORw0KGgo=");
    });

    it("matches AUDIO/MPEG (uppercase) as audio/*", () => {
      render(
        <MediaContentView
          mimeType="AUDIO/MPEG"
          base64Data="SUQzBAAAAA=="
        />,
      );
      const audio = document.querySelector("audio");
      expect(audio).toBeInTheDocument();
      expect(audio).toHaveAttribute("controls");
    });

    it("blocks TEXT/HTML (uppercase) from Open in new tab", () => {
      render(
        <MediaContentView
          mimeType="TEXT/HTML"
          base64Data="PHNjcmlwdD4="
          filename="page.html"
        />,
      );
      expect(screen.queryByText("Open in new tab")).not.toBeInTheDocument();
    });

    it("allows Text/Plain (mixed case) in Open in new tab", () => {
      render(
        <MediaContentView
          mimeType="Text/Plain"
          base64Data="SGVsbG8="
          filename="readme.txt"
        />,
      );
      expect(screen.getByText("Open in new tab")).toBeInTheDocument();
    });
  });

  // ---- MIME type badge ----
  it("renders the MIME type badge", () => {
    render(
      <MediaContentView
        mimeType="image/png"
        base64Data="iVBORw0KGgo="
      />,
    );
    expect(screen.getByText("image/png")).toBeInTheDocument();
  });

  it("does not render a badge when mimeType is undefined", () => {
    render(
      <MediaContentView
        base64Data="AAAA"
        filename="data.bin"
      />,
    );
    // The mime type badge is rendered inside the badge span
    // When mimeType is undefined, mimeTypeBadge is null, so no badge
    expect(screen.queryByText("application/octet-stream")).not.toBeInTheDocument();
  });
});