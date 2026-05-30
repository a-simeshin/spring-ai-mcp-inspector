// Portions adapted from modelcontextprotocol/inspector (Apache-2.0)
// https://github.com/modelcontextprotocol/inspector

import { Plus } from 'lucide-react';
import { Button } from '../components/ui/Button';
import { useToast } from '../lib/hooks/useToast';

/**
 * Apps tab: placeholder for MCP "apps" capability (servers that expose tools
 * with embedded UI resource URIs). Real launching is not implemented.
 */
export function AppsTab() {
  const { toast } = useToast();
  return (
    <section className="space-y-4">
      <header className="flex items-center justify-between">
        <div>
          <h2 className="text-xl font-semibold text-foreground">Apps</h2>
          <p className="text-sm text-muted-foreground">Browse MCP apps exposed by the connected server.</p>
        </div>
        <Button
          type="button"
          variant="primary"
          size="sm"
          onClick={() =>
            toast({
              title: 'Not implemented',
              description: 'Launching MCP apps is not yet supported by this inspector.',
            })
          }
        >
          <Plus size={12} /> Launch app
        </Button>
      </header>

      <div className="rounded-md border border-dashed border-border bg-muted p-8 text-center text-sm text-muted-foreground">
        <p>Connect to an MCP server with apps capability to see them here.</p>
      </div>
    </section>
  );
}

export default AppsTab;
