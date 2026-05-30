// Portions adapted from modelcontextprotocol/inspector (Apache-2.0)
// https://github.com/modelcontextprotocol/inspector

import { useEffect, useState } from 'react';
import { subscribeEvents, type ElicitationRequestEvent } from '../api/sse';
import { respondToServerRequest } from '../api/client';
import { useConnection } from '../state/connection';
import { useToast } from '../lib/hooks/useToast';
import { ElicitationRequest } from '../components/ElicitationRequest';
import type { JsonSchema } from '../utils/schemaUtils';

interface ElicitationParams {
  message?: string;
  requestedSchema?: JsonSchema;
}

interface PendingElicitation {
  requestId: string;
  message?: string;
  schema: JsonSchema;
  status: 'pending' | 'accepted' | 'declined' | 'cancelled';
}

/**
 * Elicitation tab: collects server-initiated `elicitation/create` requests
 * delivered via SSE and renders a schema-driven form for each.
 */
export function ElicitationTab() {
  const { sessionId } = useConnection();
  const { toast } = useToast();
  const [requests, setRequests] = useState<PendingElicitation[]>([]);

  useEffect(() => {
    const sub = subscribeEvents({
      onElicitationRequest: (event: ElicitationRequestEvent) => {
        const params = (event.params as ElicitationParams) ?? {};
        const schema: JsonSchema = params.requestedSchema ?? { type: 'object', properties: {} };
        setRequests((prev) => {
          if (prev.some((r) => r.requestId === event.requestId)) return prev;
          return [
            { requestId: event.requestId, message: params.message, schema, status: 'pending' },
            ...prev,
          ];
        });
      },
    });
    return () => sub.close();
  }, []);

  if (!sessionId) {
    return (
      <section className="space-y-2">
        <h2 className="text-xl font-semibold text-foreground">Elicitation</h2>
        <p className="text-sm italic text-muted-foreground">Not connected.</p>
      </section>
    );
  }

  const respond = async (
    requestId: string,
    body: { result?: unknown; error?: { code: number; message: string } },
    nextStatus: PendingElicitation['status'],
  ) => {
    try {
      await respondToServerRequest(sessionId, requestId, body);
      setRequests((prev) => prev.map((r) => (r.requestId === requestId ? { ...r, status: nextStatus } : r)));
    } catch (err) {
      toast({
        title: 'Response failed',
        description: err instanceof Error ? err.message : String(err),
        variant: 'danger',
      });
    }
  };

  const handleAccept = (requestId: string, content: Record<string, unknown>) =>
    respond(requestId, { result: { action: 'accept', content } }, 'accepted');

  const handleDecline = (requestId: string) =>
    respond(requestId, { result: { action: 'decline' } }, 'declined');

  const handleCancel = (requestId: string) =>
    respond(requestId, { result: { action: 'cancel' } }, 'cancelled');

  return (
    <section className="space-y-4">
      <header>
        <h2 className="text-xl font-semibold text-foreground">Elicitation</h2>
        <p className="text-sm text-muted-foreground">
          When the server requests information from the user, respond below.
        </p>
      </header>
      {requests.length === 0 ? (
        <p className="text-sm italic text-muted-foreground">No pending requests.</p>
      ) : (
        <div className="space-y-3">
          {requests.map((req) => (
            <ElicitationRequest
              key={req.requestId}
              requestId={req.requestId}
              message={req.message}
              schema={req.schema}
              status={req.status}
              onAccept={handleAccept}
              onDecline={handleDecline}
              onCancel={handleCancel}
            />
          ))}
        </div>
      )}
    </section>
  );
}

export default ElicitationTab;
