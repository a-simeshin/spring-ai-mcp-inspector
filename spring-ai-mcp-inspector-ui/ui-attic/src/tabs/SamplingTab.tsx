// Portions adapted from modelcontextprotocol/inspector (Apache-2.0)
// https://github.com/modelcontextprotocol/inspector

import { useEffect, useState } from 'react';
import { subscribeEvents, type SamplingRequestEvent } from '../api/sse';
import { respondToServerRequest } from '../api/client';
import { useConnection } from '../state/connection';
import { useToast } from '../lib/hooks/useToast';
import { SamplingRequest } from '../components/SamplingRequest';

interface PendingSampling {
  requestId: string;
  params: unknown;
  status: 'pending' | 'approved' | 'rejected';
}

/**
 * Sampling tab: collects server-initiated `sampling/createMessage` requests
 * delivered via SSE and lets the user approve or reject each one.
 */
export function SamplingTab() {
  const { sessionId } = useConnection();
  const { toast } = useToast();
  const [requests, setRequests] = useState<PendingSampling[]>([]);

  useEffect(() => {
    const sub = subscribeEvents({
      onSamplingRequest: (event: SamplingRequestEvent) => {
        setRequests((prev) => {
          if (prev.some((r) => r.requestId === event.requestId)) return prev;
          return [{ requestId: event.requestId, params: event.params, status: 'pending' }, ...prev];
        });
      },
    });
    return () => sub.close();
  }, []);

  if (!sessionId) {
    return (
      <section className="space-y-2">
        <h2 className="text-xl font-semibold text-foreground">Sampling</h2>
        <p className="text-sm italic text-muted-foreground">Not connected.</p>
      </section>
    );
  }

  const handleApprove = async (requestId: string, text: string) => {
    try {
      await respondToServerRequest(sessionId, requestId, {
        result: {
          role: 'assistant',
          content: { type: 'text', text },
          model: 'inspector-user',
        },
      });
      setRequests((prev) => prev.map((r) => (r.requestId === requestId ? { ...r, status: 'approved' } : r)));
    } catch (err) {
      toast({ title: 'Approve failed', description: err instanceof Error ? err.message : String(err), variant: 'danger' });
    }
  };

  const handleReject = async (requestId: string) => {
    try {
      await respondToServerRequest(sessionId, requestId, {
        error: { code: -32601, message: 'User rejected sampling request' },
      });
      setRequests((prev) => prev.map((r) => (r.requestId === requestId ? { ...r, status: 'rejected' } : r)));
    } catch (err) {
      toast({ title: 'Reject failed', description: err instanceof Error ? err.message : String(err), variant: 'danger' });
    }
  };

  return (
    <section className="space-y-4">
      <header>
        <h2 className="text-xl font-semibold text-foreground">Sampling</h2>
        <p className="text-sm text-muted-foreground">
          When the server requests LLM sampling, approve or reject below.
        </p>
      </header>
      {requests.length === 0 ? (
        <p className="text-sm italic text-muted-foreground">No pending requests.</p>
      ) : (
        <div className="space-y-3">
          {requests.map((req) => (
            <SamplingRequest
              key={req.requestId}
              requestId={req.requestId}
              params={req.params}
              status={req.status}
              onApprove={handleApprove}
              onReject={handleReject}
            />
          ))}
        </div>
      )}
    </section>
  );
}

export default SamplingTab;
