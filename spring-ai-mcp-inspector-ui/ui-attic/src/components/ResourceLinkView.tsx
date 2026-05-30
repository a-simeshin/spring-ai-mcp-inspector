// Portions adapted from modelcontextprotocol/inspector (Apache-2.0)
// https://github.com/modelcontextprotocol/inspector

import { useState } from 'react';
import { ExternalLink } from 'lucide-react';
import { Dialog, DialogContent, DialogTitle } from './ui/Dialog';
import { Button } from './ui/Button';
import { JsonView } from './JsonView';
import { jsonRpc } from '../api/client';

export interface ResourceLinkViewProps {
  uri: string;
  name?: string;
  description?: string;
  mimeType?: string;
}

interface ResourceContent {
  uri: string;
  mimeType?: string;
  text?: string;
  blob?: string;
}

interface ResourceReadResult {
  contents: ResourceContent[];
}

/** Renders an inline resource link that opens a modal preview on click. */
export function ResourceLinkView({ uri, name, description, mimeType }: ResourceLinkViewProps) {
  const [open, setOpen] = useState(false);
  const [content, setContent] = useState<ResourceContent | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleOpen = async () => {
    setOpen(true);
    if (content !== null) return;
    setLoading(true);
    setError(null);
    try {
      const response = await jsonRpc<ResourceReadResult>('resources/read', { uri });
      if (response.error) {
        setError(`${response.error.code}: ${response.error.message}`);
      } else {
        setContent(response.result?.contents?.[0] ?? null);
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setLoading(false);
    }
  };

  return (
    <>
      <button
        type="button"
        onClick={handleOpen}
        className="inline-flex items-center gap-1 rounded border border-border bg-muted px-2 py-1 text-xs font-mono text-foreground hover:bg-primary"
      >
        <ExternalLink size={12} />
        <span className="truncate max-w-[260px]">{name ?? uri}</span>
        {mimeType && <span className="text-muted-foreground">({mimeType})</span>}
      </button>
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent className="max-w-2xl">
          <DialogTitle>{name ?? uri}</DialogTitle>
          {description && <p className="mt-1 text-sm text-muted-foreground">{description}</p>}
          <div className="mt-2 text-xs text-muted-foreground">
            {uri}
            {mimeType && <> · {mimeType}</>}
          </div>
          <div className="mt-3 max-h-[60vh] overflow-auto rounded-md border border-border bg-muted p-2">
            {loading && <span className="text-sm text-muted-foreground">Loading…</span>}
            {error && <span className="text-sm text-destructive">{error}</span>}
            {!loading && !error && content && (content.text != null ? (
              <pre className="whitespace-pre-wrap text-xs text-foreground">{content.text}</pre>
            ) : content.blob != null ? (
              <pre className="break-all text-xs text-muted-foreground">{content.blob}</pre>
            ) : (
              <JsonView value={content} />
            ))}
          </div>
          <div className="mt-3 flex justify-end">
            <Button type="button" variant="ghost" onClick={() => setOpen(false)}>
              Close
            </Button>
          </div>
        </DialogContent>
      </Dialog>
    </>
  );
}

export default ResourceLinkView;
