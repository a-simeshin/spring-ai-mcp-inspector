// [spring-ai-mcp-inspector PATCH] Shared mimeType-aware content renderer for
// resource previews (#108, #59 follow-up). Replaces hardcoded type-switches
// in ToolResults.tsx and raw JSON.stringify rendering in ResourcesTab.tsx/
// ResourceLinkView.tsx.
// Supports image/* → <img>, audio/* → <audio>, other binary → <a download>,
// text/* → JsonView fallback.
// Security: "Open in new tab" is restricted to inert MIME types only
// (text/plain, image/*, audio/*, video/*, application/pdf) to prevent
// execution of attacker-controlled HTML/JS via data: URIs.
import JsonView from "./JsonView";

interface MediaContentViewProps {
  /** MIME type of the content (e.g. "image/png", "text/plain"). */
  mimeType?: string;
  /**
   * Base64-encoded binary data (for BlobResourceContents.blob,
   * ImageContent.data). When provided alongside text, binary takes precedence
   * for image/audio/binary mime types.
   */
  base64Data?: string;
  /**
   * Plain text content (for TextResourceContents.text or when no binary
   * blob is present).
   */
  text?: string;
  /** Optional className forwarded to the wrapper. */
  className?: string;
  /** Optional alt text for images. */
  alt?: string;
  /** Optional filename hint for download links. */
  filename?: string;
}

/**
 * MIME types that are safe to open via data: URI in a new tab.
 * Active content types (text/html, image/svg+xml, application/xml, etc.)
 * are excluded to prevent XSS via attacker-controlled BlobResourceContents.
 */
const INERT_MIME_TYPES: ReadonlySet<string> = new Set([
  "text/plain",
  "application/pdf",
  "application/json",
  "application/octet-stream",
]);

/** Check whether a MIME type is safe to open via data: URI in a new tab. */
const isInertMimeType = (mt: string): boolean => {
  const lower = mt.toLowerCase();
  return (
    INERT_MIME_TYPES.has(lower) ||
    lower.startsWith("image/") ||
    lower.startsWith("audio/") ||
    lower.startsWith("video/")
  );
};

/**
 * Shared mimeType-aware content renderer.
 *
 * Dispatch:
 * - `image/*` (with base64Data)  → `<img>` with data URI
 * - `audio/*` (with base64Data)  → `<audio controls>` with data URI
 * - binary/* or other non-text mimeTypes with base64Data → `<a download>`
 *   (+ "Open in new tab" only for inert MIME types)
 * - text/* or no base64Data      → `<JsonView>` (preserves existing rendering)
 */
const MediaContentView = ({
  mimeType,
  base64Data,
  text,
  className,
  alt,
  filename,
}: MediaContentViewProps) => {
  // Normalise mimeType to lowercase for case-insensitive dispatch
  const normalisedMimeType = mimeType?.toLowerCase();
  const hasBinary = typeof base64Data === "string";

  /** MIME type badge shown above content for all resource types (shows original). */
  const mimeTypeBadge = mimeType ? (
    <span className="inline-block bg-blue-100 dark:bg-blue-900 text-blue-800 dark:text-blue-200 text-xs px-2 py-0.5 rounded mb-2 font-mono">
      {mimeType}
    </span>
  ) : null;

  // Image content — render as <img>
  if (hasBinary && normalisedMimeType?.startsWith("image/")) {
    const dataUri = `data:${normalisedMimeType};base64,${base64Data}`;
    const downloadName = filename || "image";
    return (
      <div className={className}>
        {mimeTypeBadge}
        <img
          src={dataUri}
          alt={alt || "Resource image"}
          className="max-w-full h-auto"
        />
        <div className="mt-2">
          <a
            download={downloadName}
            href={dataUri}
            className="inline-flex items-center gap-2 px-4 py-2 rounded text-sm font-medium
                       bg-blue-600 text-white hover:bg-blue-700 transition-colors
                       focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-500
                       w-fit"
          >
            <DownloadIcon />
            Download{filename ? ` ${filename}` : ""}
          </a>
        </div>
      </div>
    );
  }

  // Audio content — render as <audio controls>
  if (hasBinary && normalisedMimeType?.startsWith("audio/")) {
    const dataUri = `data:${normalisedMimeType};base64,${base64Data}`;
    const downloadName = filename || "audio";
    return (
      <div className={className}>
        {mimeTypeBadge}
        <audio
          controls
          src={dataUri}
          className="w-full"
        >
          <p>Your browser does not support audio playback</p>
        </audio>
        <div className="mt-2">
          <a
            download={downloadName}
            href={dataUri}
            className="inline-flex items-center gap-2 px-4 py-2 rounded text-sm font-medium
                       bg-blue-600 text-white hover:bg-blue-700 transition-colors
                       focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-500
                       w-fit"
          >
            <DownloadIcon />
            Download{filename ? ` ${filename}` : ""}
          </a>
        </div>
      </div>
    );
  }

  // Other binary content — render download/open affordance
  if (hasBinary) {
    const href = `data:${normalisedMimeType || "application/octet-stream"};base64,${base64Data}`;
    const downloadName = filename || "download";
    return (
      <div className={`flex flex-col gap-2 ${className || ""}`.trim()}>
        {mimeTypeBadge && (
          <span className="w-fit">{mimeTypeBadge}</span>
        )}
        <a
          download={downloadName}
          href={href}
          className="inline-flex items-center gap-2 px-4 py-2 rounded text-sm font-medium
                     bg-blue-600 text-white hover:bg-blue-700 transition-colors
                     focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-500
                     w-fit"
        >
          <DownloadIcon />
          Download{filename ? ` ${filename}` : ""}
        </a>
        {/* "Open in new tab" restricted to inert MIME types only --
            avoids executing attacker-controlled HTML/JS via data: URIs. */}
        {filename && normalisedMimeType && isInertMimeType(normalisedMimeType) && (
          <a
            href={href}
            target="_blank"
            rel="noopener noreferrer"
            className="text-sm text-blue-600 dark:text-blue-400 hover:underline"
          >
            Open in new tab
          </a>
        )}
      </div>
    );
  }

  // Text content — render via JsonView (preserves existing behaviour)
  const displayData = text ?? "";
  return (
    <div className={className}>
      {mimeTypeBadge}
      <JsonView data={displayData} />
    </div>
  );
};

/** Simple inline SVG download icon (14×14). */
const DownloadIcon = () => (
  <svg
    xmlns="http://www.w3.org/2000/svg"
    width="14"
    height="14"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    strokeWidth="2"
    strokeLinecap="round"
    strokeLinejoin="round"
    className="flex-shrink-0"
  >
    <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
    <polyline points="7 10 12 15 17 10" />
    <line x1="12" x2="12" y1="15" y2="3" />
  </svg>
);

export default MediaContentView;
