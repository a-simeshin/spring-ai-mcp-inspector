// [spring-ai-mcp-inspector PATCH] Rendered markdown preview for text/markdown
// resource payloads (issue #170). Markdown is parsed with marked and the
// resulting HTML is sanitized with DOMPurify before insertion: raw HTML in
// untrusted sources cannot inject script elements, event handler attributes
// or javascript:/data: URLs; only raster image data: URIs are kept. A toggle
// switches the default rendered
// view to the raw markdown source, shown in a monospace, selectable and
// copyable block.
import { useMemo, useState } from "react";
import { marked } from "marked";
import DOMPurify from "dompurify";

// DOMPurify's default policy keeps every data: URI on media elements
// regardless of the media type, which is wider than this preview needs. The
// hook below narrows data: to raster image payloads only (issue #170 negative
// tests); SVG is excluded because standalone SVG documents can carry script.
// It is installed once for the shared DOMPurify instance, whose sanitize() is
// only used by this preview in the application.
const RASTER_IMAGE_DATA_URI =
  /^data:image\/(?:png|jpeg|jpg|gif|webp|avif|bmp);base64,[a-z0-9+/=]+$/i;
let dataUriHookInstalled = false;
const installDataUriRestriction = () => {
  if (dataUriHookInstalled) return;
  dataUriHookInstalled = true;
  DOMPurify.addHook("uponSanitizeAttribute", (_node, data) => {
    if (
      /^data:/i.test(data.attrValue) &&
      !RASTER_IMAGE_DATA_URI.test(data.attrValue)
    ) {
      data.keepAttr = false;
    }
  });
};
installDataUriRestriction();

interface MarkdownPreviewProps {
  /** Raw markdown source text (already decoded by MediaContentView). */
  content: string;
}

const MarkdownPreview = ({ content }: MarkdownPreviewProps) => {
  const [showSource, setShowSource] = useState(false);

  const renderedHtml = useMemo(() => {
    const parsed = marked.parse(content, { async: false });
        return DOMPurify.sanitize(typeof parsed === "string" ? parsed : "");
  }, [content]);

  return (
    <div>
      <button
        type="button"
        data-testid="markdown-view-toggle"
        aria-pressed={showSource}
        onClick={() => setShowSource((v) => !v)}
        className="text-sm text-blue-600 dark:text-blue-400 hover:underline mb-2"
      >
        {showSource ? "View rendered" : "View source"}
      </button>
      {showSource ? (
        <pre
          className="p-4 border rounded font-mono text-sm whitespace-pre-wrap break-words max-h-96 overflow-auto"
          data-testid="markdown-source-view"
        >
          {content}
        </pre>
      ) : (
        <div
          className="markdown-preview p-4 border rounded text-sm max-h-96 overflow-auto"
          data-testid="markdown-rendered-view"
          dangerouslySetInnerHTML={{ __html: renderedHtml }}
        />
      )}
    </div>
  );
};

export default MarkdownPreview;
